package com.traceability.convocatoria.application.service;

import com.traceability.convocatoria.application.audit.ConvocatoriaAuditAction;
import com.traceability.convocatoria.application.audit.ConvocatoriaAuditEntry;
import com.traceability.convocatoria.application.authorization.ConvocatoriaActor;
import com.traceability.convocatoria.application.authorization.ConvocatoriaAuthorizationPolicy;
import com.traceability.convocatoria.application.command.CloseConvocatoriaCommand;
import com.traceability.convocatoria.application.command.CloseConvocatoriaResult;
import com.traceability.convocatoria.application.command.CreateConvocatoriaCommand;
import com.traceability.convocatoria.application.command.CreateConvocatoriaResult;
import com.traceability.convocatoria.application.command.EditConfigurationCommand;
import com.traceability.convocatoria.application.command.EditConfigurationResult;
import com.traceability.convocatoria.application.idempotency.CommandType;
import com.traceability.convocatoria.application.idempotency.IdempotentCommandExecutor;
import com.traceability.convocatoria.application.port.out.CampaignAssignmentRepositoryPort;
import com.traceability.convocatoria.application.port.out.CampaignFundingLedgerRepositoryPort;
import com.traceability.convocatoria.application.port.out.ConvocatoriaAuditLogPort;
import com.traceability.convocatoria.application.port.out.ConvocatoriaRepositoryPort;
import com.traceability.convocatoria.application.port.out.DonationIntentRepositoryPort;
import com.traceability.convocatoria.application.port.out.OrganizationVerificationPort;
import com.traceability.convocatoria.domain.exception.CampaignAlreadyClosedException;
import com.traceability.convocatoria.domain.exception.CampaignAlreadyHasDonationsException;
import com.traceability.convocatoria.domain.exception.CampaignNotFoundException;
import com.traceability.convocatoria.domain.exception.ConfigurationChangeOnClosedCampaignException;
import com.traceability.convocatoria.domain.exception.ConfigurationVersionConflictException;
import com.traceability.convocatoria.domain.model.CampaignFundingLedger;
import com.traceability.convocatoria.domain.model.Convocatoria;
import com.traceability.convocatoria.domain.model.ConvocatoriaStatus;
import com.traceability.convocatoria.domain.model.OrganizationVerification;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;

/**
 * Ciclo de vida de la convocatoria: crear (con ledger si {@code MONETARY}), edición directa de configuración y
 * cierre manual (ADR-037 §2.1, §2.2, §5; Enmienda §3.1, §3.2, §3.4, §3.5; implementation_plan.md §4.4, §5, §10).
 * Cada comando es idempotente por {@code commandId} y se ejecuta en una sola transacción junto con su audit log
 * y el registro de comandos procesados. La autorización se evalúa antes del reclamo, para que un duplicado nunca
 * devuelva el resultado original a un actor no autorizado.
 */
@Service
public class ConvocatoriaLifecycleService {

    private static final char[] PUBLIC_CODE_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    /**
     * Longitud de {@code publicCode}: 26 caracteres de un alfabeto de 32 = 130 bits aleatorios (deuda D-3; ≥128 bits,
     * Q-CV01-10 de la ficha CV-01). Longitud y alfabeto no son contrato: el cliente lo trata como opaco.
     */
    public static final int PUBLIC_CODE_LENGTH = 26;
    /** Forma de un {@code publicCode} emitido por este servicio: CV-07 no consulta nada que no la tenga. */
    public static final java.util.regex.Pattern PUBLIC_CODE_FORMAT =
            java.util.regex.Pattern.compile("^[0-9A-HJKMNP-TV-Z]{" + PUBLIC_CODE_LENGTH + "}$");

    private final IdempotentCommandExecutor executor;
    private final ConvocatoriaAuthorizationPolicy authorizationPolicy;
    private final OrganizationVerificationPort organizationVerificationPort;
    private final ConvocatoriaRepositoryPort convocatorias;
    private final CampaignFundingLedgerRepositoryPort ledgers;
    private final DonationIntentRepositoryPort donationIntents;
    private final CampaignAssignmentRepositoryPort assignments;
    private final ConvocatoriaAuditLogPort auditLog;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public ConvocatoriaLifecycleService(IdempotentCommandExecutor executor,
                                       ConvocatoriaAuthorizationPolicy authorizationPolicy,
                                       OrganizationVerificationPort organizationVerificationPort,
                                       ConvocatoriaRepositoryPort convocatorias,
                                       CampaignFundingLedgerRepositoryPort ledgers,
                                       DonationIntentRepositoryPort donationIntents,
                                       CampaignAssignmentRepositoryPort assignments,
                                       ConvocatoriaAuditLogPort auditLog,
                                       ObjectProvider<Clock> clock) {
        this.executor = executor;
        this.authorizationPolicy = authorizationPolicy;
        this.organizationVerificationPort = organizationVerificationPort;
        this.convocatorias = convocatorias;
        this.ledgers = ledgers;
        this.donationIntents = donationIntents;
        this.assignments = assignments;
        this.auditLog = auditLog;
        this.clock = clock.getIfAvailable(Clock::systemUTC);
    }

    /** Crear convocatoria: {@code ADMINISTRATOR} de una organización {@code VERIFIED} (ADR-037 §5; X1). */
    public CreateConvocatoriaResult createConvocatoria(CreateConvocatoriaCommand command) {
        ConvocatoriaActor actor = authorizationPolicy.requireAdministratorOf(command.actorAccountId(),
                command.organizationRef());
        Map<String, String> result = executor.execute(command.commandId(), CommandType.CREATE_CONVOCATORIA, () -> {
            Convocatoria.requireDatesNotInPast(command.startDate(), command.endDate(), clock.instant());
            OrganizationVerification.requireVerified(command.organizationRef(),
                    organizationVerificationPort.isVerified(command.organizationRef()));
            Convocatoria convocatoria = Convocatoria.create(UUID.randomUUID().toString(), command.organizationRef(),
                    newPublicCode(), command.title(), command.description(), command.visibility(),
                    command.startDate(), command.endDate(), command.configuration());
            convocatorias.insert(convocatoria);
            if (convocatoria.getConfiguration().acceptsMonetary()) {
                ledgers.insert(CampaignFundingLedger.open(convocatoria.getCampaignRef(), convocatoria.getConfiguration()));
            }
            audit(ConvocatoriaAuditAction.CONVOCATORIA_CREATED, convocatoria.getCampaignRef(), actor, null, false,
                    command.commandId(), Map.of("publicCode", convocatoria.getPublicCode(),
                            "configurationVersion", String.valueOf(convocatoria.getConfigurationVersion())));
            return Map.of("campaignRef", convocatoria.getCampaignRef(), "publicCode", convocatoria.getPublicCode());
        });
        return new CreateConvocatoriaResult(result.get("campaignRef"), result.get("publicCode"));
    }

    /**
     * Edición directa: solo sin {@code DonationIntent} de la convocatoria (H1), escritura condicional sobre la
     * versión esperada; crea el ledger si se añade {@code MONETARY} y lo retira si se quita (N2; G1).
     */
    public EditConfigurationResult editConfiguration(EditConfigurationCommand command) {
        Convocatoria loaded = load(command.campaignRef());
        ConvocatoriaActor actor = authorizationPolicy.requireAdministratorOf(command.actorAccountId(),
                loaded.getOrganizationRef());
        Map<String, String> result = executor.execute(command.commandId(), CommandType.EDIT_CONFIGURATION, () -> {
            Convocatoria convocatoria = load(command.campaignRef());
            if (convocatoria.getStatus() == ConvocatoriaStatus.CLOSED) {
                throw new ConfigurationChangeOnClosedCampaignException("Campaign " + command.campaignRef() + " is CLOSED");
            }
            if (convocatoria.getConfigurationVersion() != command.expectedConfigurationVersion()) {
                throw new ConfigurationVersionConflictException("Expected configuration version "
                        + command.expectedConfigurationVersion() + " but found " + convocatoria.getConfigurationVersion());
            }
            if (donationIntents.existsByCampaignRef(command.campaignRef())) {
                throw new CampaignAlreadyHasDonationsException(
                        "Campaign " + command.campaignRef() + " already has donations: direct edit is not allowed");
            }
            Convocatoria.MonetaryTransition transition = convocatoria.reconfigure(command.newConfiguration());
            if (!convocatorias.updateConfigurationIfVersion(convocatoria, command.expectedConfigurationVersion())) {
                throw new ConfigurationVersionConflictException("Configuration of " + command.campaignRef()
                        + " changed concurrently from version " + command.expectedConfigurationVersion());
            }
            switch (transition) {
                case ADDED -> ledgers.insert(CampaignFundingLedger.open(convocatoria.getCampaignRef(),
                        convocatoria.getConfiguration()));
                case REMOVED -> ledgers.deleteByCampaignRef(convocatoria.getCampaignRef());
                case UNCHANGED -> { }
            }
            audit(ConvocatoriaAuditAction.CONFIGURATION_EDITED, convocatoria.getCampaignRef(), actor, null, false,
                    command.commandId(), Map.of(
                            "previousConfigurationVersion", String.valueOf(command.expectedConfigurationVersion()),
                            "configurationVersion", String.valueOf(convocatoria.getConfigurationVersion()),
                            "monetaryTransition", transition.name()));
            return Map.of("campaignRef", convocatoria.getCampaignRef(),
                    "configurationVersion", String.valueOf(convocatoria.getConfigurationVersion()));
        });
        return new EditConfigurationResult(result.get("campaignRef"), Long.parseLong(result.get("configurationVersion")));
    }

    /** Cierre manual {@code OPEN → CLOSED}, exclusivo de {@code ADMINISTRATOR} (Enmienda §3.4; implementation_plan.md §10). */
    public CloseConvocatoriaResult closeConvocatoria(CloseConvocatoriaCommand command) {
        Convocatoria loaded = load(command.campaignRef());
        ConvocatoriaActor actor = authorizationPolicy.requireAdministratorOf(command.actorAccountId(),
                loaded.getOrganizationRef());
        Map<String, String> result = executor.execute(command.commandId(), CommandType.CLOSE_CONVOCATORIA, () -> {
            Convocatoria convocatoria = load(command.campaignRef());
            convocatoria.close();
            if (!convocatorias.closeIfOpen(command.campaignRef())) {
                throw new CampaignAlreadyClosedException("Campaign " + command.campaignRef() + " is already CLOSED");
            }
            // D-06 (Carlos, 2026-10-08): en la misma transacción, las asignaciones activas pasan a historial
            long historical = assignments.markHistoricalByCampaignRef(command.campaignRef(), clock.instant());
            audit(ConvocatoriaAuditAction.CONVOCATORIA_CLOSED, command.campaignRef(), actor, null, false,
                    command.commandId(), Map.of("historicalAssignments", String.valueOf(historical)));
            return Map.of("campaignRef", command.campaignRef());
        });
        return new CloseConvocatoriaResult(result.get("campaignRef"));
    }

    private Convocatoria load(String campaignRef) {
        return convocatorias.findByCampaignRef(campaignRef)
                .orElseThrow(() -> new CampaignNotFoundException("Campaign " + campaignRef + " not found"));
    }

    private void audit(ConvocatoriaAuditAction action, String campaignRef, ConvocatoriaActor actor, String targetRef,
                       boolean selfAssigned, String commandId, Map<String, String> details) {
        auditLog.append(new ConvocatoriaAuditEntry(UUID.randomUUID().toString(), action, campaignRef,
                actor.accountId(), targetRef, selfAssigned, commandId, clock.instant(), details));
    }

    private String newPublicCode() {
        char[] code = new char[PUBLIC_CODE_LENGTH];
        for (int i = 0; i < code.length; i++) {
            code[i] = PUBLIC_CODE_ALPHABET[random.nextInt(PUBLIC_CODE_ALPHABET.length)];
        }
        return new String(code);
    }
}
