package identity.infrastructure.persistence.mongo.transaction;

import com.mongodb.MongoException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.MongoTransactionManager;

/**
 * Custom MongoTransactionManager for identity module that retries commitTransaction
 * upon encountering UnknownTransactionCommitResult without re-executing business operations.
 *
 * References: ADR-038 §2.8 (Decision D1 = C+).
 */
public class CommitRetryingMongoTransactionManager extends MongoTransactionManager {

    private static final Logger log = LoggerFactory.getLogger(CommitRetryingMongoTransactionManager.class);

    // ADR-038 §2.8: commit retry policy C+ (up to 3 total commit attempts)
    public static final int MAX_COMMIT_ATTEMPTS = 3;

    public CommitRetryingMongoTransactionManager(MongoDatabaseFactory dbFactory) {
        super(dbFactory);
    }

    @Override
    protected void doCommit(MongoTransactionObject transactionObject) throws Exception {
        commitWithRetry(transactionObject::commitTransaction);
    }

    void commitWithRetry(Runnable commitAction) {
        int commitAttempts = 0;
        while (true) {
            commitAttempts++;
            try {
                commitAction.run();
                return;
            } catch (Exception ex) {
                if (isUnknownTransactionCommitResult(ex) && commitAttempts < MAX_COMMIT_ATTEMPTS) {
                    log.warn("UnknownTransactionCommitResult detected during commit (attempt {}/{}). Retrying commit only...",
                            commitAttempts, MAX_COMMIT_ATTEMPTS);
                    continue;
                }
                if (ex instanceof RuntimeException re) {
                    throw re;
                }
                throw new RuntimeException(ex);
            }
        }
    }

    private boolean isUnknownTransactionCommitResult(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            if (current instanceof MongoException mongoException) {
                if (mongoException.hasErrorLabel(MongoException.UNKNOWN_TRANSACTION_COMMIT_RESULT_LABEL)) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }
}
