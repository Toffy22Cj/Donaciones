package com.traceability.core.infrastructure.security.mongo;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RevokedTrackingCodeRepository extends MongoRepository<RevokedTrackingCodeDocument, String> {
}
