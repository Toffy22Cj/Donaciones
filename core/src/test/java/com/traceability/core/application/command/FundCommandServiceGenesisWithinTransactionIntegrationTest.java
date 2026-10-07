package com.traceability.core.application.command;

import com.traceability.contracts.HashPort;
import com.traceability.contracts.authorization.IdentityPrincipalPort;
import com.traceability.core.application.exception.ConcurrencyConflictException;
import com.traceability.core.domain.event.SystemActor;
import com.traceability.core.domain.fund.OrganizationRef;
import com.traceability.core.infrastructure.persistence.mongo.ProcessedCommandDocument;
import com.traceability.core.infrastructure.persistence.mongo.TraceabilityEventDocument;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T1 (ADR-037 §2.3, ADR-045): génesis de {@code Fund} dentro de la transacción del orquestador, sin reintento interno.
 */
@SpringBootTest(properties = {
        "core.projection.retry.delay=100",
        "core.projection.retry.timeout-minutes=5"
})
@Testcontainers
@org.springframework.context.annotation.Import(com.traceability.core.support.TransactionProbe.Config.class)
class FundCommandServiceGenesisWithinTransactionIntegrationTest {

    @MockBean
    private IdentityPrincipalPort identityPrincipalPort;

    @MockBean
    private HashPort hashPort;

    @Container
    static MongoDBContainer mongoDBContainer = new MongoDBContainer(DockerImageName.parse("mongo:6.0"))
            .withCommand("--replSet", "rs0");

    @DynamicPropertySource
    static void setProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongoDBContainer::getReplicaSetUrl);
    }

    @Configuration
    @SpringBootApplication(scanBasePackages = "com.traceability.core")
    @org.springframework.data.mongodb.repository.config.EnableMongoRepositories(basePackages = "com.traceability.core")
    static class TestConfig {
        @Bean
        MongoTransactionManager transactionManager(MongoDatabaseFactory dbFactory) {
            return new MongoTransactionManager(dbFactory);
        }
    }

    @Autowired
    private FundCommandService fundCommandService;

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private MongoTransactionManager transactionManager;

    private TransactionTemplate tx;

    @Autowired
    private com.traceability.core.support.TransactionProbe transactionProbe;

    @BeforeEach
    void setup() {
        mongoTemplate.dropCollection(TraceabilityEventDocument.class);
        mongoTemplate.dropCollection(ProcessedCommandDocument.class);
        // Colecciones e índice único creados fuera de cualquier transacción.
        mongoTemplate.createCollection(TraceabilityEventDocument.class);
        mongoTemplate.createCollection(ProcessedCommandDocument.class);
        mongoTemplate.indexOps(TraceabilityEventDocument.class).ensureIndex(
                new Index().on("streamId", Sort.Direction.ASC).on("sequence", Sort.Direction.ASC).unique());
        tx = new TransactionTemplate(transactionManager);
    }

    @AfterEach
    void clean() {
        mongoTemplate.dropCollection(TraceabilityEventDocument.class);
        mongoTemplate.dropCollection(ProcessedCommandDocument.class);
    }

    @Test
    void withinExternalTransaction_appendsGenesisAndClaimsCommand() {
        transactionProbe.reset();
        String commandId = UUID.randomUUID().toString();
        String fundId = UUID.randomUUID().toString();

        Boolean appended = tx.execute(status -> genesis(commandId, fundId));

        transactionProbe.assertEveryWriteWasTransactional();
        assertThat(appended).isTrue();
        assertThat(eventsOf(fundId)).isEqualTo(1);
        assertThat(claimed(commandId)).isTrue();
    }

    @Test
    void withoutTransaction_failsImmediatelyAndWritesNothing() {
        String commandId = UUID.randomUUID().toString();
        String fundId = UUID.randomUUID().toString();

        assertThatThrownBy(() -> genesis(commandId, fundId))
                .isInstanceOf(IllegalTransactionStateException.class);

        assertThat(eventsOf(fundId)).isZero();
        assertThat(claimed(commandId)).isFalse();
    }

    @Test
    void externalRollback_leavesNeitherEventNorClaim() {
        transactionProbe.reset();
        String commandId = UUID.randomUUID().toString();
        String fundId = UUID.randomUUID().toString();

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            genesis(commandId, fundId);
            throw new IllegalStateException("fallo posterior del orquestador");
        })).isInstanceOf(IllegalStateException.class);

        transactionProbe.assertEveryWriteWasTransactional();
        assertThat(eventsOf(fundId)).isZero();
        assertThat(claimed(commandId)).isFalse();
    }

    @Test
    void conflict_propagatesOnFirstAttemptWithItsCause_noInternalRetry() {
        transactionProbe.reset();
        String fundId = UUID.randomUUID().toString();
        tx.execute(status -> genesis(UUID.randomUUID().toString(), fundId));

        String secondCommandId = UUID.randomUUID().toString();

        // Con reintento interno saldría ConcurrencyRetryExhaustedException tras 3 intentos;
        // T1 propaga el ConcurrencyConflictException original, con la DuplicateKeyException como causa.
        assertThatThrownBy(() -> tx.execute(status -> genesis(secondCommandId, fundId)))
                .isExactlyInstanceOf(ConcurrencyConflictException.class)
                .hasCauseInstanceOf(DuplicateKeyException.class);

        transactionProbe.assertEveryWriteWasTransactional();
        assertThat(eventsOf(fundId)).isEqualTo(1);
        assertThat(claimed(secondCommandId)).as("el rollback externo descarta también el reclamo").isFalse();
    }

    @Test
    void sameCommandIdReplayed_returnsFalseWithoutDuplicating() {
        transactionProbe.reset();
        String commandId = UUID.randomUUID().toString();
        String fundId = UUID.randomUUID().toString();

        Boolean first = tx.execute(status -> genesis(commandId, fundId));
        Boolean replay = tx.execute(status -> genesis(commandId, fundId));

        transactionProbe.assertEveryWriteWasTransactional();
        assertThat(first).isTrue();
        assertThat(replay).isFalse();
        assertThat(eventsOf(fundId)).isEqualTo(1);
    }

    private boolean genesis(String commandId, String fundId) {
        return fundCommandService.clearFundsGenesisWithinTransaction(
                commandId, fundId, new OrganizationRef("ORG-1"), "CAMP-1", "DONOR-1", "COP",
                1000L, "SRC-1", new SystemActor("funds-application-orchestrator"));
    }

    private long eventsOf(String fundId) {
        return mongoTemplate.count(new Query(Criteria.where("streamId").is(fundId)), TraceabilityEventDocument.class);
    }

    private boolean claimed(String commandId) {
        return mongoTemplate.exists(new Query(Criteria.where("_id").is(commandId)), ProcessedCommandDocument.class);
    }
}
