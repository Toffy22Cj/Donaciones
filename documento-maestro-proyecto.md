# Documento Maestro — Motor de Trazabilidad Verificable de Donaciones

**Nombre comercial provisional (no usado en código):** el proyecto se ha referido a sí mismo informalmente como "PaxFide" en la conversación de diseño, pero esto es explícitamente **no vinculante** — puede cambiar sin afectar nada del dominio, la arquitectura ni el código.
**Base package Java:** `com.traceability`
**Fase actual:** Fase 2 — Implementación (la Fase 1, Domain Design, está formalmente cerrada)

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
└── app/         → Módulo de ensamblaje (Bootstrap). Depende de core, crypto, ai.
                   Provee la configuración compartida (ej. MongoTransactionManager)
                   y el punto de entrada (@SpringBootApplication). Cero lógica de dominio.
```

Dependencias unidireccionales, verificadas por un test de ArchUnit dentro de `core` que rompe el build si:
- cualquier clase bajo `core.domain` importa Spring/MongoDB/BSON.
- `ai` o `crypto` importan cualquier clase bajo `core.*`.

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

**Excepciones:** `InsufficientAvailableFundsException`, `ExceedsClearedFundsException`, `DuplicateAllocationException`.

---

## 7. Capa de Aplicación: Sagas

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
| 7 | `OutboxSagaCoordinator` genérico | ✅ Completada |
| 8 | `crypto`: `JcsHashAdapter`, `MerkleTree` | ✅ Completada |
| 9 | Persistencia MongoDB, `EventStorePort`, Outbox transaccional | ✅ Completada, verificada con Testcontainers real (incluyendo detección real de bug de `MongoTransactionManager` no autoconfigurado) |
| 10 | Proyecciones CQRS: `DonationProjection`, `asset_index`, cuarentena, reconstrucción | ✅ Completada, incluyendo test de condición de carrera real bulk+resume |
| 11 | `DonationAuditFacts`, `AuditFactsPort` implementado, generalización del framework de proyección | ✅ **Completada** — 9/9 tests ejecutados en Testcontainers real, sin regresión |
| 12 | `ai`: `NarrativeGenerator` consumiendo `AuditFactsPort` | ✅ **Completada** — Resiliencia single-flight, TTL fallback y prompt injection preventions |
| 13 | `crypto.infrastructure.web3j`: `Web3jBlockchainAnchorAdapter`, `BlockchainAnchorScheduler` y `AnchorConfirmationPoller` | ✅ **Completada** — 39 tests de regresión cruzada, integración end-to-end real contra Ganache comprobando la transición SUBMITTED -> ANCHORED comprobando en-chain Merkle Root. |
| 14 | `app`: ensamblaje del módulo de bootstrap (`@SpringBootApplication`, sin capa HTTP) — cierre técnico de la Fase 2 | ✅ **Completada** — `ApplicationContextLoadTest` en verde: `core`, `crypto` y `ai` ensamblados en un único `ApplicationContext`, `MongoTransactionManager` canónico, smoke test transaccional real (Event Store + Outbox releídos vía puertos reales) |

**Métrica de calidad actual (última cifra confirmada):** 100% Cobertura de las 14 tareas de la Fase 2, pruebas automatizadas pasando exitosamente en todos los módulos (incluyendo el módulo `crypto` entero con 39 pruebas interconectadas, módulo `ai`, `core`, y el ensamblaje completo en `app`). Disciplina demostrada exigiendo ejecución real contra Testcontainers.

**Fase 2: cerrada.** El sistema completo arranca como un único proceso ensamblado, no solo como módulos verificados por separado.

**Próximo hito inmediato:** definición formal del alcance de la Fase 3 (probablemente API REST y exposición de trazabilidad verificable para el donante) — pendiente de una sesión de Modo de Arquitectura dedicada. Ver `plan-ejecucion-agentes-fase2.md` sección 6.

## 9.1 Deudas Técnicas Identificadas

1. **`WebEnvironment` en Tests:** `MongoTransactionManager` usa `MongoDatabaseFactory` el cual en Spring requiere levantar un subconjunto mayor de beans al usar MongoDB Testcontainers si la configuración de auto-discovery de Spring choca. (Resuelto parcialmente; mantener bajo vigilancia si los tiempos de test suben).
2. **Ubicación del Wrapper Web3j (`AnchorRegistry`):** El plugin web3j generó el wrapper de Java del smart contract en `crypto/target/generated-test-sources/web3j`, pero se está utilizando tanto para tests como para el código de producción. Compila correctamente porque Maven agrega la carpeta al classpath, pero semánticamente es un code smell que un artefacto de producción dependa de `generated-test-sources`. (Deuda técnica menor: corregir en el futuro reconfigurando `web3j-maven-plugin` para que genere en `generated-sources` o aislando el cliente de producción).
3. **Workaround en `Testcontainers` (docker.api.version):** En el módulo `app`, `Testcontainers` falla consistentemente al inferir la versión del API de Docker y hace un fallback a la versión no soportada `1.32`, bloqueando el arranque del `ApplicationContextLoadTest`. Esto ocurre bajo condiciones idénticas al módulo `crypto`, el cual sí negocia exitosamente la versión `1.41`. Tras una exhaustiva revisión de árboles de dependencias y variables de entorno sin hallar diferencias causales, se estableció como workaround un archivo `docker-java.properties` en `app` con `api.version=1.41`. Si alguien "limpia" este archivo pensando que es basura, el test volverá a fallar silenciosamente en integración continua.

4. **Snapshotting de Eventos:** Pendiente de optimización para streams de ciclo de vida largo (candidato principal: `Fund` de campañas activas), donde el replay completo penaliza el tiempo de recuperación en memoria del lado de escritura. No implementar preventivamente — instrumentar longitud de historial como métrica de observabilidad primero; evaluar snapshotting solo si un stream real se acerca a un umbral de referencia (~500 eventos) con latencia medible.

---

## 10. Diccionario de Conceptos

**ADR (Architecture Decision Record):** documento formal que congela una decisión arquitectónica con su contexto, alternativas consideradas y consecuencias. En este proyecto, 16-17 ADRs numerados forman el contrato de dominio vigente.

**Aggregate / Aggregate Root:** entidad raíz que protege un conjunto de invariantes de negocio bajo un único límite de consistencia transaccional. En este proyecto: `Fund` y `PhysicalAsset`. Se reconstruye completamente a partir del replay de sus eventos.

**allocationId:** identificador de una operación de asignación financiera específica (`Fund → PhysicalAsset`). Distinto de `sourceAllocationId` (heredado por los hijos de un split, que no tuvieron asignación propia).

**AuditFactsDTO / AuditFactsPort:** contrato estable (vive en `contracts`) mediante el cual el módulo `ai` lee hechos deterministas de auditoría, sin conocer MongoDB ni la estructura interna de `core`.

**Aggregate Boundary:** límite de consistencia transaccional de un Aggregate. Se decide por invariantes de negocio, nunca por conveniencia de modelado o relaciones "naturales" del dominio.

**causedDeficit:** flag booleano derivado, calculado por el dominio `Fund` cuando un reembolso excede el saldo disponible (no el histórico). Es un hecho, no una interpretación — nunca lo calcula el LLM.

**childAssetId:** identificador del `PhysicalAsset` hijo nacido de una operación `ASSET_SPLIT`.

**commandId:** identificador de idempotencia de la *intención* de un comando (protege contra reintentos de red / doble clic). Distinto de `eventId`, `externalEventId` y los identificadores de operación de negocio.

**Concurrencia Optimista:** mecanismo de control de concurrencia donde la versión esperada (`expectedVersion`) se fija en el momento de evaluar la lógica de negocio, y la escritura se rechaza (no se recalcula) si esa versión ya no coincide con el estado real de la base de datos.

**Consistencia Eventual:** propiedad de un sistema distribuido donde dos partes relacionadas (ej. `Fund` y `PhysicalAsset`) pueden estar temporalmente desincronizadas, resolviéndose mediante sagas y compensación, en vez de una transacción ACID única imposible de mantener entre dos Aggregate Roots distintos.

**currentLocation / lastKnownLocation:** `currentLocation` es la ubicación confirmada actual (null solo durante `DISPATCHED`, antes de `RECEIVED`/`DELIVERED`). `lastKnownLocation` nunca es null tras el registro — preserva el último nodo confirmado incluso durante el tránsito, para que el sistema nunca "pierda de vista" la donación (ADR-014).

**custodianRef vs. beneficiaryRef:** `custodianRef` es el responsable operativo actual (bodeguero, transportista). `beneficiaryRef` es el receptor final de la ayuda humanitaria. Nunca se confunden ni se sobrescriben entre sí (ADR-014).

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

**Merkle Tree / Merkle Root:** estructura que agrupa periódicamente los hashes de múltiples eventos en un único hash raíz, que es lo que efectivamente se ancla en blockchain (nunca los eventos individuales), por eficiencia y economía de costos de transacción.

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

**Upcasting:** técnica de transformar un evento histórico de una versión de esquema antigua a la estructura esperada por el código actual, sin modificar el evento original almacenado.
