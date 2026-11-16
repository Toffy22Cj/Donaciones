package com.traceability.app.bootstrap.historic;

import com.traceability.core.application.port.out.EventStorePort;
import com.traceability.core.application.service.TransactionalEventPublisher;
import com.traceability.core.domain.event.DomainEvent;
import com.traceability.core.domain.event.DomainEventPayload;
import com.traceability.core.domain.event.EventType;
import com.traceability.core.domain.shared.AggregateRoot;
import com.traceability.core.domain.shared.EventTime;
import com.traceability.core.domain.shared.SimulatedTimeNotEnabledException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Plan del paso 2 (2026-11-16), aserción negativa: <b>sin</b> el perfil {@code demo-historico}, la aplicación arranca
 * sin armado, sin reloj del perfil y sin semilla; pedir una fecha pasada lanza
 * {@link SimulatedTimeNotEnabledException}, y {@code occurredAt} es la hora real.
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
    "crypto.anchor.poll.delay=9999999",
    "crypto.anchor.submit.delay=9999999",
    "crypto.anchor.stuck-monitor.delay=9999999",
    "crypto.web3j.private-key=0x1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef",
    "crypto.web3j.node-url=http://dummy-node",
    "spring.ai.openai.api-key=dummy-api-key"
})
class DemoHistoricProfileAbsentTest {

    @Container
    static MongoDBContainer mongo = new MongoDBContainer(DockerImageName.parse("mongo:6.0")).withCommand("--replSet", "rs0");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongo::getReplicaSetUrl);
    }

    @Autowired private ApplicationContext context;
    @Autowired private TransactionalEventPublisher eventPublisher;
    @Autowired private EventStorePort eventStore;

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private com.traceability.contracts.authorization.IdentityPrincipalPort identityPrincipalPort;

    /** Un agregado mínimo, para leer el {@code occurredAt} que sella {@link AggregateRoot}. */
    static final class Probe extends AggregateRoot {
        DomainEvent emit(DomainEventPayload payload) {
            raiseEvent(() -> "ASSET_REGISTERED", payload);
            List<DomainEvent> events = getUncommittedEvents();
            return events.get(events.size() - 1);
        }

        @Override protected void apply(DomainEventPayload payload) {}
    }

    @Test
    void withoutTheProfile_noneOfItsBeansExist() {
        assertThat(context.getBeansOfType(DemoHistoricConfig.class)).isEmpty();
        assertThat(context.getBeansOfType(DemoHistoricSeeder.class)).isEmpty();
        assertThat(context.getBeansOfType(DemoHistoricSeedRunner.class)).isEmpty();
        assertThat(context.getBeansOfType(EventTime.Armed.class)).isEmpty();
        assertThat(context.getBeansOfType(Clock.class)).as("sin el reloj del perfil").isEmpty();
        assertThat(EventTime.isArmed()).isFalse();
    }

    @Test
    void withoutTheProfile_backdatingThrows_andTheStoredEventKeepsTheRealOccurredAt() {
        String streamId = java.util.UUID.randomUUID().toString();
        DomainEventPayload payload = new com.traceability.core.domain.physicalasset.payloads.AssetRegisteredPayload(
                streamId, "FOOD_RATION", java.math.BigDecimal.TEN, "KGS", "WH-01", "CUST-01", null, null, null, null);
        Instant past = Instant.parse("2026-06-01T10:00:00Z");
        List<DomainEvent> emitted = new ArrayList<>();

        assertThatThrownBy(() -> EventTime.callAt(past, () -> emitted.add(new Probe().emit(payload))))
                .isInstanceOf(SimulatedTimeNotEnabledException.class);
        assertThat(emitted).as("el bloque no llegó a ejecutarse: ningún hecho se fechó en el pasado").isEmpty();

        // El mismo hecho, sin bloque: hora real, también una vez guardado en el event store
        DomainEvent event = new Probe().emit(payload);
        EventType type = event.eventType();
        eventPublisher.appendAndOutbox(streamId, "PhysicalAsset", 0L,
                List.of(new DomainEvent(type, payload, event.occurredAt())),
                new com.traceability.core.domain.event.SystemActor("system"), List.of(),
                java.util.UUID.randomUUID().toString());
        Instant stored = eventStore.loadStream(streamId).get(0).occurredAt();
        assertThat(Duration.between(stored, Instant.now()).abs()).isLessThan(Duration.ofSeconds(30));
        assertThat(stored).isAfter(past.plus(Duration.ofDays(30)));
    }
}
