package com.traceability.core.infrastructure.projection.mongo;

import com.traceability.core.application.port.out.DonationReadModel;
import com.traceability.core.application.port.out.DonationReadPort;
import com.traceability.core.application.port.out.LogisticsReadItem;
import com.traceability.core.infrastructure.projection.mongo.documents.DonationProjectionDocument;
import com.traceability.core.infrastructure.projection.mongo.repositories.DonationProjectionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class DonationReadAdapter implements DonationReadPort {

    private final DonationProjectionRepository projectionRepository;

    @Override
    public Optional<DonationReadModel> findByFundId(String fundId) {
        return projectionRepository.findById(fundId)
                .map(this::mapToModel);
    }

    private DonationReadModel mapToModel(DonationProjectionDocument doc) {
        long confirmedAllocationAmount = doc.getAllocations().stream()
                .filter(a -> "CONFIRMED".equals(a.getStatus()))
                .mapToLong(DonationProjectionDocument.AllocationProjection::getAmount)
                .sum();

        List<LogisticsReadItem> logistics = doc.getLogistics().stream()
                .map(l -> new LogisticsReadItem(
                        l.getAssetId(),
                        l.getLifecycleStatus(),
                        l.getAssetType(),
                        l.getUnitOfMeasure(),
                        l.getQuantity(),
                        l.getCurrentLocation(),
                        l.getCurrentCustodian()
                ))
                .toList();

        return new DonationReadModel(
                doc.getProjectionId(),
                doc.getCurrency(),
                doc.getCampaignRef(),
                doc.getFinancialSnapshot().getOriginalAmount(),
                doc.getFinancialSnapshot().getClearedAmount(),
                doc.getFinancialSnapshot().getPendingAllocationAmount(),
                confirmedAllocationAmount,
                doc.getFinancialSnapshot().getRefundedAmount(),
                doc.getStatus(),
                logistics
        );
    }
}
