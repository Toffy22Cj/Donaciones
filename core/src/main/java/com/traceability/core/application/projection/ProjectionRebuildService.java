package com.traceability.core.application.projection;

import com.mongodb.client.MongoChangeStreamCursor;
import com.traceability.core.infrastructure.persistence.mongo.TraceabilityEventDocument;
import com.traceability.core.infrastructure.projection.ProjectionEventSource;
import com.traceability.core.infrastructure.projection.mongo.documents.AssetHistoryProjectionDocument;
import com.traceability.core.infrastructure.projection.mongo.documents.AssetIndexDocument;
import com.traceability.core.infrastructure.projection.mongo.documents.DonationProjectionDocument;
import com.traceability.core.infrastructure.projection.mongo.documents.ProjectionCheckpointDocument;
import com.traceability.core.infrastructure.projection.mongo.repositories.ProjectionCheckpointRepository;
import org.bson.BsonDocument;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ProjectionRebuildService {

    private final MongoTemplate mongoTemplate;
    private final DonationProjectionHandler projectionHandler;
    private final ProjectionCheckpointRepository checkpointRepository;
    private final ProjectionEventSource eventSource;

    private static final String CHECKPOINT_ID = "donation_projection_stream";

    public ProjectionRebuildService(MongoTemplate mongoTemplate,
                                    DonationProjectionHandler projectionHandler,
                                    ProjectionCheckpointRepository checkpointRepository,
                                    ProjectionEventSource eventSource) {
        this.mongoTemplate = mongoTemplate;
        this.projectionHandler = projectionHandler;
        this.checkpointRepository = checkpointRepository;
        this.eventSource = eventSource;
    }

    public void rebuildAll() {
        // 1. Stop real-time consumption
        eventSource.stop();

        // 2. Get current resume token before reading bulk
        BsonDocument resumeToken;
        try (com.mongodb.client.MongoChangeStreamCursor<com.mongodb.client.model.changestream.ChangeStreamDocument<Document>> cursor = (com.mongodb.client.MongoChangeStreamCursor<com.mongodb.client.model.changestream.ChangeStreamDocument<Document>>) mongoTemplate.getCollection("event_store").watch().iterator()) {
            cursor.tryNext();
            resumeToken = cursor.getResumeToken();
        }

        // 3. Clear existing projections
        mongoTemplate.dropCollection(DonationProjectionDocument.class);
        mongoTemplate.dropCollection(AssetHistoryProjectionDocument.class);
        mongoTemplate.dropCollection(AssetIndexDocument.class);

        // 4. Process historical bulk (ordered by sequence to maintain integrity within streams)
        // Note: For a complete system rebuild across all streams, ordering by occurredAt or just processing 
        // with idempotency/retries is sufficient. We order by occurredAt.
        Query query = new Query().with(Sort.by(Sort.Direction.ASC, "occurredAt"));
        List<TraceabilityEventDocument> historicalEvents = mongoTemplate.find(query, TraceabilityEventDocument.class);
        
        for (TraceabilityEventDocument event : historicalEvents) {
            projectionHandler.handleEvent(event);
        }

        // 5. Save the token and restart Change Stream
        if (resumeToken != null) {
            String tokenString = resumeToken.getString("_data").getValue();
            checkpointRepository.save(new ProjectionCheckpointDocument(CHECKPOINT_ID, tokenString));
        }
        
        eventSource.start();
    }
}
