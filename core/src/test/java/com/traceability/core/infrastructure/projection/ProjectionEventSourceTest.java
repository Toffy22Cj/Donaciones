package com.traceability.core.infrastructure.projection;

import com.traceability.core.application.event.EventCanonicalMapper;
import com.traceability.core.infrastructure.persistence.mongo.TraceabilityEventDocument;
import com.traceability.core.infrastructure.projection.mongo.documents.ProjectionCheckpointDocument;
import com.traceability.core.infrastructure.projection.mongo.documents.ProjectionRetryDocument;
import com.traceability.core.infrastructure.projection.mongo.repositories.AssetIndexRepository;
import com.traceability.core.infrastructure.projection.mongo.repositories.DonationProjectionRepository;
import com.traceability.core.infrastructure.projection.mongo.repositories.ProjectionCheckpointRepository;
import com.traceability.core.infrastructure.projection.mongo.repositories.ProjectionRetryRepository;
import org.bson.BsonDocument;
import org.bson.BsonString;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import com.mongodb.client.model.changestream.ChangeStreamDocument;
import org.springframework.data.mongodb.core.messaging.Message;
import org.springframework.data.mongodb.core.messaging.MessageListener;
import org.springframework.data.mongodb.core.messaging.MessageListenerContainer;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ProjectionEventSourceTest {

    private ProjectionEventSource eventSource;
    private MongoTemplate mongoTemplate;
    private MessageListenerContainer messageListenerContainer;
    private ProjectionEventHandler handler1;
    private ProjectionCheckpointRepository checkpointRepository;
    private ProjectionRetryRepository retryRepository;
    private EventCanonicalMapper canonicalMapper;
    private AssetIndexRepository assetIndexRepository;
    private DonationProjectionRepository projectionRepository;

    @BeforeEach
    void setUp() {
        mongoTemplate = mock(MongoTemplate.class);
        messageListenerContainer = mock(MessageListenerContainer.class);
        handler1 = mock(ProjectionEventHandler.class);
        when(handler1.getHandlerName()).thenReturn("Handler1");
        checkpointRepository = mock(ProjectionCheckpointRepository.class);
        retryRepository = mock(ProjectionRetryRepository.class);
        canonicalMapper = mock(EventCanonicalMapper.class);
        assetIndexRepository = mock(AssetIndexRepository.class);
        projectionRepository = mock(DonationProjectionRepository.class);

        eventSource = new ProjectionEventSource(
                mongoTemplate,
                messageListenerContainer,
                List.of(handler1),
                checkpointRepository,
                retryRepository,
                canonicalMapper,
                mock(com.traceability.core.application.projection.AssetProjectionRouting.class),
                projectionRepository
        );
    }

    @Test
    void testCritical_FalloAlPersistirRetry() throws Exception {
        // Arrange
        eventSource.start(); // This registers the listener

        // Capture the listener
        ArgumentCaptor<org.springframework.data.mongodb.core.messaging.ChangeStreamRequest> requestCaptor = ArgumentCaptor.forClass(org.springframework.data.mongodb.core.messaging.ChangeStreamRequest.class);
        verify(messageListenerContainer).register(requestCaptor.capture(), eq(TraceabilityEventDocument.class));
        MessageListener<ChangeStreamDocument<Document>, TraceabilityEventDocument> listener = requestCaptor.getValue().getMessageListener();

        // Prepare the message
        TraceabilityEventDocument eventDoc = new TraceabilityEventDocument();
        eventDoc.setEventId("evt-1");
        eventDoc.setStreamId("stream-1");
        eventDoc.setSequence(1L);

        Message<ChangeStreamDocument<Document>, TraceabilityEventDocument> message = mock(Message.class);
        when(message.getBody()).thenReturn(eventDoc);
        ChangeStreamDocument<Document> rawMock = mock(ChangeStreamDocument.class);
        when(rawMock.getResumeToken()).thenReturn(new BsonDocument("_data", new BsonString("token123")));
        when(message.getRaw()).thenReturn(rawMock);

        // Handler throws retryable exception
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("DB down"))
                .when(handler1).handleEvent(eventDoc);

        // Repository fails to persist retry
        when(retryRepository.save(any(ProjectionRetryDocument.class)))
                .thenThrow(new RuntimeException("MongoDB down for retries"));

        // Act & Assert
        RuntimeException thrown = assertThrows(RuntimeException.class, () -> {
            listener.onMessage(message);
        });

        assertEquals("Failed to persist retry document, aborting checkpoint advance", thrown.getMessage());

        // Ensure checkpoint was NOT saved
        verify(checkpointRepository, never()).save(any(ProjectionCheckpointDocument.class));
    }

    @Test
    void testInitialFailure_PermanentErrorQuarantined_AndCheckpointAdvances() throws Exception {
        ProjectionRetryDocument saved = deliverFailingEvent(new IllegalArgumentException("Invalid payload"));

        assertEquals("QUARANTINED", saved.getStatus(), "Permanent error must be quarantined on the first attempt");
        verify(checkpointRepository).save(any(ProjectionCheckpointDocument.class));
    }

    @Test
    void testInitialFailure_RetryableErrorPending_AndCheckpointAdvances() throws Exception {
        ProjectionRetryDocument saved = deliverFailingEvent(new org.springframework.dao.DataAccessResourceFailureException("DB down"));

        assertEquals("PENDING", saved.getStatus(), "Retryable error must be left PENDING for the scheduler");
        assertEquals(0, saved.getRetryCount());
        assertNotNull(saved.getFirstAttemptAt());
        verify(checkpointRepository).save(any(ProjectionCheckpointDocument.class));
    }

    private ProjectionRetryDocument deliverFailingEvent(Exception failure) throws Exception {
        eventSource.start();
        ArgumentCaptor<org.springframework.data.mongodb.core.messaging.ChangeStreamRequest> requestCaptor = ArgumentCaptor.forClass(org.springframework.data.mongodb.core.messaging.ChangeStreamRequest.class);
        verify(messageListenerContainer).register(requestCaptor.capture(), eq(TraceabilityEventDocument.class));
        MessageListener<ChangeStreamDocument<Document>, TraceabilityEventDocument> listener = requestCaptor.getValue().getMessageListener();

        TraceabilityEventDocument eventDoc = new TraceabilityEventDocument();
        eventDoc.setEventId("evt-2");
        eventDoc.setStreamId("fund-2");
        eventDoc.setAggregateType("Fund");
        eventDoc.setSequence(1L);

        Message<ChangeStreamDocument<Document>, TraceabilityEventDocument> message = mock(Message.class);
        when(message.getBody()).thenReturn(eventDoc);
        ChangeStreamDocument<Document> rawMock = mock(ChangeStreamDocument.class);
        when(rawMock.getResumeToken()).thenReturn(new BsonDocument("_data", new BsonString("token456")));
        when(message.getRaw()).thenReturn(rawMock);

        doThrow(failure).when(handler1).handleEvent(eventDoc);
        when(projectionRepository.findById("fund-2")).thenReturn(Optional.empty());

        listener.onMessage(message);

        ArgumentCaptor<ProjectionRetryDocument> captor = ArgumentCaptor.forClass(ProjectionRetryDocument.class);
        verify(retryRepository).save(captor.capture());
        return captor.getValue();
    }
}
