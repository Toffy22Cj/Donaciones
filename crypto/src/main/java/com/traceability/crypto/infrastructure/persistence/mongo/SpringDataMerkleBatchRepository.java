package com.traceability.crypto.infrastructure.persistence.mongo;

import com.traceability.crypto.domain.AnchorStatus;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SpringDataMerkleBatchRepository extends MongoRepository<MerkleBatchDocument, String> {
    Optional<MerkleBatchDocument> findByBatchId(String batchId);
    List<MerkleBatchDocument> findByStatus(AnchorStatus status);
}
