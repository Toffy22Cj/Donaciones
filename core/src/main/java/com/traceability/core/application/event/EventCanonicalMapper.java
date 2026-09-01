package com.traceability.core.application.event;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.traceability.core.domain.event.DomainEventPayload;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * Ensures deterministic conversion of DomainEvents and their metadata to a Map for hashing.
 * Acts as the single source of truth for converting events to and from generic Maps.
 */
@Component
public class EventCanonicalMapper {

    private final ObjectMapper mapper;

    public EventCanonicalMapper() {
        this.mapper = new ObjectMapper();
        this.mapper.registerModule(new JavaTimeModule());
        this.mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    /**
     * Converts a full event envelope into a generic Map.
     */
    public Map<String, Object> toCanonicalMap(
            String eventId,
            String streamId,
            String aggregateType,
            long sequence,
            String eventType,
            String schemaVersion,
            Instant occurredAt,
            Instant recordedAt,
            String actorRef,
            String origin,
            DomainEventPayload payload) {
        
        Map<String, Object> map = new HashMap<>();
        map.put("eventId", eventId);
        map.put("streamId", streamId);
        map.put("aggregateType", aggregateType);
        map.put("sequence", sequence);
        map.put("eventType", eventType);
        map.put("schemaVersion", schemaVersion);
        map.put("occurredAt", occurredAt != null ? occurredAt.toString() : null);
        map.put("recordedAt", recordedAt != null ? recordedAt.toString() : null);
        map.put("actorRef", actorRef);
        map.put("origin", origin);

        // Convert the strongly-typed payload into a generic Map
        Map<String, Object> payloadMap = mapper.convertValue(payload, new TypeReference<Map<String, Object>>() {});
        map.put("payload", payloadMap);

        return map;
    }
    
    /**
     * Converts a generic Map back into a strongly-typed DomainEventPayload.
     */
    public DomainEventPayload convertPayload(Map<String, Object> payloadMap, String eventType) {
        Class<? extends DomainEventPayload> clazz = EventPayloadRegistry.getClassForType(eventType);
        return mapper.convertValue(payloadMap, clazz);
    }
}
