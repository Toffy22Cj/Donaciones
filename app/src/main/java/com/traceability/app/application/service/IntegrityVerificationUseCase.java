package com.traceability.app.application.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;

import com.traceability.core.application.port.out.UnanchoredEventRepositoryPort;
import com.traceability.crypto.application.port.in.IntegrityVerificationPort;
import com.traceability.crypto.application.port.out.MerkleBatchRepositoryPort;
import com.traceability.crypto.domain.AnchorStatus;
import com.traceability.crypto.domain.MerkleBatch;
import com.traceability.crypto.domain.MerkleTree;
import com.traceability.crypto.domain.StreamIdentity;
import com.traceability.crypto.domain.VerificationResult;
import com.traceability.crypto.domain.VerificationStatus;
import com.traceability.crypto.domain.exception.LegacyBatchLeafHashesUnavailableException;

@Service
public class IntegrityVerificationUseCase implements IntegrityVerificationPort {

    private final MerkleBatchRepositoryPort merkleBatchRepositoryPort;
    private final UnanchoredEventRepositoryPort unanchoredEventRepositoryPort;

    public IntegrityVerificationUseCase(MerkleBatchRepositoryPort merkleBatchRepositoryPort,
                                        UnanchoredEventRepositoryPort unanchoredEventRepositoryPort) {
        this.merkleBatchRepositoryPort = merkleBatchRepositoryPort;
        this.unanchoredEventRepositoryPort = unanchoredEventRepositoryPort;
    }

    @Override
    public VerificationResult verifyBatch(String batchId) {
        MerkleBatch batch = merkleBatchRepositoryPort.findByBatchId(batchId)
                .orElseThrow(() -> new IllegalArgumentException("Batch not found: " + batchId));

        return verify(batch);
    }

    private VerificationResult verify(MerkleBatch batch) {
        if (batch.status() != AnchorStatus.ANCHORED) {
            return new VerificationResult(VerificationStatus.INCONCLUSIVE, null, batch.merkleRoot(), List.of(), false);
        }

        if (batch.leafHashes() == null || batch.leafHashes().isEmpty()) {
            throw new LegacyBatchLeafHashesUnavailableException(
                    "Batch " + batch.batchId() + " is a legacy batch missing 'leafHashes' data. Cannot verify integrity and attribute mismatches."
            );
        }

        List<String> currentLeaves = unanchoredEventRepositoryPort.getEventHashesByCoverage(batch.coverage());

        List<StreamIdentity> expectedIdentities = batch.coverage().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .flatMap(entry -> LongStream.rangeClosed(entry.getValue().fromSequence(), entry.getValue().toSequence())
                        .mapToObj(seq -> new StreamIdentity(entry.getKey(), seq)))
                .toList();

        if (currentLeaves.size() != batch.leafHashes().size()) {
            return new VerificationResult(VerificationStatus.MISMATCH, null, batch.merkleRoot(), List.of(), false);
        }

        MerkleTree tree = MerkleTree.build(currentLeaves);
        String recomputedRoot = tree.getRoot();

        if (recomputedRoot.equals(batch.merkleRoot())) {
            return new VerificationResult(VerificationStatus.MATCH, recomputedRoot, batch.merkleRoot(), List.of(), true);
        }

        List<StreamIdentity> affectedSequences = new ArrayList<>();
        for (int i = 0; i < currentLeaves.size(); i++) {
            if (!currentLeaves.get(i).equals(batch.leafHashes().get(i))) {
                affectedSequences.add(expectedIdentities.get(i));
            }
        }

        return new VerificationResult(VerificationStatus.MISMATCH, recomputedRoot, batch.merkleRoot(), affectedSequences, !affectedSequences.isEmpty());
    }

    @Override
    public Stream<VerificationResult> verifyAllAnchored() {
        Stream<MerkleBatch> batchStream = merkleBatchRepositoryPort.streamByStatus(AnchorStatus.ANCHORED);
        return batchStream
                .map(this::verify)
                .onClose(batchStream::close);
    }
}
