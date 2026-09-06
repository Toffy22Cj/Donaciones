package com.traceability.core.domain.physicalasset.payloads;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.math.BigDecimal;

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
            "child1", new BigDecimal("50.0000"), "kg", new BigDecimal("100.0000"), new BigDecimal("50.0000"), "REGISTERED", "loc1", "cust1", "root1"
        );
        
        String json = mapper.writeValueAsString(original);
        assertNotNull(json);

        AssetSplitPayload deserialized = mapper.readValue(json, AssetSplitPayload.class);
        assertEquals("child1", deserialized.childAssetId());
        assertEquals(new BigDecimal("50.0000"), deserialized.extractedQuantity());
        assertEquals("REGISTERED", deserialized.statusBeforeSplit());
    }

    @Test
    void testSerializationOfAssetRegisteredPayload() throws Exception {
        AssetRegisteredPayload original = new AssetRegisteredPayload(
            "asset1", "typeA", new BigDecimal("100.0000"), "kg", "loc1", "cust1", "parent1", "root1", "alloc1", "sourceAlloc1"
        );
        String json = mapper.writeValueAsString(original);
        assertNotNull(json);
        AssetRegisteredPayload deserialized = mapper.readValue(json, AssetRegisteredPayload.class);
        assertEquals("asset1", deserialized.assetId());
        assertEquals("typeA", deserialized.assetType());
        assertEquals(new BigDecimal("100.0000"), deserialized.quantity());
        assertEquals("sourceAlloc1", deserialized.sourceAllocationId());
    }

    @Test
    void testSerializationOfAssetDispatchedPayload() throws Exception {
        AssetDispatchedPayload original = new AssetDispatchedPayload("carrier1", "loc1");
        String json = mapper.writeValueAsString(original);
        assertNotNull(json);
        AssetDispatchedPayload deserialized = mapper.readValue(json, AssetDispatchedPayload.class);
        assertEquals("carrier1", deserialized.carrierRef());
        assertEquals("loc1", deserialized.previousLocation());
    }

    @Test
    void testSerializationOfAssetReceivedPayload() throws Exception {
        AssetReceivedPayload original = new AssetReceivedPayload("loc2", "receiver1");
        String json = mapper.writeValueAsString(original);
        assertNotNull(json);
        AssetReceivedPayload deserialized = mapper.readValue(json, AssetReceivedPayload.class);
        assertEquals("loc2", deserialized.facilityLocation());
        assertEquals("receiver1", deserialized.receiverRef());
    }

    @Test
    void testSerializationOfAssetCustodyTransferredPayload() throws Exception {
        AssetCustodyTransferredPayload original = new AssetCustodyTransferredPayload("cust1", "cust2");
        String json = mapper.writeValueAsString(original);
        assertNotNull(json);
        AssetCustodyTransferredPayload deserialized = mapper.readValue(json, AssetCustodyTransferredPayload.class);
        assertEquals("cust1", deserialized.previousCustodianRef());
        assertEquals("cust2", deserialized.newCustodianRef());
    }

    @Test
    void testSerializationOfAssetDepletedPayload() throws Exception {
        AssetDepletedPayload original = new AssetDepletedPayload(new BigDecimal("100.0000"));
        String json = mapper.writeValueAsString(original);
        assertNotNull(json);
        AssetDepletedPayload deserialized = mapper.readValue(json, AssetDepletedPayload.class);
        assertEquals(new BigDecimal("100.0000"), deserialized.previousQuantity());
    }

    @Test
    void testSerializationOfAssetSplitCompensatedPayload() throws Exception {
        AssetSplitCompensatedPayload original = new AssetSplitCompensatedPayload("child1", new BigDecimal("50.0000"));
        String json = mapper.writeValueAsString(original);
        assertNotNull(json);
        AssetSplitCompensatedPayload deserialized = mapper.readValue(json, AssetSplitCompensatedPayload.class);
        assertEquals("child1", deserialized.childAssetId());
        assertEquals(new BigDecimal("50.0000"), deserialized.reintegratedQuantity());
    }
    
    @Test
    void testBackwardCompatibility_LongToBigDecimal() throws Exception {
        // This simulates a v1 event coming from Mongo where quantity was persisted as an integer/long
        String legacyJson = "{\"assetId\":\"asset1\",\"assetType\":\"typeA\",\"quantity\":100,\"unitOfMeasure\":\"kg\"}";
        AssetRegisteredPayload deserialized = mapper.readValue(legacyJson, AssetRegisteredPayload.class);
        
        // Jackson should seamlessly parse it into a BigDecimal
        assertNotNull(deserialized.quantity());
        assertEquals(new BigDecimal("100"), deserialized.quantity());
    }
}
