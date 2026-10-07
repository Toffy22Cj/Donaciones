package com.traceability.convocatoria.application.query;

import java.time.Instant;
import java.util.List;

/**
 * Datos de una convocatoria para el predictor (P3; ADR-044). Interno: lo consume {@code app} y nunca sale en una
 * respuesta. Las intenciones llevan una clave de donante opaca, local a esta lectura ({@code donorKey}), en lugar del
 * {@code donorRef}: solo sirve para contar donantes distintos.
 *
 * @param targetAmount solo con {@code MONETARY}; {@code null} si no
 * @param targetPolicy solo con {@code MONETARY}; {@code null} si no
 */
public record CampaignPredictionData(String campaignRef, String organizationRef, String status, String visibility,
                                     String targetPolicy, Long targetAmount, Instant startDate, Instant endDate,
                                     int paymentMethodsEnabled, long orgPriorCampaigns, List<IntentOutcome> intents,
                                     boolean truncated) {

    public enum Outcome { CONFIRMED, FAILED, FUNDING_REJECTED, IN_PROGRESS }

    /** {@code confirmedAt} solo con {@code CONFIRMED}. */
    public record IntentOutcome(Outcome outcome, long amount, int donorKey, Instant confirmedAt) {}
}
