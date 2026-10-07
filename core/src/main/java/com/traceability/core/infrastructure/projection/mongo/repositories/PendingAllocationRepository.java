package com.traceability.core.infrastructure.projection.mongo.repositories;

import com.traceability.core.infrastructure.projection.mongo.documents.PendingAllocationDocument;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PendingAllocationRepository extends MongoRepository<PendingAllocationDocument, String> {
}
