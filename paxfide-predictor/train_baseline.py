"""
Entrenamiento y evaluación baseline del componente predictivo de convocatorias.

ADR de referencia: ADR-044 (PROPUESTO).

Pregunta: dado el estado de una convocatoria en t ∈ {0.15, 0.25, 0.50},
          ¿P(recaudado al cierre >= 100 % de la meta)?
Complementario: % final estimado (regresión).

Decisiones de evaluación (ver ADR-044 §2.4):
  - Partición POR CONVOCATORIA (GroupShuffleSplit + GroupKFold sobre campaignRef):
    ningún campaignRef aparece en train y test a la vez. Se verifica con assert.
  - Se excluyen snapshots con alreadyReachedAtT == 1 (etiqueta trivial: inflaría métricas).
  - Se excluyen las convocatorias STRICT (ADR-044 P7/D3): rechazan la donación que excedería la meta, así
    que casi nunca llegan al 100 % por diseño. No son un caso a predecir; dejarlas dentro añade
    negativos triviales que inflan las métricas de cualquier modelo que vea targetPolicy.
    El generador no produce CLOSE_ON_TARGET con rechazo del exceso (su CLOSE_ON_TARGET acepta la
    donación y cierra la convocatoria), así que no hay otra variante con el mismo problema.
  - La mejora sobre el baseline de ritmo se mide también fold a fold (los mismos 5 folds de
    GroupKFold para todos los modelos): media y desviación de la diferencia de Brier.
  - Métricas principales: Brier score y calibración (se va a mostrar una probabilidad),
    más log loss y ROC AUC. Accuracy solo como referencia.
  - Tres modelos: baseline de ritmo (logística con solo paceRatio), regresión logística
    completa y HistGradientBoosting (árboles).

Salida en --out:
  metrics.json, calibration.png, reliability_by_t.png,
  model_<nombre>.joblib, sample_predictions.json
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

import joblib
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np
import pandas as pd
import sklearn
from sklearn.calibration import calibration_curve
from sklearn.compose import ColumnTransformer
from sklearn.ensemble import HistGradientBoostingClassifier, HistGradientBoostingRegressor
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import (accuracy_score, brier_score_loss, log_loss,
                             mean_absolute_error, roc_auc_score)
from sklearn.model_selection import GroupKFold, GroupShuffleSplit
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import OneHotEncoder, StandardScaler

MODEL_VERSION = "baseline-0.2.0"  # 0.2.0: sin STRICT (ADR-044 P7/D3)
EXCLUDED_POLICIES = ("STRICT",)
SEED = 20261006

NUMERIC = [
    "logTargetAmount", "durationDays", "orgPriorCampaigns", "paymentMethodsEnabled",
    "pctTimeElapsed", "pctRaised", "nDonations", "nDistinctDonors",
    "meanDonationPctOfTarget", "recentVelocity", "overallVelocity",
    "requiredVelocity", "paceRatio", "failedRate",
]
CATEGORICAL = ["targetPolicy", "visibility"]
FEATURES = NUMERIC + CATEGORICAL
LABEL = "label_reachedTarget"
LABEL_REG = "label_finalPctOfTarget"


def make_models() -> dict[str, Pipeline]:
    pre_linear = ColumnTransformer([
        ("num", StandardScaler(), NUMERIC),
        ("cat", OneHotEncoder(handle_unknown="ignore"), CATEGORICAL),
    ])
    pre_tree = ColumnTransformer([
        ("num", "passthrough", NUMERIC),
        ("cat", OneHotEncoder(handle_unknown="ignore", sparse_output=False), CATEGORICAL),
    ])
    return {
        "pace_baseline": Pipeline([
            ("pre", ColumnTransformer([("num", StandardScaler(), ["paceRatio"])])),
            ("clf", LogisticRegression(max_iter=1000)),
        ]),
        "logistic_regression": Pipeline([
            ("pre", pre_linear),
            ("clf", LogisticRegression(max_iter=2000, C=1.0)),
        ]),
        "hist_gradient_boosting": Pipeline([
            ("pre", pre_tree),
            ("clf", HistGradientBoostingClassifier(
                max_iter=300, learning_rate=0.05, max_leaf_nodes=15,
                l2_regularization=1.0, random_state=SEED)),
        ]),
    }


def expected_calibration_error(y, p, n_bins=10) -> float:
    bins = np.linspace(0, 1, n_bins + 1)
    idx = np.clip(np.digitize(p, bins) - 1, 0, n_bins - 1)
    ece = 0.0
    for b in range(n_bins):
        m = idx == b
        if m.any():
            ece += m.mean() * abs(y[m].mean() - p[m].mean())
    return float(ece)


def scores(y, p) -> dict:
    return {
        "n": int(len(y)),
        "positiveRate": round(float(np.mean(y)), 4),
        "brier": round(float(brier_score_loss(y, p)), 4),
        "logLoss": round(float(log_loss(y, p, labels=[0, 1])), 4),
        "rocAuc": round(float(roc_auc_score(y, p)), 4) if len(np.unique(y)) > 1 else None,
        "ece": round(expected_calibration_error(np.asarray(y), np.asarray(p)), 4),
        "accuracyAt0_5": round(float(accuracy_score(y, p >= 0.5)), 4),
    }


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", type=Path, default=Path("data"))
    ap.add_argument("--out", type=Path, default=Path("output"))
    args = ap.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)

    manifest = json.loads((args.data / "manifest.json").read_text())
    df = pd.read_csv(args.data / "snapshots.csv")

    # Guardia anti-leakage: ninguna columna de etiqueta entra como feature.
    assert not any(c.startswith("label_") for c in FEATURES), "Etiqueta usada como feature"

    n_total = len(df)
    df = df[df["alreadyReachedAtT"] == 0].reset_index(drop=True)
    n_trivial = n_total - len(df)
    n_before_policy = len(df)
    df = df[~df["targetPolicy"].isin(EXCLUDED_POLICIES)].reset_index(drop=True)
    n_excluded_policy = n_before_policy - len(df)

    X, y, groups = df[FEATURES], df[LABEL].to_numpy(), df["campaignRef"].to_numpy()

    # ---- Hold-out por convocatoria
    gss = GroupShuffleSplit(n_splits=1, test_size=0.2, random_state=SEED)
    tr, te = next(gss.split(X, y, groups))
    overlap = set(groups[tr]) & set(groups[te])
    assert not overlap, f"Leakage: {len(overlap)} convocatorias en train y test"

    results: dict = {
        "modelVersion": MODEL_VERSION,
        "datasetVersion": manifest["datasetVersion"],
        "datasetSha256": manifest["sha256"]["snapshots.csv"],
        "synthetic": manifest["synthetic"],
        "sklearnVersion": sklearn.__version__,
        "split": {
            "strategy": "GroupShuffleSplit por campaignRef (20 % test) + GroupKFold(5) en train",
            "snapshotsTotal": n_total,
            "snapshotsExcludedAlreadyReached": n_trivial,
            "excludedPolicies": list(EXCLUDED_POLICIES),
            "excludedPoliciesReason": "STRICT rechaza la donación que excedería la meta: casi nunca llega al 100 % por diseño (ADR-044 P7/D3)",
            "snapshotsExcludedByPolicy": n_excluded_policy,
            "trainSnapshots": int(len(tr)), "testSnapshots": int(len(te)),
            "trainCampaigns": int(len(set(groups[tr]))), "testCampaigns": int(len(set(groups[te]))),
            "campaignOverlapTrainTest": len(overlap),
        },
        "models": {},
    }

    test_probs: dict[str, np.ndarray] = {}
    cv_brier_by_model: dict[str, list[float]] = {}
    for name, model in make_models().items():
        # CV agrupada dentro de train (estabilidad de la métrica)
        cv_brier = []
        for ftr, fva in GroupKFold(n_splits=5).split(X.iloc[tr], y[tr], groups[tr]):
            assert not set(groups[tr][ftr]) & set(groups[tr][fva])
            m = make_models()[name].fit(X.iloc[tr].iloc[ftr], y[tr][ftr])
            cv_brier.append(brier_score_loss(y[tr][fva], m.predict_proba(X.iloc[tr].iloc[fva])[:, 1]))

        cv_brier_by_model[name] = cv_brier
        model.fit(X.iloc[tr], y[tr])
        p = model.predict_proba(X.iloc[te])[:, 1]
        test_probs[name] = p
        per_t = {}
        for t in sorted(df["t"].unique()):
            mask = df.iloc[te]["t"].to_numpy() == t
            per_t[str(t)] = scores(y[te][mask], p[mask])
        results["models"][name] = {
            "cvBrierMean": round(float(np.mean(cv_brier)), 4),
            "cvBrierStd": round(float(np.std(cv_brier)), 4),
            "test": scores(y[te], p),
            "testByT": per_t,
        }
        joblib.dump({"model": model, "features": FEATURES, "modelVersion": MODEL_VERSION,
                     "datasetVersion": manifest["datasetVersion"]},
                    args.out / f"model_{name}.joblib")

    # ---- Mejora sobre el baseline de ritmo, emparejada por fold (mismos folds para todos los modelos)
    base_cv = np.array(cv_brier_by_model["pace_baseline"])
    results["improvementOverPaceBaseline"] = {
        name: {
            "cvBrierGainMean": round(float(np.mean(base_cv - np.array(cv))), 4),
            "cvBrierGainStd": round(float(np.std(base_cv - np.array(cv))), 4),
            "cvBrierGainPerFold": [round(float(g), 4) for g in (base_cv - np.array(cv))],
            "testBrierGain": round(results["models"]["pace_baseline"]["test"]["brier"]
                                   - results["models"][name]["test"]["brier"], 4),
        }
        for name, cv in cv_brier_by_model.items() if name != "pace_baseline"
    }

    # ---- Control de leakage: con etiquetas permutadas (dentro de train) el AUC en test real
    # debe centrarse en 0.5. Una sola permutación NO sirve: con features muy informativas,
    # un modelo de ruido toma pesos de signo aleatorio y su AUC se aleja de 0.5 en
    # cualquier dirección. Se usa la media de N permutaciones.
    rng = np.random.default_rng(SEED)
    perm_aucs = []
    for _ in range(30):
        pm = make_models()["logistic_regression"].fit(X.iloc[tr], rng.permutation(y[tr]))
        perm_aucs.append(float(roc_auc_score(y[te], pm.predict_proba(X.iloc[te])[:, 1])))
    perm_mean = float(np.mean(perm_aucs))
    results["leakageControl"] = {
        "check": "logistic_regression entrenada 30 veces con etiquetas permutadas; AUC medio en test real",
        "rocAucMean": round(perm_mean, 4),
        "rocAucStd": round(float(np.std(perm_aucs)), 4),
        "passed": bool(abs(perm_mean - 0.5) < 0.05),
    }
    assert results["leakageControl"]["passed"], f"Control de leakage falló: AUC permutado medio = {perm_mean}"

    # ---- Regresión complementaria: % final de la meta
    reg = Pipeline([
        ("pre", ColumnTransformer([
            ("num", "passthrough", NUMERIC),
            ("cat", OneHotEncoder(handle_unknown="ignore", sparse_output=False), CATEGORICAL)])),
        ("reg", HistGradientBoostingRegressor(max_iter=300, learning_rate=0.05, random_state=SEED)),
    ])
    reg.fit(X.iloc[tr], df[LABEL_REG].to_numpy()[tr])
    pred_pct = reg.predict(X.iloc[te])
    results["regressionFinalPct"] = {
        "model": "HistGradientBoostingRegressor",
        "testMAE_pctPoints": round(float(mean_absolute_error(df[LABEL_REG].to_numpy()[te], pred_pct)) * 100, 2),
        "naiveMAE_linearExtrapolation_pctPoints": round(float(mean_absolute_error(
            df[LABEL_REG].to_numpy()[te], df.iloc[te]["paceRatio"].to_numpy())) * 100, 2),
    }
    joblib.dump({"model": reg, "features": FEATURES, "modelVersion": MODEL_VERSION},
                args.out / "model_final_pct_regressor.joblib")

    # ---- Gráficos de calibración
    fig, ax = plt.subplots(figsize=(6, 6))
    ax.plot([0, 1], [0, 1], "--", color="#888", label="Calibración perfecta")
    for name, p in test_probs.items():
        fy, fx = calibration_curve(y[te], p, n_bins=10, strategy="quantile")
        ax.plot(fx, fy, "o-", label=f"{name} (Brier {results['models'][name]['test']['brier']})")
    ax.set_xlabel("Probabilidad predicha")
    ax.set_ylabel("Frecuencia observada de alcanzar la meta")
    ax.set_title("Curva de calibración — test (datos SINTÉTICOS)")
    ax.legend(loc="upper left", fontsize=8)
    fig.tight_layout()
    fig.savefig(args.out / "calibration.png", dpi=130)
    plt.close(fig)

    best = min(results["models"], key=lambda k: results["models"][k]["test"]["brier"])
    fig, axes = plt.subplots(1, 3, figsize=(13, 4.2), sharey=True)
    for ax, t in zip(axes, sorted(df["t"].unique())):
        mask = df.iloc[te]["t"].to_numpy() == t
        fy, fx = calibration_curve(y[te][mask], test_probs[best][mask], n_bins=8, strategy="quantile")
        ax.plot([0, 1], [0, 1], "--", color="#888")
        ax.plot(fx, fy, "o-")
        ax.set_title(f"t = {t}  (Brier {results['models'][best]['testByT'][str(t)]['brier']})")
        ax.set_xlabel("Probabilidad predicha")
    axes[0].set_ylabel("Frecuencia observada")
    fig.suptitle(f"Calibración por punto t — {best} (datos SINTÉTICOS)")
    fig.tight_layout()
    fig.savefig(args.out / "reliability_by_t.png", dpi=130)
    plt.close(fig)
    results["bestByBrier"] = best

    # ---- Ejemplo del artefacto de predicción (forma propuesta en ADR-044 §2.3)
    sample = df.iloc[te].head(3)
    probs = test_probs[best][:3]
    pcts = pred_pct[:3]
    (args.out / "sample_predictions.json").write_text(json.dumps([
        {
            "kind": "ESTIMATE",
            "disclaimer": "Estimación estadística. No es un hecho verificable del sistema de trazabilidad.",
            "campaignRef": r.campaignRef,
            "t": float(r.t),
            "probabilityReachTarget": round(float(pr), 4),
            "estimatedFinalPctOfTarget": round(float(pc), 4),
            "modelVersion": MODEL_VERSION,
            "datasetVersion": manifest["datasetVersion"],
            "synthetic": True,
        } for r, pr, pc in zip(sample.itertuples(), probs, pcts)
    ], indent=2, ensure_ascii=False))

    (args.out / "metrics.json").write_text(json.dumps(results, indent=2, ensure_ascii=False))
    print(json.dumps({k: results[k] for k in ("split", "bestByBrier", "regressionFinalPct", "leakageControl", "improvementOverPaceBaseline")}, indent=2, ensure_ascii=False))
    print("\nmodelo                   CV-Brier       test-Brier  logLoss  AUC     ECE")
    for name, r in results["models"].items():
        t = r["test"]
        print(f"{name:24s} {r['cvBrierMean']:.4f}±{r['cvBrierStd']:.4f}  {t['brier']:.4f}      "
              f"{t['logLoss']:.4f}   {t['rocAuc']:.4f}  {t['ece']:.4f}")


if __name__ == "__main__":
    main()
