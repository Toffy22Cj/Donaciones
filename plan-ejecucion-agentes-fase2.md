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

---

## 4. Backlog Secuencial de Tareas

### TAREA 0 — Scaffolding Multi-módulo Maven

**Estado:** ✅ **COMPLETADA**

```
TAREA: Scaffolding del proyecto multi-módulo Maven.

CONTEXTO: Arrancamos el repositorio. Necesitamos cuatro módulos Maven con
dependencias unidireccionales estrictas: contracts <- core, contracts <- crypto,
contracts <- ai. Ninguno de estos módulos debe llamarse ni referenciar el nombre
comercial del proyecto; usa el groupId `com.traceability` y artifactIds
`contracts`, `core`, `crypto`, `ai`.

ENTREGABLES:
1. pom.xml raíz (packaging pom) declarando los 4 módulos hijos.
2. contracts/pom.xml — sin dependencias de Spring Boot, sin MongoDB driver, sin
   Web3j. Solo Java estándar (y opcionalmente javax/jakarta.validation si se
   necesita anotar contratos, pero NO lo agregues salvo que lo pidas primero).
3. core/pom.xml — depende de `contracts`. Incluye spring-boot-starter,
   spring-data-mongodb, JUnit 5, Testcontainers (mongodb), Lombok (scope
   provided/optional).
4. crypto/pom.xml — depende de `contracts` ÚNICAMENTE (no de `core`). Incluye
   Web3j y una librería JCS/RFC 8785 (busca la dependencia Maven correcta;
   si no estás seguro de cuál usar, detente y pregunta antes de elegir una).
5. ai/pom.xml — depende de `contracts` ÚNICAMENTE (no de `core`). Incluye
   spring-ai-core (verifica el artifactId exacto vigente; si no estás seguro,
   detente y pregunta).
6. Un test de arquitectura (ArchUnit) en `core` que falle el build si:
   a) cualquier clase bajo `core.domain` importa algo de org.springframework.*
      o com.mongodb.* o org.bson.*
   b) el módulo `ai` o `crypto` importan cualquier clase bajo `core.*`
      (esto se valida en su propio módulo, con dependencia de test hacia core
      solo si es estrictamente necesario para el chequeo — si no es posible
      sin violar el propio aislamiento, repórtalo y omite este sub-punto).

QUÉ NO HACER:
- No escribas ninguna clase de dominio todavía. Esta tarea es solo esqueleto
  y build.
- No elijas versión de Spring Boot distinta a 3.4.4 sin preguntar.
- No agregues Lombok como dependencia de `contracts`, `crypto` ni `ai`.

DEFINITION OF DONE: `mvn clean install` desde la raíz compila los 4 módulos
sin errores, y el test de ArchUnit en `core` pasa (verificando el punto 6a).
Entrega un resumen de qué librería exacta elegiste para JCS/RFC 8785 y para
Spring AI, con versión y justificación breve, para aprobación.
```

---

### TAREA 1 — `core.domain.shared`: AggregateRoot base, EventStream, contratos de evento

**Estado:** ✅ **COMPLETADA**

```
TAREA: Implementar las clases base del modelo de dominio de Event Sourcing,
compartidas por todos los Aggregate Roots del sistema.

CONTEXTO / ADRs: ADR-013 (taxonomía de identificadores). Estas clases son la
base mecánica sobre la que se construirán PhysicalAsset y Fund; no contienen
lógica de negocio de ningún dominio específico.

ENTREGABLES (paquete `core.domain.shared` y `core.domain.event`):

1. `AggregateRoot<ID, E extends DomainEvent>` (clase abstracta):
   - Mantiene: `ID id`, `long currentVersion`, `List<E> uncommittedEvents`
     (mutable internamente, expuesta como vista inmutable).
   - Método `protected void raise(E event)`: agrega a uncommittedEvents Y
     aplica el evento al estado interno invocando `apply(event)`.
   - Método abstracto `protected abstract void apply(E event)`: cada
     Aggregate concreto implementa cómo cada tipo de evento muta su estado.
   - Método `List<E> pullUncommittedEvents()`: devuelve y limpia la lista
     (para que el Application Handler los tome exactamente una vez).
   - Método estático de fábrica para reconstrucción vía replay:
     `static <T extends AggregateRoot<?,?>> T rehydrate(T instance,
     List<? extends DomainEvent> history)` que aplica cada evento en orden
     SIN agregarlo a uncommittedEvents (evento histórico, no nuevo).

2. `DomainEvent` (interfaz o clase base sellada — decide con `sealed interface`
   de Java 21 si encaja bien, o interfaz simple si prefieres composición):
   - Debe exponer como mínimo: `String eventId()`, `String streamId()`,
     `long sequence()`, `String eventType()`, `int schemaVersion()`,
     `Instant occurredAt()`, `Instant recordedAt()`, `String actorRef()`.
   - NO incluyas `previousHash`/`eventHash` aquí — esos son responsabilidad
     del módulo `crypto` al momento de persistir, no del dominio. Repórtalo
     si tienes dudas sobre dónde trazar esta línea.

3. `EventStream` (Value Object o clase simple):
   - Representa el límite de orden/concurrencia: `streamId`, `aggregateType`,
     `currentVersion`.
   - Método de validación: `void validateNextSequence(long incoming)` que
     lanza `SequenceConflictException` si `incoming != currentVersion + 1`.

4. Excepciones base en `core.domain.shared.exceptions`:
   - `DomainInvariantViolationException` (clase base abstracta para todas las
     excepciones de invariante de negocio).
   - `SequenceConflictException` (para el conflicto de concurrencia optimista).
   - `AggregateNotFoundException`.

QUÉ NO HACER:
- No implementes todavía ningún evento concreto de Fund ni de PhysicalAsset.
- No agregues lógica de hashing ni de canonicalización aquí.
- No hagas que `AggregateRoot` conozca MongoDB de ninguna forma.

TESTS REQUERIDOS:
- Test que verifica que `raise()` agrega el evento a uncommittedEvents Y
  muta el estado (usando un Aggregate de prueba mínimo).
- Test que verifica que `rehydrate()` reconstruye el estado sin dejar
  eventos en uncommittedEvents.
- Test que verifica que `validateNextSequence` lanza excepción ante secuencia
  no consecutiva (probar con salto y con repetición).

DEFINITION OF DONE: todo lo anterior compila, los tests pasan, y ninguna
clase de este paquete importa nada fuera de `java.*`.
```

---

### TAREA 2 — `contracts`: puertos de salida compartidos

**Estado:** ✅ **COMPLETADA**

```
TAREA: Definir los contratos (interfaces + DTOs) que cruzan las fronteras de
módulo Maven, sin ninguna implementación.

CONTEXTO / ADRs: ADR-015 (aislamiento de `ai` respecto a infraestructura de
`core`). Este módulo es deliberadamente mínimo: solo interfaces y records.

ENTREGABLES (módulo `contracts`, paquete `com.traceability.contracts`):

1. `HashPort` (interfaz): método `String canonicalizeAndHash(Map<String,Object>
   eventData, String previousHash)`. No definas la implementación — eso es
   tarea de `crypto` en una fase posterior.
2. `AuditFactsPort` (interfaz): método `Optional<AuditFactsDTO>
   getAuditFacts(String donationId)`.
3. `AuditFactsDTO` (record): SOLO campos de datos deterministas de auditoría
   (ej. lista de transiciones con duración y flag de anomalía). No incluyas
   ningún campo que dependa de la estructura interna de MongoDB. Si no tienes
   certeza de qué campos exactos debe llevar, defínelo con el mínimo
   razonable basado en el ejemplo ya discutido (from, to, durationSeconds,
   expectedMaximumSeconds, anomaly) y repórtalo para ajuste.

QUÉ NO HACER:
- No implementes ninguna de estas interfaces en este módulo.
- No agregues ninguna dependencia de Spring, MongoDB, ni Web3j a `contracts`.
- No definas aquí el EventStorePort — ese vive dentro de `core.application.port.out`
  porque es específico del ciclo de vida de Aggregates, no un contrato cruzado
  entre módulos Maven externos.

DEFINITION OF DONE: el módulo `contracts` compila de forma completamente
aislada (sin ninguna dependencia externa más que el JDK), y `core`, `crypto`
y `ai` pueden declarar dependencia hacia él sin arrastrar nada más.
```

---

### TAREA 3 — `core.domain.physicalasset`: Aggregate `PhysicalAsset`

**Estado:** ✅ **COMPLETADA**

Esta es la tarea de mayor riesgo del backlog inicial — es el Aggregate que absorbió más iteraciones de diseño. El prompt es deliberadamente exhaustivo.

```
TAREA: Implementar el Aggregate Root `PhysicalAsset` completo, con todos sus
comandos, eventos, invariantes y mecanismo de compensación.

CONTEXTO / ADRs: ADR-002, ADR-003, ADR-005, ADR-006, ADR-008, ADR-009, ADR-014.
Lee estos seis ADRs del Prompt Maestro antes de escribir una sola línea.

ESTADO INTERNO DEL AGREGADO (memoria reconstruida por replay):
    assetId: String
    assetType: String
    quantity: BigDecimal            // Q actual, derivado de replay
    unitOfMeasure: String           // inmutable tras registro
    lifecycleStatus: enum { REGISTERED, DISPATCHED, RECEIVED, DELIVERED, DEPLETED }
    currentLocation: String | null  // null solo entre DISPATCHED y RECEIVED/DELIVERED
    lastKnownLocation: String       // nunca null tras el registro; ver ADR-014
    custodianRef: String
    parentAssetRef: String | null
    rootAssetRef: String            // = assetId propio si es raíz
    allocationId: String | null       // asignación financiera propia, si es raíz de linaje
    sourceAllocationId: String | null // heredada, si es hijo de split
    currentVersion: long

COMANDOS Y SUS CONTRATOS EXACTOS (tabla ya validada, no reinterpretar):

1. RegisterPhysicalAsset (génesis, expectedVersion = 0)
   Precondiciones: quantity > 0, unitOfMeasure no nulo/vacío.
   Emite: ASSET_REGISTERED
     payload: assetId, assetType, quantity, unitOfMeasure, currentLocation,
              custodianRef, parentAssetRef (nullable), rootAssetRef,
              allocationId (nullable), sourceAllocationId (nullable)
   Postcondición: status = REGISTERED, setea todos los campos anteriores.

2. DispatchPhysicalAsset
   Precondición: status IN {REGISTERED, RECEIVED}. currentLocation != null.
   Emite: ASSET_DISPATCHED
     payload: carrierRef, previousLocation (= currentLocation antes de mutar)
   Postcondición: status = DISPATCHED.
                  lastKnownLocation = currentLocation (valor previo).
                  currentLocation = null.
                  custodianRef = carrierRef.
   REGLA CRÍTICA (ADR-014): lastKnownLocation NUNCA se sobrescribe con null.
   Solo se actualiza cuando currentLocation tiene un valor confirmado real.

3. ReceivePhysicalAsset
   Precondición: status == DISPATCHED.
   Emite: ASSET_RECEIVED
     payload: facilityLocation, receiverRef
   Postcondición: status = RECEIVED.
                  currentLocation = facilityLocation.
                  lastKnownLocation = facilityLocation.
                  custodianRef = receiverRef.

4. TransferAssetCustody
   Precondición: status NOT IN {DELIVERED, DEPLETED}.
                 newCustodianRef != custodianRef actual (InvariantException si es igual;
                 esto es DISTINTO de idempotencia por commandId, que se resuelve en la
                 capa de aplicación ANTES de invocar este método del Aggregate — no
                 dupliques esa lógica aquí).
   Emite: ASSET_CUSTODY_TRANSFERRED
     payload: previousCustodianRef, newCustodianRef
   Postcondición: custodianRef = newCustodianRef. Nada más cambia.

5. SplitPhysicalAsset
   Precondición: status IN {REGISTERED, RECEIVED}.
                 0 < extractedQuantity <= quantity actual.
                 childAssetId != assetId propio.
   Emite: ASSET_SPLIT
     payload: childAssetId, extractedQuantity, unitOfMeasure (= la propia),
              parentQuantityBefore, parentQuantityAfter,
              statusBeforeSplit (= this.lifecycleStatus ANTES de aplicar el split;
              ver ADR-008, necesario para la eventual compensación),
              childLocation (= this.currentLocation, heredado),
              childCustodianRef (= this.custodianRef, heredado),
              rootAssetRef (heredado o = assetId propio si este es la raíz)
   Postcondición: quantity -= extractedQuantity.
                  Si quantity resultante == 0:
                      además emite ASSET_DEPLETED (payload: previousQuantity)
                      status = DEPLETED
                  Si quantity resultante > 0:
                      status NO cambia (sigue REGISTERED o RECEIVED, el que era).
   NOTA: este comando NO crea el hijo. El hijo nace vía un
   RegisterPhysicalAsset separado, disparado por el OutboxSagaCoordinator
   (Tarea 7, bloqueada). Este Aggregate solo emite el hecho de la extracción.

6. CompensateAssetSplit (comando de sistema, invocado solo por el
   OutboxSagaCoordinator tras timeout, nunca por un usuario)
   Precondición: existe en el historial un ASSET_SPLIT con
                 childAssetId == el referenciado por el comando, Y ese
                 childAssetId no aparece ya en un ASSET_SPLIT_COMPENSATED
                 previo (invariante de no-doble-compensación, ADR-009).
                 status != DELIVERED (no se compensa un split sobre un
                 asset ya entregado — si esto ocurre es un error de saga
                 más profundo; lanza excepción específica, no lo asumas
                 silenciosamente).
   Emite: ASSET_SPLIT_COMPENSATED
     payload: childAssetId (referencia a la operación que se revierte),
              reintegratedQuantity
   Postcondición: quantity += reintegratedQuantity.
                  Si el status actual es DEPLETED:
                      status = statusBeforeSplit (leído del evento
                      ASSET_SPLIT original correspondiente a ese childAssetId
                      durante el replay — el Aggregate necesita poder acceder
                      a ese dato histórico; considéralo al diseñar cómo el
                      método apply() indexa splits pendientes de compensar
                      internamente durante la reconstrucción, por ejemplo
                      manteniendo un Map<childAssetId, statusBeforeSplit>
                      en memoria transitoria mientras se hace replay).

7. DeliverPhysicalAsset
   Precondición: status IN {DISPATCHED, RECEIVED}.
   Emite: ASSET_DELIVERED
     payload: finalCustodianRef, beneficiaryRef, locationRef,
              evidenceRef (nullable), deliveredAt
   Postcondición: status = DELIVERED (terminal).
                  currentLocation = locationRef.
                  lastKnownLocation = locationRef.
                  custodianRef = finalCustodianRef.
                  ****beneficiaryRef NO se asigna a custodianRef. Vive
                  únicamente en el payload del evento. Esta es la regla
                  ADR-014 más fácil de romper por accidente — revísala
                  dos veces.****

EXCEPCIONES DE DOMINIO A CREAR (una por cada violación, nombradas
específicamente, todas extendiendo DomainInvariantViolationException):
- InvalidAssetTransitionException (transición de estado no permitida)
- InsufficientQuantityException (split con extractedQuantity > quantity)
- RedundantCustodyTransferException (newCustodianRef == custodianRef actual)
- InvalidSplitTargetException (childAssetId == assetId propio)
- DuplicateCompensationException (segunda compensación sobre mismo childAssetId)
- AssetTerminalStateException (comando inválido sobre DELIVERED o DEPLETED)

QUÉ NO HACER (lista de errores ya cometidos y corregidos en el diseño —
NO LOS REPITAS):
- NO vacíes currentLocation sin antes copiar su valor a lastKnownLocation.
- NO asignes beneficiaryRef a custodianRef en DeliverPhysicalAsset.
- NO trates ASSET_SPLIT como evento terminal — el padre sobrevive si Q > 0.
- NO cambies lifecycleStatus dentro de SplitPhysicalAsset salvo el caso Q==0.
- NO implementes IN_TRANSIT como estado ni ASSET_TRANSIT_REPORTED como evento.
- NO mezcles la deduplicación por commandId (capa de aplicación) con la
  invariante de custodia redundante (capa de dominio) dentro de este Aggregate.
- NO generes el hash del evento aquí. Eso es post-procesamiento en `crypto`.

TESTS UNITARIOS REQUERIDOS (JUnit 5, sin Spring, sin Mongo — Aggregate puro):
Camino feliz, uno por comando (7 tests mínimo).
Camino de fallo, uno por excepción listada arriba (6 tests mínimo).
Test específico de replay: construir un historial de eventos manualmente
  (REGISTERED -> DISPATCHED -> RECEIVED -> SPLIT parcial -> SPLIT que agota
  a DEPLETED -> COMPENSATED) y verificar que el estado final reconstruido
  es exactamente el esperado, incluyendo la "resurrección" desde DEPLETED.
Test específico de ADR-014: verificar que tras DISPATCHED,
  lastKnownLocation conserva el valor pre-despacho, y que tras DELIVERED,
  custodianRef NUNCA es igual a beneficiaryRef.

DEFINITION OF DONE: todos los tests anteriores existen y pasan. El agente
entrega, además del código, una tabla de trazabilidad comando -> ADR(s)
aplicados -> test(s) que lo cubren, para revisión humana antes de continuar
a la Tarea 4.
```

---

### TAREA 4 — Eventos y Payloads formales de `PhysicalAsset`

**Estado:** ✅ **COMPLETADA**

```
TAREA: Formalizar como `record` de Java 21 cada payload de evento de
PhysicalAsset ya usado en la Tarea 3, y verificar que son serializables
de forma determinista (requisito para la canonicalización JCS futura).

ENTREGABLES (paquete `core.domain.physicalasset.payloads`):
- AssetRegisteredPayload, AssetDispatchedPayload, AssetReceivedPayload,
  AssetCustodyTransferredPayload, AssetSplitPayload, AssetDepletedPayload,
  AssetSplitCompensatedPayload, AssetDeliveredPayload.
Cada uno como record inmutable, con exactamente los campos listados en la
Tarea 3 para su evento correspondiente. Ningún campo adicional.

QUÉ NO HACER:
- No agregues campos "por si acaso" (ej. no agregues un campo `notes` libre
  que no fue discutido — si crees que hace falta, repórtalo, no lo agregues).
- No uses tipos mutables (List, Map mutable) sin envolver en
  Collections.unmodifiableList/Map si es indispensable usarlos.

DEFINITION OF DONE: cada record tiene un test de serialización/deserialización
JSON round-trip (usando Jackson, ya que Spring Boot lo trae) que verifica que
el orden de campos no afecta la igualdad del objeto reconstruido (esto es
preparación para la Tarea de `crypto`, que exigirá canonicalización JCS —
no la implementes aquí, solo verifica que el payload es "canonicalizable"
sin ambigüedad).
```

---

### TAREA 5 — `core.domain.fund`: Aggregate `Fund`

**Estado:** ✅ **COMPLETADA**

TAREA: Implementar el Aggregate Root `Fund` completo, con todos sus comandos, eventos e invariantes.
CONTEXTO / ADRs: ADR-004, ADR-012, ADR-013, ADR-016.
DETALLES: Implementación de comandos `registerFund`, `clearFundsGenesis`, `requestAllocation`, `confirmAllocation`, `reverseAllocation`, `refund`. Verificación de invariantes como que `refundedAmount + refundAmount <= clearedAmount` y el manejo de génesis dual.
DEFINITION OF DONE: Tests unitarios exhaustivos para caminos felices y excepciones de dominio.

---

### TAREA 6 — Eventos y Payloads formales de `Fund`

**Estado:** ✅ **COMPLETADA**

TAREA: Formalizar como `record` inmutables de Java 21 los payloads de eventos de `Fund`.
DETALLES: `FundRegisteredPayload`, `FundsClearedPayload`, `AllocationRequestedPayload`, `AllocationConfirmedPayload`, `AllocationReversedPayload`, `FundsRefundedPayload`.

---

### TAREA 7 — `core.application.saga`: `OutboxSagaCoordinator` genérico + `SagaPolicy<T>`

**Estado:** ✅ **COMPLETADA**

TAREA: Implementar patrón Outbox transaccional y un coordinador de Sagas agnóstico.
CONTEXTO: ADR-007, ADR-008, ADR-009.
DETALLES: Implementación de `OutboxMessage`, `OutboxStatus`, `SagaPolicy` para definir políticas de compensación. `OutboxSagaCoordinator` con scheduling/polling.

---

### TAREA 8 — `crypto`: `JcsHashAdapter` + `MerkleTree`

**Estado:** ✅ **COMPLETADA**

TAREA: Implementar la criptografía, canonicalización y agrupación en Merkle Trees.
DETALLES: Implementación de `JcsHashAdapter` usando RFC 8785 (JCS) y SHA-256 para encadenar los eventos con `previousHash`. Implementación de `MerkleTree` para generar la raíz del bloque.

---

### TAREA 9 — `core.infrastructure.persistence.mongo`: `EventStorePort` + Outbox

**Estado:** ✅ **COMPLETADA**

TAREA: Adaptador de MongoDB para persistencia transaccional del Event Store y Outbox.
DETALLES: `MongoEventStoreAdapter` con control de concurrencia optimista (índice único en `streamId` y `sequence`). Configuración de `MongoTransactionManager` para atomicidad, verificado con Testcontainers.

---

### TAREA 10 — `core.infrastructure.projection`: Proyectores y Reconstrucción CQRS

**Estado:** ✅ **COMPLETADA**

TAREA: Implementar sistema CQRS y proyecciones usando MongoDB Change Streams.
CONTEXTO: ADR-010, ADR-011.
DETALLES: `DonationProjectionHandler`, control de idempotencia real (`incomingSequence <= lastProcessedSequence`). Protocolo de reconstrucción en 3 pasos (stop/token/bulk/resume) para prevenir pérdida de eventos. Scheduler de cuarentena y reintentos para `SequenceGapException`. Todo validado con Testcontainers simulando la condición de carrera del Change Stream y entrega concurrente.

---

### TAREA 11 — Implementación de `DonationAuditFacts` y refactorización genérica de Proyecciones

**Estado:** ✅ **COMPLETADA**

TAREA: Refactorizar `ProjectionEventSource` y `ProjectionRetryScheduler` para soportar múltiples handlers genéricos, e implementar `DonationAuditFactsHandler`.
CONTEXTO: ADR-015.
DETALLES: Extraer interfaz `ProjectionEventHandler`. Implementar `AuditThresholdProperties`, `DonationAuditFactsDocument`, y `AuditFactsPortImpl` para el módulo `ai`.

---

### TAREA 12 — `ai`: `NarrativeGenerator` (consumo de `AuditFactsPort` + Spring AI)

**Estado:** ✅ **COMPLETADA**
**Nota:** esta tarea NO tiene contrato de agente todavía. Requiere una pasada de
Modo de Arquitectura (responsabilidad, invariantes, manejo de fallback/timeout/costes,
defensa contra prompt injection, versionado del modelo) antes de redactarse como
prompt ejecutable. Ver sección 16 del Prompt Maestro original — esos principios
siguen sin formalizarse como ADR.

---

### TAREA 13 — `crypto.infrastructure.web3j`: `BlockchainAnchorAdapter`

**Estado:** ✅ **COMPLETADA**

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
                                                                                                          │
                                                                                                   [APROBACIÓN]
                                                                                                          ▼
                                                                                                    Tarea 11 (ai: AuditFacts)
                                                                                                          │
                                                                                                   [APROBACIÓN]
                                                                                                          ▼
                                                                                                    Tarea 12 (ai: NarrativeGenerator)
```

Ningún agente debe saltarse una flecha de aprobación. Si un agente completa una tarea y, sin que un humano la haya revisado, intenta iniciar la siguiente, el humano supervisor debe detenerlo — esto reproduce en la ejecución la misma disciplina de "espera aprobación cuando la decisión sea arquitectónicamente significativa" que rigió todo el diseño de la Fase 1.
