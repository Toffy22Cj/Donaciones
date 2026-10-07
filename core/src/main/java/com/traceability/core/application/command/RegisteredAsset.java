package com.traceability.core.application.command;

/**
 * Resultado de un registro de activo (plan B6-c §2.1), leído del evento de génesis: un duplicado devuelve exactamente
 * lo mismo que el registro original.
 *
 * @param donationRef solo en el Camino B; {@code null} en el Camino A
 * @param campaignRef {@code null} si el activo no tiene convocatoria
 */
public record RegisteredAsset(String assetId, String donationRef, String campaignRef) {}
