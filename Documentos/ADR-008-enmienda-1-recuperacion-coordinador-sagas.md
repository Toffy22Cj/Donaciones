# ADR-007/008 — Enmienda 1: recuperación del coordinador de sagas

**Estado:** **PROPUESTA** (2026-10-07). La decide Carlos. Debe estar **aprobada antes del merge** del PR de código de B1-bis (regla 3.5): cambia un mecanismo de recuperación existente.
**Enmienda a:** ADR-007 (coordinación de sagas vía outbox) y ADR-008 (compensación), que solo existen como entradas del catálogo (`documento-maestro-proyecto.md` §III). Sigue el patrón de ADR-042 (ventana máxima, cuarentena y salida manual) y de ADR-022 (resolución manual por JMX).
**Origen:** hallazgos H1 y H2 de `plan-b1bis-saga-division.md`; decisiones de Carlos del 2026-10-07 (Q1–Q4 de B1-bis).

---

## 1. Contexto

`OutboxSagaCoordinator` (`core/.../application/saga/OutboxSagaCoordinator.java`) hoy:

1. Ejecuta `execute` y, si falla, reintenta con *backoff* `2^n × 15 s` mientras no venza la ventana (`saga.quarantine.window`, **24 h** por defecto en el código; el documento maestro §7.2 dice 4 h).
2. Al vencer la ventana, llama **una vez** a `compensate` y marca el mensaje `QUARANTINED` **aunque `compensate` lance una excepción** (`:45-66`). Esa compensación no se reintenta nunca.
3. No distingue un fallo permanente de uno transitorio. Un fallo permanente de `execute` espera la ventana completa antes de compensar.
4. `QUARANTINED` significa a la vez "compensado con éxito" y "compensación fallida". No hay forma de saber cuáles necesitan a una persona, ni operación para sacarlos de ahí.

**Consecuencias:**
- **Saga de registro** (`ASSET_REGISTRATION_SAGA`, `AssetRegisteredSagaPolicy`): un fallo transitorio de `reverseAllocation` deja la asignación en `pendingAllocationAmount` para siempre, sin aviso.
- **Saga de división** (B1-bis): una compensación perdida deja la cantidad extraída sin hijo y sin devolver al padre. Y si el padre se entrega antes de resolverla, compensar es imposible (`DELIVERED` es terminal), pero **crear el hijo sigue siendo posible**, porque no depende del estado del padre: la cantidad ya se extrajo.

## 2. Decisión

### D1. Dos fases con ventana máxima cada una

| Fase | Empieza | Termina | Qué ejecuta el coordinador |
|---|---|---|---|
| **Ejecución** | `createdAt` | `createdAt + saga.quarantine.window` (**`PT4H`** por defecto, antes `PT24H`; alineado con ADR-010 y ADR-045) | `execute` |
| **Resolución** | al vencer la ejecución, o en cuanto `execute` falla de forma permanente | `inicio de la resolución + saga.resolution.window` (**`PT4H`** por defecto, como ADR-042 §5) | `compensate`, que pasa a significar **resolver**: compensar o, si la política lo permite, recuperar hacia delante (D4) |

El inicio de la resolución se guarda en el mensaje (`resolutionStartedAt`), para que la ventana no dependa de que el fallo permanente llegara antes o después.

### D2. Fallo permanente frente a transitorio

- **Permanente:** la política lanza `PermanentSagaFailureException`: el dominio hace imposible la operación (un invariante, un agregado terminal, un dato que no existe). Cualquier otra excepción es **transitoria**.
- **En ejecución:**
  - transitorio → `PENDING` con *backoff*, como hoy;
  - permanente → pasa **ya** a la fase de resolución, sin esperar a que venza la ventana (ADR-008: "fallo permanente dispara compensación").
- **En resolución:**
  - éxito → **`RESOLVED`** (estado nuevo);
  - transitorio → `PENDING` con *backoff* `2^n × 15 s` con **tope de 1 h**, hasta que venza la ventana de resolución; después, `QUARANTINED`;
  - permanente → `QUARANTINED` en el acto.
- Cada paso a `QUARANTINED` emite un log **ERROR** con `messageId`, `sagaType` y el motivo, nunca el payload completo, e incrementa un contador.

### D3. Estados del mensaje

| Estado | Significado | ¿Necesita a una persona? |
|---|---|---|
| `PENDING` | En ejecución o en resolución, con reintento programado | No |
| `COMPLETED` | `execute` tuvo éxito | No |
| **`RESOLVED`** (nuevo) | La resolución tuvo éxito: compensado o recuperado hacia delante | No |
| `QUARANTINED` | **Solo** la resolución que no pudo completarse (fallo permanente o ventana agotada) | **Sí** |

`QUARANTINED` deja de significar "compensado". No hay entornos con datos reales (Carlos, 2026-10-07), así que no hace falta migrar mensajes existentes.

### D4. Recuperar hacia delante antes de dejar algo sin resolver (decisión de Carlos, Q2)

Una política puede tener **más de una salida** en la resolución. La de la división (`ASSET_SPLIT_SAGA`) resuelve en este orden, siempre bajo la barrera atómica `SPLIT_RESOLUTION:{childAssetId}` de `plan-b1bis-saga-division.md` §2:

1. Si el reclamo ya existe, termina con éxito según su resultado.
2. **Compensar** (`ASSET_SPLIT_COMPENSATED` en el padre).
3. Si compensar es imposible porque el padre está `DELIVERED`, **crear el hijo** igualmente. La cantidad ya se extrajo y tiene que estar en algún stream.
4. Solo si crear el hijo **también** falla de forma permanente → `PermanentSagaFailureException` → `QUARANTINED`. La división queda **`UNRESOLVED`** y la resuelve una persona por la salida manual (D5).

La saga de registro no tiene salida hacia delante: su resolución es `reverseAllocation`, como hasta ahora.

### D5. Salida manual por JMX (ADR-022; regla 2.6: ninguna cuarentena sin salida)

MBean `SagaOutboxAdministration`, expuesto como los demás MBeans de operación del sistema (ADR-042, ADR-045):

| Operación o atributo | Efecto |
|---|---|
| `quarantinedCount` (atributo) | Número de mensajes `QUARANTINED`, por `sagaType` |
| `listQuarantined(sagaType, limit)` | `messageId`, `sagaType`, `correlationId`, `resolutionStartedAt`, último motivo |
| `retryResolution(messageId)` | Vuelve a `PENDING` en fase de resolución con una **ventana nueva** (`resolutionStartedAt = ahora`). Solo acepta mensajes `QUARANTINED` |
| `markResolvedManually(messageId, note)` | Pasa a `RESOLVED` cuando una persona resolvió fuera del sistema. Guarda la nota y emite un WARN. Solo acepta mensajes `QUARANTINED` |

Ninguna operación vuelve a `execute`: la fase de ejecución ya venció. Las operaciones son idempotentes sobre el estado (un mensaje que ya no está `QUARANTINED` da error explícito y no cambia nada).

### D6. Efecto sobre la saga de registro existente (`ASSET_REGISTRATION_SAGA`)

| Aspecto | Antes | Después |
|---|---|---|
| Plazo antes de compensar un `confirmAllocation` que falla | 24 h | **4 h** |
| `confirmAllocation` con fallo permanente | Esperaba las 24 h | Pasa a resolución en el acto |
| `reverseAllocation` con fallo transitorio | Perdido; mensaje `QUARANTINED` sin aviso | Reintentado hasta 4 h; después `QUARANTINED` con ERROR y salida manual |
| Mensaje compensado con éxito | `QUARANTINED` | `RESOLVED` |
| El activo físico ya registrado | Se queda (la compensación solo revierte la asignación) | Igual; no cambia |

`reverseAllocation` ya es idempotente por `commandId = messageId-comp` y por ADR-009, así que reintentarlo es seguro.

## 3. Alternativas

| Alternativa | Por qué no |
|---|---|
| Reintentar la compensación indefinidamente | Un fallo "transitorio" que nunca se resuelve se reintentaría para siempre, sin que nadie lo vea. La ventana máxima y la cuarentena con salida manual lo hacen visible (como ADR-042) |
| Dejar `UNRESOLVED` en cuanto compensar sea imposible | Deja en manos de una persona casos que el sistema puede resolver: crear el hijo no depende del estado del padre (D4) |
| **Impedir entregar el padre mientras tenga divisiones pendientes** | Prevendría el caso de raíz, pero el padre no sabe cuándo nace el hijo (ocurre en otro stream). Exigiría un evento de resolución en el padre y una regla nueva en `deliver`. **Descartada para este corte** (Carlos, 2026-10-07); queda registrada por si el caso `UNRESOLVED` resultara frecuente |
| Cambiar la firma de `SagaPolicy` | Innecesario: `compensate` conserva la firma y pasa a significar "resolver". La clasificación va en la excepción |

## 4. Consecuencias

- Una compensación transitoria fallida ya no se pierde.
- Un caso irresoluble queda visible (ERROR, contador, `listQuarantined`) y tiene salida.
- La saga de registro compensa antes (4 h) y nunca pierde su compensación en silencio.
- El documento de outbox gana `resolutionStartedAt`, `lastFailureReason` y `manualNote`, y el estado `RESOLVED`.
- **Fuera de alcance:** el bloqueo entre instancias del coordinador (`fetchPendingMessages` sin reclamo). La barrera de cada política garantiza la corrección; el bloqueo es otra mejora.

## 5. Verificación (en el PR de B1-bis)

- Coordinador: un fallo transitorio en resolución vuelve a `PENDING`; pasada la ventana de resolución → `QUARANTINED`; fallo permanente en ejecución → resolución inmediata; fallo permanente en resolución → `QUARANTINED`; éxito → `RESOLVED`.
- **Saga de registro con el valor por defecto (sin propiedad configurada):**
  - a las 3 h 59 min reintenta `confirmAllocation`;
  - a las 4 h 1 min llama a `reverseAllocation`;
  - un `reverseAllocation` que falla una vez se reintenta y termina `RESOLVED`.
- JMX: `retryResolution` y `markResolvedManually` solo sobre `QUARANTINED`; `quarantinedCount` y `listQuarantined` reflejan el estado.
- División: el orden de D4, incluido el padre `DELIVERED` → hijo creado, y la creación imposible → `QUARANTINED` y estado `UNRESOLVED`.
- Mutaciones: volver a marcar `QUARANTINED` tras una compensación fallida; quitar la ventana de resolución; tratar como transitorio un `PermanentSagaFailureException`.
