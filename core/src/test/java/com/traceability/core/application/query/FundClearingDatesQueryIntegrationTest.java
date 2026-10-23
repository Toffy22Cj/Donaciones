package com.traceability.core.application.query;

import com.traceability.contracts.HashPort;
import com.traceability.contracts.authorization.AuthorizationRole;
import com.traceability.core.application.authorization.CrossOrganizationAccessException;
import com.traceability.core.application.authorization.InsufficientRoleException;
import com.traceability.core.application.port.out.EventStorePort;
import com.traceability.core.application.port.out.FundClearingDatesReadPort;
import com.traceability.core.application.port.out.FundClearingDatesReadPort.ClearingDates;
import com.traceability.core.application.port.out.FundDirectoryPort;
import com.traceability.core.domain.event.DomainEvent;
import com.traceability.core.domain.event.DomainEventPayload;
import com.traceability.core.domain.event.HumanActor;
import com.traceability.core.domain.event.SystemActor;
import com.traceability.core.domain.fund.FundEventType;
import com.traceability.core.domain.fund.payloads.FundRegisteredPayload;
import com.traceability.core.domain.fund.payloads.FundRegisteredV2Payload;
import com.traceability.core.domain.fund.payloads.FundsClearedPayload;
import com.traceability.core.domain.fund.payloads.FundsClearedV2Payload;
import com.traceability.core.infrastructure.authorization.TestIdentityPrincipalPort;
import com.traceability.core.infrastructure.authorization.TestIdentityPrincipalPortConfig;
import com.traceability.core.infrastructure.persistence.mongo.TraceabilityEventDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;

/**
 * Ficha R-6, Bloque B, test 5 (plan-r6-bloque-b.md §3), en Mongo real: G-2, primera y última liquidación por
 * {@code sequence}, versiones 1.0 y 2.0. Los eventos se
 * escriben con {@link EventStorePort#append} para fijar su {@code occurredAt} ({@code AggregateRoot} usa
 * {@code Instant.now()}); la versión de esquema la decide la clase del payload.
 */
@SpringBootTest(properties = {
        "core.projection.retry.delay=99999999",
        "core.projection.retry.timeout-minutes=1",
        "saga.outbox.delay=99999999"
})
@Testcontainers
@Import(TestIdentityPrincipalPortConfig.class)
class FundClearingDatesQueryIntegrationTest {

    private static final String ORG = "ORG-R6B";
    private static final String OTHER_ORG = "ORG-R6B-OTHER";
    private static final String ADMIN = "acc-r6b-admin";
    private static final String EMPLOYEE = "acc-r6b-employee";
    private static final String REPRESENTATIVE = "acc-r6b-rep";
    private static final String OTHER_ADMIN = "acc-r6b-other-admin";
    private static final SystemActor SYSTEM = new SystemActor("r6b-tests");
    /** "Ahora": 15 de noviembre de 2026 a mediodía en Bogotá. */
    static final Instant NOW = Instant.parse("2026-11-15T17:00:00Z");

    @Container
    static MongoDBContainer mongo = new MongoDBContainer(DockerImageName.parse("mongo:6.0")).withCommand("--replSet", "rs0");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongo::getReplicaSetUrl);
    }

    @Configuration
    @SpringBootApplication(scanBasePackages = "com.traceability.core")
    @org.springframework.data.mongodb.repository.config.EnableMongoRepositories(basePackages = "com.traceability.core")
    static class TestConfig {
        @Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @SpyBean private TestIdentityPrincipalPort identityPrincipalPort;
    @SpyBean private FundDirectoryPort directory;
    @MockBean private HashPort hashPort;

    @Autowired private EventStorePort eventStore;
    @Autowired private FundClearingDatesReadPort clearingDates;
    @Autowired private MongoTemplate mongoTemplate;

    @BeforeEach
    void setUp() {
        mongoTemplate.remove(new org.springframework.data.mongodb.core.query.Query(), TraceabilityEventDocument.class);
        identityPrincipalPort.reset();
        identityPrincipalPort.addPrincipal(ADMIN, ORG, Set.of(AuthorizationRole.ADMINISTRATOR));
        identityPrincipalPort.addPrincipal(EMPLOYEE, ORG, Set.of(AuthorizationRole.EMPLOYEE));
        identityPrincipalPort.addPrincipal(REPRESENTATIVE, ORG, Set.of(AuthorizationRole.REPRESENTATIVE));
        identityPrincipalPort.addPrincipal(OTHER_ADMIN, OTHER_ORG, Set.of(AuthorizationRole.ADMINISTRATOR));
    }

    // ── Escritura de eventos con occurredAt fijado ──

    private static String newFund() {
        return "fund-" + UUID.randomUUID();
    }

    private void append(String fundId, long expectedVersion, FundEventType type, DomainEventPayload payload, Instant at) {
        eventStore.append(fundId, "Fund", expectedVersion, List.of(new DomainEvent(type, payload, at)), SYSTEM);
    }

    private static FundsClearedV2Payload v2(String org, long amount) {
        return new FundsClearedV2Payload(org, amount, "src-" + UUID.randomUUID(), "COP", null, "anon:d");
    }

    private static FundsClearedPayload v1(long amount) {
        return new FundsClearedPayload(amount, "src-" + UUID.randomUUID(), "COP", null, "anon:d");
    }

    private static HumanActor actor(String accountId) {
        return new HumanActor(accountId);
    }

    // ── Test 5 (G-2) ──

    @Test
    void g2_oneClearing_firstEqualsLastEqualsItsOccurredAt() {
        String fund = newFund();
        Instant at = Instant.parse("2026-10-03T14:00:00Z");
        append(fund, 0, FundEventType.FUNDS_CLEARED, v2(ORG, 1000), at);

        assertThat(clearingDates.findClearingDates(fund)).contains(new ClearingDates(at, at));
    }

    @Test
    void g2_twoClearings_firstAndLastBySequence_evenIfTheirOccurredAtAreOutOfOrder() {
        String fund = newFund();
        Instant genesis = Instant.parse("2026-10-05T10:00:00Z");
        Instant later = Instant.parse("2026-10-01T10:00:00Z"); // anterior en el tiempo, posterior en sequence
        append(fund, 0, FundEventType.FUNDS_CLEARED, v2(ORG, 1000), genesis);
        append(fund, 1, FundEventType.FUNDS_CLEARED, v2(ORG, 500), later);

        assertThat(clearingDates.findClearingDates(fund)).contains(new ClearingDates(genesis, later));
    }

    @Test
    void g2_aFundWithoutClearings_hasNoDates() {
        String fund = newFund();
        append(fund, 0, FundEventType.FUND_REGISTERED, new FundRegisteredV2Payload(ORG, 1000L, "COP", null, "anon:d"),
                Instant.parse("2026-10-01T10:00:00Z"));

        assertThat(clearingDates.findClearingDates(fund)).isEmpty();
        assertThat(clearingDates.findClearingDates("fund-that-does-not-exist")).isEmpty();
    }

    @Test
    void g2_version1AndVersion2Clearings_bothCount() {
        String legacy = newFund();
        Instant v1At = Instant.parse("2026-09-10T10:00:00Z");
        append(legacy, 0, FundEventType.FUNDS_CLEARED, v1(700), v1At);
        String current = newFund();
        Instant v2At = Instant.parse("2026-10-10T10:00:00Z");
        append(current, 0, FundEventType.FUNDS_CLEARED, v2(ORG, 900), v2At);

        assertThat(clearingDates.findClearingDates(legacy)).contains(new ClearingDates(v1At, v1At));
        assertThat(clearingDates.findClearingDates(current)).contains(new ClearingDates(v2At, v2At));
    }
}
