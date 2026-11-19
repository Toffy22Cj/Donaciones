package com.traceability.app.application.media;

import com.traceability.app.application.media.StepEvidenceIndex.Entry;
import com.traceability.app.application.media.StepEvidenceIndex.Step;
import com.traceability.core.domain.event.DomainEvent;
import com.traceability.core.domain.physicalasset.PhysicalAssetEventType;
import com.traceability.core.domain.physicalasset.payloads.AssetCustodyTransferredPayload;
import com.traceability.core.domain.physicalasset.payloads.AssetDeliveredPayload;
import com.traceability.core.domain.physicalasset.payloads.AssetDeliveredV2Payload;
import com.traceability.core.domain.physicalasset.payloads.AssetDispatchedPayload;
import com.traceability.core.domain.physicalasset.payloads.AssetDispatchedV2Payload;
import com.traceability.core.domain.physicalasset.payloads.AssetDispatchedV3Payload;
import com.traceability.core.domain.physicalasset.payloads.AssetReceivedPayload;
import com.traceability.core.domain.physicalasset.payloads.AssetReceivedV2Payload;
import com.traceability.core.domain.physicalasset.payloads.AssetReceivedV3Payload;
import com.traceability.core.domain.physicalasset.payloads.AssetRegisteredPayload;
import com.traceability.core.domain.physicalasset.payloads.AssetRegisteredV4Payload;
import com.traceability.core.domain.physicalasset.payloads.AssetRegisteredV5Payload;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-051 Enmienda 2, Tarea 2 (plan §2.3, H-7): las fotos por paso se leen del <b>evento</b> de cada paso, no del estado
 * del agregado. Las versiones anteriores (sin {@code evidenceRef}) y una huella con formato inválido cuentan como «sin
 * foto», y un paso repetido (re-despacho) da una entrada por evento, cada una con su {@code sequence}.
 */
class StepEvidenceIndexTest {

    private static final String PHOTO_1 = "sha256:" + "1".repeat(64);
    private static final String PHOTO_2 = "sha256:" + "2".repeat(64);
    private static final String PHOTO_3 = "sha256:" + "3".repeat(64);
    private static final String PHOTO_4 = "sha256:" + "4".repeat(64);
    private static final Instant T = Instant.parse("2026-11-19T10:00:00Z");

    private static DomainEvent event(PhysicalAssetEventType type, com.traceability.core.domain.event.DomainEventPayload payload, int i) {
        return new DomainEvent(type, payload, T.plusSeconds(i));
    }

    private static AssetRegisteredV5Payload registered5(String photo) {
        return new AssetRegisteredV5Payload("a", "FOOD", BigDecimal.TEN, "KG", "WH", "c", null, "a", null, null, "org", "d", "don",
                "camp", "05001", "MEDELLÍN, ANTIOQUIA", "V", photo);
    }

    @Test
    void theNewVersions_giveOneEntryPerStepEvent_withTheirSequenceAndPhoto() {
        List<Entry> entries = StepEvidenceIndex.of(List.of(
                event(PhysicalAssetEventType.ASSET_REGISTERED, registered5(PHOTO_1), 0),
                event(PhysicalAssetEventType.ASSET_DISPATCHED, new AssetDispatchedV3Payload("car", "WH", "05001", "Z", "V", PHOTO_2), 1),
                event(PhysicalAssetEventType.ASSET_RECEIVED, new AssetReceivedV3Payload("fac", "rec", "05001", "Z", "V", null), 2)));

        assertThat(entries).extracting(Entry::step).containsExactly(Step.REGISTERED, Step.DISPATCHED, Step.RECEIVED);
        assertThat(entries).extracting(Entry::sequence).containsExactly(1L, 2L, 3L);
        assertThat(entries).extracting(Entry::evidenceRef).containsExactly(PHOTO_1, PHOTO_2, null);
        assertThat(entries.get(1).occurredAt()).isEqualTo(T.plusSeconds(1));
    }

    // las versiones anteriores no tenían foto de paso: «sin foto», nunca un error
    @Test
    void theOldVersions_areStepsWithoutAPhoto() {
        List<Entry> entries = StepEvidenceIndex.of(List.of(
                event(PhysicalAssetEventType.ASSET_REGISTERED, new AssetRegisteredPayload("a", "FOOD", BigDecimal.TEN, "KG", "WH", "c", null, "a", null, null), 0),
                event(PhysicalAssetEventType.ASSET_REGISTERED, new AssetRegisteredV4Payload("a", "FOOD", BigDecimal.TEN, "KG", "WH", "c", null, "a", null, null, "org", "d", "don", "camp", "05001", "Z", "V"), 1),
                event(PhysicalAssetEventType.ASSET_DISPATCHED, new AssetDispatchedPayload("car", "WH"), 2),
                event(PhysicalAssetEventType.ASSET_DISPATCHED, new AssetDispatchedV2Payload("car", "WH", "05001", "Z", "V"), 3),
                event(PhysicalAssetEventType.ASSET_RECEIVED, new AssetReceivedPayload("fac", "rec"), 4),
                event(PhysicalAssetEventType.ASSET_RECEIVED, new AssetReceivedV2Payload("fac", "rec", "05001", "Z", "V"), 5)));

        assertThat(entries).hasSize(6);
        assertThat(entries).extracting(Entry::evidenceRef).containsOnlyNulls();
    }

    @Test
    void theDelivery_readsItsEvidenceRef_fromAnyVersion_andAFreeTextOneIsNoPhoto() {
        List<Entry> entries = StepEvidenceIndex.of(List.of(
                event(PhysicalAssetEventType.ASSET_DELIVERED, new AssetDeliveredPayload("f", "b", "loc", PHOTO_3, T), 0),
                event(PhysicalAssetEventType.ASSET_DELIVERED, new AssetDeliveredV2Payload("f", "b", "loc", PHOTO_4, T, "05001", "Z", "V"), 1),
                event(PhysicalAssetEventType.ASSET_DELIVERED, new AssetDeliveredPayload("f", "b", "loc", "acta-12", T), 2)));

        assertThat(entries).extracting(Entry::step).containsOnly(Step.DELIVERED);
        assertThat(entries).extracting(Entry::evidenceRef).containsExactly(PHOTO_3, PHOTO_4, null);
    }

    // otros eventos (custodia, etc.) no son pasos con foto
    @Test
    void otherEvents_areNotSteps() {
        List<Entry> entries = StepEvidenceIndex.of(List.of(
                event(PhysicalAssetEventType.ASSET_CUSTODY_TRANSFERRED, new AssetCustodyTransferredPayload("old", "new"), 0)));

        assertThat(entries).isEmpty();
    }

    // re-despacho: una entrada por evento, cada una con su sequence; la última de un paso es la de mayor sequence
    @Test
    void aRepeatedStep_givesOneEntryPerEvent_andTheLatestIsTheHighestSequence() {
        List<Entry> entries = StepEvidenceIndex.of(List.of(
                event(PhysicalAssetEventType.ASSET_REGISTERED, registered5(null), 0),
                event(PhysicalAssetEventType.ASSET_DISPATCHED, new AssetDispatchedV3Payload("car", "WH", "05001", "Z", "V", PHOTO_1), 1),
                event(PhysicalAssetEventType.ASSET_RECEIVED, new AssetReceivedV3Payload("fac", "rec", "05001", "Z", "V", null), 2),
                event(PhysicalAssetEventType.ASSET_DISPATCHED, new AssetDispatchedV3Payload("car", "WH", "05001", "Z", "V", PHOTO_2), 3)));

        assertThat(StepEvidenceIndex.latest(entries, Step.DISPATCHED)).get().extracting(Entry::sequence, Entry::evidenceRef)
                .containsExactly(4L, PHOTO_2);
        assertThat(StepEvidenceIndex.at(entries, Step.DISPATCHED, 2)).get().extracting(Entry::evidenceRef).isEqualTo(PHOTO_1);
        // una sequence que no es de ese paso, o inexistente, no existe para ese paso
        assertThat(StepEvidenceIndex.at(entries, Step.DISPATCHED, 3)).isEmpty();
        assertThat(StepEvidenceIndex.at(entries, Step.DISPATCHED, 99)).isEmpty();
        assertThat(StepEvidenceIndex.latest(entries, Step.RECEIVED)).get().extracting(Entry::evidenceRef).isNull();
    }

    @Test
    void theRouteName_isLowercase_andParsesBack() {
        assertThat(Step.DISPATCHED.routeName()).isEqualTo("dispatched");
        assertThat(Step.fromRoute("delivered")).contains(Step.DELIVERED);
        assertThat(Step.fromRoute("registered")).contains(Step.REGISTERED);
        assertThat(Step.fromRoute("REGISTERED")).isEmpty();
        assertThat(Step.fromRoute("steps")).isEmpty();
    }
}
