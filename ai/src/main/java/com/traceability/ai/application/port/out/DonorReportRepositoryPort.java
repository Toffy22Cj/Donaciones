package com.traceability.ai.application.port.out;

import com.traceability.ai.domain.narrative.DonorReportDTO;
import java.util.Optional;

public interface DonorReportRepositoryPort {
    Optional<DonorReportDTO> findByLogicalKey(String donationId, long auditFactsSequence, String sourceFactsHash, String promptTemplateVersion, String modelIdentifier);
    DonorReportDTO save(String donationId, DonorReportDTO report);
}
