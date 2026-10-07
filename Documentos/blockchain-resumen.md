# Blockchain — Resumen de diseño conceptual (no congelado)

**Estado:** Diseño pre-ADR para las piezas nuevas (Productor MerkleBatch, `IntegrityVerificationPort`); referencia consolidada para las piezas ya existentes (Anchoring EVM). No incluye configuración de despliegue real.
**Para qué sirve:** punto de continuidad para retomar la capa Blockchain de Fase 6 sin reconstruir el hilo completo de la conversación.

---

## 1. Lo que ya existía antes de esta conversación (cerrado, no se toca)

**Anchoring EVM (ADR-019, ADR-022)** — implementado, con 39 tests, integración end-to-end real contra Testcontainers/Ganache:

```text
PENDING → SUBMITTING → SUBMITTED → {
    ANCHORED
  | ANCHOR_MISMATCH
  | STUCK → { RESUBMIT → SUBMITTED | ABANDON → FAILED }
  | FAILED
}
```

- `nonceUsed`: reservado atómicamente en Mongo (contador propio), misma transacción que reclama el batch.
- `SUBMITTING`: estado de atomicidad reserva→envío→persistencia.
- `ANCHORED`: exige `status==1` + N confirmaciones + coincidencia exacta de bytes (`Numeric.hexStringToByteArray`, nunca `.getBytes()` — evita falsos `ANCHOR_MISMATCH`).
- `STUCK`: `SUBMITTING` sin `txHash` tras timeout. Resolución **exclusivamente manual** vía JMX (`BlockchainAdminOperationsService.resolveStuckBatch(ABANDON|RESUBMIT)`) — nunca automática, para evitar doble gasto accidental y mantener control humano sobre costos de gas.
- Componentes: `BlockchainAnchorScheduler` (guardia de prioridad para `SUBMITTING` sin `txHash`), `AnchorConfirmationPoller`.
- **`MerkleBatch` real (14 campos)**: `batchId, sequenceRangeStart, sequenceRangeEnd, merkleRoot, createdAt, status, network, smartContractAddress, nonceUsed, transactionHash, submittedAt, anchoredAt, confirmedBlockNumber, resolution, maxFeePerGasOverride`. **No tenía** `streamId`, `algorithmVersion`, ni lista de leaves — ver §3.
- Blockchain = *integrity anchor*, nunca Event Store. PII permanece off-chain, sin cambios.

**Corrección de proceso importante, para no repetirla**: `documento-maestro-proyecto.md` contiene una frase de prosa desactualizada (*"el anclaje... es la Tarea 12, aún no implementada"*) que contradice la propia tabla de tareas del mismo documento (Tarea 13, ✅ Completada). Cuando una fuente cite estado de implementación, verificar contra la tabla de tareas y, si es posible, contra el código — no contra la prosa descriptiva aislada.

## 2. Hueco real encontrado — el productor de `MerkleBatch` nunca se construyó

Verificado por inspección directa del código (`crypto/src/main/java/com/traceability/crypto/application/service/` solo contiene `BlockchainAnchorScheduler`, `AnchorConfirmationPoller`, `BlockchainAdminOperationsService` — ninguno construye batches desde el Event Store). Coincide con la propuesta que Roberto planteó en la reunión del 18 sept ("productor de lotes Merkle para agrupar eventos huérfanos"), que había quedado como idea suelta sin construir.

**Consecuencia sobre la cobertura de tests ya aceptada**: los 39 tests prueban el ciclo `PENDING→ANCHORED` partiendo de un `MerkleBatch` ya construido (fixture) — no prueban `event_store→PENDING`, porque ese código no existe.

## 3. Diseño nuevo — Productor de `MerkleBatch` (Fase 6)

**Cambio de modelo**: `sequenceRangeStart/End` (escalar) se sustituye por cobertura multi-stream:

```text
MerkleBatch.coverage: Map<streamId, Range{fromSequence, toSequence}>
```

- **Invariante**: un `streamId` aparece como máximo una vez por batch — es consecuencia estructural, no una regla arbitraria: la reclamación toma siempre *todos* los huérfanos de un stream a la vez, y el Event Store nunca tiene gaps de secuencia (`SequenceGapException` ya lo impide) — la discontinuidad dentro de un stream es estructuralmente imposible bajo este mecanismo.
- **Orden canónico de leaves**: `streamId ASC → sequence ASC` — enumeración reproducible del conjunto, no un orden temporal de negocio (eso ya lo captura `occurredAt`/`recordedAt` de cada evento).
- **Nuevos estados de `MerkleBatch`**: `COLLECTING` (eventos reclamados, root aún no calculado) antes de `PENDING`. No se reutiliza la semántica de `STUCK` (esa representa incertidumbre externa; `COLLECTING` es cómputo local determinista, siempre recuperable, sin estado de fallo adicional).

**Protocolo de tres fases** (evita transacciones Mongo largas mientras se calcula el árbol):

```text
Fase 1 (transacción Mongo corta):
  seleccionar streams elegibles (paginación por cursor, streamId ASC, round-robin
    para evitar starvation — no prioriza por volumen ni por antigüedad)
  seleccionar eventos huérfanos (merkleBatchId == null) hasta maxEventsPerBatch
  crear MerkleBatch(COLLECTING) con coverage[] ya fijado
  asignar merkleBatchId a los eventos seleccionados
  COMMIT

Fase 2 (fuera de transacción):
  leer eventHash de la cobertura ya persistida (nunca volver a consultar "huérfanos")
  ordenar streamId ASC → sequence ASC
  construir MerkleTree → merkleRoot

Fase 3 (actualización condicional, un solo documento):
  UPDATE MerkleBatch WHERE batchId=X AND status=COLLECTING
  SET merkleRoot=R, status=PENDING
```

- `merkleBatchId` (nuevo campo en `TraceabilityEventDocument`): metadata de ciclo de vida, mismo patrón que `actorRef` (ADR-030) — vive fuera de `EventCanonicalMapper`, no afecta `eventHash` ni la cadena `previousHash`. El documento del evento deja de ser inmutable a nivel físico completo, pero su contenido criptográfico permanece inmutable.
- **Recuperación**: un batch `COLLECTING` es retomable desde Fase 2 — no requiere estado de fallo ni liberación de eventos ya reclamados. La cobertura persistida en Fase 1 es la frontera inmutable del batch; eventos nuevos que lleguen después nunca contaminan un batch ya en cálculo.
- El cursor de paginación es estado del *productor/scheduler*, no del dominio — no participa en la identidad ni integridad criptográfica de ningún batch.
- El productor **no realiza anchoring**. `PENDING` es consumido exclusivamente por el mecanismo ya existente de ADR-019.

**Pendiente, explícitamente de implementación (no arquitectura)**: valor concreto de `maxEventsPerBatch` y de streams-por-ciclo — la *semántica* (corta sin romper contigüidad; ronda round-robin) ya está cerrada, el número es tuning.

**No incorporado**: `algorithmVersion` — se evaluó y se descartó modificar retroactivamente una entidad ya implementada sin caso de uso concreto. Riesgo aceptado y registrado: si la regla de duplicación de hoja impar cambia en el futuro, los batches anteriores a ese cambio no serán reproducibles con precisión byte-exacta por un verificador futuro.

## 4. Diseño nuevo — `IntegrityVerificationPort` (adelanto desde Fase 7)

**Propósito preciso, para no confundirlo con lo que ya hace `AnchorConfirmationPoller`:**

```text
AnchorConfirmationPoller  → verifica el anclaje EN EL MOMENTO de la confirmación
IntegrityVerificationPort → verifica RETROSPECTIVAMENTE que el Event Store de HOY
                              sigue produciendo la raíz histórica ya anclada
```

```text
IntegrityVerificationPort
  verifyBatch(batchId): VerificationResult          — caso de uso individual
  verifyAllAnchored(): Stream<VerificationResult>   — orquestación administrativa/
                                                        background, mismo método,
                                                        no una segunda semántica

VerificationResult
  status: MATCH | MISMATCH | INCONCLUSIVE
  recomputedRoot
  expectedRoot        (MerkleBatch.merkleRoot)
  affectedSequences: List<(streamId, sequence)>   — solo poblado cuando la
                                                      discrepancia es atribuible
                                                      a edición in-place de un evento
  diagnosisComplete: boolean                        — false cuando el MISMATCH no
                                                      pudo atribuirse a secuencias
                                                      concretas (p.ej. adición/
                                                      eliminación de eventos dentro
                                                      del rango cubierto)
```

- **Lógica**: `status != ANCHORED` → `INCONCLUSIVE` (cubre `PENDING/COLLECTING/SUBMITTING/SUBMITTED/STUCK/ANCHOR_MISMATCH/FAILED` — ninguno constituye anclaje confirmado). Si `ANCHORED`: usar `coverage[]` para reconstruir exactamente qué consultar en `event_store` (sin ambigüedad, gracias al modelo de §3), recalcular con el mismo pipeline de producción (`EventCanonicalMapper`/`JcsHashAdapter`/`MerkleTree` — nunca una reimplementación paralela), comparar contra `merkleRoot`.
- **Precisión importante sobre `diagnosisComplete=false`**: no significa "no sabemos si hubo manipulación" — significa que sabemos que la raíz diverge, pero la evidencia disponible no permite atribuir la divergencia a una secuencia concreta. No confundir limitación diagnóstica con conclusión sobre la causa.
- **Sin RPC en el caso principal**: la comparación es 100% local (Event Store vs. `merkleRoot` ya almacenado) — no hace falta volver a consultar la blockchain, porque la pregunta que responde es distinta de la que ya resolvió el Poller.
- Es de solo lectura — nunca modifica `event_store` ni `MerkleBatch`, nunca repara ni corrige.
- Explícitamente fuera de este cierre: cualquier Watchdog/alertamiento automático que convierta un `MISMATCH` en alerta, cuarentena o incidente — capacidad futura, consumidora de este puerto, no parte de él.

## 5. Fuera del perímetro de esta capa (deliberadamente, no por descuido)

- Conexión a red EVM real (cuenta Solidity, RPC provider, contrato desplegado) — trabajo de infraestructura/despliegue, no decisión de arquitectura.
- `algorithmVersion` — ver §3.
- RPC en el verificador — ver §4.
- Watchdog/alertamiento automático.
- Modificación de ADR-019/ADR-022 — no hay evidencia que lo justifique; se usan tal cual.

## 6. Hallazgos de auditoría independientes, sin relación con Blockchain — pendientes aparte

Encontrados durante la verificación de código de esta capa, no resueltos aquí:

- **[Media]** `PhysicalAsset.apply()` para `AssetDispatchedPayload` muta `currentLocation` y `custodianRef` en el mismo evento — posible divergencia con ADR-003 (*"ningún evento de custodia sustituye a un evento de movimiento físico, y viceversa"*). Requiere decidir si el ADR o el código debe corregirse, con más contexto del que se tiene aquí.
- **[Baja]** `ProjectionRetryDocument` usa el literal `"PENDING"` donde ADR-010 documenta `RETRY_PENDING` — inconsistencia de nomenclatura, probablemente inofensiva.

## 7. Nota de procedencia

Anchoring EVM (ADR-019/022) y `MerkleBatch` (forma original de 14 campos) ya existían, implementados y probados, antes de esta conversación. Productor de `MerkleBatch` (cobertura multi-stream, `COLLECTING`, protocolo de 3 fases) e `IntegrityVerificationPort` son diseño nuevo de esta sesión — ninguno de los dos tiene código todavía.
