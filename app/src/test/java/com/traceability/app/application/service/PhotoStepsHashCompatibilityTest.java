package com.traceability.app.application.service;

import com.traceability.core.application.event.EventCanonicalMapper;
import com.traceability.core.domain.event.DomainEvent;
import com.traceability.core.domain.event.DomainEventPayload;
import com.traceability.core.domain.physicalasset.payloads.AssetDispatchedPayload;
import com.traceability.core.domain.physicalasset.payloads.AssetDispatchedV2Payload;
import com.traceability.core.domain.physicalasset.payloads.AssetReceivedPayload;
import com.traceability.core.domain.physicalasset.payloads.AssetReceivedV2Payload;
import com.traceability.core.domain.physicalasset.payloads.AssetRegisteredPayload;
import com.traceability.core.domain.physicalasset.payloads.AssetRegisteredV2Payload;
import com.traceability.core.domain.physicalasset.payloads.AssetRegisteredV3Payload;
import com.traceability.core.domain.physicalasset.payloads.AssetRegisteredV4Payload;
import com.traceability.crypto.application.JcsHashAdapter;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
}
