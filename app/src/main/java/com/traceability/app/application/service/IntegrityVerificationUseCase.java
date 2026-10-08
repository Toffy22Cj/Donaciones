package com.traceability.app.application.service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;

import com.traceability.core.application.integrity.EventIntegrityChecker;
import com.traceability.core.application.port.out.UnanchoredEventRepositoryPort;
import com.traceability.crypto.application.port.in.IntegrityVerificationPort;
import com.traceability.crypto.application.port.out.MerkleBatchRepositoryPort;
import com.traceability.crypto.domain.AnchorStatus;
import com.traceability.crypto.domain.InconclusiveReason;
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
    private final EventIntegrityChecker eventIntegrityChecker;

    public IntegrityVerificationUseCase(MerkleBatchRepositoryPort merkleBatchRepositoryPort,
                                        UnanchoredEventRepositoryPort unanchoredEventRepositoryPort,
                                        EventIntegrityChecker eventIntegrityChecker) {
        this.merkleBatchRepositoryPort = merkleBatchRepositoryPort;
        this.unanchoredEventRepositoryPort = unanchoredEventRepositoryPort;
        this.eventIntegrityChecker = eventIntegrityChecker;
    }

    @Override
    public VerificationResult verifyBatch(String batchId) {
        MerkleBatch batch = merkleBatchRepositoryPort.findByBatchId(batchId)
                .orElseThrow(() -> new IllegalArgumentException("Batch not found: " + batchId));

        return verify(batch);
    }

    /**
     * Enmienda 1 de ADR-039 (aprobada por Carlos el 2026-10-08): además de recalcular la raíz con los {@code eventHash}
     * guardados, recalcula cada {@code eventHash} desde su payload y comprueba la cadena {@code previousHash}
     * ({@link EventIntegrityChecker}). Un hash o una cadena que no cuadran, o una raíz distinta, dan {@code MISMATCH}
     * con las secuencias atribuidas. Un evento anterior al corte cuya forma canónica no se puede determinar da
     * {@code INCONCLUSIVE}/{@code CANONICAL_FORM_UNKNOWN}, nunca {@code MISMATCH}.
     */
    private VerificationResult verify(MerkleBatch batch) {
        if (batch.status() != AnchorStatus.ANCHORED) {
            return new VerificationResult(VerificationStatus.INCONCLUSIVE, null, batch.merkleRoot(), List.of(), false,
                    InconclusiveReason.NOT_ANCHORED);
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

        Set<StreamIdentity> tampered = new LinkedHashSet<>();
        Set<StreamIdentity> unknownForm = new LinkedHashSet<>();
        for (EventIntegrityChecker.Finding f : eventIntegrityChecker.check(batch.coverage())) {
            StreamIdentity id = new StreamIdentity(f.streamId(), f.sequence());
            if (f.problem() == EventIntegrityChecker.Problem.CANONICAL_FORM_UNKNOWN) {
                unknownForm.add(id);
            } else {
                tampered.add(id);
            }
        }

        MerkleTree tree = MerkleTree.build(currentLeaves);
        String recomputedRoot = tree.getRoot();
        boolean rootMatches = recomputedRoot.equals(batch.merkleRoot());

        if (!rootMatches) {
            for (int i = 0; i < currentLeaves.size(); i++) {
                if (!currentLeaves.get(i).equals(batch.leafHashes().get(i))) {
                    tampered.add(expectedIdentities.get(i));
                }
            }
        }
        if (!rootMatches || !tampered.isEmpty()) {
            List<StreamIdentity> affected = expectedIdentities.stream().filter(tampered::contains).toList();
            return new VerificationResult(VerificationStatus.MISMATCH, recomputedRoot, batch.merkleRoot(), affected,
                    !affected.isEmpty());
        }
        if (!unknownForm.isEmpty()) {
            return new VerificationResult(VerificationStatus.INCONCLUSIVE, recomputedRoot, batch.merkleRoot(),
                    List.copyOf(unknownForm), true, InconclusiveReason.CANONICAL_FORM_UNKNOWN);
        }
        return new VerificationResult(VerificationStatus.MATCH, recomputedRoot, batch.merkleRoot(), List.of(), true);
    }

    @Override
    public Stream<VerificationResult> verifyAllAnchored() {
        Stream<MerkleBatch> batchStream = merkleBatchRepositoryPort.streamByStatus(AnchorStatus.ANCHORED);
        return batchStream
                .map(this::verify)
                .onClose(batchStream::close);
    }
}
