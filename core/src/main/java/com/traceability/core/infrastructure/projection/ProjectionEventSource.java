package com.traceability.core.infrastructure.projection;

import com.traceability.core.application.projection.DonationProjectionHandler;
import com.traceability.core.infrastructure.persistence.mongo.TraceabilityEventDocument;
import com.traceability.core.infrastructure.projection.mongo.documents.ProjectionCheckpointDocument;
import com.traceability.core.infrastructure.projection.mongo.repositories.ProjectionCheckpointRepository;
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
    
    private static final String CHECKPOINT_ID = "donation_projection_stream";

    public ProjectionEventSource(MongoTemplate mongoTemplate, 
                                 MessageListenerContainer messageListenerContainer,
                                 List<ProjectionEventHandler> projectionHandlers, 
                                 ProjectionCheckpointRepository checkpointRepository) {
        this.mongoTemplate = mongoTemplate;
        this.messageListenerContainer = messageListenerContainer;
        this.projectionHandlers = projectionHandlers;
        this.checkpointRepository = checkpointRepository;
    }

    private org.springframework.data.mongodb.core.messaging.Subscription subscription;

    @PostConstruct
    public void start() {
        ProjectionCheckpointDocument checkpoint = checkpointRepository.findById(CHECKPOINT_ID).orElse(null);
        
        MessageListener<ChangeStreamDocument<Document>, TraceabilityEventDocument> listener = message -> {
            TraceabilityEventDocument eventDoc = message.getBody();
            if (eventDoc != null) {
                // Forward strictly to the read side handlers (CQRS)
                for (ProjectionEventHandler handler : projectionHandlers) {
                    try {
                        handler.handleEvent(eventDoc);
                    } catch (Exception e) {
                        // Isolate handler failures so they don't block others
                        System.err.println("Handler " + handler.getHandlerName() + " failed: " + e.getMessage());
                    }
                }
                
                // Save resume token
                BsonDocument resumeToken = message.getRaw().getResumeToken();
                if (resumeToken != null) {
                    BsonString dataString = resumeToken.getString("_data");
                    if (dataString != null) {
                        ProjectionCheckpointDocument cp = new ProjectionCheckpointDocument(CHECKPOINT_ID, dataString.getValue());
                        checkpointRepository.save(cp);
                    }
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
}
