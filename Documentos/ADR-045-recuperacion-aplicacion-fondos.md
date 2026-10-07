# ADR-045 — Recuperación de la aplicación de fondos de `DonationIntent`

**Estado:** APROBADO — aprobación humana explícita de Carlos el 2026-10-07 ("sí, lo apruebo"), sobre el texto completo de este documento. Todo lo marcado como [PROPUESTA] queda aprobado tal como está redactado, incluida la marca `fundsAppliedAt` (§2.5) y `retry-window` = 4 horas (§2.3). Único valor abierto: `max-attempts`, tratado como ajuste de implementación (§7). Redactado por el agente el 2026-10-07.
**Número:** 045, reservado en el catálogo de `documento-maestro-proyecto.md` §5 (PR #30). Sustituye a la referencia "ADR-043 (recuperación)" de la Enmienda 2 de ADR-037, que nunca llegó a existir como documento.
**Origen de la obligación:** `reglas-equipo-y-agentes.md` §3.5 — introduce un mecanismo nuevo de reintento y recuperación de fallos.
**Regularización:** la consulta de recuperables (`findConfirmedPendingApplication`), la barrera `APPLY_FUNDS` y el estado `FUNDING_REJECTED` entraron en `develop` con el PR #29 sin este ADR aprobado. Este documento se aprueba o se rechaza **a posteriori** para esa parte (incumplimiento registrado en `auditoria-fase6-codigo-vs-documentacion.md` §10).

**Relacionados:**
- ADR-037 y sus Enmiendas 1 y 2 (`ADR-037-enmienda-2-convocatoria.md` §3–§5, base de este ADR).
- `ADR-042-orquestacion-centralizada-reintentos-proyeccion.md` (reintentos de proyección en `core`, **Aprobado**). Se cita por nombre de archivo porque el número 042 lo comparte hoy con el ADR del frontend web, que se renumerará a ADR-046.
- ADR-010 (ventana de 4 horas y cuarentena), ADR-022 (resolución manual por JMX), ADR-039 y su Enmienda 1 (lección de B-10: bloqueo por cabeza de cola).

**Etiquetas:** [DECISIÓN APROBADA] = decisión humana ya tomada (Enmienda 2 §1.1, aprobada con C1–C3 el 2026-10-07); [PROPUESTA] = diseño de este borrador, pendiente de aprobación; [PENDIENTE] = fuera de este ADR; [ESTADO] = hecho verificado del código o los documentos.

---

## 1. Context

- [DECISIÓN APROBADA] Confirmar una `DonationIntent` y aplicar sus fondos son actos separados (F-1, F-2). La aplicación es un Acto 2 del sistema, en una transacción MongoDB del orquestador de `app`: reclamo `APPLY_FUNDS` + incremento del ledger + `clearFundsGenesis` + outbox (Enmienda 2 §3.2).
- [DECISIÓN APROBADA] Recuperación automática con disparo inmediato y scheduler de respaldo en `app`; la consulta de recuperables pertenece a `convocatoria`; la seguridad con varias instancias la da la barrera, y el scheduler no añade una segunda lógica de negocio (D2, Enmienda 2 §5).
- [ESTADO] Una intención aplicada **sigue en `CONFIRMED` para siempre**: la marca de aplicación es el reclamo en `convocatoria_processed_commands`, no un campo de la intención (`implementation_plan.md` §17.4).
- [ESTADO] `findConfirmedPendingApplication(limit)` (`MongoDonationIntentRepositoryAdapter.java:80-114`) es una agregación: `match status = CONFIRMED` → `sort _id` → `$lookup` del reclamo → descarta reclamadas → `$lookup` del ledger → excluye `CLOSE_ON_TARGET + CLOSE` → `limit`. No hay índice en `donation_intents.status`. Su coste crece con el histórico.
- [ESTADO] El orden es `_id`, es decir, fijo. Una intención que falla en cada intento aparece siempre en la misma posición del lote.
- [ESTADO] Hoy no existen en `app` el orquestador, el disparo inmediato ni el scheduler. Bloqueos: T1 (`clearFundsGenesis` reintenta dentro de `retryTemplate`, `FundCommandService.java:83-97`) y P8 (la génesis no escribe outbox).
- [DECISIÓN APROBADA] No se habilita el flujo con dinero real mientras P1 (registro de dinero no aceptable) siga fuera de corte (Enmienda 2 §4, D4).

### Problemas que este ADR debe resolver

1. **Bloqueo por cabeza de cola.** Las anomalías de invariante (`CampaignNotFoundException`, `InvalidFundingAmountException`, `InvalidFundGenesisException`, `DonationIntentNotFoundException`) dejan la intención en `CONFIRMED` y se propagan (Enmienda 2 §4). Con orden fijo y `limit`, `limit` intenciones anómalas ocupan el lote en cada ciclo y las sanas nunca se procesan. Es el mismo defecto que B-10 en `COLLECTING`, y el que `ADR-042-orquestacion-centralizada-reintentos-proyeccion.md` ya resolvió para proyecciones.
2. **Coste creciente del descubrimiento.** La consulta recorre todas las `CONFIRMED` históricas y las cruza con otra colección.
3. **Condición sin salida (P9).** Las intenciones `CONFIRMED` de convocatorias `CLOSE_ON_TARGET + CLOSE` quedan fuera de la cola hasta que exista R4, sin estado terminal ni reintento. Es una decisión aprobada, pero hoy es invisible.

---

## 2. Decision

### 2.1 Disparo inmediato

- [DECISIÓN APROBADA] Lo ejecuta el caso de uso de `app` que invoca la confirmación, porque `convocatoria` no puede llamar a `app` (Enmienda 2 §5).
- [PROPUESTA] Secuencia: **Tx 1** confirmación (`convocatoria`, commit) → **Tx 2** aplicación (orquestador). El resultado de la confirmación no depende del resultado de la aplicación: si Tx 2 falla, termina en `FUNDING_REJECTED` o se interrumpe (caída del proceso), la confirmación ya está registrada y la intención queda recuperable por el scheduler (§2.2).
- [PROPUESTA] El disparo inmediato hace un solo intento de Tx 2 (con el reintento acotado de errores transitorios de la Enmienda 1 §6 a nivel de transacción completa). No reintenta en bucle: eso es trabajo del scheduler.
- [PENDIENTE] El webhook (P3) usará el mismo orquestador para Tx 2. Su contrato queda fuera de este ADR.

### 2.2 Scheduler de respaldo

- [DECISIÓN APROBADA] Vive en `app`; usa la consulta de `convocatoria`.
- [PROPUESTA] Sigue el patrón de `BlockchainAnchorProducer` (`@Scheduled` con `fixedDelay`, lote con límite configurable, transiciones condicionales, "no-op benigno" si otra instancia ya hizo el trabajo).
- [PROPUESTA] **Como máximo un intento por intención y por ejecución**, cada uno en su propia Tx 2. Mismo principio que `ADR-042-orquestacion-centralizada-reintentos-proyeccion.md` decisión 6.
- [PROPUESTA] Configuración: `enabled`, `fixed-delay`, `batch-size`, `retry-window` (§2.3), `max-attempts` (§2.3).
- [PROPUESTA] **Habilitación por entorno:** `enabled = false` por defecto en producción hasta que existan P1, T1 y P8. Cumple literalmente la Enmienda 2 §4 (no habilitar dinero real sin P1). En desarrollo y pruebas puede activarse.

### 2.3 Taxonomía de resultados y salida de anomalías

[PROPUESTA] Se adopta la taxonomía de `ADR-042-orquestacion-centralizada-reintentos-proyeccion.md` (reintentable / permanente / cuarentena), aplicada a la aplicación de fondos:

| Resultado de Tx 2 | Efecto | ¿Cuenta como intento fallido? |
|---|---|---|
| Éxito | Fondos aplicados (reclamo escrito) | No |
| Barrera ya reclamada (otra instancia o el disparo inmediato) | No-op; resultado original conservado (N12) | **No** |
| `CampaignFundingLimitExceededException` | `CONFIRMED → FUNDING_REJECTED` en transacción propia, con motivo y fecha (Enmienda 2 §4 + condición C2) | No (es un resultado terminal de negocio) |
| Transitorio que agota el reintento acotado (`TransientTransactionError`, `ConcurrencyConflictException` de la génesis) | Sigue `CONFIRMED`; reintentable | Sí |
| Anomalía de invariante (`CampaignNotFoundException`, `InvalidFundingAmountException`, `InvalidFundGenesisException`, `DonationIntentNotFoundException`) | Sigue `CONFIRMED`; **cuarentena inmediata** | Sí |
| `CloseOnTargetCloseNotSupportedException` (no debería llegar: la consulta ya la excluye) | Sigue `CONFIRMED`; se trata como anomalía de consistencia → cuarentena | Sí |
| Excepción no clasificada | Cuarentena (permanente por defecto, igual que ADR-042 de proyecciones) | Sí |

- [PROPUESTA] **Metadatos de recuperación en la intención**, sin estado nuevo: `applicationAttempts`, `firstApplicationAttemptAt`, `lastApplicationAttemptAt`, `lastApplicationError` (clase de excepción, sin mensaje libre ni PII) y `applicationQuarantined` (booleano). `CONFIRMED` conserva el significado exacto de F-1; la cuarentena es un dato de operación, no un estado de dominio.
- [PROPUESTA] Los metadatos se escriben **en una escritura propia después del rollback de Tx 2**, condicionada a `status = CONFIRMED` y a ausencia de reclamo `APPLY_FUNDS`. Mismo criterio que la transición a `FUNDING_REJECTED`. Si esa escritura falla, el intento no queda contado y el siguiente ciclo lo repite (la barrera evita efectos dobles).
- [PROPUESTA] **Cuarentena por agotamiento:** un fallo reintentable pasa a cuarentena cuando supera `max-attempts` **o** han pasado más de `retry-window` (propuesto: 4 horas, como ADR-010) desde `firstApplicationAttemptAt`, lo que ocurra primero.
- [PROPUESTA] **Salida manual** por JMX (patrón ADR-022): `releaseApplicationQuarantine(intentId)` pone `applicationQuarantined = false` y reinicia los contadores, para usarlo tras corregir la causa. La operación es condicional (`status = CONFIRMED`, sin reclamo) y queda en el audit log de `convocatoria` con operador y motivo.
- [PROPUESTA] Una intención en cuarentena **no** pasa a `FUNDING_REJECTED`: una anomalía de invariante no es un rechazo de negocio (Enmienda 2 §4) y convertirla en terminal escondería un defecto.

### 2.4 Orden y equidad del lote

- [PROPUESTA] La consulta excluye `applicationQuarantined = true` y ordena por `applicationAttempts ASC`, luego `lastApplicationAttemptAt ASC` (ausente primero), luego `_id`. Las intenciones nuevas o con menos intentos van primero; las que fallan bajan en la cola en vez de ocupar siempre el primer puesto.
- [PROPUESTA] Con un intento por ejecución (§2.2), el orden y la cuarentena juntos garantizan progreso: ninguna intención reintentable puede impedir indefinidamente el procesamiento de otra.

### 2.5 Coste del descubrimiento

[PROPUESTA] **Marca de aplicación en la intención:** `fundsAppliedAt`, escrita **dentro de Tx 2**, en la misma transacción que el reclamo `APPLY_FUNDS`.
- La barrera sigue siendo la única fuente de verdad de la idempotencia. La marca es un dato derivado, pero al escribirse en la misma transacción no puede divergir del reclamo.
- La consulta pasa a `match status = CONFIRMED, fundsAppliedAt ausente, applicationQuarantined ≠ true`, apoyada en un **índice parcial** sobre ese filtro. Deja de recorrer el histórico de intenciones ya aplicadas.
- El `$lookup` del reclamo se conserva como comprobación defensiva sobre el conjunto ya reducido. El `$lookup` del ledger para P9 también se aplica solo sobre ese conjunto.
- [ESTADO] Modifica lo que dice `implementation_plan.md` §17.4 ("la marca de aplicación es el reclamo"): el reclamo sigue siendo la barrera, pero deja de ser la única marca. Requiere cambio de código en `convocatoria`.

### 2.6 Condición sin salida de P9

- [DECISIÓN APROBADA] Las intenciones `CONFIRMED` de `CLOSE_ON_TARGET + CLOSE` quedan fuera de la cola mientras R4 no exista (P9, opción a).
- [PROPUESTA] Se registra como **condición sin salida conocida** bajo la regla §2.6, aceptada solo mientras el dinero real esté deshabilitado (§2.2).
- [PROPUESTA] Métricas expuestas por el scheduler en cada ejecución: número de intenciones excluidas por P9, número en cuarentena y antigüedad de la intención recuperable más antigua. Advertencia en log si cualquiera de las dos primeras es mayor que cero.

### 2.7 Seguridad con varias instancias

- [DECISIÓN APROBADA] La da la barrera (§3.3 de la Enmienda 2). El scheduler no añade bloqueos propios.
- [PROPUESTA] Dos instancias pueden tomar la misma intención en el mismo ciclo: una aplica, la otra encuentra el reclamo y termina en no-op, que no cuenta como intento fallido (§2.3). La colisión dentro de la transacción es un conflicto transitorio que se reintenta y termina en ese mismo no-op.
- [PROPUESTA] La escritura de metadatos (§2.3) es condicional y su `$inc` es atómico, así que dos instancias que fallan a la vez cuentan dos intentos, sin pérdida de actualizaciones.

### 2.8 Observabilidad

- [PROPUESTA] Disparo inmediato: hereda el `correlationId` de la petición que confirmó.
- [PROPUESTA] Scheduler: genera un `runId` por ejecución y un `correlationId` por intención derivado de él. Cada intento registra `intentId`, `campaignRef`, `fundId`, resultado de la taxonomía de §2.3 y duración.
- [PENDIENTE] Unificar esta convención con el origen del `correlationId` en los schedulers de blockchain (pendiente B-8 de la auditoría de Fase 6).

---

## 3. Alternatives

| Alternativa | Por qué no se propone |
|---|---|
| Confirmación y aplicación en una sola transacción | Contradice F-1/F-2 (aprobadas) |
| Solo scheduler, sin disparo inmediato | Latencia innecesaria en el caso normal; D2 exige ambos |
| Solo disparo inmediato, sin scheduler | Una caída entre Tx 1 y Tx 2 pierde la aplicación; D2 exige ambos |
| Evento de dominio u outbox emitido al confirmar, consumido por un handler | Añade infraestructura de mensajería que `convocatoria` no tiene; la barrera más una consulta cubre el caso sin ella |
| Nuevo estado `APPLICATION_QUARANTINED` en `DonationIntent` | Amplía la máquina de estados de la Enmienda 2 y cambia el contrato público de estados (ADR-041); la cuarentena es operativa, no de dominio |
| Colección aparte para intentos y cuarentena | Separa datos que se consultan juntos y obliga a otro `$lookup`, que es justo el coste que §2.5 quiere reducir |
| Mantener el orden por `_id` sin cuarentena | Reproduce el bloqueo por cabeza de cola (B-10) |
| Ordenar por intentos sin tope ni cuarentena | Quita el bloqueo, pero las anomalías se reintentan para siempre y consumen capacidad de cada lote |
| Aceptar el coste del descubrimiento con un `limit` medido | Válido para el corte académico, pero el coste sigue creciendo con cada intención aplicada |
| Pasar las anomalías a `FUNDING_REJECTED` | Esconde defectos detrás de un rechazo de negocio (Enmienda 2 §4) |

---

## 4. Consequences

### Positivas

- La aplicación de fondos tiene progreso garantizado: ninguna intención puede bloquear indefinidamente a las demás.
- Toda condición de fallo tiene una salida nombrada: `FUNDING_REJECTED` para el rechazo de negocio, cuarentena con salida manual para las anomalías, métricas visibles para P9.
- El descubrimiento deja de depender del tamaño del histórico.
- Reutiliza dos patrones ya aprobados y probados en el propio sistema: `BlockchainAnchorProducer` y `ADR-042-orquestacion-centralizada-reintentos-proyeccion.md`.

### Negativas y deuda aceptada

- `DonationIntent` gana campos de operación (`fundsAppliedAt` y metadatos de recuperación) que no son de dominio puro.
- La clasificación reintentable/permanente vive en el scheduler de `app`; si cambian las excepciones de `convocatoria` o `core`, hay que mantenerla sincronizada (misma deuda que acepta ADR-042 de proyecciones).
- P9 sigue sin salida hasta R4; solo se hace visible.
- Mientras P1 no exista, el mecanismo completo solo puede ejercitarse en desarrollo y pruebas.

---

## 5. Dependencias

| Dependencia | Módulo | Qué bloquea |
|---|---|---|
| Aprobación de la Enmienda 2 de ADR-037 (con C1–C3) | — | Base normativa de este ADR |
| T1: `clearFundsGenesis` sin reintento interno | `core` | Tx 2 |
| P8: mensaje de outbox de la génesis | `core` | Tx 2 conforme a ADR-037 §2.3 |
| Adaptador de producción de `OrganizationVerificationPort` | `app` | **Resuelto** en `e269985` |
| P1: registro de dinero no aceptable | producto / `convocatoria` | Habilitar el scheduler en producción |
| P3: webhook | `app` / `api` | Confirmación de intenciones de pasarela |
| R4 | `convocatoria` | Salida de las intenciones de P9 |

---

## 6. Definition of Done (tests que deben existir antes de cerrar la implementación)

- Disparo inmediato: Tx 1 confirma; si Tx 2 falla o el proceso cae entre ambas, la intención queda `CONFIRMED` y el scheduler la aplica en la siguiente ejecución.
- Una sola aplicación efectiva con disparo inmediato y scheduler concurrentes, y con dos instancias del scheduler (Testcontainers, réplica real).
- El no-op por barrera ya reclamada no incrementa `applicationAttempts`.
- `CampaignFundingLimitExceededException` → `FUNDING_REJECTED` con motivo y fecha en la misma transacción; las demás ramas no se tocan (`verify(..., never())`).
- Anomalía de invariante → cuarentena inmediata; nunca `FUNDING_REJECTED`.
- Fallo reintentable → cuarentena al superar `max-attempts` o `retry-window`.
- **Equidad:** con `batch-size` intenciones anómalas al frente de la cola, una intención sana posterior se aplica en el mismo ciclo o en el siguiente.
- `releaseApplicationQuarantine` reinicia contadores, la intención vuelve a la cola y la operación queda auditada.
- `fundsAppliedAt` se escribe en la misma transacción que el reclamo; un rollback de Tx 2 no deja ninguno de los dos.
- La consulta no devuelve intenciones con `fundsAppliedAt`, en cuarentena ni de `CLOSE_ON_TARGET + CLOSE`, y usa el índice parcial (`explain`).
- Métricas de P9 y de cuarentena expuestas.
- Con `enabled = false`, el scheduler no ejecuta ningún intento.

---

## 7. Status y condiciones de aprobación

**APROBADO — Carlos, 2026-10-07.**

| # | Condición | Estado |
|---|---|---|
| 1 | Aprobar el texto de §2.1 a §2.8 | APROBADO (2026-10-07) |
| 2 | Marca `fundsAppliedAt` (§2.5) frente a aceptar el coste con un límite medido | APROBADO: `fundsAppliedAt` + índice parcial |
| 3 | `retry-window` y `max-attempts` | `retry-window` = 4 horas, APROBADO. `max-attempts`: valor de implementación, sin decisión arquitectónica (mismo criterio que `maxEventsPerBatch`); la ventana de 4 horas acota los reintentos aunque `max-attempts` no se fije |
| 4 | Constancia de que la consulta, la barrera y `FUNDING_REJECTED` entraron en el PR #29 sin este ADR | APROBADO de forma retroactiva; el incumplimiento de §3.5 queda registrado |
| 5 | Enmienda 2 de ADR-037 §5 y §9 citan ADR-045 | PENDIENTE de verificar en el repositorio (PR #30) |

**Qué autoriza esta aprobación:** el diseño. El código (orquestador, disparo inmediato, scheduler, metadatos de recuperación, `fundsAppliedAt`, índice parcial) sigue exigiendo plan de implementación aprobado (regla §3.4) y está bloqueado por T1 y P8 (§5).
