"""
Valor esperado del test de punta a punta de las estimaciones históricas (CampaignPredictionHistoryEndToEndTest,
encargo 6, P3). Calcula, con el propio `build_snapshot` del entrenamiento (paxfide-predictor) y los modelos
`baseline-0.2.0`, la estimación en t = 0,15 y t = 0,25 con los datos que había EN ESE MOMENTO según el event store:
una convocatoria FLEXIBLE y PUBLIC de 40 días con meta de 8 000 000 COP (la meta en pesos, como en el entrenamiento),
y dos donaciones acreditadas (FUNDS_CLEARED) de 1 200 000 y 900 000 COP en t = 0,20.

El pago fallido del test no está en el event store: en los cortes pasados la tasa de fallos es 0 (DD-75), así que
aquí no aparece. En t = 0,15 no hay ninguna donación; en t = 0,25 están las dos (y caen en la ventana reciente).

Uso: python -I e2e_history_expected.py --predictor <dir paxfide-predictor> --out <fichero.json>
"""
import argparse
import json
import sys
from pathlib import Path

ap = argparse.ArgumentParser()
ap.add_argument("--predictor", type=Path, required=True)
ap.add_argument("--out", type=Path, required=True)
args = ap.parse_args()

sys.path.insert(0, str(args.predictor.resolve()))
import joblib  # noqa: E402
import pandas as pd  # noqa: E402
from generate_synthetic_dataset import build_snapshot  # noqa: E402

campaign = {"campaignRef": "e2e-history", "durationDays": 40, "targetAmount": 8_000_000.0,
            "targetPolicy": "FLEXIBLE", "visibility": "PUBLIC", "orgPriorCampaigns": 0, "paymentMethodsEnabled": 1,
            "closedEarlyOnDay": None, "reachedTarget": None, "finalPctOfTarget": None}
intents = pd.DataFrame([
    {"intentRef": "i-1", "donorRef": "d-1", "dayFraction": 0.20, "amount": 1_200_000.0, "status": "CONFIRMED",
     "rejectedByPolicy": False},
    {"intentRef": "i-2", "donorRef": "d-2", "dayFraction": 0.20, "amount": 900_000.0, "status": "CONFIRMED",
     "rejectedByPolicy": False},
])

clf = joblib.load(args.predictor / "output" / "model_logistic_regression.joblib")
reg = joblib.load(args.predictor / "output" / "model_final_pct_regressor.joblib")
out = {"modelVersion": clf["modelVersion"], "cuts": {}}
for t in (0.15, 0.25):
    snap = build_snapshot(campaign, intents, t)
    frame = pd.DataFrame([snap], columns=clf["features"])
    out["cuts"][str(t)] = {"features": {k: snap[k] for k in clf["features"]},
                           "pctRaised": snap["pctRaised"],
                           "probabilityReachTarget": float(clf["model"].predict_proba(frame)[0, 1]),
                           "finalPctOfTarget": float(reg["model"].predict(frame)[0])}
args.out.write_text(json.dumps(out, indent=2))
print(json.dumps(out, indent=2))
