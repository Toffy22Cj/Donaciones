package com.traceability.app.application.prediction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Evaluación en Java del predictor offline exportado a JSON por {@code scripts/predictor/export_baseline_json.py}
 * (segunda autorización de Carlos, P3; ADR-044). Sin runtime de Python ni dependencias nuevas.
 * <ul>
 *   <li><b>Clasificador</b> (regresión logística): {@code StandardScaler} en las numéricas, one-hot en las categóricas
 *   (una categoría desconocida deja su grupo a cero, como {@code handle_unknown="ignore"}), producto con los
 *   coeficientes y sigmoide.</li>
 *   <li><b>Regresor del % final</b> ({@code HistGradientBoostingRegressor}): predicción base más la hoja de cada árbol;
 *   en cada nodo, {@code x <= umbral} va a la izquierda y un NaN sigue {@code missingLeft}, como scikit-learn.</li>
 * </ul>
 * La paridad con scikit-learn (tolerancia 1e-9) la prueba {@code CampaignPredictorModelParityTest}.
 */
public final class CampaignPredictorModel {

    private record Node(boolean leaf, double value, int feature, double threshold, boolean missingLeft, int left,
                        int right) {}

    private final String modelVersion;
    private final boolean synthetic;
    private final List<String> excludedPolicies;
    private final List<String> numeric;
    private final List<String> categorical;
    private final Map<String, List<String>> classifierCategories;
    private final double[] mean;
    private final double[] scale;
    private final double[] coefficients;
    private final double intercept;
    private final List<String> regressorNumeric;
    private final List<String> regressorCategorical;
    private final Map<String, List<String>> regressorCategories;
    private final double baseline;
    private final List<Node[]> trees;

    private CampaignPredictorModel(JsonNode root) {
        modelVersion = root.get("modelVersion").asText();
        synthetic = root.get("synthetic").asBoolean();
        excludedPolicies = strings(root.get("excludedPolicies"));
        JsonNode c = root.get("classifier");
        numeric = strings(c.get("numericFeatures"));
        categorical = strings(c.get("categoricalFeatures"));
        classifierCategories = categories(c.get("categories"), categorical);
        mean = doubles(c.get("scalerMean"));
        scale = doubles(c.get("scalerScale"));
        coefficients = doubles(c.get("coefficients"));
        intercept = c.get("intercept").asDouble();
        JsonNode r = root.get("finalPctRegressor");
        regressorNumeric = strings(r.get("numericFeatures"));
        regressorCategorical = strings(r.get("categoricalFeatures"));
        regressorCategories = categories(r.get("categories"), regressorCategorical);
        baseline = r.get("baselinePrediction").asDouble();
        trees = new ArrayList<>();
        for (JsonNode tree : r.get("trees")) {
            Node[] nodes = new Node[tree.size()];
            for (int i = 0; i < nodes.length; i++) {
                JsonNode n = tree.get(i);
                nodes[i] = new Node(n.get("leaf").asBoolean(), n.get("value").asDouble(), n.get("feature").asInt(),
                        n.get("threshold").asDouble(), n.get("missingLeft").asBoolean(), n.get("left").asInt(),
                        n.get("right").asInt());
            }
            trees.add(nodes);
        }
        int width = numeric.size() + classifierCategories.values().stream().mapToInt(List::size).sum();
        if (mean.length != numeric.size() || scale.length != numeric.size() || coefficients.length != width) {
            throw new IllegalStateException("Predictor JSON inconsistente: dimensiones del clasificador");
        }
    }

    public static CampaignPredictorModel load(InputStream json) {
        try {
            return new CampaignPredictorModel(new ObjectMapper().readTree(json));
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo leer el predictor", e);
        }
    }

    public String modelVersion() {
        return modelVersion;
    }

    public boolean synthetic() {
        return synthetic;
    }

    public List<String> excludedPolicies() {
        return excludedPolicies;
    }

    /** P(recaudado al cierre ≥ 100 % de la meta). */
    public double probabilityReachTarget(Map<String, Object> features) {
        double z = intercept;
        double[] x = encode(features, numeric, categorical, classifierCategories, true);
        for (int i = 0; i < x.length; i++) {
            z += coefficients[i] * x[i];
        }
        return 1.0 / (1.0 + Math.exp(-z));
    }

    /** % final estimado de la meta (1.0 = 100 %). */
    public double finalPctOfTarget(Map<String, Object> features) {
        double[] x = encode(features, regressorNumeric, regressorCategorical, regressorCategories, false);
        double raw = baseline;
        for (Node[] tree : trees) {
            Node node = tree[0];
            while (!node.leaf()) {
                double v = x[node.feature()];
                boolean left = Double.isNaN(v) ? node.missingLeft() : v <= node.threshold();
                node = tree[left ? node.left() : node.right()];
            }
            raw += node.value();
        }
        return raw;
    }

    private double[] encode(Map<String, Object> features, List<String> num, List<String> cat,
                            Map<String, List<String>> cats, boolean standardize) {
        int width = num.size() + cats.values().stream().mapToInt(List::size).sum();
        double[] x = new double[width];
        for (int i = 0; i < num.size(); i++) {
            Object value = features.get(num.get(i));
            double v = value == null ? Double.NaN : ((Number) value).doubleValue();
            x[i] = standardize ? (v - mean[i]) / scale[i] : v;
        }
        int offset = num.size();
        for (String name : cat) {
            List<String> values = cats.get(name);
            int index = values.indexOf(String.valueOf(features.get(name)));
            if (index >= 0) {
                x[offset + index] = 1.0;
            }
            offset += values.size();
        }
        return x;
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(v -> values.add(v.asText()));
        return List.copyOf(values);
    }

    private static double[] doubles(JsonNode array) {
        double[] values = new double[array.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = array.get(i).asDouble();
        }
        return values;
    }

    private static Map<String, List<String>> categories(JsonNode node, List<String> order) {
        Map<String, List<String>> values = new java.util.LinkedHashMap<>();
        for (String name : order) {
            values.put(name, strings(node.get(name)));
        }
        return values;
    }
}
