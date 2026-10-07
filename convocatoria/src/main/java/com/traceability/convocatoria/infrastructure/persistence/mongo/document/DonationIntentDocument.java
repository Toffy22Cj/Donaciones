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
public class DonationIntentDocument {

    public static final String COLLECTION = "donation_intents";
    /**
     * Índice parcial de la consulta de recuperables (ADR-045 §2.5). MongoDB no admite "campo ausente" en un filtro
     * parcial, así que el filtro es {@code status = CONFIRMED} y la consulta busca {@code fundsAppliedAt = null}.
     */
    public static final String PENDING_APPLICATION_INDEX = "ix_pending_application";

    @Id
    public String intentId;
    @Indexed(name = "uq_fund_id", unique = true)
    public String fundId;
    public String organizationRef;
    public String campaignRef;
    public String donorRef;
    public long amount;
    public String currency;
    public String paymentMethod;
    public String confirmationSource;
    public long configurationVersion;
    @Indexed(name = "uq_payment_session_id", unique = true, partialFilter = "{'paymentSessionId': {$type: 'string'}}")
    public String paymentSessionId;
    public String providerEventId;
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
