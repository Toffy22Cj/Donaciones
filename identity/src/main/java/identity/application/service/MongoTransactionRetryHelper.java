package identity.application.service;

import com.mongodb.MongoException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

@Component
public class MongoTransactionRetryHelper {

    private static final Logger log = LoggerFactory.getLogger(MongoTransactionRetryHelper.class);
    private static final int MAX_ATTEMPTS = 3;
    private final TransactionTemplate transactionTemplate;
    private final java.util.concurrent.atomic.AtomicInteger retryCount = new java.util.concurrent.atomic.AtomicInteger(0);

    public MongoTransactionRetryHelper(PlatformTransactionManager transactionManager) {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public <T> T executeWithRetry(Supplier<T> operation) {
        int attempts = 0;
        while (true) {
            attempts++;
            try {
                return transactionTemplate.execute(status -> operation.get());
            } catch (Exception e) {
                if (isTransientTransactionError(e) && attempts < MAX_ATTEMPTS) {
                    log.warn("TransientTransactionError detected (attempt {}/{}). Retrying transaction...", attempts, MAX_ATTEMPTS);
                    retryCount.incrementAndGet();
                    continue;
                }
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

    private boolean isTransientTransactionError(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            if (current instanceof MongoException mongoException) {
                if (mongoException.hasErrorLabel(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL)) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    // For testing purposes
    int getRetryCount() {
        return retryCount.get();
    }
}
