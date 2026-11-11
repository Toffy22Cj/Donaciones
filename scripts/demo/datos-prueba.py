#!/usr/bin/env python3
"""
Datos de prueba para la app sobre la demo local (perfil dev). Todo pasa por la API del backend, como la web o la
semilla: no cambia su código ni escribe directo en la base.

  convocatoria   Crea una convocatoria pública como administrador@ (monetaria y en especie). Con --minutos corto, la
                 predicción da cifra pronto: solo estima entre el 15 % y el 50 % del tiempo de la convocatoria.
  pagos          Hace de pasarela: confirma (o con --fallar, rechaza) las donaciones hechas en la app que esperan pago.

Uso:  python3 scripts/demo/datos-prueba.py convocatoria [--titulo T] [--minutos 60] [--meta 20000000] [--empleado CORREO]
      python3 scripts/demo/datos-prueba.py pagos [--fallar]
Antes: set -a; . scripts/demo/demo.env; set +a   (TRACEABILITY_DEMO_SEED_PASSWORD y TRACEABILITY_DEMO_WEBHOOK_SECRET)
"""
import argparse
import datetime as dt
import hashlib
import hmac
import json
import os
import subprocess
import sys
import urllib.error
import urllib.request
import uuid

BASE = os.environ.get("PAXFIDE_API", "http://localhost:8080")


def call(method, path, body=None, token=None, headers=None):
    data = body if isinstance(body, (bytes, type(None))) else json.dumps(body).encode()
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    if method == "POST" and token:
        req.add_header("Command-Id", str(uuid.uuid4()))
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            raw = r.read()
            return r.status, json.loads(raw) if raw else None
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            return e.code, json.loads(raw) if raw else None
        except ValueError:
            return e.code, raw.decode(errors="replace")


def env(name):
    v = os.environ.get(name)
    if not v:
        sys.exit(f"Falta {name}: antes corre  set -a; . scripts/demo/demo.env; set +a")
    return v


def login(email):
    st, r = call("POST", "/api/v1/auth/login", {"email": email, "password": env("TRACEABILITY_DEMO_SEED_PASSWORD")})
    if st != 200:
        sys.exit(f"No se pudo entrar con {email}: HTTP {st} {r}")
    return r["token"]


def iso(t):
    return t.replace(microsecond=0).isoformat().replace("+00:00", "Z")


def convocatoria(a):
    admin = login("administrador@demo.paxfide.local")
    _, me = call("GET", "/api/v1/me", token=admin)
    org = me["organizationId"]
    start = dt.datetime.now(dt.timezone.utc)
    end = start + dt.timedelta(minutes=a.minutos)
    titulo = a.titulo or f"Prueba {start.astimezone().strftime('%H:%M')}"
    st, c = call("POST", f"/api/v1/organizations/{org}/campaigns", {
        "title": titulo, "description": "Convocatoria de prueba de la demo local", "visibility": "PUBLIC",
        "startDate": iso(start), "endDate": iso(end),
        "configuration": {"acceptedDonationTypes": ["MONETARY", "IN_KIND"], "acceptedPaymentMethods": ["GATEWAY"],
                          "currency": "COP", "targetAmount": str(a.meta), "targetPolicy": "FLEXIBLE"}}, token=admin)
    if st != 201:
        sys.exit(f"No se creó la convocatoria: HTTP {st} {c}")
    print(f"Convocatoria «{titulo}» creada: publicCode {c['publicCode']}")
    if a.empleado:
        emp = login(a.empleado)
        _, em = call("GET", "/api/v1/me", token=emp)
        st, r = call("POST", f"/api/v1/campaigns/{c['campaignRef']}/employees", {"employeeRef": em["accountId"]},
                     token=admin)
        print(f"  {a.empleado} asignado" if st == 201 else f"  no se asignó {a.empleado}: HTTP {st} {r}")
    local = lambda m: (start + dt.timedelta(minutes=m)).astimezone().strftime("%H:%M")
    print(f"  Termina a las {local(a.minutos)}. La predicción da cifra entre las {local(a.minutos * 0.15)} "
          f"y las {local(a.minutos * 0.5)} (15 % a 50 % del tiempo).")
    print("  En la app aparece en Inicio → Convocatorias abiertas. Para donar: dona en la app y luego corre  pagos")


def pagos(a):
    secret = env("TRACEABILITY_DEMO_WEBHOOK_SECRET")
    query = ('db.donation_intents.find({status:"PENDING", paymentProvider:"SIMULATED", paymentSessionId:{$ne:null}})'
             '.forEach(d => print(JSON.stringify({intentId: d.intentId, session: d.paymentSessionId, '
             'amount: String(d.amount), currency: d.currency})))')
    out = subprocess.run(["docker", "exec", os.environ.get("MONGO_CONTAINER", "paxfide-demo-mongo"), "mongosh",
                          os.environ.get("MONGO_DB", "paxfide_demo"), "--quiet", "--eval", query],
                         check=True, capture_output=True, text=True).stdout
    intents = [json.loads(l) for l in out.splitlines() if l.startswith("{")]
    if not intents:
        print("No hay donaciones esperando pago.")
        return
    kind = "payment.failed" if a.fallar else "payment.confirmed"
    verb = "rechazado" if a.fallar else "confirmado"
    ok = 0
    for it in intents:
        event = json.dumps({"type": kind, "paymentSessionId": it["session"], "providerEventId": "evt-" + str(uuid.uuid4()),
                            "amount": it["amount"], "currency": it["currency"]}).encode()
        sig = hmac.new(secret.encode(), event, hashlib.sha256).hexdigest()
        st, _ = call("POST", "/api/v1/webhooks/payments", event, headers={"X-Simulated-Signature": sig})
        print(f"  {it['amount']} {it['currency']}: {verb if st == 200 else 'error HTTP ' + str(st)}")
        ok += st == 200
    print(f"{ok} de {len(intents)} pagos {verb}s. En la app, desliza hacia abajo para actualizar.")


def main():
    p = argparse.ArgumentParser(description="Datos de prueba de la demo local")
    sub = p.add_subparsers(dest="cmd", required=True)
    c = sub.add_parser("convocatoria", help="crear una convocatoria pública")
    c.add_argument("--titulo")
    c.add_argument("--minutos", type=int, default=60, help="duración (por defecto 60: predicción de los 9 a los 30 min)")
    c.add_argument("--meta", type=int, default=20000000, help="meta en la unidad del backend (como la semilla)")
    c.add_argument("--empleado", help="correo del empleado responsable (uno solo por convocatoria activa)")
    c.set_defaults(fn=convocatoria)
    g = sub.add_parser("pagos", help="confirmar o rechazar los pagos pendientes")
    g.add_argument("--fallar", action="store_true")
    g.set_defaults(fn=pagos)
    a = p.parse_args()
    a.fn(a)


if __name__ == "__main__":
    main()
