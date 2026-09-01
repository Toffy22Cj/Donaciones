package com.traceability.core.domain.physicalasset.payloads;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class PayloadSerializationTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void testSerializationOfAssetDeliveredPayload() throws Exception {
        Instant now = Instant.now();
        AssetDeliveredPayload original = new AssetDeliveredPayload(
            "finalCust", "beneficiary", "location", "evidence", now
        );

        String json = mapper.writeValueAsString(original);
        assertNotNull(json);

        AssetDeliveredPayload deserialized = mapper.readValue(json, AssetDeliveredPayload.class);
        assertEquals("finalCust", deserialized.finalCustodianRef());
        assertEquals("beneficiary", deserialized.beneficiaryRef());
        assertEquals("location", deserialized.locationRef());
        assertEquals("evidence", deserialized.evidenceRef());
        assertEquals(now, deserialized.deliveredAt());
    }
    
    @Test
    void testSerializationOfAssetSplitPayload() throws Exception {
        AssetSplitPayload original = new AssetSplitPayload(
            "child1", 50, "kg", 100, 50, "REGISTERED", "loc1", "cust1", "root1"
        );
        
        String json = mapper.writeValueAsString(original);
        assertNotNull(json);

        AssetSplitPayload deserialized = mapper.readValue(json, AssetSplitPayload.class);
        assertEquals("child1", deserialized.childAssetId());
        assertEquals(50, deserialized.extractedQuantity());
        assertEquals("REGISTERED", deserialized.statusBeforeSplit());
    }
}
