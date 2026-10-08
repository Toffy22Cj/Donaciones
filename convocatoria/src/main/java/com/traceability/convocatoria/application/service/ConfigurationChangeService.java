package com.traceability.convocatoria.application.service;

import com.traceability.convocatoria.application.audit.ConvocatoriaAuditAction;
import com.traceability.convocatoria.application.audit.ConvocatoriaAuditEntry;
import com.traceability.convocatoria.application.authorization.ConvocatoriaActor;
import com.traceability.convocatoria.application.authorization.ConvocatoriaAuthorizationPolicy;
import com.traceability.convocatoria.application.idempotency.CommandType;
import com.traceability.convocatoria.application.idempotency.IdempotentCommandExecutor;
import com.traceability.convocatoria.application.port.out.CampaignFundingLedgerRepositoryPort;
import com.traceability.convocatoria.application.port.out.ConfigurationChangeRequestRepositoryPort;
import com.traceability.convocatoria.application.port.out.ConvocatoriaAuditLogPort;
import com.traceability.convocatoria.application.port.out.ConvocatoriaRepositoryPort;
import com.traceability.convocatoria.application.port.out.DonationIntentRepositoryPort;
import com.traceability.convocatoria.domain.exception.CampaignNotFoundException;
import com.traceability.convocatoria.domain.exception.ConfigurationChangeRequestNotFoundException;
import com.traceability.convocatoria.domain.exception.ConfigurationChangeRequestNotPendingException;
import com.traceability.convocatoria.domain.exception.ConfigurationVersionConflictException;
import com.traceability.convocatoria.domain.exception.MonetaryRemovalNotAllowedException;
import com.traceability.convocatoria.domain.exception.SelfApprovalNotAllowedException;
import com.traceability.convocatoria.domain.model.CampaignFundingLedger;
import com.traceability.convocatoria.domain.model.ConfigurationChangeRequest;
import com.traceability.convocatoria.domain.model.ConfigurationChangeRequestStatus;
import com.traceability.convocatoria.domain.model.Convocatoria;
import com.traceability.convocatoria.domain.model.ConvocatoriaConfiguration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Cambio de configuración con solicitud y aprobación (Enmienda 1 de ADR-037, §3.2; Enmienda 4, DD-73). Cada comando
 * es idempotente por {@code commandId}, se autoriza antes del reclamo y se ejecuta en una transacción con su
 * auditoría.
 * <ul>
 *   <li>pedir: {@code ADMINISTRATOR}; la propuesta se valida contra la versión vigente sin aplicarla; una sola
 *   pendiente por convocatoria;</li>
 *   <li>aprobar: otro {@code ADMINISTRATOR} o el {@code REPRESENTATIVE}, nunca el solicitante; si la configuración
 *   avanzó, falla y la solicitud sigue pendiente; nunca quita {@code MONETARY} con intenciones (D4);</li>
 *   <li>rechazar: los mismos que aprueban, o el propio solicitante (retirarla).</li>
 * </ul>
 * Sin aprobador válido la solicitud queda pendiente: el cambio se bloquea (PaxFide nunca aprueba).
 */
@Service
public class ConfigurationChangeService {

    public static final int LIST_LIMIT = 50;

    private final IdempotentCommandExecutor executor;
    private final ConvocatoriaAuthorizationPolicy authorization;
    private final ConvocatoriaRepositoryPort convocatorias;
    private final ConfigurationChangeRequestRepositoryPort requests;
    private final CampaignFundingLedgerRepositoryPort ledgers;
    private final DonationIntentRepositoryPort donationIntents;
    private final ConvocatoriaAuditLogPort auditLog;
    private final Clock clock;

    public ConfigurationChangeService(IdempotentCommandExecutor executor, ConvocatoriaAuthorizationPolicy authorization,
                                      ConvocatoriaRepositoryPort convocatorias,
                                      ConfigurationChangeRequestRepositoryPort requests,
                                      CampaignFundingLedgerRepositoryPort ledgers,
                                      DonationIntentRepositoryPort donationIntents, ConvocatoriaAuditLogPort auditLog,
                                      ObjectProvider<Clock> clock) {
        this.executor = executor;
        this.authorization = authorization;
        this.convocatorias = convocatorias;
        this.requests = requests;
        this.ledgers = ledgers;
        this.donationIntents = donationIntents;
        this.auditLog = auditLog;
        this.clock = clock.getIfAvailable(Clock::systemUTC);
    }

    public record Requested(String requestId, long baseConfigurationVersion) {}

    public record Approved(String requestId, long configurationVersion) {}

    public Requested request(String commandId, String actorAccountId, String campaignRef,
                             long expectedConfigurationVersion, ConvocatoriaConfiguration proposed) {
        Convocatoria loaded = load(campaignRef);
        ConvocatoriaActor actor = authorization.requireAdministratorOf(actorAccountId, loaded.getOrganizationRef());
        Map<String, String> result = executor.execute(commandId, CommandType.REQUEST_CONFIGURATION_CHANGE, () -> {
            Convocatoria convocatoria = load(campaignRef);
            requireVersion(convocatoria, expectedConfigurationVersion);
            // valida la propuesta con las reglas de reconfigure, sin guardar nada (cerrada, meta, moneda, MONETARY)
            Convocatoria simulated = load(campaignRef);
            requireNoMonetaryRemoval(campaignRef, simulated.reconfigure(proposed));
            ConfigurationChangeRequest request = ConfigurationChangeRequest.pending(UUID.randomUUID().toString(),
                    convocatoria, proposed, actor.accountId(), clock.instant());
            requests.insert(request);
            audit(ConvocatoriaAuditAction.CONFIGURATION_CHANGE_REQUESTED, campaignRef, actor, commandId, Map.of(
                    "requestId", request.requestId(),
                    "baseConfigurationVersion", String.valueOf(request.baseConfigurationVersion())));
            return Map.of("requestId", request.requestId(),
                    "baseConfigurationVersion", String.valueOf(request.baseConfigurationVersion()));
        });
        return new Requested(result.get("requestId"), Long.parseLong(result.get("baseConfigurationVersion")));
    }

    public Approved approve(String commandId, String actorAccountId, String campaignRef, String requestId) {
        Convocatoria loaded = load(campaignRef);
        ConvocatoriaActor actor = authorization.requireApproverOf(actorAccountId, loaded.getOrganizationRef());
        ConfigurationChangeRequest found = requestOf(campaignRef, requestId);
        if (found.requestedBy().equals(actor.accountId())) {
            throw new SelfApprovalNotAllowedException("The requester cannot approve their own configuration change");
        }
        Map<String, String> result = executor.execute(commandId, CommandType.APPROVE_CONFIGURATION_CHANGE, () -> {
            ConfigurationChangeRequest request = requestOf(campaignRef, requestId);
            if (request.status() != ConfigurationChangeRequestStatus.PENDING) {
                throw new ConfigurationChangeRequestNotPendingException("Request " + requestId + " is " + request.status());
            }
            Convocatoria convocatoria = load(campaignRef);
            // la aprobación falla si la configuración avanzó (Enmienda 1, §3.2); la solicitud sigue PENDING
            requireVersion(convocatoria, request.baseConfigurationVersion());
            Convocatoria.MonetaryTransition transition = convocatoria.reconfigure(request.proposedConfiguration());
            requireNoMonetaryRemoval(campaignRef, transition);
            if (!convocatorias.updateConfigurationIfVersion(convocatoria, request.baseConfigurationVersion())) {
                throw new ConfigurationVersionConflictException("Configuration of " + campaignRef
                        + " changed concurrently from version " + request.baseConfigurationVersion());
            }
            switch (transition) {
                case ADDED -> ledgers.insert(CampaignFundingLedger.open(campaignRef, convocatoria.getConfiguration()));
                case REMOVED -> ledgers.deleteByCampaignRef(campaignRef); // solo sin intenciones (D4)
                case UNCHANGED -> { }
            }
            Instant now = clock.instant();
            if (!requests.markApprovedIfPending(requestId, actor.accountId(), now, convocatoria.getConfigurationVersion())) {
                throw new ConfigurationChangeRequestNotPendingException("Request " + requestId + " is no longer PENDING");
            }
            audit(ConvocatoriaAuditAction.CONFIGURATION_CHANGE_APPROVED, campaignRef, actor, commandId, Map.of(
                    "requestId", requestId,
                    "previousConfigurationVersion", String.valueOf(request.baseConfigurationVersion()),
                    "configurationVersion", String.valueOf(convocatoria.getConfigurationVersion()),
                    "monetaryTransition", transition.name()));
            return Map.of("requestId", requestId,
                    "configurationVersion", String.valueOf(convocatoria.getConfigurationVersion()));
        });
        return new Approved(result.get("requestId"), Long.parseLong(result.get("configurationVersion")));
    }

    public void reject(String commandId, String actorAccountId, String campaignRef, String requestId) {
        Convocatoria loaded = load(campaignRef);
        // el solicitante es ADMINISTRATOR, así que también pasa: puede retirar la suya
        ConvocatoriaActor decidedBy = authorization.requireApproverOf(actorAccountId, loaded.getOrganizationRef());
        ConfigurationChangeRequest found = requestOf(campaignRef, requestId);
        executor.execute(commandId, CommandType.REJECT_CONFIGURATION_CHANGE, () -> {
            if (!requests.markRejectedIfPending(requestId, decidedBy.accountId(), clock.instant())) {
                throw new ConfigurationChangeRequestNotPendingException("Request " + requestId + " is no longer PENDING");
            }
            audit(ConvocatoriaAuditAction.CONFIGURATION_CHANGE_REJECTED, campaignRef, decidedBy, commandId, Map.of(
                    "requestId", requestId, "withdrawnByRequester",
                    String.valueOf(found.requestedBy().equals(decidedBy.accountId()))));
            return Map.of("requestId", requestId);
        });
    }

    /** Las solicitudes de la convocatoria, más recientes primero ({@code ADMINISTRATOR} o {@code REPRESENTATIVE}). */
    public List<ConfigurationChangeRequest> list(String actorAccountId, String campaignRef) {
        Convocatoria loaded = load(campaignRef);
        authorization.requireApproverOf(actorAccountId, loaded.getOrganizationRef());
        return requests.findByCampaignRef(campaignRef, LIST_LIMIT);
    }

    private ConfigurationChangeRequest requestOf(String campaignRef, String requestId) {
        return requests.findById(requestId).filter(r -> r.campaignRef().equals(campaignRef))
                .orElseThrow(() -> new ConfigurationChangeRequestNotFoundException("Request not found in campaign"));
    }

    private Convocatoria load(String campaignRef) {
        return convocatorias.findByCampaignRef(campaignRef)
                .orElseThrow(() -> new CampaignNotFoundException("Campaign " + campaignRef + " not found"));
    }

    private static void requireVersion(Convocatoria convocatoria, long expected) {
        if (convocatoria.getConfigurationVersion() != expected) {
            throw new ConfigurationVersionConflictException("Expected configuration version " + expected
                    + " but found " + convocatoria.getConfigurationVersion());
        }
    }

    private void requireNoMonetaryRemoval(String campaignRef, Convocatoria.MonetaryTransition transition) {
        if (transition == Convocatoria.MonetaryTransition.REMOVED && donationIntents.existsByCampaignRef(campaignRef)) {
            throw new MonetaryRemovalNotAllowedException("Campaign " + campaignRef + " already has donation intents");
        }
    }

    private void audit(ConvocatoriaAuditAction action, String campaignRef, ConvocatoriaActor actor, String commandId,
                       Map<String, String> details) {
        auditLog.append(new ConvocatoriaAuditEntry(UUID.randomUUID().toString(), action, campaignRef, actor.accountId(),
                null, false, commandId, clock.instant(), details));
    }
}
