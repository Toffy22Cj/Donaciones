# Código Fuente — Extensión del Repositorio (Tarea 13, Fase 2)

Este documento contiene el código fuente completo de los tres archivos modificados.

---

## ARCHIVO 1: BlockchainAnchorRepositoryPort.java

**Ruta:** `crypto/src/main/java/com/traceability/crypto/application/port/out/BlockchainAnchorRepositoryPort.java`

**Interfaz Pública:**

```java
package com.traceability.crypto.application.port.out;

import com.traceability.crypto.domain.MerkleBatch;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface BlockchainAnchorRepositoryPort extends MerkleBatchRepositoryPort {
    /**
     * Atomically claims the next PENDING batch by generating a nonce from the counter
     * and setting its status to SUBMITTING inside a single MongoDB transaction.
     * Retries automatically if a TransientTransactionError occurs under high contention.
     *
     * @param network the blockchain network identifier
     * @param smartContractAddress the address of the target smart contract
     * @return the claimed batch if one was found, empty otherwise
     */
    Optional<MerkleBatch> claimNextPendingBatchAndAssignNonceWithRetry(String network, String smartContractAddress);

    /**
     * Finds the oldest SUBMITTING batch that:
     * - Has a nonce assigned (nonceUsed != null)
     * - Does NOT have a transaction hash yet (transactionHash == null)
     *
     * Used for scheduler retry logic when a batch failed to broadcast.
     *
     * @return the retry candidate batch, or empty
     */
    Optional<MerkleBatch> findSubmittingWithoutTxHashAndNonce();

    /**
     * Finds all SUBMITTING batches that:
     * - Have no transaction hash (transactionHash == null)
     * - Have a submittedAt timestamp BEFORE the given cutoff
     *
     * Used to detect stale SUBMITTING batches for timeout escalation.
     *
     * @param cutoff the instant before which to find batches
     * @return list of stale batches, ordered by submittedAt ascending
     */
    List<MerkleBatch> findSubmittingWithoutTxHashOlderThan(Instant cutoff);

    /**
     * Finds all SUBMITTED batches, sorted by submittedAt ascending (oldest first).
     *
     * Used for confirmation polling.
     *
     * @return list of SUBMITTED batches in submission order
     */
    List<MerkleBatch> findSubmittedOlderFirst();

    /**
     * Resets a SUBMITTING batch to retry with the same nonce.
     * - Status remains SUBMITTING
     * - nonceUsed is preserved
     * - transactionHash is cleared to null
     * - resolution is cleared to null
     *
     * Called when transaction broadcast fails and we want to retry with same nonce.
     *
     * @param batchId the batch to reset
     */
    void keepSubmittingWithSameNonce(String batchId);

    /**
     * Reconciles a SUBMITTING batch after a timeout has been detected.
     * - Status is set to SUBMITTING
     * - nonceUsed is set to the provided value (may differ from previous)
     * - resolution is cleared
     *
     * Called when a SUBMITTING batch has timed out waiting for txHash and needs escalation.
     *
     * @param batchId the batch to reconcile
     * @param nonceUsed the nonce to assign or reassign
     */
    void reconcileSubmittingTimeout(String batchId, Long nonceUsed);

    /**
     * Updates a batch to SUBMITTED status with a transaction hash and timestamp.
     * - Status is set to SUBMITTED
     * - transactionHash is set to the provided txHash
     * - submittedAt is updated if provided (or preserves existing timestamp)
     * - resolution is cleared
     *
     * Called when the scheduler successfully broadcasts and receives a tx receipt.
     *
     * @param batchId the batch to update
     * @param txHash the transaction hash from the blockchain
     * @param submittedAt the timestamp when it was submitted (null to preserve)
     */
    void updateSubmitted(String batchId, String txHash, Instant submittedAt);

    /**
     * Marks a batch as STUCK.
     *
     * Used by the scheduler when a SUBMITTING batch has timed out waiting for txHash
     * and no retry is possible without human intervention.
     *
     * @param batchId the batch to mark
     */
    void markStuck(String batchId);

    /**
     * Marks a batch as FAILED.
     *
     * Used when the transaction receipt indicates failure (status == 0).
     *
     * @param batchId the batch to mark
     */
    void markFailed(String batchId);

    /**
     * Marks a batch as ANCHORED with confirmation details.
     *
     * @param batchId the batch to mark
     * @param confirmedBlockNumber the block number where the tx was confirmed
     * @param anchoredAt the instant when confirmation was detected
     */
    void markAnchored(String batchId, Long confirmedBlockNumber, Instant anchoredAt);

    /**
     * Marks a batch as ANCHOR_MISMATCH.
     *
     * Used when the Merkle root in the blockchain does not match the local one.
     *
     * @param batchId the batch to mark
     */
    void markAnchorMismatch(String batchId);
}
```

---

## ARCHIVO 2: MerkleBatchMongoAdapter.java (Extracto con los Nuevos Métodos)

**Ruta:** `crypto/src/main/java/com/traceability/crypto/infrastructure/persistence/mongo/MerkleBatchMongoAdapter.java`

**Métodos Nuevos Implementados:**

```java
@Override
public Optional<MerkleBatch> findSubmittingWithoutTxHashAndNonce() {
    Query query = new Query(Criteria.where("status").is(AnchorStatus.SUBMITTING)
            .and("transactionHash").is(null)
            .and("nonceUsed").ne(null));
    query.with(Sort.by(Sort.Direction.ASC, "submittedAt"));

    MerkleBatchDocument doc = mongoTemplate.findOne(query, MerkleBatchDocument.class);
    return Optional.ofNullable(doc).map(this::toDomain);
}

@Override
public List<MerkleBatch> findSubmittingWithoutTxHashOlderThan(Instant cutoff) {
    if (cutoff == null) {
        throw new IllegalArgumentException("cutoff instant cannot be null");
    }
    Query query = new Query(Criteria.where("status").is(AnchorStatus.SUBMITTING)
            .and("transactionHash").is(null)
            .and("submittedAt").lt((Object) cutoff));
    query.with(Sort.by(Sort.Direction.ASC, "submittedAt"));

    return mongoTemplate.find(query, MerkleBatchDocument.class).stream()
            .map(this::toDomain)
            .collect(Collectors.toList());
}

@Override
public List<MerkleBatch> findSubmittedOlderFirst() {
    Query query = new Query(Criteria.where("status").is(AnchorStatus.SUBMITTED));
    query.with(Sort.by(Sort.Direction.ASC, "submittedAt"));

    return mongoTemplate.find(query, MerkleBatchDocument.class).stream()
            .map(this::toDomain)
            .collect(Collectors.toList());
}

@Override
public void keepSubmittingWithSameNonce(String batchId) {
    Query query = new Query(Criteria.where("batchId").is(batchId));
    Update update = new Update()
            .set("status", AnchorStatus.SUBMITTING)
            .set("transactionHash", null)
            .set("resolution", null);
    mongoTemplate.updateFirst(query, update, MerkleBatchDocument.class);
}

@Override
public void reconcileSubmittingTimeout(String batchId, Long nonceUsed) {
    Query query = new Query(Criteria.where("batchId").is(batchId));
    Update update = new Update()
            .set("status", AnchorStatus.SUBMITTING)
            .set("nonceUsed", nonceUsed)
            .set("resolution", null);
    mongoTemplate.updateFirst(query, update, MerkleBatchDocument.class);
}

@Override
public void updateSubmitted(String batchId, String txHash, Instant submittedAt) {
    Query query = new Query(Criteria.where("batchId").is(batchId));
    Update update = new Update()
            .set("transactionHash", txHash)
            .set("status", AnchorStatus.SUBMITTED)
            .set("resolution", null);

    if (submittedAt != null) {
        update.set("submittedAt", submittedAt);
    }

    mongoTemplate.updateFirst(query, update, MerkleBatchDocument.class);
}

@Override
public void markStuck(String batchId) {
    Query query = new Query(Criteria.where("batchId").is(batchId));
    Update update = new Update().set("status", AnchorStatus.STUCK);
    mongoTemplate.updateFirst(query, update, MerkleBatchDocument.class);
}

@Override
public void markFailed(String batchId) {
    Query query = new Query(Criteria.where("batchId").is(batchId));
    Update update = new Update().set("status", AnchorStatus.FAILED);
    mongoTemplate.updateFirst(query, update, MerkleBatchDocument.class);
}

@Override
public void markAnchored(String batchId, Long confirmedBlockNumber, Instant anchoredAt) {
    Query query = new Query(Criteria.where("batchId").is(batchId));
    Update update = new Update()
            .set("status", AnchorStatus.ANCHORED)
            .set("confirmedBlockNumber", confirmedBlockNumber)
            .set("anchoredAt", anchoredAt != null ? anchoredAt : Instant.now());
    mongoTemplate.updateFirst(query, update, MerkleBatchDocument.class);
}

@Override
public void markAnchorMismatch(String batchId) {
    Query query = new Query(Criteria.where("batchId").is(batchId));
    Update update = new Update().set("status", AnchorStatus.ANCHOR_MISMATCH);
    mongoTemplate.updateFirst(query, update, MerkleBatchDocument.class);
}
```

---

## ARCHIVO 3: MerkleBatchMongoAdapterTest.java (Resumen de Tests)

**Ruta:** `crypto/src/test/java/com/traceability/crypto/infrastructure/persistence/mongo/MerkleBatchMongoAdapterTest.java`

**Estructura de Tests:**

### Configuración (Spring Boot + Testcontainers)

```java
@SpringBootTest(classes = MerkleBatchMongoAdapterTest.TestConfig.class)
@Testcontainers
class MerkleBatchMongoAdapterTest {
    @Container
    static MongoDBContainer mongoDBContainer = new MongoDBContainer(...)
            .withCommand("--replSet", "rs0");

    @Autowired
    private MerkleBatchMongoAdapter adapter;

    @Autowired
    private MongoTemplate mongoTemplate;

    @BeforeEach
    void cleanUp() {
        mongoTemplate.dropCollection(MerkleBatchDocument.class);
        mongoTemplate.dropCollection(Web3NonceCounterDocument.class);
    }
}
```

### Tests (14 totales)

#### Existentes (2):

1. **testRollbackWhenNoPendingBatch()**
   - Verifica que nonce no avanza si no hay batch PENDING

2. **testConcurrency_TwoThreadsClaimSingleBatch()**
   - Verifica que solo un thread reclama el batch bajo contención

#### Nuevos para métodos de búsqueda (5):

3. **testFindSubmittingWithoutTxHashAndNonce_returnsEmptyWhenNone()**
   - Empty case cuando no existen batches

4. **testFindSubmittingWithoutTxHashAndNonce_findsBatchWithoutTxHash()**
   - Encuentra batch SUBMITTING sin txHash

5. **testFindSubmittingWithoutTxHashAndNonce_ignoresBatchesWithTxHash()**
   - Filtra batches que ya tienen txHash

6. **testFindSubmittingWithoutTxHashOlderThan_ignoresBatchesTooNew()**
   - No retorna batches después del cutoff

7. **testFindSubmittingWithoutTxHashOlderThan_findsBatchesBefore()**
   - Encuentra batches antes del cutoff

#### Nuevos para operaciones de estado (7):

8. **testKeepSubmittingWithSameNonce_preservesNonce()**
   - Nonce se conserva, txHash se limpia

9. **testReconcileSubmittingTimeout_setsNonceAndResets()**
   - Asigna nonce y cambia status a SUBMITTING

10. **testMarkStuck_changesStatus()**
    - Verifica transición a STUCK

11. **testMarkFailed_changesStatus()**
    - Verifica transición a FAILED

12. **testMarkAnchored_setsBlockNumberAndTimestamp()**
    - Verifica transición a ANCHORED con bloque y timestamp

13. **testMarkAnchorMismatch_changesStatus()**
    - Verifica transición a ANCHOR_MISMATCH

14. **testFindSubmittedOlderFirst_returnsSorted()**
    - Verifica orden ascendente por submittedAt

---

## Validación de Cobertura

| Método del Adaptador                     | Tests | Casos Cubiertos                     |
| ---------------------------------------- | ----- | ----------------------------------- |
| `findSubmittingWithoutTxHashAndNonce()`  | 3     | empty, found, filtered              |
| `keepSubmittingWithSameNonce()`          | 1     | nonce preserved, txHash cleared     |
| `reconcileSubmittingTimeout()`           | 1     | nonce assigned, status SUBMITTING   |
| `findSubmittingWithoutTxHashOlderThan()` | 2     | too new (filtered), too old (found) |
| `markStuck()`                            | 1     | status transition                   |
| `markFailed()`                           | 1     | status transition                   |
| `markAnchored()`                         | 1     | status + block + timestamp          |
| `markAnchorMismatch()`                   | 1     | status transition                   |
| `findSubmittedOlderFirst()`              | 1     | sorting order                       |
| `claimNextPendingBatchAndAssignNonce()`  | 2     | rollback, concurrency               |

**Total: 14 tests ejecutándose contra Testcontainers MongoDB real**

---

## Ejecución en Terminal Local

Para que el usuario valide esto:

```bash
cd /home/carlos/Proyectos/Donaciones
mvn clean test -pl crypto -am
```

Resultado esperado:

```
[INFO] --- maven-surefire-plugin:3.x.x:test (default-test) @ crypto ---
[INFO] Running com.traceability.crypto.infrastructure.persistence.mongo.MerkleBatchMongoAdapterTest
[INFO] Tests run: 14, Failures: 0, Errors: 0, Skipped: 0
[INFO]
[INFO] BUILD SUCCESS
```

Con archivo XML actualizado en:

```
crypto/target/surefire-reports/TEST-com.traceability.crypto.infrastructure.persistence.mongo.MerkleBatchMongoAdapterTest.xml
```

Mostrando `tests="14"` en lugar de `tests="2"`.
