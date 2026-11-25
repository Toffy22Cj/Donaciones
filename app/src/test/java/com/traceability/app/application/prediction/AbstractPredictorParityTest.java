package com.traceability.app.application.prediction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * ADR-044 Enmienda 4 (PROPUESTA): la misma prueba para cada versión del modelo que se conserva.
 * Paridad Java ↔ Python (P3: "sin paridad no hay endpoint"). Los vectores y sus salidas los produce scikit-learn
 * ({@code scripts/predictor/export_baseline_json.py}) con los modelos entrenados offline; la tolerancia es 1e-9.
 */
abstract class AbstractPredictorParityTest {

    static final double TOLERANCE = 1e-9;
    CampaignPredictorModel model;
    JsonNode parity;

    /** La versión del modelo que prueba esta clase: {@code baseline-0.4.0} (vigente) o {@code baseline-0.3.0} (conservada). */
    abstract String version();

    @BeforeEach
    void load() throws Exception {
        String base = "/predictor/campaign-predictor-" + version();
        try (InputStream json = AbstractPredictorParityTest.class.getResourceAsStream(base + ".json");
             InputStream vectors = AbstractPredictorParityTest.class.getResourceAsStream(base + "-parity.json")) {
            assertThat(json).as("falta el artefacto " + base + ".json").isNotNull();
            assertThat(vectors).as("falta " + base + "-parity.json").isNotNull();
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
        assertThat(model.modelVersion()).isEqualTo(version()).isEqualTo(parity.get("modelVersion").asText());
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
