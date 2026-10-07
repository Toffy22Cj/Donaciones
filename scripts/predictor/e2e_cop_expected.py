"""
Valor esperado del test de punta a punta del predictor (CampaignPredictionCopEndToEndTest): una convocatoria creada por
CV-01 en COP con meta de 800 000 000 unidades mínimas (8 000 000 COP, exponente ISO 4217 = 2), 40 días, en t = 0,25,
con donaciones de 1 200 000 COP (día 2) y 900 000 COP (día 9,5) y un pago fallido. scikit-learn evalúa los modelos
entrenados con la meta EN PESOS, como en el entrenamiento.

Uso: python e2e_cop_expected.py --models <dir con los .joblib> --out <fichero.json>
"""
import argparse
import json
import math
from pathlib import Path

import joblib
import pandas as pd

ap = argparse.ArgumentParser()
ap.add_argument("--models", type=Path, required=True)
ap.add_argument("--out", type=Path, required=True)
args = ap.parse_args()

target_pesos = 8_000_000.0
duration, t = 40.0, 0.25
donations = [(1_200_000.0, 2.0), (900_000.0, 9.5)]          # (pesos, día desde el inicio)
raised = sum(a for a, _ in donations)
pct = raised / target_pesos
window_start = max(0.0, t - 0.10)
recent = sum(a for a, d in donations if d / duration >= window_start)
elapsed = t * duration
x = {
    "logTargetAmount": math.log(target_pesos), "durationDays": duration, "orgPriorCampaigns": 0.0,
    "paymentMethodsEnabled": 1.0, "pctTimeElapsed": t, "pctRaised": pct, "nDonations": 2.0, "nDistinctDonors": 2.0,
    "meanDonationPctOfTarget": (raised / 2) / target_pesos,
    "recentVelocity": recent / target_pesos / max((t - window_start) * duration, 1e-9),
    "overallVelocity": pct / max(elapsed, 1e-9),
    "requiredVelocity": max(0.0, 1.0 - pct) / max(duration - elapsed, 1e-9),
    "paceRatio": pct / t, "failedRate": 1.0 / 3.0, "targetPolicy": "FLEXIBLE", "visibility": "PUBLIC",
}
clf = joblib.load(args.models / "model_logistic_regression.joblib")
reg = joblib.load(args.models / "model_final_pct_regressor.joblib")
frame = pd.DataFrame([x], columns=clf["features"])
out = {"modelVersion": clf["modelVersion"], "features": x,
       "probabilityReachTarget": float(clf["model"].predict_proba(frame)[0, 1]),
       "finalPctOfTarget": float(reg["model"].predict(frame)[0])}
args.out.write_text(json.dumps(out, indent=2))
print(json.dumps(out, indent=2))
