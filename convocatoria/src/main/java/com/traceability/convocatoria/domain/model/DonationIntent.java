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

    private DonationIntent(String intentId, String fundId, String organizationRef, String campaignRef,
                           String donorRef, long amount, String currency, PaymentMethod paymentMethod,
                           ConfirmationSource confirmationSource, long configurationVersion,
                           String paymentSessionId, String providerEventId, Instant expiresAt,
                           DonationIntentStatus status, Confirmation confirmation) {
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
                null, null, expiresAt, DonationIntentStatus.PENDING, null);
    }

    /** Uso exclusivo de adaptadores de persistencia: no aplica reglas de creación. */
    public static DonationIntent reconstitute(String intentId, String fundId, String organizationRef,
                                              String campaignRef, String donorRef, long amount, String currency,
                                              PaymentMethod paymentMethod, ConfirmationSource confirmationSource,
                                              long configurationVersion, String paymentSessionId,
                                              String providerEventId, Instant expiresAt,
                                              DonationIntentStatus status, Confirmation confirmation) {
        return new DonationIntent(intentId, fundId, organizationRef, campaignRef, donorRef, amount, currency,
                paymentMethod, confirmationSource, configurationVersion, paymentSessionId, providerEventId,
                expiresAt, status, confirmation);
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
