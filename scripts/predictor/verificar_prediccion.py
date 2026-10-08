"""
Comprueba que la predicción que sirve el backend (y que muestran la app y la web) es la del modelo entrenado.

Para cada convocatoria de la organización:
  1. pide la predicción al backend (GET .../prediction), como hace la app;
  2. lee de MongoDB los mismos datos que usa el backend (convocatoria, intenciones y convocatorias previas);
  3. calcula las variables con la misma definición que CampaignFeatureBuilder (y build_snapshot del entrenamiento),
     en el mismo instante que el backend (`asOf` de la respuesta);
  4. evalúa los modelos ORIGINALES de scikit-learn (paxfide-predictor/output/*.joblib) con esas variables;
  5. compara las dos cifras (el backend redondea a 4 decimales).

Solo lectura: no escribe nada en MongoDB ni en el backend.

Uso (con el backend de la demo arrancado, ver runbook-demo-local.md):
  python3 -m venv ~/predvenv && ~/predvenv/bin/pip install scikit-learn==1.9.1 pandas joblib
  ~/predvenv/bin/python scripts/predictor/verificar_prediccion.py \
      [--email administrador@demo.paxfide.local] [--password demo-local-password] \
      [--api http://127.0.0.1:8080/api/v1] [--mongo-container paxfide-demo-mongo] [--db paxfide_demo]
"""
import argparse
import json
import math
import subprocess
import urllib.request
from datetime import datetime
from pathlib import Path

import joblib
import pandas as pd

ROOT = Path(__file__).resolve().parents[2]
MODELS = ROOT / "paxfide-predictor" / "output"
RECENT_WINDOW_FRACTION = 0.10  # igual que el entrenamiento y CampaignFeatureBuilder
TOLERANCE = 1e-4  # el backend redondea a 4 decimales
EXPONENTS = {"COP": 2}

ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
ap.add_argument("--api", default="http://127.0.0.1:8080/api/v1")
ap.add_argument("--email", default="administrador@demo.paxfide.local")
ap.add_argument("--password", default="demo-local-password")
ap.add_argument("--mongo-container", default="paxfide-demo-mongo")
ap.add_argument("--db", default="paxfide_demo")
ap.add_argument("--campaign-ref", help="solo esta convocatoria")
args = ap.parse_args()


def http(method, path, token=None, body=None):
    req = urllib.request.Request(args.api + path, method=method)
    req.add_header("Accept", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    data = None
    if body is not None:
        data = json.dumps(body).encode()
        req.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(req, data) as r:
        return json.loads(r.read() or b"null")


def mongo(js):
    """Ejecuta una consulta de solo lectura con mongosh dentro del contenedor y devuelve el JSON."""
    out = subprocess.run(
        ["docker", "exec", args.mongo_container, "mongosh", "--quiet", args.db, "--eval",
         f"print(EJSON.stringify({js}, {{relaxed: true}}))"],
        check=True, capture_output=True, text=True).stdout
    return json.loads(out)


def instant(v):
    s = v["$date"] if isinstance(v, dict) else v
    return datetime.fromisoformat(s.replace("Z", "+00:00"))


def days(a, b):
    return (b - a).total_seconds() / 86400.0


def features(conv, intents, prior, now):
    """Misma definición que CampaignFeatureBuilder.build (app/src/main/java/.../prediction)."""
    start, end = instant(conv["startDate"]), instant(conv["endDate"])
    duration = days(start, end)
    target = float(conv["targetAmount"])
    minor_per_unit = 10 ** EXPONENTS[conv["currency"]]
    t = days(start, now) / duration

    donor_keys = {}
    for i in intents:  # la clave de donante: anónimos (sin donorRef) comparten una sola clave, como en Java
        donor_keys.setdefault(i.get("donorRef"), len(donor_keys))
    past = [i for i in intents if i["status"] != "PENDING"]
    confirmed = [i for i in past if i["status"] == "CONFIRMED"]
    raised = sum(float(i["amount"]) for i in confirmed)
    donors = {donor_keys[i.get("donorRef")] for i in confirmed}
    pct = raised / target

    window_start = max(0.0, t - RECENT_WINDOW_FRACTION)
    recent = sum(float(i["amount"]) for i in confirmed
                 if i.get("confirmedAt") and days(start, instant(i["confirmedAt"])) / duration >= window_start)
    window_days = max((t - window_start) * duration, 1e-9)
    elapsed = t * duration
    remaining = duration - elapsed
    failed = sum(1 for i in past if i["status"] in ("FAILED", "EXPIRED_UNKNOWN"))

    return {
        "logTargetAmount": math.log(target / minor_per_unit),
        "durationDays": duration,
        "orgPriorCampaigns": float(prior),
        "paymentMethodsEnabled": float(len(conv.get("acceptedPaymentMethods") or [])),
        "pctTimeElapsed": t,
        "pctRaised": pct,
        "nDonations": float(len(confirmed)),
        "nDistinctDonors": float(len(donors)),
        "meanDonationPctOfTarget": 0.0 if not confirmed else (raised / len(confirmed)) / target,
        "recentVelocity": recent / target / window_days,
        "overallVelocity": pct / max(elapsed, 1e-9),
        "requiredVelocity": max(0.0, 1.0 - pct) / max(remaining, 1e-9),
        "paceRatio": pct / t,
        "failedRate": 0.0 if not past else failed / len(past),
        "targetPolicy": conv["targetPolicy"],
        "visibility": conv["visibility"],
    }


clf = joblib.load(MODELS / "model_logistic_regression.joblib")
reg = joblib.load(MODELS / "model_final_pct_regressor.joblib")

token = http("POST", "/auth/login", body={"email": args.email, "password": args.password})["token"]
org = http("GET", "/me", token)["organizationId"]
campaigns = http("GET", f"/organizations/{org}/campaigns", token)["items"]
if args.campaign_ref:
    campaigns = [c for c in campaigns if c["campaignRef"] == args.campaign_ref]

print(f"Modelo entrenado: {clf['modelVersion']} ({MODELS})\n")
checked = failures = 0
for c in campaigns:
    ref = c["campaignRef"]
    p = http("GET", f"/organizations/{org}/campaigns/{ref}/prediction", token)
    print(f"== {c['title']} ({ref})")
    if not p["available"] or p.get("unavailableReason"):
        print(f"   Sin cifra que comparar: {p.get('unavailableReason')} — {p.get('unavailableText') or ''}\n")
        continue

    now = instant(p["asOf"])
    conv = mongo(f"db.convocatorias.findOne({{_id: '{ref}'}})")  # campaignRef es el _id
    intents = mongo(f"db.donation_intents.find({{campaignRef: '{ref}'}}).toArray()")
    prior = mongo(f"db.convocatorias.countDocuments({{organizationRef: '{conv['organizationRef']}', "
                  f"startDate: {{$lt: ISODate('{instant(conv['startDate']).isoformat()}')}}}})")
    x = features(conv, intents, prior, now)
    frame = pd.DataFrame([x], columns=clf["features"])
    model_prob = float(clf["model"].predict_proba(frame)[0, 1])
    model_final = float(reg["model"].predict(pd.DataFrame([x], columns=reg["features"]))[0])

    print("   Variables (en el instante del backend, " + p["asOf"] + "):")
    for k, v in x.items():
        print(f"     {k:24s} {v}")
    rows = [("Probabilidad de alcanzar la meta", p["probabilityReachTarget"], model_prob),
            ("% final estimado de la meta", p["estimatedFinalPctOfTarget"], model_final)]
    for label, api, model in rows:
        ok = abs(api - model) <= TOLERANCE
        checked += 1
        failures += 0 if ok else 1
        print(f"   {label:34s} backend={api:.4f}  modelo={model:.6f}  {'OK' if ok else 'DIFERENTE'}")
    print()

if checked == 0:
    print("Ninguna convocatoria tiene cifra ahora mismo (solo hay estimación entre el 15 % y el 50 % de su duración).")
else:
    print(f"Resultado: {checked - failures}/{checked} cifras coinciden con el modelo entrenado.")
raise SystemExit(1 if failures else 0)
