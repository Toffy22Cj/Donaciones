package com.traceability.core.application.query;

import com.traceability.contracts.HashPort;
import com.traceability.contracts.authorization.AuthorizationRole;
import com.traceability.core.application.authorization.CrossOrganizationAccessException;
import com.traceability.core.application.authorization.InsufficientRoleException;
import com.traceability.core.application.port.out.EventStorePort;
import com.traceability.core.application.port.out.FundDirectoryPort;
import com.traceability.core.application.port.out.FundMonthlyClearingReadPort;
import com.traceability.core.application.port.out.FundMonthlyClearingReadPort.MonthItem;
import com.traceability.core.application.port.out.FundMonthlyClearingReadPort.MonthlyClearing;
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
 * Ficha R-6, Bloque B, test 6 (plan-r6-bloque-b.md §3), en Mongo real: G-6, recaudado por mes en
 * {@code America/Bogota}, pertenencia por la génesis, versiones 1.0 y 2.0. Los eventos se
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
class FundMonthlyClearingIntegrationTest {

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
    @Autowired private FundMonthlyClearingReadPort monthly;
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

    // ── Test 6 (G-6) ──

    private static MonthItem item(MonthlyClearing m, String month, String currency) {
        return m.items().stream().filter(i -> i.month().equals(YearMonth.parse(month)) && i.currency().equals(currency))
                .findFirst().orElseThrow(() -> new AssertionError("sin ítem " + month + " " + currency + ": " + m));
    }

    @Test
    void g6_emptyMonthsAreZero_andTheTimezoneIsBogota() {
        String fund = newFund();
        append(fund, 0, FundEventType.FUNDS_CLEARED, v2(ORG, 1000), Instant.parse("2026-11-10T15:00:00Z"));

        MonthlyClearing m = monthly.monthlyForOrganization(ORG, 3, actor(ADMIN));

        assertThat(m.timezone()).isEqualTo("America/Bogota");
        assertThat(m.truncated()).isFalse();
        assertThat(m.items()).extracting(MonthItem::month)
                .containsExactly(YearMonth.of(2026, 9), YearMonth.of(2026, 10), YearMonth.of(2026, 11));
        assertThat(item(m, "2026-09", "COP").clearedAmount()).isZero();
        assertThat(item(m, "2026-10", "COP").clearedAmount()).isZero();
        assertThat(item(m, "2026-11", "COP").clearedAmount()).isEqualTo(1000);
    }

    @Test
    void g6_monthCutIsInBogota_2330OnThe31stCountsInThatMonth() {
        String fund = newFund();
        // 31 de octubre, 23:30 en Bogotá = 1 de noviembre, 04:30 UTC
        append(fund, 0, FundEventType.FUNDS_CLEARED, v2(ORG, 300), Instant.parse("2026-11-01T04:30:00Z"));
        // 1 de noviembre, 00:30 en Bogotá = 05:30 UTC
        append(fund, 1, FundEventType.FUNDS_CLEARED, v2(ORG, 40), Instant.parse("2026-11-01T05:30:00Z"));

        MonthlyClearing m = monthly.monthlyForOrganization(ORG, 2, actor(EMPLOYEE));

        assertThat(item(m, "2026-10", "COP").clearedAmount()).isEqualTo(300);
        assertThat(item(m, "2026-11", "COP").clearedAmount()).isEqualTo(40);
    }

    @Test
    void g6_version1And2_countInTheOrganizationOfTheGenesis() {
        Instant oct = Instant.parse("2026-10-10T15:00:00Z");
        // P-R6B-2: génesis 2.0 de ORG y un FUNDS_CLEARED 1.0 posterior, escrito a mano
        String mixed = newFund();
        append(mixed, 0, FundEventType.FUNDS_CLEARED, v2(ORG, 1000), oct);
        append(mixed, 1, FundEventType.FUNDS_CLEARED, v1(250), oct.plusSeconds(60));
        // génesis FUND_REGISTERED 2.0 de ORG con una liquidación 2.0
        String registered = newFund();
        append(registered, 0, FundEventType.FUND_REGISTERED, new FundRegisteredV2Payload(ORG, 5000L, "COP", null, "anon:d"), oct);
        append(registered, 1, FundEventType.FUNDS_CLEARED, v2(ORG, 70), oct.plusSeconds(120));
        // otra organización
        String foreign = newFund();
        append(foreign, 0, FundEventType.FUNDS_CLEARED, v2(OTHER_ORG, 9999), oct);
        // P-R6B-1: génesis 1.0 sin organización (anterior a 0579f41): no suma en nadie
        String orphan = newFund();
        append(orphan, 0, FundEventType.FUND_REGISTERED, new FundRegisteredPayload(100L, "COP", null, "anon:d"), oct);
        append(orphan, 1, FundEventType.FUNDS_CLEARED, v1(8888), oct.plusSeconds(60));

        MonthlyClearing m = monthly.monthlyForOrganization(ORG, 2, actor(ADMIN));

        assertThat(item(m, "2026-10", "COP").clearedAmount()).isEqualTo(1000 + 250 + 70);
        assertThat(monthly.monthlyForOrganization(OTHER_ORG, 2, actor(OTHER_ADMIN)).items())
                .filteredOn(i -> i.month().equals(YearMonth.of(2026, 10))).extracting(MonthItem::clearedAmount)
                .containsExactly(9999L);
    }

    @Test
    void g6_withoutFunds_itemsAreEmpty() {
        assertThat(monthly.monthlyForOrganization(ORG, 6, actor(ADMIN)).items()).isEmpty();
    }

    @Test
    void g6_sameRulesAsGetFunds_otherOrganizationOrRepresentativeOnly_isRejected() {
        assertThatThrownBy(() -> monthly.monthlyForOrganization(ORG, 6, actor(OTHER_ADMIN)))
                .isInstanceOf(CrossOrganizationAccessException.class);
        assertThatThrownBy(() -> monthly.monthlyForOrganization(ORG, 6, actor(REPRESENTATIVE)))
                .isInstanceOf(InsufficientRoleException.class);
    }

    /** P-R6B-6: con más de 10 000 fondos, se leen 10 000 y {@code truncated} lo dice. */
    @Test
    void g6_moreThan10000Funds_isTruncated() {
        List<String> many = IntStream.range(0, 10_001).mapToObj(i -> "fund-many-" + i).toList();
        doReturn(many).when(directory).findFundIdsByOrganization(eq(ORG), anyInt());

        MonthlyClearing m = monthly.monthlyForOrganization(ORG, 1, actor(ADMIN));

        assertThat(m.truncated()).isTrue();
    }
}
