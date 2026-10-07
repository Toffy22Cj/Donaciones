package com.traceability.ai.infrastructure.persistence.mongo.repositories;

import com.traceability.ai.infrastructure.persistence.mongo.documents.DonorReportDocument;
import org.springframework.data.mongodb.repository.MongoRepository;
import java.util.Optional;

public interface DonorReportMongoRepository extends MongoRepository<DonorReportDocument, String> {
    /** El más reciente: tras el intervalo de reintento puede haber más de un fallback con la misma clave lógica. */
    Optional<DonorReportDocument> findFirstByDonationIdAndAuditFactsSequenceAndSourceFactsHashAndPromptTemplateVersionAndModelIdentifierOrderByGeneratedAtDesc(
            String donationId, long auditFactsSequence, String sourceFactsHash, String promptTemplateVersion, String modelIdentifier);
}
