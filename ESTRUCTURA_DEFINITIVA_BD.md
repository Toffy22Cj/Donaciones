# ESTRUCTURA DEFINITIVA Y MANUAL DE CONSTRUCCIÓN DE BASE DE DATOS
## Sistema de Trazabilidad Verificable "Nexxus / Donaciones"
**Base de Datos Lógica:** `nexxus_traceability`  
**Motor:** MongoDB 7.0+ Enterprise / Community (Replica Set obligatorio, Sharding Hashed)  
**Paradigma:** Pure Event Sourcing + CQRS Asíncrono + Sagas con Transactional Outbox  
**Estatus:** **DOCUMENTO CANÓNICO CONGELADO — VERSIÓN FINAL DEFINITIVA (FASE 3)**  
**Consenso Unánime:** Claude (Árbitro Principal) | ChatGPT (Lead Reviewer) | Gemini (Data Systems Architect)  

---

## 1. PRINCIPIOS CONSTITUCIONALES DE LA BASE DE DATOS (NO NEGOCIABLES)

1. **Separación de Responsabilidades en Outbox:**
   * La colección `outbox` se utiliza **exclusivamente para Sagas de Dominio transaccionales cross-stream** (`Fund` $\leftrightarrow$ `PhysicalAsset`).
   * Las Proyecciones CQRS se alimentan **exclusivamente mediante Change Streams** con afinidad de stream (`StreamAffinityDispatcher`). Se prohíbe taxativamente crear un Outbox para proyecciones.
2. **Regla de Oro de Idempotencia Atómica (Regla ChatGPT):**
   * *Nunca considerar exitoso el procesamiento de un evento hasta que la mutación de la proyección y el avance de su secuencia (`lastProcessedSequence`) hayan quedado persistidos en la misma operación atómica de MongoDB.*
   * *El `resumeToken` del Change Stream solo se avanza después de que las proyecciones hayan confirmado su escritura.*
3. **Inmutabilidad Absoluta del Event Store:**
   * La colección `event_store` es append-only. Prohibido ejecutar comandos `update`, `delete` o `replace` sobre eventos existentes.
4. **Privacidad por Diseño (Zero PII):**
   * Toda referencia a personas, empresas o ubicaciones sensibles se almacena mediante identificadores opacos (`donorRef`, `actorRef`, `beneficiaryRef`, `carrierRef`). Cero datos identificables en el Event Store o en hashes anclados a Blockchain.

---

## 2. MAPA MAESTRO DE COLECCIONES Y SHARDING

| # | Colección | Capa / Rol | Shard Key | Tipo de Persistencia |
|---|---|---|---|---|
| **1** | `event_store` | Write Side (OLTP) | `{"streamId": "hashed"}` | Inmutable (Append-Only) |
| **2** | `outbox` | Write Side (Sagas) | No Sharded (o `{ "status": 1 }`) | Transitoria con Distributed Lease |
| **3** | `merkle_batches` | Infra / Crypto | No Sharded | Transicional Auditada |
| **4** | `web3_nonce_counter`| Infra / Crypto | No Sharded | Contador Atómico Monotónico |
| **5** | `donation_projections` | Read Side (CQRS) | `{"_id": "hashed"}` | Derivada Materializada (Counters) |
| **6** | `donation_logistics_buckets` | Read Side (CQRS) | `{"fundId": "hashed"}` | Derivada Particionada (Bucket Pattern) |
| **7** | `donation_audit_facts` | Read Side (AI) | `{"_id": "hashed"}` | Derivada Materializada (Audit DTOs) |
| **8** | `asset_history` | Read Side (CQRS) | `{"_id": "hashed"}` | Derivada Materializada (Historial Unitario) |
| **9** | `asset_index` | Read Side (Técnico)| `{"_id": "hashed"}` | Índice Técnico de Resolución $O(1)$ |
| **10**| `projection_checkpoints` | Read Side (Infra)| No Sharded | Estado Operacional de Idempotencia |
| **11**| `quarantined_projections`| DLQ / Resiliencia | No Sharded | Cola de Excepciones y Gaps |

---

## 3. ESPECIFICACIÓN TÉCNICA DETALLADA POR COLECCIÓN

---

### COLECCIÓN 1: `event_store` (Fuente Única de Verdad)
Almacena todos los hechos históricos emitidos por los Aggregate Roots (`Fund` y `PhysicalAsset`).

* **Clase Java / Documento:** `com.traceability.core.infrastructure.persistence.mongo.TraceabilityEventDocument`
* **Sharding Mandatorio:** `sh.shardCollection("nexxus_traceability.event_store", { "streamId": "hashed" })`

#### Esquema BSON Canónico:
```json
{
  "_id": "550e8400-e29b-41d4-a716-446655440000",
  "eventId": "550e8400-e29b-41d4-a716-446655440000",
  "streamId": "fund-2026-med-01",
  "aggregateType": "Fund",
  "sequence": NumberLong(1),
  "eventType": "FUND_REGISTERED",
  "schemaVersion": "1.0",
  "occurredAt": "2026-09-05T02:00:00.000Z",
  "recordedAt": "2026-09-05T02:00:00.125Z",
  "actorRef": "USR-OP-8921",
  "origin": "TRACEABILITY_CORE",
  "payload": {
    "campaignRef": "CAMP-2026-FLOODS",
    "donorRef": "DONOR-ANON-7782",
    "currency": "USD",
    "pledgedAmount": NumberLong(5000000)
  },
  "previousHash": "0000000000000000000000000000000000000000000000000000000000000000",
  "eventHash": "a3f8c2b5e1d4a7f6e3c2b1a0d9e8f7a6b5c4d3e2f1a0b9c8d7e6f5a4b3c2d1e0"
}
```

#### Matriz de Índices:
```javascript
// 1. PK Primaria
db.event_store.createIndex({ "_id": 1 }, { name: "pk_event_id" });

// 2. Concurrencia Optimista Estricta (Invariante Central)
db.event_store.createIndex(
  { "streamId": 1, "sequence": 1 }, 
  { name: "idx_stream_sequence_unique", unique: true }
);

// 3. Rehidratación rápida en loadStream()
db.event_store.createIndex(
  { "streamId": 1, "sequence": 1, "eventType": 1 }, 
  { name: "idx_stream_replay_covering" }
);

// 4. Cold Recovery Path para Merkle Trees y Auditoría Temporal
db.event_store.createIndex(
  { "recordedAt": 1, "_id": 1 }, 
  { name: "idx_crypto_merkle_batching" }
);

// 5. Replay segmentado por tipo de aggregate
db.event_store.createIndex(
  { "aggregateType": 1, "recordedAt": 1 }, 
  { name: "idx_aggregate_recorded_replay" }
);
```

---

### COLECCIÓN 2: `outbox` (Transactional Outbox para Sagas de Dominio)
Garantiza la entrega atómica de mensajes cross-stream en transacciones locales ACID.

* **Clase Java / Documento:** `com.traceability.core.infrastructure.persistence.mongo.OutboxMessageDocument`

#### Esquema BSON Canónico con Distributed Lease:
```json
{
  "_id": "msg-88219-uuid",
  "messageId": "msg-88219-uuid",
  "sagaType": "FUND_TO_ASSET_ALLOCATION",
  "sourceAggregateId": "fund-2026-med-01",
  "correlationId": "alloc-uuid-001",
  "payload": "{\"allocationId\":\"alloc-uuid-001\",\"assetType\":\"MEDICAL_KITS\",\"quantity\":100}",
  "status": "IN_FLIGHT",
  "retryCount": 0,
  "createdAt": ISODate("2026-09-05T02:10:00.000Z"),
  "nextRetryAt": ISODate("2026-09-05T02:10:00.000Z"),
  "lockedBy": "worker-pod-us-east-1a",
  "lockedUntil": ISODate("2026-09-05T02:11:00.000Z")
}
```

#### Matriz de Índices:
```javascript
db.outbox.createIndex({ "_id": 1 }, { name: "pk_outbox_id" });

// Polling distribuido atómico con findAndModify
db.outbox.createIndex(
  { "status": 1, "nextRetryAt": 1, "lockedUntil": 1 }, 
  { name: "idx_outbox_polling_lease" }
);

db.outbox.createIndex(
  { "correlationId": 1 }, 
  { name: "idx_outbox_correlation" }
);
```

#### Operación Atómica de Reclamo (Atomic Lease Claiming):
```javascript
db.outbox.findOneAndUpdate(
  {
    $and: [
      { "status": { $in: ["PENDING", "IN_FLIGHT"] } },
      { "nextRetryAt": { $lte: new Date() } },
      { $or: [
          { "lockedUntil": null },
          { "lockedUntil": { $lt: new Date() } } // Auto-recuperación de workers caídos
      ]}
    ]
  },
  {
    $set: {
      "status": "IN_FLIGHT",
      "lockedBy": "worker-pod-id",
      "lockedUntil": new Date(Date.now() + 60000) // Lease de 60 segundos
    }
  },
  { sort: { "nextRetryAt": 1 }, returnDocument: "after" }
);
```

---

### COLECCIÓN 3: `merkle_batches` (Lotes de Integridad Blockchain)
Agrupa hashes canónicos de eventos para su anclaje a Polygon/Ganache bajo la máquina de estados de ADR-019.

* **Clase Java / Documento:** `com.traceability.crypto.infrastructure.persistence.mongo.MerkleBatchDocument`

#### Esquema BSON Canónico:
```json
{
  "_id": ObjectId("66d91200f1a23b456789abcd"),
  "batchId": "BATCH-20260905-001",
  "sequenceRangeStart": NumberLong(1),
  "sequenceRangeEnd": NumberLong(500),
  "merkleRoot": "0x7b5a2f8c9d1e3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2b3c4d5e6f7a8b",
  "createdAt": ISODate("2026-09-05T02:00:00.000Z"),
  "status": "SUBMITTED",
  "network": "polygon-amoy",
  "smartContractAddress": "0x1234567890123456789012345678901234567890",
  "nonceUsed": NumberLong(42),
  "transactionHash": "0xabc987def6543210fedcba0123456789abcdef0123456789abcdef0123456789",
  "submittedAt": ISODate("2026-09-05T02:05:00.000Z"),
  "anchoredAt": null,
  "confirmedBlockNumber": null,
  "resolution": null
}
```

#### Matriz de Índices:
```javascript
db.merkle_batches.createIndex({ "_id": 1 });
db.merkle_batches.createIndex({ "batchId": 1 }, { name: "idx_merkle_batch_id_unique", unique: true });
db.merkle_batches.createIndex({ "status": 1, "createdAt": 1 }, { name: "idx_merkle_status_scheduler" });
```

---

### COLECCIÓN 4: `web3_nonce_counter` (Control Local de Nonce Web3)
Evita condiciones de carrera en el mempool administrando localmente el nonce de la cuenta de anclaje.

* **Clase Java / Documento:** `com.traceability.crypto.infrastructure.persistence.mongo.Web3NonceCounterDocument`

#### Esquema BSON Canónico:
```json
{
  "_id": "polygon-amoy-0x1234567890123456789012345678901234567890",
  "nextNonce": NumberLong(43)
}
```

---

### COLECCIÓN 5: `donation_projections` (Vista Materializada Principal - Counters Only)
Contiene la visión financiera y el estado macro de la donación. **Desacoplada del array masivo de activos para evitar desbordar los 16MB BSON.**

* **Clase Java / Documento:** `com.traceability.core.infrastructure.projection.mongo.documents.DonationProjectionDocument`

#### Esquema BSON Canónico:
```json
{
  "_id": "fund-2026-med-01",
  "projectionId": "fund-2026-med-01",
  "status": "ACTIVE",
  "financialSnapshot": {
    "sourceTransactionId": "TX-EXT-99812",
    "originalAmount": NumberLong(5000000),
    "clearedAmount": NumberLong(5000000),
    "pendingAllocationAmount": NumberLong(0),
    "refundedAmount": NumberLong(0)
  },
  "allocations": [
    {
      "allocationId": "alloc-uuid-001",
      "vendorId": "VEND-MED-GLOBAL",
      "requirementId": "REQ-KITS-ANTIBIOTICS",
      "amount": NumberLong(2500000)
    }
  ],
  "logisticsSummary": {
    "totalAssetsRegistered": NumberLong(1250),
    "totalAssetsInTransit": NumberLong(300),
    "totalAssetsDelivered": NumberLong(950),
    "activeBucketsCount": 3
  },
  "auditMetadata": {
    "fundLastProcessedSequence": NumberLong(3),
    "lastUpdated": ISODate("2026-09-05T02:30:00.000Z")
  }
}
```

#### Matriz de Índices:
```javascript
db.donation_projections.createIndex({ "_id": 1 });
db.donation_projections.createIndex({ "allocations.allocationId": 1 }, { name: "idx_donations_alloc_id" });
db.donation_projections.createIndex({ "status": 1 }, { name: "idx_donations_status" });
```

---

### COLECCIÓN 6: `donation_logistics_buckets` (Inventario Detallado - Bucket Pattern)
Almacena el detalle de unidades físicas trazables asociadas a un fondo, divididas en lotes de máximo 500 ítems.

* **Clase Java / Documento:** `com.traceability.core.infrastructure.projection.mongo.documents.DonationLogisticsBucketDocument` *(NUEVA)*

#### Esquema BSON Canónico:
```json
{
  "_id": "fund-2026-med-01_b001",
  "fundId": "fund-2026-med-01",
  "bucketNumber": 1,
  "count": 500,
  "items": [
    {
      "assetId": "asset-root-001",
      "allocationId": "alloc-uuid-001",
      "sourceAllocationId": null,
      "parentAssetRef": null,
      "rootAssetRef": "asset-root-001",
      "quantity": NumberLong(100),
      "unitOfMeasure": "KITS",
      "assetType": "MEDICAL_SUPPLIES",
      "currentLocation": "FACILITY-BOGOTA-CENTRAL",
      "currentCustodian": "CARRIER-ANDES-LOGISTICS",
      "lifecycleStatus": "RECEIVED"
    }
  ]
}
```

#### Matriz de Índices:
```javascript
db.donation_logistics_buckets.createIndex({ "_id": 1 });
db.donation_logistics_buckets.createIndex(
  { "fundId": 1, "bucketNumber": 1 }, 
  { name: "idx_buckets_fund_number_unique", unique: true }
);
db.donation_logistics_buckets.createIndex(
  { "items.assetId": 1 }, 
  { name: "idx_buckets_asset_search" }
);
```

---

### COLECCIÓN 7: `donation_audit_facts` (Hechos Deterministas para Módulo IA)
Contrato determinista exclusivo para consumo del módulo `ai` a través de `AuditFactsPort`.

* **Clase Java / Documento:** `com.traceability.core.infrastructure.projection.mongo.documents.DonationAuditFactsDocument`

#### Esquema BSON Canónico:
```json
{
  "_id": "fund-2026-med-01",
  "fundId": "fund-2026-med-01",
  "transitions": [
    {
      "assetRef": "asset-child-002",
      "fromStatus": "DISPATCHED",
      "toStatus": "DELIVERED",
      "occurredAtFrom": ISODate("2026-09-05T01:00:00.000Z"),
      "occurredAtTo": ISODate("2026-09-05T02:30:00.000Z"),
      "durationSeconds": NumberLong(5400),
      "expectedMaximumSeconds": NumberLong(172800),
      "anomaly": false
    }
  ],
  "financialFlags": [
    {
      "type": "DEFICIT_CAUSED",
      "allocationId": null,
      "refundId": null,
      "causedDeficit": false,
      "amount": NumberLong(0),
      "occurredAt": ISODate("2026-09-05T02:00:00.000Z")
    }
  ],
  "generatedAt": ISODate("2026-09-05T02:30:00.000Z"),
  "auditMetadata": {
    "fundLastProcessedSequence": NumberLong(3)
  }
}
```

#### Matriz de Índices:
```javascript
db.donation_audit_facts.createIndex({ "_id": 1 });
db.donation_audit_facts.createIndex(
  { "transitions.anomaly": 1 }, 
  { name: "idx_audit_anomalies", sparse: true }
);
```

---

### COLECCIÓN 8: `asset_history` (Trazabilidad Cronológica Unitaria)
Línea de tiempo individual por activo físico.

* **Clase Java / Documento:** `com.traceability.core.infrastructure.projection.mongo.documents.AssetHistoryProjectionDocument`

#### Esquema BSON Canónico:
```json
{
  "_id": "asset-root-001",
  "assetId": "asset-root-001",
  "transitions": [
    {
      "sequence": NumberLong(1),
      "eventType": "ASSET_REGISTERED",
      "timestamp": "2026-09-05T02:05:00.000Z",
      "location": "WAREHOUSE-BOGOTA",
      "custodian": "CUSTODIAN-MAIN-01",
      "status": "REGISTERED"
    }
  ]
}
```

#### Matriz de Índices:
```javascript
db.asset_history.createIndex({ "_id": 1 });
```

---

### COLECCIÓN 9: `asset_index` (Índice de Resolución Técnico $O(1)$)
Mapea `assetId` $\to$ `projectionId` (`fundId`) para enrutamiento inmediato en el Change Stream.

* **Clase Java / Documento:** `com.traceability.core.infrastructure.projection.mongo.documents.AssetIndexDocument`

#### Esquema BSON Canónico:
```json
{
  "_id": "asset-child-002",
  "assetId": "asset-child-002",
  "projectionId": "fund-2026-med-01",
  "rootAssetRef": "asset-root-001",
  "lastAppliedSequence": NumberLong(2)
}
```

#### Matriz de Índices:
```javascript
db.asset_index.createIndex({ "_id": 1 });
db.asset_index.createIndex({ "projectionId": 1 }, { name: "idx_asset_index_projection" });
db.asset_index.createIndex({ "rootAssetRef": 1 }, { name: "idx_asset_index_root_lineage" });
```

---

### COLECCIÓN 10: `projection_checkpoints` (Idempotencia y Estado Operacional)
Almacena el checkpoint persistente por proyector y el token de reanudación del cursor.

* **Clase Java / Documento:** `com.traceability.core.infrastructure.projection.mongo.documents.ProjectionCheckpointDocument`

#### Esquema BSON Canónico:
```json
{
  "_id": "DonationProjectionHandler_fund-2026-med-01",
  "handlerName": "DonationProjectionHandler",
  "streamId": "fund-2026-med-01",
  "lastProcessedSequence": NumberLong(3),
  "lastEventId": "550e8400-e29b-41d4-a716-446655440000",
  "resumeToken": "8266D912000000012B...",
  "updatedAt": ISODate("2026-09-05T02:30:00.000Z")
}
```

#### Matriz de Índices:
```javascript
db.projection_checkpoints.createIndex({ "_id": 1 });
db.projection_checkpoints.createIndex(
  { "handlerName": 1, "streamId": 1 }, 
  { name: "idx_checkpoints_handler_stream_unique", unique: true }
);
```

---

### COLECCIÓN 11: `quarantined_projections` (Dead Letter Queue - DLQ)
Almacén no bloqueante para eventos desordenados o con dependencias no resueltas.

* **Clase Java / Documento:** `com.traceability.core.infrastructure.projection.mongo.documents.ProjectionRetryDocument`

#### Esquema BSON Canónico:
```json
{
  "_id": "550e8400-e29b-41d4-a716-446655440000_DonationProjectionHandler",
  "eventId": "550e8400-e29b-41d4-a716-446655440000",
  "handlerName": "DonationProjectionHandler",
  "streamId": "asset-child-002",
  "projectionId": "fund-2026-med-01",
  "sequence": NumberLong(4),
  "eventType": "ASSET_RECEIVED",
  "payload": { ... },
  "occurredAt": "2026-09-05T02:15:00.000Z",
  "retryCount": 1,
  "firstAttemptAt": ISODate("2026-09-05T02:15:01.000Z"),
  "lastAttemptAt": ISODate("2026-09-05T02:15:16.000Z"),
  "nextAttemptAt": ISODate("2026-09-05T02:15:46.000Z"),
  "status": "PENDING",
  "failureReason": "SequenceGapException: Expected sequence 3 but received 4"
}
```

#### Matriz de Índices:
```javascript
db.quarantined_projections.createIndex({ "_id": 1 });
db.quarantined_projections.createIndex(
  { "status": 1, "nextAttemptAt": 1 }, 
  { name: "idx_quarantine_polling" }
);
db.quarantined_projections.createIndex(
  { "projectionId": 1, "status": 1, "sequence": 1 }, 
  { name: "idx_quarantine_resume_ordered" }
);
```

---

## 4. SCRIPT CANÓNICO DE INICIALIZACIÓN MONGOSH

Este script se ejecuta durante el despliegue del entorno o en migraciones de base de datos (`init-mongo.js`):

```javascript
// Conexión a la base de datos oficial
db = db.getSiblingDB("nexxus_traceability");

print("--- INICIANDO CONSTRUCCIÓN DE BASE DE DATOS NEXXUS TRACEABILITY ---");

// 1. event_store
db.createCollection("event_store");
db.event_store.createIndex({ "streamId": 1, "sequence": 1 }, { name: "idx_stream_sequence_unique", unique: true });
db.event_store.createIndex({ "streamId": 1, "sequence": 1, "eventType": 1 }, { name: "idx_stream_replay_covering" });
db.event_store.createIndex({ "recordedAt": 1, "_id": 1 }, { name: "idx_crypto_merkle_batching" });
db.event_store.createIndex({ "aggregateType": 1, "recordedAt": 1 }, { name: "idx_aggregate_recorded_replay" });

// 2. outbox
db.createCollection("outbox");
db.outbox.createIndex({ "status": 1, "nextRetryAt": 1, "lockedUntil": 1 }, { name: "idx_outbox_polling_lease" });
db.outbox.createIndex({ "correlationId": 1 }, { name: "idx_outbox_correlation" });

// 3. merkle_batches
db.createCollection("merkle_batches");
db.merkle_batches.createIndex({ "batchId": 1 }, { name: "idx_merkle_batch_id_unique", unique: true });
db.merkle_batches.createIndex({ "status": 1, "createdAt": 1 }, { name: "idx_merkle_status_scheduler" });

// 4. web3_nonce_counter
db.createCollection("web3_nonce_counter");

// 5. donation_projections
db.createCollection("donation_projections");
db.donation_projections.createIndex({ "allocations.allocationId": 1 }, { name: "idx_donations_alloc_id" });
db.donation_projections.createIndex({ "status": 1 }, { name: "idx_donations_status" });

// 6. donation_logistics_buckets
db.createCollection("donation_logistics_buckets");
db.donation_logistics_buckets.createIndex({ "fundId": 1, "bucketNumber": 1 }, { name: "idx_buckets_fund_number_unique", unique: true });
db.donation_logistics_buckets.createIndex({ "items.assetId": 1 }, { name: "idx_buckets_asset_search" });

// 7. donation_audit_facts
db.createCollection("donation_audit_facts");
db.donation_audit_facts.createIndex({ "transitions.anomaly": 1 }, { name: "idx_audit_anomalies", sparse: true });

// 8. asset_history
db.createCollection("asset_history");

// 9. asset_index
db.createCollection("asset_index");
db.asset_index.createIndex({ "projectionId": 1 }, { name: "idx_asset_index_projection" });
db.asset_index.createIndex({ "rootAssetRef": 1 }, { name: "idx_asset_index_root_lineage" });

// 10. projection_checkpoints
db.createCollection("projection_checkpoints");
db.projection_checkpoints.createIndex({ "handlerName": 1, "streamId": 1 }, { name: "idx_checkpoints_handler_stream_unique", unique: true });

// 11. quarantined_projections
db.createCollection("quarantined_projections");
db.quarantined_projections.createIndex({ "status": 1, "nextAttemptAt": 1 }, { name: "idx_quarantine_polling" });
db.quarantined_projections.createIndex({ "projectionId": 1, "status": 1, "sequence": 1 }, { name: "idx_quarantine_resume_ordered" });

// Sharding (si el cluster soporta sharding)
try {
  sh.enableSharding("nexxus_traceability");
  sh.shardCollection("nexxus_traceability.event_store", { "streamId": "hashed" });
  sh.shardCollection("nexxus_traceability.donation_projections", { "_id": "hashed" });
  sh.shardCollection("nexxus_traceability.donation_logistics_buckets", { "fundId": "hashed" });
  sh.shardCollection("nexxus_traceability.asset_index", { "_id": "hashed" });
  print("--- SHARDING CONFIGURADO CON ÉXITO ---");
} catch (e) {
  print("INFO: Clúster en modo Replica Set standalone (sharding no habilitado o no disponible en este nodo).");
}

print("--- CONSTRUCCIÓN DE BASE DE DATOS FINALIZADA SATISFACTORIAMENTE ---");
```

---
*Fin del Manual de Construcción Definitivo de la Base de Datos. Ninguna estructura adicional se modificará sin una nueva sesión formal del Tribunal de Arquitectura.*
