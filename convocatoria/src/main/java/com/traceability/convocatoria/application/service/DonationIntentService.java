package com.traceability.convocatoria.application.service;

import com.traceability.convocatoria.application.audit.ConvocatoriaAuditAction;
import com.traceability.convocatoria.application.audit.ConvocatoriaAuditEntry;
import com.traceability.convocatoria.application.authorization.ConvocatoriaActor;
import com.traceability.convocatoria.application.authorization.ConvocatoriaAuthorizationPolicy;
import com.traceability.convocatoria.application.command.CampaignReference;
import com.traceability.convocatoria.application.command.ConfirmDonationIntentCommand;
import com.traceability.convocatoria.application.command.CreateDonationIntentCommand;
import com.traceability.convocatoria.application.command.CreateDonationIntentResult;
import com.traceability.convocatoria.application.idempotency.CommandType;
import com.traceability.convocatoria.application.idempotency.IdempotentCommandExecutor;
import com.traceability.convocatoria.application.port.out.ConvocatoriaAuditLogPort;
import com.traceability.convocatoria.application.port.out.ConvocatoriaRepositoryPort;
import com.traceability.convocatoria.application.port.out.DonationIntentRepositoryPort;
import com.traceability.convocatoria.application.port.out.OrganizationVerificationPort;
import com.traceability.convocatoria.domain.exception.CampaignNotFoundException;
import com.traceability.convocatoria.domain.exception.DonationIntentExpiredException;
import com.traceability.convocatoria.domain.exception.DonationIntentNotFoundException;
import com.traceability.convocatoria.domain.exception.GatewayIntentManualConfirmationNotAllowedException;
import com.traceability.convocatoria.domain.model.ConfirmationSource;
import com.traceability.convocatoria.domain.model.Convocatoria;
import com.traceability.convocatoria.domain.model.DonationIntent;
import com.traceability.convocatoria.domain.model.DonationIntentStatus;
import com.traceability.convocatoria.domain.model.OrganizationVerification;
import com.traceability.convocatoria.domain.model.PaymentMethod;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * {@code DonationIntent}: resolución de {@code publicCode}, creación idempotente y transición
 * {@code PENDING → CONFIRMED} (ADR-037 §2.6, §2.6bis; Enmienda §5; implementation_plan.md §7.2, §7.3, §9).
 * La duración del vencimiento de {@code BANK_TRANSFER} es un parámetro de configuración de implementación
 * ({@code convocatoria.donation-intent.bank-transfer-expiration}, sin valor por defecto; Enmienda §5.2 [REQUISITO]).
 */
@Service
public class DonationIntentService {

    private final IdempotentCommandExecutor executor;
    private final OrganizationVerificationPort organizationVerificationPort;
    private final ConvocatoriaAuthorizationPolicy authorizationPolicy;
    private final ConvocatoriaRepositoryPort convocatorias;
    private final DonationIntentRepositoryPort donationIntents;
    private final ConvocatoriaAuditLogPort auditLog;
    private final Duration bankTransferExpiration;
    private final Clock clock;

    public DonationIntentService(IdempotentCommandExecutor executor,
                                 OrganizationVerificationPort organizationVerificationPort,
                                 ConvocatoriaAuthorizationPolicy authorizationPolicy,
                                 ConvocatoriaRepositoryPort convocatorias,
                                 DonationIntentRepositoryPort donationIntents,
                                 ConvocatoriaAuditLogPort auditLog,
                                 @Value("${convocatoria.donation-intent.bank-transfer-expiration}") Duration bankTransferExpiration,
                                 ObjectProvider<Clock> clock) {
        this.executor = executor;
        this.organizationVerificationPort = organizationVerificationPort;
        this.authorizationPolicy = authorizationPolicy;
        this.convocatorias = convocatorias;
        this.donationIntents = donationIntents;
        this.auditLog = auditLog;
        this.bankTransferExpiration = Objects.requireNonNull(bankTransferExpiration, "bankTransferExpiration");
        this.clock = clock.getIfAvailable(Clock::systemUTC);
    }

    /** Resolución interna {@code publicCode → campaignRef + organizationRef} (ADR-037 §2.6). */
    public CampaignReference resolvePublicCode(String publicCode) {
        Convocatoria convocatoria = loadByPublicCode(publicCode);
        return new CampaignReference(convocatoria.getCampaignRef(), convocatoria.getOrganizationRef());
    }

    /**
     * Crea la intención (Enmienda §5.1; X1; R1; P5; P7): convocatoria {@code OPEN}, organización {@code VERIFIED},
     * {@code MONETARY} y medio aceptados en la versión vigente, moneda igual; sin comprobar fechas. Idempotente por
     * {@code commandId}: un duplicado devuelve {@code intentId} y {@code fundId} originales (ID).
     */
    public CreateDonationIntentResult createDonationIntent(CreateDonationIntentCommand command) {
        Map<String, String> result = executor.execute(command.commandId(), CommandType.CREATE_DONATION_INTENT, () -> {
            Convocatoria convocatoria = loadByPublicCode(command.publicCode());
            OrganizationVerification.requireVerified(convocatoria.getOrganizationRef(),
                    organizationVerificationPort.isVerified(convocatoria.getOrganizationRef()));
            Instant now = clock.instant();
            Instant expiresAt = command.paymentMethod() == PaymentMethod.BANK_TRANSFER
                    ? now.plus(bankTransferExpiration) : null;
            DonationIntent intent = DonationIntent.create(UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                    convocatoria, command.donorRef(), command.amount(), command.currency(), command.paymentMethod(),
                    expiresAt);
            donationIntents.insert(intent);
            auditLog.append(new ConvocatoriaAuditEntry(UUID.randomUUID().toString(),
                    ConvocatoriaAuditAction.DONATION_INTENT_CREATED, convocatoria.getCampaignRef(), command.donorRef(),
                    intent.getIntentId(), false, command.commandId(), now, Map.of(
                            "fundId", intent.getFundId(),
                            "paymentMethod", intent.getPaymentMethod().name(),
                            "confirmationSource", intent.getConfirmationSource().name(),
                            "configurationVersion", String.valueOf(intent.getConfigurationVersion()))));
            return Map.of("intentId", intent.getIntentId(), "fundId", intent.getFundId());
        });
        return new CreateDonationIntentResult(result.get("intentId"), result.get("fundId"));
    }

    /**
     * Confirmación manual, transición condicional e idempotente {@code PENDING → CONFIRMED} (Enmienda §5.2–§5.3; N8).
     * Solo un {@code ADMINISTRATOR} de la organización de la intención puede ejecutarla, comprobado con
     * {@link ConvocatoriaAuthorizationPolicy#requireAdministratorOf}; una intención de pasarela ({@code GATEWAY}) se
     * rechaza con {@link GatewayIntentManualConfirmationNotAllowedException}. Las dos comprobaciones van antes de
     * cualquier escritura (ADR-037 Enmienda 2 §3.1). Confirma la intención y nada más: no crea {@code Fund},
     * no aplica fondos ni toca el ledger; {@code CONFIRMED} no significa fondos aplicados (F-1, F-2). Devuelve si la
     * transición se aplicó; una {@code BANK_TRANSFER} vencida se rechaza con {@link DonationIntentExpiredException}.
     * Propagación {@code SUPPORTS}: se une a una transacción existente; fuera de ella es una escritura atómica de un
     * solo documento.
     */
    @Transactional(propagation = Propagation.SUPPORTS)
    public boolean confirmDonationIntent(ConfirmDonationIntentCommand command) {
        DonationIntent intent = donationIntents.findById(command.intentId())
                .orElseThrow(() -> new DonationIntentNotFoundException("DonationIntent " + command.intentId() + " not found"));
        ConvocatoriaActor actor = authorizationPolicy.requireAdministratorOf(command.actorAccountId(),
                intent.getOrganizationRef());
        if (intent.getPaymentMethod() == PaymentMethod.GATEWAY
                || intent.getConfirmationSource() == ConfirmationSource.PAYMENT_PROVIDER) {
            throw new GatewayIntentManualConfirmationNotAllowedException("DonationIntent " + command.intentId()
                    + " comes from a payment gateway and cannot be confirmed manually");
        }
        DonationIntent.Confirmation confirmation = DonationIntent.Confirmation.of(actor.accountId(), clock.instant(),
                intent.getPaymentMethod(), command.reference());
        if (donationIntents.confirmIfPending(command.intentId(), confirmation)) {
            return true;
        }
        DonationIntent current = donationIntents.findById(command.intentId()).orElseThrow(
                () -> new DonationIntentNotFoundException("DonationIntent " + command.intentId() + " not found"));
        if (current.getStatus() == DonationIntentStatus.PENDING && current.isExpiredAt(confirmation.confirmedAt())) {
            throw new DonationIntentExpiredException("DonationIntent " + command.intentId() + " expired at "
                    + current.getExpiresAt());
        }
        return false;
    }

    private Convocatoria loadByPublicCode(String publicCode) {
        return convocatorias.findByPublicCode(publicCode)
                .orElseThrow(() -> new CampaignNotFoundException("No campaign with publicCode " + publicCode));
    }
}
