package com.traceability.core.infrastructure.projection.mongo.documents;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.Map;

@Document(collection = "quarantined_projections")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectionRetryDocument {

    @Id
    private String id; // composite id: eventId_handlerName
    
    private String eventId; // same as TraceabilityEventDocument.eventId
    
    private String handlerName; // the handler that failed
    
    private String streamId; // aggregate id
    
    private String projectionId; // fundId for the projection, if known
    
    private long sequence;
    
    private String eventType;
    
    private Map<String, Object> payload;
    
    private String occurredAt;
    
    @Builder.Default
    private int retryCount = 0;
    
    private String firstAttemptAt;
    
    private String lastAttemptAt;
    
    @Builder.Default
    private String status = "PENDING"; // PENDING, QUARANTINED
}
