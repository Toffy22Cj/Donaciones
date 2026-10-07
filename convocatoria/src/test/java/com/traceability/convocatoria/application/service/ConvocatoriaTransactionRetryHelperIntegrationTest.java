package com.traceability.convocatoria.application.service;

import com.mongodb.MongoException;

import com.traceability.convocatoria.domain.exception.EmployeeAlreadyAssignedException;
import com.traceability.convocatoria.support.AbstractConvocatoriaMongoIntegrationTest;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reintento ante {@code TransientTransactionError} (implementation_plan.md §4.4, §13.1) con un conflicto de
 * escritura real de MongoDB: B escribe el mismo documento mientras la transacción de A sigue abierta.
 */
class ConvocatoriaTransactionRetryHelperIntegrationTest extends AbstractConvocatoriaMongoIntegrationTest {

    private static final String COLLECTION = "convocatoria_test_counter";

    @Autowired private ConvocatoriaTransactionRetryHelper helper;
    @Autowired private MongoTemplate mongoTemplate;

    @BeforeEach
    void setUp() {
        mongoTemplate.dropCollection(COLLECTION);
        mongoTemplate.createCollection(COLLECTION);
        mongoTemplate.insert(new Document("_id", "c").append("n", 0), COLLECTION);
    }

    private void increment() {
        mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is("c")), new Update().inc("n", 1), COLLECTION);
    }

    @Test
    void realWriteConflictIsRetriedAndEffectAppliedOnce() throws Exception {
        CountDownLatch aWrote = new CountDownLatch(1);
        CountDownLatch bFailedOnce = new CountDownLatch(1);
        CountDownLatch aCommitted = new CountDownLatch(1);
        AtomicInteger bAttempts = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> a = pool.submit(() -> {
                helper.executeWithRetry(() -> {
                    increment();
                    aWrote.countDown();
                    await(bFailedOnce);
                    return null;
                });
                aCommitted.countDown();
            });
            Future<?> b = pool.submit(() -> helper.executeWithRetry(() -> {
                if (bAttempts.incrementAndGet() == 1) {
                    await(aWrote);
                } else {
                    bFailedOnce.countDown();
                    await(aCommitted);
                }
                increment();
                return null;
            }));
            a.get(30, TimeUnit.SECONDS);
            b.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertEquals(2, bAttempts.get(), "B hit a real write conflict and was retried once");
        assertEquals(2, mongoTemplate.findById("c", Document.class, COLLECTION).getInteger("n"),
                "each transaction applied its increment exactly once");
    }

    @Test
    void domainExceptionIsNotRetried() {
        AtomicInteger attempts = new AtomicInteger();
        assertThrows(EmployeeAlreadyAssignedException.class, () -> helper.executeWithRetry(() -> {
            attempts.incrementAndGet();
            increment();
            throw new EmployeeAlreadyAssignedException("forced");
        }));
        assertEquals(1, attempts.get());
        assertEquals(0, mongoTemplate.findById("c", Document.class, COLLECTION).getInteger("n"));
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(20, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static MongoException transientWriteConflict() {
        MongoException e = new MongoException(112, "WriteConflict (forced by test)");
        e.addLabel(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL);
        return e;
    }

    /** Política: un fallo transitorio se reintenta hasta tener éxito dentro del límite; cada intento fallido se revierte. */
    @Test
    void transientFailuresAreRetriedUntilSuccessWithinTheLimitAndEachFailedAttemptRollsBack() {
        AtomicInteger attempts = new AtomicInteger();
        String result = helper.executeWithRetry(() -> {
            increment();
            if (attempts.incrementAndGet() < ConvocatoriaTransactionRetryHelper.MAX_ATTEMPTS) {
                throw transientWriteConflict();
            }
            return "done";
        });
        assertEquals("done", result);
        assertEquals(ConvocatoriaTransactionRetryHelper.MAX_ATTEMPTS, attempts.get());
        assertEquals(1, mongoTemplate.findById("c", Document.class, COLLECTION).getInteger("n"));
    }

    /** Política: el reintento es finito; agotado el límite, la excepción transitoria se propaga sin efectos. */
    @Test
    void transientFailureBeyondTheLimitIsPropagatedAfterExactlyMaxAttempts() {
        AtomicInteger attempts = new AtomicInteger();
        MongoException e = assertThrows(MongoException.class, () -> helper.executeWithRetry(() -> {
            attempts.incrementAndGet();
            increment();
            throw transientWriteConflict();
        }));
        assertTrue(e.hasErrorLabel(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL));
        assertEquals(ConvocatoriaTransactionRetryHelper.MAX_ATTEMPTS, attempts.get());
        assertEquals(0, mongoTemplate.findById("c", Document.class, COLLECTION).getInteger("n"));
    }

    /**
     * Política: espera exponencial con jitter y tope; la primera espera conserva 10-50 ms y la suma de las esperas
     * mínimas cubre la duración máxima medida de la transacción ganadora bajo carga (301 ms).
     */
    @Test
    void backoffGrowsExponentiallyWithCapAndItsMinimumWindowCoversTheMeasuredContention() {
        assertArrayEquals(new long[] {10, 50}, ConvocatoriaTransactionRetryHelper.backoffBoundsMillis(1));
        assertArrayEquals(new long[] {20, 100}, ConvocatoriaTransactionRetryHelper.backoffBoundsMillis(2));
        assertArrayEquals(new long[] {160, 500}, ConvocatoriaTransactionRetryHelper.backoffBoundsMillis(5));
        assertArrayEquals(new long[] {500, 500}, ConvocatoriaTransactionRetryHelper.backoffBoundsMillis(30));
        long minimumWindow = 0;
        for (int retry = 1; retry < ConvocatoriaTransactionRetryHelper.MAX_ATTEMPTS; retry++) {
            minimumWindow += ConvocatoriaTransactionRetryHelper.backoffBoundsMillis(retry)[0];
        }
        assertTrue(minimumWindow >= 301, "minimum retry window " + minimumWindow + " ms");
    }
}
