package com.traceability.core.domain.physicalasset;

import com.traceability.core.domain.event.DomainEvent;
import com.traceability.core.domain.event.DomainEventPayload;
import com.traceability.core.domain.physicalasset.exceptions.InvalidCompensationQuantityException;
import com.traceability.core.domain.physicalasset.payloads.AssetRegisteredV3Payload;
import com.traceability.core.domain.physicalasset.payloads.AssetSplitPayload;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** D-SPLIT S3 (fábrica del hijo y origen de cada atributo, P4) y S4 (cantidad de la compensación). */
class PhysicalAssetSplitChildTest {

    private final List<DomainEventPayload> history = new ArrayList<>();

    private PhysicalAsset committed(PhysicalAsset asset) {
        asset.getUncommittedEvents().stream().map(DomainEvent::payload).forEach(history::add);
        return PhysicalAsset.rehydrate("parent-1", history, history.size());
    }

    private PhysicalAsset caminoAParent() {
        return committed(PhysicalAsset.register("parent-1", "FOOD", new BigDecimal("10"), "KG", "WAREHOUSE-A", "custodian-a",
                null, "parent-1", "alloc-1", null, "ORG-1", null, "CAMP-1"));
    }

    @Test
    void theChildTakesQuantityLocationAndCustodianFromTheSplit_andImmutableAttributesFromTheParent() {
        PhysicalAsset parent = caminoAParent();
        parent.split("child-1", new BigDecimal("3"));
        parent = committed(parent);
        // P4: el padre se despacha después de la división; el hijo NO toma la ubicación ni el custodio actuales
        parent.dispatch("carrier-x");
        parent = committed(parent);

        PhysicalAsset child = PhysicalAsset.registerSplitChild(parent, "child-1");
        AssetRegisteredV3Payload genesis = (AssetRegisteredV3Payload) child.getUncommittedEvents().get(0).payload();

        assertThat(genesis.assetId()).isEqualTo("child-1");
        assertThat(genesis.quantity()).isEqualByComparingTo("3");
        assertThat(genesis.unitOfMeasure()).isEqualTo("KG");
        assertThat(genesis.currentLocation()).isEqualTo("WAREHOUSE-A");
        assertThat(genesis.custodianRef()).isEqualTo("custodian-a");
        assertThat(genesis.parentAssetRef()).isEqualTo("parent-1");
        assertThat(genesis.rootAssetRef()).isEqualTo("parent-1");
        assertThat(genesis.organizationRef()).isEqualTo("ORG-1");
        assertThat(genesis.donorRef()).isNull();
        assertThat(genesis.donationRef()).isNull();
        assertThat(genesis.campaignRef()).isEqualTo("CAMP-1");
        assertThat(genesis.assetType()).isEqualTo("FOOD");
        assertThat(genesis.allocationId()).isNull();
        assertThat(genesis.sourceAllocationId()).isEqualTo("alloc-1");
    }

    @Test
    void aChildOfACaminoBParent_inheritsDonorAndDonation() {
        PhysicalAsset parent = committed(PhysicalAsset.create("parent-1", "CLOTHES", new BigDecimal("5"), "UNIT", "LOC",
                "cust", null, "parent-1", null, null, "ORG-1", "donor-1", "donation-1", null));
        parent.split("child-b", new BigDecimal("2"));
        parent = committed(parent);

        AssetRegisteredV3Payload genesis = (AssetRegisteredV3Payload)
                PhysicalAsset.registerSplitChild(parent, "child-b").getUncommittedEvents().get(0).payload();

        assertThat(genesis.donorRef()).isEqualTo("donor-1");
        assertThat(genesis.donationRef()).isEqualTo("donation-1");
        assertThat(genesis.sourceAllocationId()).isNull();
    }

    @Test
    void aGrandchild_keepsTheSourceAllocationOfItsParent() {
        PhysicalAsset child = PhysicalAsset.rehydrate("child-1", List.of(new AssetRegisteredV3Payload("child-1", "FOOD",
                new BigDecimal("3"), "KG", "LOC", "cust", "parent-1", "root-1", null, "alloc-1", "ORG-1", null, null,
                "CAMP-1")), 1);
        child.split("grandchild-1", new BigDecimal("1"));
        List<DomainEventPayload> h = new ArrayList<>(List.of(new AssetRegisteredV3Payload("child-1", "FOOD",
                new BigDecimal("3"), "KG", "LOC", "cust", "parent-1", "root-1", null, "alloc-1", "ORG-1", null, null,
                "CAMP-1")));
        child.getUncommittedEvents().stream().map(DomainEvent::payload).forEach(h::add);
        child = PhysicalAsset.rehydrate("child-1", h, h.size());

        AssetRegisteredV3Payload genesis = (AssetRegisteredV3Payload)
                PhysicalAsset.registerSplitChild(child, "grandchild-1").getUncommittedEvents().get(0).payload();

        assertThat(genesis.sourceAllocationId()).isEqualTo("alloc-1");
        assertThat(genesis.rootAssetRef()).isEqualTo("root-1");
        assertThat(genesis.parentAssetRef()).isEqualTo("child-1");
    }

    @Test
    void anUnknownChild_cannotBeRegistered() {
        assertThatThrownBy(() -> PhysicalAsset.registerSplitChild(caminoAParent(), "nope"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void findSplit_readsAV1SplitTakingTheReferencesFromTheParent() {
        PhysicalAsset parent = caminoAParent();
        history.add(new AssetSplitPayload("child-v1", new BigDecimal("2"), "KG", new BigDecimal("10"),
                new BigDecimal("8"), "REGISTERED", "LOC-V1", "cust-v1", "parent-1"));
        parent = PhysicalAsset.rehydrate("parent-1", history, history.size());

        SplitRecord split = parent.findSplit("child-v1").orElseThrow();

        assertThat(split.extractedQuantity()).isEqualByComparingTo("2");
        assertThat(split.childLocation()).isEqualTo("LOC-V1");
        assertThat(split.organizationRef()).isEqualTo("ORG-1");
        assertThat(split.campaignRef()).isNull();
    }

    // DoD 9
    @Test
    void compensatingWithAQuantityOtherThanTheExtracted_isRejected() {
        PhysicalAsset parent = caminoAParent();
        parent.split("child-1", new BigDecimal("3"));
        PhysicalAsset committedParent = committed(parent);

        assertThatThrownBy(() -> committedParent.compensateSplit("child-1", new BigDecimal("2")))
                .isInstanceOf(InvalidCompensationQuantityException.class);
        committedParent.compensateSplit("child-1", new BigDecimal("3.0000"));
        assertThat(committedParent.getUncommittedEvents()).hasSize(1);
    }
}
