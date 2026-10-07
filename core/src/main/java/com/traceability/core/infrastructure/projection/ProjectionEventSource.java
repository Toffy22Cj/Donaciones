package com.traceability.core.infrastructure.projection;

import com.traceability.core.application.projection.AssetProjectionRouting;
import com.traceability.core.application.projection.DonationProjectionHandler;
import com.traceability.core.infrastructure.persistence.mongo.TraceabilityEventDocument;
import com.traceability.core.infrastructure.projection.mongo.documents.ProjectionCheckpointDocument;
import com.traceability.core.infrastructure.projection.mongo.repositories.ProjectionCheckpointRepository;
import com.traceability.core.infrastructure.projection.mongo.documents.ProjectionRetryDocument;
import com.traceability.core.infrastructure.projection.mongo.repositories.ProjectionRetryRepository;
import com.traceability.core.infrastructure.projection.mongo.documents.DonationProjectionDocument;
import com.traceability.core.infrastructure.projection.mongo.repositories.DonationProjectionRepository;
import com.traceability.core.application.event.EventCanonicalMapper;
import com.traceability.core.domain.event.DomainEventPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.Instant;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.bson.BsonDocument;
import org.bson.BsonString;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.messaging.ChangeStreamRequest;
import org.springframework.data.mongodb.core.messaging.MessageListener;
import org.springframework.data.mongodb.core.messaging.Message;
import com.mongodb.client.model.changestream.ChangeStreamDocument;
import org.springframework.data.mongodb.core.messaging.MessageListenerContainer;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ProjectionEventSource {

    private final MongoTemplate mongoTemplate;
    private final MessageListenerContainer messageListenerContainer;
    private final List<ProjectionEventHandler> projectionHandlers;
    private final ProjectionCheckpointRepository checkpointRepository;
    private final ProjectionRetryRepository retryRepository;
    private final EventCanonicalMapper canonicalMapper;
    private final AssetProjectionRouting assetRouting;
    private final DonationProjectionRepository projectionRepository;

    private static final Logger log = LoggerFactory.getLogger(ProjectionEventSource.class);
    private static final String CHECKPOINT_ID = "donation_projection_stream";

    public ProjectionEventSource(MongoTemplate mongoTemplate,
                                 MessageListenerContainer messageListenerContainer,
                                 List<ProjectionEventHandler> projectionHandlers,
                                 ProjectionCheckpointRepository checkpointRepository,
                                 ProjectionRetryRepository retryRepository,
                                 EventCanonicalMapper canonicalMapper,
                                 AssetProjectionRouting assetRouting,
                                 DonationProjectionRepository projectionRepository) {
        this.mongoTemplate = mongoTemplate;
        this.messageListenerContainer = messageListenerContainer;
        this.projectionHandlers = projectionHandlers;
        this.checkpointRepository = checkpointRepository;
        this.retryRepository = retryRepository;
        this.canonicalMapper = canonicalMapper;
        this.assetRouting = assetRouting;
        this.projectionRepository = projectionRepository;
    }

    private org.springframework.data.mongodb.core.messaging.Subscription subscription;

    @PostConstruct
    public void start() {
        ProjectionCheckpointDocument checkpoint = checkpointRepository.findById(CHECKPOINT_ID).orElse(null);

        MessageListener<ChangeStreamDocument<Document>, TraceabilityEventDocument> listener = message -> {
            TraceabilityEventDocument eventDoc = message.getBody();
            if (eventDoc != null) {
                boolean allHandlersSucceededOrDurable = true;
                // Forward strictly to the read side handlers (CQRS)
                for (ProjectionEventHandler handler : projectionHandlers) {
                    try {
                        handler.handleEvent(eventDoc);
                    } catch (Exception e) {
                        log.error("Handler {} failed: {}", handler.getHandlerName(), e.getMessage());
                        boolean durable = enqueueInitialRetry(eventDoc, handler, e);
                        if (!durable) {
                            allHandlersSucceededOrDurable = false;
                        }
                    }
                }

                // Save resume token only if all handlers succeeded or failed durably
                if (allHandlersSucceededOrDurable) {
                    BsonDocument resumeToken = message.getRaw().getResumeToken();
                    if (resumeToken != null) {
                        BsonString dataString = resumeToken.getString("_data");
                        if (dataString != null) {
                            ProjectionCheckpointDocument cp = new ProjectionCheckpointDocument(CHECKPOINT_ID, dataString.getValue());
                            checkpointRepository.save(cp);
                        }
                    }
                } else {
                    log.error("Failed to persist retry document, aborting checkpoint advance for eventId={}", eventDoc.getEventId());
                    throw new RuntimeException("Failed to persist retry document, aborting checkpoint advance");
                }
            }
        };

        ChangeStreamRequest.ChangeStreamRequestBuilder<TraceabilityEventDocument> builder =
            ChangeStreamRequest.builder(listener)
                .collection("event_store")
                .filter(org.springframework.data.mongodb.core.aggregation.Aggregation.newAggregation(
                    org.springframework.data.mongodb.core.aggregation.Aggregation.match(
                        org.springframework.data.mongodb.core.query.Criteria.where("operationType").in("insert", "replace", "update")
                    )
                ));

        if (checkpoint != null && checkpoint.getResumeToken() != null) {
            builder.resumeAfter(new BsonDocument("_data", new BsonString(checkpoint.getResumeToken())));
        }

        ChangeStreamRequest<TraceabilityEventDocument> request = builder.build();

        if (subscription != null) {
            messageListenerContainer.remove(subscription);
        }

        subscription = messageListenerContainer.register(request, TraceabilityEventDocument.class);
        messageListenerContainer.start();
    }

    @PreDestroy
    public void stop() {
        if (subscription != null) {
            messageListenerContainer.remove(subscription);
            subscription = null;
        }
        messageListenerContainer.stop();
    }

    private boolean enqueueInitialRetry(TraceabilityEventDocument eventDoc, ProjectionEventHandler handler, Exception e) {
        try {
            ProjectionRetryDocument retryDoc = ProjectionRetryDocument.builder()
                .id(eventDoc.getEventId() + "_" + handler.getHandlerName())
                .handlerName(handler.getHandlerName())
                .eventId(eventDoc.getEventId())
                .streamId(eventDoc.getStreamId())
                .sequence(eventDoc.getSequence())
                .eventType(eventDoc.getEventType())
                .schemaVersion(eventDoc.getSchemaVersion())
                .payload(eventDoc.getPayload())
                .occurredAt(eventDoc.getOccurredAt())
                .firstAttemptAt(Instant.now().toString())
                .lastAttemptAt(Instant.now().toString())
                .retryCount(0)
                .status("PENDING")
                .build();

            // Classify error
            boolean isPermanent = isPermanentError(e);
            if (isPermanent) {
                retryDoc.setStatus("QUARANTINED");
            }

            // A5 PAUSED rule: if we can determine the projectionId and it is PAUSED, quarantine immediately.
            String projectionId = null;
            if ("Fund".equals(eventDoc.getAggregateType())) {
                projectionId = eventDoc.getStreamId();
            } else {
                try {
                    DomainEventPayload payload = canonicalMapper.convertPayload(eventDoc.getPayload(), eventDoc.getEventType(), eventDoc.getSchemaVersion());
                    projectionId = assetRouting.resolveProjectionId(eventDoc.getStreamId(), payload, false);
                } catch (IllegalArgumentException ex) {
                    log.error("Failed to deserialize event payload for streamId={}, eventId={}, eventType={}, schemaVersion={}: {}",
                            eventDoc.getStreamId(), eventDoc.getEventId(), eventDoc.getEventType(), eventDoc.getSchemaVersion(), ex.getMessage(), ex);
                    retryDoc.setStatus("QUARANTINED");
                } catch (org.springframework.dao.DataAccessException ex) {
                    log.warn("Database access error while resolving projectionId for streamId={}, eventId={}: {}",
                            eventDoc.getStreamId(), eventDoc.getEventId(), ex.getMessage());
                } catch (Exception ex) {
                    log.error("Unexpected error resolving projectionId during retry enqueue for streamId={}, eventId={}: {}",
                            eventDoc.getStreamId(), eventDoc.getEventId(), ex.getMessage(), ex);
                    retryDoc.setStatus("QUARANTINED");
                }
            }

            retryDoc.setProjectionId(projectionId);

            if (projectionId != null) {
                DonationProjectionDocument proj = projectionRepository.findById(projectionId).orElse(null);
                if (proj != null && "PAUSED".equals(proj.getStatus())) {
                    retryDoc.setStatus("QUARANTINED");
                }
            }

            retryRepository.save(retryDoc);
            return true;
        } catch (Exception ex) {
            log.error("Failed to save retry document for eventId={}: {}", eventDoc.getEventId(), ex.getMessage(), ex);
            return false; // Persistence failed
        }
    }

    private boolean isPermanentError(Exception e) {
        if (e instanceof com.traceability.core.application.projection.DonationProjectionHandler.SequenceGapException ||
            e instanceof com.traceability.core.application.projection.DonationProjectionHandler.MissingDependencyException ||
            e instanceof org.springframework.dao.DataAccessException ||
            e.getClass().getName().contains("MongoSocketException") ||
            e.getClass().getName().contains("MongoTimeoutException")) {
            return false; // Retryable
        }
        return true; // NullPointerException, IllegalArgumentException, ProjectionPausedException, etc are permanent
    }
}
