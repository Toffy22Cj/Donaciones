package com.traceability.core.infrastructure.persistence.mongo;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Document(collection = "outbox")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OutboxMessageDocument {

    @Id
    private String messageId;
    private String sagaType;
    private String sourceAggregateId;
    private String correlationId;
    private String payload;
    private String status;
    private int retryCount;
    private Instant createdAt;
    private Instant nextRetryAt;
}
