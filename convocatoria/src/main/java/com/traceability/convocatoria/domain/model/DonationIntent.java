package com.traceability.convocatoria.domain.model;

import com.traceability.convocatoria.domain.exception.DonationIntentExpiredException;
import com.traceability.convocatoria.domain.exception.IncompleteConfirmationException;
import com.traceability.convocatoria.domain.exception.InvalidDonationAmountException;

import java.time.Instant;
import java.util.Objects;

/**
 * Intención de donación monetaria (ADR-037 §2.6, §2.6bis; Enmienda §5, N5–N10).
 * {@code fundId} se genera una sola vez al crear y es inmutable. {@code paymentSessionId} y
 * {@code providerEventId} quedan nulos hasta la integración del proveedor (P3). La expiración solo
 * existe para {@code BANK_TRANSFER} y no introduce un estado nuevo (implementation_plan.md §3.5).
 */
public final class DonationIntent {

    private final String intentId;
    private final String fundId;
    private final String organizationRef;
    private final String campaignRef;
    private final String donorRef;
    private final long amount;
    private final String currency;
    private final PaymentMethod paymentMethod;
    private final ConfirmationSource confirmationSource;
    private final long configurationVersion;
    private final String paymentSessionId;
    private final String providerEventId;
    private final Instant expiresAt;
    private DonationIntentStatus status;
    private Confirmation confirmation;
    private final ApplicationTracking applicationTracking;
    private final FundingRejection fundingRejection;
    /** Enmienda 3 de ADR-037, D2 y D6: proveedor de la sesión de pago y credencial de consulta (solo su hash). */
    private final Access access;

    private DonationIntent(String intentId, String fundId, String organizationRef, String campaignRef,
                           String donorRef, long amount, String currency, PaymentMethod paymentMethod,
                           ConfirmationSource confirmationSource, long configurationVersion,
                           String paymentSessionId, String providerEventId, Instant expiresAt,
                           DonationIntentStatus status, Confirmation confirmation,
                           ApplicationTracking applicationTracking, FundingRejection fundingRejection,
                           Access access) {
        this.intentId = Objects.requireNonNull(intentId, "intentId");
        this.fundId = Objects.requireNonNull(fundId, "fundId");
        this.organizationRef = Objects.requireNonNull(organizationRef, "organizationRef");
        this.campaignRef = Objects.requireNonNull(campaignRef, "campaignRef");
        this.donorRef = Objects.requireNonNull(donorRef, "donorRef");
        this.amount = amount;
        this.currency = Objects.requireNonNull(currency, "currency");
        this.paymentMethod = Objects.requireNonNull(paymentMethod, "paymentMethod");
        this.confirmationSource = Objects.requireNonNull(confirmationSource, "confirmationSource");
        this.configurationVersion = configurationVersion;
        this.paymentSessionId = paymentSessionId;
        this.providerEventId = providerEventId;
        this.expiresAt = expiresAt;
        this.status = Objects.requireNonNull(status, "status");
        this.confirmation = confirmation;
        this.applicationTracking = applicationTracking == null ? ApplicationTracking.NONE : applicationTracking;
        this.fundingRejection = fundingRejection;
        this.access = access == null ? Access.NONE : access;
    }

    /**
     * Crea la intención validando las precondiciones de la convocatoria en su versión vigente y registrando
     * esa versión como prueba (resumen §6.8.3). {@code bankTransferExpiresAt} es obligatorio solo para
     * {@code BANK_TRANSFER}; lo calcula el caso de uso con el parámetro de configuración de implementación
     * (Enmienda §5.2 [REQUISITO]).
     */
    public static DonationIntent create(String intentId, String fundId, Convocatoria convocatoria, String donorRef,
                                        long amount, String currency, PaymentMethod paymentMethod,
                                        Instant bankTransferExpiresAt) {
        convocatoria.assertAcceptsDonationIntent(paymentMethod, currency);
        if (amount <= 0) {
            throw new InvalidDonationAmountException("amount must be strictly positive");
        }
        Instant expiresAt = null;
        if (paymentMethod == PaymentMethod.BANK_TRANSFER) {
            expiresAt = Objects.requireNonNull(bankTransferExpiresAt, "bankTransferExpiresAt");
        }
        ConfirmationSource source = paymentMethod == PaymentMethod.GATEWAY
                ? ConfirmationSource.PAYMENT_PROVIDER
                : ConfirmationSource.ORGANIZATION;
        return new DonationIntent(intentId, fundId, convocatoria.getOrganizationRef(), convocatoria.getCampaignRef(),
                donorRef, amount, currency, paymentMethod, source, convocatoria.getConfigurationVersion(),
                null, null, expiresAt, DonationIntentStatus.PENDING, null, ApplicationTracking.NONE, null, null);
    }

    /**
     * Creación con la sesión del proveedor de pago ({@code GATEWAY}) y la credencial de consulta (Enmienda 3 de
     * ADR-037, D2, D3 y D6). {@code paymentProvider} y {@code paymentSessionId} son obligatorios para {@code GATEWAY}
     * y nulos para el resto. El {@code statusToken} nunca llega aquí: solo su hash.
     */
    public static DonationIntent create(String intentId, String fundId, Convocatoria convocatoria, String donorRef,
                                        long amount, String currency, PaymentMethod paymentMethod,
                                        Instant bankTransferExpiresAt, String paymentProvider, String paymentSessionId,
                                        String statusTokenHash, Instant statusTokenExpiresAt) {
        DonationIntent base = create(intentId, fundId, convocatoria, donorRef, amount, currency, paymentMethod,
                bankTransferExpiresAt);
        boolean gateway = paymentMethod == PaymentMethod.GATEWAY;
        if (gateway != (paymentProvider != null) || gateway != (paymentSessionId != null)) {
            throw new IllegalArgumentException("paymentProvider and paymentSessionId are required for GATEWAY only");
        }
        Objects.requireNonNull(statusTokenHash, "statusTokenHash");
        Objects.requireNonNull(statusTokenExpiresAt, "statusTokenExpiresAt");
        return new DonationIntent(base.intentId, base.fundId, base.organizationRef, base.campaignRef, base.donorRef,
                base.amount, base.currency, base.paymentMethod, base.confirmationSource, base.configurationVersion,
                paymentSessionId, null, base.expiresAt, DonationIntentStatus.PENDING, null, ApplicationTracking.NONE,
                null, new Access(paymentProvider, statusTokenHash, statusTokenExpiresAt));
    }

    /** Uso exclusivo de adaptadores de persistencia: no aplica reglas de creación. */
    public static DonationIntent reconstitute(String intentId, String fundId, String organizationRef,
                                              String campaignRef, String donorRef, long amount, String currency,
                                              PaymentMethod paymentMethod, ConfirmationSource confirmationSource,
                                              long configurationVersion, String paymentSessionId,
                                              String providerEventId, Instant expiresAt,
                                              DonationIntentStatus status, Confirmation confirmation) {
        return reconstitute(intentId, fundId, organizationRef, campaignRef, donorRef, amount, currency, paymentMethod,
                confirmationSource, configurationVersion, paymentSessionId, providerEventId, expiresAt, status,
                confirmation, ApplicationTracking.NONE, null);
    }

    /** Uso exclusivo de adaptadores de persistencia, con los datos de operación de ADR-045 y la traza de C2. */
    public static DonationIntent reconstitute(String intentId, String fundId, String organizationRef,
                                              String campaignRef, String donorRef, long amount, String currency,
                                              PaymentMethod paymentMethod, ConfirmationSource confirmationSource,
                                              long configurationVersion, String paymentSessionId,
                                              String providerEventId, Instant expiresAt,
                                              DonationIntentStatus status, Confirmation confirmation,
                                              ApplicationTracking applicationTracking,
                                              FundingRejection fundingRejection) {
        return reconstitute(intentId, fundId, organizationRef, campaignRef, donorRef, amount, currency, paymentMethod,
                confirmationSource, configurationVersion, paymentSessionId, providerEventId, expiresAt, status,
                confirmation, applicationTracking, fundingRejection, Access.NONE);
    }

    /** Uso exclusivo de adaptadores de persistencia, con los datos de la Enmienda 3 de ADR-037. */
    public static DonationIntent reconstitute(String intentId, String fundId, String organizationRef,
                                              String campaignRef, String donorRef, long amount, String currency,
                                              PaymentMethod paymentMethod, ConfirmationSource confirmationSource,
                                              long configurationVersion, String paymentSessionId,
                                              String providerEventId, Instant expiresAt,
                                              DonationIntentStatus status, Confirmation confirmation,
                                              ApplicationTracking applicationTracking,
                                              FundingRejection fundingRejection, Access access) {
        return new DonationIntent(intentId, fundId, organizationRef, campaignRef, donorRef, amount, currency,
                paymentMethod, confirmationSource, configurationVersion, paymentSessionId, providerEventId,
                expiresAt, status, confirmation, applicationTracking, fundingRejection, access);
    }

    /**
     * Transición condicional e idempotente {@code PENDING → CONFIRMED} (Enmienda §5.3). Devuelve si se aplicó;
     * fuera de {@code PENDING} no hace nada. Una {@code BANK_TRANSFER} vencida no se confirma (Enmienda §5.2).
     * Registra quién, cuándo, medio y referencia (N8). En persistencia la barrera es la escritura condicional.
     */
    public boolean confirm(String confirmedBy, Instant confirmedAt, String reference) {
        Confirmation candidate = Confirmation.of(confirmedBy, confirmedAt, paymentMethod, reference);
        if (status != DonationIntentStatus.PENDING) {
            return false;
        }
        if (isExpiredAt(confirmedAt)) {
            throw new DonationIntentExpiredException("DonationIntent " + intentId + " expired at " + expiresAt);
        }
        this.status = DonationIntentStatus.CONFIRMED;
        this.confirmation = candidate;
        return true;
    }

    /** Solo {@code BANK_TRANSFER} vence; vencida cuando {@code instant ≥ expiresAt}. */
    public boolean isExpiredAt(Instant instant) {
        return expiresAt != null && !instant.isBefore(expiresAt);
    }

    public String getIntentId() { return intentId; }
    public String getFundId() { return fundId; }
    public String getOrganizationRef() { return organizationRef; }
    public String getCampaignRef() { return campaignRef; }
    public String getDonorRef() { return donorRef; }
    public long getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public PaymentMethod getPaymentMethod() { return paymentMethod; }
    public ConfirmationSource getConfirmationSource() { return confirmationSource; }
    public long getConfigurationVersion() { return configurationVersion; }
    public String getPaymentSessionId() { return paymentSessionId; }
    public String getProviderEventId() { return providerEventId; }
    public Instant getExpiresAt() { return expiresAt; }
    public DonationIntentStatus getStatus() { return status; }
    public Confirmation getConfirmation() { return confirmation; }
    public ApplicationTracking getApplicationTracking() { return applicationTracking; }
    public FundingRejection getFundingRejection() { return fundingRejection; }
    public Access getAccess() { return access; }
    public String getPaymentProvider() { return access.paymentProvider(); }

    /**
     * Enmienda 3 de ADR-037: {@code paymentProvider} (D2, inmutable, fijado al crear una {@code GATEWAY}) y la
     * credencial de consulta {@code statusToken} (D6), de la que solo se guarda el hash SHA-256 y la caducidad.
     */
    public record Access(String paymentProvider, String statusTokenHash, Instant statusTokenExpiresAt) {

        public static final Access NONE = new Access(null, null, null);
    }

    /**
     * Datos de operación de la aplicación de fondos (ADR-045 §2.3, §2.5). No son estado de dominio: {@code CONFIRMED}
     * conserva su significado (F-1) y la cuarentena no es un estado. {@code fundsAppliedAt} se escribe en la misma
     * transacción que el reclamo {@code APPLY_FUNDS}; la barrera sigue siendo la única fuente de verdad de la
     * idempotencia. {@code lastError} es solo el nombre de la clase de la excepción (sin mensaje ni PII).
     */
    public record ApplicationTracking(Instant fundsAppliedAt, int attempts, Instant firstAttemptAt,
                                      Instant lastAttemptAt, String lastError, boolean quarantined) {

        public static final ApplicationTracking NONE = new ApplicationTracking(null, 0, null, null, null, false);
    }

    /** Motivo y fecha del paso a {@code FUNDING_REJECTED}, escritos en la misma transacción (Enmienda 2 §4, C2). */
    public record FundingRejection(Instant rejectedAt, String reason) {
    }

    /** Datos de confirmación: quién, cuándo, medio y referencia/evidencia (N8, Enmienda §5.2). */
    public record Confirmation(String confirmedBy, Instant confirmedAt, PaymentMethod paymentMethod, String reference) {

        public static Confirmation of(String confirmedBy, Instant confirmedAt, PaymentMethod paymentMethod,
                                      String reference) {
            if (confirmedBy == null || confirmedBy.isBlank() || confirmedAt == null || paymentMethod == null
                    || reference == null || reference.isBlank()) {
                throw new IncompleteConfirmationException(
                        "A confirmation records who, when, payment method and reference (N8)");
            }
            return new Confirmation(confirmedBy, confirmedAt, paymentMethod, reference);
        }
    }
}
