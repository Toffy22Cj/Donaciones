package com.traceability.convocatoria.application.service;

import com.mongodb.MongoException;
import com.traceability.convocatoria.application.idempotency.CommandClaimCollisionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * Transacción programática con reintento acotado ante {@code TransientTransactionError}
 * (implementation_plan.md §4.4; ADR-037 §2.2; convocatoria-resumen.md §2). Reimplementa el patrón de
 * {@code MongoTransactionRetryHelper} de {@code identity} sin importarlo. Además reintenta la transacción completa
 * ante una colisión concurrente del reclamo de {@code commandId} (implementation_plan.md §7.1). Nunca reintenta
 * excepciones de dominio: se propagan en el primer intento.
 */
@Component
public class ConvocatoriaTransactionRetryHelper {

    private static final Logger log = LoggerFactory.getLogger(ConvocatoriaTransactionRetryHelper.class);
    /**
     * Intentos totales. Con la espera exponencial de {@link #backoffBoundsMillis(int)}, la ventana mínima entre el primer
     * y el último intento es 10+20+40+80+160 = 310 ms, por encima de la duración máxima medida de la transacción ganadora
     * bajo carga (301 ms) en una colisión de reclamo; con 3 intentos y 10-50 ms fijos era de 20-100 ms y el llamador
     * perdedor podía agotar los reintentos antes de que el ganador confirmara (verificado el 2026-10-02).
     */
    static final int MAX_ATTEMPTS = 6;
    static final long BASE_MIN_MILLIS = 10;
    static final long BASE_MAX_MILLIS = 50;
    static final long MAX_BACKOFF_MILLIS = 500;

    private final TransactionTemplate transactionTemplate;

    public ConvocatoriaTransactionRetryHelper(PlatformTransactionManager transactionManager) {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public <T> T executeWithRetry(Supplier<T> operation) {
        int attempts = 0;
        while (true) {
            attempts++;
            try {
                return transactionTemplate.execute(status -> operation.get());
            } catch (RuntimeException e) {
                if (isRetryable(e) && attempts < MAX_ATTEMPTS) {
                    log.warn("Retryable transaction failure (attempt {}/{}): {}", attempts, MAX_ATTEMPTS, e.toString());
                    backoff(attempts);
                    continue;
                }
                throw e;
            }
        }
    }

    static boolean isRetryable(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            if (current instanceof CommandClaimCollisionException) {
                return true;
            }
            if (current instanceof MongoException mongoException
                    && mongoException.hasErrorLabel(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * Espera tras el reintento {@code retryNumber} (1 = primera espera): jitter en
     * [{@code 10·2^(n-1)}, {@code 50·2^(n-1)}] ms, con tope de {@value #MAX_BACKOFF_MILLIS} ms. La primera espera
     * conserva el criterio anterior (10-50 ms, el de {@code CommandRetryTemplate} de {@code core}, reportado en §16); las
     * siguientes crecen para cubrir la duración de la transacción concurrente que provocó el conflicto.
     */
    static long[] backoffBoundsMillis(int retryNumber) {
        long factor = 1L << Math.min(retryNumber - 1, 20);
        long min = Math.min(BASE_MIN_MILLIS * factor, MAX_BACKOFF_MILLIS);
        long max = Math.min(BASE_MAX_MILLIS * factor, MAX_BACKOFF_MILLIS);
        return new long[] {min, max};
    }

    private static void backoff(int retryNumber) {
        long[] bounds = backoffBoundsMillis(retryNumber);
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(bounds[0], bounds[1] + 1));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during transaction retry backoff", ie);
        }
    }
}
