"""
Mock v2 de predicciones + gráficos de referencia (básico y avanzado).

ADR-044 (PROPUESTO). Artefacto ACADÉMICO: datos sintéticos, no es contrato HTTP.

Qué añade frente al mock v1:
  - raisedSeries: recaudo acumulado (% de la meta) observado hasta t (dato de entrada).
  - projection:   cuantiles p10 / p50 / p90 del % final de la meta, de tres
                  HistGradientBoostingRegressor(loss="quantile") entrenados SOLO con
                  el split de train (misma partición por convocatoria que train_baseline.py).

Salida (--out):
  mock_predictions_response_v2.json
  chart_basic.png      probabilidad actual por convocatoria (barras + banda de riesgo)
  chart_advanced.png   recaudo observado + proyección con banda p10–p90, y la
                       trayectoria de la probabilidad en paneles separados (sin doble eje)
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
from sklearn.compose import ColumnTransformer
from sklearn.ensemble import HistGradientBoostingRegressor
from sklearn.model_selection import GroupShuffleSplit
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import OneHotEncoder

from train_baseline import CATEGORICAL, NUMERIC, FEATURES, SEED, EXCLUDED_POLICIES

# Paleta: status (riesgo) siempre con etiqueta de texto; serie y texto neutros.
STATUS = {"ON_TRACK": "#0ca30c", "WATCH": "#fab219", "HIGH_RISK": "#d03b3b"}
STATUS_LABEL = {"ON_TRACK": "En camino", "WATCH": "Vigilar", "HIGH_RISK": "En riesgo"}
SERIES = "#2a78d6"
TEXT, TEXT_2, GRID, SURFACE = "#0b0b0b", "#52514e", "#e4e3df", "#fcfcfb"
BANDS = {"HIGH_RISK": "p < 0.35", "WATCH": "0.35 <= p < 0.65", "ON_TRACK": "p >= 0.65"}
NAMES = ["Kits de higiene La Boquilla", "Agua potable Nelson Mandela", "Mercados para adultos mayores"]


def band(p: float) -> str:
    return "HIGH_RISK" if p < 0.35 else ("WATCH" if p < 0.65 else "ON_TRACK")


def fmt_pct(p: float) -> str:
    return "< 1 %" if p < 0.01 else f"{p * 100:.0f} %"


def quantile_models(X, y, groups):
    tr, _ = next(GroupShuffleSplit(n_splits=1, test_size=0.2, random_state=SEED).split(X, y, groups))
    models = {}
    for q in (0.1, 0.5, 0.9):
        m = Pipeline([
            ("pre", ColumnTransformer([
                ("num", "passthrough", NUMERIC),
                ("cat", OneHotEncoder(handle_unknown="ignore", sparse_output=False), CATEGORICAL)])),
            ("reg", HistGradientBoostingRegressor(loss="quantile", quantile=q, max_iter=300,
                                                  learning_rate=0.05, random_state=SEED)),
        ])
        models[q] = m.fit(X.iloc[tr], y[tr])
    return models, set(groups[tr])


def raised_series(intents: pd.DataFrame, ref: str, target: float, t_max: float, n=11) -> list[dict]:
    its = intents[(intents.campaignRef == ref) & (intents.status == "CONFIRMED") & (~intents.rejectedByPolicy)]
    pts = []
    for t in np.linspace(0, t_max, n):
        pts.append({"t": round(float(t), 3),
                    "pctRaised": round(float(its[its.dayFraction < t].amount.sum() / target), 4)})
    return pts


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", type=Path, default=Path("data"))
    ap.add_argument("--models", type=Path, default=Path("output"))
    ap.add_argument("--out", type=Path, default=Path("output"))
    args = ap.parse_args()

    df = pd.read_csv(args.data / "snapshots.csv")
    intents = pd.read_csv(args.data / "donation_intents.csv")
    clf = joblib.load(args.models / "model_logistic_regression.joblib")
    # Mismo filtro que train_baseline.py, para que la partición por convocatoria coincida (ADR-044 P7/D3).
    df_fit = df[(df.alreadyReachedAtT == 0) & (~df.targetPolicy.isin(EXCLUDED_POLICIES))].reset_index(drop=True)
    qmodels, train_refs = quantile_models(df_fit[FEATURES], df_fit["label_finalPctOfTarget"].to_numpy(),
                                          df_fit["campaignRef"].to_numpy())

    # Ejemplos: SOLO convocatorias de test (no vistas al entrenar), sin STRICT (ver ADR-044 P7).
    cand = df_fit[(df_fit.targetPolicy != "STRICT") & (~df_fit.campaignRef.isin(train_refs))]
    cand = cand.groupby("campaignRef").filter(lambda g: len(g) == 3)
    last = cand[cand.t == 0.5].assign(p=lambda d: clf["model"].predict_proba(d[FEATURES])[:, 1]).sort_values("p")
    picks = [last.iloc[int(len(last) * q)].campaignRef for q in (0.12, 0.5, 0.88)]

    items = []
    for name, ref in zip(NAMES, picks):
        g = df[df.campaignRef == ref].sort_values("t")
        probs = clf["model"].predict_proba(g[FEATURES])[:, 1]
        cur = g.iloc[[-1]]
        q = {k: float(m.predict(cur[FEATURES])[0]) for k, m in qmodels.items()}
        q10, q50, q90 = sorted([q[0.1], q[0.5], q[0.9]])   # evita cruce de cuantiles
        history = [{"t": float(r.t), "pctRaisedAtT": round(float(r.pctRaised), 4),
                    "probabilityReachTarget": round(float(p), 4), "riskBand": band(p)}
                   for r, p in zip(g.itertuples(), probs)]
        items.append({
            "campaignRef": ref, "campaignName": name,
            "targetAmount": int(cur.targetAmount.iloc[0]), "currency": "COP",
            "targetPolicy": cur.targetPolicy.iloc[0], "durationDays": int(cur.durationDays.iloc[0]),
            "latest": history[-1],
            "history": history,
            "raisedSeries": raised_series(intents, ref, float(cur.targetAmount.iloc[0]), 0.5),
            "projection": {"atT": 0.5, "finalPctOfTarget": {"p10": round(q10, 4), "p50": round(q50, 4), "p90": round(q90, 4)},
                           "note": "El modelo estima solo el valor al cierre; la curva entre t y 1.0 es interpolación visual."},
        })

    out = {
        "kind": "ESTIMATE", "synthetic": True,
        "disclaimer": "Estimación estadística sobre datos simulados. No es un hecho verificable del sistema de trazabilidad.",
        "organizationRef": "org-demo-001", "generatedAt": "2026-10-06T19:30:00-05:00",
        "model": {"modelVersion": "baseline-0.1.0", "algorithm": "logistic_regression + quantile_hgb",
                  "datasetVersion": "synthetic-v1", "target": "P(recaudado al cierre >= 100% de la meta)"},
        "riskBands": {**BANDS, "status": "PROPUESTA - umbrales no aprobados"},
        "items": items,
    }
    (args.out / "mock_predictions_response_v2.json").write_text(json.dumps(out, indent=2, ensure_ascii=False))

    plt.rcParams.update({"font.size": 10, "axes.edgecolor": GRID, "axes.labelcolor": TEXT_2,
                         "xtick.color": TEXT_2, "ytick.color": TEXT_2, "figure.facecolor": SURFACE,
                         "axes.facecolor": SURFACE})

    # ---------------- Básico: probabilidad actual por convocatoria
    fig, ax = plt.subplots(figsize=(8, 3.2))
    for i, it in enumerate(reversed(items)):
        p, b = it["latest"]["probabilityReachTarget"], it["latest"]["riskBand"]
        ax.barh(i, max(p, 0.005), height=0.45, color=STATUS[b])
        ax.text(min(p, 1) + 0.015, i, f"{fmt_pct(p)} · {STATUS_LABEL[b]}", va="center", color=TEXT, fontsize=10)
    ax.set_yticks(range(len(items)), [it["campaignName"] for it in reversed(items)], color=TEXT)
    ax.set_xlim(0, 1.25)
    ax.set_xticks([0, .25, .5, .75, 1], ["0 %", "25 %", "50 %", "75 %", "100 %"])
    ax.xaxis.grid(True, color=GRID, lw=0.8); ax.set_axisbelow(True)
    for s in ("top", "right", "left"):
        ax.spines[s].set_visible(False)
    ax.set_xlabel("Probabilidad estimada de alcanzar la meta (t = 50 %)")
    ax.set_title("Riesgo de convocatorias — estimación con datos simulados", loc="left", color=TEXT, fontsize=12)
    fig.tight_layout(); fig.savefig(args.out / "chart_basic.png", dpi=150); plt.close(fig)

    # ---------------- Avanzado: proyección con incertidumbre + trayectoria de probabilidad
    fig, axes = plt.subplots(2, 3, figsize=(13, 6.4), gridspec_kw={"height_ratios": [2.2, 1]}, sharex=True)
    for col, it in enumerate(items):
        ax, axp = axes[0, col], axes[1, col]
        s = it["raisedSeries"]; pr = it["projection"]["finalPctOfTarget"]
        t_now, v_now = s[-1]["t"], s[-1]["pctRaised"]
        ax.plot([p["t"] for p in s], [p["pctRaised"] * 100 for p in s], color=SERIES, lw=2, label="Recaudo observado")
        ax.fill_between([t_now, 1], [v_now * 100, pr["p10"] * 100], [v_now * 100, pr["p90"] * 100],
                        color=SERIES, alpha=0.15, lw=0, label="Rango probable (p10–p90)")
        ax.plot([t_now, 1], [v_now * 100, pr["p50"] * 100], color=SERIES, lw=2, ls="--", label="Proyección (p50)")
        ax.axhline(100, color=TEXT_2, lw=1, ls=":"); ax.text(0.01, 102, "Meta", color=TEXT_2, fontsize=9)
        ax.axvline(t_now, color=GRID, lw=1); ax.text(t_now + 0.01, 3, "hoy", color=TEXT_2, fontsize=9)
        ax.text(1.0, pr["p50"] * 100, f" {pr['p50'] * 100:.0f} %", va="center", color=TEXT, fontsize=9)
        ax.set_title(it["campaignName"], loc="left", color=TEXT, fontsize=10)
        ax.set_ylim(0, max(130, pr["p90"] * 100 + 15)); ax.yaxis.grid(True, color=GRID, lw=0.8); ax.set_axisbelow(True)
        for sp in ("top", "right"):
            ax.spines[sp].set_visible(False)
        if col == 0:
            ax.set_ylabel("% de la meta"); ax.legend(loc="upper left", fontsize=8, frameon=False)

        h = it["history"]
        axp.plot([x["t"] for x in h], [x["probabilityReachTarget"] * 100 for x in h], color=TEXT_2, lw=1.5, zorder=1)
        for x in h:
            axp.scatter(x["t"], x["probabilityReachTarget"] * 100, s=55, color=STATUS[x["riskBand"]],
                        edgecolor=SURFACE, linewidth=2, zorder=2)
            axp.text(x["t"], x["probabilityReachTarget"] * 100 + 12, fmt_pct(x["probabilityReachTarget"]),
                     ha="center", fontsize=8, color=TEXT)
        axp.set_ylim(0, 125); axp.set_yticks([0, 50, 100], ["0", "50", "100"])
        axp.yaxis.grid(True, color=GRID, lw=0.8); axp.set_axisbelow(True)
        for sp in ("top", "right"):
            axp.spines[sp].set_visible(False)
        axp.set_xlim(0, 1.08); axp.set_xticks([0, .15, .25, .5, 1], ["0", "15", "25", "50", "100"])
        axp.set_xlabel("% del tiempo de la convocatoria")
        if col == 0:
            axp.set_ylabel("P(meta) %")
            from matplotlib.lines import Line2D
            axp.legend(handles=[Line2D([], [], marker="o", ls="", color=STATUS[k], label=STATUS_LABEL[k])
                                for k in ("HIGH_RISK", "WATCH", "ON_TRACK")],
                       loc="upper right", fontsize=8, frameon=False, ncol=3)
    fig.suptitle("Proyección de recaudo y probabilidad de alcanzar la meta — estimación con datos SIMULADOS",
                 x=0.01, ha="left", color=TEXT, fontsize=12)
    fig.tight_layout(); fig.savefig(args.out / "chart_advanced.png", dpi=150); plt.close(fig)
    print(json.dumps([{k: it[k] for k in ("campaignName", "latest", "projection")} for it in items], indent=1, ensure_ascii=False))


if __name__ == "__main__":
    main()
