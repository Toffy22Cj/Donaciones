package com.traceability.app.bootstrap;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

import com.traceability.crypto.application.port.in.IntegrityVerificationPort;
import com.traceability.crypto.application.port.out.MerkleBatchRepositoryPort;
import com.traceability.crypto.domain.AnchorStatus;
import com.traceability.crypto.domain.MerkleBatch;
import com.traceability.crypto.domain.VerificationResult;
import com.traceability.crypto.domain.exception.LegacyBatchLeafHashesUnavailableException;

/**
 * T-04 (plan de evolución del producto §2.1, aprobado el 2026-11-14): verificación de una restauración de prueba.
 * Solo con {@code traceability.restore-check.enabled=true}, que pone {@code paxfide.sh restaurar-prueba} contra una
 * base restaurada, aislada y desechable; nunca contra la base del servidor.
 * <p>
 * Cuenta los eventos de {@code event_store} y verifica cada lote {@code ANCHORED} con
 * {@link IntegrityVerificationPort#verifyBatch} (la misma verificación que {@code verifyAllAnchored}, lote a lote para
 * poder nombrar los lotes que fallan): recálculo de la raíz y de cada {@code eventHash} con su cadena
 * {@code previousHash} (Enmienda 1 de ADR-039). Escribe el informe en {@code traceability.restore-check.output-file}
 * (formato {@code clave=valor}, una por línea, de forma atómica) y lo registra. {@code resultado=OK} solo si no hay
 * ningún {@code MISMATCH} ni ningún lote que no se pudo verificar; un {@code INCONCLUSIVE} se cuenta aparte y no falla.
 */
@Component
@ConditionalOnProperty(name = "traceability.restore-check.enabled", havingValue = "true")
public class RestoreVerificationRunner implements ApplicationRunner {

    static final String EVENT_STORE_COLLECTION = "event_store";

    private static final Logger log = LoggerFactory.getLogger(RestoreVerificationRunner.class);

    private final IntegrityVerificationPort verification;
    private final MerkleBatchRepositoryPort batches;
    private final MongoTemplate mongo;
    private final Path outputFile;

    public RestoreVerificationRunner(IntegrityVerificationPort verification, MerkleBatchRepositoryPort batches,
                                     MongoTemplate mongo,
                                     @Value("${traceability.restore-check.output-file:}") String outputFile) {
        if (outputFile == null || outputFile.isBlank()) {
            throw new IllegalStateException("traceability.restore-check.output-file must be set when "
                    + "traceability.restore-check.enabled=true");
        }
        this.verification = verification;
        this.batches = batches;
        this.mongo = mongo;
        this.outputFile = Path.of(outputFile);
    }

    @Override
    public void run(ApplicationArguments args) {
        Report report = verify();
        String text = report.render();
        write(text);
        log.info("Verificación de restauración terminada: {}", text.replace('\n', ' ').trim());
    }

    Report verify() {
        long events = mongo.getCollection(EVENT_STORE_COLLECTION).countDocuments();
        Report report = new Report(events);
        List<String> ids;
        try (Stream<MerkleBatch> anchored = batches.streamByStatus(AnchorStatus.ANCHORED)) {
            ids = anchored.map(MerkleBatch::batchId).toList();
        }
        for (String batchId : ids) {
            try {
                report.add(batchId, verification.verifyBatch(batchId));
            } catch (LegacyBatchLeafHashesUnavailableException e) {
                report.unverifiable(batchId);
            }
        }
        return report;
    }

    private void write(String text) {
        try {
            Path parent = outputFile.toAbsolutePath().getParent();
            Files.createDirectories(parent);
            Path tmp = Files.createTempFile(parent, ".restore-check", ".tmp");
            Files.writeString(tmp, text, StandardCharsets.UTF_8);
            Files.move(tmp, outputFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write restore-check report to " + outputFile, e);
        }
    }

    /** Recuento de la verificación. {@code resultado=OK} solo sin {@code MISMATCH} y sin lotes no verificables. */
    static final class Report {
        private final long events;
        private int match;
        private int inconclusive;
        private final List<String> mismatched = new ArrayList<>();
        private final List<String> unverifiable = new ArrayList<>();

        Report(long events) {
            this.events = events;
        }

        void add(String batchId, VerificationResult result) {
            switch (result.status()) {
                case MATCH -> match++;
                case INCONCLUSIVE -> inconclusive++;
                case MISMATCH -> mismatched.add(batchId);
            }
        }

        void unverifiable(String batchId) {
            unverifiable.add(batchId);
        }

        boolean ok() {
            return mismatched.isEmpty() && unverifiable.isEmpty();
        }

        String render() {
            return "resultado=" + (ok() ? "OK" : "FALLO") + "\n"
                    + "eventos=" + events + "\n"
                    + "lotes_anclados=" + (match + inconclusive + mismatched.size() + unverifiable.size()) + "\n"
                    + "match=" + match + "\n"
                    + "inconclusive=" + inconclusive + "\n"
                    + "mismatch=" + mismatched.size() + "\n"
                    + "no_verificables=" + unverifiable.size() + "\n"
                    + "lotes_mismatch=" + String.join(",", mismatched) + "\n"
                    + "lotes_no_verificables=" + String.join(",", unverifiable) + "\n";
        }
    }
}
