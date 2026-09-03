package com.traceability.crypto.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.contracts.HashPort;
import org.erdtman.jcs.JsonCanonicalizer;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

@Component
public class JcsHashAdapter implements HashPort {

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String canonicalizeAndHash(Map<String, Object> eventData, String previousHash) {
        try {
            // 1. Clone the map to avoid mutating the caller's reference
            Map<String, Object> dataToHash = new HashMap<>(eventData);
            
            // 2. Add previousHash
            dataToHash.put("previousHash", previousHash);
            
            // 3. Remove eventHash just in case it was accidentally included
            dataToHash.remove("eventHash");

            // 4. Convert Map to JSON String
            String rawJson = mapper.writeValueAsString(dataToHash);

            // 5. Canonicalize the JSON string using JCS (RFC 8785)
            JsonCanonicalizer canonicalizer = new JsonCanonicalizer(rawJson);
            String canonicalJson = canonicalizer.getEncodedString();

            // 6. Generate SHA-256 hash
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(canonicalJson.getBytes(StandardCharsets.UTF_8));

            // 7. Convert to lowercase hex
            return bytesToHex(hashBytes);

        } catch (Exception e) {
            throw new RuntimeException("Failed to canonicalize and hash event data", e);
        }
    }

    private String bytesToHex(byte[] hashBytes) {
        StringBuilder hexString = new StringBuilder(2 * hashBytes.length);
        for (byte b : hashBytes) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex);
        }
        return hexString.toString();
    }
}
