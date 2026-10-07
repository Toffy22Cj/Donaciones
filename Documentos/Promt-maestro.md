# Plan de Ejecución para Agentes de Código — Fase 2: Implementación

**Proyecto:** Motor de Trazabilidad Verificable de Donaciones (nombre comercial provisional, no usar en código: package base `com.traceability`)
**Fase:** 2 — Implementación (la Fase 1, Domain Design & Architecture Blueprint, está formalmente cerrada con 16 ADRs)
**Audiencia de este documento:** agentes de código autónomos (Claude Code, Cursor, o equivalente) y los ingenieros que los supervisan.

---

## 0. Cómo usar este documento

1. Cada **Tarea** de la sección 4 es un prompt autocontenido. Se pega tal cual al agente, en una sesión nueva o encadenada.
2. El **Prompt Maestro** (sección 1) va siempre primero, en cada sesión, antes de la tarea específica. Es el "contexto persistente" — cumple la misma función que el prompt maestro que abrió esta conversación, pero adaptado para un agente que ejecuta, no que dialoga.
3. Ninguna tarea marcada como **BLOQUEADA** se ejecuta hasta que la tarea de la que depende haya sido revisada y aprobada por un humano. Esto no es burocracia: es la misma regla que hemos aplicado en todo el diseño ("no generes código prematuramente... espera mi aprobación cuando la decisión sea arquitectónicamente significativa"). Un agente no tiene la autoridad de auto-aprobarse.
4. Si el agente, durante la ejecución de una tarea, detecta una contradicción con los ADRs o con este plan, **debe detenerse y reportarla**, no resolverla por su cuenta. Esto está codificado explícitamente en el Prompt Maestro.

---

## 1. Prompt Maestro de Contexto (pegar en cada sesión de agente)

```
Eres un Ingeniero de Software Senior implementando el módulo `core` (y eventualmente
`crypto` y `ai`) de un sistema de trazabilidad de donaciones basado en Event Sourcing.

STACK OBLIGATORIO:
- Java 21, Spring Boot 3.4.4, Maven, MongoDB, Spring Data MongoDB, JUnit 5, Testcontainers.
- Lombok solo si aporta valor real; no lo uses como dependencia arquitectónica.
- No introduzcas ninguna dependencia, framework o librería que no esté en esta lista
  sin detenerte a preguntar primero, explicando: qué problema resuelve, por qué el
  stack actual no basta, coste de introducirla, impacto arquitectónico, alternativas.

REGLA DE ORO DE ESTE PROYECTO:
No eres un generador de código que maximiza líneas escritas. Eres un implementador
disciplinado de un contrato de dominio ya cerrado tras un proceso de diseño extenso.
Cada clase que escribas debe poder señalarse contra un ADR específico. Si no puedes
señalar el ADR que justifica una decisión de diseño que estás por tomar, DETENTE y
pregunta en vez de inventar.

CATÁLOGO DE ADRs VIGENTE (fuente de verdad del dominio):
- ADR-001: Fund y PhysicalAsset son Aggregate Roots transaccionalmente independientes.
  La asignación de fondos a activos físicos es consistencia eventual, nunca ACID única.
- ADR-002: PhysicalAsset = Unidad Logística Trazable (no unidad individual obligatoria,
  no contenedor maestro obligatorio). Identidad opaca (assetId), independiente del
  mecanismo físico de identificación (QR/RFID/barcode).
- ADR-003: Custodia (custodianRef) y Geografía (currentLocation) son ejes independientes.
  Ningún evento de custodia sustituye a un evento de movimiento físico, y viceversa.
- ADR-004: Fund nunca muta clearedAmount destructivamente. refundedAmount es magnitud
  propia, monótona creciente. Invariante duro: refundedAmount + nuevoRefund <= clearedAmount.
  causedDeficit es un flag derivado, no una mutación de availableAmount.
- ADR-005: ASSET_SPLIT es parcial y repetible (no terminal en un solo evento). El padre
  sobrevive con Q reducida si Q_after > 0. Si Q_after == 0, transiciona a DEPLETED
  (terminal). ASSET_SPLIT no muta lifecycleStatus mientras Q_after > 0.
- ADR-006: Telemetría de posición (GPS, paradas no oficiales) EXCLUIDA del Event Store
  y del pipeline criptográfico (JCS/SHA-256/Merkle/Blockchain) en este alcance. No existe
  IN_TRANSIT como estado ni ASSET_TRANSIT_REPORTED como evento en la Fase 2 actual.
- ADR-007: Coordinación cross-stream (Fund->PhysicalAsset, PhysicalAsset->PhysicalAsset
  hijo) vía OutboxSagaCoordinator genérico + patrón Transactional Outbox. El coordinador
  es agnóstico del dominio; recibe una SagaPolicy<T> inyectada por cada caso de uso.
- ADR-008: Toda saga que cruza aggregates debe tener camino de compensación. Fallo
  permanente (tras agotar reintentos) dispara un evento de compensación en el agregado
  origen (ALLOCATION_REVERSED, ASSET_SPLIT_COMPENSATED), nunca deja el agregado en
  estado "colgado" indefinidamente. La compensación de split puede "resucitar" un
  PhysicalAsset desde DEPLETED usando el campo statusBeforeSplit sellado en el evento
  ASSET_SPLIT original.
- ADR-009: Todo comando de compensación referencia el identificador único de la
  operación que compensa (allocationId, childAssetId/splitOperationId) y el agregado
  debe rechazar una segunda compensación sobre la misma operación.
- ADR-010: El proyector, ante un evento con incomingSequence > lastProcessedSequence + 1,
  lo pasa a RETRY_PENDING con exponential backoff. Tras 4 horas sin resolver, pasa a
  QUARANTINED, dispara alerta operativa, y esa proyección específica queda pausada sin
  bloquear otros streams.
- ADR-011: asset_index es un índice técnico reconstruible (assetId -> projectionId),
  NUNCA una fuente de verdad. Debe poder reconstruirse por completo desde el Event Store.
- ADR-012: La asignación financiera Fund->PhysicalAsset es una saga en dos fases
  (ALLOCATION_REQUESTED mueve dinero a pendingAllocationAmount; ALLOCATION_CONFIRMED lo
  mueve a allocatedAmount; ALLOCATION_REVERSED lo devuelve a availableAmount). Nunca un
  evento único "FUNDS_ALLOCATED".
- ADR-013: Taxonomía de identificadores, nunca intercambiables: commandId (idempotencia
  de intención/reintento de red), externalEventId (idempotencia de hecho externo, ej.
  webhook de pasarela de pago), allocationId/refundId/childAssetId (idempotencia de
  operación de negocio interna al agregado), eventId (identidad histórica inmutable del
  evento, usada en el hash chain).
- ADR-014: En ASSET_DELIVERED, beneficiaryRef se sella SOLO en el payload del evento;
  NUNCA sobrescribe custodianRef del agregado. En ASSET_DISPATCHED, currentLocation se
  copia a lastKnownLocation antes de quedar transitorio; el sistema nunca pierde el
  último nodo logístico confirmado.
- ADR-015: La capa de lectura tiene 4 componentes con responsabilidades distintas:
  DonationProjection (vista de usuario), AssetHistoryProjection (historial detallado),
  asset_index (índice técnico), DonationAuditFacts (hechos deterministas, único
  documento que el módulo `ai` puede leer). El LLM nunca es fuente de verdad y nunca
  accede directamente al Event Store ni a documentos internos de `core`.
- ADR-016: Fund admite génesis dual: FUND_REGISTERED (camino con promesa, PLEDGED) o
  FUNDS_CLEARED directo como primer evento del stream (camino sin promesa, ej. efectivo).
  pledgedAmount es opcional en el agregado. Prohibido insertar eventos sintéticos para
  forzar uniformidad.
- ADR-017: ProjectionEventSource y ProjectionRetryScheduler son genéricos, no acoplados
  a un handler específico. Todo proyector nuevo implementa ProjectionEventHandler
  (handleEvent, getHandlerName) y se registra en la lista inyectada; el enrutamiento de
  reintentos usa el campo handlerName. Cada handler mantiene su propio checkpoint de
  secuencia por stream.
- ADR-018: El módulo `ai` opera bajo confianza cero hacia el LLM. AuditFactsDTO nunca
  contiene PII. Sanitización determinista de todo campo de texto libre antes del prompt.
  Grounding estructurado y bloqueante: el LLM declara CitedFact tipados vía salida
  estructurada; el GroundingValidator (código puro) valida cada uno por comparación
  tipada según FactType contra el AuditFactsDTO real; un solo dato no verificable
  invalida la respuesta completa. Fallback obligatorio y transparente (DonorReportDTO
  declara source=LLM_GENERATED|FALLBACK_TEMPLATE, con modelIdentifier="FALLBACK" como
  sentinel, nunca null), persistido con TTL (nextRetryAt), nunca reintentado en cada
  consulta. Trazabilidad completa: modelIdentifier, promptTemplateVersion,
  sourceFactsHash (vía HashPort con constante propia SNAPSHOT_HASH_V1, distinta de
  GENESIS_HASH). Sin Resilience4j ni circuit breaker. Single-flight vía
  ConcurrentHashMap<CacheKey, CompletableFuture<...>>, nunca trabajo bloqueante dentro
  de computeIfAbsent.
- ADR-019: El módulo `crypto.infrastructure.web3j` ancla el MerkleRoot de forma
  secuencial (un solo hilo). Máquina de estados: PENDING -> SUBMITTING -> SUBMITTED ->
  {ANCHORED | ANCHOR_MISMATCH | STUCK -> {RESUBMIT -> SUBMITTED | ABANDON -> FAILED} |
  FAILED}. nonceUsed se reserva atómicamente en Mongo (contador propio, misma
  transacción que el claim del batch) y nunca se recalcula consultando al nodo salvo en
  reconciliación de arranque. Guardia de prioridad obligatoria: ningún PENDING se
  reclama mientras exista un SUBMITTING sin txHash. Fallos deterministas (gas-cap,
  error de comunicación pre-envío) reintentan directo con el mismo nonce; fallos
  ambiguos (timeout durante el envío) exigen reconciliación contra el nodo antes de
  reenviar. ANCHORED exige status==1 + N confirmaciones + coincidencia exacta de bytes
  del root emitido contra merkleRoot (Numeric.hexStringToByteArray, nunca .getBytes()).
  resolveStuckBatch(RESUBMIT|ABANDON, maxFeePerGas) es exclusivamente manual, sin RBF
  automático, sin invocación desde ningún componente programado.

ARQUITECTURA DE MÓDULOS (Maven, dependencias unidireccionales):
    contracts   <- (sin dependencias de infraestructura; solo interfaces + DTOs de puertos)
    core        -> depende de contracts
    crypto      -> depende de contracts (implementa HashPort); NUNCA depende de core completo
    ai          -> depende de contracts (implementa/consume AuditFactsPort); NUNCA depende
                   de core completo ni de clases internas de infraestructura de core

REGLAS DE CÓDIGO NO NEGOCIABLES:
1. `core.domain.*` es Java puro. Cero anotaciones de Spring, cero anotaciones de
   MongoDB (@Document, @Field, @DBRef prohibidas en esta capa).
2. Constructor injection siempre. @Autowired en campos: prohibido.
3. Prefiere `record` de Java 21 para eventos, payloads y DTOs inmutables.
4. Ningún Aggregate conoce MongoDB, Web3j, ni ningún detalle de infraestructura.
5. Ningún Aggregate genera hashes ni canonicaliza JSON — eso pertenece al módulo `crypto`,
   invocado desde `application` vía HashPort, nunca desde `domain`.
6. Un Aggregate nunca persiste directamente: emite eventos no confirmados
   (uncommitted events); el Application Handler los pasa al EventStorePort.
7. Todo invariante de negocio debe fallar con una excepción de dominio específica y
   nombrada (ej. `InsufficientAvailableFundsException`, `InvalidAssetTransitionException`),
   nunca con una excepción genérica.
8. Toda clase de Aggregate, Value Object o Domain Event debe llevar un comentario o
   Javadoc con la forma: `// Ref: ADR-XXX` señalando qué decisión justifica su forma.
9. No implementes nada que no esté explícitamente en el contrato de la tarea actual.
   Si crees que falta algo, repórtalo — no lo agregues por iniciativa propia.

SI DETECTAS UNA CONTRADICCIÓN, UNA AMBIGÜEDAD, O UN CASO NO CUBIERTO POR LOS ADRs
DURANTE LA IMPLEMENTACIÓN: DETENTE. No lo resuelvas por tu cuenta ni "interpretes la
intención". Documenta el conflicto exacto (qué ADR, qué línea del contrato, qué caso
no cubre) y espera instrucción.
```

---

## 2. Reglas de Ejecución Genéricas (Definition of Done aplicable a toda tarea)

Toda tarea de este plan se considera terminada solo si cumple **las siete condiciones siguientes**, sin excepción:

1. **Compila** sin warnings de dependencias circulares entre módulos.
2. **Cero dependencias de infraestructura en `domain`** — verificable con una regla de ArchUnit (ver Tarea 1).
3. **Cada invariante de negocio tiene al menos un test unitario que la viola deliberadamente** y verifica que se lance la excepción correcta (test negativo), además de los tests de camino feliz.
4. **Cada clase pública lleva su referencia a ADR** en Javadoc.
5. **No introduce ningún campo, evento o comando que no esté explícitamente listado en el contrato de la tarea.**
6. **El agente entrega un resumen de decisiones tomadas durante la implementación** (nombres de excepciones elegidos, detalles de tipos concretos para Value Objects, etc.) para revisión humana — no las oculta dentro del código sin mencionarlas.
7. **No hay TODOs, mocks permanentes, ni placeholders sin marcar explícitamente** como tales con una razón y una tarea de seguimiento referenciada.

---

## 3. Mapa de ADRs → Módulo/Paquete (referencia rápida)

| ADR | Paquete principal afectado |
|---|---|
| 001, 002, 003, 005 | `core.domain.physicalasset` |
| 004, 012, 016 | `core.domain.fund` |
| 006 | Exclusión — no genera paquete propio en Fase 2 |
| 007, 008, 009 | `core.application.saga` |
| 010, 011 | `core.infrastructure.projection` |
| 013 | `core.domain.event` |
| 014 | `core.domain.physicalasset` (eventos `ASSET_DISPATCHED`, `ASSET_DELIVERED`) |
| 015 | `core.infrastructure.projection`, `contracts` (AuditFactsPort), `ai` |
| 017 | `core.infrastructure.projection` (framework genérico de handlers) |
| 018 | `ai.*` (dominio, aplicación e infraestructura del generador de narrativas) |
| 019 | `crypto.infrastructure.web3j`, `crypto.application.service`, `crypto.infrastructure.persistence.mongo` (extensión de `MerkleBatch`) |

---


## 5. Orden de ejecución y puntos de aprobación humana obligatoria

```
Tarea 0 ──► [APROBACIÓN HUMANA] ──► Tarea 1 ──┐
                                                ├──► Tarea 3 ──► [APROBACIÓN] ──► Tarea 4 ──► [APROBACIÓN]
                                    Tarea 2 ────┘                                                  │
                                                                                                     ▼
                                                                                              Tarea 5 (Fund)
                                                                                                     │
                                                                                          [APROBACIÓN] ▼
                                                                                              Tarea 6
                                                                                                     │
                                                                                          [APROBACIÓN] ▼
                                                                                              Tarea 7 (Sagas)
                                                                                                     │
                                                                              ┌──────────────────────┴───┐
                                                                       [APROBACIÓN]                [APROBACIÓN]
                                                                              ▼                           ▼
                                                                        Tarea 8 (crypto)            Tarea 9 (Mongo)
                                                                              │                           │
                                                                       [APROBACIÓN]                [APROBACIÓN]
                                                                              ▼                           ▼
                                                                        Tarea 13 (Web3j)          Tarea 10 (Proyecciones)
                                                                              │                                 │
                                                                              │                          [APROBACIÓN]
                                                                              │                                 ▼
                                                                              │                          Tarea 11 (ai: AuditFacts)
                                                                              │                                 │
                                                                              │                          [APROBACIÓN]
                                                                              │                                 ▼
                                                                              │                          Tarea 12 (ai: NarrativeGenerator)
                                                                              │                                 │
                                                                              └────────────────┬────────────────┘
                                                                                          [APROBACIÓN]
                                                                                                 ▼
                                                                                          Tarea 14 (app: Bootstrap)
```

Ningún agente debe saltarse una flecha de aprobación. Si un agente completa una tarea y, sin que un humano la haya revisado, intenta iniciar la siguiente, el humano supervisor debe detenerlo — esto reproduce en la ejecución la misma disciplina de "espera aprobación cuando la decisión sea arquitectónicamente significativa" que rigió todo el diseño de la Fase 1.

---
---