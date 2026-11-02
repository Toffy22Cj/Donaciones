package com.traceability.core.application.service;

import com.traceability.core.infrastructure.projection.mongo.documents.LocationReferenceDocument;
import com.traceability.core.infrastructure.projection.mongo.repositories.LocationReferenceRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.mock.mockito.MockBean;
import com.traceability.contracts.authorization.IdentityPrincipalPort;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import com.traceability.contracts.HashPort;
import com.traceability.core.application.exception.InvalidLocationReferenceException;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    private IdentityPrincipalPort identityPrincipalPort;

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

    // ── ADR-052 D1 y D4: el punto de la zona pública ──

    @Test
    void resolve_withValidCoordinates_returnsZoneAndPoint() {
        repository.save(new LocationReferenceDocument("CTG-1", "Cartagena, Bolívar", 10.391, -75.4794));

        Optional<PublicZone> zone = service.resolve("CTG-1");

        assertTrue(zone.isPresent());
        assertEquals("Cartagena, Bolívar", zone.get().zoneName());
        assertEquals(new ZonePoint(10.391, -75.4794), zone.get().point());
    }

    @Test
    void resolve_withoutCoordinates_returnsZoneWithoutPoint() {
        Optional<PublicZone> zone = service.resolve("BOG-WAREHOUSE-01");

        assertTrue(zone.isPresent());
        assertEquals("Bogota DC", zone.get().zoneName());
        assertNull(zone.get().point());
    }

    @Test
    void resolve_withOnlyOneCoordinate_orOutOfRange_neverInventsAPoint() {
        repository.save(new LocationReferenceDocument("ONE", "Zona A", 10.0, null));
        repository.save(new LocationReferenceDocument("LAT", "Zona B", 91.0, -75.0));
        repository.save(new LocationReferenceDocument("LON", "Zona C", 10.0, -181.0));
        repository.save(new LocationReferenceDocument("NAN", "Zona D", Double.NaN, -75.0));

        for (String raw : java.util.List.of("ONE", "LAT", "LON", "NAN")) {
            PublicZone zone = service.resolve(raw).orElseThrow();
            assertNull(zone.point(), raw);
        }
        assertEquals("Zona A", service.resolve("ONE").orElseThrow().zoneName());
    }

    @Test
    void resolve_unknownOrNull_isEmpty() {
        assertTrue(service.resolve("UNKNOWN-LOC").isEmpty());
        assertTrue(service.resolve(null).isEmpty());
    }

    @Test
    void register_savesOrReplaces_andValidatesD1() {
        service.register("CTG-1", "Cartagena, Bolívar", 10.391, -75.4794);
        service.register("CTG-1", "Cartagena", 10.4, -75.5);
        service.register("SIN", "Sin punto", null, null);

        assertEquals("Cartagena", service.resolve("CTG-1").orElseThrow().zoneName());
        assertEquals(new ZonePoint(10.4, -75.5), service.resolve("CTG-1").orElseThrow().point());
        assertNull(service.resolve("SIN").orElseThrow().point());

        assertThrows(InvalidLocationReferenceException.class, () -> service.register(" ", "Zona", null, null));
        assertThrows(InvalidLocationReferenceException.class, () -> service.register("X", " ", null, null));
        assertThrows(InvalidLocationReferenceException.class, () -> service.register("X", "Zona", 10.0, null));
        assertThrows(InvalidLocationReferenceException.class, () -> service.register("X", "Zona", 90.5, 0.0));
        assertThrows(InvalidLocationReferenceException.class, () -> service.register("X", "Zona", 0.0, 180.5));
        assertTrue(service.resolve("X").isEmpty(), "una entrada inválida no se guarda");
    }
}
