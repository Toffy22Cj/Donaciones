package com.traceability.crypto.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * Merkle Tree implementation for hashing batches of events.
 */
public class MerkleTree {

    private final String root;

    private MerkleTree(String root) {
        this.root = root;
    }

    /**
     * Builds a Merkle Tree from the given list of leaf hashes.
     * Respects the exact order of insertion.
     * If a level has an odd number of nodes, the last node is duplicated and hashed with itself.
     */
    public static MerkleTree build(List<String> leafHashes) {
        if (leafHashes == null || leafHashes.isEmpty()) {
            throw new IllegalArgumentException("Cannot build Merkle Tree from empty leaves");
        }
        
        List<String> currentLevel = new ArrayList<>(leafHashes);
        
        while (currentLevel.size() > 1) {
            List<String> nextLevel = new ArrayList<>();
            
            for (int i = 0; i < currentLevel.size(); i += 2) {
                String left = currentLevel.get(i);
                // If it's the last odd element, duplicate it (hash with itself)
                String right = (i + 1 < currentLevel.size()) ? currentLevel.get(i + 1) : left;
                
                nextLevel.add(hashPair(left, right));
            }
            currentLevel = nextLevel;
        }
        
        return new MerkleTree(currentLevel.get(0));
    }

    public String getRoot() {
        return root;
    }

    private static String hashPair(String left, String right) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String concatenated = left + right; 
            byte[] hashBytes = digest.digest(concatenated.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    private static String bytesToHex(byte[] hashBytes) {
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
