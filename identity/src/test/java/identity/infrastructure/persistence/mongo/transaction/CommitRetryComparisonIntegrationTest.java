package identity.infrastructure.persistence.mongo.transaction;

import com.github.f4b6a3.ulid.UlidCreator;
import com.mongodb.MongoException;
import com.mongodb.client.MongoDatabase;
import identity.application.port.out.AuditLogPort;
import identity.application.service.MongoTransactionRetryHelper;
import identity.domain.model.AccountId;
import identity.domain.model.AuditAction;
import identity.domain.model.AuditActor;
import identity.domain.model.AuditLogEntry;
import identity.domain.model.OrganizationId;
import identity.infrastructure.persistence.mongo.IdentityTestApplication;
import identity.infrastructure.persistence.mongo.repositories.MongoAuditLogAdapter;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@DataMongoTest
@Testcontainers
@ContextConfiguration(classes = IdentityTestApplication.class)
@Import({
        MongoAuditLogAdapter.class,
        MongoTransactionRetryHelper.class
})
public class CommitRetryComparisonIntegrationTest {

    @Container
    static final MongoDBContainer mongoDBContainer = new MongoDBContainer(DockerImageName.parse("mongo:6.0"))
            .withCommand("--replSet", "docker-rs", "--setParameter", "enableTestCommands=1");

    @DynamicPropertySource
    static void setProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongoDBContainer::getReplicaSetUrl);
    }

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private MongoDatabaseFactory dbFactory;

    @Autowired
    private MongoTransactionManager standardTransactionManager;

    @Autowired
    private MongoTransactionRetryHelper cPlusRetryHelper;

    @Autowired
    private AuditLogPort auditLogPort;

    @BeforeEach
    void setUp() {
        disableCommitFailPoint();
    }

    @AfterEach
    void tearDown() {
        disableCommitFailPoint();
    }

    private void enableCommitFailPoint(int times) {
        MongoDatabase adminDb = dbFactory.getMongoDatabase("admin");
        Document command = new Document("configureFailPoint", "failCommand")
                .append("mode", new Document("times", times))
                .append("data", new Document("failCommands", List.of("commitTransaction"))
                        .append("errorCode", 91)
                        .append("errorLabels", List.of("RetryableWriteError")));
        adminDb.runCommand(command);
    }

    private void disableCommitFailPoint() {
        try {
            MongoDatabase adminDb = dbFactory.getMongoDatabase("admin");
            Document command = new Document("configureFailPoint", "failCommand")
                    .append("mode", "off");
            adminDb.runCommand(command);
        } catch (Exception ignored) {
        }
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

    @Test
    void scenario1_standardManager_times1_succeedsViaDriverRetry() {
        String testId = UUID.randomUUID().toString();
        AtomicInteger lambdaExecutions = new AtomicInteger(0);
        TransactionTemplate standardTemplate = new TransactionTemplate(standardTransactionManager);

        try {
            enableCommitFailPoint(1);

            assertDoesNotThrow(() -> {
                standardTemplate.execute(status -> {
                    lambdaExecutions.incrementAndGet();
                    mongoTemplate.insert(new Document("_id", testId).append("data", "scenario1"), "comparison_test_docs");
                    AuditLogEntry entry = AuditLogEntry.record(
                            UlidCreator.getUlid().toString(),
                            Instant.now(),
                            new AuditActor.AccountAuditActor(AccountId.generate()),
                            AccountId.generate(),
                            OrganizationId.generate(),
                            AuditAction.ACCOUNT_CREATED,
                            Map.of("testId", testId)
                    );
                    auditLogPort.record(entry);
                    return null;
                });
            });

            assertEquals(1, lambdaExecutions.get(), "Driver internal retry must not re-execute lambda");
            assertEquals(1, mongoTemplate.count(new Query(Criteria.where("_id").is(testId)), "comparison_test_docs"));
            assertEquals(1, mongoTemplate.count(new Query(Criteria.where("changeSummary.testId").is(testId)), "identity_audit_log"));
        } finally {
            disableCommitFailPoint();
        }
    }

    @Test
    void scenario2_helperCPlus_times1_succeeds() {
        String testId = UUID.randomUUID().toString();
        AtomicInteger lambdaExecutions = new AtomicInteger(0);

        try {
            enableCommitFailPoint(1);

            assertDoesNotThrow(() -> {
                cPlusRetryHelper.executeWithRetry(() -> {
                    lambdaExecutions.incrementAndGet();
                    mongoTemplate.insert(new Document("_id", testId).append("data", "scenario2"), "comparison_test_docs");
                    AuditLogEntry entry = AuditLogEntry.record(
                            UlidCreator.getUlid().toString(),
                            Instant.now(),
                            new AuditActor.AccountAuditActor(AccountId.generate()),
                            AccountId.generate(),
                            OrganizationId.generate(),
                            AuditAction.ACCOUNT_CREATED,
                            Map.of("testId", testId)
                    );
                    auditLogPort.record(entry);
                });
            });

            assertEquals(1, lambdaExecutions.get(), "C+ with times:1 must execute lambda once");
            assertEquals(1, mongoTemplate.count(new Query(Criteria.where("_id").is(testId)), "comparison_test_docs"));
            assertEquals(1, mongoTemplate.count(new Query(Criteria.where("changeSummary.testId").is(testId)), "identity_audit_log"));
        } finally {
            disableCommitFailPoint();
        }
    }

    @Test
    void scenario3_standardManager_times2_failsWithUnknownTransactionCommitResult() {
        String testId = UUID.randomUUID().toString();
        AtomicInteger lambdaExecutions = new AtomicInteger(0);
        TransactionTemplate standardTemplate = new TransactionTemplate(standardTransactionManager);

        try {
            enableCommitFailPoint(2);

            Exception thrown = assertThrows(Exception.class, () -> {
                standardTemplate.execute(status -> {
                    lambdaExecutions.incrementAndGet();
                    mongoTemplate.insert(new Document("_id", testId).append("data", "scenario3"), "comparison_test_docs");
                    AuditLogEntry entry = AuditLogEntry.record(
                            UlidCreator.getUlid().toString(),
                            Instant.now(),
                            new AuditActor.AccountAuditActor(AccountId.generate()),
                            AccountId.generate(),
                            OrganizationId.generate(),
                            AuditAction.ACCOUNT_CREATED,
                            Map.of("testId", testId)
                    );
                    auditLogPort.record(entry);
                    return null;
                });
            });

            assertTrue(hasErrorLabel(thrown, MongoException.UNKNOWN_TRANSACTION_COMMIT_RESULT_LABEL),
                    "Expected UnknownTransactionCommitResult in exception chain, but got: " + thrown);

            long docsPersisted = mongoTemplate.count(new Query(Criteria.where("_id").is(testId)), "comparison_test_docs");
            long auditEntriesPersisted = mongoTemplate.count(new Query(Criteria.where("changeSummary.testId").is(testId)), "identity_audit_log");

            System.out.println("=== SCENARIO 3 (Option A, times:2) EVIDENCE ===");
            System.out.println("  Lambda executions: " + lambdaExecutions.get());
            System.out.println("  Exception: " + thrown.getClass().getName() + " - " + thrown.getMessage());
            System.out.println("  Documents persisted: " + docsPersisted);
            System.out.println("  Audit entries persisted: " + auditEntriesPersisted);
        } finally {
            disableCommitFailPoint();
        }
    }

    @Test
    void scenario4_helperCPlus_times2_succeedsWithSingleLambdaExecution() {
        String testId = UUID.randomUUID().toString();
        AtomicInteger lambdaExecutions = new AtomicInteger(0);

        try {
            enableCommitFailPoint(2);

            assertDoesNotThrow(() -> {
                cPlusRetryHelper.executeWithRetry(() -> {
                    lambdaExecutions.incrementAndGet();
                    mongoTemplate.insert(new Document("_id", testId).append("data", "scenario4"), "comparison_test_docs");
                    AuditLogEntry entry = AuditLogEntry.record(
                            UlidCreator.getUlid().toString(),
                            Instant.now(),
                            new AuditActor.AccountAuditActor(AccountId.generate()),
                            AccountId.generate(),
                            OrganizationId.generate(),
                            AuditAction.ACCOUNT_CREATED,
                            Map.of("testId", testId)
                    );
                    auditLogPort.record(entry);
                });
            });

            assertEquals(1, lambdaExecutions.get(), "C+ must NOT re-execute business lambda on commit retry");
            long docsPersisted = mongoTemplate.count(new Query(Criteria.where("_id").is(testId)), "comparison_test_docs");
            long auditEntriesPersisted = mongoTemplate.count(new Query(Criteria.where("changeSummary.testId").is(testId)), "identity_audit_log");

            assertEquals(1, docsPersisted, "Exactly 1 document must be persisted");
            assertEquals(1, auditEntriesPersisted, "Exactly 1 audit log entry must be persisted");

            System.out.println("=== SCENARIO 4 (Option C+, times:2) EVIDENCE ===");
            System.out.println("  Lambda executions: " + lambdaExecutions.get());
            System.out.println("  Documents persisted: " + docsPersisted);
            System.out.println("  Audit entries persisted: " + auditEntriesPersisted);
        } finally {
            disableCommitFailPoint();
        }
    }
}
