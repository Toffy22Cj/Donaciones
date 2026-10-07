package com.traceability.app.application.prediction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.convocatoria.application.query.CampaignPredictionData;
import com.traceability.convocatoria.application.query.CampaignPredictionData.IntentOutcome;
import com.traceability.convocatoria.application.query.CampaignPredictionData.Outcome;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Paridad del cálculo de variables: {@link CampaignFeatureBuilder} frente a {@code build_snapshot} del generador
 * Python, con las mismas convocatorias e intenciones sintéticas ({@code export_baseline_json.py --generator}).
 * Estados: {@code CONFIRMED} sin rechazo → confirmada; con {@code rejectedByPolicy} → {@code FUNDING_REJECTED}; el
 * resto → fallida (DD-46).
 */
class CampaignFeatureBuilderParityTest {

    static final Instant START = Instant.parse("2027-01-01T00:00:00Z");
    static final double NANOS_PER_DAY = 86_400_000_000_000.0;

    static Instant at(double days) {
        return START.plusNanos(Math.round(days * NANOS_PER_DAY));
    }

    @Test
    void featuresMatchTheTrainingDefinition() throws Exception {
        JsonNode parity;
        try (InputStream in = getClass().getResourceAsStream("/predictor/campaign-predictor-baseline-0.2.0-feature-parity.json")) {
            parity = new ObjectMapper().readTree(in);
        }
        assertThat(parity.get("cases").size()).isGreaterThan(50);
        double worst = 0;
        for (JsonNode c : parity.get("cases")) {
            int duration = c.get("durationDays").asInt();
            Map<String, Integer> donorKeys = new HashMap<>();
            List<IntentOutcome> intents = new ArrayList<>();
            for (JsonNode i : c.get("intents")) {
                Outcome outcome = !"CONFIRMED".equals(i.get("status").asText()) ? Outcome.FAILED
                        : i.get("rejectedByPolicy").asBoolean() ? Outcome.FUNDING_REJECTED : Outcome.CONFIRMED;
                int donorKey = donorKeys.computeIfAbsent(i.get("donorRef").asText(), k -> donorKeys.size());
                intents.add(new IntentOutcome(outcome, (long) i.get("amount").asDouble(), donorKey,
                        outcome == Outcome.CONFIRMED ? at(i.get("dayFraction").asDouble() * duration) : null));
            }
            CampaignPredictionData data = new CampaignPredictionData("cmp", "org", "OPEN",
                    c.get("visibility").asText(), c.get("targetPolicy").asText(), (long) c.get("targetAmount").asDouble(),
                    START, at(duration), c.get("paymentMethodsEnabled").asInt(), c.get("orgPriorCampaigns").asLong(),
                    intents, false);

            Map<String, Object> actual = CampaignFeatureBuilder.build(data, at(c.get("t").asDouble() * duration)).byName();

            JsonNode expected = c.get("expected");
            expected.fields().forEachRemaining(e -> {
                if (e.getValue().isTextual()) {
                    assertThat(actual.get(e.getKey())).as(e.getKey()).isEqualTo(e.getValue().asText());
                } else {
                    double want = e.getValue().asDouble();
                    double got = ((Number) actual.get(e.getKey())).doubleValue();
                    assertThat(got).as("%s en t=%s", e.getKey(), c.get("t")).isCloseTo(want, within(1e-9 * Math.max(1, Math.abs(want))));
                }
            });
            for (String k : List.of("pctRaised", "recentVelocity", "requiredVelocity", "paceRatio")) {
                worst = Math.max(worst, Math.abs(((Number) actual.get(k)).doubleValue() - expected.get(k).asDouble()));
            }
        }
        System.out.println("Paridad de variables: diferencia máxima = " + worst);
    }
}
