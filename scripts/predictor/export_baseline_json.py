"""
Exporta el predictor offline de convocatorias (paxfide-predictor, baseline-0.2.0, sin STRICT) a JSON para que el
backend lo evalúe en Java sin runtime de Python (segunda autorización de Carlos, P3; ADR-044).

- Clasificador: la regresión logística (mejor modelo por Brier en metrics.json): StandardScaler + OneHotEncoder +
  coeficientes.
- Regresor del % final: HistGradientBoostingRegressor: predicción base + árboles (umbral numérico, hijo izquierdo y
  derecho, hoja con valor, dirección de los NaN).
- Vectores de paridad: entradas fijas y las salidas de scikit-learn, para el test Java (tolerancia 1e-9).

Los floats se escriben con repr (json de Python), que es exacto en ida y vuelta para float64.
No cambia el entrenamiento ni el código del predictor; solo lee sus artefactos.

Uso (con scikit-learn de la misma versión que entrenó, ver metrics.json):
    python export_baseline_json.py --models <dir con los .joblib> --snapshots <snapshots.csv> --out <dir>
"""
from __future__ import annotations

import argparse
import json
import math
from pathlib import Path

import joblib
import numpy as np
import pandas as pd
import sklearn

N_PARITY_ROWS = 300


def export_classifier(bundle: dict) -> dict:
    model = bundle["model"]
    pre = model.named_steps["pre"]
    clf = model.named_steps["clf"]
    (_, scaler, numeric), (_, onehot, categorical) = [(n, t, c) for n, t, c in pre.transformers_ if n != "remainder"]
    assert list(clf.classes_) == [0, 1]
    assert onehot.handle_unknown == "ignore"
    return {
        "type": "logistic_regression",
        "numericFeatures": list(numeric),
        "categoricalFeatures": list(categorical),
        "scalerMean": [float(v) for v in scaler.mean_],
        "scalerScale": [float(v) for v in scaler.scale_],
        "categories": {c: [str(v) for v in cats] for c, cats in zip(categorical, onehot.categories_)},
        "coefficients": [float(v) for v in clf.coef_[0]],
        "intercept": float(clf.intercept_[0]),
    }


def export_regressor(bundle: dict) -> dict:
    model = bundle["model"]
    pre = model.named_steps["pre"]
    reg = model.named_steps["reg"]
    (_, num_t, numeric), (_, onehot, categorical) = [(n, t, c) for n, t, c in pre.transformers_ if n != "remainder"]
    # "passthrough" queda como un FunctionTransformer identidad tras el fit
    assert num_t == "passthrough" or (type(num_t).__name__ == "FunctionTransformer" and num_t.func is None)
    assert onehot.handle_unknown == "ignore"
    assert reg.is_categorical_ is None
    trees = []
    for (predictor,) in reg._predictors:
        nodes = predictor.nodes
        assert not nodes["is_categorical"].any()
        trees.append([
            {"leaf": bool(n["is_leaf"]), "value": float(n["value"]), "feature": int(n["feature_idx"]),
             "threshold": float(n["num_threshold"]), "missingLeft": bool(n["missing_go_to_left"]),
             "left": int(n["left"]), "right": int(n["right"])}
            for n in nodes
        ])
    baseline = reg._baseline_prediction
    return {
        "type": "hist_gradient_boosting_regressor",
        "numericFeatures": list(numeric),
        "categoricalFeatures": list(categorical),
        "categories": {c: [str(v) for v in cats] for c, cats in zip(categorical, onehot.categories_)},
        "baselinePrediction": float(np.asarray(baseline).ravel()[0]),
        "trees": trees,
    }


N_FEATURE_CAMPAIGNS = 25


def export_feature_parity(args, model_version: str, features: list[str]) -> None:
    """Para N convocatorias sintéticas y cada t, las intenciones anteriores a t y las variables de build_snapshot."""
    import importlib.util
    spec = importlib.util.spec_from_file_location("generator", args.generator)
    generator = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(generator)
    campaigns = pd.read_csv(args.campaigns).head(N_FEATURE_CAMPAIGNS)
    intents = pd.read_csv(args.intents)
    intents = intents[intents["campaignRef"].isin(campaigns["campaignRef"])]
    cases = []
    for _, c in campaigns.iterrows():
        campaign = c.to_dict()
        closed = campaign["closedEarlyOnDay"]
        campaign["closedEarlyOnDay"] = None if pd.isna(closed) else int(closed)
        mine = intents[intents["campaignRef"] == campaign["campaignRef"]]
        for t in (0.15, 0.25, 0.5):
            snapshot = generator.build_snapshot(campaign, mine, t)
            if snapshot is None:
                continue
            past = mine[mine["dayFraction"] < t]
            cases.append({
                "t": t,
                "durationDays": int(campaign["durationDays"]),
                "targetAmount": float(campaign["targetAmount"]),
                "targetPolicy": campaign["targetPolicy"], "visibility": campaign["visibility"],
                "orgPriorCampaigns": int(campaign["orgPriorCampaigns"]),
                "paymentMethodsEnabled": int(campaign["paymentMethodsEnabled"]),
                "intents": [{"status": r["status"], "rejectedByPolicy": bool(r["rejectedByPolicy"]),
                             "amount": float(r["amount"]), "donorRef": r["donorRef"],
                             "dayFraction": float(r["dayFraction"])} for _, r in past.iterrows()],
                "expected": {k: (snapshot[k] if isinstance(snapshot[k], str) else float(snapshot[k])) for k in features},
            })
    (args.out / f"campaign-predictor-{model_version}-feature-parity.json").write_text(
        json.dumps({"modelVersion": model_version, "cases": cases}))


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--models", type=Path, required=True)
    ap.add_argument("--snapshots", type=Path, required=True)
    ap.add_argument("--out", type=Path, required=True)
    # Opcional: paridad del cálculo de variables (build_snapshot del generador) con el de Java
    ap.add_argument("--generator", type=Path)
    ap.add_argument("--campaigns", type=Path)
    ap.add_argument("--intents", type=Path)
    args = ap.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)

    clf_bundle = joblib.load(args.models / "model_logistic_regression.joblib")
    reg_bundle = joblib.load(args.models / "model_final_pct_regressor.joblib")
    assert clf_bundle["modelVersion"] == reg_bundle["modelVersion"]
    features = clf_bundle["features"]
    assert reg_bundle["features"] == features

    exported = {
        "modelVersion": clf_bundle["modelVersion"],
        "datasetVersion": clf_bundle.get("datasetVersion"),
        "synthetic": True,
        "excludedPolicies": ["STRICT"],
        "sklearnVersion": sklearn.__version__,
        "features": features,
        "classifier": export_classifier(clf_bundle),
        "finalPctRegressor": export_regressor(reg_bundle),
    }
    (args.out / f"campaign-predictor-{exported['modelVersion']}.json").write_text(json.dumps(exported))

    # Vectores de paridad: filas reales del dataset sintético (sin STRICT ni meta ya alcanzada, como el
    # entrenamiento), más casos límite: categoría desconocida (one-hot a cero) y valores extremos.
    df = pd.read_csv(args.snapshots)
    df = df[(df["alreadyReachedAtT"] == 0) & (df["targetPolicy"] != "STRICT")].head(N_PARITY_ROWS)
    rows = df[features].to_dict(orient="records")
    edge = dict(rows[0])
    rows.append({**edge, "targetPolicy": "UNKNOWN_POLICY", "visibility": "UNKNOWN_VISIBILITY"})
    rows.append({**edge, "pctRaised": 0.0, "nDonations": 0, "nDistinctDonors": 0, "meanDonationPctOfTarget": 0.0,
                 "recentVelocity": 0.0, "overallVelocity": 0.0, "paceRatio": 0.0, "failedRate": 0.0})
    rows.append({**edge, "logTargetAmount": math.log(1e12), "paceRatio": 50.0, "requiredVelocity": 0.0})
    frame = pd.DataFrame(rows, columns=features)
    probabilities = clf_bundle["model"].predict_proba(frame)[:, 1]
    final_pct = reg_bundle["model"].predict(frame)
    vectors = [{"input": {k: (str(v) if isinstance(v, str) else float(v)) for k, v in r.items()},
                "probabilityReachTarget": float(p), "finalPctOfTarget": float(f)}
               for r, p, f in zip(rows, probabilities, final_pct)]
    (args.out / f"campaign-predictor-{exported['modelVersion']}-parity.json").write_text(
        json.dumps({"modelVersion": exported["modelVersion"], "vectors": vectors}))
    if args.generator:
        export_feature_parity(args, exported["modelVersion"], features)
    print(json.dumps({"modelVersion": exported["modelVersion"], "trees": len(exported["finalPctRegressor"]["trees"]),
                      "vectors": len(vectors)}))


if __name__ == "__main__":
    main()
