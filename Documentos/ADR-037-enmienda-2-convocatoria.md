# ADR-037 — Enmienda 2 — Confirmación y aplicación de fondos de `DonationIntent`

**Estado:** **APROBADA — Carlos, 2026-10-07**, con las condiciones C1–C3 (integradas en §4 y §9). Hasta esa fecha fue BORRADOR: recoge decisiones humanas aprobadas el 2026-10-02 (§1.1); el texto lo redactó el agente. La aprobación es en parte retroactiva: la barrera `APPLY_FUNDS`, `FUNDING_REJECTED` y la consulta de recuperables se fusionaron en el PR #29 antes de esta aprobación (incumplimiento de la regla 3.5 registrado en `auditoria-fase6-codigo-vs-documentacion.md` §10.4).
**Fecha:** 2026-10-02.
**ADR que enmienda:** ADR-037 (`ADR-037-convocatoria-ledger-assignment-donationintent.md`) y su Enmienda 1 (`ADR-037-enmienda-1-convocatoria.md`). "ADR-037 §X" y "Enmienda 1 §X" se refieren a esos archivos.
*Nota de numeración (2026-10-07, dirección aprobada por Carlos):* este borrador citaba "ADR-043" en 6 lugares. Ese número lo ocupa `ADR-043-frontend-movil-paxfide-mobile.md` y el 044 está reservado al componente predictivo, así que el ADR de recuperación pasa a **ADR-045** (`ADR-045-recuperacion-aplicacion-fondos.md`, APROBADO el 2026-10-07). Ver `auditoria-fase6-codigo-vs-documentacion.md` §10 y `documento-maestro-proyecto.md` §5.

**ADR asociado:** ADR-045 (recuperación automática de la aplicación de fondos), exigido por la regla 3.5 de `reglas-equipo-y-agentes.md`.
**Fuentes:** decisiones humanas del 2026-10-02 registradas en `convocatoria-resumen.md` §6.20; auditorías temporales `auditoria-c01-postcorreccion.md`, `auditoria-cierre-f1-f2.md`, `auditoria-flujo-confirmed-fondos-v3.md`, `debate-cierre-confirmed-fondos.md`, `auditoria-preimplementacion-cierre-convocatoria.md`, `auditoria-delimitacion-cierre-convocatoria.md` (evidencia, no normativa).

---

## 1. Objeto, vigencia y precedencia

Separar la confirmación de una `DonationIntent` de la aplicación de sus fondos, fijar la barrera de idempotencia de la aplicación y el estado terminal de rechazo, y declarar lo que corresponde a otros módulos.

**Regla de vigencia:** todo lo que esta enmienda no modifica explícitamente sigue vigente según ADR-037 y la Enmienda 1. **Regla de precedencia:** donde se contradigan, esta enmienda prevalece sobre ADR-037 y la Enmienda 1 (la regla de `convocatoria-resumen.md:91` exige enmienda para que una decisión humana modifique un ADR; esta es esa enmienda).

Se mantienen las etiquetas de la Enmienda 1 ([DECISIÓN], [REQUISITO], [PENDIENTE], [ESTADO], [DEPENDENCIA]).

### 1.1 Decisiones humanas que incorpora (2026-10-02)

| # | Decisión |
|---|---|
| F-1 | Confirmar una `DonationIntent` y aplicar sus fondos son actos separados. `PENDING → CONFIRMED` no significa fondos aplicados; la aplicación ocurre después |
| F-2 | `confirmDonationIntent()` no crea `Fund`, no aplica fondos ni incrementa el ledger; la génesis del `Fund` y la aplicación financiera pertenecen al flujo posterior |
| D1 | La barrera `CONFIRMED → fondos aplicados` es idempotente por intención, con un tipo de comando de sistema propio y una clave determinista derivada de la intención; nunca el `commandId` del cliente. Una sola aplicación efectiva por intención, también con reintentos y concurrencia. Se reutiliza el registro de comandos procesados del módulo |
| D2 | Recuperación automática: disparo inmediato cuando corresponda y scheduler de respaldo en `app`; la consulta de intenciones recuperables pertenece a `convocatoria`; segura con varias instancias |
| D3 | Una intención que no puede financiarse de forma permanente pasa al estado terminal `FUNDING_REJECTED`. `CampaignFundingLimitExceededException` es permanente en este corte. `CloseOnTargetCloseNotSupportedException` **no** lleva a `FUNDING_REJECTED` mientras R4 no esté implementado. Los conflictos transitorios se reintentan. No se reutiliza `FAILED` |
| D4 | P1 (registro del dinero rechazado) sigue fuera de este corte |
| D5 | La confirmación manual solo la ejecuta un `ADMINISTRATOR` de la organización; una intención de pasarela no se confirma manualmente; `confirmedBy` es el actor real |
| D6 | La aplicación la dispara el sistema; no requiere un segundo acto humano |
| D7 | Esta enmienda y ADR-045, antes del código |
| Claves | El espacio de claves se separa solo para el comando de sistema: entre comandos de cliente se mantiene I1 (`implementation_plan.md` §7.1) |

---

## 2. Decisiones que modifica

| Tema | Qué decía | Qué cambia | Sección |
|---|---|---|---|
| Barrera financiera | Enmienda 1 §5.3: la transición `PENDING → CONFIRMED` es la barrera antes de cualquier efecto financiero, en la misma transacción | La confirmación es un acto propio. La barrera de la aplicación es el reclamo del comando de sistema `APPLY_FUNDS` por intención | §3.3 |
| Confirmación manual | Enmienda 1 §5.2 N9: "se ejecuta como `CLEAR_FUNDS_AS_GENESIS`" | Confirmar no es la génesis. La confirmación manual exige `ADMINISTRATOR` de la organización (mismo rol que N9 exigía) | §3.1 |
| Estados de `DonationIntent` | ADR-037 §2.6: `PENDING`, `CONFIRMED`, `FAILED`, `EXPIRED-UNKNOWN` | Se añade `FUNDING_REJECTED` (terminal) | §4 |
| Flujo del webhook | ADR-037 §2.6 paso 3: correlación válida → `clearFundsGenesis` | Dos actos: confirmación y aplicación posterior (§3) | §3 |
| Registro de comandos procesados | Enmienda 1 §3.5: comandos de escritura del módulo, `commandId` del cliente | Añade comandos de sistema con espacio de claves propio | §3.3 |
| Recuperación | No existía | Disparo inmediato + scheduler de respaldo (ADR-045) | §5 |

---

## 3. Flujo definitivo

```
Acto 1 — confirmación (convocatoria):
  PENDING ──confirmDonationIntent()──▶ CONFIRMED          (no toca ledger, Fund ni registro de comandos)

Acto 2 — aplicación posterior (sistema; orquestador de app, UNA transacción MongoDB):
  CONFIRMED ──▶ reclamo (APPLY_FUNDS, intentId)   ← barrera (convocatoria)
              + incremento del ledger por política           (convocatoria, ADR-037 §2.2)
              + clearFundsGenesis(commandId derivado, fundId de la intención)  (core)
              + outbox                                       (core; §3.2)
  barrera ya reclamada  → no-op (el resultado original se conserva)
  rechazo permanente    → rollback + CONFIRMED → FUNDING_REJECTED en otra transacción (§4)
  fallo transitorio     → rollback completo + reintento de la transacción entera (Enmienda 1 §6)
```

### 3.1 Confirmación

- **[DECISIÓN]** `CONFIRMED` significa exclusivamente que la intención fue confirmada correctamente. **No** significa fondos aplicados al ledger ni que exista un `Fund` (F-1).
- **[DECISIÓN]** `confirmDonationIntent()` registra la confirmación (quién, cuándo, medio, referencia; N8) y nada más: no crea `Fund`, no aplica fondos, no incrementa el ledger, no reclama la barrera (F-2).
- **[DECISIÓN]** Confirmación manual (`BANK_TRANSFER`): solo un `ADMINISTRATOR` de la organización de la intención, comprobado con la política existente de `convocatoria` (`ConvocatoriaAuthorizationPolicy.requireAdministratorOf`, sobre `IdentityPrincipalPort`). `confirmedBy` es la cuenta del actor autorizado, no texto libre (D5).
- **[DECISIÓN]** Una intención de pasarela (`paymentMethod = GATEWAY` / `confirmationSource = PAYMENT_PROVIDER`) no se confirma por el camino manual (D5; N8: "la confirmación humana nunca sustituye al webhook"). Su confirmación corresponde al webhook (P3).
- **[ESTADO]** Sustituye en N9 la frase "La confirmación manual se ejecuta como `CLEAR_FUNDS_AS_GENESIS`". Se conserva la exigencia de `ADMINISTRATOR`, ahora aplicada a la confirmación. `CLEAR_FUNDS_AS_GENESIS` (`ADMINISTRATOR`, ADR-032) sigue rigiendo la génesis para actores humanos; la aplicación del Acto 2 la ejecuta el sistema (§3.2).
- **[DEPENDENCIA]** El filtrado de cuentas `INACTIVE` en `IdentityPrincipalPort` pertenece a ADR-038.

### 3.2 Aplicación posterior

- **[DECISIÓN]** La aplicación consume una intención `CONFIRMED`; una intención en otro estado no se aplica (F-1).
- **[DECISIÓN]** La dispara el sistema (D6), por disparo inmediato o por el scheduler de respaldo (§5, ADR-045). No exige un segundo acto humano. En `core`, la génesis la ejecuta un `SystemActor`; `core` no comprueba roles para actores de sistema, así que **la autorización humana del movimiento de dinero es la de la confirmación** (§3.1).
- **[DECISIÓN]** Ledger + génesis del `Fund` + outbox en **una** transacción MongoDB del orquestador de `app` (ADR-037 §2.3, sin cambios). `convocatoria` aporta la barrera y el incremento del ledger, uniéndose a la transacción externa y marcándola para rollback ante cualquier excepción; `core` aporta la génesis y el outbox.
- **[REQUISITO]** Ninguna pieza reintenta dentro de la transacción del orquestador (Enmienda 1 §6): `convocatoria` reclama la barrera sin reintento interno cuando hay transacción activa; `clearFundsGenesis` necesita un camino sin reintento interno (T1, `implementation_plan.md` §8).
- **[REQUISITO]** El `commandId` de `clearFundsGenesis` se deriva de forma determinista de la intención (Enmienda 1 §5.3, sin cambios).
- **[PENDIENTE] P8** Contenido del mensaje de outbox de la génesis: ADR-037 §2.3 lo exige, pero hoy `clearFundsGenesis` no escribe ninguno y ningún documento define cuál debe ser. Se decide en `core`.

### 3.3 Barrera de idempotencia

- **[DECISIÓN]** La barrera es el registro de comandos procesados de `convocatoria` (Enmienda 1 §3.5), con el comando de sistema `APPLY_FUNDS` y `commandId = intentId` (clave conceptual `APPLY_FUNDS:{intentId}`). No se crea una segunda infraestructura de idempotencia (Enmienda 1 §2.1 se respeta: mismo mecanismo, misma colección, mismo reclamo atómico, misma transacción).
- **[DECISIÓN]** Identidad lógica de un comando de sistema: `(commandType, commandId)`, en un espacio de claves separado del de los comandos de cliente, representado como `_id = {commandType, commandId}` (subdocumento): un `_id` de tipo documento nunca coincide con uno de texto, así que ningún `commandId` de cliente puede ocupar una clave de sistema. No se usa la cadena literal `APPLY_FUNDS:{intentId}` como `_id`, porque el `commandId` de cliente es texto libre y podría coincidir con ella. Entre comandos de cliente se mantiene el espacio único con la regla I1.
- **[REQUISITO]** El reclamo de la barrera va **antes** del incremento del ledger en la transacción. Una repetición (secuencial, concurrente o tras reinicio) encuentra el reclamo y no produce un segundo efecto; ante un duplicado **se devuelve** el resultado original guardado, sin modificarlo (N12).
- **[ESTADO]** Relación con ADR-013: el identificador de negocio de la aplicación es la intención (`intentId`); la clave del registro es un valor de sistema derivado de él, en su propio espacio, no intercambiable con un `commandId` de cliente. Mismo criterio que la Enmienda 1 §5.3 para el `commandId` de la génesis.

---

## 4. Estado `FUNDING_REJECTED`

- **[DECISIÓN]** `FUNDING_REJECTED` es terminal y significa exclusivamente: la intención estaba `CONFIRMED` y el intento de aplicación financiera fue rechazado de forma permanente. No reutiliza `FAILED` (fallo del pago, ADR-041; transiciones del webhook, P3).

| Resultado de la aplicación | Efecto sobre la intención | Motivo |
|---|---|---|
| `CampaignFundingLimitExceededException` | `CONFIRMED → FUNDING_REJECTED` | Permanente en este corte: la meta no se edita y el ledger no tiene operación de liberación |
| `CloseOnTargetCloseNotSupportedException` | **Sigue `CONFIRMED`**, sin aplicar, y **fuera de la cola de recuperación** (P9, opción a) | R4 pendiente: no se convierte en terminal ni se reintenta indefinidamente; cuando exista R4 se definirá cómo vuelven a ser elegibles |
| Conflicto transitorio (`TransientTransactionError`, colisión del reclamo, `ConcurrencyConflictException` de la génesis) | Sigue `CONFIRMED`; reintento | No es rechazo |
| `CampaignNotFoundException`, `InvalidFundingAmountException`, `InvalidFundGenesisException`, `DonationIntentNotFoundException` | Sigue `CONFIRMED`; se propaga | Anomalías de invariante, no rechazo de negocio |

- **[CONDICIÓN C1 — 2026-10-07]** `FUNDING_REJECTED` es terminal **solo mientras** la meta de la convocatoria no se edite y el ledger no tenga operación de liberación. Cualquier cambio en una de esas dos premisas obliga a revisar esta sección antes de fusionarse.
- **[REQUISITO — condición C2, 2026-10-07]** La transición a `FUNDING_REJECTED` registra **motivo y fecha en la misma transacción** que el cambio de estado. P10 (§7) queda solo para el formato completo de la entrada del audit log.
- **[REQUISITO]** La transición a `FUNDING_REJECTED` se escribe en una transacción propia (la de la aplicación se revierte), condicionada a `status = CONFIRMED` y a que la barrera no esté reclamada. La operación verifica por sí misma que la condición es permanente (capacidad insuficiente en una política con límite); no se fía del llamador.
- **[DEPENDENCIA] P1 (D4).** El dinero de una intención `FUNDING_REJECTED` queda sin registro mientras P1 siga fuera de corte (Enmienda 1 §3.3). Consecuencia: **no se habilita el flujo con dinero real** (webhook u orquestador en producción) hasta que exista el registro de dinero no aceptable. La intención sí tiene salida (estado terminal); el dinero no.

---

## 5. Recuperación

- **[DECISIÓN]** Disparo inmediato de la aplicación tras confirmar, y scheduler de respaldo que recupera intenciones `CONFIRMED` sin aplicar (D2). Detalle en ADR-045.
- **[DECISIÓN]** La consulta de intenciones recuperables pertenece a `convocatoria`: intenciones `CONFIRMED` sin reclamo `APPLY_FUNDS`, excluidas las de convocatorias `CLOSE_ON_TARGET + CLOSE` mientras R4 no exista (P9, opción a). Una intención `FUNDING_REJECTED` nunca vuelve a la cola. El scheduler pertenece a `app`.
- **[REQUISITO]** Seguridad con varias instancias: la proporciona la barrera (§3.3); el scheduler no añade una segunda lógica de negocio.
- **[ESTADO]** `convocatoria` no puede llamar a `app` (regla de arquitectura), así que el disparo inmediato lo hace el caso de uso de `app` que invoca la confirmación.

---

## 6. Dependencias

| Dependencia | Módulo | Qué bloquea |
|---|---|---|
| Implementación de producción de `OrganizationVerificationPort` (ADR-038) | `identity`/`app` | `app → convocatoria`: orquestador, disparo inmediato, scheduler |
| T1: `clearFundsGenesis` sin reintento interno | `core` | La transacción única del Acto 2 |
| P8: mensaje de outbox de la génesis | `core` | Cumplimiento literal de ADR-037 §2.3 |
| P3: webhook | `app`/`api` | Confirmación de intenciones de pasarela |
| ADR-041 / `api-contract-matrix.md` | `api` | Estado público `FUNDING_REJECTED`; contrato HTTP de la confirmación manual (no definido) |
| R4 | `convocatoria` | Intenciones `CONFIRMED` con `CLOSE_ON_TARGET + CLOSE` |
| P1 | producto / `convocatoria` (N4) | Habilitar dinero real con rechazos |

---

## 7. Pendientes explícitos

| # | Pendiente | Dónde se decide |
|---|---|---|
| P8 | Contenido del mensaje de outbox de la génesis | `core` / enmienda de ADR-037 §2.3 |
| P9 | ✅ Resuelto (decisión humana del 2026-10-02, opción a): las intenciones `CONFIRMED` de convocatorias `CLOSE_ON_TARGET + CLOSE` quedan fuera de la cola automática mientras R4 no exista; ni `FUNDING_REJECTED` ni reintento indefinido. R4 deberá definir cómo vuelven a ser elegibles | — |
| P10 | Formato completo de la entrada de audit log de `FUNDING_REJECTED`. Motivo y fecha en la misma transacción ya son requisito (C2, §4) | Enmienda posterior |
| P1, P3, R4 | Sin cambios (Enmienda 1 §8) | — |

---

## 8. Compatibilidad

- **[ESTADO]** El módulo `convocatoria` no está desplegado: no hay datos que migrar. El cambio de clave de los comandos de sistema no afecta a los documentos de comandos de cliente existentes.

---

## 9. Aprobación

- [x] Aprobación humana explícita del texto de esta enmienda — **Carlos, 2026-10-07**, con C1 (§4), C2 (§4) y C3 (numeración: toda referencia al ADR de recuperación dice ADR-045; verificado con `grep`).
- [x] Aprobación humana de ADR-045 — **Carlos, 2026-10-07** (`ADR-045-recuperacion-aplicacion-fondos.md`).
- [ ] Apertura de las dependencias de §6 en sus módulos (no bloquea el código de `convocatoria`).
