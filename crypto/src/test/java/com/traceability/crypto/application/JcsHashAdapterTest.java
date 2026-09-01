package com.traceability.crypto.application;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class JcsHashAdapterTest {

    private final JcsHashAdapter adapter = new JcsHashAdapter();

    @Test
    void testDeterminism_DifferentInsertionOrder_SameHash() {
        Map<String, Object> map1 = new LinkedHashMap<>();
        map1.put("a", "1");
        map1.put("b", "2");
        map1.put("c", "3");

        Map<String, Object> map2 = new LinkedHashMap<>();
        map2.put("c", "3");
        map2.put("a", "1");
        map2.put("b", "2");

        String hash1 = adapter.canonicalizeAndHash(map1, "prev-hash-123");
        String hash2 = adapter.canonicalizeAndHash(map2, "prev-hash-123");

        assertEquals(hash1, hash2, "Hashing must be deterministic regardless of map insertion order");
    }

    @Test
    void testSensitivity_OneSpaceChange_DifferentHash() {
        Map<String, Object> map1 = new HashMap<>();
        map1.put("data", "value");

        Map<String, Object> map2 = new HashMap<>();
        map2.put("data", "value ");

        String hash1 = adapter.canonicalizeAndHash(map1, "prev-hash-123");
        String hash2 = adapter.canonicalizeAndHash(map2, "prev-hash-123");

        assertNotEquals(hash1, hash2, "Changing a single space must result in a completely different hash");
    }

    @Test
    void testChaining_DifferentPreviousHash_DifferentHash() {
        Map<String, Object> map1 = new HashMap<>();
        map1.put("data", "value");

        String hash1 = adapter.canonicalizeAndHash(map1, "prev-hash-123");
        String hash2 = adapter.canonicalizeAndHash(map1, "prev-hash-456");

        assertNotEquals(hash1, hash2, "Different previousHash must result in a completely different hash");
    }
}
