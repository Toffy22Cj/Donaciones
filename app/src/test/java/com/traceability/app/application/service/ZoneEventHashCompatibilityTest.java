package com.traceability.app.application.service;

import com.traceability.contracts.SequenceRange;
import com.traceability.core.application.event.EventCanonicalMapper;
import com.traceability.core.application.event.EventPayloadRegistry;
import com.traceability.core.application.integrity.EventIntegrityChecker.Finding;
import com.traceability.core.application.integrity.EventIntegrityChecker.Problem;
import com.traceability.core.application.integrity.RecomputingEventIntegrityChecker;
import com.traceability.core.application.port.out.StoredEventReadPort;
import com.traceability.core.application.port.out.StoredEventReadPort.StoredEvent;
import com.traceability.core.domain.event.DomainEvent;
import com.traceability.core.domain.event.DomainEventPayload;
import com.traceability.core.domain.physicalasset.payloads.AssetDeliveredPayload;
import com.traceability.core.domain.physicalasset.payloads.AssetDeliveredV2Payload;
import com.traceability.core.domain.physicalasset.payloads.AssetDispatchedV2Payload;
import com.traceability.core.domain.physicalasset.payloads.AssetReceivedV2Payload;
import com.traceability.core.domain.physicalasset.payloads.AssetDispatchedPayload;
import com.traceability.core.domain.physicalasset.payloads.AssetReceivedPayload;
import com.traceability.core.domain.physicalasset.payloads.AssetRegisteredV3Payload;
import com.traceability.crypto.application.JcsHashAdapter;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Enmienda 2 de ADR-029, §4 (plan de la zona, test 1): el hash de los eventos <b>viejos</b> no cambia. Los valores
 * esperados se calcularon con el código <b>anterior</b> a la enmienda (rama {@code feat/core-zona-en-cada-paso}, antes
 * de tocar ninguna clase de payload, sobre {@code 6ad03ff}) y quedan fijos: si una clase existente gana un campo, su mapa canónico cambia y
 * este test falla.
 */
class ZoneEventHashCompatibilityTest {

    private static final EventCanonicalMapper MAPPER = new EventCanonicalMapper();
    private static final JcsHashAdapter HASH = new JcsHashAdapter();
    private static final Instant T0 = Instant.parse("2026-10-20T10:00:00Z");

    /** Una cadena 3.0 / 1.0 / 1.0 / 1.0 como las que ya hay guardadas, con su hash encadenado desde la génesis. */
    static List<Map<String, Object>> oldChain() {
        List<DomainEventPayload> payloads = List.of(
                new AssetRegisteredV3Payload("asset-old-1", "FOOD", new BigDecimal("10.0000"), "KG", "Bodega norte",
                        "custodian-1", null, "asset-old-1", "alloc-1", null, "org-1", null, null, "campaign-1"),
                new AssetDispatchedPayload("carrier-1", "Bodega norte"),
                new AssetReceivedPayload("Centro de acopio", "receiver-1"),
                new AssetDeliveredPayload("custodian-2", "beneficiary-1", "Escuela rural",
                        "sha256:" + "a".repeat(64), T0.plusSeconds(400)));
        String[] types = {"ASSET_REGISTERED", "ASSET_DISPATCHED", "ASSET_RECEIVED", "ASSET_DELIVERED"};
        String[] versions = {"3.0", "1.0", "1.0", "1.0"};
        List<Map<String, Object>> maps = new ArrayList<>();
        for (int i = 0; i < payloads.size(); i++) {
            maps.add(MAPPER.toCanonicalMap("evt-old-" + (i + 1), "asset-old-1", "PhysicalAsset", i + 1, types[i],
                    versions[i], T0.plusSeconds(100L * i), T0.plusSeconds(100L * i + 1), "TRACEABILITY_CORE",
                    payloads.get(i)));
        }
        return maps;
    }

    static List<String> hashes(List<Map<String, Object>> chain) {
        List<String> out = new ArrayList<>();
        String previous = DomainEvent.GENESIS_HASH;
        for (Map<String, Object> event : chain) {
            previous = HASH.canonicalizeAndHash(event, previous);
            out.add(previous);
        }
        return out;
    }

    @Test
    void test1_theHashOfOldEvents_isIdenticalBeforeAndAfterTheAmendment() {
        assertThat(hashes(oldChain())).containsExactly(
                "9e295d4297c14c5d4aadd5a864f6fcfcb3cff14245a43daeeed740409a6d146f",
                "2b46d5e1290be0314eb7f613934250c332e7c835d01ad2cadc6b0086f1c83214",
                "a37abf3973a655bcf3b133d14e3b33cdea0c88c9f5660dd1ca036cb4401f3b3e",
                "76e49af8b84f9212164624aa5351a2604f3f82f2ed4ece0eb12a5927cfb4676e");
    }

    private static Map<String, Object> received(String municipalityCode, String zoneName, String catalogVersion) {
        return MAPPER.toCanonicalMap("evt-new-1", "asset-new-1", "PhysicalAsset", 3, "ASSET_RECEIVED", "2.0", T0, T0,
                "TRACEABILITY_CORE", new AssetReceivedV2Payload("Centro de acopio", "receiver-1", municipalityCode,
                        zoneName, catalogVersion));
    }

    // test 2: la zona del evento nuevo entra en el mapa canónico y, con él, en el hash
    @Test
    void test2_theZoneOfANewEvent_isInsideItsHash() {
        Map<String, Object> event = received("05002", "ABEJORRAL, ANTIOQUIA", "DIVIPOLA-2026-09");
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) event.get("payload");
        assertThat(payload).containsEntry("municipalityCode", "05002")
                .containsEntry("zoneName", "ABEJORRAL, ANTIOQUIA")
                .containsEntry("catalogVersion", "DIVIPOLA-2026-09");
        assertThat(event).containsEntry("schemaVersion", "2.0");

        String hash = HASH.canonicalizeAndHash(event, DomainEvent.GENESIS_HASH);
        assertThat(HASH.canonicalizeAndHash(received("05001", "ABEJORRAL, ANTIOQUIA", "DIVIPOLA-2026-09"),
                DomainEvent.GENESIS_HASH)).isNotEqualTo(hash);
        assertThat(HASH.canonicalizeAndHash(received("05002", "ABEJORRAL", "DIVIPOLA-2026-09"), DomainEvent.GENESIS_HASH))
                .isNotEqualTo(hash);
        assertThat(HASH.canonicalizeAndHash(received("05002", "ABEJORRAL, ANTIOQUIA", "DIVIPOLA-2026-10"),
                DomainEvent.GENESIS_HASH)).isNotEqualTo(hash);
    }

    /** Una cadena mixta guardada: los cuatro eventos viejos de {@link #oldChain()} y tres nuevos con zona. */
    private static List<StoredEvent> mixedStoredChain() {
        List<DomainEventPayload> fresh = List.of(
                new AssetDispatchedV2Payload("carrier-2", "Escuela rural", null, null, null),
                new AssetReceivedV2Payload("Centro norte", "receiver-2", "05001", "MEDELLÍN, ANTIOQUIA", "DIVIPOLA-2026-09"),
                new AssetDeliveredV2Payload("custodian-3", "beneficiary-2", "Vereda alta", "sha256:" + "d".repeat(64),
                        T0.plusSeconds(900), "11001", "BOGOTÁ, D.C.", "DIVIPOLA-2026-09"));
        String[] types = {"ASSET_DISPATCHED", "ASSET_RECEIVED", "ASSET_DELIVERED"};
        List<Map<String, Object>> maps = new ArrayList<>(oldChain());
        for (int i = 0; i < fresh.size(); i++) {
            long seq = 5 + i;
            maps.add(MAPPER.toCanonicalMap("evt-old-" + seq, "asset-old-1", "PhysicalAsset", seq, types[i],
                    EventPayloadRegistry.getSchemaVersionForClass(fresh.get(i).getClass()), T0.plusSeconds(100L * seq),
                    T0.plusSeconds(100L * seq + 1), "TRACEABILITY_CORE", fresh.get(i)));
        }
        List<String> hashes = hashes(maps);
        List<StoredEvent> stored = new ArrayList<>();
        for (int i = 0; i < maps.size(); i++) {
            Map<String, Object> m = maps.get(i);
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = new HashMap<>((Map<String, Object>) m.get("payload"));
            stored.add(new StoredEvent((String) m.get("eventId"), "asset-old-1", "PhysicalAsset", i + 1,
                    (String) m.get("eventType"), (String) m.get("schemaVersion"), (String) m.get("occurredAt"),
                    (String) m.get("recordedAt"), "TRACEABILITY_CORE", payload,
                    i == 0 ? DomainEvent.GENESIS_HASH : hashes.get(i - 1), hashes.get(i), null));
        }
        return stored;
    }

    private static List<Finding> check(List<StoredEvent> chain) {
        StoredEventReadPort port = new StoredEventReadPort() {
            @Override
            public List<StoredEvent> findByCoverage(Map<String, SequenceRange> coverage) {
                return chain;
            }

            @Override
            public Optional<StoredEvent> find(String streamId, long sequence) {
                return chain.stream().filter(e -> e.sequence() == sequence).findFirst();
            }
        };
        return new RecomputingEventIntegrityChecker(port, MAPPER, HASH)
                .check(Map.of("asset-old-1", new SequenceRange(1, chain.size())));
    }

    // la verificación de integridad de una cadena mixta (eventos viejos y nuevos) queda en verde
    @Test
    void theIntegrityCheckOfAMixedChain_oldAndNewEvents_isGreen() {
        List<StoredEvent> chain = mixedStoredChain();
        assertThat(chain).extracting(StoredEvent::schemaVersion).containsExactly("3.0", "1.0", "1.0", "1.0", "2.0", "2.0", "2.0");
        assertThat(check(chain)).isEmpty();
    }

    // y un cambio de la zona guardada de un evento nuevo se detecta como HASH_MISMATCH
    @Test
    void aTamperedZoneInANewStoredEvent_isAHashMismatch() {
        List<StoredEvent> chain = new ArrayList<>(mixedStoredChain());
        StoredEvent received = chain.get(5);
        Map<String, Object> payload = new HashMap<>(received.payload());
        payload.put("zoneName", "OTRO MUNICIPIO, ANTIOQUIA");
        chain.set(5, new StoredEvent(received.eventId(), received.streamId(), received.aggregateType(), received.sequence(),
                received.eventType(), received.schemaVersion(), received.occurredAt(), received.recordedAt(),
                received.origin(), payload, received.previousHash(), received.eventHash(), null));

        assertThat(check(chain)).containsExactly(new Finding("asset-old-1", 6, Problem.HASH_MISMATCH));
    }
}
