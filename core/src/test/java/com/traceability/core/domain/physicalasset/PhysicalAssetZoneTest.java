package com.traceability.core.domain.physicalasset;

import com.traceability.core.application.event.EventPayloadRegistry;
import com.traceability.core.domain.event.DomainEvent;
import com.traceability.core.domain.event.DomainEventPayload;
import com.traceability.core.domain.physicalasset.exceptions.InvalidAssetTransitionException;
import com.traceability.core.domain.physicalasset.payloads.AssetDeliveredPayload;
import com.traceability.core.domain.physicalasset.payloads.AssetDeliveredV2Payload;
import com.traceability.core.domain.physicalasset.payloads.AssetDispatchedPayload;
import com.traceability.core.domain.physicalasset.payloads.AssetDispatchedV2Payload;
import com.traceability.core.domain.physicalasset.payloads.AssetReceivedPayload;
import com.traceability.core.domain.physicalasset.payloads.AssetReceivedV2Payload;
import com.traceability.core.domain.physicalasset.payloads.AssetRegisteredV3Payload;
import com.traceability.core.domain.physicalasset.payloads.AssetRegisteredV4Payload;
import com.traceability.core.domain.physicalasset.payloads.AssetSplitV4Payload;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Enmienda 2 de ADR-029 (D1, D2, D4 y E-1) y el plan de la zona en cada paso (tests 3, 5, 6, 7 y 9): el agregado sella
 * la zona DIVIPOLA en las versiones nuevas, la copia al despachar, la hereda el sublote, y un activo anterior escribe la
 * versión nueva con la zona vacía.
 */
class PhysicalAssetZoneTest {

    private static final String V = "DIVIPOLA-TEST";
    private static final SealedZone MEDELLIN = SealedZone.of("05001", "MEDELLÍN", "ANTIOQUIA", V);
    private static final SealedZone ABEJORRAL = SealedZone.of("05002", "ABEJORRAL", "ANTIOQUIA", V);
    private static final SealedZone BOGOTA = SealedZone.of("11001", "BOGOTÁ, D.C.", "BOGOTÁ, D.C.", V);
    private static final String EVIDENCE = "sha256:" + "b".repeat(64);
    private static final Instant AT = Instant.parse("2026-11-02T12:00:00Z");

    // test 3: el texto de la zona, tal cual el catálogo, y Bogotá una sola vez (P5)
    @Test
    void test3_theZoneName_isMunicipalityCommaDepartment_asInTheCatalog_andBogotaOnlyOnce() {
        assertThat(SealedZone.of("08001", "BARRANQUILLA", "ATLÁNTICO", V))
                .isEqualTo(new SealedZone("08001", "BARRANQUILLA, ATLÁNTICO", V));
        assertThat(BOGOTA).isEqualTo(new SealedZone("11001", "BOGOTÁ, D.C.", V));
        assertThat(MEDELLIN.zoneName()).isEqualTo("MEDELLÍN, ANTIOQUIA");
        assertThat(MEDELLIN.catalogVersion()).isEqualTo(V);
    }

    // D2: las versiones nuevas están registradas con su número, y las viejas se siguen leyendo
    @Test
    void theNewVersions_areRegistered_andTheOldOnesAreStillRead() {
        assertThat(EventPayloadRegistry.getSchemaVersionForClass(AssetRegisteredV4Payload.class)).isEqualTo("4.0");
        assertThat(EventPayloadRegistry.getSchemaVersionForClass(AssetSplitV4Payload.class)).isEqualTo("4.0");
        assertThat(EventPayloadRegistry.getSchemaVersionForClass(AssetDispatchedV2Payload.class)).isEqualTo("2.0");
        assertThat(EventPayloadRegistry.getSchemaVersionForClass(AssetReceivedV2Payload.class)).isEqualTo("2.0");
        assertThat(EventPayloadRegistry.getSchemaVersionForClass(AssetDeliveredV2Payload.class)).isEqualTo("2.0");
        assertThat(EventPayloadRegistry.getClassForType("ASSET_REGISTERED", "3.0")).isEqualTo(AssetRegisteredV3Payload.class);
        assertThat(EventPayloadRegistry.getClassForType("ASSET_DISPATCHED", "1.0")).isEqualTo(AssetDispatchedPayload.class);
        assertThat(EventPayloadRegistry.getClassForType("ASSET_RECEIVED", "1.0")).isEqualTo(AssetReceivedPayload.class);
        assertThat(EventPayloadRegistry.getClassForType("ASSET_DELIVERED", "1.0")).isEqualTo(AssetDeliveredPayload.class);
    }

    // los tres pasos con municipio escriben la versión nueva con la zona sellada; el despacho copia la del paso anterior
    @Test
    void registerReceiveAndDeliver_sealTheZone_andDispatchCopiesThePreviousOne() {
        PhysicalAsset asset = registered(MEDELLIN);
        AssetRegisteredV4Payload genesis = (AssetRegisteredV4Payload) last(asset);
        assertThat(genesis.municipalityCode()).isEqualTo("05001");
        assertThat(genesis.zoneName()).isEqualTo("MEDELLÍN, ANTIOQUIA");
        assertThat(genesis.catalogVersion()).isEqualTo(V);
        assertThat(genesis.currentLocation()).isEqualTo("Bodega norte");

        // test 5 (P2): el despacho no recibe zona; sella la del paso anterior
        asset.dispatch("carrier-1");
        AssetDispatchedV2Payload dispatched = (AssetDispatchedV2Payload) last(asset);
        assertThat(dispatched.municipalityCode()).isEqualTo("05001");
        assertThat(dispatched.zoneName()).isEqualTo("MEDELLÍN, ANTIOQUIA");
        assertThat(dispatched.catalogVersion()).isEqualTo(V);
        assertThat(dispatched.previousLocation()).isEqualTo("Bodega norte");

        asset.receive("Centro de acopio", "receiver-1", ABEJORRAL);
        AssetReceivedV2Payload received = (AssetReceivedV2Payload) last(asset);
        assertThat(received.municipalityCode()).isEqualTo("05002");
        assertThat(received.zoneName()).isEqualTo("ABEJORRAL, ANTIOQUIA");
        assertThat(asset.getZone()).isEqualTo(ABEJORRAL);

        asset.deliver("custodian-2", "beneficiary-1", "Escuela rural", EVIDENCE, AT, BOGOTA);
        AssetDeliveredV2Payload delivered = (AssetDeliveredV2Payload) last(asset);
        assertThat(delivered.municipalityCode()).isEqualTo("11001");
        assertThat(delivered.zoneName()).isEqualTo("BOGOTÁ, D.C.");
        assertThat(delivered.locationRef()).isEqualTo("Escuela rural");
        assertThat(asset.getZone()).isEqualTo(BOGOTA);
    }

    // la zona sobrevive a la reconstrucción del agregado, también después de despachar (lo que copia el despacho)
    @Test
    void theZone_survivesRehydration_andTheDispatchOfARehydratedAssetCopiesIt() {
        PhysicalAsset asset = registered(MEDELLIN);
        asset.dispatch("carrier-1");
        asset.receive("Centro", "receiver-1", ABEJORRAL);
        List<DomainEventPayload> history = new ArrayList<>(asset.getUncommittedEvents().stream().map(DomainEvent::payload).toList());
        PhysicalAsset again = PhysicalAsset.rehydrate("asset-1", history, history.size());
        assertThat(again.getZone()).isEqualTo(ABEJORRAL);

        again.dispatch("carrier-2");
        assertThat(((AssetDispatchedV2Payload) last(again)).municipalityCode()).isEqualTo("05002");
        history.add(last(again));
        PhysicalAsset onTheRoad = PhysicalAsset.rehydrate("asset-1", history, history.size());
        assertThat(onTheRoad.getZone()).as("en camino conserva la zona del paso anterior").isEqualTo(ABEJORRAL);
    }

    // test 6 (P3): el sublote hereda la zona sellada del padre, en ASSET_SPLIT 4.0 y en el registro 4.0 del hijo
    @Test
    void test6_theSublotInheritsTheSealedZone() {
        PhysicalAsset parent = registered(ABEJORRAL);
        parent.split("child-1", new BigDecimal("4"));
        AssetSplitV4Payload split = (AssetSplitV4Payload) parent.getUncommittedEvents().stream()
                .map(DomainEvent::payload).filter(AssetSplitV4Payload.class::isInstance).findFirst().orElseThrow();
        assertThat(split.municipalityCode()).isEqualTo("05002");
        assertThat(split.zoneName()).isEqualTo("ABEJORRAL, ANTIOQUIA");
        assertThat(split.catalogVersion()).isEqualTo(V);

        PhysicalAsset child = PhysicalAsset.registerSplitChild(rehydrate(parent), "child-1");
        AssetRegisteredV4Payload genesis = (AssetRegisteredV4Payload) last(child);
        assertThat(genesis.municipalityCode()).isEqualTo("05002");
        assertThat(genesis.zoneName()).isEqualTo("ABEJORRAL, ANTIOQUIA");
        assertThat(genesis.catalogVersion()).isEqualTo(V);
    }

    // test 7: la entrega idempotente compara también el municipio
    @Test
    void test7_anIdempotentRedelivery_withAnotherMunicipality_isRejected() {
        PhysicalAsset asset = registered(MEDELLIN);
        asset.dispatch("carrier-1");
        asset.deliver("custodian-2", "beneficiary-1", "Escuela rural", EVIDENCE, AT, BOGOTA);
        PhysicalAsset delivered = rehydrate(asset);

        delivered.deliver("custodian-2", "beneficiary-1", "Escuela rural", EVIDENCE, AT, BOGOTA);
        assertThat(delivered.getUncommittedEvents()).as("la misma entrega no escribe nada").isEmpty();
        assertThatThrownBy(() -> delivered.deliver("custodian-2", "beneficiary-1", "Escuela rural", EVIDENCE, AT, MEDELLIN))
                .isInstanceOf(InvalidAssetTransitionException.class);
    }

    // test 9 (E-1): un activo anterior, sin zona sellada, despacha y divide con la versión nueva y la zona vacía
    @Test
    void test9_anOldAssetWithoutSealedZone_dispatchesAndSplitsWithTheNewVersion_andTheZoneEmpty() {
        List<DomainEventPayload> history = List.of(new AssetRegisteredV3Payload("asset-old", "FOOD", new BigDecimal("10"),
                "KG", "Bodega vieja", "custodian-1", null, "asset-old", "alloc-1", null, "org-1", null, null, null));
        PhysicalAsset old = PhysicalAsset.rehydrate("asset-old", history, 1);
        assertThat(old.getZone()).isNull();

        old.split("child-old", new BigDecimal("2"));
        AssetSplitV4Payload split = (AssetSplitV4Payload) last(old);
        assertThat(split.municipalityCode()).isNull();
        assertThat(split.zoneName()).isNull();
        assertThat(split.catalogVersion()).isNull();
        assertThat(split.childLocation()).isEqualTo("Bodega vieja");

        PhysicalAsset again = PhysicalAsset.rehydrate("asset-old", history, 1);
        again.dispatch("carrier-1");
        AssetDispatchedV2Payload dispatched = (AssetDispatchedV2Payload) last(again);
        assertThat(dispatched.municipalityCode()).isNull();
        assertThat(dispatched.zoneName()).isNull();
        assertThat(dispatched.catalogVersion()).isNull();
        assertThat(dispatched.previousLocation()).isEqualTo("Bodega vieja");
    }

    // una recepción vieja (1.0) borra la zona: la ubicación cambió sin zona sellada
    @Test
    void anOldReceive_clearsTheZone_becauseTheLocationChangedWithoutASealedZone() {
        PhysicalAsset asset = registered(MEDELLIN);
        asset.dispatch("carrier-1");
        List<DomainEventPayload> payloads = new ArrayList<>(asset.getUncommittedEvents().stream().map(DomainEvent::payload).toList());
        payloads.add(new AssetReceivedPayload("Centro viejo", "receiver-1"));
        assertThat(PhysicalAsset.rehydrate("asset-1", payloads, payloads.size()).getZone()).isNull();
    }

    private static PhysicalAsset registered(SealedZone zone) {
        return PhysicalAsset.register("asset-1", "FOOD", new BigDecimal("10"), "KG", "Bodega norte", "custodian-1",
                null, "asset-1", "alloc-1", null, "org-1", null, "campaign-1", zone);
    }

    private static DomainEventPayload last(PhysicalAsset asset) {
        List<DomainEvent> events = asset.getUncommittedEvents();
        return events.get(events.size() - 1).payload();
    }

    private static PhysicalAsset rehydrate(PhysicalAsset asset) {
        List<DomainEventPayload> payloads = asset.getUncommittedEvents().stream().map(DomainEvent::payload).toList();
        return PhysicalAsset.rehydrate("asset-1", payloads, payloads.size());
    }
}
