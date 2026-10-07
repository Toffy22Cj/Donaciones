#!/usr/bin/env python3
"""
Recorrido del golden path contra el backend local en marcha (runbook-demo-local.md, paso 6).

Hace por HTTP los pasos 1–7 de golden-path.md con las cuentas de la semilla (perfil dev), espera el anclaje en la
Ganache local y guarda cada petición y respuesta en <salida>/NN-paso.json, más un resumen. Solo biblioteca estándar.

Uso:  python3 scripts/demo/recorrido.py [--base http://localhost:8080] [--salida demo-evidencia/<fecha>]
Variables: TRACEABILITY_DEMO_SEED_PASSWORD y TRACEABILITY_DEMO_WEBHOOK_SECRET (las mismas que el backend),
           GANACHE_URL (por defecto http://localhost:8545), MONGO_CONTAINER (por defecto paxfide-demo-mongo).
"""
import argparse
import datetime
import hashlib
import hmac
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid

SEED = {
    "plataforma": "plataforma@demo.paxfide.local",
    "administrador": "administrador@demo.paxfide.local",
    "empleado": "empleado@demo.paxfide.local",
    "donante": "donante@demo.paxfide.local",
}


SECRET_KEYS = {"token", "statusToken", "trackingCode", "password"}


def redact(value):
    """Credenciales fuera de la evidencia: JWT, statusToken, trackingCode y contraseñas se guardan como ***."""
    if isinstance(value, dict):
        return {k: ("***" if k in SECRET_KEYS and v else redact(v)) for k, v in value.items()}
    if isinstance(value, list):
        return [redact(v) for v in value]
    return value


class Demo:
    def __init__(self, base, out):
        self.base, self.out, self.n = base.rstrip("/"), out, 0
        os.makedirs(out, exist_ok=True)

    def call(self, name, method, path, body=None, token=None, headers=None, expect=None, command=True):
        h = {"Content-Type": "application/json"} if body is not None else {}
        if token:
            h["Authorization"] = "Bearer " + token
        if method == "POST" and command:
            h["Command-Id"] = str(uuid.uuid4())
        h.update(headers or {})
        data = body.encode() if isinstance(body, str) else (json.dumps(body).encode() if body is not None else None)
        req = urllib.request.Request(self.base + path, data=data, method=method, headers=h)
        try:
            with urllib.request.urlopen(req, timeout=20) as r:
                status, text = r.status, r.read().decode()
        except urllib.error.HTTPError as e:
            status, text = e.code, e.read().decode()
        self.n += 1
        safe = {k: ("***" if k in ("Authorization", "Intent-Token", "X-Simulated-Signature") else v) for k, v in h.items()}
        sent = redact(json.loads(data)) if data and data[:1] in (b"{", b"[") else None
        record = {"paso": name, "metodo": method, "ruta": path, "cabeceras": safe, "cuerpo": sent, "estado": status,
                  "respuesta": redact(json.loads(text)) if text.startswith(("{", "[")) else text}
        with open(os.path.join(self.out, f"{self.n:02d}-{name}.json"), "w") as f:
            json.dump(record, f, ensure_ascii=False, indent=2)
        if expect is not None and status not in (expect if isinstance(expect, tuple) else (expect,)):
            sys.exit(f"[{name}] {method} {path} -> {status}: {text}")
        print(f"  {self.n:02d} {name}: {status}")
        return status, (json.loads(text) if text.startswith(("{", "[")) else None)

    def until(self, what, fn, timeout=180, every=2):
        end = time.time() + timeout
        while time.time() < end:
            result = fn()
            if result:
                return result
            time.sleep(every)
        sys.exit(f"tiempo agotado esperando: {what}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://localhost:8080")
    stamp = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H-%M-%SZ")
    ap.add_argument("--salida", default=f"demo-evidencia/{stamp}")
    args = ap.parse_args()
    password = os.environ["TRACEABILITY_DEMO_SEED_PASSWORD"]
    webhook_secret = os.environ["TRACEABILITY_DEMO_WEBHOOK_SECRET"].encode()
    d = Demo(args.base, args.salida)
    print(f"Recorrido contra {args.base}; evidencia en {args.salida}")

    tok = {}
    for role, email in SEED.items():
        _, r = d.call(f"login-{role}", "POST", "/api/v1/auth/login", {"email": email, "password": password}, expect=200,
                      command=False)
        tok[role] = r["token"]
    _, me = d.call("me-administrador", "GET", "/api/v1/me", token=tok["administrador"], expect=200)
    org = me["organizationId"]
    _, me_e = d.call("me-empleado", "GET", "/api/v1/me", token=tok["empleado"], expect=200)

    # Paso 1 (criterio 1): la plataforma verifica la organización (409 si ya lo estaba de otra ejecución)
    d.call("verificar-organizacion", "POST", f"/api/v1/platform/organizations/{org}/verify", token=tok["plataforma"],
           expect=(200, 409), command=False)

    # Paso 2 (criterio 2): convocatoria PUBLIC y un EMPLOYEE responsable
    now = datetime.datetime.now(datetime.timezone.utc)
    start = (now + datetime.timedelta(minutes=1)).strftime("%Y-%m-%dT%H:%M:%SZ")
    end = (now + datetime.timedelta(days=60)).strftime("%Y-%m-%dT%H:%M:%SZ")
    _, camp = d.call("crear-convocatoria", "POST", f"/api/v1/organizations/{org}/campaigns", {
        "title": "Abrigo para el invierno", "description": "Convocatoria de la demo local", "visibility": "PUBLIC",
        "startDate": start, "endDate": end,
        "configuration": {"acceptedDonationTypes": ["MONETARY", "IN_KIND"], "acceptedPaymentMethods": ["GATEWAY"],
                          "currency": "COP", "targetAmount": "50000000", "targetPolicy": "FLEXIBLE"}},
        token=tok["administrador"], expect=201)
    code = camp["publicCode"]
    st, _ = d.call("asignar-empleado", "POST", f"/api/v1/campaigns/{camp['campaignRef']}/employees",
                   {"employeeRef": me_e["accountId"]}, token=tok["administrador"], expect=(201, 409))
    if st == 409:
        # regla de dominio: un EMPLOYEE es responsable de una sola convocatoria activa. Solo pasa al repetir el
        # recorrido sobre la misma base; para la evidencia limpia, empezar de cero (runbook, paso 7)
        print("  aviso: el empleado ya es responsable de otra convocatoria (recorrido repetido)")

    # Paso 3 (criterios 3, 4 y 5): donación sin cuenta y con cuenta, liquidadas por el webhook simulado firmado
    d.call("ver-convocatoria", "GET", f"/api/v1/public/campaigns/{code}", expect=200)
    tracking = {}
    for who, amount, token in (("anonima", "6000000", None), ("con-cuenta", "4000000", tok["donante"])):
        _, intent = d.call(f"donar-{who}", "POST", f"/api/v1/public/campaigns/{code}/donation-intents",
                           {"amount": amount, "currency": "COP", "paymentMethod": "GATEWAY"}, token=token, expect=201)
        session = intent["paymentRedirectUrl"].rsplit("/", 1)[-1]
        event = json.dumps({"type": "payment.confirmed", "paymentSessionId": session,
                            "providerEventId": "evt-" + str(uuid.uuid4()), "amount": amount, "currency": "COP"})
        sig = hmac.new(webhook_secret, event.encode(), hashlib.sha256).hexdigest()
        d.call(f"webhook-{who}", "POST", "/api/v1/webhooks/payments", event,
               headers={"X-Simulated-Signature": sig}, expect=200, command=False)
        status = d.until(f"fondos aplicados ({who})", lambda: (lambda s: s if s[1] and s[1].get("trackingCode") else None)(
            d.call(f"estado-intencion-{who}", "GET", f"/api/v1/public/donation-intents/{intent['intentId']}",
                   headers={"Intent-Token": intent["statusToken"]}, expect=200)))
        tracking[who] = status[1]["trackingCode"]

    # Criterio 6: la donación con cuenta en su historial
    d.call("historial-donante", "GET", "/api/v1/account/donations", token=tok["donante"], expect=200)

    # Paso 4 (criterio 7, Camino A): fondos, asignación y registro del activo
    _, funds = d.call("fondos-organizacion", "GET", f"/api/v1/organizations/{org}/funds", token=tok["administrador"],
                      expect=200)
    fund = next(f for f in funds["items"]
                if f.get("campaignRef") == camp["campaignRef"] and f["clearedAmount"] == "6000000")
    _, alloc = d.call("asignacion", "POST", f"/api/v1/funds/{fund['fundId']}/allocations", {"amount": "5000000"},
                      token=tok["administrador"], expect=201)
    _, asset = d.call("registrar-activo", "POST", "/api/v1/physical-assets/register", {
        "fundId": fund["fundId"], "assetType": "BLANKET", "quantity": "10", "unitOfMeasure": "UNITS",
        "custodianRef": "bodega-1", "currentLocation": "bodega-1", "allocationId": alloc["allocationId"]},
        token=tok["empleado"], expect=201)
    parent = asset["assetRef"]

    # Paso 4B (criterios 15 y 16): división
    _, split = d.call("dividir", "POST", f"/api/v1/physical-assets/{parent}/split", {"quantity": "4"},
                      token=tok["empleado"], expect=202)
    child = split["childAssetRef"]
    d.until("hijo creado", lambda: d.call("estado-division", "GET", f"/api/v1/physical-assets/{parent}/splits/{child}",
                                          token=tok["empleado"])[1].get("status") == "CHILD_CREATED")

    # Paso 5 (criterios 8, 9 y 17): logística hasta DELIVERED
    for name, ref in (("padre", parent), ("hijo", child)):
        base = f"/api/v1/physical-assets/{ref}"
        d.call(f"despachar-{name}", "POST", base + "/dispatch", {"carrierRef": "transportista-1"}, token=tok["empleado"],
               expect=200)
        d.call(f"recibir-{name}", "POST", base + "/receive", {"facilityLocation": "centro-1", "receiverRef": "recibe-1"},
               token=tok["empleado"], expect=200)
        d.call(f"entregar-{name}", "POST", base + "/deliver", {"finalCustodianRef": "custodio-final",
               "beneficiaryRef": "beneficiario-1", "locationRef": "centro-1", "evidenceRef": "acta-1"},
               token=tok["empleado"], expect=200)

    # Paso 7: seguimiento público y narrativas (criterios 13, 14 y 19)
    d.until("logística proyectada", lambda: (lambda r: r[1] and r[1].get("logistics"))(
        d.call("seguimiento", "GET", "/api/v1/donations/tracking", token=tracking["anonima"])))
    d.until("narrativa individual", lambda: (lambda r: r[0] == 200 and r[1].get("status") == "AVAILABLE")(
        d.call("narrativa-individual", "GET", "/api/v1/donations/tracking/narrative", token=tracking["anonima"])))
    d.until("narrativa de convocatoria", lambda: d.call("narrativa-convocatoria", "GET",
                                                        f"/api/v1/public/campaigns/{code}/narrative")[0] == 200)

    # Criterios 10, 11, 12 y 18: anclaje en la Ganache local
    mongo = os.environ.get("MONGO_CONTAINER", "paxfide-demo-mongo")
    db = os.environ.get("MONGO_DB", "paxfide_demo")
    streams = [fund["fundId"], parent, child]
    script = ("const s=%s; const ev=db.event_store.find({streamId:{$in:s}},{merkleBatchId:1}).toArray();"
              "const ids=[...new Set(ev.map(e=>e.merkleBatchId))];"
              "const b=db.merkle_batches.find({batchId:{$in:ids.filter(x=>x)}}).toArray();"
              "print(JSON.stringify({events:ev.length, sinBatch:ev.filter(e=>!e.merkleBatchId).length,"
              "batches:b.map(x=>({batchId:x.batchId,status:x.status,merkleRoot:x.merkleRoot,txHash:x.transactionHash,"
              "block:x.confirmedBlockNumber}))}))") % json.dumps(streams)

    def anchored():
        r = subprocess.run(["docker", "exec", mongo, "mongosh", "--quiet", db, "--eval", script],
                           capture_output=True, text=True)
        try:
            state = json.loads(r.stdout.strip().splitlines()[-1])
        except (ValueError, IndexError):
            return None
        ok = state["sinBatch"] == 0 and state["batches"] and all(b["status"] == "ANCHORED" for b in state["batches"])
        return state if ok else None

    state = d.until("eventos del recorrido anclados", anchored, timeout=300, every=5)
    ganache = os.environ.get("GANACHE_URL", "http://localhost:8545")
    receipts = []
    for b in state["batches"]:
        req = urllib.request.Request(ganache, method="POST", headers={"Content-Type": "application/json"},
                                     data=json.dumps({"jsonrpc": "2.0", "id": 1, "method": "eth_getTransactionReceipt",
                                                      "params": [b["txHash"]]}).encode())
        with urllib.request.urlopen(req, timeout=10) as r:
            rec = json.load(r)["result"]
        receipts.append({"batchId": b["batchId"], "merkleRoot": b["merkleRoot"], "txHash": b["txHash"],
                         "status": rec["status"], "blockNumber": int(rec["blockNumber"], 16), "to": rec["to"]})
    with open(os.path.join(args.salida, "anclaje-ganache.json"), "w") as f:
        json.dump({"nota": "Anclaje real en la cadena local (Ganache); no verificable públicamente por terceros. "
                           "El poller solo marca ANCHORED si la raíz leída de la cadena coincide con la del batch.",
                   "estado": state, "recibos": receipts}, f, ensure_ascii=False, indent=2)
    print(f"  anclaje: {len(receipts)} batch(es) ANCHORED en Ganache")
    print(f"Recorrido completo. Evidencia en {args.salida}")


if __name__ == "__main__":
    main()
