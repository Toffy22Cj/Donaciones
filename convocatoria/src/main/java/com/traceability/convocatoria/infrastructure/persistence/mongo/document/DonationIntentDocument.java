package com.traceability.convocatoria.infrastructure.persistence.mongo.document;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Documento de {@code DonationIntent} (ADR-037 §2.6; Enmienda §5.1): {@code _id = intentId} (único),
 * {@code fundId} único y {@code paymentSessionId} único parcial (solo si existe). Los campos nulos no se escriben.
 */
@Document(collection = DonationIntentDocument.COLLECTION)
@CompoundIndex(name = DonationIntentDocument.PENDING_APPLICATION_INDEX,
        def = "{'status': 1, 'fundsAppliedAt': 1, 'applicationAttempts': 1, 'lastApplicationAttemptAt': 1, '_id': 1}",
        partialFilter = "{'status': 'CONFIRMED'}")
@CompoundIndex(name = DonationIntentDocument.PROVIDER_EVENT_INDEX, def = "{'paymentProvider': 1, 'providerEventId': 1}",
        unique = true, partialFilter = "{'providerEventId': {$type: 'string'}}")
public class DonationIntentDocument {

    public static final String COLLECTION = "donation_intents";
    /**
     * Índice parcial de la consulta de recuperables (ADR-045 §2.5). MongoDB no admite "campo ausente" en un filtro
     * parcial, así que el filtro es {@code status = CONFIRMED} y la consulta busca {@code fundsAppliedAt = null}.
     */
    public static final String PENDING_APPLICATION_INDEX = "ix_pending_application";
    /** Enmienda 3 de ADR-037, D4: dos proveedores pueden repetir un id de evento; el mismo proveedor no. */
    public static final String PROVIDER_EVENT_INDEX = "uq_provider_event";
    /** ADR-048: historial de la cuenta por {@code donorRef}. */
    public static final String DONOR_REF_INDEX = "ix_donor_ref";

    @Id
    public String intentId;
    @Indexed(name = "uq_fund_id", unique = true)
    public String fundId;
    public String organizationRef;
    @Indexed(name = "idx_campaign_ref")
    public String campaignRef;
    @Indexed(name = DONOR_REF_INDEX)
    public String donorRef;
    public long amount;
    public String currency;
    public String paymentMethod;
    public String confirmationSource;
    public long configurationVersion;
    @Indexed(name = "uq_payment_session_id", unique = true, partialFilter = "{'paymentSessionId': {$type: 'string'}}")
    public String paymentSessionId;
    public String providerEventId;
    /** Enmienda 3 de ADR-037, D2. */
    public String paymentProvider;
    /** Enmienda 3 de ADR-037, D6: solo el hash SHA-256 del {@code statusToken}, nunca el token. */
    public String statusTokenHash;
    public Instant statusTokenExpiresAt;
    /** Enmienda 3 de ADR-037, D4: fecha del paso a {@code FAILED}. */
    public Instant failedAt;
    public Instant expiresAt;
    public String status;
    public String confirmedBy;
    public Instant confirmedAt;
    public String confirmationPaymentMethod;
    public String confirmationReference;
    // ADR-045 §2.3, §2.5: datos de operación de la aplicación de fondos.
    public Instant fundsAppliedAt;
    public Integer applicationAttempts;
    public Instant firstApplicationAttemptAt;
    public Instant lastApplicationAttemptAt;
    public String lastApplicationError;
    public Boolean applicationQuarantined;
    // Enmienda 2 §4, C2: motivo y fecha de FUNDING_REJECTED.
    public Instant fundingRejectedAt;
    public String fundingRejectionReason;
}
