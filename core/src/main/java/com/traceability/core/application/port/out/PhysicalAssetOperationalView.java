package com.traceability.core.application.port.out;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Vista operacional de un activo (matriz §4b, {@code PhysicalAssetOperationalReadModel}). Excluye a propósito
 * {@code donorRef}, los datos financieros del {@code Fund} y la genealogía de la división: autenticarse no relaja el
 * perímetro de privacidad. {@code assetType} es el registrado (ficha R-6, G-4b). {@code lastEventAt} es el
 * {@code occurredAt} del último evento del stream por {@code sequence} (ficha R-6, Enmienda 1, E2, G-9): solo lo rellena el
 * listado de la organización; la vista individual lo deja nulo.
 */
public record PhysicalAssetOperationalView(String assetRef, String lifecycleStatus, String currentCustodianRef,
                                           String currentLocation, BigDecimal quantity, String unitOfMeasure,
                                           String campaignRef, String assetType, Instant lastEventAt,
                                           /** ADR-029 Enmienda 2, P6: la zona sellada; {@code null} si no la hay (E-1). */
                                           String municipalityCode, String zoneName) {
    /** Sin zona. */
    public PhysicalAssetOperationalView(String assetRef, String lifecycleStatus, String currentCustodianRef,
                                        String currentLocation, BigDecimal quantity, String unitOfMeasure,
                                        String campaignRef, String assetType, Instant lastEventAt) {
        this(assetRef, lifecycleStatus, currentCustodianRef, currentLocation, quantity, unitOfMeasure, campaignRef,
                assetType, lastEventAt, null, null);
    }
}
