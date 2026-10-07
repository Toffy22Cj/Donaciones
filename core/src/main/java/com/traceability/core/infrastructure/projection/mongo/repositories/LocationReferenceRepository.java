package com.traceability.core.infrastructure.projection.mongo.repositories;

import com.traceability.core.infrastructure.projection.mongo.documents.LocationReferenceDocument;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface LocationReferenceRepository extends MongoRepository<LocationReferenceDocument, String> {
}
