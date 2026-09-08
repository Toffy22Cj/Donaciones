# Documento Maestro — Motor de Trazabilidad Verificable de Donaciones

**Nombre comercial provisional (no usado en código):** el proyecto se ha referido a sí mismo informalmente como "PaxFide" en la conversación de diseño, pero esto es explícitamente **no vinculante** — puede cambiar sin afectar nada del dominio, la arquitectura ni el código.
**Base package Java:** `com.traceability`
**Fase actual:** Fase 3 — Exposición REST de Lectura **completa** (Fase 1 y Fase 2 formalmente cerradas, incluyendo auditoría exhaustiva de Fase 2 con 13 hallazgos corregidos). Ver `estado-fase3.md` para el detalle completo de Fase 3.

---

## 1. Visión y Objetivo del Proyecto

Sistema de trazabilidad verificable para donaciones de organizaciones sociales. Registra y demuestra el ciclo de vida completo de una donación:

```
Ingreso financiero → Transmutación/adquisición de recursos → Movimiento logístico
→ Recepción → Entrega final
```

**Principio rector:** el Event Store es la única fuente de verdad. Los eventos son hechos históricos inmutables. El sistema NO se modela como CRUD tradicional (donde el estado actual sobrescribe el anterior); el estado se reconstruye desde eventos o se mantiene en proyecciones derivadas y reconstruibles.

**Caso de uso demostrativo:** una fundación recibe una donación digital, la convierte en recursos físicos (kits, insumos), los rastrea físicamente hasta el beneficiario final, y sella criptográficamente cada paso para que nadie —ni siquiera la propia organización— pueda alterar la historia sin que se detecte.

---

## 2. Arquitectura General

### 2.1 Estilo arquitectónico

- **Monolito Modular** (no microservicios). Un solo repositorio, un solo despliegue, pero con fronteras de módulo estrictas que permiten extraer servicios en el futuro sin reescribir todo.
- **Event Sourcing** como patrón de persistencia del dominio.
- **Domain-Driven Design pragmático**: Aggregate Boundaries decididos por invariantes y consistencia transaccional, nunca por conveniencia de nombres o relaciones "naturales" del mundo real.
- **Arquitectura Hexagonal / Ports & Adapters**: el dominio (`core.domain`) es Java puro, sin conocer Spring, MongoDB ni ningún framework. La comunicación con el exterior pasa por puertos (interfaces) definidos en `contracts` o en `core.application.port.out`.
- **CQRS**: separación estricta entre el modelo de escritura (Aggregates + Event Store) y el modelo de lectura (Proyecciones desnormalizadas, reconstruibles).

### 2.2 Regla de oro del proyecto

> Correctness > Convenience. Explicitness > Magic. Domain Integrity > Framework Convenience. Long-term Maintainability > Short-term Speed. Pero evitando también el overengineering — la solución debe ser la más simple que satisfaga correctamente los requisitos presentes y los riesgos previsibles.

No se introduce infraestructura (Kafka, Redis, Kubernetes, microservicios, CQRS framework, Event Bus, Service Mesh) "porque podría necesitarse". Cada pieza de infraestructura adicional requiere necesidad demostrable.

---

## 3. Stack Tecnológico

| Componente | Tecnología |
|---|---|
| Lenguaje | Java 21 |
| Framework | Spring Boot 3.4.4 |
| Build | Maven (multi-módulo) |
| Persistencia | MongoDB (Spring Data MongoDB) |
| IA | Spring AI |
| Blockchain | Web3j |
| Hashing | SHA-256 |
| Canonicalización | JSON Canonicalization Scheme (JCS / RFC 8785), librería `io.github.erdtman:java-json-canonicalization:1.1` |
| Testing | JUnit 5, Testcontainers (MongoDB como Replica Set de 1 nodo, requerido para transacciones) |
| Inyección | Constructor injection exclusivamente; `@Autowired` en campos prohibido |
| Utilidades | Lombok, solo donde aporte valor real, nunca como dependencia arquitectónica |

Ninguna tecnología nueva se introduce sin antes justificar: qué problema resuelve, por qué el stack actual no basta, coste de introducirla, impacto arquitectónico, alternativas consideradas.

---

## 4. Estructura de Módulos Maven

```
raíz/ (pom.xml, packaging=pom)
├── contracts/   → interfaces + DTOs puros. CERO dependencias de infraestructura
│                  (sin Spring, sin MongoDB, sin Web3j). Es el único módulo del
│                  que crypto y ai pueden depender.
├── core/        → depende de contracts. Dominio (domain), orquestación
│                  (application), adaptadores técnicos (infrastructure).
├── crypto/      → depende ÚNICAMENTE de contracts. Implementa HashPort.
│                  NUNCA depende de core.
├── ai/          → depende ÚNICAMENTE de contracts. Implementa/consume
│                  AuditFactsPort. NUNCA depende de core.
├── api/         → Módulo de presentación REST (Fase 3, ADR-020). Depende de
│                  `core` (solo sus puertos de aplicación, `core.application.port.out`)
│                  y de `contracts` (para `NarrativeReadPort`, único puerto que cruza
│                  hacia `ai`). NUNCA importa `core.infrastructure.*` directamente —
│                  verificado por su propio test de ArchUnit.
└── app/         → Módulo de ensamblaje (Bootstrap). Depende de core, crypto, ai, api.
                   Provee la configuración compartida (ej. MongoTransactionManager)
                   y el punto de entrada (@SpringBootApplication). Cero lógica de dominio.
```

Dependencias unidireccionales, verificadas por tests de ArchUnit que rompen el build si:
- cualquier clase bajo `core.domain` importa Spring/MongoDB/BSON.
- `ai` o `crypto` importan cualquier clase bajo `core.*`.
- cualquier clase bajo `api.*` importa algo de `core.infrastructure.*` (regla propia de `api`, Tarea 3.0, demostrada activa — falla ante una violación deliberada de prueba, no solo "nunca se dispara").

### 4.1 Árbol interno de `core`

```
core/src/main/java/core/
├── domain/                    # Java puro, cero dependencias externas
│   ├── shared/                # AggregateRoot, EventStream, excepciones base
│   ├── event/                 # DomainEvent, DomainEventPayload, GENESIS_HASH
│   ├── fund/                  # Aggregate Fund + payloads
│   └── physicalasset/         # Aggregate PhysicalAsset + payloads
│
├── application/
│   ├── port/out/               # EventStorePort, OutboxPort, AuditFactsPort (contratos)
│   ├── event/                  # EventEnvelopeFactory, EventPayloadRegistry, EventCanonicalMapper
│   ├── saga/                   # OutboxSagaCoordinator, SagaPolicy<T>
│   ├── service/                # TransactionalEventPublisher
│   └── projection/             # DonationProjectionHandler, DonationAuditFactsHandler,
│                                # ProjectionEventHandler (interfaz común), ProjectionRetryScheduler
│
└── infrastructure/
    ├── persistence/mongo/       # MongoEventStoreAdapter, MongoOutboxPort, documentos @Document
    └── projection/               # ProjectionEventSource (Change Streams), documentos de lectura,
                                   # AuditFactsPortImpl
```

---

## 5. Catálogo de Decisiones Arquitectónicas (ADRs)

### I. Límites de Dominio y Consistencia
- **ADR-001 — Segregación de Agregados.** `Fund` y `PhysicalAsset` son Aggregate Roots transaccionalmente independientes. La asignación de donaciones a activos físicos es consistencia eventual, nunca una transacción ACID única. `Donation` NO es un Aggregate — es una proyección de lectura.
- **ADR-002 — Granularidad del Activo Físico.** `PhysicalAsset` = Unidad Logística Trazable, identificada por `assetId` opaco. No es obligatoriamente una unidad individual ni un contenedor maestro fijo. El mecanismo físico de identificación (QR/RFID/barcode) es independiente de la identidad del Aggregate.
- **ADR-003 — Separación Custodia/Geografía.** `custodianRef` (responsabilidad operativa) y `currentLocation` (nodo logístico) son ejes independientes. Ningún evento de custodia sustituye a un evento de movimiento físico, y viceversa. Se abandonó el concepto genérico de "Transferencia" única.

### II. Modelos Operativos y Matemáticos
- **ADR-004 — Reembolsos: Sobregiro Controlado y Preservación del Histórico.** `Fund` nunca muta `clearedAmount` destructivamente. `refundedAmount` es magnitud propia, monótona creciente. Invariante duro: `refundedAmount + newRefund <= clearedAmount`. Si el reembolso excede `availableAmount` (no `clearedAmount`), se acepta y se levanta `causedDeficit = true` como flag derivado — la logística ya materializada no se revierte automáticamente.
- **ADR-005 — Desconsolidación Parcial y Repetible (ASSET_SPLIT).** El split extrae una cantidad Q del padre; el padre **sobrevive** si `Q_after > 0` (no es terminal en un solo evento, a diferencia del diseño original descartado). Si `Q_after == 0`, transiciona a `DEPLETED` (terminal). El split no muta `lifecycleStatus` mientras `Q_after > 0`.
- **ADR-006 — Telemetría vs. Auditoría Criptográfica.** GPS continuo o paradas no oficiales quedan **excluidos** del Event Store y del pipeline criptográfico (JCS/SHA-256/Merkle/Blockchain). No existe `IN_TRANSIT` como estado ni `ASSET_TRANSIT_REPORTED` como evento en el alcance actual. Diferido a un sistema de telemetría futuro, completamente ajeno al hash chain.
- **ADR-016 — Génesis Dual de Fund.** `Fund` admite dos caminos de creación: `FUND_REGISTERED` (con promesa previa, `PLEDGED`) o `FUNDS_CLEARED` directo como primer evento (sin promesa, ej. efectivo/transferencia manual). `pledgedAmount` es opcional en el agregado. Prohibido insertar eventos sintéticos para forzar uniformidad.

### III. Resiliencia, Sagas y Compensaciones
- **ADR-007 — Coordinación de Sagas vía Outbox Transaccional.** Comunicación cross-stream (Fund→PhysicalAsset, PhysicalAsset padre→hijo) vía `OutboxSagaCoordinator` genérico, agnóstico de dominio, parametrizado con `SagaPolicy<T>` inyectada por cada caso de uso.
- **ADR-008 — Compensación de Fugas de Inventario/Dinero.** Toda saga cross-aggregate tiene camino de compensación. Fallo permanente (agotamiento de reintentos) dispara evento de compensación en el agregado origen (`ALLOCATION_REVERSED`, `ASSET_SPLIT_COMPENSATED`). La compensación de split puede "resucitar" un `PhysicalAsset` desde `DEPLETED` usando `statusBeforeSplit`, sellado en el evento `ASSET_SPLIT` original.
- **ADR-009 — Idempotencia Interna de Compensación.** Todo comando de compensación referencia el identificador único de la operación que revierte (`allocationId`, `childAssetId`). El agregado rechaza una segunda compensación sobre la misma operación.
- **ADR-012 — Asignación Financiera en Dos Fases.** Cruzar `Fund`↔`PhysicalAsset` sin fingir atomicidad: `ALLOCATION_REQUESTED` mueve dinero a `pendingAllocationAmount`; `ALLOCATION_CONFIRMED` lo mueve a `allocatedAmount`; `ALLOCATION_REVERSED` lo devuelve a `availableAmount`. Nunca un evento único `FUNDS_ALLOCATED`.
- **ADR-013 — Taxonomía Estricta de Identificadores.** `commandId` (idempotencia de intención/reintento de red), `externalEventId` (idempotencia de hecho externo, ej. webhook de pasarela de pago), `allocationId`/`refundId`/`childAssetId` (idempotencia de operación de negocio interna), `eventId` (identidad histórica inmutable, participa en el hash chain). Nunca intercambiables.
- **ADR-014 — Separación Custodia/Beneficiario y Continuidad de Ubicación.** En `ASSET_DELIVERED`, `beneficiaryRef` se sella solo en el payload, nunca sobrescribe `custodianRef`. En `ASSET_DISPATCHED`, `currentLocation` se copia a `lastKnownLocation` antes de quedar transitorio — el sistema nunca "pierde" el último nodo confirmado.

### IV. Modelo de Lectura (CQRS) y Orden de Eventos
- **ADR-010 — Cuarentena de Eventos Fuera de Orden.** Si `incomingSequence > lastProcessedSequence + 1`: `RETRY_PENDING` con backoff exponencial. Tras 4 horas: `QUARANTINED`, alerta operativa, esa proyección específica se pausa sin bloquear otros streams. Reanudación manual (`resumeProjection`) reprocesa en orden estricto de sequence.
- **ADR-011 — Índice de Resolución (asset_index).** Índice técnico reconstruible (`assetId → projectionId`, donde `projectionId = fundId` estrictamente), NUNCA fuente de verdad.
- **ADR-015 — Arquitectura Desacoplada de la Capa de Lectura.** Cuatro componentes con responsabilidades distintas: `DonationProjection` (vista de usuario), `AssetHistoryProjection` (historial detallado), `asset_index` (índice técnico), `DonationAuditFacts` (hechos deterministas, único documento que `ai` puede leer, vía `AuditFactsPort`). El LLM nunca es fuente de verdad y nunca accede directamente al Event Store ni a documentos internos de `core`.
- **ADR-017 (implícito, emergente en Tarea 10/11) — Framework de Proyección Genérico.** `ProjectionEventSource` y `ProjectionRetryScheduler` son genéricos, no acoplados a un handler específico. Cualquier proyector nuevo implementa la interfaz común `ProjectionEventHandler` (`handleEvent`, `getHandlerName`) y se registra en la lista inyectada; el enrutamiento de reintentos usa el campo `handlerName` en el documento de retry. Cada handler mantiene su propio checkpoint de secuencia por stream, independiente de los demás.

### V. Integración Externa y Operaciones
- **ADR-022 — Resolución Manual de Lotes Atascados (JMX).** La resolución de lotes en estado `STUCK` durante el anclaje a blockchain (Web3j) se realiza exclusivamente de forma manual vía JMX (`BlockchainAdminOperationsService.resolveStuckBatch(ABANDON|RESUBMIT)`). No hay reintento automático (RBF) programado para evitar doble gasto accidental y mantener el control humano sobre los costos operativos de gas.

### VI. Exposición Pública de Lectura (Fase 3)
- **ADR-020 — Ubicación y Dirección de Dependencia de la Capa API.** Módulo Maven nuevo `api`, separado de `app` (que permanece como bootstrap puro). Dependencia unidireccional `app → api → core` (solo puertos de `core.application.port.out`, nunca infraestructura interna). Excepción única: `api` también depende de `contracts` para `NarrativeReadPort`, porque ese puerto cruza hacia `ai`, que no puede depender de `core` ni de `api`.
- **ADR-021-A — Mecanismo Primario de Autorización.** Tracking code (bearer credential) sin cuenta autenticada para el flujo público de consulta de donación. Cuenta autenticada descartada por fricción excesiva en contexto de donantes ocasionales; queda como necesidad separada para un futuro módulo de identidad (rol "Fundación").
- **ADR-021-B — Naturaleza del Tracking Code.** Stateless, HMAC-SHA-256 completo (sin truncar), dominio versionado `"tracking:v1:" + fundId`, comparación vía `MessageDigest.isEqual()` (timing-safe), expiración embebida en el propio token (365 días por defecto), lista de revocación dispersa (`revoked_tracking_codes`, solo `tokenHash` + `revokedAt`, TTL de 365 días) — nunca un registro completo de cada token emitido.
- **ADR-021-C — Generación del Tracking Code.** Ocurre fuera de Fase 3 (ningún endpoint público lo genera); Fase 3 solo valida. Es determinista y stateless, así que cualquier sistema que conozca el secreto y el `fundId` puede calcularlo de forma independiente.
- **ADR-021-D — Perímetro de Exposición.** Clasificación campo por campo, verificada contra código real, de qué se excluye (`sourceTransactionId`, `allocationId`, `requirementId`, `sourceAllocationId`, `parentAssetRef`, `rootAssetRef`, `statusBeforeSplit`, `AuditMetadata`, `donorRef`), qué se enmascara (`currentCustodian`→categoría por `lifecycleStatus`, `vendorId`→categoría, `currentLocation`→`locationZone` vía tabla de referencia exacta), y qué se expone tal cual o transformado (`assetId`→`assetRef` vía HMAC, `quantity`, `lifecycleStatus`, montos financieros).
- **ADR-022 — ver sección V** (Resolución manual de lotes atascados, sin cambios).
- **ADR-023 — Sin Spring Security.** El mecanismo de autorización de Fase 3 (bearer token stateless con HMAC) no necesita sesiones, roles ni autenticación de usuario — se implementa con un `OncePerRequestFilter` propio (`TrackingCodeAuthFilter`). Spring Security habría sido sobre-ingeniería para este alcance.
- **ADR-024 — Exposición Asíncrona de Narrativas (Approved with amendment).** `NarrativeReadPort` (en `contracts`, único puerto que cruza hacia `ai`) con estados `AVAILABLE`/`PENDING`, generación lazy asíncrona sin `.join()` bloqueante en el camino HTTP, reutilizando el single-flight ya existente de Tarea 12. **Enmienda aprobada tras arbitraje entre dos agentes:** la narrativa se expone como endpoint HTTP independiente (`GET /tracking/narrative`), nunca embebida en `PublicDonationTrackingDTO` — evita que el estado interno de un proceso asíncrono/eventualmente consistente (`ai`) determine el código HTTP del recurso principal, y aísla el radio de fallo entre `core` (determinista) y `ai` (con incertidumbre inherente por diseño, ADR-018).

---

## 6. Modelo de Dominio

### 6.1 Aggregate `PhysicalAsset`

**Estado interno** (reconstruido por replay):
```
assetId, assetType, quantity (BigDecimal), unitOfMeasure (inmutable),
lifecycleStatus, currentLocation (nullable), lastKnownLocation (nunca null
tras registro), custodianRef, parentAssetRef (nullable), rootAssetRef,
allocationId (nullable, propio), sourceAllocationId (nullable, heredado),
currentVersion
```

**Máquina de estados** (`IN_TRANSIT` fue eliminado; ver ADR-006):

| Estado | Comandos que acepta |
|---|---|
| `REGISTERED` | Dispatch, TransferCustody, Split |
| `DISPATCHED` | Receive, Deliver, TransferCustody |
| `RECEIVED` | Dispatch, Deliver, Split, TransferCustody |
| `DELIVERED` | — (terminal) |
| `DEPLETED` | — (terminal, reversible solo vía CompensateAssetSplit) |

**Comandos y eventos:**

| Comando | Evento(s) | Postcondición clave |
|---|---|---|
| `RegisterPhysicalAsset` | `ASSET_REGISTERED` | Génesis del stream |
| `DispatchPhysicalAsset` | `ASSET_DISPATCHED` | `lastKnownLocation` preserva origen; `currentLocation = null` |
| `ReceivePhysicalAsset` | `ASSET_RECEIVED` | `currentLocation` y `lastKnownLocation` = ubicación confirmada |
| `TransferAssetCustody` | `ASSET_CUSTODY_TRANSFERRED` | Solo `custodianRef` cambia; rechaza si `newCustodian == actual` |
| `SplitPhysicalAsset` | `ASSET_SPLIT` (+ `ASSET_DEPLETED` si Q llega a 0) | Padre sobrevive si Q>0; hijo nace vía saga separada |
| `CompensateAssetSplit` | `ASSET_SPLIT_COMPENSATED` | Reintegra cantidad; puede "resucitar" desde `DEPLETED` |
| `DeliverPhysicalAsset` | `ASSET_DELIVERED` | `beneficiaryRef` solo en payload, nunca sobrescribe `custodianRef` |

**Excepciones de dominio:** `InvalidAssetTransitionException`, `InsufficientQuantityException`, `RedundantCustodyTransferException`, `InvalidSplitTargetException`, `DuplicateCompensationException`, `AssetTerminalStateException`.

### 6.2 Aggregate `Fund`

**Estado interno:**
```
fundId, campaignRef, donorRef, currency, pledgedAmount (opcional),
clearedAmount (histórico, inmutable), pendingAllocationAmount,
allocatedAmount, refundedAmount, status, currentVersion
```

**Ecuación fundamental:**
```
availableAmount = clearedAmount - pendingAllocationAmount - allocatedAmount - refundedAmount
```

**Estados:** `PLEDGED`, `CLEARED`, `FAILED`, `REFUNDED`/`FULLY_REFUNDED` (derivado, no evento propio).

**Comandos y eventos principales:**

| Comando | Evento | Notas |
|---|---|---|
| `RegisterFund` | `FUND_REGISTERED` | Camino con promesa (ADR-016) |
| `ClearFunds` | `FUNDS_CLEARED` | Puede ser génesis directo (ADR-016) |
| `AllocateFunds` (fase 1) | `ALLOCATION_REQUESTED` | Mueve a `pendingAllocationAmount` |
| — (fase 2, saga) | `ALLOCATION_CONFIRMED` | Mueve a `allocatedAmount` |
| — (compensación) | `ALLOCATION_REVERSED` | Devuelve a `availableAmount` |
| `RefundFunds` | `FUNDS_REFUNDED` | `refundedAmount += monto`; `causedDeficit` si excede disponible |

**Excepciones documentadas:** 
- `InsufficientAvailableFundsException`: Cuando `requestedAmount > availableAmount`.
- `ExceedsClearedFundsException`: Cuando `refundAmount + refundedAmount > clearedAmount`.
- `DuplicateAllocationException`: Cuando una solicitud de allocation (`allocationId`) ya existe en los registros históricos.
- `InvalidFundTransitionException`: Protege la invariante de que una allocation no puede ser confirmada ni reversada si no existe previamente como una solicitud pendiente (`activeAllocations`).
---

## 7. Capa de Aplicación: Sagas y Command Handlers

### 7.1 Capa de Command Handlers (Procesamiento de Entrada)
- **`CommandRetryTemplate`**: Orquesta el procesamiento seguro de comandos, capturando `ConcurrencyConflictException` para recargar el `AggregateRoot` actualizado y reevaluar las reglas de negocio, aplicando un backoff exponencial configurado (mitigando bloqueos bajo alta carga).
- **Servicios de Dominio (`FundCommandService`, etc.)**: Coordinan la ejecución invocando el agregado, delegando en el `TransactionalEventPublisher` y formalizando el registro de las `SagaPolicy` concretas (`AssetRegisteredSagaPolicy`, `SplitPhysicalAssetSagaPolicy`, `FundAllocationSagaPolicy`).

### 7.2 Orquestación Transaccional (Sagas)
**`OutboxSagaCoordinator`** — motor genérico en Java puro (sin Spring, sin Mongo):
- `processPendingMessages()`: primero verifica ventana de cuarentena (4h desde `createdAt`); si expiró, compensa y marca `QUARANTINED` (sin tocar `retryCount`); si no expiró, intenta `execute()` y aplica backoff exponencial (`2^retryCount × 15s`) ante fallo.
- `compensate()` envuelto en su propio try-catch — un fallo de compensación no detiene el procesamiento del resto del lote.
- `SagaPolicy<T>` inyectada por caso de uso (una para asignación Fund↔Asset, otra para split de PhysicalAsset) — el coordinador nunca conoce lógica de dominio.
- `OutboxMessage` incluye `sourceAggregateId` para trazabilidad operativa.

**Protocolo de escritura atómica (Transactional Outbox):** el evento de dominio y el mensaje de outbox se persisten en la misma transacción MongoDB (`TransactionalEventPublisher`, `@Transactional`, invocado desde fuera del propio bean para evitar el problema de auto-invocación de Spring AOP).

---

## 8. Capa de Infraestructura

### 8.1 Concurrencia Optimista (innegociable)

```
1. Application Handler carga Aggregate → currentVersion = N (fijo)
2. Evalúa invariantes de negocio
3. Ensambla evento con sequence = N + 1 (decidido AQUÍ, nunca recalculado después)
4. EventStorePort.append(streamId, expectedVersion=N, evento)
5. Adaptador consulta previousHash del evento N exacto (no "el más reciente")
6. Índice único (streamId, sequence) en MongoDB rechaza colisiones →
   ConcurrencyConflictException (nunca reintento automático en el adaptador)
7. Application Handler recarga y reevalúa el comando completo, no solo reescribe
```

### 8.2 Pipeline criptográfico (`crypto`)

```
DomainEvent → EventCanonicalMapper (Map determinista)
           → HashPort.canonicalizeAndHash(eventData, previousHash)
           → JcsHashAdapter: inserta previousHash en el mapa, canonicaliza JCS/RFC8785,
             SHA-256, nunca incluye el propio eventHash en el material hasheado
           → eventHash sellado en el documento persistido
```
`MerkleTree`: agrupa hashes periódicamente, duplica la última hoja si el número es impar, orden estrictamente de inserción. `MerkleBatch` con estado `PENDING`/`ANCHORED` — el anclaje real a blockchain (Web3j) es la Tarea 12, aún no implementada.

### 8.3 Reconstrucción del payload tipado

`EventPayloadRegistry`: mapea `eventType` (string) → clase concreta de payload, para que `loadStream()` reconstruya instancias fuertemente tipadas (no `Map` genérico) antes de entregarlas al `switch` de pattern matching en `AggregateRoot.apply()`. También es el punto de extensión futuro para upcasting de `schemaVersion` antiguos.

### 8.4 Proyecciones (CQRS)

```
event_store (Mongo, colección real)
     │
     ▼ Change Stream (ProjectionEventSource, aislado de MongoEventStoreAdapter)
     │
     ├── DonationProjectionHandler → donation_views (vista de usuario)
     └── DonationAuditFactsHandler → donation_audit_facts (hechos para IA)
```
- Reconstrucción: guardar `resumeToken` → bulk histórico → conmutar al stream desde el token — con guardia de idempotencia (`sequence <= lastProcessedSequence` → descartar) protegiendo contra doble entrega en la ventana de la reconstrucción.
- `DonationAuditFacts`: transiciones auditadas limitadas a `DISPATCHED→RECEIVED` (72h default), `DISPATCHED→DELIVERED` (48h default, marcado como candidato a revisión por cubrir trayecto completo), `RECEIVED→DELIVERED` (48h default). Umbrales en `application.yml`, nunca hardcoded; el umbral usado se congela dentro del propio registro histórico (no se recalcula retroactivamente si la configuración cambia). Incluye `financialFlags` con `causedDeficit` de `Fund`.

---

## 9. Estado Actual del Proyecto

| Tarea | Contenido | Estado |
|---|---|---|
| 0 | Scaffolding Maven multi-módulo | ✅ Completada |
| 1 | `AggregateRoot`, `EventStream`, contratos base | ✅ Completada |
| 2 | `contracts`: `HashPort`, `AuditFactsPort`, DTOs | ✅ Completada (DTO actualizado en Tarea 11) |
| 3 | Aggregate `PhysicalAsset` completo | ✅ Completada, con tabla de trazabilidad comando→ADR→test |
| 4 | Payloads formales de `PhysicalAsset` | ✅ Completada |
| 5 | Aggregate `Fund` completo | ✅ Completada, con tabla de trazabilidad |
| 6 | Payloads formales de `Fund` | ✅ Completada |
| 7 | `OutboxSagaCoordinator` genérico | ✅ Completada (Reabierta y corregida tras auditoría) |
| 7.1 | Capa de Command Handlers + activación real de sagas | ✅ **Completada** — `CommandRetryTemplate`, policies y handlers implementados |
| 8 | `crypto`: `JcsHashAdapter`, `MerkleTree` | ✅ Completada |
| 9 | Persistencia MongoDB, `EventStorePort`, Outbox transaccional | ✅ Completada, verificada con Testcontainers real |
| 10 | Proyecciones CQRS: `DonationProjection`, cuarentena | ✅ Completada, incluyendo test de condición de carrera real bulk+resume |
| 10.1 | Corrección de Defectos (monto de origen `FUNDS_CLEARED`) | ✅ Completada |
| 10.2 | Corrección de Defectos (historial y logística en eventos) | ✅ Completada |
| 10.3 | Corrección Hallazgo #4: Metadata de Fund en Eventos/Proyecciones | ✅ **Completada** — Inclusión de `currency`, `campaignRef`, `donorRef` (nullable) y estado derivado |
| 10.4 | Corrección Hallazgo #6: Migración de quantity a BigDecimal | ✅ **Completada** — Refactorización estructural en todo el dominio y proyecciones usando `Decimal128` |
| 11 | `DonationAuditFacts`, `AuditFactsPort` implementado | ✅ Completada |
| 12 | `ai`: `NarrativeGenerator` consumiendo `AuditFactsPort` | ✅ Completada |
| 13 | `crypto.infrastructure.web3j`: anclaje EVM | ✅ Completada |
| 14 | `app`: ensamblaje del módulo de bootstrap (Cierre de Fase 2) | ✅ Completada |
| 10.5 | Corrección Hallazgo #13: `ProjectionRetryDocument` vía `.builder()` (no `new()`) | ✅ **Completada** — restaura el rescate real de eventos en gap, que el propio `ProjectionRetryScheduler` (Frente 2) nunca habría podido reclamar |
| **Fase 3** | **Exposición REST de Lectura — 14 tareas (3.0 a 3.13)** | ✅ **COMPLETADA** |
| 3.0 | Scaffolding del módulo `api` (Maven, ArchUnit) | ✅ Completada |
| 3.1 | `DonationReadPort` + `DonationReadModel` | ✅ Completada |
| 3.2 | Infraestructura de secretos HMAC compartidos | ✅ Completada |
| 3.3 | Servicio de tracking code (`TrackingCodeService`) | ✅ Completada |
| 3.4 | `OncePerRequestFilter` de autorización | ✅ Completada |
| 3.5 | Verificación de pertenencia vía `asset_index` | ✅ Completada |
| 3.6 | Colección `location_reference` | ✅ Completada |
| 3.7 | Servicio de cálculo de `assetRef` | ✅ Completada |
| 3.8 | Mapper `DonationReadModel` → `PublicDonationTrackingDTO` | ✅ Completada |
| 3.9 | Primer controlador REST (`GET /tracking`) | ✅ Completada |
| 3.10 | Segundo endpoint (historial de activo) | ✅ Completada |
| 3.11 | `NarrativeReadPort` en `contracts` (ADR-024) | ✅ Completada |
| 3.12 | Implementación de `NarrativeReadPort` en `ai` | ✅ Completada |
| 3.13 | Endpoint HTTP de narrativa (`GET /tracking/narrative`) | ✅ Completada |

**Métrica de calidad — Fase 2:** 100% cobertura de las 14 tareas originales más 6 correcciones de auditoría (7.1, 10.1–10.5), pruebas pasando en todos los módulos contra Testcontainers real.

**Métrica de calidad — Fase 3:** los tres endpoints públicos verificados end-to-end (filtro real + controlador real + puerto + mapper, no solo piezas certificadas por separado). Reactor completo de 6 módulos (`contracts`, `core`, `crypto`, `ai`, `api`, `app`) en verde.

**Fase 2: cerrada. Fase 3: cerrada.** El sistema expone tres endpoints públicos de lectura sobre el motor de trazabilidad completo, con autorización por tracking code, perímetro de privacidad verificado campo por campo, y narrativa de IA generada de forma asíncrona sin bloquear el camino HTTP.

**Próximo hito inmediato:** sin definir formalmente todavía. Candidatos identificados: (a) Fase D de la auditoría de Fase 2 — Hallazgo #5 (forma de `AggregateRoot`), pospuesto por decisión consciente; (b) módulo de identidad/cuentas (rol "Fundación" obligatorio, donante individual opcional) — hilo completamente aparte, sin diseño iniciado; (c) deuda técnica menor de Fase 3 (ver sección 9.1, ítems 6-9). Ver `estado-fase3.md` sección 10 para el detalle.

## 9.1 Deudas Técnicas Identificadas

1. **`WebEnvironment` en Tests:** `MongoTransactionManager` usa `MongoDatabaseFactory` el cual en Spring requiere levantar un subconjunto mayor de beans al usar MongoDB Testcontainers si la configuración de auto-discovery de Spring choca. (Resuelto parcialmente; mantener bajo vigilancia si los tiempos de test suben).
2. **Ubicación del Wrapper Web3j (`AnchorRegistry`):** El plugin web3j generó el wrapper de Java del smart contract en `crypto/target/generated-test-sources/web3j`, pero se está utilizando tanto para tests como para el código de producción. Compila correctamente porque Maven agrega la carpeta al classpath, pero semánticamente es un code smell que un artefacto de producción dependa de `generated-test-sources`. (Deuda técnica menor: corregir en el futuro reconfigurando `web3j-maven-plugin` para que genere en `generated-sources` o aislando el cliente de producción).
3. **Workaround en `Testcontainers` (docker.api.version):** 
- **Negociación de versión de API de Docker (`docker-java.properties`)**: Testcontainers negocia correctamente la versión `v1.41` en el módulo `crypto`, pero por razones desconocidas falla al intentar un fallback a `v1.32` en los módulos `app` y `core` bajo condiciones idénticas. El workaround aplicado fue forzar `api.version=1.41` en `src/test/resources/docker-java.properties` (y copiar el archivo `testcontainers.properties`) tanto en `app` como en `core`. Esto requiere investigación futura para aislar la causa raíz en la resolución de dependencias o configuración del daemon.

4. **Snapshotting de Eventos:** Pendiente de optimización para streams de ciclo de vida largo (candidato principal: `Fund` de campañas activas), donde el replay completo penaliza el tiempo de recuperación en memoria del lado de escritura. No implementar preventivamente — instrumentar longitud de historial como métrica de observabilidad primero; evaluar snapshotting solo si un stream real se acerca a un umbral de referencia (~500 eventos) con latencia medible.

5. **Hallazgos de `ProjectionRetryScheduler` (Resuelto):** El problema sobre umbrales y manejo genérico de reintentos reportado tempranamente fue **completamente resuelto** mediante el rediseño genérico documentado en ADR-017 e implementado durante la Tarea 11. Se deja constancia explícita de su resolución aquí.

6. **`PublicVendorCategory` sin uso (Fase 3):** el enum se definió durante el diseño del perímetro de exposición pero nunca se incluyó en ningún DTO — `vendorId` vive en `allocations[]`, no en `logistics[]`, y no se decidió si Fase 3 debía exponer un resumen de asignaciones enmascarado. Pendiente de decisión de producto, no bloqueante.

7. **Población de `location_reference` (Fase 3):** la colección de referencia de ubicaciones (Tarea 3.6) quedó vacía o con datos mínimos de prueba. Poblarla con ubicaciones operativas reales es trabajo posterior de operación, no de diseño — el mecanismo de consulta exacta ya está implementado y probado.

8. **Sin mecanismo operativo para `TrackingCodeService.revoke()` (Fase 3):** el método de revocación de tracking codes existe y está probado (Tarea 3.3), pero no hay ningún endpoint administrativo ni exposición JMX para invocarlo en producción — a diferencia de `resolveStuckBatch` (ADR-022), que sí tiene su vía JMX. Candidato a una mini-tarea futura con el mismo patrón.

9. **Gestión de secretos HMAC en producción (Fase 3):** los dos secretos (tracking code, `assetRef`) se gestionan como variables de entorno vía `@ConfigurationProperties`, sin vault dedicado — decisión consciente de no sobre-ingeniería dado el contexto del proyecto. Revisar si un despliegue en producción real exige un mecanismo más robusto de rotación.

---

## 10. Diccionario de Conceptos

**ADR (Architecture Decision Record):** documento formal que congela una decisión arquitectónica con su contexto, alternativas consideradas y consecuencias. En este proyecto, 16-17 ADRs numerados forman el contrato de dominio vigente.

**Aggregate / Aggregate Root:** entidad raíz que protege un conjunto de invariantes de negocio bajo un único límite de consistencia transaccional. En este proyecto: `Fund` y `PhysicalAsset`. Se reconstruye completamente a partir del replay de sus eventos.

**allocationId:** identificador de una operación de asignación financiera específica (`Fund → PhysicalAsset`). Distinto de `sourceAllocationId` (heredado por los hijos de un split, que no tuvieron asignación propia).

**AuditFactsDTO / AuditFactsPort:** contrato estable (vive en `contracts`) mediante el cual el módulo `ai` lee hechos deterministas de auditoría, sin conocer MongoDB ni la estructura interna de `core`.

**Aggregate Boundary:** límite de consistencia transaccional de un Aggregate. Se decide por invariantes de negocio, nunca por conveniencia de modelado o relaciones "naturales" del dominio.

**assetRef (Fase 3):** referencia pública y opaca de un `PhysicalAsset`, derivada por HMAC-SHA-256 completo (`"asset-ref:v1:" + assetId`), nunca el `assetId` interno crudo. No es invertible directamente — la resolución `assetRef → assetId` se hace recalculando el HMAC sobre un conjunto acotado de candidatos conocidos (los activos de una donación), nunca vía índice persistido.

**causedDeficit:** flag booleano derivado, calculado por el dominio `Fund` cuando un reembolso excede el saldo disponible (no el histórico). Es un hecho, no una interpretación — nunca lo calcula el LLM.

**childAssetId:** identificador del `PhysicalAsset` hijo nacido de una operación `ASSET_SPLIT`.

**commandId:** identificador de idempotencia de la *intención* de un comando (protege contra reintentos de red / doble clic). Distinto de `eventId`, `externalEventId` y los identificadores de operación de negocio.

**Concurrencia Optimista:** mecanismo de control de concurrencia donde la versión esperada (`expectedVersion`) se fija en el momento de evaluar la lógica de negocio, y la escritura se rechaza (no se recalcula) si esa versión ya no coincide con el estado real de la base de datos.

**Consistencia Eventual:** propiedad de un sistema distribuido donde dos partes relacionadas (ej. `Fund` y `PhysicalAsset`) pueden estar temporalmente desincronizadas, resolviéndose mediante sagas y compensación, en vez de una transacción ACID única imposible de mantener entre dos Aggregate Roots distintos.

**currentLocation / lastKnownLocation:** `currentLocation` es la ubicación confirmada actual (null solo durante `DISPATCHED`, antes de `RECEIVED`/`DELIVERED`). `lastKnownLocation` nunca es null tras el registro — preserva el último nodo confirmado incluso durante el tránsito, para que el sistema nunca "pierda de vista" la donación (ADR-014).

**custodianRef vs. beneficiaryRef:** `custodianRef` es el responsable operativo actual (bodeguero, transportista). `beneficiaryRef` es el receptor final de la ayuda humanitaria. Nunca se confunden ni se sobrescriben entre sí (ADR-014).

**DonationReadPort / DonationReadModel (Fase 3):** puerto de lectura en `core.application.port.out` que expone hacia `api` únicamente los campos que el perímetro de exposición (ADR-021-D) permite — nunca `DonationProjectionDocument` directamente. `confirmedAllocationAmount` se calcula aquí, en el adaptador, no en `api`.

**DomainEvent / DomainEventPayload:** el hecho histórico inmutable emitido por un Aggregate. El payload contiene solo datos de negocio; el envoltorio completo (`eventId`, `sequence`, hashes) se ensambla después, fuera del dominio puro.

**Event Sourcing:** patrón donde el estado no se persiste directamente; se deriva de la secuencia completa (o parcial, con snapshot) de eventos históricos inmutables asociados a un stream.

**Event Store:** la colección/base de datos que almacena todos los eventos de dominio de forma append-only, encadenados criptográficamente. Es la única fuente de verdad del sistema.

**EventPayloadRegistry:** componente que mapea `eventType` (string) a la clase Java concreta de su payload, necesario para reconstruir tipos fuertemente tipados al leer eventos de vuelta desde MongoDB.

**expectedVersion:** la versión de un Aggregate en el momento en que se evaluó la lógica de negocio; se usa como precondición de escritura para detectar conflictos de concurrencia.

**externalEventId:** identificador de un hecho generado por un sistema externo (ej. webhook de una pasarela de pago), usado para deduplicar reintentos que el propio sistema externo puede generar, independientemente de nuestros `commandId`.

**GENESIS_HASH:** constante única que representa el `previousHash` del primer evento de cualquier stream (no hay evento anterior real que hashear).

**Hexagonal Architecture (Ports & Adapters):** patrón donde el dominio define contratos (puertos) que la infraestructura implementa (adaptadores), manteniendo el núcleo de negocio libre de dependencias técnicas.

**JCS (JSON Canonicalization Scheme, RFC 8785):** estándar que define una representación determinista y única de un documento JSON (orden alfabético de claves, sin espacios extra), necesaria para que el hash de un evento sea reproducible sin ambigüedad.

**lifecycleStatus:** el estado actual de la máquina de estados de un Aggregate (ej. `REGISTERED`, `DISPATCHED`, `DELIVERED` para `PhysicalAsset`).

**locationZone (Fase 3):** valor público derivado de `currentLocation` vía una tabla de referencia determinista (`location_reference`, consulta exacta, sin heurística de texto ni fuzzy matching). `null` si la ubicación no está registrada en la tabla — un dato ausente es preferible a uno inventado.

**Merkle Tree / Merkle Root:** estructura que agrupa periódicamente los hashes de múltiples eventos en un único hash raíz, que es lo que efectivamente se ancla en blockchain (nunca los eventos individuales), por eficiencia y economía de costos de transacción.

**NarrativeReadPort (Fase 3):** puerto neutral en `contracts` (ADR-024) mediante el cual `api` obtiene el estado de la narrativa de IA de una donación sin depender de `ai` directamente. Método `getOrTriggerGeneration(fundId)` — el nombre expone deliberadamente que invocarlo puede disparar generación asíncrona como efecto colateral, en vez de esconderlo detrás de un nombre que sugiera lectura pura.

**Modular Monolith:** estilo arquitectónico de un solo despliegue con fronteras internas estrictas entre módulos (aquí, `contracts`, `core`, `crypto`, `ai`), sin la complejidad operativa de microservicios reales.

**OutboxSagaCoordinator / Transactional Outbox:** patrón para coordinar operaciones que cruzan dos Aggregate Roots (streams) sin transacción distribuida real: se persiste el evento de dominio y un "mensaje pendiente" en la misma transacción local; un proceso asíncrono relaya ese mensaje, con reintentos, backoff y compensación ante fallo permanente.

**payload:** el contenido de negocio específico de un tipo de evento (ej. `AssetDispatchedPayload`), sin metadata de infraestructura como hashes o secuencia.

**PII Vault / Opaque Reference:** patrón de privacidad donde los datos personales identificables viven separados y protegidos, referenciados desde el dominio de trazabilidad solo mediante identificadores opacos, nunca expuestos directamente en datos destinados a blockchain.

**previousHash / eventHash:** el hash del evento anterior en el stream (`previousHash`) y el hash resultante de canonicalizar y hashear el evento actual junto con ese `previousHash` (`eventHash`). Forman la cadena de integridad criptográfica. El propio `eventHash` nunca participa en el material que se hashea para calcularlo.

**PhysicalAsset:** Aggregate Root que representa una unidad logística trazable (no necesariamente una unidad física individual ni un contenedor maestro fijo). Ver sección 6.1.

**ProjectionEventHandler:** interfaz común implementada por cada proyector (`DonationProjectionHandler`, `DonationAuditFactsHandler`), que permite reutilizar la misma infraestructura de Change Stream, reintentos y cuarentena sin duplicar lógica.

**Proyección (Projection / Read Model):** vista de lectura derivada de los eventos, optimizada para consulta, reconstruible en cualquier momento desde el Event Store, nunca la fuente de verdad.

**quantity / unitOfMeasure:** cantidad actual de un `PhysicalAsset` (mutable solo vía split) y su unidad de medida (inmutable durante todo el ciclo de vida del stream).

**refundId:** identificador de idempotencia interna de una operación de reembolso sobre `Fund`, análogo a `allocationId` pero para la operación inversa.

**rootAssetRef / parentAssetRef:** referencias de genealogía de un `PhysicalAsset`. `parentAssetRef` es el padre directo (null si es raíz); `rootAssetRef` es el ancestro original de todo el linaje (permite reconstruir el árbol completo de un split sin recorrer la cadena completa).

**Saga:** secuencia coordinada de operaciones que cruza múltiples Aggregate Roots, con su propio mecanismo de compensación ante fallo parcial, en lugar de depender de una transacción ACID imposible entre dos streams distintos.

**schemaVersion:** número de versión del payload de un tipo de evento, que permite evolucionar la estructura de eventos futuros sin romper la interpretación de eventos históricos ya persistidos (upcasting).

**sequence:** número entero monótono creciente que ordena los eventos dentro de un stream específico (nunca se usa timestamp para ordenar el dominio). La restricción `(streamId, sequence)` es única en la base de datos.

**sourceAllocationId:** ver `allocationId`.

**statusBeforeSplit:** campo sellado en el evento `ASSET_SPLIT`, que preserva el `lifecycleStatus` del padre justo antes del split, necesario para poder "resucitarlo" correctamente si la compensación de una saga fallida requiere revertir un split que había llevado al padre a `DEPLETED`.

**Stream (Event Stream):** la secuencia completa, ordenada, de eventos pertenecientes a un único Aggregate (identificado por `streamId`), que define el límite de orden y concurrencia.

**TraceabilityEvent:** modelo conceptual del evento completo persistido, incluyendo metadata de infraestructura (eventId, sequence, hashes) además del payload de negocio.

**Tracking Code (Fase 3):** credencial bearer stateless que autoriza la consulta pública de una donación sin necesidad de cuenta (ADR-021-A/B). Formato: `Base64URL(fundId + "|" + expiry) + "." + Base64URL(HMAC-SHA-256("tracking:v1:" + fundId + "|" + expiry))`. Generado fuera de Fase 3; Fase 3 solo valida.

**Upcasting:** técnica de transformar un evento histórico de una versión de esquema antigua a la estructura esperada por el código actual, sin modificar el evento original almacenado.
