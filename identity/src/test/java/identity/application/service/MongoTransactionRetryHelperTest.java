package identity.application.service;

import com.mongodb.MongoException;
import identity.domain.exception.IdentityConcurrentModificationException;
import identity.domain.exception.IdentityTransactionOutcomeUnknownException;
import identity.domain.exception.NestedIdentityTransactionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MongoTransactionRetryHelperTest {

    private PlatformTransactionManager transactionManager;
    private List<Long> recordedSleeps;
    private MongoTransactionRetryHelper.Sleeper sleeper;

    @BeforeEach
    void setUp() {
        recordedSleeps = new ArrayList<>();
        sleeper = recordedSleeps::add;
        transactionManager = new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) throws TransactionException {
                return new SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) throws TransactionException {
            }

            @Override
            public void rollback(TransactionStatus status) throws TransactionException {
            }
        };
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    void transientError_onceThenSuccess_invokesTwice_returnsResult_oneSleepRecorded() {
        MongoTransactionRetryHelper helper = new MongoTransactionRetryHelper(transactionManager, sleeper);

        MongoException transientEx = new MongoException(112, "WriteConflict");
        transientEx.addLabel(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL);

        @SuppressWarnings("unchecked")
        Supplier<String> operation = mock(Supplier.class);
        when(operation.get())
                .thenThrow(transientEx)
                .thenReturn("success");

        String result = helper.executeWithRetry(operation);

        assertEquals("success", result);
        verify(operation, times(2)).get();
        assertEquals(1, recordedSleeps.size());
        assertEquals(1, helper.getRetryCount());
    }

    @Test
    void transientError_threeTimes_throwsIdentityConcurrentModificationException_invokesThreeTimes() {
        MongoTransactionRetryHelper helper = new MongoTransactionRetryHelper(transactionManager, sleeper);

        MongoException transientEx = new MongoException(112, "WriteConflict");
        transientEx.addLabel(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL);

        @SuppressWarnings("unchecked")
        Supplier<String> operation = mock(Supplier.class);
        when(operation.get()).thenThrow(transientEx);

        IdentityConcurrentModificationException ex = assertThrows(
                IdentityConcurrentModificationException.class,
                () -> helper.executeWithRetry(operation)
        );

        assertTrue(ex.getMessage().contains("exhausted"));
        verify(operation, times(3)).get();
        assertEquals(2, recordedSleeps.size());
        assertEquals(2, helper.getRetryCount());
    }

    @Test
    void unknownCommitResult_inChain_throwsIdentityTransactionOutcomeUnknownException_invokesOnce_zeroSleeps() {
        MongoTransactionRetryHelper helper = new MongoTransactionRetryHelper(transactionManager, sleeper);

        MongoException unknownEx = new MongoException(91, "UnknownTransactionCommitResult");
        unknownEx.addLabel(MongoException.UNKNOWN_TRANSACTION_COMMIT_RESULT_LABEL);
        RuntimeException wrapped = new RuntimeException("Commit failed", unknownEx);

        @SuppressWarnings("unchecked")
        Supplier<String> operation = mock(Supplier.class);
        when(operation.get()).thenThrow(wrapped);

        IdentityTransactionOutcomeUnknownException ex = assertThrows(
                IdentityTransactionOutcomeUnknownException.class,
                () -> helper.executeWithRetry(operation)
        );

        assertSame(wrapped, ex.getCause());
        verify(operation, times(1)).get();
        assertEquals(0, recordedSleeps.size());
        assertEquals(0, helper.getRetryCount());
    }

    @Test
    void bothLabelsPresent_unknownWins_invokedOnce_zeroSleeps() {
        MongoTransactionRetryHelper helper = new MongoTransactionRetryHelper(transactionManager, sleeper);

        MongoException exWithBoth = new MongoException("Ambiguous error");
        exWithBoth.addLabel(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL);
        exWithBoth.addLabel(MongoException.UNKNOWN_TRANSACTION_COMMIT_RESULT_LABEL);

        @SuppressWarnings("unchecked")
        Supplier<String> operation = mock(Supplier.class);
        when(operation.get()).thenThrow(exWithBoth);

        assertThrows(
                IdentityTransactionOutcomeUnknownException.class,
                () -> helper.executeWithRetry(operation)
        );

        verify(operation, times(1)).get();
        assertEquals(0, recordedSleeps.size());
        assertEquals(0, helper.getRetryCount());
    }

    @Test
    void domainException_propagatesAsIs_invokedOnce_zeroSleeps() {
        MongoTransactionRetryHelper helper = new MongoTransactionRetryHelper(transactionManager, sleeper);

        identity.domain.exception.InactiveAccountException domainEx =
                new identity.domain.exception.InactiveAccountException("Account inactive");

        @SuppressWarnings("unchecked")
        Supplier<String> operation = mock(Supplier.class);
        when(operation.get()).thenThrow(domainEx);

        identity.domain.exception.InactiveAccountException thrown = assertThrows(
                identity.domain.exception.InactiveAccountException.class,
                () -> helper.executeWithRetry(operation)
        );

        assertSame(domainEx, thrown);
        verify(operation, times(1)).get();
        assertEquals(0, recordedSleeps.size());
        assertEquals(0, helper.getRetryCount());
    }

    @Test
    void backoffSleeps_fallWithinExpectedBounds() {
        MongoTransactionRetryHelper helper = new MongoTransactionRetryHelper(transactionManager, sleeper);

        for (int attempt = 1; attempt <= 5; attempt++) {
            long expectedCap = Math.min(MongoTransactionRetryHelper.CAP_BACKOFF_MS,
                    MongoTransactionRetryHelper.BASE_BACKOFF_MS * (1L << (attempt - 1)));
            for (int sample = 0; sample < 50; sample++) {
                long sleep = helper.calculateBackoff(attempt);
                assertTrue(sleep >= 0 && sleep <= expectedCap,
                        "Sleep " + sleep + " not in [0, " + expectedCap + "] for attempt " + attempt);
            }
        }
    }

    @Test
    void activeTransaction_throwsNestedIdentityTransactionException_operationNeverInvoked() {
        MongoTransactionRetryHelper helper = new MongoTransactionRetryHelper(transactionManager, sleeper);

        @SuppressWarnings("unchecked")
        Supplier<String> operation = mock(Supplier.class);

        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThrows(
                    NestedIdentityTransactionException.class,
                    () -> helper.executeWithRetry(operation)
            );
            verify(operation, never()).get();
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }
}
