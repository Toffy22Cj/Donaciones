package com.traceability.app.application.media;

/**
 * ADR-051, Tarea 3: no hay portada que servir (convocatoria no visible por su código, sin portada, retirada o sin
 * bytes). La única causa del 404 de la ruta pública, para que todos sean idénticos, como el logo.
 */
public class CampaignCoverNotFoundException extends RuntimeException {
    public CampaignCoverNotFoundException() {
        super("Campaign cover NotFound");
    }
}
