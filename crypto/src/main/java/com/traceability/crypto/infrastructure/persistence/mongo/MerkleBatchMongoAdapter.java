package com.traceability.crypto.infrastructure.persistence.mongo;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import com.traceability.crypto.application.port.out.BlockchainAnchorRepositoryPort;
import com.traceability.crypto.domain.AnchorStatus;
import com.traceability.crypto.domain.MerkleBatch;

@Component
public class MerkleBatchMongoAdapter implements BlockchainAnchorRepositoryPort {

    private final SpringDataMerkleBatchRepository repository;
    private final MongoTemplate mongoTemplate;
    private final org.springframework.transaction.support.TransactionTemplate transactionTemplate;

    public MerkleBatchMongoAdapter(SpringDataMerkleBatchRepository repository, MongoTemplate mongoTemplate, org.springframework.transaction.support.TransactionTemplate transactionTemplate) {
        this.repository = repository;
        this.mongoTemplate = mongoTemplate;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public MerkleBatch save(MerkleBatch batch) {
        MerkleBatchDocument doc = repository.findByBatchId(batch.batchId())
                .orElseGet(MerkleBatchDocument::new);
                
        doc.setBatchId(batch.batchId());
        doc.setCoverage(batch.coverage());
        doc.setMerkleRoot(batch.merkleRoot());
        doc.setLeafHashes(batch.leafHashes());
        doc.setCreatedAt(batch.createdAt());
        doc.setStatus(batch.status());
        doc.setNetwork(batch.network());
        doc.setSmartContractAddress(batch.smartContractAddress());
        doc.setNonceUsed(batch.nonceUsed());
        doc.setTransactionHash(batch.transactionHash());
        doc.setSubmittedAt(batch.submittedAt());
        doc.setAnchoredAt(batch.anchoredAt());
        doc.setConfirmedBlockNumber(batch.confirmedBlockNumber());
        doc.setResolution(batch.resolution());
        doc.setMaxFeePerGasOverride(batch.maxFeePerGasOverride());
        
        MerkleBatchDocument saved = repository.save(doc);
        return toDomain(saved);
    }

    @Override
    public Optional<MerkleBatch> findByBatchId(String batchId) {
        return repository.findByBatchId(batchId).map(this::toDomain);
    }

    @Override
    public List<MerkleBatch> findByStatus(AnchorStatus status) {
        return repository.findByStatus(status).stream()
                .map(this::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    public java.util.stream.Stream<MerkleBatch> streamByStatus(AnchorStatus status) {
        java.util.stream.Stream<MerkleBatchDocument> sourceStream = repository.streamByStatus(status);
        return sourceStream.map(this::toDomain).onClose(sourceStream::close);
    }

    @Override
    public boolean transitionCollectingToPending(String batchId, String merkleRoot, java.util.List<String> leafHashes) {
        Query query = new Query(Criteria.where("batchId").is(batchId).and("status").is(AnchorStatus.COLLECTING));
        Update update = new Update().set("status", AnchorStatus.PENDING).set("merkleRoot", merkleRoot).set("leafHashes", leafHashes);
        com.mongodb.client.result.UpdateResult result = mongoTemplate.updateFirst(query, update, MerkleBatchDocument.class);
        return result.getModifiedCount() > 0;
    }

    @Override
    public Optional<MerkleBatch> claimNextPendingBatchAndAssignNonceWithRetry(String network, String smartContractAddress) {
        int maxRetries = 3;
        int retries = 0;
        
        while (true) {
            try {
                return claimNextPendingBatchAndAssignNonce(network, smartContractAddress);
            } catch (com.traceability.crypto.domain.exception.NoPendingBatchAvailableException e) {
                // Transaction already rolled back the nonce increment — nothing to anchor yet.
                return Optional.empty();
            } catch (RuntimeException e) {
                if (e instanceof org.springframework.dao.TransientDataAccessException) {
                    handleTransientError(e, retries, maxRetries);
                    retries++;
                } else if (e.getCause() instanceof com.mongodb.MongoException && 
                           ((com.mongodb.MongoException) e.getCause()).hasErrorLabel(com.mongodb.MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL)) {
                    handleTransientError(e, retries, maxRetries);
                    retries++;
                } else if (e instanceof com.mongodb.MongoException && 
                           ((com.mongodb.MongoException) e).hasErrorLabel(com.mongodb.MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL)) {
                    handleTransientError(e, retries, maxRetries);
                    retries++;
                } else {
                    throw e;
                }
            }
        }
    }
    
    private void handleTransientError(Exception e, int retries, int maxRetries) {
        if (retries >= maxRetries) {
            throw new RuntimeException("Max retries exceeded for TransientTransactionError during batch claim", e);
        }
        try {
            Thread.sleep(50 + (long)(Math.random() * 50)); // Short backoff with jitter
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted during TransientTransactionError backoff", ie);
        }
    }

    @Override
    public Optional<MerkleBatch> claimNextPendingBatchAndAssignNonce(String network, String smartContractAddress) {
        return transactionTemplate.execute(status -> {
            String counterId = network + "-" + smartContractAddress;

            // 1. Increment and get the next nonce atomically
            Query counterQuery = new Query(Criteria.where("_id").is(counterId));
            Update counterUpdate = new Update().inc("nextNonce", 1);
            FindAndModifyOptions counterOptions = new FindAndModifyOptions().returnNew(true).upsert(true);
            
            Web3NonceCounterDocument counter = mongoTemplate.findAndModify(
                    counterQuery, counterUpdate, counterOptions, Web3NonceCounterDocument.class);

            if (counter == null) {
                status.setRollbackOnly();
                throw new IllegalStateException("Failed to increment nonce counter for " + counterId);
            }
            
            long assignedNonce = counter.getNextNonce() - 1;

            // 2. Atomically claim the batch
            Query query = new Query(Criteria.where("status").is(AnchorStatus.PENDING));
            query.with(Sort.by(Sort.Direction.ASC, "sequenceRangeStart"));

            Instant submissionStartedAt = Instant.now();
            Update update = new Update()
                    .set("status", AnchorStatus.SUBMITTING)
                    .set("nonceUsed", assignedNonce)
                    .set("network", network)
                    .set("smartContractAddress", smartContractAddress)
                    .set("submittedAt", submissionStartedAt);

            FindAndModifyOptions options = new FindAndModifyOptions().returnNew(true);

            MerkleBatchDocument claimedDoc = mongoTemplate.findAndModify(query, update, options, MerkleBatchDocument.class);

            // 3. If no batch is PENDING, we rollback the transaction to avoid burning a nonce unnecessarily
            if (claimedDoc == null) {
                // We MUST set rollback only, otherwise the transaction will commit the nonce increment!
                status.setRollbackOnly();
                throw new com.traceability.crypto.domain.exception.NoPendingBatchAvailableException("No PENDING batch found");
            }

            return Optional.of(toDomain(claimedDoc));
        });
    }

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

    @Override
    public void resolveStuckBatch(String batchId, com.traceability.crypto.domain.Resolution resolution, java.math.BigInteger maxFeePerGas) {
        Query query = new Query(Criteria.where("batchId").is(batchId).and("status").is(AnchorStatus.STUCK));
        
        Update update = new Update().set("resolution", resolution);
        
        if (resolution == com.traceability.crypto.domain.Resolution.RESUBMIT) {
            update.set("status", AnchorStatus.SUBMITTING);
            if (maxFeePerGas != null) {
                update.set("maxFeePerGasOverride", maxFeePerGas);
            }
        } else if (resolution == com.traceability.crypto.domain.Resolution.ABANDON) {
            update.set("status", AnchorStatus.FAILED);
        }

        com.mongodb.client.result.UpdateResult result = mongoTemplate.updateFirst(query, update, MerkleBatchDocument.class);
        
        if (result.getMatchedCount() == 0) {
            throw new IllegalStateException("Batch " + batchId + " not found or not in STUCK state.");
        }
    }

    @Override
    public void seedNonceCounter(String network, String smartContractAddress, long startingNonce) {
        String counterId = network + "-" + smartContractAddress;
        
        Query query = new Query(Criteria.where("_id").is(counterId));
        // Only update if the startingNonce is strictly greater than the current nextNonce (or it doesn't exist)
        // Note: MongoDB natively supports $max which we can use via Update.max()
        Update update = new Update().max("nextNonce", startingNonce);
        
        mongoTemplate.upsert(query, update, Web3NonceCounterDocument.class);
    }

    private MerkleBatch toDomain(MerkleBatchDocument doc) {
        java.util.Map<String, com.traceability.contracts.SequenceRange> coverage = doc.getCoverage();
        if (coverage == null || coverage.isEmpty()) {
            throw new com.traceability.crypto.domain.exception.LegacyBatchCoverageUnavailableException(
                    "Batch " + doc.getBatchId() + " is a legacy batch missing 'coverage' data. " +
                    "Cannot safely reconstruct stream sequences without historical migration."
            );
        }

        return new MerkleBatch(
                doc.getBatchId(),
                coverage,
                doc.getMerkleRoot(),
                doc.getLeafHashes(),
                doc.getCreatedAt(),
                doc.getStatus(),
                doc.getNetwork(),
                doc.getSmartContractAddress(),
                doc.getNonceUsed(),
                doc.getTransactionHash(),
                doc.getSubmittedAt(),
                doc.getAnchoredAt(),
                doc.getConfirmedBlockNumber(),
                doc.getResolution(),
                doc.getMaxFeePerGasOverride()
        );
    }
}
