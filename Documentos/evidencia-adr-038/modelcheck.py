"""
Verificador exhaustivo de intercalaciones para ADR-038 (Platform Administrator).

ES UN MODELO, NO MongoDB. Modela la semántica DOCUMENTADA de transacciones
multi-documento de MongoDB (docs: transactions-production-consideration):
  - Las lecturas dentro de la transacción ven una instantánea (stale reads posibles).
  - Una escritura que MODIFICA un documento adquiere su bloqueo.
    Una escritura que no modifica nada (no-op / filtro sin coincidencia) no bloquea.
  - Si el documento fue modificado por otra transacción confirmada después de la
    instantánea, o lo tiene bloqueado otra transacción en curso -> WriteConflict
    (etiqueta TransientTransactionError) y la transacción aborta.
  - Reintento acotado (3 intentos) de la transacción COMPLETA, como
    MongoTransactionRetryHelper (identity), solo ante TransientTransactionError.
Explora TODAS las intercalaciones de los pasos de 2-3 operaciones concurrentes
(model checking sin estado, re-ejecutando desde cero cada prefijo de planificación).
"""
import copy, itertools, sys

MAX_ATTEMPTS = 3


class Conflict(Exception):
    pass


class DuplicateKey(Exception):
    pass


class DB:
    def __init__(self, docs):
        self.docs = copy.deepcopy(docs)          # committed: key -> value (dict) ; missing = absent
        self.ver = {k: 0 for k in self.docs}     # committed version per key
        self.clock = 0
        self.locks = {}                          # key -> txn id
        self.audit = []                          # committed audit entries
        self.tid = 0

    def begin(self):
        self.tid += 1
        return Txn(self, self.tid)

    def nontx_read(self, key):                   # lectura fuera de transacción (último confirmado)
        return copy.deepcopy(self.docs.get(key))


class Txn:
    def __init__(self, db, tid):
        self.db, self.tid = db, tid
        self.snap = copy.deepcopy(db.docs)
        self.snapver = dict(db.ver)
        self.buf = {}
        self.audit = []
        self.done = False

    def view(self, key):
        if key in self.buf:
            return copy.deepcopy(self.buf[key])
        return copy.deepcopy(self.snap.get(key))

    def read(self, key):
        return self.view(key)

    def _acquire(self, key):
        latest = self.db.ver.get(key, None)
        if latest != self.snapver.get(key, None):
            raise Conflict(f"{key} modificado tras la instantánea")
        holder = self.db.locks.get(key)
        if holder is not None and holder != self.tid:
            raise Conflict(f"{key} bloqueado por T{holder}")
        self.db.locks[key] = self.tid

    def write(self, key, newval):
        """Escritura. Si no modifica el documento: no-op, sin bloqueo (docs)."""
        if self.view(key) == newval:
            return False
        self._acquire(key)
        self.buf[key] = copy.deepcopy(newval)
        return True

    def update_if(self, key, pred, fn):
        """updateOne(filtro, cambio): matched==0 -> no escribe, no bloquea."""
        cur = self.view(key)
        if cur is None or not pred(cur):
            return 0
        self.write(key, fn(copy.deepcopy(cur)))
        return 1

    def insert(self, key, val):
        if self.view(key) is not None:
            raise DuplicateKey(key)
        self._acquire(key)
        self.buf[key] = copy.deepcopy(val)

    def commit(self):
        db = self.db
        for k, v in self.buf.items():
            db.clock += 1
            db.docs[k] = v
            db.ver[k] = db.clock
        db.audit.extend(self.audit)
        self._release()

    def abort(self):
        self._release()

    def _release(self):
        self.done = True
        for k in [k for k, t in self.db.locks.items() if t == self.tid]:
            del self.db.locks[k]


# --------------------------------------------------------------------------
# Operaciones. Cada `yield` es un punto donde el planificador puede intercalar.
# `d` = diseño (flags). Devuelven el resultado del comando como string.
# --------------------------------------------------------------------------

def with_retry(body, retries=True):
    """Envuelve body(txn) -> generator; reintenta la transacción completa ante Conflict."""
    def gen(db):
        attempts = 0
        while True:
            attempts += 1
            t = db.begin()
            yield
            try:
                res = yield from body(t)
                if res == "OK":
                    t.commit()
                else:
                    t.abort()
                return res
            except Conflict:
                t.abort()
                if retries and attempts < MAX_ATTEMPTS:
                    yield
                    continue
                return "WriteConflict(propagado)"
            except DuplicateKey:
                t.abort()
                return "AlreadyBootstrapped"
    return gen


def acct(a):
    return "acct:" + a


def grant(target, d):
    pre = {}

    def outside_check(db):
        # Variante D0: precondición evaluada FUERA de la transacción (antes del retry loop)
        a = db.nontx_read(acct(target))
        pre["a"] = a

    def body(t):
        a = pre["a"] if d.get("check_outside_tx") else t.read(acct(target))
        yield
        st = t.read("state")
        yield
        if st is None:
            return "StateMissing"
        if a is None:
            return "AccountNotFound"
        if d.get("grant_requires_active") and a["status"] != "ACTIVE":
            return "InactiveAccount"
        if a["auth"]:
            return "AlreadyGranted"
        if d.get("conditional_account_write"):
            n = t.update_if(acct(target), lambda x: not x["auth"], lambda x: {**x, "auth": True})
            if n == 0:
                return "AlreadyGranted"
        else:
            cur = t.read(acct(target))
            t.write(acct(target), {**cur, "auth": True})
        yield
        t.write("state", {**t.read("state"), "count": t.read("state")["count"] + 1})
        yield
        t.audit.append(("GRANT", target))
        return "OK"

    inner = with_retry(body)

    def gen(db):
        if d.get("check_outside_tx"):
            outside_check(db)
            yield
        return (yield from inner(db))
    return gen


def revoke(target, d):
    def body(t):
        a = t.read(acct(target))
        yield
        if d.get("no_singleton"):
            # Variante sin documento dedicado: count sobre accounts (write skew esperado)
            n = sum(1 for k in t.snap if k.startswith("acct:") and t.view(k)["auth"])
            yield
            if not a["auth"]:
                return "NotHeld"
            if n <= 1:
                return "LastAdmin"
            t.write(acct(target), {**a, "auth": False})
            yield
            t.audit.append(("REVOKE", target))
            return "OK"
        st = t.read("state")
        yield
        if st is None:
            return "StateMissing"
        if not a["auth"]:
            return "NotHeld"
        n = t.update_if("state", lambda s: s["count"] > 1, lambda s: {**s, "count": s["count"] - 1})
        if n == 0:
            return "LastAdmin"
        yield
        if d.get("conditional_account_write"):
            m = t.update_if(acct(target), lambda x: x["auth"], lambda x: {**x, "auth": False})
            if m == 0:
                return "NotHeld"
        else:
            t.write(acct(target), {**t.read(acct(target)), "auth": False})
        yield
        t.audit.append(("REVOKE", target))
        return "OK"
    return with_retry(body)


def deactivate(target, d):
    # Código ACTUAL: DeactivateAccountService = @Transactional sin reintento, sin mirar platformAuthority.
    def body(t):
        a = t.read(acct(target))
        yield
        if d.get("deactivate_guard") and a["auth"]:
            return "PlatformAdminDeactivation"
        if a["status"] == "INACTIVE":
            return "OK_noop"
        t.write(acct(target), {**a, "status": "INACTIVE"})
        yield
        t.audit.append(("DEACTIVATE", target))
        return "OK"
    return with_retry(body, retries=d.get("deactivate_retries", False))


def bootstrap(target, d):
    def body(t):
        a = t.read(acct(target))
        yield
        if a is None or a["status"] != "ACTIVE":
            return "BootstrapTargetInvalid"
        if d.get("bootstrap_checks_auth") and a["auth"]:
            return "BootstrapTargetAlreadyAdmin"
        t.insert("state", {"count": 1})
        yield
        t.write(acct(target), {**a, "auth": True})
        yield
        t.audit.append(("BOOTSTRAP", target))
        return "OK"
    return with_retry(body)


# --------------------------------------------------------------------------
# Invariantes
# --------------------------------------------------------------------------

def check(db, results, ops):
    v = []
    admins = [k for k, x in db.docs.items() if k.startswith("acct:") and x["auth"]]
    active_admins = [k for k in admins if db.docs[k]["status"] == "ACTIVE"]
    st = db.docs.get("state")
    if st is not None and st["count"] != len(admins):
        v.append(f"I1 contador={st['count']} != admins reales={len(admins)}")
    if (st is not None or any(k.startswith("acct:") for k in db.docs)) and len(admins) < 1 and "BOOT" not in str(ops):
        v.append("I2 cero Platform Administrators")
    inactive_admins = [k for k in admins if db.docs[k]["status"] != "ACTIVE"]
    if inactive_admins:
        v.append(f"I6 admin INACTIVO contado en el contador (bloqueo latente): {inactive_admins}")
    if st is not None and len(active_admins) < 1:
        v.append(f"I3 BLOQUEO: ningún administrador ACTIVO (admins={admins})")
    ok = sum(1 for r in results if r == "OK")
    audited = sum(1 for e in db.audit if e[0] in ("GRANT", "REVOKE", "BOOTSTRAP", "DEACTIVATE"))
    if ok != audited:
        v.append(f"I4 auditoría: {ok} éxitos vs {audited} entradas")
    if sum(1 for e in db.audit if e[0] == "BOOTSTRAP") > 1:
        v.append("I5 bootstrap ejecutado más de una vez")
    return v


# --------------------------------------------------------------------------
# Exploración exhaustiva (stateless DFS: re-ejecuta cada prefijo desde cero)
# --------------------------------------------------------------------------

def run(initial, opfactories, schedule):
    db = DB(initial)
    gens = [f(db) for f in opfactories]
    results = [None] * len(gens)
    alive = list(range(len(gens)))
    trace = []
    # primer paso de cada generador se ejecuta sólo cuando el planificador lo elige
    started = [False] * len(gens)
    i = 0
    while True:
        enabled = [g for g in alive]
        if not enabled:
            return db, results, None, trace
        if i >= len(schedule):
            return db, results, enabled, trace
        g = schedule[i]
        i += 1
        try:
            next(gens[g])
            trace.append(g)
        except StopIteration as e:
            results[g] = e.value
            alive.remove(g)
            trace.append(g)


def explore(initial, opfactories, limit=2_000_000):
    stats = {"schedules": 0, "violations": []}
    seen_outcomes = {}

    def dfs(prefix):
        if stats["schedules"] >= limit:
            return
        db, results, enabled, trace = run(initial, opfactories, prefix)
        if enabled is None:
            stats["schedules"] += 1
            key = (tuple(results), tuple(sorted((k, str(v)) for k, v in db.docs.items())))
            seen_outcomes[key] = seen_outcomes.get(key, 0) + 1
            viol = check(db, results, opfactories)
            if viol and len(stats["violations"]) < 3:
                stats["violations"].append((viol, results, prefix, {k: v for k, v in db.docs.items()}))
            elif viol:
                stats["violations"].append((viol,))
            return
        for g in enabled:
            dfs(prefix + [g])

    dfs([])
    return stats, seen_outcomes


def A(auth, status="ACTIVE"):
    return {"auth": auth, "status": status}


def scenario(title, initial, ops, designs):
    print("=" * 100)
    print(title)
    for dname, d in designs:
        factories = [f(t, d) for f, t in ops]
        stats, outcomes = explore(initial, factories)
        nviol = len(stats["violations"])
        verdict = "OK (0 contraejemplos)" if nviol == 0 else f"ROTO: {nviol} intercalaciones violan invariantes"
        print(f"  [{dname}] intercalaciones={stats['schedules']:>6}  resultados distintos={len(outcomes):>3}  -> {verdict}")
        for oc in sorted(outcomes):
            print(f"        resultado: {list(oc[0])}  x{outcomes[oc]}")
        if nviol:
            viol, results, prefix, docs = stats["violations"][0]
            print(f"        CONTRAEJEMPLO: {viol}")
            print(f"          resultados={results}  planificación={prefix}")
            print(f"          estado final={docs}")


if __name__ == "__main__":
    CLAUDE = {"grant_requires_active": False}
    CHATGPT = {"conditional_account_write": True}
    NAIVE = {"check_outside_tx": True}
    FULL = {"conditional_account_write": True, "grant_requires_active": True, "deactivate_guard": True,
            "bootstrap_checks_auth": True}

    scenario("S1a REVOKE(A) || REVOKE(B), 2 admins, SIN documento dedicado (count() sobre accounts)",
             {"acct:A": A(True), "acct:B": A(True)},
             [(revoke, "A"), (revoke, "B")],
             [("sin singleton", {"no_singleton": True})])

    scenario("S1  REVOKE(A) || REVOKE(B), 2 admins, CON documento dedicado",
             {"acct:A": A(True), "acct:B": A(True), "state": {"count": 2}},
             [(revoke, "A"), (revoke, "B")],
             [("Claude #9 (singleton + $gt:1)", CLAUDE),
              ("ChatGPT #9 (singleton + filtro en Account)", CHATGPT)])

    scenario("S2  GRANT(C) || GRANT(C) sobre la misma cuenta — ¿contador inflado? (crítica de ChatGPT a #9)",
             {"acct:A": A(True), "acct:C": A(False), "state": {"count": 1}},
             [(grant, "C"), (grant, "C")],
             [("D0 precondición FUERA de la transacción", NAIVE),
              ("D0 + update condicional de ChatGPT (defensa en profundidad)", {**NAIVE, **CHATGPT}),
              ("Claude #9 ($set + $inc, chequeo dentro)", CLAUDE),
              ("ChatGPT #9 (update condicional en Account)", CHATGPT)])

    scenario("S3  GRANT(C) || REVOKE(C) (C ya admin, 2 admins) — hueco #3",
             {"acct:A": A(True), "acct:C": A(True), "state": {"count": 2}},
             [(grant, "C"), (revoke, "C")],
             [("Claude", CLAUDE), ("ChatGPT", CHATGPT)])

    scenario("S4  REVOKE(A) || DEACTIVATE(B), 2 admins — hueco A (código actual de DeactivateAccount)",
             {"acct:A": A(True), "acct:B": A(True), "state": {"count": 2}},
             [(revoke, "A"), (deactivate, "B")],
             [("Deactivate ACTUAL (sin guarda)", CLAUDE),
              ("Deactivate con guarda propuesta", {"deactivate_guard": True})])

    scenario("S5  GRANT(C) || DEACTIVATE(C) — ¿puede quedar un admin INACTIVO?",
             {"acct:A": A(True), "acct:C": A(False), "state": {"count": 1}},
             [(grant, "C"), (deactivate, "C")],
             [("GRANT sin exigir ACTIVE + guarda en deactivate", {"conditional_account_write": True, "deactivate_guard": True}),
              ("FULL: GRANT exige ACTIVE + guarda", FULL)])

    scenario("S5b GRANT sobre cuenta YA INACTIVA (secuencial)",
             {"acct:A": A(True), "acct:C": A(False, "INACTIVE"), "state": {"count": 1}},
             [(grant, "C")],
             [("GRANT sin exigir ACTIVE (propuesta de ambas IAs)", {"conditional_account_write": True}),
              ("GRANT exige ACTIVE", FULL)])

    scenario("S6  BOOTSTRAP || BOOTSTRAP concurrentes",
             {"acct:A": A(False), "acct:B": A(False)},
             [(bootstrap, "A"), (bootstrap, "B")],
             [("Claude #5", CLAUDE), ("ChatGPT #5 (+ chequeo cuenta ya admin)", FULL)])

    scenario("S7  BOOTSTRAP sobre cuenta que YA tiene autoridad y sin estado (inconsistencia previa)",
             {"acct:A": A(True), "acct:B": A(False)},
             [(bootstrap, "B")],
             [("Claude #5 (no mira auth previa de otras cuentas)", CLAUDE), ("ChatGPT #5", FULL)])
