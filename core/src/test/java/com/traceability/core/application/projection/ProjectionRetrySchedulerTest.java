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
}
