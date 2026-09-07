package com.traceability.core.application.service;

import com.traceability.core.infrastructure.projection.mongo.documents.LocationReferenceDocument;
import com.traceability.core.infrastructure.projection.mongo.repositories.LocationReferenceRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.springframework.boot.test.mock.mockito.MockBean;
import com.traceability.contracts.HashPort;
import com.traceability.core.application.port.out.OutboxPort;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
    "core.projection.retry.delay=100",
    "core.projection.retry.timeout-minutes=5"
})
@Testcontainers
class LocationReferenceServiceIntegrationTest {

    @MockBean
    private HashPort hashPort;
    
    @MockBean
    private OutboxPort outboxPort;

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
    static class TestConfig {}

    @Autowired
    private LocationReferenceService service;

    @Autowired
    private LocationReferenceRepository repository;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        repository.save(new LocationReferenceDocument("BOG-WAREHOUSE-01", "Bogota DC"));
    }

    @AfterEach
    void tearDown() {
        repository.deleteAll();
    }

    @Test
    void resolveZone_withKnownLocation_exactMatch() {
        Optional<String> result = service.resolveZone("BOG-WAREHOUSE-01");

        assertTrue(result.isPresent());
        assertEquals("Bogota DC", result.get());
    }

    @Test
    void resolveZone_withCaseMismatch_returnsEmpty_verifyingExactMatchInMongo() {
        Optional<String> result = service.resolveZone("bog-warehouse-01");

        assertTrue(result.isEmpty());
    }

    @Test
    void resolveZone_withUnknownLocation_returnsEmpty() {
        Optional<String> result = service.resolveZone("UNKNOWN-LOC");

        assertTrue(result.isEmpty());
    }
}
