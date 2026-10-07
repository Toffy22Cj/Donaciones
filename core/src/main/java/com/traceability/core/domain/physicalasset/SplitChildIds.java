package com.traceability.core.domain.physicalasset;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * Identificador del hijo de una división (D-SPLIT S2, P1): UUID v5 de {@code parentAssetId + ":" + commandId} en un
 * espacio de nombres propio. Determinista (el mismo comando sobre el mismo padre da el mismo hijo, sin guardar nada),
 * sin colisiones entre padres y sin revelar el {@code commandId} (SHA-1).
 */
public final class SplitChildIds {

    /** Espacio de nombres de las divisiones. Constante: cambiarlo cambiaría los ids de las divisiones repetidas. */
    public static final UUID NS_ASSET_SPLIT = UUID.fromString("6f1c2a5e-9b47-5d3a-8e21-4c7b9d0f1a36");

    private SplitChildIds() {}

    public static String of(String parentAssetId, String commandId) {
        return v5(NS_ASSET_SPLIT, parentAssetId + ":" + commandId).toString();
    }

    /** UUID versión 5 (RFC 9562 §5.5): SHA-1 del espacio de nombres y el nombre. Java solo trae la versión 3. */
    public static UUID v5(UUID namespace, String name) {
        MessageDigest sha1;
        try {
            sha1 = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 not available", e);
        }
        sha1.update(ByteBuffer.allocate(16).putLong(namespace.getMostSignificantBits())
                .putLong(namespace.getLeastSignificantBits()).array());
        byte[] hash = sha1.digest(name.getBytes(StandardCharsets.UTF_8));
        hash[6] = (byte) ((hash[6] & 0x0f) | 0x50); // versión 5
        hash[8] = (byte) ((hash[8] & 0x3f) | 0x80); // variante RFC 9562
        ByteBuffer bytes = ByteBuffer.wrap(hash, 0, 16);
        return new UUID(bytes.getLong(), bytes.getLong());
    }
}
