package com.traceability.convocatoria.infrastructure.persistence.mongo.document;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Documento de {@code DonationIntent} (ADR-037 §2.6; Enmienda §5.1): {@code _id = intentId} (único),
 * {@code fundId} único y {@code paymentSessionId} único parcial (solo si existe). Los campos nulos no se escriben.
 */
@Document(collection = DonationIntentDocument.COLLECTION)
public class DonationIntentDocument {

    public static final String COLLECTION = "donation_intents";

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
}
