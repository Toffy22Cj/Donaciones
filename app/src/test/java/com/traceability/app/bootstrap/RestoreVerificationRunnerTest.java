package com.traceability.app.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.data.mongodb.core.MongoTemplate;

import com.mongodb.client.MongoCollection;
import com.traceability.crypto.application.port.in.IntegrityVerificationPort;
import com.traceability.crypto.application.port.out.MerkleBatchRepositoryPort;
import com.traceability.crypto.domain.AnchorStatus;
import com.traceability.crypto.domain.InconclusiveReason;
import com.traceability.crypto.domain.MerkleBatch;
import com.traceability.crypto.domain.VerificationResult;
import com.traceability.crypto.domain.VerificationStatus;
import com.traceability.crypto.domain.exception.LegacyBatchLeafHashesUnavailableException;

/** T-04: el informe de la restauración de prueba solo dice OK sin MISMATCH y sin lotes que no se pudieron verificar. */
class RestoreVerificationRunnerTest {

    @TempDir Path dir;

    private final IntegrityVerificationPort verification = mock(IntegrityVerificationPort.class);
    private final MerkleBatchRepositoryPort batches = mock(MerkleBatchRepositoryPort.class);
    private final MongoTemplate mongo = mock(MongoTemplate.class);
    @SuppressWarnings("unchecked")
    private final MongoCollection<Document> events = mock(MongoCollection.class);

    @BeforeEach
    void eventStore() {
        when(mongo.getCollection(RestoreVerificationRunner.EVENT_STORE_COLLECTION)).thenReturn(events);
        when(events.countDocuments()).thenReturn(42L);
    }

    private static MerkleBatch batch(String id) {
        MerkleBatch b = mock(MerkleBatch.class);
        when(b.batchId()).thenReturn(id);
        return b;
    }

    private static VerificationResult result(VerificationStatus status) {
        return new VerificationResult(status, "r", "r", List.of(), true,
                status == VerificationStatus.INCONCLUSIVE ? InconclusiveReason.CANONICAL_FORM_UNKNOWN : null);
    }

    private String run() throws Exception {
        Path out = dir.resolve("sub/informe.txt");
        new RestoreVerificationRunner(verification, batches, mongo, out.toString())
                .run(new DefaultApplicationArguments());
        return Files.readString(out);
    }

    @Test
    void allMatchOrInconclusiveIsOkAndCountsEvents() throws Exception {
        MerkleBatch a = batch("a");
        MerkleBatch b = batch("b");
        when(batches.streamByStatus(AnchorStatus.ANCHORED)).thenReturn(Stream.of(a, b));
        when(verification.verifyBatch("a")).thenReturn(result(VerificationStatus.MATCH));
        when(verification.verifyBatch("b")).thenReturn(result(VerificationStatus.INCONCLUSIVE));

        String report = run();

        assertThat(report).contains("resultado=OK\n", "eventos=42\n", "lotes_anclados=2\n", "match=1\n",
                "inconclusive=1\n", "mismatch=0\n", "no_verificables=0\n", "lotes_mismatch=\n");
        verify(verification, never()).verifyAllAnchored();
    }

    @Test
    void oneMismatchFailsAndNamesTheBatch() throws Exception {
        MerkleBatch a = batch("a");
        MerkleBatch b = batch("b");
        when(batches.streamByStatus(AnchorStatus.ANCHORED)).thenReturn(Stream.of(a, b));
        when(verification.verifyBatch("a")).thenReturn(result(VerificationStatus.MATCH));
        when(verification.verifyBatch("b")).thenReturn(result(VerificationStatus.MISMATCH));

        assertThat(run()).contains("resultado=FALLO\n", "match=1\n", "mismatch=1\n", "lotes_mismatch=b\n");
    }

    @Test
    void legacyBatchWithoutLeavesIsNotOk() throws Exception {
        MerkleBatch a = batch("a");
        when(batches.streamByStatus(AnchorStatus.ANCHORED)).thenReturn(Stream.of(a));
        when(verification.verifyBatch("a")).thenThrow(new LegacyBatchLeafHashesUnavailableException("legacy"));

        assertThat(run()).contains("resultado=FALLO\n", "mismatch=0\n", "no_verificables=1\n",
                "lotes_no_verificables=a\n");
    }

    @Test
    void noAnchoredBatchesIsOkWithZeroCounts() throws Exception {
        when(batches.streamByStatus(AnchorStatus.ANCHORED)).thenReturn(Stream.empty());

        assertThat(run()).contains("resultado=OK\n", "lotes_anclados=0\n");
    }

    @Test
    void refusesToStartWithoutOutputFile() {
        assertThatThrownBy(() -> new RestoreVerificationRunner(verification, batches, mongo, " "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("traceability.restore-check.output-file");
    }
}
