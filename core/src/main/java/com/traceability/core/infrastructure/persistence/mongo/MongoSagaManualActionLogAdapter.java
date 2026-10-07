package com.traceability.core.infrastructure.persistence.mongo;

import com.traceability.core.application.port.out.SagaManualActionLogPort;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * Auditoría de solo inserción de las acciones manuales sobre sagas (ADR-007/008 Enmienda 1, D5). Se escribe en la misma
 * transacción que el cambio de estado del mensaje.
 */
@Component
public class MongoSagaManualActionLogAdapter implements SagaManualActionLogPort {

    static final String COLLECTION = "saga_manual_actions";

    private final MongoTemplate mongoTemplate;

    public MongoSagaManualActionLogAdapter(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public void record(String messageId, String sagaType, String correlationId, String action, String previousStatus,
                       String operator, String note, Instant at) {
        mongoTemplate.insert(new Document("_id", UUID.randomUUID().toString())
                .append("messageId", messageId)
                .append("sagaType", sagaType)
                .append("correlationId", correlationId)
                .append("action", action)
                .append("previousStatus", previousStatus)
                .append("operator", operator)
                .append("note", note)
                .append("at", Date.from(at)), COLLECTION);
    }
}
