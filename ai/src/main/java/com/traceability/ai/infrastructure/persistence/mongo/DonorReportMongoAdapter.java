package com.traceability.ai.infrastructure.persistence.mongo;

import com.traceability.ai.application.port.out.DonorReportRepositoryPort;
import com.traceability.ai.domain.narrative.DonorReportDTO;
import com.traceability.ai.infrastructure.persistence.mongo.documents.DonorReportDocument;
import com.traceability.ai.infrastructure.persistence.mongo.repositories.DonorReportMongoRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

@Component
public class DonorReportMongoAdapter implements DonorReportRepositoryPort {

    private final DonorReportMongoRepository repository;

    public DonorReportMongoAdapter(DonorReportMongoRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<DonorReportDTO> findByLogicalKey(String donationId, long auditFactsSequence, String sourceFactsHash, String promptTemplateVersion, String modelIdentifier) {
        return repository.findByDonationIdAndAuditFactsSequenceAndSourceFactsHashAndPromptTemplateVersionAndModelIdentifier(
                donationId, auditFactsSequence, sourceFactsHash, promptTemplateVersion, modelIdentifier)
                .map(this::toDTO);
    }

    @Override
    public DonorReportDTO save(String donationId, DonorReportDTO report) {
        DonorReportDocument doc = new DonorReportDocument();
        doc.setId(UUID.randomUUID().toString());
        doc.setDonationId(donationId);
        doc.setNarrativeText(report.narrativeText());
        doc.setSource(report.source());
        doc.setModelIdentifier(report.modelIdentifier());
        doc.setPromptTemplateVersion(report.promptTemplateVersion());
        doc.setSourceFactsHash(report.sourceFactsHash());
        doc.setAuditFactsSequence(report.auditFactsSequence());
        doc.setGeneratedAt(report.generatedAt());
        doc.setNextRetryAt(report.nextRetryAt());

        repository.save(doc);
        return report;
    }

    private DonorReportDTO toDTO(DonorReportDocument doc) {
        return new DonorReportDTO(
                doc.getNarrativeText(),
                doc.getSource(),
                doc.getModelIdentifier(),
                doc.getPromptTemplateVersion(),
                doc.getSourceFactsHash(),
                doc.getAuditFactsSequence(),
                doc.getGeneratedAt(),
                doc.getNextRetryAt()
        );
    }
}
