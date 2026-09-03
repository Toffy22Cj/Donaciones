package com.traceability.app;

import com.traceability.core.application.service.TransactionalEventPublisher;
import com.traceability.core.domain.event.DomainEvent;
import com.traceability.core.domain.event.DomainEventPayload;
import com.traceability.core.domain.event.EventType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
    // Schedulers mitigados: delays extremos para que no disparen durante el test
    "crypto.anchor.poll.delay=9999999",
    "crypto.anchor.submit.delay=9999999",
    "crypto.anchor.stuck-monitor.delay=9999999",
    
    // Propiedades dummy para superar el fail-fast de los módulos
    "crypto.web3j.private-key=0x1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef",
    "crypto.web3j.node-url=http://dummy-node",
    "spring.ai.openai.api-key=dummy-api-key"
})
class ApplicationContextLoadTest {

    @Container
    static MongoDBContainer mongoDBContainer = new MongoDBContainer(DockerImageName.parse("mongo:6.0"))
            .withCommand("--replSet", "rs0");

    @DynamicPropertySource
    static void setProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongoDBContainer::getReplicaSetUrl);
    }

    @Autowired
    private ApplicationContext context;

    @Autowired
    private TransactionalEventPublisher eventPublisher;

    @Autowired
    private com.traceability.core.application.port.out.EventStorePort eventStorePort;

    @Autowired
    private com.traceability.core.application.port.out.OutboxPort outboxPort;

    @Test
    void contextLoadsAndTransactionSmokeTestPasses() {
        // 1. Verificar la existencia del TransactionManager en el contexto
        MongoTransactionManager txManager = context.getBean(MongoTransactionManager.class);
        assertThat(txManager).isNotNull();

        // 2. Smoke Test Transaccional: probar que la infraestructura compartida
        // realmente puede escribir en MongoDB bajo una transacción
        String streamId = UUID.randomUUID().toString();
        
        com.traceability.core.domain.physicalasset.payloads.AssetRegisteredPayload realPayload = 
                new com.traceability.core.domain.physicalasset.payloads.AssetRegisteredPayload(
                        streamId, "FOOD_RATION", 100L, "KGS", "WH-01", "CUST-01", null, null, null, null
                );
        EventType dummyEventType = () -> "ASSET_REGISTERED";
        
        DomainEvent event = new DomainEvent(dummyEventType, realPayload, Instant.now());

        // 3. Crear OutboxMessage para probar escritura dual
        com.traceability.core.application.saga.OutboxMessage outboxMessage = 
                new com.traceability.core.application.saga.OutboxMessage(
                        UUID.randomUUID().toString(),
                        "domain_event",
                        streamId,
                        streamId,
                        "dummy_payload",
                        com.traceability.core.application.saga.OutboxStatus.PENDING,
                        0,
                        Instant.now(),
                        Instant.now()
                );

        // Si MongoTransactionManager no estuviera correctamente configurado
        // y enlazado, o MongoDB no aceptara transacciones,
        // TransactionalEventPublisher (que usa @Transactional) fallaría aquí.
        eventPublisher.appendAndOutbox(
                streamId,
                "PhysicalAsset",
                0L, // expectedVersion
                event,
                "system",
                List.of(outboxMessage)
        );
        
        // 3. Aserciones de Lectura (Event Store)
        List<DomainEvent> stream = eventStorePort.loadStream(streamId);
        assertThat(stream).hasSize(1);
        DomainEvent loadedEvent = stream.get(0);
        assertThat(loadedEvent.eventType().name()).isEqualTo("ASSET_REGISTERED");
        
        assertThat(loadedEvent.payload()).isInstanceOf(com.traceability.core.domain.physicalasset.payloads.AssetRegisteredPayload.class);
        com.traceability.core.domain.physicalasset.payloads.AssetRegisteredPayload loadedPayload = 
                (com.traceability.core.domain.physicalasset.payloads.AssetRegisteredPayload) loadedEvent.payload();
        assertThat(loadedPayload.assetId()).isEqualTo(streamId);
        assertThat(loadedPayload.assetType()).isEqualTo("FOOD_RATION");

        // 4. Aserciones de Lectura (Outbox)
        // Buscamos los mensajes que están pendientes de procesar
        List<com.traceability.core.application.saga.OutboxMessage> pending = outboxPort.fetchPendingMessages(Instant.now().plusSeconds(60));
        
        // Esperamos al menos 1 (nuestro evento recién insertado), filtramos por nuestro streamId
        boolean foundInOutbox = pending.stream()
                .anyMatch(msg -> streamId.equals(msg.sourceAggregateId()) && "domain_event".equals(msg.sagaType()));
        assertThat(foundInOutbox).isTrue();
    }
}
