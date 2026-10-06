"""
Generador de dataset SINTÉTICO para el componente predictivo de convocatorias.

ADR de referencia: ADR-044 (PROPUESTO) — Componente predictivo Python, offline y de solo lectura.

Reglas que este script respeta (ADR-044 §2):
  - No se conecta a MongoDB, ni al Event Store, ni a ninguna API del backend.
  - Solo escribe archivos planos en --out (CSV; Parquet opcional si existe pyarrow).
  - Semilla fija: misma semilla + misma versión => mismos archivos byte a byte.
  - Sin PII: donorRef es una referencia opaca aleatoria, nunca un nombre/correo/documento.

Qué genera:
  campaigns.csv       1 fila por convocatoria (atributos estáticos + resultado final)
  donation_intents.csv 1 fila por DonationIntent simulado (incluye FAILED / EXPIRED_UNKNOWN)
  snapshots.csv       1 fila por (convocatoria, t) con features calculadas SOLO con datos <= t
  manifest.json       versión, semilla, parámetros, conteos y hash SHA-256 de cada archivo

Modelo generativo (deliberadamente simple y documentado, no "realista"):
  - Cada convocatoria tiene un atractivo latente `appeal` que NO se exporta como feature.
    Es lo que hace que el problema sea predecible pero no trivial.
  - Llegadas diarias de donaciones ~ Poisson(lambda(d)), con curva en U:
    pico inicial, valle intermedio y repunte final ("efecto fecha límite").
  - Montos ~ LogNormal.
  - Estado del intent: CONFIRMED / FAILED / EXPIRED_UNKNOWN. Solo CONFIRMED suma al recaudo.
  - targetPolicy:
      FLEXIBLE         acepta todo
      STRICT           rechaza la donación que excedería la meta (no entra al ledger)
      CLOSE_ON_TARGET  la convocatoria pasa a CLOSED el día en que alcanza la meta
"""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

import numpy as np
import pandas as pd

DATASET_VERSION = "synthetic-v1"
SNAPSHOT_TS = (0.15, 0.25, 0.50)          # decisión del equipo (6 oct 2026)
POLICIES = ("FLEXIBLE", "STRICT", "CLOSE_ON_TARGET")
POLICY_WEIGHTS = (0.6, 0.25, 0.15)
VISIBILITIES = ("PUBLIC", "PRIVATE_LINK")
RECENT_WINDOW_FRACTION = 0.10             # "velocidad reciente" = último 10 % de la duración


# --------------------------------------------------------------------------- simulación
def _arrival_curve(n_days: int) -> np.ndarray:
    """Curva en U normalizada a media 1: más donaciones al inicio y al final."""
    x = np.linspace(0.0, 1.0, n_days)
    curve = 1.6 * np.exp(-x / 0.12) + 0.35 + 1.1 * np.exp(-(1.0 - x) / 0.08)
    return curve / curve.mean()


def simulate_campaign(rng: np.random.Generator, idx: int) -> tuple[dict, list[dict]]:
    campaign_ref = f"cmp-{idx:05d}"
    duration_days = int(rng.integers(15, 91))
    target_amount = float(np.round(np.exp(rng.normal(np.log(8_000_000), 0.8)), -3))  # COP
    target_policy = str(rng.choice(POLICIES, p=POLICY_WEIGHTS))
    visibility = str(rng.choice(VISIBILITIES, p=(0.8, 0.2)))
    org_prior_campaigns = int(rng.poisson(3))
    payment_methods_enabled = int(rng.integers(1, 4))   # pasarela / transferencia / efectivo

    # Atractivo latente (no observable). Correlaciona débilmente con atributos observables.
    appeal = float(np.exp(
        rng.normal(0.0, 0.55)
        + 0.06 * min(org_prior_campaigns, 8)
        + 0.08 * (payment_methods_enabled - 1)
        - 0.25 * (visibility == "PRIVATE_LINK")
    ))

    mean_amount = float(np.exp(rng.normal(np.log(60_000), 0.35)))
    # Donaciones esperadas en total para llegar ~ a la meta con appeal = 1
    expected_total_donations = target_amount / mean_amount
    base_rate = expected_total_donations / duration_days * appeal * rng.uniform(0.55, 1.15)

    curve = _arrival_curve(duration_days)
    intents: list[dict] = []
    cleared = 0.0
    closed_on_day: int | None = None
    donor_pool = max(5, int(expected_total_donations * 1.5))

    for day in range(duration_days):
        if closed_on_day is not None:
            break
        n = int(rng.poisson(base_rate * curve[day]))
        for _ in range(n):
            amount = float(np.round(np.exp(rng.normal(np.log(mean_amount), 0.9)), -2))
            amount = max(amount, 1_000.0)
            status = str(rng.choice(
                ("CONFIRMED", "FAILED", "EXPIRED_UNKNOWN"), p=(0.88, 0.08, 0.04)))
            rejected_by_policy = False
            if status == "CONFIRMED":
                if target_policy == "STRICT" and cleared + amount > target_amount:
                    rejected_by_policy = True   # no entra al ledger
                else:
                    cleared += amount
            intents.append({
                "campaignRef": campaign_ref,
                "intentRef": f"int-{idx:05d}-{len(intents):05d}",
                "donorRef": f"dnr-{rng.integers(0, donor_pool):06d}-{idx:05d}",
                "day": day,
                "dayFraction": round(float(day + rng.random()) / duration_days, 6),
                "amount": amount,
                "status": status,
                "rejectedByPolicy": rejected_by_policy,
            })
            if target_policy == "CLOSE_ON_TARGET" and cleared >= target_amount:
                closed_on_day = day
                break

    campaign = {
        "campaignRef": campaign_ref,
        "durationDays": duration_days,
        "targetAmount": target_amount,
        "targetPolicy": target_policy,
        "visibility": visibility,
        "orgPriorCampaigns": org_prior_campaigns,
        "paymentMethodsEnabled": payment_methods_enabled,
        "finalClearedAmount": cleared,
        "finalPctOfTarget": cleared / target_amount,
        "reachedTarget": int(cleared >= target_amount),
        "closedEarlyOnDay": closed_on_day,
    }
    return campaign, intents


# --------------------------------------------------------------------------- features
def build_snapshot(campaign: dict, intents: pd.DataFrame, t: float) -> dict | None:
    """
    Features de la convocatoria en el instante t (fracción del tiempo total).

    INVARIANTE ANTI-LEAKAGE: solo se usan intents con dayFraction < t.
    `campaign` solo aporta atributos ESTÁTICOS (conocidos al crear la convocatoria);
    los campos final* / reachedTarget / closedEarlyOnDay se usan solo como etiqueta.
    """
    duration = campaign["durationDays"]
    target = campaign["targetAmount"]
    past = intents[intents["dayFraction"] < t]
    confirmed = past[(past["status"] == "CONFIRMED") & (~past["rejectedByPolicy"])]

    raised = float(confirmed["amount"].sum())
    pct_raised = raised / target

    # Snapshots no predecibles: la convocatoria ya terminó en t.
    closed_day = campaign["closedEarlyOnDay"]
    if closed_day is not None and (closed_day + 1) / duration <= t:
        return None

    window_start = max(0.0, t - RECENT_WINDOW_FRACTION)
    recent = confirmed[confirmed["dayFraction"] >= window_start]
    window_days = max((t - window_start) * duration, 1e-9)
    elapsed_days = t * duration
    remaining_days = duration - elapsed_days

    recent_velocity = float(recent["amount"].sum()) / target / window_days      # % meta / día
    overall_velocity = pct_raised / max(elapsed_days, 1e-9)
    required_velocity = max(0.0, 1.0 - pct_raised) / max(remaining_days, 1e-9)
    n_attempts = len(past)

    return {
        "campaignRef": campaign["campaignRef"],
        "t": t,
        # --- atributos estáticos
        "targetAmount": target,
        "logTargetAmount": float(np.log(target)),
        "durationDays": duration,
        "targetPolicy": campaign["targetPolicy"],
        "visibility": campaign["visibility"],
        "orgPriorCampaigns": campaign["orgPriorCampaigns"],
        "paymentMethodsEnabled": campaign["paymentMethodsEnabled"],
        # --- estado en t
        "pctTimeElapsed": t,
        "pctRaised": pct_raised,
        "nDonations": int(len(confirmed)),
        "nDistinctDonors": int(confirmed["donorRef"].nunique()),
        "meanDonationPctOfTarget": float(confirmed["amount"].mean() / target) if len(confirmed) else 0.0,
        "recentVelocity": recent_velocity,
        "overallVelocity": overall_velocity,
        "requiredVelocity": required_velocity,
        "paceRatio": pct_raised / t,                       # >1 => va por delante del ritmo lineal
        "failedRate": float((past["status"] != "CONFIRMED").sum() / n_attempts) if n_attempts else 0.0,
        "alreadyReachedAtT": int(pct_raised >= 1.0),
        # --- etiquetas (NUNCA usar como feature)
        "label_reachedTarget": campaign["reachedTarget"],
        "label_finalPctOfTarget": campaign["finalPctOfTarget"],
    }


# --------------------------------------------------------------------------- main
def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--n-campaigns", type=int, default=1500)
    ap.add_argument("--seed", type=int, default=20261006)
    ap.add_argument("--out", type=Path, default=Path("data"))
    args = ap.parse_args()

    rng = np.random.default_rng(args.seed)
    campaigns, all_intents = [], []
    for i in range(args.n_campaigns):
        c, its = simulate_campaign(rng, i)
        campaigns.append(c)
        all_intents.extend(its)

    campaigns_df = pd.DataFrame(campaigns)
    intents_df = pd.DataFrame(all_intents)

    snapshots, skipped_closed = [], 0
    by_campaign = dict(tuple(intents_df.groupby("campaignRef")))
    empty = intents_df.iloc[0:0]
    for c in campaigns:
        its = by_campaign.get(c["campaignRef"], empty)
        for t in SNAPSHOT_TS:
            s = build_snapshot(c, its, t)
            if s is None:
                skipped_closed += 1
            else:
                snapshots.append(s)
    snapshots_df = pd.DataFrame(snapshots)

    args.out.mkdir(parents=True, exist_ok=True)
    files = {
        "campaigns.csv": campaigns_df,
        "donation_intents.csv": intents_df,
        "snapshots.csv": snapshots_df,
    }
    for name, df in files.items():
        df.to_csv(args.out / name, index=False, float_format="%.6f")
    try:
        import pyarrow  # noqa: F401
        snapshots_df.to_parquet(args.out / "snapshots.parquet", index=False)
    except ImportError:
        pass

    manifest = {
        "datasetVersion": DATASET_VERSION,
        "synthetic": True,
        "warning": "Datos inventados. No representan convocatorias reales. "
                   "No cargar en MongoDB ni en el Event Store (ADR-044 §2).",
        "seed": args.seed,
        "nCampaigns": args.n_campaigns,
        "snapshotTs": list(SNAPSHOT_TS),
        "recentWindowFraction": RECENT_WINDOW_FRACTION,
        "counts": {
            "campaigns": len(campaigns_df),
            "donationIntents": len(intents_df),
            "snapshots": len(snapshots_df),
            "snapshotsSkippedCampaignAlreadyClosed": skipped_closed,
        },
        "reachedTargetRate": round(float(campaigns_df["reachedTarget"].mean()), 4),
        "sha256": {name: _sha256(args.out / name) for name in files},
    }
    (args.out / "manifest.json").write_text(json.dumps(manifest, indent=2, ensure_ascii=False))
    print(json.dumps(manifest["counts"], indent=2))
    print(f"reachedTargetRate = {manifest['reachedTargetRate']}")


if __name__ == "__main__":
    main()
