package com.traceability.core.infrastructure.projection.mongo.repositories;

import com.traceability.core.infrastructure.projection.mongo.documents.ProjectionRetryDocument;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ProjectionRetryRepository extends MongoRepository<ProjectionRetryDocument, String> {
    List<ProjectionRetryDocument> findByStatus(String status);
    List<ProjectionRetryDocument> findByProjectionIdAndStatusOrderBySequenceAsc(String projectionId, String status);
}
