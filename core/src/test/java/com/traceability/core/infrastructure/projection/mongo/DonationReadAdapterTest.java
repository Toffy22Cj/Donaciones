package com.traceability.core.infrastructure.projection.mongo;

import com.traceability.core.application.port.out.DonationReadModel;
import com.traceability.core.application.port.out.LogisticsReadItem;
import com.traceability.core.infrastructure.projection.mongo.documents.DonationProjectionDocument;
import com.traceability.core.infrastructure.projection.mongo.repositories.DonationProjectionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DonationReadAdapterTest {

    @Mock
    private DonationProjectionRepository repository;

    @InjectMocks
    private DonationReadAdapter adapter;

    @Test
    void findByFundId_WhenNotFound_ReturnsEmpty() {
        when(repository.findById("unknown")).thenReturn(Optional.empty());

        Optional<DonationReadModel> result = adapter.findByFundId("unknown");

        assertThat(result).isEmpty();
    }

    @Test
    void findByFundId_WhenFound_CalculatesConfirmedAmountAndMapsCorrectly() {
        DonationProjectionDocument doc = new DonationProjectionDocument();
        doc.setProjectionId("fund-123");
        doc.setCurrency("USD");
        doc.setCampaignRef("camp-456");
        doc.setStatus("ACTIVE");

        doc.getFinancialSnapshot().setOriginalAmount(1000000L);
        doc.getFinancialSnapshot().setClearedAmount(900000L);
        doc.getFinancialSnapshot().setPendingAllocationAmount(50000L);
        doc.getFinancialSnapshot().setRefundedAmount(0L);

        // Allocations for calculation: 100k CONFIRMED + 50k PENDING + 200k CONFIRMED = 300k
        DonationProjectionDocument.AllocationProjection a1 = new DonationProjectionDocument.AllocationProjection();
        a1.setAmount(100000L);
        a1.setStatus("CONFIRMED");
        a1.setAllocationId("alloc-1");
        a1.setVendorId("vendor-1");

        DonationProjectionDocument.AllocationProjection a2 = new DonationProjectionDocument.AllocationProjection();
        a2.setAmount(50000L);
        a2.setStatus("PENDING");
        a2.setAllocationId("alloc-2");
        a2.setVendorId("vendor-2");

        DonationProjectionDocument.AllocationProjection a3 = new DonationProjectionDocument.AllocationProjection();
        a3.setAmount(200000L);
        a3.setStatus("CONFIRMED");
        a3.setAllocationId("alloc-3");
        a3.setVendorId("vendor-3");

        doc.setAllocations(Arrays.asList(a1, a2, a3));

        DonationProjectionDocument.LogisticsProjection l1 = new DonationProjectionDocument.LogisticsProjection();
        l1.setAssetId("asset-1");
        l1.setLifecycleStatus("IN_TRANSIT");
        l1.setAssetType("MEDICAL_SUPPLIES");
        l1.setUnitOfMeasure("BOX");
        l1.setQuantity(new BigDecimal("100"));
        l1.setCurrentLocation("ZONE_A");
        l1.setCurrentCustodian("LOGISTICS_PARTNER");
        // These fields must NOT appear in the result
        l1.setSourceAllocationId("source-alloc");
        l1.setParentAssetRef("parent-ref");
        l1.setRootAssetRef("root-ref");
        l1.setStatusBeforeSplit("WAREHOUSE");

        doc.setLogistics(Arrays.asList(l1));

        when(repository.findById("fund-123")).thenReturn(Optional.of(doc));

        Optional<DonationReadModel> result = adapter.findByFundId("fund-123");

        assertThat(result).isPresent();
        DonationReadModel model = result.get();
        
        assertThat(model.fundId()).isEqualTo("fund-123");
        assertThat(model.currency()).isEqualTo("USD");
        assertThat(model.campaignRef()).isEqualTo("camp-456");
        
        assertThat(model.originalAmount()).isEqualTo(1000000L);
        assertThat(model.clearedAmount()).isEqualTo(900000L);
        assertThat(model.pendingAllocationAmount()).isEqualTo(50000L);
        assertThat(model.refundedAmount()).isEqualTo(0L);
        
        // Exact calculation requested: 100k + 200k = 300k
        assertThat(model.confirmedAllocationAmount()).isEqualTo(300000L);
        
        assertThat(model.status()).isEqualTo("ACTIVE");
        
        assertThat(model.logistics()).hasSize(1);
        LogisticsReadItem log = model.logistics().get(0);
        
        assertThat(log.assetId()).isEqualTo("asset-1");
        assertThat(log.lifecycleStatus()).isEqualTo("IN_TRANSIT");
        assertThat(log.assetType()).isEqualTo("MEDICAL_SUPPLIES");
        assertThat(log.unitOfMeasure()).isEqualTo("BOX");
        assertThat(log.quantity()).isEqualTo(new BigDecimal("100"));
        assertThat(log.currentLocation()).isEqualTo("ZONE_A");
        assertThat(log.currentCustodian()).isEqualTo("LOGISTICS_PARTNER");

        // The Java compiler enforces that fields like allocationId, vendorId, parentAssetRef, rootAssetRef 
        // are not present in DonationReadModel and LogisticsReadItem. 
        // We do not need a reflection assertion since the record definition itself guarantees data purging.
    }
}
