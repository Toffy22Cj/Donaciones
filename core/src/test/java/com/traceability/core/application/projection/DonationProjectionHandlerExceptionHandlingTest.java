package com.traceability.core.application.projection;

import com.traceability.core.application.event.EventCanonicalMapper;
import com.traceability.core.domain.event.SystemActor;
import com.traceability.core.infrastructure.persistence.mongo.TraceabilityEventDocument;
import com.traceability.core.infrastructure.projection.mongo.documents.DonationProjectionDocument;
import com.traceability.core.infrastructure.projection.mongo.documents.ProjectionRetryDocument;
import com.traceability.core.infrastructure.projection.mongo.repositories.AssetHistoryProjectionRepository;
import com.traceability.core.infrastructure.projection.mongo.repositories.AssetIndexRepository;
import com.traceability.core.infrastructure.projection.mongo.repositories.DonationProjectionRepository;
import com.traceability.core.infrastructure.projection.mongo.repositories.ProjectionRetryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Test verifying that DonationProjectionHandler does NOT silently ignore exceptions
 * during retry enqueueing, maintaining full error traceability and quarantining invalid payloads.
 * Ref: Cierre Técnico Fase 5 (A5).
 */
class DonationProjectionHandlerExceptionHandlingTest {

    private MongoTemplate mongoTemplate;
    private EventCanonicalMapper canonicalMapper;
    private AssetIndexRepository assetIndexRepository;
    private DonationProjectionRepository projectionRepository;
    private AssetHistoryProjectionRepository historyRepository;
    private ProjectionRetryRepository retryRepository;

    private DonationProjectionHandler handler;

    @BeforeEach
    void setUp() {
        mongoTemplate = mock(MongoTemplate.class);
        canonicalMapper = new EventCanonicalMapper();
        assetIndexRepository = mock(AssetIndexRepository.class);
        projectionRepository = mock(DonationProjectionRepository.class);
        historyRepository = mock(AssetHistoryProjectionRepository.class);
        retryRepository = mock(ProjectionRetryRepository.class);

        handler = new DonationProjectionHandler(
                mongoTemplate,
                canonicalMapper,
                assetIndexRepository,
                projectionRepository,
                historyRepository,
                retryRepository
        );
    }

    @Test
    @DisplayName("enqueueForRetry: invalid payload schema does NOT disappear silently and is immediately QUARANTINED")
    void testEnqueueForRetry_withInvalidPayload_isQuarantinedAndNotSilentlyIgnored() {
        // Arrange: Event with invalid payload that cannot be mapped to AssetRegisteredPayload
        // (missing required fields for record constructor)
        TraceabilityEventDocument eventDoc = buildEvent(
                "asset-123",
                "PhysicalAsset",
                1,
                "ASSET_REGISTERED",
                "1.0",
                Map.of("corruptField", "unexpectedValue")
        );

        // Act: Enqueue for retry
        handler.enqueueForRetry(eventDoc);

        // Assert: Verify document was saved to retryRepository
        ArgumentCaptor<ProjectionRetryDocument> captor = ArgumentCaptor.forClass(ProjectionRetryDocument.class);
        verify(retryRepository).save(captor.capture());

        ProjectionRetryDocument savedDoc = captor.getValue();
        assertNotNull(savedDoc);
        assertEquals("asset-123", savedDoc.getStreamId());
        assertEquals("ASSET_REGISTERED", savedDoc.getEventType());
        assertNull(savedDoc.getProjectionId(), "ProjectionId should be null when payload is unparseable");

        // Defect before fix: catch (Exception ignored) {} left status as default "PENDING"
        // Expected behavior after fix: poison pill event with invalid payload is marked "QUARANTINED"
        assertEquals("QUARANTINED", savedDoc.getStatus(),
                "Event with invalid payload must be QUARANTINED, not left in PENDING retry loop");
    }

    @Test
    @DisplayName("enqueueForRetry: unknown eventType throws IllegalArgumentException and is QUARANTINED")
    void testEnqueueForRetry_withUnknownEventType_isQuarantined() {
        // Arrange: Event with completely unknown event type
        TraceabilityEventDocument eventDoc = buildEvent(
                "asset-unknown",
                "PhysicalAsset",
                1,
                "COMPLETELY_UNKNOWN_EVENT_TYPE",
                "1.0",
                Map.of("key", "value")
        );

        // Act
        handler.enqueueForRetry(eventDoc);

        // Assert
        ArgumentCaptor<ProjectionRetryDocument> captor = ArgumentCaptor.forClass(ProjectionRetryDocument.class);
        verify(retryRepository).save(captor.capture());

        ProjectionRetryDocument savedDoc = captor.getValue();
        assertEquals("QUARANTINED", savedDoc.getStatus(),
                "Event with unknown event type must be QUARANTINED immediately");
    }

    @Test
    @DisplayName("enqueueForRetry: transient DataAccessException logs warning and keeps PENDING status for retry")
    void testEnqueueForRetry_withTransientDataAccessException_keepsPendingStatus() {
        // Arrange: Valid payload, but database access times out during resolveProjectionId
        Map<String, Object> validPayload = Map.of(
                "allocationId", "alloc-1",
                "sourceAllocationId", "source-alloc",
                "quantity", 100,
                "unitOfMeasure", "UNITS",
                "assetType", "MEDICAL_SUPPLIES",
                "currentLocation", "WAREHOUSE",
                "custodianRef", "CUST-1"
        );
        TraceabilityEventDocument eventDoc = buildEvent(
                "asset-db-err",
                "PhysicalAsset",
                1,
                "ASSET_REGISTERED",
                "1.0",
                validPayload
        );

        // Simulate transient DB failure when querying asset_index
        when(assetIndexRepository.findById("asset-db-err"))
                .thenThrow(new QueryTimeoutException("Simulated transient MongoDB timeout"));

        // Act
        handler.enqueueForRetry(eventDoc);

        // Assert
        ArgumentCaptor<ProjectionRetryDocument> captor = ArgumentCaptor.forClass(ProjectionRetryDocument.class);
        verify(retryRepository).save(captor.capture());

        ProjectionRetryDocument savedDoc = captor.getValue();
        assertNotNull(savedDoc);
        assertNull(savedDoc.getProjectionId());
        // Transient infrastructure failure should remain PENDING to allow subsequent scheduled retries
        assertEquals("PENDING", savedDoc.getStatus(),
                "Transient database errors must remain PENDING for scheduled retry once DB recovers");
    }

    @Test
    @DisplayName("enqueueForRetry: when projection is PAUSED, status is QUARANTINED")
    void testEnqueueForRetry_whenProjectionIsPaused_isQuarantined() {
        // Arrange: Fund event where streamId is fundId, and projection is PAUSED
        TraceabilityEventDocument eventDoc = buildEvent(
                "fund-paused-1",
                "Fund",
                2,
                "ALLOCATION_REQUESTED",
                "1.0",
                Map.of("allocationId", "alloc-1", "requestedAmount", 500)
        );

        DonationProjectionDocument pausedProj = new DonationProjectionDocument();
        pausedProj.setProjectionId("fund-paused-1");
        pausedProj.setStatus("PAUSED");
        when(projectionRepository.findById("fund-paused-1")).thenReturn(Optional.of(pausedProj));

        // Act
        handler.enqueueForRetry(eventDoc);

        // Assert
        ArgumentCaptor<ProjectionRetryDocument> captor = ArgumentCaptor.forClass(ProjectionRetryDocument.class);
        verify(retryRepository).save(captor.capture());

        ProjectionRetryDocument savedDoc = captor.getValue();
        assertEquals("fund-paused-1", savedDoc.getProjectionId());
        assertEquals("QUARANTINED", savedDoc.getStatus());
    }

    private TraceabilityEventDocument buildEvent(String streamId, String aggregateType, long sequence,
                                                 String eventType, String schemaVersion, Map<String, Object> payload) {
        TraceabilityEventDocument doc = new TraceabilityEventDocument();
        doc.setEventId(UUID.randomUUID().toString());
        doc.setStreamId(streamId);
        doc.setAggregateType(aggregateType);
        doc.setSequence(sequence);
        doc.setEventType(eventType);
        doc.setSchemaVersion(schemaVersion);
        doc.setPayload(payload);
        doc.setActorRef(new SystemActor("test-policy"));
        doc.setOccurredAt(Instant.now().toString());
        doc.setRecordedAt(Instant.now().toString());
        doc.setEventHash("hash-" + streamId + "-" + sequence);
        return doc;
    }
}
