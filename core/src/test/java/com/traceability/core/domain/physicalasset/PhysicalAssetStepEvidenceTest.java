package com.traceability.core.domain.physicalasset;

import com.traceability.core.application.event.EventPayloadRegistry;
import com.traceability.core.domain.event.DomainEvent;
import com.traceability.core.domain.event.DomainEventPayload;
import com.traceability.core.domain.physicalasset.payloads.AssetDispatchedV2Payload;
import com.traceability.core.domain.physicalasset.payloads.AssetDispatchedV3Payload;
import com.traceability.core.domain.physicalasset.payloads.AssetReceivedV2Payload;
import com.traceability.core.domain.physicalasset.payloads.AssetReceivedV3Payload;
import com.traceability.core.domain.physicalasset.payloads.AssetRegisteredV4Payload;
import com.traceability.core.domain.physicalasset.payloads.AssetRegisteredV5Payload;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-051 Enmienda 2, E2-2 (plan de la Tarea 1, grupo B, tests 5, 6 y 12): el agregado escribe siempre las versiones
 * nuevas ({@code ASSET_REGISTERED} 5.0, {@code ASSET_DISPATCHED} 3.0 y {@code ASSET_RECEIVED} 3.0), con o sin foto; el
 * estado del activo es el mismo que con las versiones anteriores; y el hijo de una división nace sin foto.
 */
class PhysicalAssetStepEvidenceTest {

    private static final String V = "DIVIPOLA-TEST";
    private static final SealedZone MEDELLIN = SealedZone.of("05001", "MEDELLÍN", "ANTIOQUIA", V);
    private static final String REGISTRATION_PHOTO = "sha256:" + "1".repeat(64);
    private static final String DISPATCH_PHOTO = "sha256:" + "2".repeat(64);
    private static final String RECEIPT_PHOTO = "sha256:" + "3".repeat(64);

    private static PhysicalAsset registered(String evidenceRef) {
        return PhysicalAsset.create("asset-1", "FOOD", new BigDecimal("10"), "KG", "Bodega norte", "custodian-1", null,
                "asset-1", null, null, "org-1", "donor-1", "donation-1", "campaign-1", MEDELLIN, evidenceRef);
    }

    private static DomainEventPayload last(PhysicalAsset asset) {
        List<DomainEvent> events = asset.getUncommittedEvents();
        return events.get(events.size() - 1).payload();
    }

    // E2-2: la 5.0, la 3.0 y la 3.0 están registradas con su número
    @Test
    void theNewVersions_areRegistered() {
        assertThat(EventPayloadRegistry.getSchemaVersionForClass(AssetRegisteredV5Payload.class)).isEqualTo("5.0");
        assertThat(EventPayloadRegistry.getSchemaVersionForClass(AssetDispatchedV3Payload.class)).isEqualTo("3.0");
        assertThat(EventPayloadRegistry.getSchemaVersionForClass(AssetReceivedV3Payload.class)).isEqualTo("3.0");
        // y las anteriores se siguen leyendo, sin cambio
        assertThat(EventPayloadRegistry.getSchemaVersionForClass(AssetRegisteredV4Payload.class)).isEqualTo("4.0");
        assertThat(EventPayloadRegistry.getSchemaVersionForClass(AssetDispatchedV2Payload.class)).isEqualTo("2.0");
        assertThat(EventPayloadRegistry.getSchemaVersionForClass(AssetReceivedV2Payload.class)).isEqualTo("2.0");
    }

    // test 5: sin foto se escribe la versión nueva con null, y el estado es el de siempre
    @Test
    void test5_withoutAPhoto_theThreeStepsWriteTheNewVersionWithNull_andTheStateIsTheSame() {
        PhysicalAsset asset = registered(null);
        assertThat(last(asset)).isInstanceOf(AssetRegisteredV5Payload.class);
        assertThat(((AssetRegisteredV5Payload) last(asset)).evidenceRef()).isNull();

        asset.dispatch("carrier-1", null);
        assertThat(last(asset)).isInstanceOf(AssetDispatchedV3Payload.class);
        AssetDispatchedV3Payload dispatched = (AssetDispatchedV3Payload) last(asset);
        assertThat(dispatched.evidenceRef()).isNull();
        assertThat(dispatched.carrierRef()).isEqualTo("carrier-1");
        assertThat(dispatched.previousLocation()).isEqualTo("Bodega norte");
        assertThat(dispatched.municipalityCode()).isEqualTo("05001");

        asset.receive("Centro de acopio", "receiver-1", MEDELLIN, null);
        assertThat(last(asset)).isInstanceOf(AssetReceivedV3Payload.class);
        AssetReceivedV3Payload received = (AssetReceivedV3Payload) last(asset);
        assertThat(received.evidenceRef()).isNull();
        assertThat(received.facilityLocation()).isEqualTo("Centro de acopio");
        assertThat(received.zoneName()).isEqualTo("MEDELLÍN, ANTIOQUIA");

        // el estado tras reconstruir desde los eventos nuevos
        PhysicalAsset rebuilt = PhysicalAsset.rehydrate("asset-1",
                asset.getUncommittedEvents().stream().map(DomainEvent::payload).toList(), 3);
        assertThat(rebuilt.getLifecycleStatus()).isEqualTo(AssetLifecycleStatus.RECEIVED);
        assertThat(rebuilt.getCurrentLocation()).isEqualTo("Centro de acopio");
        assertThat(rebuilt.getCustodianRef()).isEqualTo("receiver-1");
        assertThat(rebuilt.getOrganizationRef()).isEqualTo("org-1");
        assertThat(rebuilt.getCampaignRef()).isEqualTo("campaign-1");
    }

    // test 6: con foto, cada paso lleva su propia huella
    @Test
    void test6_withAPhoto_eachStepCarriesItsOwnEvidenceRef() {
        PhysicalAsset asset = registered(REGISTRATION_PHOTO);
        assertThat(((AssetRegisteredV5Payload) last(asset)).evidenceRef()).isEqualTo(REGISTRATION_PHOTO);
        asset.dispatch("carrier-1", DISPATCH_PHOTO);
        assertThat(((AssetDispatchedV3Payload) last(asset)).evidenceRef()).isEqualTo(DISPATCH_PHOTO);
        asset.receive("Centro de acopio", "receiver-1", MEDELLIN, RECEIPT_PHOTO);
        assertThat(((AssetReceivedV3Payload) last(asset)).evidenceRef()).isEqualTo(RECEIPT_PHOTO);
    }

    // Camino A (compra) y la sobrecarga sin foto: la misma versión nueva
    @Test
    void pathA_andTheOverloadsWithoutAPhoto_alsoWriteTheNewVersionWithNull() {
        PhysicalAsset pathA = PhysicalAsset.register("asset-2", "FOOD", new BigDecimal("10"), "KG", "Bodega", "c-1", null,
                "asset-2", "alloc-1", null, "org-1", null, "campaign-1", MEDELLIN, REGISTRATION_PHOTO);
        assertThat(((AssetRegisteredV5Payload) last(pathA)).evidenceRef()).isEqualTo(REGISTRATION_PHOTO);

        PhysicalAsset legacyOverload = PhysicalAsset.register("asset-3", "FOOD", new BigDecimal("10"), "KG", "Bodega", "c-1",
                null, "asset-3", "alloc-1", null, "org-1", null, "campaign-1", MEDELLIN);
        assertThat(last(legacyOverload)).isInstanceOf(AssetRegisteredV5Payload.class);
        assertThat(((AssetRegisteredV5Payload) last(legacyOverload)).evidenceRef()).isNull();

        legacyOverload.dispatch("carrier-1");
        assertThat(last(legacyOverload)).isInstanceOf(AssetDispatchedV3Payload.class);
        legacyOverload.receive("Centro", "receiver-1", MEDELLIN);
        assertThat(last(legacyOverload)).isInstanceOf(AssetReceivedV3Payload.class);
    }

    // test 12: el hijo de una división nace con la 5.0 y sin foto (E2-2, Q-T1-3)
    @Test
    void test12_theChildOfASplit_isBornWithVersion5_andNoPhoto() {
        PhysicalAsset parent = registered(REGISTRATION_PHOTO);
        parent.split("child-1", new BigDecimal("4"));
        PhysicalAsset child = PhysicalAsset.registerSplitChild(parent, "child-1");

        assertThat(last(child)).isInstanceOf(AssetRegisteredV5Payload.class);
        assertThat(((AssetRegisteredV5Payload) last(child)).evidenceRef()).isNull();
    }

    // Los eventos viejos ya guardados se siguen aplicando igual
    @Test
    void oldVersions_areStillAppliedAsBefore() {
        List<DomainEventPayload> old = new ArrayList<>();
        old.add(new AssetRegisteredV4Payload("asset-9", "FOOD", new BigDecimal("10.0000"), "KG", "Bodega norte",
                "custodian-1", null, "asset-9", "alloc-1", null, "org-1", "donor-1", "donation-1", "campaign-1", "05001",
                "MEDELLÍN, ANTIOQUIA", V));
        old.add(new AssetDispatchedV2Payload("carrier-1", "Bodega norte", "05001", "MEDELLÍN, ANTIOQUIA", V));
        old.add(new AssetReceivedV2Payload("Centro", "receiver-1", "05001", "MEDELLÍN, ANTIOQUIA", V));

        PhysicalAsset rebuilt = PhysicalAsset.rehydrate("asset-9", old, 3);

        assertThat(rebuilt.getLifecycleStatus()).isEqualTo(AssetLifecycleStatus.RECEIVED);
        assertThat(rebuilt.getCurrentLocation()).isEqualTo("Centro");
        assertThat(rebuilt.getCustodianRef()).isEqualTo("receiver-1");
    }
}
