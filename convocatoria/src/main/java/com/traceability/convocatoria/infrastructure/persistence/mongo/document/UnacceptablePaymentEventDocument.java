package com.traceability.convocatoria.infrastructure.persistence.mongo.document;

import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/** Registro {@code unacceptable_payment_events} (Enmienda 3 de ADR-037, D4), único por proveedor y evento. */
@Document(collection = UnacceptablePaymentEventDocument.COLLECTION)
@CompoundIndex(name = "uq_provider_event", def = "{'paymentProvider': 1, 'providerEventId': 1}", unique = true)
public class UnacceptablePaymentEventDocument {

    public static final String COLLECTION = "unacceptable_payment_events";

    public String id;
    public String paymentProvider;
    public String providerEventId;
    public String intentId;
    public long amount;
    public String currency;
    public String reason;
    public Instant receivedAt;
}
