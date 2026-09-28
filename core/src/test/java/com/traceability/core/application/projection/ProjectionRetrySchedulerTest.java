package com.traceability.core.application.projection;

import com.traceability.core.infrastructure.persistence.mongo.TraceabilityEventDocument;
import com.traceability.core.infrastructure.projection.ProjectionEventHandler;
import com.traceability.core.infrastructure.projection.mongo.documents.DonationProjectionDocument;
import com.traceability.core.infrastructure.projection.mongo.documents.ProjectionRetryDocument;
import com.traceability.core.infrastructure.projection.mongo.repositories.DonationProjectionRepository;
import com.traceability.core.infrastructure.projection.mongo.repositories.ProjectionRetryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ProjectionRetrySchedulerTest {

    private ProjectionRetryScheduler scheduler;
    private ProjectionRetryRepository retryRepository;
    private DonationProjectionRepository projectionRepository;
    private ProjectionEventHandler handler;

    @BeforeEach
    void setUp() {
        retryRepository = mock(ProjectionRetryRepository.class);
        projectionRepository = mock(DonationProjectionRepository.class);
        handler = mock(ProjectionEventHandler.class);
        when(handler.getHandlerName()).thenReturn("TestHandler");

        scheduler = new ProjectionRetryScheduler(
                retryRepository,
                List.of(handler),
                projectionRepository
        );
    }

    @Test
    void testFirstAttemptAtAndRetryCount_OnRetryableError() throws Exception {
        // Arrange
        Instant originalFirstAttempt = Instant.now().minusSeconds(3600); // 1 hour ago
        
        ProjectionRetryDocument doc = new ProjectionRetryDocument();
        doc.setId("retry-1");
        doc.setHandlerName("TestHandler");
        doc.setFirstAttemptAt(originalFirstAttempt.toString());
        doc.setRetryCount(2);
        doc.setStatus("PROCESSING");
        doc.setEventType("TEST_EVENT");
        doc.setPayload(java.util.Map.of());

        when(retryRepository.claimNextPendingRetry(anyInt()))
                .thenReturn(Optional.of(doc))
                .thenReturn(Optional.empty()); // only one document

        // Handler throws retryable error
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("DB down"))
                .when(handler).handleEvent(any());

        // Act
        scheduler.processRetries();

        // Assert
        ArgumentCaptor<ProjectionRetryDocument> captor = ArgumentCaptor.forClass(ProjectionRetryDocument.class);
        verify(retryRepository).save(captor.capture());
        
        ProjectionRetryDocument savedDoc = captor.getValue();
        
        // Comportamiento observable
        assertEquals(originalFirstAttempt.toString(), savedDoc.getFirstAttemptAt(), "firstAttemptAt must remain unchanged");
        assertEquals(3, savedDoc.getRetryCount(), "retryCount must increment");
        assertEquals("PENDING", savedDoc.getStatus(), "status must return to PENDING");
        assertNotNull(savedDoc.getLastAttemptAt(), "lastAttemptAt must be updated");
        assertNull(savedDoc.getProcessingStartedAt(), "processingStartedAt must be cleared");
        assertEquals("retry-1", savedDoc.getId(), "Must save the SAME document ID, not a clone");
    }

    @Test
    void testTaxonomy_PermanentError_QuarantinesImmediately() throws Exception {
        // Arrange
        ProjectionRetryDocument doc = new ProjectionRetryDocument();
        doc.setId("retry-perm");
        doc.setHandlerName("TestHandler");
        doc.setFirstAttemptAt(Instant.now().toString());
        doc.setEventType("TEST_EVENT");
        doc.setPayload(java.util.Map.of());

        when(retryRepository.claimNextPendingRetry(anyInt()))
                .thenReturn(Optional.of(doc))
                .thenReturn(Optional.empty());

        // A5 Case 1: Payload inválido (lanza IllegalArgumentException)
        doThrow(new IllegalArgumentException("Invalid payload format"))
                .when(handler).handleEvent(any());

        // Act
        scheduler.processRetries();

        // Assert
        ArgumentCaptor<ProjectionRetryDocument> captor = ArgumentCaptor.forClass(ProjectionRetryDocument.class);
        verify(retryRepository).save(captor.capture());
        
        assertEquals("QUARANTINED", captor.getValue().getStatus(), "IllegalArgumentException must be permanent and quarantine immediately");
    }

    @Test
    void testTaxonomy_ProjectionPausedException_QuarantinesImmediately() throws Exception {
        // Arrange
        ProjectionRetryDocument doc = new ProjectionRetryDocument();
        doc.setId("retry-paused");
        doc.setHandlerName("TestHandler");
        doc.setFirstAttemptAt(Instant.now().toString());
        doc.setEventType("TEST_EVENT");
        doc.setPayload(java.util.Map.of());

        when(retryRepository.claimNextPendingRetry(anyInt()))
                .thenReturn(Optional.of(doc))
                .thenReturn(Optional.empty());

        // A5 Case 4: Projection Paused
        doThrow(new com.traceability.core.application.projection.DonationProjectionHandler.ProjectionPausedException("Paused"))
                .when(handler).handleEvent(any());

        // Act
        scheduler.processRetries();

        // Assert
        ArgumentCaptor<ProjectionRetryDocument> captor = ArgumentCaptor.forClass(ProjectionRetryDocument.class);
        verify(retryRepository).save(captor.capture());
        assertEquals("QUARANTINED", captor.getValue().getStatus(), "ProjectionPausedException must be permanent and quarantine immediately");
    }

    @Test
    void testTaxonomy_UnknownEventType_QuarantinesImmediately() throws Exception {
        // Arrange
        ProjectionRetryDocument doc = new ProjectionRetryDocument();
        doc.setId("retry-unk");
        doc.setHandlerName("TestHandler");
        doc.setFirstAttemptAt(Instant.now().toString());
        doc.setEventType("TEST_EVENT");
        doc.setPayload(java.util.Map.of());

        when(retryRepository.claimNextPendingRetry(anyInt()))
                .thenReturn(Optional.of(doc))
                .thenReturn(Optional.empty());

        // A5 Case 2: EventType desconocido (lanza IllegalArgumentException en el mapper)
        doThrow(new IllegalArgumentException("Unknown event type"))
                .when(handler).handleEvent(any());

        // Act
        scheduler.processRetries();

        // Assert
        ArgumentCaptor<ProjectionRetryDocument> captor = ArgumentCaptor.forClass(ProjectionRetryDocument.class);
        verify(retryRepository).save(captor.capture());
        assertEquals("QUARANTINED", captor.getValue().getStatus(), "Unknown event type must quarantine immediately");
    }

    @Test
    void testTaxonomy_Success_DeletesRetry() throws Exception {
        // Arrange
        ProjectionRetryDocument doc = new ProjectionRetryDocument();
        doc.setId("retry-success");
        doc.setHandlerName("TestHandler");
        doc.setEventType("TEST_EVENT");
        doc.setPayload(java.util.Map.of());

        when(retryRepository.claimNextPendingRetry(anyInt()))
                .thenReturn(Optional.of(doc))
                .thenReturn(Optional.empty());

        // Handler succeeds (no exception)

        // Act
        scheduler.processRetries();

        // Assert
        verify(retryRepository).delete(doc);
        verify(retryRepository, never()).save(any());
    }

    @Test
    void testRetryableFailure_AttemptedOncePerRun_AndDoesNotStarveLaterDocuments() throws Exception {
        // Arrange: in-memory claim with the same semantics as claimNextPendingRetry
        // (lowest sequence among PENDING documents, which become PROCESSING when claimed).
        ProjectionRetryDocument failing = retryDoc("retry-failing", 1L, Instant.now().toString());
        ProjectionRetryDocument later = retryDoc("retry-later", 2L, Instant.now().toString());
        List<ProjectionRetryDocument> store = List.of(failing, later);
        when(retryRepository.claimNextPendingRetry(anyInt())).thenAnswer(inv -> store.stream()
                .filter(d -> "PENDING".equals(d.getStatus()))
                .min(java.util.Comparator.comparingLong(ProjectionRetryDocument::getSequence))
                .map(d -> {
                    d.setStatus("PROCESSING");
                    d.setProcessingStartedAt(Instant.now().toString());
                    return d;
                }));

        java.util.concurrent.atomic.AtomicInteger failingAttempts = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(inv -> {
            TraceabilityEventDocument ev = inv.getArgument(0);
            if ("evt-retry-failing".equals(ev.getEventId())) {
                failingAttempts.incrementAndGet();
                throw new org.springframework.dao.DataAccessResourceFailureException("DB down");
            }
            return null;
        }).when(handler).handleEvent(any());

        // Act
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(5), () -> scheduler.processRetries());

        // Assert: one attempt per run, released to PENDING for the next run, later document processed
        assertEquals(1, failingAttempts.get(), "A retryable failure must not be re-attempted within the same run");
        assertEquals("PENDING", failing.getStatus());
        assertNull(failing.getProcessingStartedAt());
        assertEquals(1, failing.getRetryCount());
        verify(retryRepository).save(failing);
        verify(retryRepository).delete(later);
        verify(retryRepository, never()).delete(failing);
    }

    @Test
    void testRetryableFailureOlderThanFourHours_QuarantinesAndPausesProjection() throws Exception {
        // Arrange
        ProjectionRetryDocument doc = retryDoc("retry-old", 1L, Instant.now().minus(5, ChronoUnit.HOURS).toString());
        doc.setProjectionId("fund-old");
        DonationProjectionDocument proj = new DonationProjectionDocument();
        proj.setStatus("ACTIVE");
        when(projectionRepository.findById("fund-old")).thenReturn(Optional.of(proj));
        when(retryRepository.claimNextPendingRetry(anyInt()))
                .thenReturn(Optional.of(doc))
                .thenReturn(Optional.empty());
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("DB down"))
                .when(handler).handleEvent(any());

        // Act
        scheduler.processRetries();

        // Assert
        ArgumentCaptor<ProjectionRetryDocument> captor = ArgumentCaptor.forClass(ProjectionRetryDocument.class);
        verify(retryRepository).save(captor.capture());
        assertEquals("QUARANTINED", captor.getValue().getStatus(), "Retryable failure past the 4h window must quarantine");
        verify(retryRepository, never()).delete(any());
        assertEquals("PAUSED", proj.getStatus());
    }

    @Test
    void testResumeProjection_FailureKeepsEventQuarantinedAndStops() throws Exception {
        // Arrange
        DonationProjectionDocument proj = new DonationProjectionDocument();
        proj.setStatus("PAUSED");
        when(projectionRepository.findById("fund-r")).thenReturn(Optional.of(proj));
        ProjectionRetryDocument first = retryDoc("retry-q1", 1L, Instant.now().toString());
        ProjectionRetryDocument second = retryDoc("retry-q2", 2L, Instant.now().toString());
        for (ProjectionRetryDocument d : List.of(first, second)) {
            d.setProjectionId("fund-r");
            d.setStatus("QUARANTINED");
        }
        when(retryRepository.findByProjectionIdAndStatusOrderBySequenceAsc("fund-r", "QUARANTINED"))
                .thenReturn(List.of(first, second));
        doThrow(new DonationProjectionHandler.SequenceGapException("gap"))
                .when(handler).handleEvent(any());

        // Act
        scheduler.resumeProjection("fund-r");

        // Assert: nothing deleted, event kept QUARANTINED, projection paused again, order preserved
        verify(retryRepository, never()).delete(any());
        verify(retryRepository).save(first);
        assertEquals("QUARANTINED", first.getStatus());
        assertEquals("PAUSED", proj.getStatus());
        verify(handler, times(1)).handleEvent(any());
    }

    private ProjectionRetryDocument retryDoc(String id, long sequence, String firstAttemptAt) {
        ProjectionRetryDocument doc = new ProjectionRetryDocument();
        doc.setId(id);
        doc.setEventId("evt-" + id);
        doc.setHandlerName("TestHandler");
        doc.setSequence(sequence);
        doc.setFirstAttemptAt(firstAttemptAt);
        doc.setStatus("PENDING");
        doc.setEventType("TEST_EVENT");
        doc.setPayload(java.util.Map.of());
        return doc;
    }
}
