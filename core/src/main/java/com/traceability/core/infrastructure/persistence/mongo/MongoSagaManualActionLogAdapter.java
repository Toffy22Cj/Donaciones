package com.traceability.core.infrastructure.persistence.mongo;

import com.traceability.core.application.port.out.SagaManualActionLogPort;
import org.springframework.stereotype.Component;

import java.time.Instant;

/** Skeleton B1-bis. */
@Component
public class MongoSagaManualActionLogAdapter implements SagaManualActionLogPort {

    @Override
    public void record(String messageId, String sagaType, String correlationId, String action, String previousStatus,
                       String operator, String note, Instant at) {
    }
}
