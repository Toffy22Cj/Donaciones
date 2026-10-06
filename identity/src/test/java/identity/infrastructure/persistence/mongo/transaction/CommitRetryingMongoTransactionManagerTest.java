package identity.infrastructure.persistence.mongo.transaction;

import com.mongodb.MongoException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.MongoDatabaseFactory;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CommitRetryingMongoTransactionManagerTest {

    private CommitRetryingMongoTransactionManager manager;

    @BeforeEach
    void setUp() {
        MongoDatabaseFactory dbFactory = mock(MongoDatabaseFactory.class);
        manager = new CommitRetryingMongoTransactionManager(dbFactory);
    }

    @Test
    void unknown_onceThenSuccess_commitsTwice() {
        MongoException unknown = new MongoException(91, "UnknownTransactionCommitResult");
        unknown.addLabel(MongoException.UNKNOWN_TRANSACTION_COMMIT_RESULT_LABEL);

        Runnable commitAction = mock(Runnable.class);
        doThrow(unknown)
                .doNothing()
                .when(commitAction).run();

        manager.commitWithRetry(commitAction);

        verify(commitAction, times(2)).run();
    }

    @Test
    void unknown_threeTimes_throwsAfterExactlyThreeAttempts() {
        MongoException unknown = new MongoException(91, "UnknownTransactionCommitResult");
        unknown.addLabel(MongoException.UNKNOWN_TRANSACTION_COMMIT_RESULT_LABEL);

        Runnable commitAction = mock(Runnable.class);
        doThrow(unknown).when(commitAction).run();

        MongoException thrown = assertThrows(MongoException.class, () -> manager.commitWithRetry(commitAction));

        assertSame(unknown, thrown);
        verify(commitAction, times(3)).run();
    }

    @Test
    void errorWithoutLabel_propagatesOnFirstAttempt() {
        MongoException other = new MongoException(112, "Other");

        Runnable commitAction = mock(Runnable.class);
        doThrow(other).when(commitAction).run();

        MongoException thrown = assertThrows(MongoException.class, () -> manager.commitWithRetry(commitAction));

        assertSame(other, thrown);
        verify(commitAction, times(1)).run();
    }
}
