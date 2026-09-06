package com.traceability.core.infrastructure.projection.mongo.repositories;

import com.traceability.core.infrastructure.projection.mongo.documents.ProjectionRetryDocument;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

@Repository
public class ProjectionRetryRepositoryImpl implements ProjectionRetryRepositoryCustom {

    private final MongoTemplate mongoTemplate;

    public ProjectionRetryRepositoryImpl(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public Optional<ProjectionRetryDocument> claimNextPendingRetry(int processingTimeoutMinutes) {
        Instant now = Instant.now();
        Instant timeoutThreshold = now.minus(processingTimeoutMinutes, ChronoUnit.MINUTES);

        // We claim either a PENDING document, or a PROCESSING document that timed out
        Criteria criteria = new Criteria().orOperator(
                Criteria.where("status").is("PENDING"),
                Criteria.where("status").is("PROCESSING").and("processingStartedAt").lt(timeoutThreshold.toString())
        );

        Query query = new Query(criteria);
        query.with(Sort.by(Sort.Direction.ASC, "sequence")); // Ensure ordered processing

        Update update = new Update()
                .set("status", "PROCESSING")
                .set("processingStartedAt", now.toString());

        FindAndModifyOptions options = new FindAndModifyOptions().returnNew(true);

        ProjectionRetryDocument claimedDoc = mongoTemplate.findAndModify(
                query, update, options, ProjectionRetryDocument.class);

        return Optional.ofNullable(claimedDoc);
    }
}
