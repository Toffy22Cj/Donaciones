package com.traceability.app.web.campaign;

/**
 * CV-07: no hay convocatoria con ese {@code publicCode}, o el código no tiene la forma de uno emitido (plan B6-a
 * §2.3). 404 con cuerpo fijo. Es propia de la lectura pública: {@code CampaignNotFoundException} responde 403 en las
 * operaciones de administración, y cada excepción tiene una sola traducción (Q-B60-4). El mensaje no lleva el código,
 * que es un secreto bearer (ADR-041 §2.7).
 */
public class PublicCampaignNotFoundException extends RuntimeException {
    public PublicCampaignNotFoundException() {
        super("public campaign not found");
    }
}
