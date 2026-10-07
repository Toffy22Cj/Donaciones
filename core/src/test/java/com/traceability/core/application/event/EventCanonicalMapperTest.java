package com.traceability.core.application.event;

import com.traceability.core.domain.event.SystemActor;
import com.traceability.core.domain.event.ExternalActor;
import com.traceability.core.domain.physicalasset.payloads.AssetDispatchedPayload;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EventCanonicalMapperTest {

    @Test
    void testToCanonicalMap_ExcludesActorRef() {
        EventCanonicalMapper mapper = new EventCanonicalMapper();
        AssetDispatchedPayload payload = new AssetDispatchedPayload("carrier-1", "loc-1");
        Instant occurredAt = Instant.now();
        Instant recordedAt = occurredAt.plusSeconds(1);

        // Map without actorRef being passed (since the signature doesn't take it anymore)
        // Wait, the test was meant to check that actorRef doesn't affect the hash.
        // But since actorRef is removed from the method signature of toCanonicalMap entirely,
        // it physically cannot affect the hash because it's not even an input to the mapping!
        // We will assert that the returned map does NOT contain an "actorRef" key.
        Map<String, Object> canonicalMap = mapper.toCanonicalMap(
                "evt-123",
                "stream-123",
                "PhysicalAsset",
                1L,
                "ASSET_DISPATCHED",
                "1.0",
                occurredAt,
                recordedAt,
                "origin-system",
                payload
        );

        assertEquals(false, canonicalMap.containsKey("actorRef"), "Canonical map should explicitly exclude actorRef");
    }
}
