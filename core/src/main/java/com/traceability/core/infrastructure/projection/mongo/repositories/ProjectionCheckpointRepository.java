package com.traceability.core.infrastructure.projection.mongo.repositories;

import com.traceability.core.infrastructure.projection.mongo.documents.ProjectionCheckpointDocument;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ProjectionCheckpointRepository extends MongoRepository<ProjectionCheckpointDocument, String> {
}
