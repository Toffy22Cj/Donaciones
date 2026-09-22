# ADR-035 (número tentativo — confirmar contra el catálogo real antes de commitear) — Productor de MerkleBatch e IntegrityVerificationPort

**Estado:** Aprobado — modelo arquitectónico cerrado en lo que la documentación permite cerrar. No es "listo para implementar sin más": pendiente de un conjunto explícito de decisiones menores (§7-A) y verificaciones técnicas (§7-B).
**Fecha:** Sesión de Fase 6, review formal de 12 puntos (Modo de Arquitectura), posterior a ADR-033 (Convocatoria) y ADR-034 (Identidad).
**Complementa:** `blockchain-resumen.md`, `technical_documentation.md` §8.2-8.4. No reabre ADR-019/ADR-022 (Anchoring EVM, implementado y probado con 39 tests).

---

## 1. Contexto

`blockchain-resumen.md` documenta un hueco real, verificado por inspección de código: `crypto/.../application/service/` solo contiene `BlockchainAnchorScheduler`, `AnchorConfirmationPoller`, `BlockchainAdminOperationsService` — ninguno construye un `MerkleBatch` a partir del Event Store. Los 39 tests existentes prueban el ciclo `PENDING→ANCHORED` partiendo de un `MerkleBatch` ya fabricado (fixture); no prueban `event_store→PENDING`, porque ese código no existe. Este ADR formaliza el diseño de las dos piezas que cierran ese hueco: el **Productor de `MerkleBatch`** y el **`IntegrityVerificationPort`** (verificación retrospectiva, adelantada desde Fase 7).

## 2. Decisión

### 2.1 Productor de `MerkleBatch`

**Responsabilidad**: agrupar eventos huérfanos (`merkleBatchId==null`) del Event Store en `MerkleBatch`, calcular su `merkleRoot`, y entregarlo en `PENDING` para que el mecanismo ya existente (ADR-019) lo consuma. **No realiza anchoring.**

**Cambio de modelo**: `MerkleBatch.coverage: Map<streamId, Range{fromSequence,toSequence}>` sustituye los campos escalares `sequenceRangeStart/End` del modelo original (14 campos). Invariantes:
- Un `streamId` aparece como máximo una vez por batch — consecuencia estructural de que la reclamación toma siempre todos los huérfanos de un stream a la vez, y el Event Store nunca tiene gaps (`SequenceGapException` ya lo impide).
- Orden canónico de leaves: `streamId ASC → sequence ASC` — enumeración reproducible del conjunto, no orden temporal de negocio.
- Nuevo estado `COLLECTING` (eventos reclamados, root aún no calculado), previo a `PENDING`. No reutiliza la semántica de `STUCK` — `COLLECTING` es cómputo local determinista, siempre recuperable, sin estado de fallo adicional. No existe `COLLECTING→FAILED` ni `COLLECTING→STUCK`.

**Protocolo de tres fases** (evita transacciones Mongo largas durante el cálculo del árbol):
```
Fase 1 (transacción Mongo corta): seleccionar streams (cursor, streamId ASC, round-robin)
  → seleccionar huérfanos hasta maxEventsPerBatch → crear MerkleBatch(COLLECTING)
  con coverage[] fijado → asignar merkleBatchId a los eventos seleccionados → COMMIT
Fase 2 (fuera de transacción): leer eventHash de la cobertura persistida (nunca volver
  a consultar "huérfanos") → ordenar streamId ASC → sequence ASC → MerkleTree → merkleRoot
Fase 3 (actualización condicional, un solo documento):
  UPDATE MerkleBatch WHERE batchId=X AND status=COLLECTING SET merkleRoot=R, status=PENDING
```

**Recuperación**: un `COLLECTING` es retomable desde Fase 2 sin liberar eventos ya reclamados — la cobertura persistida en Fase 1 es la frontera inmutable del batch; eventos nuevos que lleguen después nunca contaminan un batch en cálculo (Fase 2 no vuelve a consultar huérfanos).

**`merkleBatchId`** (nuevo campo en `TraceabilityEventDocument`): metadata de ciclo de vida, mismo patrón que `actorRef` (ADR-030) — vive fuera de `EventCanonicalMapper`, no afecta `eventHash` ni `previousHash`. `event.merkleBatchId` y `MerkleBatch.batchId` son la **misma identidad lógica** (confirmado por lectura textual directa del protocolo: se crea el `MerkleBatch` y, en la misma operación, se asigna su identidad a los eventos seleccionados — no hay paso intermedio que genere un tercer identificador), simplemente con nombre de campo distinto por convención de referencia entre colecciones.

### 2.2 `IntegrityVerificationPort`

Verificación **retrospectiva** — distinta de `AnchorConfirmationPoller` (que verifica el anclaje en el momento de la confirmación): comprueba que el Event Store de hoy sigue produciendo la raíz histórica ya anclada.

```
verifyBatch(batchId): VerificationResult
verifyAllAnchored(): Stream<VerificationResult>

VerificationResult { status: MATCH|MISMATCH|INCONCLUSIVE, recomputedRoot, expectedRoot,
                      affectedSequences: List<(streamId,sequence)>, diagnosisComplete: boolean }
```

- `status != ANCHORED` (cubre `PENDING/COLLECTING/SUBMITTING/SUBMITTED/STUCK/ANCHOR_MISMATCH/FAILED`) → `INCONCLUSIVE`.
- `ANCHORED`: usa `coverage[]` para reconstruir exactamente qué consultar, recalcula con el mismo pipeline de producción (`EventCanonicalMapper`/`JcsHashAdapter`/`MerkleTree`, nunca una reimplementación paralela), compara contra `merkleRoot`.
- `diagnosisComplete=false` **no significa "no sabemos si hubo manipulación"** — significa que la raíz diverge pero la evidencia disponible no permite atribuirla a secuencias concretas.
- Sin RPC en el caso principal (comparación 100% local). Solo lectura — nunca modifica `event_store` ni `MerkleBatch`, nunca repara.
- **Dependencia de orden de entrega**: este puerto necesita que el productor ya haya fijado `coverage[]` en los batches — no puede verificarse útilmente contra ningún batch anclado antes de que el productor exista y produzca con el modelo nuevo (ver §7-A, compatibilidad histórica).

## 3. Consecuencias

- Positivas: cierra el hueco de `event_store→PENDING` que los 39 tests existentes no cubren; hace explícito que `algorithmVersion` fue deliberadamente descartado (riesgo aceptado: si la regla de duplicación de hoja impar cambia, batches anteriores a ese cambio no serán reproducibles byte-exactamente); confirma por evidencia de código que `EventStorePort` tal como existe no soporta ninguna de las tres operaciones que el productor necesita.
- Negativas / deuda aceptada: el documento físico deja de ser inmutable a nivel de todos sus campos (aunque su contenido criptográfico permanece inmutable) — mismo patrón ya aceptado para `actorRef`; sin plan de migración para `MerkleBatch` históricos con el esquema de 14 campos escalares.

## 4. Alternativas descartadas

- **Reutilizar `EventStorePort` sin modificación**: descartada por evidencia de código, no por prudencia — la interfaz real (`append`, `loadStream(streamId)`) no soporta búsqueda transversal de huérfanos, escritura condicional sobre documentos existentes, ni lectura por rango de cobertura.
- **Recalcular `eventHash` desde el payload para construir las leaves**: descartada — las leaves son los `eventHash` ya persistidos; recanonicalizar introduciría una segunda reconstrucción criptográfica innecesaria y contaminaría la frontera `EventCanonicalMapper → eventHash → Merkle Producer`.
- **`COLLECTING` como variante de `STUCK`**: descartada — representan incertidumbres de naturaleza distinta (cómputo local determinista y recuperable vs. incertidumbre de un proceso externo).
- **Lock explícito de stream para Fase 1**: no descartada por evidencia en contra, pero no recomendada como primera opción — añade estado y persistencia adicional (expiración, recuperación de locks abandonados) que el diseño actual no contempla; se prefiere competencia transaccional (ver §7-A, B1).
- **Índice `{merkleBatchId:1}` como requisito automático**: descartado como obligación — es candidato de rendimiento a verificar contra el patrón real de consulta y volumen, no una decisión arquitectónica.
- **`MISMATCH` tratado como excepción**: descartado — es un resultado de dominio válido y esperado dentro del enum `VerificationResult.status`, no un fallo del puerto.

## 5. Autorización

Ninguno de los dos componentes expone endpoint HTTP en el alcance actual — confirmado por cita directa de `api-contract-matrix.md` §6: "procesos internos (scheduler-driven) o de invocación administrativa futura fuera de este alcance". No hay `RoleAuthorizationPolicy`/`OrganizationBoundaryPolicy` que aplicar. Riesgo a vigilar (no bloqueante hoy): `VerificationResult.affectedSequences` revela estructura interna del Event Store — si en el futuro se expone como endpoint HTTP, debe pasar por un nuevo ciclo de este mismo proceso de review, no una implementación silenciosa.

## 6. Observabilidad

Cadena completa sin identificadores nuevos: `eventId → streamId+sequence → eventHash → merkleBatchId(=batchId) → coverage[] → merkleRoot → transactionHash → estado EVM`. `merkleBatchId` es metadata de ciclo de vida, **no** un `correlationId` — no debe confundirse ni sustituirse por él. Origen del `correlationId` para ejecuciones de scheduler/background: no especificado (§7-A, B9).

## 7. Explícitamente NO resuelto por este ADR

### 7-A. Decisiones y contratos abiertos

| # | Pendiente | Bloquea implementación de |
|---|---|---|
| B1 | Estrategia exacta de claim de Fase 1 (propuesta: escritura condicional a nivel de documento sobre el evento, `merkleBatchId:null` como filtro de la propia escritura; genera conflicto transaccional detectable en solapamiento real) — no verificada contra código | Concurrencia segura del productor |
| B2 | Partición cuando un stream supera `maxEventsPerBatch` sin romper contigüidad (propuesta plausible: `batch-1→[1..1000], batch-2→[1001..2000]`, un solo `Range` por stream por batch) | Streams con volumen alto de huérfanos |
| B3 | Resultado de `matchedCount==0` en Fase 3 (no-op silencioso propuesto por analogía con Convocatoria, no confirmado) | Contrato de aplicación del productor |
| B4 | Mecanismo operativo que descubre y reintenta `COLLECTING` abandonados (la recuperabilidad está cerrada; el disparador no) | Resiliencia operacional |
| B5 | Compatibilidad/migración de `MerkleBatch` históricos (14 campos escalares) frente al nuevo modelo `coverage[]` — sin plan documentado | `IntegrityVerificationPort` contra batches anteriores a este ADR |
| B6 | Paginación/límite de `verifyAllAnchored(): Stream<VerificationResult>` — sin criterio de corte especificado | Uso administrativo/background seguro a escala |
| B7 | `verifyBatch(batchId)` con id inexistente — sin excepción nombrada | Manejo de errores del verificador |
| B8 | `event_store` sin los datos esperados por `coverage[]` de un batch `ANCHORED` (posible corrupción) — severidad no definida (¿`INCONCLUSIVE` o condición más severa?) | Semántica de integridad del verificador |
| B9 | Origen del `correlationId` para ejecuciones de scheduler/background | Observabilidad completa de extremo a extremo |
| B10 | Extender `EventStorePort` vs. crear puerto especializado — Alternativa A (reutilizar sin cambios) ya descartada por evidencia; inclinación razonada hacia puerto especializado (evita mezclar responsabilidades con los consumidores de negocio actuales de `EventStorePort`, `FundCommandService`/`PhysicalAssetCommandService`), sin congelar | Forma física del contrato de lectura/claim |

### 7-B. Verificación técnica pendiente (no decisiones nuevas, demostrar que la implementación respeta el diseño)

Fase 1 atómica (batch+claim en una transacción real) · claim concurrente evita doble pertenencia · escritura de `merkleBatchId` segura frente a carrera · sin reclamación parcial tras rollback · `COLLECTING` retomable produce el mismo root · `MerkleTree` produce el orden canónico exigido (riesgo conocido: la implementación actual documentada usa "orden estrictamente de inserción" — verificado contra `technical_documentation.md` §8.3, cita textual — y debe adaptarse o verificarse compatible con `streamId ASC → sequence ASC`) · cálculo usa `eventHash`, nunca `merkleBatchId` · `IntegrityVerificationPort` es realmente read-only · fronteras ArchUnit (`crypto` no depende de `app`, no accede a `MongoTemplate`/`MongoRepository` directamente) · consulta de huérfanos con comportamiento aceptable a escala · transiciones existentes de anchoring no alteradas por el productor.

### Riesgos explícitamente retirados durante este review (no incluir en catálogos futuros)

Necesidad de endpoint HTTP para productor/verificador · necesidad de un identificador de tracing nuevo · `merkleBatchId` contaminando `eventHash` · `COLLECTING` como variante de `STUCK` · fallo de Fase 2 como transición de estado nueva · reutilización sin cambios de `EventStorePort` (ya resuelto como descartada, no como abierta) · obligación automática de índice `{merkleBatchId:1}` · `MISMATCH` como excepción · `batchId`/`merkleBatchId` como identidades físicamente distintas (confirmado: son la misma).

Ningún punto de §7 bloquea continuar con la capa de IA de Fase 6; los marcados en 7-A deben resolverse antes de asignar esa pieza específica a un agente de código, y los de 7-B antes de dar por buena la implementación del productor/verificador.

## 8. Trazabilidad de verificación

Se inspeccionó código real de `EventStorePort`/`MongoEventStoreAdapter` (ya revisado en la sesión de Convocatoria, reutilizado aquí para confirmar por evidencia que el contrato actual no cubre las operaciones del productor) y se verificó textualmente contra `technical_documentation.md` §8.3 la afirmación sobre el orden de inserción del `MerkleTree` existente — no es una suposición, es cita directa. Ningún componente nuevo de esta capa (productor, `IntegrityVerificationPort`) tiene código propio inspeccionado en esta sesión — ambos son diseño puro sobre `blockchain-resumen.md`.
