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

    /** Lado del hermano en un paso de la prueba de inclusión (ficha R-6, Enmienda 1, E1.3). */
    public enum Side { LEFT, RIGHT }

    /** Un paso de la prueba: el hash del hermano y a qué lado se concatena (ficha R-6, Enmienda 1, E1.3). */
    public record ProofStep(Side side, String hash) {}

    /**
     * Prueba de inclusión de la hoja {@code index}, de la hoja hacia la raíz (ficha R-6, Enmienda 1, E1.3; plan del
     * Bloque C, versión 2, §2.3 (3)). Con el mismo {@code hashPair} y la misma regla del nodo impar que {@link #build}:
     * el último nodo de un nivel impar se combina consigo mismo, así que su paso es {@code {RIGHT, el mismo nodo}}.
     * Verificar: {@code nodo = hoja}; {@code LEFT → hashPair(paso, nodo)}; {@code RIGHT → hashPair(nodo, paso)};
     * válida si {@code nodo} es la raíz.
     */
    public static List<ProofStep> proof(List<String> leafHashes, int index) {
        if (leafHashes == null || leafHashes.isEmpty()) {
            throw new IllegalArgumentException("Cannot build a Merkle proof from empty leaves");
        }
        if (index < 0 || index >= leafHashes.size()) {
            throw new IllegalArgumentException("Leaf index " + index + " out of range for " + leafHashes.size() + " leaves");
        }

        List<ProofStep> path = new ArrayList<>();
        List<String> currentLevel = new ArrayList<>(leafHashes);
        int position = index;

        while (currentLevel.size() > 1) {
            if (position % 2 == 0) {
                // Hermano a la derecha; si es el último de un nivel impar, el propio nodo (como build)
                String sibling = (position + 1 < currentLevel.size()) ? currentLevel.get(position + 1) : currentLevel.get(position);
                path.add(new ProofStep(Side.RIGHT, sibling));
            } else {
                path.add(new ProofStep(Side.LEFT, currentLevel.get(position - 1)));
            }

            List<String> nextLevel = new ArrayList<>();
            for (int i = 0; i < currentLevel.size(); i += 2) {
                String left = currentLevel.get(i);
                String right = (i + 1 < currentLevel.size()) ? currentLevel.get(i + 1) : left;
                nextLevel.add(hashPair(left, right));
            }
            currentLevel = nextLevel;
            position /= 2;
        }

        return List.copyOf(path);
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
