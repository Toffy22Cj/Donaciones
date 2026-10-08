package com.traceability.app.application.prediction;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Variables de una convocatoria en un instante, con los nombres y la definición del entrenamiento offline
 * ({@code paxfide-predictor}, {@code generate_synthetic_dataset.py}, {@code build_snapshot}). Importes como fracción de
 * la meta; velocidades en fracción de la meta por día.
 */
public record CampaignFeatures(double logTargetAmount, double durationDays, double orgPriorCampaigns,
                               double paymentMethodsEnabled, double pctTimeElapsed, double pctRaised,
                               double nDonations, double nDistinctDonors, double meanDonationPctOfTarget,
                               double recentVelocity, double overallVelocity, double requiredVelocity,
                               double paceRatio, double failedRate, String targetPolicy, String visibility) {

    /** Valores por nombre de variable, como las columnas del entrenamiento. */
    public Map<String, Object> byName() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("logTargetAmount", logTargetAmount);
        values.put("durationDays", durationDays);
        values.put("orgPriorCampaigns", orgPriorCampaigns);
        values.put("paymentMethodsEnabled", paymentMethodsEnabled);
        values.put("pctTimeElapsed", pctTimeElapsed);
        values.put("pctRaised", pctRaised);
        values.put("nDonations", nDonations);
        values.put("nDistinctDonors", nDistinctDonors);
        values.put("meanDonationPctOfTarget", meanDonationPctOfTarget);
        values.put("recentVelocity", recentVelocity);
        values.put("overallVelocity", overallVelocity);
        values.put("requiredVelocity", requiredVelocity);
        values.put("paceRatio", paceRatio);
        values.put("failedRate", failedRate);
        values.put("targetPolicy", targetPolicy);
        values.put("visibility", visibility);
        return values;
    }
}
