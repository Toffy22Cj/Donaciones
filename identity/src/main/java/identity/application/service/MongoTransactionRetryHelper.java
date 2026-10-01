package identity.application.service;

import com.mongodb.MongoException;
import identity.domain.exception.IdentityConcurrentModificationException;
import identity.domain.exception.IdentityTransactionOutcomeUnknownException;
import identity.domain.exception.NestedIdentityTransactionException;
import identity.infrastructure.persistence.mongo.transaction.CommitRetryingMongoTransactionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Transaction retry helper for identity module adhering to policy C+ (ADR-038 §2.8).
 */
@Component
public class MongoTransactionRetryHelper {

    private static final Logger log = LoggerFactory.getLogger(MongoTransactionRetryHelper.class);

    // ADR-038 §2.8: Constants for transaction retry policy C+
    public static final int MAX_ATTEMPTS = 3;
    public static final long BASE_BACKOFF_MS = 50L;
    public static final long CAP_BACKOFF_MS = 500L;

    @FunctionalInterface
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private final TransactionTemplate transactionTemplate;
    private final Sleeper sleeper;
    private final AtomicInteger retryCount = new AtomicInteger(0);

    @Autowired
    public MongoTransactionRetryHelper(MongoDatabaseFactory dbFactory) {
        CommitRetryingMongoTransactionManager txManager = new CommitRetryingMongoTransactionManager(dbFactory);
        txManager.afterPropertiesSet();
        this.transactionTemplate = new TransactionTemplate(txManager);
        this.sleeper = Thread::sleep;
    }

    // Package-private constructor for unit tests without real clock / real DB
    MongoTransactionRetryHelper(PlatformTransactionManager transactionManager, Sleeper sleeper) {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.sleeper = sleeper != null ? sleeper : Thread::sleep;
    }

    public MongoTransactionRetryHelper(PlatformTransactionManager transactionManager) {
        this(transactionManager, Thread::sleep);
    }

    public <T> T executeWithRetry(Supplier<T> operation) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new NestedIdentityTransactionException(
                    "Nested identity transaction detected: operations must not be invoked within an already active transaction");
        }

        int attempts = 0;
        while (true) {
            attempts++;
            try {
                return transactionTemplate.execute(status -> operation.get());
            } catch (Exception e) {
                // 1. UnknownTransactionCommitResult -> IdentityTransactionOutcomeUnknownException (never re-executed)
                if (hasErrorLabel(e, MongoException.UNKNOWN_TRANSACTION_COMMIT_RESULT_LABEL)) {
                    log.error("UnknownTransactionCommitResult detected. Outcome is ambiguous; operation will not be re-executed.", e);
                    throw new IdentityTransactionOutcomeUnknownException(
                            "Transaction commit result is unknown after commit retries exhausted", e);
                }

                // 2. TransientTransactionError -> full transaction retry with exponential backoff & full jitter
                if (hasErrorLabel(e, MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL)) {
                    if (attempts < MAX_ATTEMPTS) {
                        retryCount.incrementAndGet();
                        long backoff = calculateBackoff(attempts);
                        log.warn("TransientTransactionError detected (attempt {}/{}). Backing off for {} ms and retrying...",
                                attempts, MAX_ATTEMPTS, backoff);
                        try {
                            sleeper.sleep(backoff);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw new IdentityConcurrentModificationException(
                                    "Interrupted during transaction retry backoff", ie);
                        }
                        continue;
                    } else {
                        log.error("TransientTransactionError detected and max attempts ({}) exhausted.", MAX_ATTEMPTS, e);
                        throw new IdentityConcurrentModificationException(
                                "Concurrent modification conflict: max retry attempts (" + MAX_ATTEMPTS + ") exhausted", e);
                    }
                }

                // 3. Any other exception (including domain exceptions) -> propagate without retry
                throw e;
            }
        }
    }

    public void executeWithRetry(Runnable operation) {
        executeWithRetry(() -> {
            operation.run();
            return null;
        });
    }

    private boolean hasErrorLabel(Throwable ex, String label) {
        Throwable current = ex;
        while (current != null) {
            if (current instanceof MongoException mongoException) {
                if (mongoException.hasErrorLabel(label)) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    long calculateBackoff(int attempt) {
        long maxBackoff = Math.min(CAP_BACKOFF_MS, BASE_BACKOFF_MS * (1L << (attempt - 1)));
        return ThreadLocalRandom.current().nextLong(0, maxBackoff + 1);
    }

    // For testing purposes
    int getRetryCount() {
        return retryCount.get();
    }
}
