# Diagramas de Arquitectura — Sistema "PaxFide" (Fase 2 Completada)

A continuación, se presentan los diagramas que detallan la arquitectura implementada y verificada durante la Fase 2.

## 1. Arquitectura General y Flujo de Event Sourcing / CQRS

Este diagrama muestra el flujo desde la ingesta de un comando hasta la persistencia inmutable y su eventual materialización en las proyecciones y auditorías.

> **Nota:** no existe todavía capa HTTP/API expuesta (Fase 3, sin definir formalmente). El "Comando" de entrada hoy es programático — la Tarea 14 (`app`) ensambla `core`, `crypto` y `ai` en un único `ApplicationContext`, verificado con un smoke test transaccional real, pero sin ningún endpoint público todavía.

```mermaid
flowchart TD
    %% Entradas
    User((Comando programático — sin API HTTP en esta fase))
    
    subgraph Bootstrap [app — Bootstrap / Ensamblaje]
        AppCtx[ApplicationContext único]
    end

    subgraph Application [Application Layer]
        Handler[Command Handlers / Application Services]
        OutboxSaga[Outbox Saga Coordinator]
        ProjHandler[Projection Handlers]
    end

    subgraph Domain [Domain Layer]
        Fund[Fund Aggregate]
        Asset[PhysicalAsset Aggregate]
        Events[Domain Events]
    end

    subgraph Infrastructure [Infrastructure Layer]
        EventStore[(Mongo Event Store)]
        Outbox[(Mongo Outbox)]
        ChangeStream((Change Stream))
        ProjDB[(Mongo Projections)]
    end

    subgraph External Modules [Isolated Modules]
        Crypto[Crypto Module]
        AI[AI Module]
    end

    %% Ensamblaje
    AppCtx -.->|ensambla, MongoTransactionManager compartido| Handler
    AppCtx -.->|ensambla| Crypto
    AppCtx -.->|ensambla| AI

    %% Relaciones de comando
    User -->|Envía Comando| Handler
    Handler -->|Invoca| Fund
    Handler -->|Invoca| Asset
    Fund -->|Emite| Events
    Asset -->|Emite| Events
    
    %% Persistencia Atómica
    Handler -->|Transaccional| EventStore
    Handler -->|Transaccional| Outbox
    
    %% Sagas
    Outbox -->|Polling / Eventual| OutboxSaga
    OutboxSaga -->|Dispara compensación o acción cruzada| Handler
    
    %% Crypto
    Handler -.->|Canonicaliza y Hashea| Crypto
    
    %% Proyecciones (CQRS)
    EventStore -->|Notifica| ChangeStream
    ChangeStream -->|Consume| ProjHandler
    ProjHandler -->|Materializa Vistas| ProjDB
    
    %% Módulo AI
    ProjDB -.->|AuditFactsPort| AI
```

---

## 2. Pipeline Criptográfico y Anclaje en Blockchain (Web3j)

Este diagrama detalla cómo los eventos de dominio se transforman en una cadena inmutable y se anclan en Polygon/Ganache usando árboles de Merkle. La máquina de estados del anclaje sigue ADR-019 en su totalidad — no se simplifica.

```mermaid
flowchart LR
    subgraph Core
        Ev[Domain Event]
    end

    subgraph Crypto Module
        Canonicalizer[JCS RFC 8785]
        SHA256[SHA-256 Hasher]
        Merkle[Merkle Tree Builder]
        NonceCtr[(Web3NonceCounterDocument)]
        MongoBatch[(MerkleBatch MongoDB)]
        Adapter[Web3j Anchor Adapter — nonce forzado, nunca autoresuelto]
        Scheduler[Blockchain Anchor Scheduler — single-thread, guardia de prioridad]
        Poller[Anchor Confirmation Poller]
        Manual[["resolveStuckBatch — comando MANUAL exclusivamente"]]
    end

    subgraph Blockchain
        Polygon[(Polygon / Ganache)]
        SmartContract[AnchorRegistry.sol]
    end

    %% Generación de Hash
    Ev -->|Datos de negocio| Canonicalizer
    Canonicalizer -->|JSON Canónico estricto| SHA256
    SHA256 -->|eventHash| Merkle
    
    %% Agrupamiento
    Merkle -->|Genera Merkle Root| MongoBatch
    MongoBatch -->|PENDING| Scheduler
    
    %% Reserva atómica de nonce + claim (misma transacción Mongo)
    Scheduler -->|Guardia: SUBMITTING sin txHash tiene prioridad absoluta| Scheduler
    Scheduler <-.->|claim + nonce, transacción atómica única| NonceCtr
    Scheduler -->|PENDING → SUBMITTING, nonceUsed persistido ANTES del envío| MongoBatch
    
    %% Envío
    Scheduler -->|delega envío| Adapter
    Adapter -->|Envía Tx con nonce forzado| Polygon
    Polygon -->|Llama| SmartContract
    Adapter -->|SUBMITTING → SUBMITTED, txHash persistido| MongoBatch
    Adapter -.->|GasCapExceededException / BlockchainNodeCommunicationException: determinista, reintento directo mismo nonce| MongoBatch
    Adapter -.->|BlockchainAnchorTimeoutException: ambiguo, reconciliar contra el nodo antes de reenviar| MongoBatch
    
    %% Poller y Confirmación (D4 de ADR-019)
    Poller -->|Monitorea receipts de SUBMITTED| Polygon
    Polygon -.->|status==1 + N confirmaciones + root exacto| Poller
    Poller -->|ANCHORED| MongoBatch
    Poller -->|root no coincide → ANCHOR_MISMATCH| MongoBatch
    Poller -->|status==0 revert → FAILED directo| MongoBatch
    Poller -->|sin receipt tras timeout → STUCK| MongoBatch
    
    %% Escalada y recuperación manual
    MongoBatch -.->|SUBMITTING sin txHash tras timeout → STUCK, solo alerta| MongoBatch
    MongoBatch -->|STUCK requiere intervención| Manual
    Manual -->|RESUBMIT: mismo nonceUsed, gas manual| MongoBatch
    Manual -->|ABANDON| MongoBatch
```

**Estados terminales/intermedios del `MerkleBatch`:** `PENDING → SUBMITTING → SUBMITTED → {ANCHORED | ANCHOR_MISMATCH | STUCK → {RESUBMIT → SUBMITTED | ABANDON → FAILED} | FAILED}`. `resolveStuckBatch` nunca se invoca automáticamente desde ningún componente programado — es exclusivamente un comando manual (ADR-019, D6).

---

## 3. Máquinas de Estado de los Aggregates

### PhysicalAsset (Trazabilidad Logística)
```mermaid
stateDiagram-v2
    [*] --> REGISTERED : RegisterPhysicalAsset
    REGISTERED --> DISPATCHED : DispatchPhysicalAsset
    REGISTERED --> REGISTERED : SplitPhysicalAsset (Si Q > 0)
    REGISTERED --> REGISTERED : TransferAssetCustody (no cambia estado)
    REGISTERED --> DEPLETED : SplitPhysicalAsset (Si Q = 0)
    
    DISPATCHED --> RECEIVED : ReceivePhysicalAsset
    DISPATCHED --> DELIVERED : DeliverPhysicalAsset
    DISPATCHED --> DISPATCHED : TransferAssetCustody (no cambia estado)
    
    RECEIVED --> DISPATCHED : DispatchPhysicalAsset
    RECEIVED --> DELIVERED : DeliverPhysicalAsset
    RECEIVED --> RECEIVED : SplitPhysicalAsset (Si Q > 0)
    RECEIVED --> RECEIVED : TransferAssetCustody (no cambia estado)
    RECEIVED --> DEPLETED : SplitPhysicalAsset (Si Q = 0)
    
    DELIVERED --> [*]
    note right of DELIVERED
        Terminal. Ningún comando aceptado.
    end note

    DEPLETED --> COMPENSATED_STATE : CompensateAssetSplit (Rollback Saga)
    state COMPENSATED_STATE <<choice>>
    COMPENSATED_STATE --> REGISTERED : si statusBeforeSplit == REGISTERED
    COMPENSATED_STATE --> RECEIVED : si statusBeforeSplit == RECEIVED
```

### Fund (Trazabilidad Financiera)
```mermaid
stateDiagram-v2
    [*] --> PLEDGED : RegisterFund (Génesis con promesa)
    [*] --> CLEARED : ClearFunds (Génesis directo)
    PLEDGED --> CLEARED : ClearFunds
    
    state CLEARED {
        [*] --> AVAILABLE
        AVAILABLE --> PENDING_ALLOCATION : RequestAllocation (Fase 1 Saga)
        PENDING_ALLOCATION --> ALLOCATED : ConfirmAllocation (Fase 2 Saga)
        PENDING_ALLOCATION --> AVAILABLE : ReverseAllocation (Compensación)
        AVAILABLE --> AVAILABLE : RefundFunds (refundedAmount += monto;\ncausedDeficit=true si excede availableAmount —\nflag independiente, no transición de estado)
    }
    
    CLEARED --> REFUNDED : Estado DERIVADO cuando refundedAmount alcanza\nclearedAmount (reembolso total), NO por causedDeficit
```
**Nota:** `causedDeficit` (flag booleano de sobregiro puntual) y `REFUNDED`/`FULLY_REFUNDED` (estado derivado de reembolso acumulado total) son dos conceptos independientes de ADR-004 — no confundirlos.
