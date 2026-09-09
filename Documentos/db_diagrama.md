# Diagrama de la Base de Datos (MongoDB)

A continuación se muestra el diagrama Entidad-Relación (ER) de las colecciones de MongoDB, detallando los atributos principales de cada documento y cómo fluye la información o se relacionan entre sí a nivel lógico (al ser NoSQL, las relaciones suelen ser lógicas mediante IDs o proyección asíncrona).

```mermaid
erDiagram
    %% Core Event Sourcing
    TRACEABILITY_EVENT_DOCUMENT {
        string eventId PK
        string streamId "FK lógico (donationId / assetId)"
        string aggregateType
        long sequence
        string eventType
        string schemaVersion
        string occurredAt
        string recordedAt
        string actorRef
        string origin
        map payload
        string previousHash
        string eventHash "Para validación y blockchain"
    }

    OUTBOX_MESSAGE_DOCUMENT {
        string messageId PK
        string sagaType
        string sourceAggregateId "FK a streamId"
        string correlationId
        string payload
        string status
        int retryCount
        instant createdAt
        instant nextRetryAt
    }

    PROCESSED_COMMAND_DOCUMENT {
        string commandId PK "ID de la petición/comando"
        instant processedAt
    }

    %% Proyecciones de Lectura (CQRS)
    DONATION_PROJECTION_DOCUMENT {
        string projectionId PK "fundId (donationId)"
        string currency
        string campaignRef
        struct financialSnapshot "originalAmount, clearedAmount..."
        list allocations "vendorId, amount, status"
        list logistics "assetId, quantity, status"
        struct auditMetadata
        string status
    }

    ASSET_HISTORY_PROJECTION_DOCUMENT {
        string assetId PK
        list transitions "eventType, timestamp, location, custodian"
    }

    %% Módulo Blockchain / Web3
    MERKLE_BATCH_DOCUMENT {
        string id PK
        string batchId
        long sequenceRangeStart "Rango de eventos anclados"
        long sequenceRangeEnd "Rango de eventos anclados"
        string merkleRoot
        instant createdAt
        string status "AnchorStatus"
        string network
        string smartContractAddress
        long nonceUsed
        string transactionHash
        instant submittedAt
        instant anchoredAt
        long confirmedBlockNumber
    }

    %% Módulo Inteligencia Artificial
    DONOR_REPORT_DOCUMENT {
        string id PK
        string donationId "FK a donation_projections"
        string narrativeText
        string source
        string modelIdentifier
        string promptTemplateVersion
        string sourceFactsHash
        long auditFactsSequence
        instant generatedAt
        instant nextRetryAt
    }

    %% Relaciones Lógicas (Flujo de datos y referencias)
    
    %% Un comando procesado genera eventos
    PROCESSED_COMMAND_DOCUMENT ||--o{ TRACEABILITY_EVENT_DOCUMENT : "Previene duplicados al crear"
    
    %% Un evento genera un mensaje de outbox en la misma transacción
    TRACEABILITY_EVENT_DOCUMENT ||--|| OUTBOX_MESSAGE_DOCUMENT : "Publica asíncronamente"
    
    %% El outbox alimenta las proyecciones (Lectura)
    OUTBOX_MESSAGE_DOCUMENT }o--|| DONATION_PROJECTION_DOCUMENT : "Actualiza estado de donación"
    OUTBOX_MESSAGE_DOCUMENT }o--|| ASSET_HISTORY_PROJECTION_DOCUMENT : "Actualiza historial físico"
    
    %% El blockchain (Merkle Batch) agrupa eventos del Event Store
    TRACEABILITY_EVENT_DOCUMENT }o--|| MERKLE_BATCH_DOCUMENT : "Ancla hashes (eventHash)"
    
    %% Los reportes IA se generan basados en las proyecciones de las donaciones
    DONATION_PROJECTION_DOCUMENT ||--o{ DONOR_REPORT_DOCUMENT : "Genera narrativa IA"

```

### Notas sobre el diseño NoSQL:
*   **No hay Foreign Keys estrictas (FK):** Al usar MongoDB, las relaciones (indicadas con líneas y `FK lógico` en el diagrama) se hacen mediante identificadores compartidos como `streamId`, `donationId` o `assetId`.
*   **Asincronía (CQRS):** La colección `outbox` funciona como un puente temporal. Los datos entran por `TRACEABILITY_EVENT_DOCUMENT` y luego un proceso en segundo plano (worker) lee el `outbox` y escribe el estado final en `DONATION_PROJECTION_DOCUMENT` y `ASSET_HISTORY_PROJECTION_DOCUMENT`.
*   **Auditoría y Confianza:** `MERKLE_BATCH_DOCUMENT` no almacena eventos completos, solo un rango de "secuencias" y una raíz matemática (`merkleRoot`) calculada a partir de los `eventHash` de la colección de eventos, lo cual conecta el mundo privado con el contrato inteligente en Web3.
