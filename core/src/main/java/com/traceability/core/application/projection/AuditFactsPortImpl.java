package com.traceability.core.application.projection;

import com.traceability.contracts.AuditFactsDTO;
import com.traceability.contracts.AuditFactsPort;
import com.traceability.core.infrastructure.projection.mongo.documents.DonationAuditFactsDocument;
import com.traceability.core.infrastructure.projection.mongo.repositories.DonationAuditFactsRepository;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class AuditFactsPortImpl implements AuditFactsPort {

    private final DonationAuditFactsRepository repository;

    public AuditFactsPortImpl(DonationAuditFactsRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<AuditFactsDTO> getAuditFacts(String donationId) {
        return repository.findById(donationId)
                .map(this::toDTO);
    }
    
    private AuditFactsDTO toDTO(DonationAuditFactsDocument doc) {
        long sequence = doc.getAuditMetadata() != null ? doc.getAuditMetadata().getFundLastProcessedSequence() : 0L;
        return new AuditFactsDTO(
                doc.getFundId(),
                sequence,
                doc.getTransitions(),
                doc.getFinancialFlags(),
                doc.getGeneratedAt()
        );
    }
}
