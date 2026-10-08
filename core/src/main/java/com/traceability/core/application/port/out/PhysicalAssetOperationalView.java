package com.traceability.core.application.port.out;

import java.math.BigDecimal;

/**
 * Vista operacional de un activo (matriz §4b, {@code PhysicalAssetOperationalReadModel}). Excluye a propósito
 * {@code donorRef}, los datos financieros del {@code Fund} y la genealogía de la división: autenticarse no relaja el
 * perímetro de privacidad.
 */
public record PhysicalAssetOperationalView(String assetRef, String lifecycleStatus, String currentCustodianRef,
                                           String currentLocation, BigDecimal quantity, String unitOfMeasure,
                                           String campaignRef) {}
