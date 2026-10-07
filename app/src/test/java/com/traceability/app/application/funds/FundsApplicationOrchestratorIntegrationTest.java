package com.traceability.app.application.funds;

import com.traceability.app.TraceabilityApplication;
import com.traceability.contracts.authorization.AuthorizationPrincipal;
import com.traceability.contracts.authorization.AuthorizationRole;
import com.traceability.contracts.authorization.IdentityPrincipalPort;
import com.traceability.convocatoria.application.audit.ConvocatoriaAuditAction;
import com.traceability.convocatoria.application.command.ConfirmDonationIntentCommand;
import com.traceability.convocatoria.application.command.CreateConvocatoriaCommand;
import com.traceability.convocatoria.application.command.CreateDonationIntentCommand;
import com.traceability.convocatoria.application.idempotency.CommandType;
import com.traceability.convocatoria.application.port.out.ProcessedCommandPort;
import com.traceability.convocatoria.application.port.out.CampaignFundingLedgerRepositoryPort;
import com.traceability.convocatoria.application.port.out.ConvocatoriaAuditLogPort;
import com.traceability.convocatoria.application.port.out.ConvocatoriaRepositoryPort;
import com.traceability.convocatoria.application.port.out.DonationIntentRepositoryPort;
import com.traceability.convocatoria.application.port.out.OrganizationVerificationPort;
import com.traceability.convocatoria.application.service.CampaignFundingLedgerService;
import com.traceability.convocatoria.application.service.ConvocatoriaLifecycleService;
import com.traceability.convocatoria.application.service.DonationIntentService;
import com.traceability.convocatoria.application.service.FundsApplicationRecoveryService;
import com.traceability.convocatoria.domain.model.ConvocatoriaConfiguration;
import com.traceability.convocatoria.domain.model.DonationIntent;
import com.traceability.convocatoria.domain.model.DonationIntentStatus;
import com.traceability.convocatoria.domain.model.DonationType;
import com.traceability.convocatoria.domain.model.PaymentMethod;
import com.traceability.convocatoria.domain.model.TargetPolicy;
import com.traceability.convocatoria.domain.model.Visibility;
import com.traceability.core.application.port.out.EventStorePort;
import com.traceability.core.application.exception.ConcurrencyConflictException;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * ADR-045 §6, parte de {@code app}: Tx 1 → Tx 2 con {@code convocatoria} y {@code core} reales sobre un replica set
 * MongoDB. Identidad se sustituye por mocks (la verificación de organización tiene su propio test de wiring); el resto
 * del contexto es el de producción. El scheduler se habilita para el test con un retardo inicial que nunca se cumple:
 * las ejecuciones se lanzan con {@link FundsApplicationRecoveryScheduler#runOnce()}.
 */
@SpringBootTest(classes = TraceabilityApplication.class)
@Testcontainers
@Import(FundsApplicationOrchestratorIntegrationTest.ClockConfig.class)
@TestPropertySource(properties = {
    "crypto.anchor.poll.delay=9999999",
    "crypto.anchor.submit.delay=9999999",
    "crypto.anchor.stuck-monitor.delay=9999999",
    "crypto.web3j.private-key=0x1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef",
    "crypto.web3j.node-url=http://dummy-node",
    "spring.ai.openai.api-key=dummy-api-key",
    "convocatoria.funds-application.recovery.enabled=true",
    "convocatoria.funds-application.recovery.initial-delay=PT1000H",
    "convocatoria.funds-application.recovery.max-attempts=3",
    "convocatoria.funds-application.recovery.retry-window=PT4H"
})
class FundsApplicationOrchestratorIntegrationTest {

    private static final String ORG = "org-funds";
    private static final String ADMIN = "admin-funds";
    private static final Instant T0 = Instant.parse("2026-10-07T10:00:00Z");

    @Container
    static MongoDBContainer mongoDBContainer = new MongoDBContainer(DockerImageName.parse("mongo:6.0"))
            .withCommand("--replSet", "rs0");

    @DynamicPropertySource
    static void setProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongoDBContainer::getReplicaSetUrl);
    }

    @TestConfiguration
    static class ClockConfig {
        @Bean
        @Primary
        TestClock testClock() {
            return new TestClock();
        }
    }

    @MockitoBean private IdentityPrincipalPort identityPrincipalPort;
    @MockitoBean private OrganizationVerificationPort organizationVerificationPort;
    /** Punto de inyección de fallos dentro de la génesis (Tx 2); sin proxy transaccional propio. */
    @MockitoSpyBean private EventStorePort eventStore;

    @Autowired private ApplicationContext context;
    @Autowired private TestClock clock;
    @Autowired private MongoTemplate mongoTemplate;
    @Autowired private ConvocatoriaLifecycleService lifecycle;
    @Autowired private DonationIntentService donationIntentService;
    @Autowired private CampaignFundingLedgerService ledgerService;
    @Autowired private FundsApplicationRecoveryService recovery;
    @Autowired private ConvocatoriaRepositoryPort convocatorias;
    @Autowired private CampaignFundingLedgerRepositoryPort ledgers;
    @Autowired private DonationIntentRepositoryPort donationIntents;
    @Autowired private ConvocatoriaAuditLogPort auditLog;
    @Autowired private FundsApplicationOrchestrator orchestrator;
    @Autowired private ConfirmAndApplyDonationIntentUseCase confirmAndApply;
    @Autowired private FundsApplicationRecoveryScheduler scheduler;
    @Autowired private FundsApplicationAdminOperations adminOperations;
    @Autowired private FundsApplicationProperties properties;
    @Autowired private ProcessedCommandPort processedCommands;

    @BeforeEach
    void setup() {
        clock.reset();
        mongoTemplate.getCollection("donation_intents").deleteMany(new Document());
        when(identityPrincipalPort.resolvePrincipal(ADMIN)).thenReturn(
                new AuthorizationPrincipal(ADMIN, ORG, Set.of(AuthorizationRole.ADMINISTRATOR), null));
        when(organizationVerificationPort.isVerified(ORG)).thenReturn(true);
    }

    // --- Disparo inmediato y recuperación (§2.1, §2.2) ---

    @Test
    void confirmation_appliesFundsImmediately_inASingleTransaction() {
        String campaign = campaign(TargetPolicy.FLEXIBLE, 1000L);
        String intentId = pendingIntent(campaign, 300);

        ConfirmAndApplyDonationIntentUseCase.Result result = confirmAndApply.execute(confirm(intentId));

        assertThat(result.confirmed()).isTrue();
        assertThat(result.application()).isEqualTo(FundsApplicationOutcome.APPLIED);
        assertApplied(intentId, campaign, 300);
    }

    @Test
    void repeatedConfirmation_doesNotApplyAgain() {
        String campaign = campaign(TargetPolicy.FLEXIBLE, 1000L);
        String intentId = pendingIntent(campaign, 300);
        confirmAndApply.execute(confirm(intentId));

        ConfirmAndApplyDonationIntentUseCase.Result again = confirmAndApply.execute(confirm(intentId));

        assertThat(again.confirmed()).isFalse();
        assertThat(again.application()).isNull();
        assertApplied(intentId, campaign, 300);
    }

    @Test
    void crashBetweenTx1AndTx2_isRecoveredByTheScheduler() {
        String campaign = campaign(TargetPolicy.FLEXIBLE, 1000L);
        String intentId = pendingIntent(campaign, 250);
        // Solo Tx 1: el proceso "cae" antes del disparo inmediato.
        assertThat(donationIntentService.confirmDonationIntent(confirm(intentId))).isTrue();
        assertThat(fundEvents(intentId)).isZero();
        assertThat(clearedAmount(campaign)).isZero();

        FundsApplicationRecoveryScheduler.RunSummary run = scheduler.runOnce();

        assertThat(run.count(FundsApplicationOutcome.APPLIED)).isEqualTo(1);
        assertApplied(intentId, campaign, 250);
        assertThat(scheduler.runOnce().batchSize()).as("ya no es recuperable").isZero();
    }

    @Test
    void failedTx2_rollsBackLedgerAndClaim_andStaysRecoverable() {
        String campaign = campaign(TargetPolicy.FLEXIBLE, 1000L);
        String intentId = pendingIntent(campaign, 400);
        failGenesisOf(intentId, new ConcurrencyConflictException("conflicto simulado"));

        ConfirmAndApplyDonationIntentUseCase.Result result = confirmAndApply.execute(confirm(intentId));

        assertThat(result.confirmed()).as("la confirmación no depende de la aplicación").isTrue();
        assertThat(result.application()).isEqualTo(FundsApplicationOutcome.RETRYABLE_FAILURE);
        DonationIntent intent = intent(intentId);
        assertThat(intent.getStatus()).isEqualTo(DonationIntentStatus.CONFIRMED);
        assertThat(intent.getApplicationTracking().fundsAppliedAt()).isNull();
        assertThat(intent.getApplicationTracking().attempts()).isEqualTo(1);
        assertThat(intent.getApplicationTracking().lastError()).isEqualTo("ConcurrencyConflictException");
        assertThat(clearedAmount(campaign)).as("rollback del ledger").isZero();
        assertThat(applyFundsClaims(intentId)).as("rollback del reclamo").isZero();
        assertThat(fundEvents(intentId)).isZero();

        Mockito.reset(eventStore);
        assertThat(scheduler.runOnce().count(FundsApplicationOutcome.APPLIED)).isEqualTo(1);
        assertApplied(intentId, campaign, 400);
    }

    // --- Una sola aplicación con concurrencia (§2.7) ---

    @Test
    void immediateTriggerAndSchedulerRacing_applyExactlyOnce() throws Exception {
        String campaign = campaign(TargetPolicy.FLEXIBLE, 100_000L);
        String intentId = pendingIntent(campaign, 500);
        assertThat(donationIntentService.confirmDonationIntent(confirm(intentId))).isTrue();

        List<Object> results = race(List.of(
                () -> orchestrator.apply(intentId),
                scheduler::runOnce));

        FundsApplicationOutcome immediate = (FundsApplicationOutcome) results.get(0);
        int schedulerApplied = ((FundsApplicationRecoveryScheduler.RunSummary) results.get(1))
                .count(FundsApplicationOutcome.APPLIED);
        assertThat((immediate == FundsApplicationOutcome.APPLIED ? 1 : 0) + schedulerApplied).isEqualTo(1);
        assertThat(immediate).isIn(FundsApplicationOutcome.APPLIED, FundsApplicationOutcome.ALREADY_APPLIED);
        assertApplied(intentId, campaign, 500);
    }

    @Test
    void twoSchedulerInstances_applyEachIntentExactlyOnce() throws Exception {
        String campaign = campaign(TargetPolicy.FLEXIBLE, 1_000_000L);
        List<String> intentIds = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            String intentId = pendingIntent(campaign, 100 + i);
            assertThat(donationIntentService.confirmDonationIntent(confirm(intentId))).isTrue();
            intentIds.add(intentId);
        }

        List<Object> results = race(List.of(scheduler::runOnce, scheduler::runOnce));

        int applied = results.stream()
                .mapToInt(r -> ((FundsApplicationRecoveryScheduler.RunSummary) r).count(FundsApplicationOutcome.APPLIED))
                .sum();
        assertThat(applied).isEqualTo(intentIds.size());
        long expected = 0;
        for (String intentId : intentIds) {
            assertThat(fundEvents(intentId)).as("una sola génesis por intención").isEqualTo(1);
            assertThat(applyFundsClaims(intentId)).isEqualTo(1);
            expected += intent(intentId).getAmount();
        }
        assertThat(clearedAmount(campaign)).isEqualTo(expected);
    }

    @Test
    void noOpOnAnAppliedIntent_doesNotCountAsAnAttempt() {
        String campaign = campaign(TargetPolicy.FLEXIBLE, 1000L);
        String intentId = pendingIntent(campaign, 100);
        confirmAndApply.execute(confirm(intentId));

        assertThat(orchestrator.apply(intentId)).isEqualTo(FundsApplicationOutcome.ALREADY_APPLIED);

        assertThat(intent(intentId).getApplicationTracking().attempts()).isZero();
        assertApplied(intentId, campaign, 100);
    }

    // --- Taxonomía (§2.3) ---

    @Test
    void strictLimitExceeded_isFundingRejectedWithReasonAndDate() {
        clock.set(T0);
        String campaign = campaign(TargetPolicy.STRICT, 1000L);
        String first = pendingIntent(campaign, 900);
        String excess = pendingIntent(campaign, 200);
        assertThat(confirmAndApply.execute(confirm(first)).application()).isEqualTo(FundsApplicationOutcome.APPLIED);

        ConfirmAndApplyDonationIntentUseCase.Result result = confirmAndApply.execute(confirm(excess));

        assertThat(result.application()).isEqualTo(FundsApplicationOutcome.FUNDING_REJECTED);
        DonationIntent rejected = intent(excess);
        assertThat(rejected.getStatus()).isEqualTo(DonationIntentStatus.FUNDING_REJECTED);
        assertThat(rejected.getFundingRejection().rejectedAt()).isEqualTo(T0);
        assertThat(rejected.getFundingRejection().reason())
                .isEqualTo(CampaignFundingLedgerService.FUNDING_LIMIT_EXCEEDED_REASON);
        assertThat(fundEvents(excess)).isZero();
        assertThat(clearedAmount(campaign)).isEqualTo(900);
        assertThat(scheduler.runOnce().batchSize()).as("FUNDING_REJECTED sale de la cola").isZero();
    }

    @Test
    void anomaly_goesToQuarantine_neverToFundingRejected() {
        String campaign = campaign(TargetPolicy.STRICT, 1000L);
        String intentId = pendingIntent(campaign, 100);
        failGenesisOf(intentId, new IllegalStateException("invariante rota"));

        assertThat(confirmAndApply.execute(confirm(intentId)).application())
                .isEqualTo(FundsApplicationOutcome.QUARANTINED);

        DonationIntent intent = intent(intentId);
        assertThat(intent.getStatus()).isEqualTo(DonationIntentStatus.CONFIRMED);
        assertThat(intent.getFundingRejection()).isNull();
        assertThat(intent.getApplicationTracking().quarantined()).isTrue();
        assertThat(intent.getApplicationTracking().lastError()).isEqualTo("IllegalStateException");
        assertThat(clearedAmount(campaign)).isZero();
        FundsApplicationRecoveryScheduler.RunSummary run = scheduler.runOnce();
        assertThat(run.batchSize()).as("la cuarentena sale de la cola").isZero();
        assertThat(run.metrics().quarantined()).isEqualTo(1);
    }

    @Test
    void retryableFailure_isQuarantinedAfterMaxAttempts() {
        String campaign = campaign(TargetPolicy.FLEXIBLE, 1000L);
        String intentId = pendingIntent(campaign, 100);
        failGenesisOf(intentId, new ConcurrencyConflictException("conflicto persistente"));

        assertThat(confirmAndApply.execute(confirm(intentId)).application())
                .isEqualTo(FundsApplicationOutcome.RETRYABLE_FAILURE);
        assertThat(scheduler.runOnce().count(FundsApplicationOutcome.RETRYABLE_FAILURE)).isEqualTo(1);
        FundsApplicationRecoveryScheduler.RunSummary third = scheduler.runOnce();

        assertThat(properties.maxAttempts()).isEqualTo(3);
        assertThat(third.count(FundsApplicationOutcome.QUARANTINED)).isEqualTo(1);
        DonationIntent intent = intent(intentId);
        assertThat(intent.getApplicationTracking().attempts()).isEqualTo(3);
        assertThat(intent.getApplicationTracking().quarantined()).isTrue();
        assertThat(intent.getStatus()).isEqualTo(DonationIntentStatus.CONFIRMED);
    }

    @Test
    void retryableFailure_isQuarantinedOnceTheRetryWindowExpires() {
        clock.set(T0);
        String campaign = campaign(TargetPolicy.FLEXIBLE, 1000L);
        String intentId = pendingIntent(campaign, 100);
        failGenesisOf(intentId, new ConcurrencyConflictException("conflicto persistente"));
        assertThat(confirmAndApply.execute(confirm(intentId)).application())
                .isEqualTo(FundsApplicationOutcome.RETRYABLE_FAILURE);

        clock.set(T0.plus(Duration.ofHours(4)).plusSeconds(1));
        FundsApplicationRecoveryScheduler.RunSummary run = scheduler.runOnce();

        assertThat(run.count(FundsApplicationOutcome.QUARANTINED)).isEqualTo(1);
        DonationIntent intent = intent(intentId);
        assertThat(intent.getApplicationTracking().attempts()).as("por debajo de max-attempts").isEqualTo(2);
        assertThat(intent.getApplicationTracking().firstAttemptAt()).isEqualTo(T0);
        assertThat(intent.getApplicationTracking().quarantined()).isTrue();
    }

    // --- Equidad (§2.4) ---

    @Test
    void failingIntent_doesNotBlockHealthyOnes() {
        String campaign = campaign(TargetPolicy.FLEXIBLE, 100_000L);
        String failing = pendingIntent(campaign, 100);
        failGenesisOf(failing, new ConcurrencyConflictException("conflicto persistente"));
        assertThat(confirmAndApply.execute(confirm(failing)).application())
                .isEqualTo(FundsApplicationOutcome.RETRYABLE_FAILURE);
        String healthy = pendingIntent(campaign, 200);
        assertThat(donationIntentService.confirmDonationIntent(confirm(healthy))).isTrue();

        FundsApplicationRecoveryScheduler oneAtATime = new FundsApplicationRecoveryScheduler(ledgerService, recovery,
                orchestrator, new FundsApplicationProperties(true, Duration.ofMinutes(1), Duration.ofMinutes(1), 1,
                        Duration.ofHours(4), 10));
        FundsApplicationRecoveryScheduler.RunSummary run = oneAtATime.runOnce();

        assertThat(run.count(FundsApplicationOutcome.APPLIED)).as("la sana va primero").isEqualTo(1);
        assertApplied(healthy, campaign, 200);
        assertThat(intent(failing).getApplicationTracking().attempts()).isEqualTo(1);
    }

    // --- Salida de la cuarentena (§2.3) ---

    @Test
    void releaseByJmx_isAudited_andTheIntentIsAppliedOnTheNextRun() {
        String campaign = campaign(TargetPolicy.FLEXIBLE, 1000L);
        String intentId = pendingIntent(campaign, 100);
        failGenesisOf(intentId, new IllegalStateException("invariante rota"));
        confirmAndApply.execute(confirm(intentId));
        assertThat(intent(intentId).getApplicationTracking().quarantined()).isTrue();
        Mockito.reset(eventStore);

        assertThat(adminOperations.releaseApplicationQuarantine(intentId, "operador-1", "causa corregida")).isTrue();

        assertThat(auditLog.findByCampaignRef(campaign))
                .anyMatch(e -> e.action() == ConvocatoriaAuditAction.APPLICATION_QUARANTINE_RELEASED);
        assertThat(scheduler.runOnce().count(FundsApplicationOutcome.APPLIED)).isEqualTo(1);
        assertApplied(intentId, campaign, 100);
    }

    @Test
    void jmxBean_isExported() {
        assertThat(context.getBean(FundsApplicationAdminOperations.class)).isNotNull();
        assertThat(FundsApplicationAdminOperations.class
                .getAnnotation(org.springframework.jmx.export.annotation.ManagedResource.class).objectName())
                .isEqualTo("com.traceability.app.application.funds:type=FundsApplicationAdminOperations");
    }

    // --- Utilidades ---

    private String campaign(TargetPolicy policy, long target) {
        ConvocatoriaConfiguration cfg = new ConvocatoriaConfiguration(EnumSet.of(DonationType.MONETARY),
                Set.of(PaymentMethod.BANK_TRANSFER), "COP", target, policy, null);
        return lifecycle.createConvocatoria(new CreateConvocatoriaCommand(UUID.randomUUID().toString(), ADMIN, ORG,
                "Campaña", null, Visibility.PUBLIC, Instant.parse("2026-10-01T00:00:00Z"),
                Instant.parse("2026-12-31T00:00:00Z"), cfg)).campaignRef();
    }

    private String pendingIntent(String campaignRef, long amount) {
        String publicCode = convocatorias.findByCampaignRef(campaignRef).orElseThrow().getPublicCode();
        return donationIntentService.createDonationIntent(new CreateDonationIntentCommand(UUID.randomUUID().toString(),
                publicCode, "donor-" + UUID.randomUUID(), amount, "COP", PaymentMethod.BANK_TRANSFER)).intentId();
    }

    private static ConfirmDonationIntentCommand confirm(String intentId) {
        return new ConfirmDonationIntentCommand(intentId, ADMIN, "BANK-" + intentId);
    }

    private void failGenesisOf(String intentId, RuntimeException failure) {
        doThrow(failure).when(eventStore).append(eq(intent(intentId).getFundId()), anyString(), anyLong(), any(), any());
    }

    private DonationIntent intent(String intentId) {
        return donationIntents.findById(intentId).orElseThrow();
    }

    private long clearedAmount(String campaignRef) {
        return ledgers.findByCampaignRef(campaignRef).orElseThrow().clearedAmount();
    }

    private long fundEvents(String intentId) {
        return mongoTemplate.getCollection("event_store")
                .countDocuments(new Document("streamId", intent(intentId).getFundId()));
    }

    private long applyFundsClaims(String intentId) {
        return processedCommands.findSystemCommand(CommandType.APPLY_FUNDS, intentId).isPresent() ? 1 : 0;
    }

    private void assertApplied(String intentId, String campaignRef, long amount) {
        DonationIntent intent = intent(intentId);
        assertThat(intent.getStatus()).isEqualTo(DonationIntentStatus.CONFIRMED);
        assertThat(intent.getApplicationTracking().fundsAppliedAt()).isNotNull();
        assertThat(fundEvents(intentId)).as("génesis del Fund").isEqualTo(1);
        assertThat(applyFundsClaims(intentId)).as("reclamo APPLY_FUNDS").isEqualTo(1);
        assertThat(ledgerService.findConfirmedPendingApplication(100))
                .noneMatch(i -> i.getIntentId().equals(intentId));
        assertThat(clearedAmount(campaignRef)).isGreaterThanOrEqualTo(amount);
    }

    private static List<Object> race(List<Callable<?>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (Callable<?> task : tasks) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<Object> results = new ArrayList<>();
            for (Future<?> f : futures) {
                results.add(f.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    /** Reloj controlable: por defecto sigue al del sistema. */
    static class TestClock extends Clock {
        private volatile Instant fixed;

        void set(Instant instant) {
            this.fixed = instant;
        }

        void reset() {
            this.fixed = null;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            Instant current = fixed;
            return current != null ? current : Instant.now();
        }
    }
}
