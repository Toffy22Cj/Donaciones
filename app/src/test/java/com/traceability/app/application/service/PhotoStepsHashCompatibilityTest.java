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
import com.traceability.core.domain.physicalasset.payloads.AssetDispatchedPayload;
import com.traceability.core.domain.physicalasset.payloads.AssetDispatchedV2Payload;
import com.traceability.core.domain.physicalasset.payloads.AssetDispatchedV3Payload;
import com.traceability.core.domain.physicalasset.payloads.AssetReceivedPayload;
import com.traceability.core.domain.physicalasset.payloads.AssetReceivedV2Payload;
import com.traceability.core.domain.physicalasset.payloads.AssetReceivedV3Payload;
import com.traceability.core.domain.physicalasset.payloads.AssetRegisteredPayload;
import com.traceability.core.domain.physicalasset.payloads.AssetRegisteredV2Payload;
import com.traceability.core.domain.physicalasset.payloads.AssetRegisteredV3Payload;
import com.traceability.core.domain.physicalasset.payloads.AssetRegisteredV4Payload;
import com.traceability.core.domain.physicalasset.payloads.AssetRegisteredV5Payload;
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
 * ADR-051 Enmienda 2, E2-2 y definición de hecho 1 (plan de la Tarea 1, grupo A): el hash de los eventos
 * {@code ASSET_REGISTERED} 1.0 a 4.0, {@code ASSET_DISPATCHED} 1.0 y 2.0 y {@code ASSET_RECEIVED} 1.0 y 2.0 ya guardados
 * <b>no cambia</b> al añadir las versiones con {@code evidenceRef}. Los valores esperados se calcularon con el código
 * de {@code develop} {@code e555c30}, <b>antes</b> de tocar ninguna clase ni el registro, y quedan fijos: si una clase
 * existente gana un campo, su mapa canónico cambia y este test falla.
 */
class PhotoStepsHashCompatibilityTest {

    static final EventCanonicalMapper MAPPER = new EventCanonicalMapper();
    static final JcsHashAdapter HASH = new JcsHashAdapter();
    static final Instant T0 = Instant.parse("2026-11-19T10:00:00Z");

    private record Old(String type, String version, DomainEventPayload payload) {}

    /** Cada versión vieja de los tres eventos, una vez, con un valor distinto en cada campo. */
    static List<Old> oldVersions() {
        BigDecimal q = new BigDecimal("10.0000");
        return List.of(
                new Old("ASSET_REGISTERED", "1.0", new AssetRegisteredPayload("a-1", "FOOD", q, "KG", "Bodega norte",
                        "custodian-1", null, "a-1", "alloc-1", null)),
                new Old("ASSET_REGISTERED", "2.0", new AssetRegisteredV2Payload("a-2", "FOOD", q, "KG", "Bodega norte",
                        "custodian-1", null, "a-2", "alloc-1", null, "org-1", "donor-1", "donation-1")),
                new Old("ASSET_REGISTERED", "3.0", new AssetRegisteredV3Payload("a-3", "FOOD", q, "KG", "Bodega norte",
                        "custodian-1", null, "a-3", "alloc-1", null, "org-1", "donor-1", "donation-1", "campaign-1")),
                new Old("ASSET_REGISTERED", "4.0", new AssetRegisteredV4Payload("a-4", "FOOD", q, "KG", "Bodega norte",
                        "custodian-1", null, "a-4", "alloc-1", null, "org-1", "donor-1", "donation-1", "campaign-1",
                        "05001", "MEDELLÍN, ANTIOQUIA", "DIVIPOLA-2026-09")),
                new Old("ASSET_DISPATCHED", "1.0", new AssetDispatchedPayload("carrier-1", "Bodega norte")),
                new Old("ASSET_DISPATCHED", "2.0", new AssetDispatchedV2Payload("carrier-1", "Bodega norte", "05001",
                        "MEDELLÍN, ANTIOQUIA", "DIVIPOLA-2026-09")),
                new Old("ASSET_RECEIVED", "1.0", new AssetReceivedPayload("Centro de acopio", "receiver-1")),
                new Old("ASSET_RECEIVED", "2.0", new AssetReceivedV2Payload("Centro de acopio", "receiver-1", "05001",
                        "MEDELLÍN, ANTIOQUIA", "DIVIPOLA-2026-09")));
    }

    /** El hash de cada evento viejo, cada uno como génesis de su propio flujo. */
    static List<String> oldHashes() {
        List<String> out = new ArrayList<>();
        int i = 0;
        for (Old o : oldVersions()) {
            Map<String, Object> event = MAPPER.toCanonicalMap("evt-old-" + i, "asset-old", "PhysicalAsset", 1, o.type(),
                    o.version(), T0.plusSeconds(i), T0.plusSeconds(i + 1), "TRACEABILITY_CORE", o.payload());
            out.add(HASH.canonicalizeAndHash(event, DomainEvent.GENESIS_HASH));
            i++;
        }
        return out;
    }

    @Test
    void test1_theHashOfEveryOldVersion_isIdenticalBeforeAndAfterTheAmendment() {
        assertThat(oldHashes()).containsExactly(
                "809dacdb61c2411960590808ed192a0e6d9c0b02fbd31c803aff00093cfc1456",
                "41823407af2ebab6f120c52660a46339ba1e9393054685b56d2177a3666db4c6",
                "6a5976850edd0ef1937d52bb1fa64517b01b7e9487d7fb9bdcfac12fdab607a8",
                "f7f0034e645eff541a9ee6e49a8f4a10f38291a964bc699346b503c09ab41201",
                "983a3c226a95bba21c84de7e402ccc969d9cdbc81cb2746b182b4bf3b61d4d3c",
                "341713a1b7226e1933892e4addbd2665054c7b4d92c4c0bd0da04af60fa5c6dc",
                "9f08a55f4343234949125622556781d035bd2afe312309e3207653afdab64882",
                "5e02fef43c8f50a03920f01302633dc5801bb44da2f1cec82fee68f71a20270d");
    }

    private static final String PHOTO_A = "sha256:" + "a".repeat(64);
    private static final String PHOTO_B = "sha256:" + "b".repeat(64);

    private static Map<String, Object> registered(String evidenceRef) {
        return MAPPER.toCanonicalMap("evt-new-1", "asset-new", "PhysicalAsset", 1, "ASSET_REGISTERED", "5.0", T0, T0,
                "TRACEABILITY_CORE", new AssetRegisteredV5Payload("a-5", "FOOD", new BigDecimal("10.0000"), "KG",
                        "Bodega norte", "custodian-1", null, "a-5", "alloc-1", null, "org-1", "donor-1", "donation-1",
                        "campaign-1", "05001", "MEDELLÍN, ANTIOQUIA", "DIVIPOLA-2026-09", evidenceRef));
    }

    private static Map<String, Object> dispatched(String evidenceRef) {
        return MAPPER.toCanonicalMap("evt-new-2", "asset-new", "PhysicalAsset", 2, "ASSET_DISPATCHED", "3.0", T0, T0,
                "TRACEABILITY_CORE", new AssetDispatchedV3Payload("carrier-1", "Bodega norte", "05001",
                        "MEDELLÍN, ANTIOQUIA", "DIVIPOLA-2026-09", evidenceRef));
    }

    private static Map<String, Object> received(String evidenceRef) {
        return MAPPER.toCanonicalMap("evt-new-3", "asset-new", "PhysicalAsset", 3, "ASSET_RECEIVED", "3.0", T0, T0,
                "TRACEABILITY_CORE", new AssetReceivedV3Payload("Centro", "receiver-1", "05001", "MEDELLÍN, ANTIOQUIA",
                        "DIVIPOLA-2026-09", evidenceRef));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> payloadOf(Map<String, Object> event) {
        return (Map<String, Object>) event.get("payload");
    }

    // test 2: la huella de la foto de cada paso entra en el mapa canónico y, con él, en el hash
    @Test
    void test2_theEvidenceRefOfANewEvent_isInsideItsHash() {
        for (var step : List.<java.util.function.Function<String, Map<String, Object>>>of(
                PhotoStepsHashCompatibilityTest::registered, PhotoStepsHashCompatibilityTest::dispatched,
                PhotoStepsHashCompatibilityTest::received)) {
            Map<String, Object> event = step.apply(PHOTO_A);
            assertThat(payloadOf(event)).containsEntry("evidenceRef", PHOTO_A);
            String hash = HASH.canonicalizeAndHash(event, DomainEvent.GENESIS_HASH);
            assertThat(HASH.canonicalizeAndHash(step.apply(PHOTO_B), DomainEvent.GENESIS_HASH)).isNotEqualTo(hash);
            assertThat(HASH.canonicalizeAndHash(step.apply(null), DomainEvent.GENESIS_HASH)).isNotEqualTo(hash);
        }
        assertThat(registered(PHOTO_A)).containsEntry("schemaVersion", "5.0");
        assertThat(dispatched(PHOTO_A)).containsEntry("schemaVersion", "3.0");
        assertThat(received(PHOTO_A)).containsEntry("schemaVersion", "3.0");
    }

    // test 4: sin foto, el mapa canónico lleva "evidenceRef": null
    @Test
    void test4_withoutAPhoto_theCanonicalMapCarriesANullEvidenceRef() {
        for (Map<String, Object> event : List.of(registered(null), dispatched(null), received(null))) {
            assertThat(payloadOf(event)).containsKey("evidenceRef").containsEntry("evidenceRef", null);
        }
    }

    /** Siete eventos guardados de un mismo activo: los cuatro viejos de dos formas y los nuevos con y sin foto. */
    private static List<StoredEvent> mixedStoredChain() {
        List<Map<String, Object>> maps = new ArrayList<>();
        int seq = 1;
        // génesis vieja (4.0), despacho viejo (2.0) y recepción vieja (2.0)
        for (Old o : List.of(oldVersions().get(3), oldVersions().get(5), oldVersions().get(7))) {
            maps.add(MAPPER.toCanonicalMap("evt-" + seq, "asset-mix", "PhysicalAsset", seq, o.type(), o.version(),
                    T0.plusSeconds(100L * seq), T0.plusSeconds(100L * seq + 1), "TRACEABILITY_CORE", o.payload()));
            seq++;
        }
        // despacho y recepción nuevos, el uno con foto y el otro sin ella
        DomainEventPayload[] fresh = {
                new AssetDispatchedV3Payload("carrier-2", "Centro", "05001", "MEDELLÍN, ANTIOQUIA", "DIVIPOLA-2026-09", PHOTO_A),
                new AssetReceivedV3Payload("Escuela", "receiver-2", "05001", "MEDELLÍN, ANTIOQUIA", "DIVIPOLA-2026-09", null)};
        String[] types = {"ASSET_DISPATCHED", "ASSET_RECEIVED"};
        for (int i = 0; i < fresh.length; i++) {
            maps.add(MAPPER.toCanonicalMap("evt-" + seq, "asset-mix", "PhysicalAsset", seq, types[i],
                    EventPayloadRegistry.getSchemaVersionForClass(fresh[i].getClass()), T0.plusSeconds(100L * seq),
                    T0.plusSeconds(100L * seq + 1), "TRACEABILITY_CORE", fresh[i]));
            seq++;
        }
        List<String> hashes = new ArrayList<>();
        String previous = DomainEvent.GENESIS_HASH;
        for (Map<String, Object> event : maps) {
            previous = HASH.canonicalizeAndHash(event, previous);
            hashes.add(previous);
        }
        List<StoredEvent> stored = new ArrayList<>();
        for (int i = 0; i < maps.size(); i++) {
            Map<String, Object> m = maps.get(i);
            stored.add(new StoredEvent((String) m.get("eventId"), "asset-mix", "PhysicalAsset", i + 1,
                    (String) m.get("eventType"), (String) m.get("schemaVersion"), (String) m.get("occurredAt"),
                    (String) m.get("recordedAt"), "TRACEABILITY_CORE", new HashMap<>(payloadOf(m)),
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
                .check(Map.of("asset-mix", new SequenceRange(1, chain.size())));
    }

    // test 3: una cadena mixta (eventos viejos y nuevos, con y sin foto) pasa la verificación de integridad
    @Test
    void test3_aMixedChain_oldAndNewEvents_withAndWithoutAPhoto_isGreen() {
        List<StoredEvent> chain = mixedStoredChain();
        assertThat(chain).extracting(StoredEvent::schemaVersion).containsExactly("4.0", "2.0", "2.0", "3.0", "3.0");
        assertThat(check(chain)).isEmpty();
    }

    // test 2 (negativa): alterar la huella guardada de un evento nuevo es un HASH_MISMATCH
    @Test
    void test2_aTamperedEvidenceRefInANewStoredEvent_isAHashMismatch() {
        List<StoredEvent> chain = new ArrayList<>(mixedStoredChain());
        StoredEvent dispatched = chain.get(3);
        Map<String, Object> payload = new HashMap<>(dispatched.payload());
        payload.put("evidenceRef", PHOTO_B);
        chain.set(3, new StoredEvent(dispatched.eventId(), dispatched.streamId(), dispatched.aggregateType(),
                dispatched.sequence(), dispatched.eventType(), dispatched.schemaVersion(), dispatched.occurredAt(),
                dispatched.recordedAt(), dispatched.origin(), payload, dispatched.previousHash(), dispatched.eventHash(), null));

        assertThat(check(chain)).containsExactly(new Finding("asset-mix", 4, Problem.HASH_MISMATCH));
    }

    // y quitar la huella a un evento que la tenía también (el null no equivale a la huella)
    @Test
    void test2_aRemovedEvidenceRef_isAHashMismatch() {
        List<StoredEvent> chain = new ArrayList<>(mixedStoredChain());
        StoredEvent dispatched = chain.get(3);
        Map<String, Object> payload = new HashMap<>(dispatched.payload());
        payload.put("evidenceRef", null);
        chain.set(3, new StoredEvent(dispatched.eventId(), dispatched.streamId(), dispatched.aggregateType(),
                dispatched.sequence(), dispatched.eventType(), dispatched.schemaVersion(), dispatched.occurredAt(),
                dispatched.recordedAt(), dispatched.origin(), payload, dispatched.previousHash(), dispatched.eventHash(), null));

        assertThat(check(chain)).containsExactly(new Finding("asset-mix", 4, Problem.HASH_MISMATCH));
    }
}
