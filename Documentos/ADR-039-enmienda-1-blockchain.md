# ADR-039 — Enmienda 1 — Verificación de integridad recalculada y estado de salida de `COLLECTING`

**Estado:** BORRADOR (2026-10-07). Redactado por el agente; requiere aprobación humana explícita. **No autoriza escribir código** (regla 3.5 de `reglas-equipo-y-agentes.md`).
**Dirección aprobada por Carlos (2026-10-07):** poner tope y estado de salida a los batches `COLLECTING` que no se pueden recuperar. **Todo el diseño concreto de este documento es una propuesta**: `COLLECTING_FAILED`, `RETRY`/`RELEASE`, `RELEASED`, el recálculo de `eventHash` e `inconclusiveReason`.
**Origen:** `auditoria-fase6-codigo-vs-documentacion.md` §10 (hallazgos B-5, B-6, B-9 y B-10).
**Complementa:** ADR-039, `blockchain-resumen.md`. No reabre ADR-019/022 (anclaje EVM).

---

## 1. Contexto

### 1.1 Lo fusionado sin ADR (regularización)

`develop` contiene, desde `7a51ecb` y `793d4b8`, cambios al modelo de blockchain que no aparecen en ADR-039 ni en `blockchain-resumen.md`. Se fusionaron sin el ADR aprobado que exige la regla 3.5:

| Cambio | Dónde | Observación |
|---|---|---|
| `MerkleBatch.leafHashes` | `crypto/.../domain/MerkleBatch.java` | Se usa para atribuir un `MISMATCH` a secuencias concretas. **No** sustituye al recálculo: la raíz se recalcula desde `event_store` (`IntegrityVerificationUseCase.verify`) |
| `MerkleBatch.maxFeePerGasOverride` | ídem | Ajuste de gas por batch |
| `MerkleBatch.recoveryAttempts` y la recuperación automática de `COLLECTING` | `BlockchainAnchorProducer.recoverStaleCollectingBatches`, `crypto.anchor.collecting-recovery.*` | Sin tope ni estado de salida (§1.3) |
| Partición por presupuesto | `MongoUnanchoredEventAdapter.claimOrphansAndAssignBatch` | Se mantiene "un `streamId` como máximo una vez por batch". La contigüidad **entre** batches del mismo stream ya no viene de reclamar todos los huérfanos del stream: depende de `sequence ASC + limit(budget)` |

### 1.2 B-9 — la verificación no recalcula `eventHash`

`IntegrityVerificationUseCase.verify` (en `app` desde `7a51ecb`) lee el campo `eventHash` **persistido** de cada evento de la cobertura (`UnanchoredEventRepositoryPort.getEventHashesByCoverage`), recalcula la raíz de Merkle con esos valores y la compara con `merkleRoot`. Por tanto:

- Si se altera el `eventHash` guardado → se detecta (lo cubre `IntegrityVerificationUseCaseIntegrationTest`).
- Si se altera el **payload** sin tocar `eventHash` → **no se detecta**. No existe ningún otro verificador del `eventHash` ni de la cadena `previousHash`.

ADR-039 se contradice. La línea 52 dice que la verificación "recalcula con el mismo pipeline de producción (`EventCanonicalMapper`/`JcsHashAdapter`/`MerkleTree`)". La línea 65 descarta "recalcular `eventHash` desde el payload para construir las leaves". El código sigue la segunda.

### 1.3 B-10 — `COLLECTING` sin estado de salida

`findCollectingOlderThan` ordena por `createdAt ASC` con `limit = max-per-cycle` (10). Un batch cuya recuperación falla siempre se reintenta en cada ciclo, sin límite: con `warn-after-attempts` solo se registra un aviso en el log. Diez batches así bloquean la recuperación de todos los demás (bloqueo por cabeza de cola). Esto choca con la regla 2.6 (todo estado necesita salida).

**Riesgo latente en `develop`:** `produceBatch` llama a `recoverStaleCollectingBatches()` sin capturar excepciones antes de `claimAndBuildNewBatch()`. Si la lectura de un batch lanza (p. ej. `LegacyBatchCoverageUnavailableException` en `toDomain()`), se interrumpe también la producción de batches nuevos. Hoy no hay batches legacy en `COLLECTING`.

### 1.4 Historial de la forma canónica (verificado con `git log -S`)

- `actorRef` **entró** en `EventCanonicalMapper.toCanonicalMap` (`map.put("actorRef", actorRef)`, como `String`) hasta el commit `0579f41` (2026-09-16, Fase 5), que lo sacó del hash. En ese mismo commit `TraceabilityEventDocument.actorRef` pasó de `String` a `ActorRef`.
- `merkleBatchId` **nunca** entró en el mapper.
- `schemaVersion` es por clase de payload y no cambió en `0579f41`: no sirve para distinguir la forma canónica.
- No consta migración de documentos antiguos, y ADR-030 prohíbe el backfill de atribución. Por tanto, los eventos anteriores conservan en Mongo `actorRef` como `String`, pero el mapeo actual a `ActorRef` no los lee.
- `blockchain-resumen.md` §3 describe `merkleBatchId` como "mismo patrón que `actorRef` (ADR-030) — vive fuera de `EventCanonicalMapper`". Es cierto para el código actual, pero no para los eventos persistidos antes de `0579f41`.

## 2. Decisión propuesta

### 2.1 Regularización

Se incorporan a ADR-039 los cuatro cambios de §1.1 tal como están en `develop`, con las correcciones de §2.3.

### 2.2 Verificación recalculada (B-9)

1. **Resolver la contradicción.** Prevalece la línea 52 de ADR-039: la verificación recalcula cada `eventHash` desde el payload con `EventCanonicalMapper` (`core`) y `HashPort` (`contracts`), lo compara con el guardado y después recalcula la raíz. La línea 65 se limita al **productor**: construir las hojas con el `eventHash` persistido sigue siendo correcto allí.
2. **Cadena.** Además comprueba `previousHash[n] == eventHash[n-1]` dentro de la cobertura de cada stream, y entre el primer evento de la cobertura y el último evento anterior a ella, si existe. El primer evento de un stream es la secuencia 1 y su `previousHash` es `GENESIS` (enmienda D-SEQ, 2026-10-07). Así se detecta el reemplazo de un evento con recálculo en cascada de los siguientes.
3. **Forma canónica.**
   - Todo evento se valida primero con la forma actual.
   - Si un evento con `recordedAt` anterior a 2026-09-16 (corte de `0579f41`) no coincide, se prueba la **forma heredada** (con `actorRef` como `String`). `recordedAt` entra en el hash, así que es una señal confiable.
   - Coincidir con cualquiera de las dos formas válidas es `MATCH`. No debilita la detección: ambas formas atan el payload al hash.
   - Si la forma no puede determinarse → `INCONCLUSIVE` con motivo `CANONICAL_FORM_UNKNOWN`, nunca `MISMATCH`. Es un riesgo aceptado.
   - El tratamiento de los eventos posteriores al corte queda abierto (§6, P1–P2).
4. **Contrato de `VerificationResult`.** Se reutiliza `INCONCLUSIVE` (sin valor nuevo en `VerificationStatus`) y se añade el componente `inconclusiveReason` (enum nombrado: `NOT_ANCHORED`, `CANONICAL_FORM_UNKNOWN`, …). **Es un cambio de contrato bajo la regla 3.5.** Consumidores fuera de tests: `IntegrityVerificationUseCase` (`app`) e `IntegrityVerificationPort` (`crypto`); ninguno expuesto por HTTP.
5. **Ubicación.** `IntegrityVerificationUseCase` ya vive en `app` y no se mueve. `app` ya depende de `core` y de `contracts`, así que no aparece ninguna dependencia `crypto → core`.

### 2.3 Estado de salida de `COLLECTING` (B-10)

1. **Revierte explícitamente** la decisión de `blockchain-resumen.md` §3: "un batch `COLLECTING` es retomable desde Fase 2 — no requiere estado de fallo ni liberación de eventos ya reclamados". El motivo es el bloqueo por cabeza de cola de §1.3: "siempre retomable" no se cumple cuando el fallo es determinista.
2. Al superar un tope de `recoveryAttempts` (configurable), el batch pasa a **`COLLECTING_FAILED`** (terminal hasta intervención manual) y deja de ocupar hueco en la recuperación.
3. Un fallo al leer o recuperar un batch **no** impide `claimAndBuildNewBatch()` en el mismo ciclo.
4. **Salida manual por JMX** (mismo patrón que `resolveStuckBatch` de ADR-022, nunca automática):
   - `RETRY`: `COLLECTING_FAILED → COLLECTING`, con `recoveryAttempts = 0`.
   - `RELEASE`: `COLLECTING_FAILED → RELEASED`, un estado terminal propio. No reutiliza `FAILED`, que significa fallo **después** de enviar la transacción en el ciclo de anclaje.
     - Filtro estricto: solo actúa sobre batches en `COLLECTING_FAILED`, y solo pone `merkleBatchId = null` en eventos con `merkleBatchId == batchId` **dentro de su `coverage`**. Nunca toca eventos reclamados por otro batch.
     - En la misma transacción escribe un registro de auditoría con `batchId`, `coverage` liberada, `eventIds`, operador y motivo. Es necesario porque poner `merkleBatchId = null` borra la evidencia de que esos eventos estuvieron en ese batch.
     - El batch `RELEASED` conserva su `coverage` como registro. Los eventos liberados vuelven a ser reclamables.

## 3. Alternativas

- **Recalcular el hash con una sola forma canónica (la actual):** descartada. Daría `MISMATCH` falso en todo evento anterior a `0579f41`.
- **Campo `canonicalFormVersion` con backfill:** descartada. ADR-030 prohíbe la rectificación o el backfill de eventos persistidos.
- **Ordenar la recuperación por `recoveryAttempts ASC` o por último intento, sin estado terminal:** elimina el bloqueo por cabeza de cola, pero un batch roto se sigue reintentando para siempre. Queda registrada como alternativa considerada.
- **Reutilizar `FAILED` para el batch liberado:** descartada. Mezclaría dos situaciones distintas para quien opera o lee el estado.
- **Añadir un valor nuevo a `VerificationStatus`:** descartada por ahora. Ampliar el enum rompe los `switch` de los consumidores, mientras que un motivo dentro de `INCONCLUSIVE` es aditivo.

## 4. Consecuencias

- La verificación detecta alteraciones del payload y de la cadena, no solo del `eventHash` guardado.
- Dos cambios de contrato que declarar (regla 3.5): el componente `inconclusiveReason` en `VerificationResult`, y el puerto de lectura que exponga el payload y el `actorRef` original (§6, P3).
- Dos estados nuevos en `AnchorStatus` (`COLLECTING_FAILED`, `RELEASED`). Hay que auditar de nuevo a sus consumidores (`BlockchainAnchorScheduler`, `AnchorConfirmationPoller`, `BlockchainAdminOperationsService`), como se hizo con `COLLECTING`.
- La verificación es más costosa: recanonicaliza cada evento de la cobertura.

## 5. Definition of Done (tests que deben existir antes de cerrar la implementación)

1. Payload alterado sin tocar `eventHash` → `MISMATCH`, con la secuencia atribuida.
2. Evento reemplazado con recálculo en cascada de `eventHash`/`previousHash` → `MISMATCH`.
3. Fixtures construidos con el mapper de `0579f41^` (`recordedAt` anterior al corte, `actorRef` como `String`) → `MATCH`.
4. Evento cuya forma canónica no puede determinarse → `INCONCLUSIVE`/`CANONICAL_FORM_UNKNOWN`, nunca `MISMATCH`.
5. Evento posterior al corte hasheado con la forma heredada → el `status` y el motivo que fije P1–P2 (§6).
6. Batch que agota el tope → `COLLECTING_FAILED`, y deja de ocupar hueco en la recuperación.
7. `RELEASE` → `RELEASED`; los eventos de su cobertura quedan reclamables; se escribe la auditoría; los eventos de otro batch no se tocan.
8. `RETRY` → `COLLECTING` con `recoveryAttempts = 0`, y se recupera.
9. Batch ilegible → `claimAndBuildNewBatch()` se ejecuta igual en el mismo ciclo.
10. Evento añadido al stream entre dos reclamaciones → los batches sucesivos quedan contiguos y sin huecos.

## 6. Preguntas abiertas (deben resolverse antes de aprobar)

- **P1 — La fecha de corte no es la de cada entorno.** El corte usa la fecha del commit `0579f41`, pero cada entorno empezó a hashear sin `actorRef` cuando **desplegó** ese código. Un entorno que siguió con el código anterior después del 2026-09-16 tendrá eventos con `recordedAt` posterior al corte hasheados con la forma heredada, y con §2.2.3 darían `MISMATCH` falso. Opciones:
  - (a) corte por entorno, con la fecha de despliegue;
  - (b) si un evento posterior al corte falla con la forma actual, probar también la heredada y, si coincide, devolver `INCONCLUSIVE` con un motivo propio (`CANONICAL_FORM_ANOMALY`), nunca `MISMATCH`.

  (b) es más simple y resuelve también P2.
- **P2 — Qué devuelve una anomalía de forma.** `VerificationResult` debe poder expresar "el evento se hasheó con una forma que no corresponde a su fecha" para que el test 5 de §5 sea escribible. Propuesta: `INCONCLUSIVE` + `CANONICAL_FORM_ANOMALY`. **No** `MATCH`, porque el evento no se hasheó como el sistema esperaba para su fecha.
- **P3 — Leer la forma heredada cambia contratos.**
  - Hoy `getEventHashesByCoverage` (`UnanchoredEventRepositoryPort`, `core`) solo devuelve `eventHash`. La verificación necesita el payload, `previousHash`, `recordedAt` y, para la forma heredada, el `actorRef` original como `String`, leído en BSON crudo. Ampliar o añadir ese puerto de lectura es un cambio de contrato (regla 3.5) y se declara junto al de `VerificationResult`.
  - La forma heredada vive en un **componente aparte de solo lectura** (p. ej. `LegacyCanonicalForm`). **No** se amplía la firma de `EventCanonicalMapper.toCanonicalMap`: `estado-fase6.md` §3.1 registra que la seguridad del `eventHash` actual depende de que esa firma no se amplíe.
- **P4 — Verificación previa a implementar.** Comprobar si existe algún evento con `recordedAt` anterior al corte (o con `actorRef` persistido como `String`) en algún entorno real. Si no existe ninguno, §2.2.3 se reduce a la forma actual y los tests 3–5 de §5 quedan como protección.
- **P5 — Valor del tope** de `recoveryAttempts` y su relación con `warn-after-attempts`.
