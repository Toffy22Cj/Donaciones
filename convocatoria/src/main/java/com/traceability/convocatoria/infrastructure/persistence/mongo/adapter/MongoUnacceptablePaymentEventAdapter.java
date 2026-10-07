package com.traceability.convocatoria.infrastructure.persistence.mongo.adapter;

import com.traceability.convocatoria.application.port.out.UnacceptablePaymentEventPort;
import com.traceability.convocatoria.infrastructure.persistence.mongo.document.UnacceptablePaymentEventDocument;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

@Component
public class MongoUnacceptablePaymentEventAdapter implements UnacceptablePaymentEventPort {

    private final MongoTemplate mongoTemplate;

    public MongoUnacceptablePaymentEventAdapter(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    /**
     * Idempotente por construcción: el {@code _id} es {@code proveedor:evento} y se escribe con {@code setOnInsert},
     * así un reenvío del mismo evento no crea un segundo registro aunque el índice único aún no exista.
     */
    @Override
    public void record(UnacceptablePaymentEvent e) {
        Query byId = new Query(Criteria.where("_id").is(e.paymentProvider() + ":" + e.providerEventId()));
        Update insertOnly = new Update()
                .setOnInsert("paymentProvider", e.paymentProvider())
                .setOnInsert("providerEventId", e.providerEventId())
                .setOnInsert("intentId", e.intentId())
                .setOnInsert("amount", e.amount())
                .setOnInsert("currency", e.currency())
                .setOnInsert("reason", e.reason())
                .setOnInsert("receivedAt", e.receivedAt());
        try {
            mongoTemplate.upsert(byId, insertOnly, UnacceptablePaymentEventDocument.class);
        } catch (DuplicateKeyException concurrent) {
            // dos upserts simultáneos del mismo evento: el otro ya lo registró
        }
    }

    @Override
    public long count() {
        return mongoTemplate.count(new Query(), UnacceptablePaymentEventDocument.class);
    }
}
