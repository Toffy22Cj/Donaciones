package com.traceability.convocatoria.application.service;

import com.traceability.convocatoria.application.audit.ConvocatoriaAuditAction;
import com.traceability.convocatoria.application.audit.ConvocatoriaAuditEntry;
import com.traceability.convocatoria.application.command.ConfirmDonationIntentCommand;
import com.traceability.convocatoria.application.command.CreateDonationIntentCommand;
import com.traceability.convocatoria.application.idempotency.CommandType;
import com.traceability.convocatoria.domain.model.ConvocatoriaConfiguration;
import com.traceability.convocatoria.domain.model.DonationIntent;
import com.traceability.convocatoria.domain.model.DonationIntentStatus;
import com.traceability.convocatoria.domain.model.OnTargetReached;
import com.traceability.convocatoria.domain.model.PaymentMethod;
import com.traceability.convocatoria.domain.model.TargetPolicy;
import com.traceability.convocatoria.infrastructure.persistence.mongo.document.DonationIntentDocument;
import com.traceability.convocatoria.support.AbstractConvocatoriaServiceIntegrationTest;
import com.traceability.convocatoria.support.ConvocatoriaScenarios;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-045 §2.3–§2.6 y Enmienda 2 §4 (C2), parte de {@code convocatoria}: marca {@code fundsAppliedAt}, motivo y
 * fecha de {@code FUNDING_REJECTED}, intentos fallidos, cuarentena con salida auditada, orden de equidad, índice parcial
 * de la consulta de recuperables y métricas.
 */
class FundsApplicationRecoveryIntegrationTest extends AbstractConvocatoriaServiceIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-10-07T10:00:00Z");

    @Autowired private ConvocatoriaLifecycleService lifecycle;
    @Autowired private DonationIntentService intents;
    @Autowired private CampaignFundingLedgerService ledgerService;
    @Autowired private FundsApplicationRecoveryService recovery;
    @Autowired private PlatformTransactionManager transactionManager;

    private String create(TargetPolicy policy, OnTargetReached onTargetReached) {
        ConvocatoriaConfiguration cfg = monetary(policy, onTargetReached, 1000L, PaymentMethod.BANK_TRANSFER);
        return ConvocatoriaScenarios.createConvocatoria(lifecycle, ADMIN, ORG, cfg);
    }

    private String confirmedIntent(String campaignRef, long amount) {
        String publicCode = convocatorias.findByCampaignRef(campaignRef).orElseThrow().getPublicCode();
        String intentId = intents.createDonationIntent(new CreateDonationIntentCommand(newCommandId(), publicCode,
                "donor-1", amount, "COP", PaymentMethod.BANK_TRANSFER)).intentId();
        assertTrue(intents.confirmDonationIntent(new ConfirmDonationIntentCommand(intentId, ADMIN, "BANK-" + intentId)));
        return intentId;
    }

    private DonationIntent intent(String intentId) {
        return donationIntents.findById(intentId).orElseThrow();
    }

    private List<String> pending(int limit) {
        return ledgerService.findConfirmedPendingApplication(limit).stream().map(DonationIntent::getIntentId).toList();
    }

    // --- fundsAppliedAt (ADR-045 §2.5) ---

    @Test
    void applicationWritesFundsAppliedAtWithTheClaim() {
        clock.set(T0);
        String c = create(TargetPolicy.FLEXIBLE, null);
        String intentId = confirmedIntent(c, 100);

        assertTrue(ledgerService.applyFundsForIntent(intentId).appliedNow());

        assertEquals(T0, intent(intentId).getApplicationTracking().fundsAppliedAt());
        assertTrue(processedCommands.findSystemCommand(CommandType.APPLY_FUNDS, intentId).isPresent());
        assertFalse(pending(100).contains(intentId));
    }

    @Test
    void externalRollbackLeavesNeitherFundsAppliedAtNorClaim() {
        String c = create(TargetPolicy.FLEXIBLE, null);
        String intentId = confirmedIntent(c, 100);

        assertThrows(IllegalStateException.class, () -> new TransactionTemplate(transactionManager).executeWithoutResult(s -> {
            ledgerService.applyFundsForIntent(intentId);
            throw new IllegalStateException("la génesis falla después del ledger");
        }));

        assertNull(intent(intentId).getApplicationTracking().fundsAppliedAt());
        assertFalse(processedCommands.findSystemCommand(CommandType.APPLY_FUNDS, intentId).isPresent());
        assertEquals(List.of(intentId), pending(100));
    }

    @Test
    void duplicateApplicationDoesNotRewriteFundsAppliedAt() {
        clock.set(T0);
        String c = create(TargetPolicy.FLEXIBLE, null);
        String intentId = confirmedIntent(c, 100);
        assertTrue(ledgerService.applyFundsForIntent(intentId).appliedNow());

        clock.set(T0.plusSeconds(60));
        assertFalse(ledgerService.applyFundsForIntent(intentId).appliedNow());

        assertEquals(T0, intent(intentId).getApplicationTracking().fundsAppliedAt());
    }

    // --- FUNDING_REJECTED con motivo y fecha (C2) ---

    @Test
    void fundingRejectionRecordsReasonAndDateInTheSameWrite() {
        clock.set(T0);
        String c = create(TargetPolicy.STRICT, null);
        assertTrue(ledgerService.applyFundsForIntent(confirmedIntent(c, 900)).appliedNow());
        String excess = confirmedIntent(c, 200);

        assertTrue(ledgerService.rejectFundingIfPermanentlyUnfundable(excess));

        DonationIntent rejected = intent(excess);
        assertEquals(DonationIntentStatus.FUNDING_REJECTED, rejected.getStatus());
        assertEquals(T0, rejected.getFundingRejection().rejectedAt());
        assertEquals(CampaignFundingLedgerService.FUNDING_LIMIT_EXCEEDED_REASON, rejected.getFundingRejection().reason());
    }

    // --- Intentos fallidos y cuarentena (§2.3) ---

    @Test
    void failuresIncrementAttemptsAndKeepTheFirstAttemptDate() {
        String c = create(TargetPolicy.FLEXIBLE, null);
        String intentId = confirmedIntent(c, 100);

        clock.set(T0);
        DonationIntent.ApplicationTracking first = recovery.recordApplicationFailure(intentId,
                "MongoTransientError", false).orElseThrow();
        clock.set(T0.plusSeconds(30));
        DonationIntent.ApplicationTracking second = recovery.recordApplicationFailure(intentId,
                "ConcurrencyConflictException", false).orElseThrow();

        assertEquals(1, first.attempts());
        assertEquals(2, second.attempts());
        assertEquals(T0, second.firstAttemptAt());
        assertEquals(T0.plusSeconds(30), second.lastAttemptAt());
        assertEquals("ConcurrencyConflictException", second.lastError());
        assertFalse(second.quarantined());
        assertTrue(pending(100).contains(intentId), "a retryable failure stays in the queue");
    }

    @Test
    void failureIsNotRecordedOnAnAppliedOrRejectedIntent() {
        String c = create(TargetPolicy.STRICT, null);
        String applied = confirmedIntent(c, 900);
        assertTrue(ledgerService.applyFundsForIntent(applied).appliedNow());
        String rejected = confirmedIntent(c, 500);
        assertTrue(ledgerService.rejectFundingIfPermanentlyUnfundable(rejected));

        assertTrue(recovery.recordApplicationFailure(applied, "X", true).isEmpty());
        assertTrue(recovery.recordApplicationFailure(rejected, "X", true).isEmpty());
        assertEquals(0, intent(applied).getApplicationTracking().attempts());
        assertFalse(intent(rejected).getApplicationTracking().quarantined());
    }

    @Test
    void quarantinedIntentLeavesTheQueueAndIsCounted() {
        String c = create(TargetPolicy.FLEXIBLE, null);
        String anomaly = confirmedIntent(c, 100);
        String healthy = confirmedIntent(c, 100);

        assertTrue(recovery.recordApplicationFailure(anomaly, "CampaignNotFoundException", true).orElseThrow().quarantined());

        assertEquals(List.of(healthy), pending(100));
        assertEquals(1, recovery.metrics().quarantined());
    }

    @Test
    void exhaustionQuarantineIsConditional() {
        String c = create(TargetPolicy.FLEXIBLE, null);
        String intentId = confirmedIntent(c, 100);
        recovery.recordApplicationFailure(intentId, "MongoTransientError", false);

        assertTrue(recovery.quarantineApplication(intentId));
        assertFalse(recovery.quarantineApplication(intentId), "already quarantined");
        assertTrue(intent(intentId).getApplicationTracking().quarantined());
    }

    @Test
    void releaseResetsCountersReturnsToTheQueueAndIsAudited() {
        String c = create(TargetPolicy.FLEXIBLE, null);
        String intentId = confirmedIntent(c, 100);
        clock.set(T0);
        recovery.recordApplicationFailure(intentId, "CampaignNotFoundException", true);
        int auditBefore = auditLog.findByCampaignRef(c).size();

        assertTrue(recovery.releaseApplicationQuarantine(intentId, "operator-1", "ledger restored"));

        DonationIntent.ApplicationTracking t = intent(intentId).getApplicationTracking();
        assertFalse(t.quarantined());
        assertEquals(0, t.attempts());
        assertNull(t.firstAttemptAt());
        assertNull(t.lastError());
        assertEquals(List.of(intentId), pending(100));
        List<ConvocatoriaAuditEntry> audit = auditLog.findByCampaignRef(c);
        assertEquals(auditBefore + 1, audit.size());
        ConvocatoriaAuditEntry entry = audit.get(audit.size() - 1);
        assertEquals(ConvocatoriaAuditAction.APPLICATION_QUARANTINE_RELEASED, entry.action());
        assertEquals("operator-1", entry.actorRef());
        assertEquals(intentId, entry.targetRef());
        assertEquals("ledger restored", entry.details().get("reason"));
        assertEquals("CampaignNotFoundException", entry.details().get("previousError"));
    }

    @Test
    void releaseOfANonQuarantinedIntentDoesNothingAndIsNotAudited() {
        String c = create(TargetPolicy.FLEXIBLE, null);
        String intentId = confirmedIntent(c, 100);
        int auditBefore = auditLog.findByCampaignRef(c).size();

        assertFalse(recovery.releaseApplicationQuarantine(intentId, "operator-1", "nothing to release"));

        assertEquals(auditBefore, auditLog.findByCampaignRef(c).size());
        assertThrows(IllegalArgumentException.class, () -> recovery.releaseApplicationQuarantine(intentId, " ", "r"));
    }

    // --- Orden de equidad (§2.4) ---

    @Test
    void failingIntentsMoveBehindHealthyOnes() {
        String c = create(TargetPolicy.FLEXIBLE, null);
        List<String> failing = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            failing.add(confirmedIntent(c, 10));
        }
        String healthy = confirmedIntent(c, 10);
        clock.set(T0);
        failing.forEach(id -> recovery.recordApplicationFailure(id, "MongoTransientError", false));

        // Con un lote del tamaño de las fallidas, la sana entra primero aunque su intentId sea posterior.
        assertEquals(healthy, pending(3).get(0));
        // Entre fallidas con los mismos intentos, primero la que lleva más tiempo sin intentarse.
        clock.set(T0.plusSeconds(10));
        recovery.recordApplicationFailure(failing.get(0), "MongoTransientError", false);
        clock.set(T0.plusSeconds(20));
        recovery.recordApplicationFailure(failing.get(1), "MongoTransientError", false);
        recovery.recordApplicationFailure(failing.get(2), "MongoTransientError", false);
        List<String> order = pending(10);
        assertEquals(List.of(healthy, failing.get(0)), order.subList(0, 2));
        // failing(1) y failing(2) empatan en intentos y fecha: desempata el intentId.
        List<String> tied = new ArrayList<>(List.of(failing.get(1), failing.get(2)));
        tied.sort(String::compareTo);
        assertEquals(tied, order.subList(2, 4));
    }

    // --- Índice parcial (§2.5) ---

    @Test
    void recoveryQueryUsesThePartialIndex() {
        String c = create(TargetPolicy.FLEXIBLE, null);
        confirmedIntent(c, 10);
        Document explain = mongoTemplate.getDb().runCommand(new Document("explain", new Document()
                .append("find", DonationIntentDocument.COLLECTION)
                .append("filter", new Document("status", "CONFIRMED").append("fundsAppliedAt", null)
                        .append("applicationQuarantined", new Document("$ne", true)))
                .append("sort", new Document("applicationAttempts", 1).append("lastApplicationAttemptAt", 1)
                        .append("_id", 1)))
                .append("verbosity", "queryPlanner"));
        String plan = explain.toJson();
        assertTrue(plan.contains(DonationIntentDocument.PENDING_APPLICATION_INDEX), plan);
        assertFalse(plan.contains("\"stage\": \"SORT\""), "the index provides the order: " + plan);
    }

    // --- Métricas (§2.6) ---

    @Test
    void metricsReportP9ExclusionsQuarantineAndOldestPending() {
        clock.set(T0);
        String flexible = create(TargetPolicy.FLEXIBLE, null);
        String oldest = confirmedIntent(flexible, 10);
        clock.set(T0.plusSeconds(60));
        confirmedIntent(flexible, 10);
        String close = create(TargetPolicy.CLOSE_ON_TARGET, OnTargetReached.CLOSE);
        confirmedIntent(close, 10);
        String quarantined = confirmedIntent(flexible, 10);
        recovery.recordApplicationFailure(quarantined, "CampaignNotFoundException", true);

        FundsApplicationRecoveryService.RecoveryMetrics metrics = recovery.metrics();

        assertEquals(1, metrics.excludedByCloseOnTargetClose());
        assertEquals(1, metrics.quarantined());
        assertEquals(T0, metrics.oldestPendingConfirmedAt());
        assertNotNull(intent(oldest).getConfirmation());
    }

    @Test
    void metricsAreEmptyWhenNothingIsPending() {
        FundsApplicationRecoveryService.RecoveryMetrics metrics = recovery.metrics();
        assertEquals(0, metrics.excludedByCloseOnTargetClose());
        assertEquals(0, metrics.quarantined());
        assertNull(metrics.oldestPendingConfirmedAt());
    }
}
