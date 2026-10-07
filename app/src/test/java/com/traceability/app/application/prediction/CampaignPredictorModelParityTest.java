package com.traceability.app.application.prediction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Paridad Java ↔ Python (P3: "sin paridad no hay endpoint"). Los vectores y sus salidas los produce scikit-learn
 * ({@code scripts/predictor/export_baseline_json.py}) con los modelos entrenados offline; la tolerancia es 1e-9.
 */
class CampaignPredictorModelParityTest {

    static final double TOLERANCE = 1e-9;
    static CampaignPredictorModel model;
    static JsonNode parity;

    @BeforeAll
    static void load() throws Exception {
        try (InputStream json = CampaignPredictorModelParityTest.class.getResourceAsStream("/predictor/campaign-predictor-baseline-0.2.0.json");
             InputStream vectors = CampaignPredictorModelParityTest.class.getResourceAsStream("/predictor/campaign-predictor-baseline-0.2.0-parity.json")) {
            model = CampaignPredictorModel.load(json);
            parity = new ObjectMapper().readTree(vectors);
        }
    }

    static Map<String, Object> input(JsonNode node) {
        Map<String, Object> values = new LinkedHashMap<>();
        node.fields().forEachRemaining(e -> values.put(e.getKey(),
                e.getValue().isTextual() ? e.getValue().asText() : (Object) e.getValue().asDouble()));
        return values;
    }

    @Test
    void theModelIsTheTrainedVersion_withoutStrict_andSynthetic() {
        assertThat(model.modelVersion()).isEqualTo("baseline-0.2.0").isEqualTo(parity.get("modelVersion").asText());
        assertThat(model.synthetic()).isTrue();
        assertThat(model.excludedPolicies()).containsExactly("STRICT");
        assertThat(parity.get("vectors").size()).isGreaterThanOrEqualTo(300);
    }

    @Test
    void probabilityReachTarget_matchesScikitLearn() {
        double worst = 0;
        for (JsonNode v : parity.get("vectors")) {
            double expected = v.get("probabilityReachTarget").asDouble();
            double actual = model.probabilityReachTarget(input(v.get("input")));
            assertThat(actual).as("%s", v.get("input")).isCloseTo(expected, within(TOLERANCE));
            worst = Math.max(worst, Math.abs(actual - expected));
        }
        System.out.println("Paridad clasificador: diferencia máxima = " + worst);
    }

    @Test
    void finalPctOfTarget_matchesScikitLearn() {
        double worst = 0;
        for (JsonNode v : parity.get("vectors")) {
            double expected = v.get("finalPctOfTarget").asDouble();
            double actual = model.finalPctOfTarget(input(v.get("input")));
            assertThat(actual).as("%s", v.get("input")).isCloseTo(expected, within(TOLERANCE));
            worst = Math.max(worst, Math.abs(actual - expected));
        }
        System.out.println("Paridad regresor: diferencia máxima = " + worst);
    }
}
