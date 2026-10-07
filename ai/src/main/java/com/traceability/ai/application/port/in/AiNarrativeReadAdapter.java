package com.traceability.ai.application.port.in;

import com.traceability.ai.application.service.DonorReportGenerator;
import com.traceability.ai.domain.exception.AuditFactsNotYetAvailableException;
import com.traceability.ai.domain.narrative.DonorReportDTO;
import com.traceability.contracts.NarrativeReadModel;
import com.traceability.contracts.NarrativeReadPort;
import com.traceability.contracts.NarrativeSource;
import com.traceability.contracts.NarrativeStatus;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

@Component
public class AiNarrativeReadAdapter implements NarrativeReadPort {

    private final DonorReportGenerator generator;

    public AiNarrativeReadAdapter(DonorReportGenerator generator) {
        this.generator = generator;
    }

    @Override
    public Optional<NarrativeReadModel> getOrTriggerGeneration(String fundId) {
        try {
            CompletableFuture<DonorReportDTO> future = generator.generateAsync(fundId);
            
            if (future.isDone() && !future.isCompletedExceptionally()) {
                DonorReportDTO report = future.join();
                return Optional.of(new NarrativeReadModel(
                        NarrativeStatus.AVAILABLE,
                        report.narrativeText(),
                        mapSource(report.source())
                ));
            }
            
            return Optional.of(new NarrativeReadModel(NarrativeStatus.PENDING, null, null));
            
        } catch (AuditFactsNotYetAvailableException e) {
            return Optional.of(new NarrativeReadModel(NarrativeStatus.PENDING, null, null));
        }
    }

    private NarrativeSource mapSource(com.traceability.ai.domain.narrative.NarrativeSource source) {
        if (source == com.traceability.ai.domain.narrative.NarrativeSource.FALLBACK_TEMPLATE) {
            return NarrativeSource.FALLBACK_TEMPLATE;
        }
        return NarrativeSource.LLM_GENERATED;
    }
}
