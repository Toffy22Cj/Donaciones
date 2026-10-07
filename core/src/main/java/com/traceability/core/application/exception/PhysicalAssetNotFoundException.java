package com.traceability.core.application.exception;

/**
 * El activo no existe (plan B6-c §2.1, DD-12). Hacia fuera responde igual que "es de otra organización": la respuesta
 * no revela si un id existe en otra organización.
 */
public class PhysicalAssetNotFoundException extends RuntimeException {
    public PhysicalAssetNotFoundException(String assetId) {
        super("PhysicalAsset " + assetId + " does not exist");
    }
}
