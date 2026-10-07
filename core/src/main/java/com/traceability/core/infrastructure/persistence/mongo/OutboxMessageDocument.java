package com.traceability.core.infrastructure.persistence.mongo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

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
    // ADR-007/008 Enmienda 1
    private Instant resolutionStartedAt;
    private String lastFailureReason;
    private String manualResolvedBy;
    private String manualNote;
    private Instant manualResolvedAt;
}
