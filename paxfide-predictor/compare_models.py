"""
Compara dos modelos entrenados (p. ej. baseline-0.3.0 y baseline-0.4.0) sobre EL MISMO conjunto de validación
(ADR-044 Enmienda 4, E4-6, PROPUESTA).

El conjunto de validación se genera aquí, con el generador de la mezcla de curvas y una semilla DISTINTA de la de
entrenamiento (por defecto 20261107), y se filtra como el entrenamiento: sin la meta ya alcanzada y sin STRICT. Las
métricas salen por curva de llegada real de cada convocatoria (u, linear, front), por corte de tiempo y en total.
Ninguno de los dos modelos vio esos datos. Las cifras de ambos se calculan AQUÍ, ninguna se copia de un informe anterior.

Uso: python compare_models.py --old <dir con model_logistic_regression.joblib> --new <dir> --out <dir> [--seed 20261107]
"""
from __future__ import annotations

import argparse
import hashlib
import json
import subprocess
import sys
import tempfile
from pathlib import Path

import joblib
import numpy as np
import pandas as pd
import sklearn

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
from train_baseline import EXCLUDED_POLICIES, FEATURES, LABEL, scores  # noqa: E402


def validation_set(seed: int, n_campaigns: int, workdir: Path) -> tuple[pd.DataFrame, str]:
    subprocess.run(
        [sys.executable, str(HERE / "generate_synthetic_dataset.py"), "--out", str(workdir), "--seed", str(seed),
         "--n-campaigns", str(n_campaigns)],
        check=True, capture_output=True, text=True,
    )
    snaps = pd.read_csv(workdir / "snapshots.csv")
    camps = pd.read_csv(workdir / "campaigns.csv")[["campaignRef", "arrivalCurve"]]
    df = snaps.merge(camps, on="campaignRef", how="left")
    df = df[df["alreadyReachedAtT"] == 0]
    df = df[~df["targetPolicy"].isin(EXCLUDED_POLICIES)].reset_index(drop=True)
    return df, hashlib.sha256((workdir / "snapshots.csv").read_bytes()).hexdigest()


def evaluate(model, df: pd.DataFrame) -> dict:
    p = model.predict_proba(df[FEATURES])[:, 1]
    y = df[LABEL].to_numpy()
    out = {"all": scores(y, p) | {"n": int(len(df))}}
    for curve, g in df.groupby("arrivalCurve"):
        idx = g.index.to_numpy()
        out[f"curve={curve}"] = scores(y[idx], p[idx]) | {"n": int(len(g))}
    for t, g in df.groupby("t"):
        idx = g.index.to_numpy()
        out[f"t={t}"] = scores(y[idx], p[idx]) | {"n": int(len(g))}
    # El caso de la Enmienda 3: ritmo lineal (0,9–1,2) al 25 % del tiempo
    lin = df[(df["t"] == 0.25) & (df["paceRatio"] > 0.9) & (df["paceRatio"] <= 1.2)]
    pl = model.predict_proba(lin[FEATURES])[:, 1]
    out["pace0.9-1.2@t0.25"] = {"n": int(len(lin)), "observedRate": round(float(lin[LABEL].mean()), 4),
                                 "meanPredicted": round(float(pl.mean()), 4)}
    return out


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--old", type=Path, required=True)
    ap.add_argument("--new", type=Path, required=True)
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--seed", type=int, default=20261107)
    ap.add_argument("--n-campaigns", type=int, default=1500)
    args = ap.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)

    with tempfile.TemporaryDirectory() as tmp:
        df, snapshots_sha = validation_set(args.seed, args.n_campaigns, Path(tmp))
    old = joblib.load(args.old / "model_logistic_regression.joblib")
    new = joblib.load(args.new / "model_logistic_regression.joblib")
    result = {
        "validation": {"seed": args.seed, "nCampaigns": args.n_campaigns, "snapshotsSha256": snapshots_sha,
                       "snapshotsUsed": int(len(df)), "sklearnVersion": sklearn.__version__, "numpyVersion": np.__version__},
        old["modelVersion"]: evaluate(old["model"], df),
        new["modelVersion"]: evaluate(new["model"], df),
    }
    (args.out / "comparison.json").write_text(json.dumps(result, indent=2, ensure_ascii=False))
    print(json.dumps(result["validation"], indent=2))


if __name__ == "__main__":
    main()
