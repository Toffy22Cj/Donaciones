# Diagramas de Arquitectura — Sistema "Nexxus" (Fase 2 Completada)

A continuación, se presentan los diagramas que detallan la arquitectura implementada y verificada durante la Fase 2.

## 1. Arquitectura General y Flujo de Event Sourcing / CQRS

Este diagrama muestra el flujo desde la ingesta de un comando hasta la persistencia inmutable y su eventual materialización en las proyecciones y auditorías.

```mermaid
flowchart TD
    %% Entradas
    User((Usuario/API))
    
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

Este diagrama detalla cómo los eventos de dominio se transforman en una cadena inmutable y se anclan en Polygon/Ganache usando árboles de Merkle.

```mermaid
flowchart LR
    subgraph Core
        Ev[Domain Event]
    end

    subgraph Crypto Module
        Canonicalizer[JCS RFC 8785]
        SHA256[SHA-256 Hasher]
        Merkle[Merkle Tree Builder]
        MongoBatch[(MerkleBatch MongoDB)]
        Adapter[Web3j Anchor Adapter]
        Scheduler[Blockchain Anchor Scheduler]
        Poller[Anchor Confirmation Poller]
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
    
    %% Scheduler y Transmisión
    Scheduler -->|Reclama batch y asigna Nonce| Adapter
    Adapter -->|Envía Tx| Polygon
    Polygon -->|Llama| SmartContract
    
    %% Poller y Confirmación
    Poller -->|Monitorea receipts| Polygon
    Polygon -.->|N bloques confirmados| Poller
    Poller -->|Valida evento RootStored y actualiza| MongoBatch
    MongoBatch -.->|ANCHORED o ANCHOR_MISMATCH| MongoBatch
```

---

## 3. Máquinas de Estado de los Aggregates

### PhysicalAsset (Trazabilidad Logística)
```mermaid
stateDiagram-v2
    [*] --> REGISTERED : RegisterPhysicalAsset
    REGISTERED --> DISPATCHED : DispatchPhysicalAsset
    REGISTERED --> REGISTERED : SplitPhysicalAsset (Si Q > 0)
    REGISTERED --> DEPLETED : SplitPhysicalAsset (Si Q = 0)
    
    DISPATCHED --> RECEIVED : ReceivePhysicalAsset
    DISPATCHED --> DELIVERED : DeliverPhysicalAsset
    
    RECEIVED --> DISPATCHED : DispatchPhysicalAsset
    RECEIVED --> DELIVERED : DeliverPhysicalAsset
    RECEIVED --> RECEIVED : SplitPhysicalAsset (Si Q > 0)
    RECEIVED --> DEPLETED : SplitPhysicalAsset (Si Q = 0)
    
    DELIVERED --> [*]
    DEPLETED --> REGISTERED/RECEIVED : CompensateAssetSplit (Rollback Saga)
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
    }
    
    CLEARED --> REFUNDED : RefundFunds (Si causedDeficit = true)
```
