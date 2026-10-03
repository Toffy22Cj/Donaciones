package com.traceability.convocatoria.application.service;

import com.mongodb.MongoException;
import com.traceability.convocatoria.application.command.ApplyFundsResult;
import com.traceability.convocatoria.application.command.CloseConvocatoriaCommand;
import com.traceability.convocatoria.application.command.ConfirmDonationIntentCommand;
import com.traceability.convocatoria.application.command.CreateDonationIntentCommand;
import com.traceability.convocatoria.application.idempotency.CommandType;
import com.traceability.convocatoria.application.idempotency.IdempotentCommandExecutor;
import com.traceability.convocatoria.application.idempotency.ProcessedCommand;
import com.traceability.convocatoria.domain.exception.CampaignFundingLimitExceededException;
import com.traceability.convocatoria.domain.exception.CampaignNotFoundException;
import com.traceability.convocatoria.domain.exception.CloseOnTargetCloseNotSupportedException;
import com.traceability.convocatoria.domain.exception.DonationIntentNotConfirmedException;
import com.traceability.convocatoria.domain.exception.DonationIntentNotFoundException;
import com.traceability.convocatoria.domain.exception.InvalidFundingAmountException;
import com.traceability.convocatoria.domain.model.ConfirmationSource;
import com.traceability.convocatoria.domain.model.ConvocatoriaConfiguration;
import com.traceability.convocatoria.domain.model.DonationIntent;
import com.traceability.convocatoria.domain.model.DonationIntentStatus;
import com.traceability.convocatoria.domain.model.OnTargetReached;
import com.traceability.convocatoria.domain.model.PaymentMethod;
import com.traceability.convocatoria.domain.model.TargetPolicy;
import com.traceability.convocatoria.infrastructure.persistence.mongo.adapter.MongoProcessedCommandAdapter;
import com.traceability.convocatoria.infrastructure.persistence.mongo.document.ProcessedCommandDocument;
import com.traceability.convocatoria.support.AbstractConvocatoriaServiceIntegrationTest;
import com.traceability.convocatoria.support.ConvocatoriaScenarios;
import com.traceability.convocatoria.support.ConvocatoriaTestApplication;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * implementation_plan.md §8, §13.1; ADR-037 §2.2; Enmienda §3.1, §3.3; D1; R4. Aplicación de fondos de una intención
 * ya {@code CONFIRMED} (F-1, F-2) con barrera en el registro de comandos procesados, comando de sistema
 * {@code (APPLY_FUNDS, intentId)}, y estado terminal {@code FUNDING_REJECTED} para el rechazo permanente
 * (ADR-037 Enmienda 2 §3.3, §4).
 */
class CampaignFundingLedgerIntegrationTest extends AbstractConvocatoriaServiceIntegrationTest {

    @Autowired private ConvocatoriaLifecycleService lifecycle;
    @Autowired private DonationIntentService intents;
    @Autowired private CampaignFundingLedgerService service;
    @Autowired private IdempotentCommandExecutor executor;
    @Autowired private PlatformTransactionManager transactionManager;

    private String create(ConvocatoriaConfiguration cfg) {
        return ConvocatoriaScenarios.createConvocatoria(lifecycle, ADMIN, ORG, cfg);
    }

    private static ConvocatoriaConfiguration policy(TargetPolicy policy, OnTargetReached onTargetReached) {
        return monetary(policy, onTargetReached, 1000L, PaymentMethod.GATEWAY, PaymentMethod.BANK_TRANSFER);
    }

    private String pendingIntent(String campaignRef, long amount, String commandId) {
        String publicCode = convocatorias.findByCampaignRef(campaignRef).orElseThrow().getPublicCode();
        return intents.createDonationIntent(new CreateDonationIntentCommand(commandId, publicCode, "donor-1", amount,
                "COP", PaymentMethod.BANK_TRANSFER)).intentId();
    }

    private String pendingIntent(String campaignRef, long amount) {
        return pendingIntent(campaignRef, amount, newCommandId());
    }

    private String confirmedIntent(String campaignRef, long amount) {
        String intentId = pendingIntent(campaignRef, amount);
        assertTrue(intents.confirmDonationIntent(new ConfirmDonationIntentCommand(intentId, ADMIN, "BANK-" + intentId)));
        return intentId;
    }

    private boolean apply(String intentId) {
        return service.applyFundsForIntent(intentId).appliedNow();
    }

    private long cleared(String campaignRef) {
        return ledgers.findByCampaignRef(campaignRef).orElseThrow().clearedAmount();
    }

    private DonationIntentStatus status(String intentId) {
        return donationIntents.findById(intentId).orElseThrow().getStatus();
    }

    private boolean claimed(String intentId) {
        return processedCommands.findSystemCommand(CommandType.APPLY_FUNDS, intentId).isPresent();
    }

    private void assertConfirmedWithoutApplication(String intentId) {
        assertEquals(DonationIntentStatus.CONFIRMED, status(intentId));
        assertFalse(claimed(intentId), "no application barrier was left behind");
    }

    private static MongoException transientWriteConflict() {
        MongoException e = new MongoException(112, "WriteConflict (forced by test)");
        e.addLabel(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL);
        return e;
    }

    // --- Reglas del ledger por política, partiendo de una intención CONFIRMED ---

    @Test
    void flexibleCanExceedTarget() {
        String c = create(policy(TargetPolicy.FLEXIBLE, null));
        assertTrue(apply(confirmedIntent(c, 900)));
        assertTrue(apply(confirmedIntent(c, 200)));
        assertEquals(1100, cleared(c));
    }

    @Test
    void strictRejectsWholeDonationThatWouldExceedTargetAndLeavesTheIntentConfirmed() {
        String c = create(policy(TargetPolicy.STRICT, null));
        assertTrue(apply(confirmedIntent(c, 900)));
        String excess = confirmedIntent(c, 200);
        assertThrows(CampaignFundingLimitExceededException.class, () -> apply(excess));
        assertEquals(900, cleared(c));
        assertConfirmedWithoutApplication(excess);
        assertTrue(apply(confirmedIntent(c, 100)));
        assertEquals(1000, cleared(c));
    }

    @Test
    void rejectExcessRejectsTheWhole200WhenTarget1000AndCleared900() {
        String c = create(policy(TargetPolicy.CLOSE_ON_TARGET, OnTargetReached.REJECT_EXCESS));
        assertTrue(apply(confirmedIntent(c, 900)));
        String excess = confirmedIntent(c, 200);
        assertThrows(CampaignFundingLimitExceededException.class, () -> apply(excess));
        assertEquals(900, cleared(c), "no partial acceptance");
        assertConfirmedWithoutApplication(excess);
    }

    @Test
    void acceptExcessHasNoCapacityCondition() {
        String c = create(policy(TargetPolicy.CLOSE_ON_TARGET, OnTargetReached.ACCEPT_EXCESS));
        assertTrue(apply(confirmedIntent(c, 900)));
        assertTrue(apply(confirmedIntent(c, 200)));
        assertEquals(1100, cleared(c));
        verify(ledgers, never()).incrementWithinTarget(anyString(), anyLong(), anyLong());
    }

    @Test
    void closeBranchIsNotSupportedAndLeavesNothing() {
        String c = create(policy(TargetPolicy.CLOSE_ON_TARGET, OnTargetReached.CLOSE));
        String intentId = confirmedIntent(c, 100);
        assertThrows(CloseOnTargetCloseNotSupportedException.class, () -> apply(intentId));
        assertEquals(0, cleared(c));
        assertConfirmedWithoutApplication(intentId);
        verify(ledgers, never()).incrementWithinTarget(anyString(), anyLong(), anyLong());
        verify(ledgers, never()).incrementUnconditionally(anyString(), anyLong());
    }

    @Test
    void closedStatusIsNotPartOfTheFilter() {
        String c = create(policy(TargetPolicy.STRICT, null));
        String intentId = pendingIntent(c, 300);
        lifecycle.closeConvocatoria(new CloseConvocatoriaCommand(newCommandId(), ADMIN, c));
        assertTrue(intents.confirmDonationIntent(new ConfirmDonationIntentCommand(intentId, ADMIN, "BANK-1")));
        assertTrue(apply(intentId));
        assertEquals(300, cleared(c));
    }

    // --- Precondiciones: solo se aplica una intención CONFIRMED ---

    @Test
    void pendingIntentCannotBeApplied() {
        String c = create(policy(TargetPolicy.FLEXIBLE, null));
        String intentId = pendingIntent(c, 100);
        assertThrows(DonationIntentNotConfirmedException.class, () -> apply(intentId));
        assertEquals(DonationIntentStatus.PENDING, status(intentId));
        assertFalse(claimed(intentId));
        assertEquals(0, cleared(c));
        verify(ledgers, never()).incrementUnconditionally(anyString(), anyLong());
    }

    @Test
    void unknownIntentIsRejected() {
        assertThrows(DonationIntentNotFoundException.class, () -> apply("missing"));
        verify(ledgers, never()).incrementUnconditionally(anyString(), anyLong());
    }

    @Test
    void missingLedgerIsAnAnomalyThatLeavesNothing() {
        String c = create(policy(TargetPolicy.FLEXIBLE, null));
        String intentId = confirmedIntent(c, 100);
        ledgers.deleteByCampaignRef(c);
        assertThrows(CampaignNotFoundException.class, () -> apply(intentId));
        assertConfirmedWithoutApplication(intentId);
        assertThrows(CampaignNotFoundException.class, () -> service.rejectFundingIfPermanentlyUnfundable(intentId));
        assertEquals(DonationIntentStatus.CONFIRMED, status(intentId), "an anomaly is never a funding rejection");
    }

    @Test
    void persistedIntentWithNonPositiveAmountIsAnAnomaly() {
        String c = create(policy(TargetPolicy.FLEXIBLE, null));
        String intentId = UUID.randomUUID().toString();
        donationIntents.insert(DonationIntent.reconstitute(intentId, UUID.randomUUID().toString(), ORG, c, "donor-1", 0,
                "COP", PaymentMethod.BANK_TRANSFER, ConfirmationSource.ORGANIZATION, 1, null, null, null,
                DonationIntentStatus.CONFIRMED, new DonationIntent.Confirmation(ADMIN, Instant.now(),
                        PaymentMethod.BANK_TRANSFER, "BANK-1")));
        assertThrows(InvalidFundingAmountException.class, () -> apply(intentId));
        assertEquals(0, cleared(c));
        assertConfirmedWithoutApplication(intentId);
    }

    // --- Transacción externa (futuro orquestador de app) ---

    @Test
    void joinsAnExternalTransactionAndRollsBackWithIt() {
        String c = create(policy(TargetPolicy.STRICT, null));
        String intentId = confirmedIntent(c, 300);
        assertThrows(IllegalStateException.class, () -> new TransactionTemplate(transactionManager).executeWithoutResult(s -> {
            assertTrue(apply(intentId));
            throw new IllegalStateException("external transaction fails after the ledger write");
        }));
        assertEquals(0, cleared(c));
        assertConfirmedWithoutApplication(intentId);
        assertTrue(apply(intentId));
        assertEquals(300, cleared(c));
    }

    @Test
    void ledgerRejectionInsideAnExternalTransactionPropagatesWithItsSemanticsAndLeavesNothing() {
        String c = create(policy(TargetPolicy.STRICT, null));
        assertTrue(apply(confirmedIntent(c, 900)));
        String excess = confirmedIntent(c, 200);
        assertThrows(CampaignFundingLimitExceededException.class,
                () -> new TransactionTemplate(transactionManager).executeWithoutResult(s -> apply(excess)));
        assertEquals(900, cleared(c));
        assertConfirmedWithoutApplication(excess);
    }

    /** DH-4: un llamador que captura el rechazo no puede confirmar la transacción con la barrera ocupada. */
    @Test
    void externalCallerCatchingTheLedgerRejectionCannotCommit() {
        String c = create(policy(TargetPolicy.STRICT, null));
        assertTrue(apply(confirmedIntent(c, 900)));
        String excess = confirmedIntent(c, 200);
        List<Throwable> caught = new ArrayList<>();
        assertThrows(UnexpectedRollbackException.class,
                () -> new TransactionTemplate(transactionManager).executeWithoutResult(s -> {
                    try {
                        apply(excess);
                    } catch (CampaignFundingLimitExceededException e) {
                        caught.add(e);
                    }
                }));
        assertEquals(1, caught.size(), "the domain exception reached the caller unchanged");
        assertEquals(900, cleared(c));
        assertConfirmedWithoutApplication(excess);
    }

    // --- Barrera: una sola aplicación por intención ---

    @Test
    void sameIntentAppliedTwiceSequentiallyIncrementsOnceAndKeepsTheOriginalResult() {
        for (TargetPolicy targetPolicy : List.of(TargetPolicy.FLEXIBLE, TargetPolicy.STRICT)) {
            String c = create(policy(targetPolicy, null));
            String intentId = confirmedIntent(c, 250);
            assertTrue(apply(intentId));
            Map<String, String> original = processedCommands
                    .findSystemCommand(CommandType.APPLY_FUNDS, intentId).orElseThrow().result();
            assertFalse(apply(intentId), "the second application of the same intent is a no-op");
            assertEquals(250, cleared(c));
            assertEquals(Map.of("intentId", intentId, "campaignRef", c, "amount", "250"), original);
            assertEquals(original, processedCommands
                    .findSystemCommand(CommandType.APPLY_FUNDS, intentId).orElseThrow().result());
        }
    }

    /** N12: un duplicado devuelve el resultado original guardado, marcado como no aplicado en esta llamada. */
    @Test
    void duplicateApplicationReturnsTheOriginalResult() {
        String c = create(policy(TargetPolicy.FLEXIBLE, null));
        String intentId = confirmedIntent(c, 250);
        ApplyFundsResult first = service.applyFundsForIntent(intentId);
        ApplyFundsResult second = service.applyFundsForIntent(intentId);
        assertEquals(new ApplyFundsResult(intentId, c, 250, true), first);
        assertEquals(new ApplyFundsResult(intentId, c, 250, false), second);
        assertEquals(250, cleared(c));
    }

    @Test
    void concurrentDuplicatesBothReceiveTheOriginalResult() throws Exception {
        for (int round = 0; round < 5; round++) {
            String c = create(policy(TargetPolicy.FLEXIBLE, null));
            String intentId = confirmedIntent(c, 250);
            CyclicBarrier barrier = new CyclicBarrier(2);
            Callable<ApplyFundsResult> task = () -> {
                barrier.await(10, TimeUnit.SECONDS);
                return service.applyFundsForIntent(intentId);
            };
            ExecutorService pool = Executors.newFixedThreadPool(2);
            List<ApplyFundsResult> results = new ArrayList<>();
            try {
                for (Future<ApplyFundsResult> f : pool.invokeAll(List.of(task, task))) {
                    results.add(f.get());
                }
            } finally {
                pool.shutdownNow();
            }
            assertEquals(1, results.stream().filter(ApplyFundsResult::appliedNow).count());
            results.forEach(r -> assertEquals(List.of(intentId, c, 250L), List.of(r.intentId(), r.campaignRef(), r.amount())));
            assertEquals(250, cleared(c));
        }
    }

    @Test
    void sameIntentAppliedConcurrentlyIncrementsOnce() throws Exception {
        for (int round = 0; round < 5; round++) {
            String c = create(policy(TargetPolicy.FLEXIBLE, null));
            String intentId = confirmedIntent(c, 250);
            CyclicBarrier barrier = new CyclicBarrier(2);
            Callable<Boolean> task = () -> {
                barrier.await(10, TimeUnit.SECONDS);
                return apply(intentId);
            };
            ExecutorService pool = Executors.newFixedThreadPool(2);
            List<Boolean> results = new ArrayList<>();
            try {
                for (Future<Boolean> f : pool.invokeAll(List.of(task, task))) {
                    results.add(f.get());
                }
            } finally {
                pool.shutdownNow();
            }
            assertEquals(1, results.stream().filter(Boolean::booleanValue).count(), "exactly one application wins");
            assertEquals(250, cleared(c));
            assertTrue(claimed(intentId));
        }
    }

    @Test
    void sameIntentAppliedAgainAfterARestartIsANoOp() {
        String c = create(policy(TargetPolicy.FLEXIBLE, null));
        String intentId = confirmedIntent(c, 250);
        assertTrue(apply(intentId));
        try (ConfigurableApplicationContext restarted = new SpringApplicationBuilder(ConvocatoriaTestApplication.class)
                .properties("spring.data.mongodb.uri=" + MONGO.getReplicaSetUrl(),
                        "convocatoria.donation-intent.bank-transfer-expiration=PT72H",
                        "spring.main.banner-mode=off")
                .run()) {
            assertFalse(restarted.getBean(CampaignFundingLedgerService.class).applyFundsForIntent(intentId).appliedNow());
        }
        assertEquals(250, cleared(c));
    }

    @Test
    void concurrentStrictApplicationsThatTogetherExceedTargetApplyOnlyOne() throws Exception {
        for (int round = 0; round < 5; round++) {
            String c = create(policy(TargetPolicy.STRICT, null));
            assertTrue(apply(confirmedIntent(c, 400)));
            List<String> competing = List.of(confirmedIntent(c, 400), confirmedIntent(c, 400));
            CyclicBarrier barrier = new CyclicBarrier(2);
            List<Callable<Boolean>> tasks = competing.stream().<Callable<Boolean>>map(id -> () -> {
                barrier.await(10, TimeUnit.SECONDS);
                return apply(id);
            }).toList();
            ExecutorService pool = Executors.newFixedThreadPool(2);
            int applied = 0;
            int rejected = 0;
            try {
                for (Future<Boolean> f : pool.invokeAll(tasks)) {
                    try {
                        assertTrue(f.get());
                        applied++;
                    } catch (ExecutionException e) {
                        assertInstanceOf(CampaignFundingLimitExceededException.class, e.getCause());
                        rejected++;
                    }
                }
            } finally {
                pool.shutdownNow();
            }
            assertEquals(1, applied);
            assertEquals(1, rejected);
            assertEquals(800, cleared(c));
        }
    }

    // --- Espacio de claves: (commandType, commandId) ---

    /** D2: un commandId de cliente igual al intentId no ocupa la clave de la aplicación, en ningún orden. */
    @Test
    void clientCommandIdEqualToTheIntentIdDoesNotCollideWithTheApplication() {
        String c = create(policy(TargetPolicy.FLEXIBLE, null));
        String first = confirmedIntent(c, 100);
        String squatter = pendingIntent(c, 50, first);
        assertTrue(apply(first), "a client CREATE_DONATION_INTENT with commandId = intentId does not block it");
        assertEquals(100, cleared(c));
        assertEquals(squatter, pendingIntent(c, 50, first), "the client command keeps its own original result");

        String second = confirmedIntent(c, 70);
        assertTrue(apply(second));
        String afterApplication = pendingIntent(c, 30, second);
        assertEquals(DonationIntentStatus.PENDING, status(afterApplication),
                "a client command with commandId = an applied intentId runs normally");

        Set<Object> ids = mongoTemplate.findAll(Document.class, ProcessedCommandDocument.COLLECTION).stream()
                .map(d -> d.get("_id")).collect(Collectors.toSet());
        assertTrue(ids.contains(first) && ids.contains(second));
        assertTrue(ids.contains(MongoProcessedCommandAdapter.systemKey(CommandType.APPLY_FUNDS, first)));
        assertTrue(ids.contains(MongoProcessedCommandAdapter.systemKey(CommandType.APPLY_FUNDS, second)));
    }

    @Test
    void systemCommandTypeCannotEnterThroughTheClientPath() {
        assertThrows(IllegalArgumentException.class,
                () -> executor.execute("any", CommandType.APPLY_FUNDS, () -> Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> processedCommands.claim("any", CommandType.APPLY_FUNDS));
        assertThrows(IllegalArgumentException.class,
                () -> processedCommands.claimSystemCommand(CommandType.CREATE_DONATION_INTENT, "any"));
        assertEquals(0, processedCommandCount());
    }

    // --- FUNDING_REJECTED: rechazo permanente, terminal ---

    @Test
    void strictLimitRejectionEndsInFundingRejectedAndCannotBeAppliedAgain() {
        String c = create(policy(TargetPolicy.STRICT, null));
        assertTrue(apply(confirmedIntent(c, 900)));
        String excess = confirmedIntent(c, 200);
        assertThrows(CampaignFundingLimitExceededException.class, () -> apply(excess));

        assertTrue(service.rejectFundingIfPermanentlyUnfundable(excess));

        assertEquals(DonationIntentStatus.FUNDING_REJECTED, status(excess));
        assertThrows(DonationIntentNotConfirmedException.class, () -> apply(excess));
        assertFalse(service.rejectFundingIfPermanentlyUnfundable(excess), "already terminal");
        assertFalse(claimed(excess));
        assertEquals(900, cleared(c));
    }

    /** Enmienda 2 §4: con R4 pendiente, CLOSE_ON_TARGET + CLOSE no es rechazo permanente; la intención sigue CONFIRMED. */
    @Test
    void closeOnTargetCloseDoesNotEndInFundingRejectedWhileR4IsPending() {
        String c = create(policy(TargetPolicy.CLOSE_ON_TARGET, OnTargetReached.CLOSE));
        String intentId = confirmedIntent(c, 100);
        assertThrows(CloseOnTargetCloseNotSupportedException.class, () -> apply(intentId));
        assertFalse(service.rejectFundingIfPermanentlyUnfundable(intentId));
        assertConfirmedWithoutApplication(intentId);
        assertThrows(CloseOnTargetCloseNotSupportedException.class, () -> apply(intentId));
        assertEquals(0, cleared(c));
    }

    @Test
    void rejectionIsNotMarkedWhenTheIntentStillFitsOrIsAlreadyApplied() {
        String c = create(policy(TargetPolicy.STRICT, null));
        String fits = confirmedIntent(c, 600);
        assertFalse(service.rejectFundingIfPermanentlyUnfundable(fits));
        assertEquals(DonationIntentStatus.CONFIRMED, status(fits));
        assertTrue(apply(fits));
        assertFalse(service.rejectFundingIfPermanentlyUnfundable(fits), "an applied intent is never rejected");
        assertEquals(DonationIntentStatus.CONFIRMED, status(fits));

        String flexible = create(policy(TargetPolicy.FLEXIBLE, null));
        assertFalse(service.rejectFundingIfPermanentlyUnfundable(confirmedIntent(flexible, 5000)));
        assertFalse(service.rejectFundingIfPermanentlyUnfundable(pendingIntent(flexible, 10)), "not CONFIRMED");
    }

    /** Un conflicto transitorio sigue el reintento y nunca termina en FUNDING_REJECTED. */
    @Test
    void transientFailureNeverEndsInFundingRejected() {
        String c = create(policy(TargetPolicy.FLEXIBLE, null));
        String intentId = confirmedIntent(c, 250);
        doThrow(transientWriteConflict()).when(ledgers).incrementUnconditionally(anyString(), anyLong());

        MongoException failure = assertThrows(MongoException.class, () -> apply(intentId));
        assertTrue(failure.hasErrorLabel(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL));
        verify(ledgers, times(ConvocatoriaTransactionRetryHelper.MAX_ATTEMPTS)).incrementUnconditionally(anyString(), anyLong());
        assertConfirmedWithoutApplication(intentId);
        assertFalse(service.rejectFundingIfPermanentlyUnfundable(intentId));
        assertEquals(DonationIntentStatus.CONFIRMED, status(intentId));

        Mockito.reset(ledgers);
        assertTrue(apply(intentId), "once the transient condition passes, the intent is applied");
        assertEquals(250, cleared(c));
    }

    /**
     * Separación de mecanismos (Enmienda 2 §3.2; Enmienda 1 §6): dentro de una transacción externa no hay reintento
     * interno; el error transitorio llega a la transacción superior tras un único intento, sin efectos. Fuera de ella,
     * la ruta autónoma sí reintenta ({@link #transientFailureNeverEndsInFundingRejected()}).
     */
    @Test
    void insideAnExternalTransactionATransientFailureIsPropagatedWithoutInternalRetry() {
        String c = create(policy(TargetPolicy.FLEXIBLE, null));
        String intentId = confirmedIntent(c, 250);
        doThrow(transientWriteConflict()).when(ledgers).incrementUnconditionally(anyString(), anyLong());

        MongoException failure = assertThrows(MongoException.class,
                () -> new TransactionTemplate(transactionManager).executeWithoutResult(s -> apply(intentId)));

        assertTrue(failure.hasErrorLabel(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL));
        verify(ledgers, times(1)).incrementUnconditionally(anyString(), anyLong());
        assertConfirmedWithoutApplication(intentId);
        assertEquals(0, cleared(c));
    }

    /** Aplicación y marca de rechazo en carrera sobre la misma intención: nunca aplicada y FUNDING_REJECTED a la vez. */
    @Test
    void applicationAndRejectionRacingNeverLeaveAnAppliedRejectedIntent() throws Exception {
        for (int round = 0; round < 5; round++) {
            String strict = create(policy(TargetPolicy.STRICT, null));
            assertTrue(apply(confirmedIntent(strict, 900)));
            String excess = confirmedIntent(strict, 200);
            String fits = confirmedIntent(create(policy(TargetPolicy.STRICT, null)), 300);
            for (String intentId : List.of(excess, fits)) {
                CyclicBarrier barrier = new CyclicBarrier(2);
                ExecutorService pool = Executors.newFixedThreadPool(2);
                try {
                    List<Future<Boolean>> results = pool.invokeAll(List.of(
                            () -> { barrier.await(10, TimeUnit.SECONDS); return apply(intentId); },
                            () -> { barrier.await(10, TimeUnit.SECONDS);
                                return service.rejectFundingIfPermanentlyUnfundable(intentId); }));
                    for (Future<Boolean> f : results) {
                        try {
                            f.get();
                        } catch (ExecutionException e) {
                            assertInstanceOf(CampaignFundingLimitExceededException.class, e.getCause());
                        }
                    }
                } finally {
                    pool.shutdownNow();
                }
                boolean applied = claimed(intentId);
                boolean rejected = status(intentId) == DonationIntentStatus.FUNDING_REJECTED;
                assertFalse(applied && rejected, "an intent is never both applied and FUNDING_REJECTED");
            }
            assertEquals(DonationIntentStatus.FUNDING_REJECTED, status(excess));
            assertFalse(claimed(excess));
            assertTrue(claimed(fits));
            assertEquals(DonationIntentStatus.CONFIRMED, status(fits));
            assertEquals(900, cleared(strict));
        }
    }

    // --- Descubrimiento de intenciones CONFIRMED sin aplicar (D2) ---

    /**
     * ADR-043, parte de convocatoria: una intención confirmada cuya aplicación no llegó a ejecutarse (el proceso murió
     * tras confirmar) sigue siendo recuperable desde un contexto nuevo, y se aplica una sola vez.
     */
    @Test
    void confirmedIntentLeftUnappliedIsRecoveredAfterARestart() {
        String c = create(policy(TargetPolicy.FLEXIBLE, null));
        String intentId = confirmedIntent(c, 250);
        try (ConfigurableApplicationContext restarted = new SpringApplicationBuilder(ConvocatoriaTestApplication.class)
                .properties("spring.data.mongodb.uri=" + MONGO.getReplicaSetUrl(),
                        "convocatoria.donation-intent.bank-transfer-expiration=PT72H",
                        "spring.main.banner-mode=off")
                .run()) {
            CampaignFundingLedgerService recovered = restarted.getBean(CampaignFundingLedgerService.class);
            List<String> pending = recovered.findConfirmedPendingApplication(100).stream()
                    .map(DonationIntent::getIntentId).toList();
            assertEquals(List.of(intentId), pending);
            assertTrue(recovered.applyFundsForIntent(intentId).appliedNow());
            assertTrue(recovered.findConfirmedPendingApplication(100).isEmpty());
        }
        assertEquals(250, cleared(c));
    }

    /** ADR-043, parte de convocatoria: dos workers que consultan y aplican el mismo lote a la vez no duplican fondos. */
    @Test
    void twoRecoveryWorkersProcessingTheSameBatchApplyEachIntentOnce() throws Exception {
        String c = create(policy(TargetPolicy.FLEXIBLE, null));
        List<String> confirmed = List.of(confirmedIntent(c, 100), confirmedIntent(c, 200), confirmedIntent(c, 300));
        CyclicBarrier barrier = new CyclicBarrier(2);
        Callable<Integer> worker = () -> {
            barrier.await(10, TimeUnit.SECONDS);
            int applied = 0;
            for (DonationIntent intent : service.findConfirmedPendingApplication(100)) {
                try {
                    if (service.applyFundsForIntent(intent.getIntentId()).appliedNow()) {
                        applied++;
                    }
                } catch (RuntimeException transientConflict) {
                    // el siguiente ciclo la recupera; aquí basta con no duplicar
                }
            }
            return applied;
        };
        ExecutorService pool = Executors.newFixedThreadPool(2);
        int total = 0;
        try {
            for (Future<Integer> f : pool.invokeAll(List.of(worker, worker))) {
                total += f.get();
            }
        } finally {
            pool.shutdownNow();
        }
        for (DonationIntent intent : service.findConfirmedPendingApplication(100)) {
            assertTrue(service.applyFundsForIntent(intent.getIntentId()).appliedNow(), "a following cycle finishes what remains");
            total++;
        }
        assertEquals(3, total, "each intent applied exactly once across workers and cycles");
        assertEquals(600, cleared(c));
        confirmed.forEach(id -> assertTrue(claimed(id)));
    }

    /**
     * P9 (opción a, Enmienda 2 §4): mientras R4 no exista, una intención CONFIRMED de una convocatoria
     * CLOSE_ON_TARGET + CLOSE queda fuera de la cola automática y nunca pasa a FUNDING_REJECTED; una intención
     * financiable sí aparece.
     */
    @Test
    void closeOnTargetCloseIntentsStayOutOfTheRecoveryQueueWithoutBeingRejected() {
        String close = create(policy(TargetPolicy.CLOSE_ON_TARGET, OnTargetReached.CLOSE));
        String notApplicable = confirmedIntent(close, 100);
        String fundable = confirmedIntent(create(policy(TargetPolicy.FLEXIBLE, null)), 100);
        String rejectExcess = confirmedIntent(create(policy(TargetPolicy.CLOSE_ON_TARGET, OnTargetReached.REJECT_EXCESS)), 50);

        Set<String> found = service.findConfirmedPendingApplication(100).stream()
                .map(DonationIntent::getIntentId).collect(Collectors.toSet());

        assertEquals(Set.of(fundable, rejectExcess), found, "only fundable intents are queued");
        assertFalse(service.rejectFundingIfPermanentlyUnfundable(notApplicable));
        assertConfirmedWithoutApplication(notApplicable);
        assertFalse(service.findConfirmedPendingApplication(100).stream()
                .anyMatch(i -> i.getIntentId().equals(notApplicable)));
    }

    @Test
    void findsOnlyConfirmedIntentsWhoseApplicationIsPending() {
        String c = create(policy(TargetPolicy.STRICT, null));
        String pending = pendingIntent(c, 10);
        String applied = confirmedIntent(c, 900);
        assertTrue(apply(applied));
        String rejected = confirmedIntent(c, 500);
        assertTrue(service.rejectFundingIfPermanentlyUnfundable(rejected));
        String waiting = confirmedIntent(c, 50);
        String waiting2 = confirmedIntent(c, 40);

        Set<String> found = service.findConfirmedPendingApplication(100).stream()
                .map(DonationIntent::getIntentId).collect(Collectors.toSet());

        assertEquals(Set.of(waiting, waiting2), found);
        assertFalse(found.contains(pending) || found.contains(applied) || found.contains(rejected));
        assertEquals(1, service.findConfirmedPendingApplication(1).size());
    }
}
