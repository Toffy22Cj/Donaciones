package com.traceability.core.infrastructure.persistence.mongo;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Document(collection = "event_store")
@CompoundIndex(name = "idx_stream_sequence", def = "{'streamId': 1, 'sequence': 1}", unique = true)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TraceabilityEventDocument {

    @Id
    private String eventId;
    
    private String streamId;
    private String aggregateType;
    private long sequence;
    private String eventType;
    private String schemaVersion;
    private String occurredAt;
    private String recordedAt;
    private String actorRef;
    private String origin;
    
    private Map<String, Object> payload;
    
    private String previousHash;
    private String eventHash;
}
