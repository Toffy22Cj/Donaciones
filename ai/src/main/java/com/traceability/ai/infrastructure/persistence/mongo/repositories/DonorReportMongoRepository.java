package com.traceability.ai.infrastructure.persistence.mongo.repositories;

import com.traceability.ai.infrastructure.persistence.mongo.documents.DonorReportDocument;
import org.springframework.data.mongodb.repository.MongoRepository;
import java.util.Optional;

public interface DonorReportMongoRepository extends MongoRepository<DonorReportDocument, String> {
    Optional<DonorReportDocument> findByDonationIdAndAuditFactsSequenceAndSourceFactsHashAndPromptTemplateVersionAndModelIdentifier(
            String donationId, long auditFactsSequence, String sourceFactsHash, String promptTemplateVersion, String modelIdentifier);
}
