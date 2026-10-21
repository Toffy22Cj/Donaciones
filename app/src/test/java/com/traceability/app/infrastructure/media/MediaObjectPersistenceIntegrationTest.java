package com.traceability.app.infrastructure.media;

import com.traceability.app.TraceabilityApplication;
import com.traceability.app.application.media.ImageSanitizer;
import com.traceability.app.application.media.MediaPurpose;
import com.traceability.app.application.media.MediaUploadService;
import com.traceability.app.application.media.StoredMedia;
import com.traceability.app.application.media.TestImages;
import com.traceability.contracts.media.MediaStorageUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Clock;
import java.time.Instant;

import static com.traceability.app.application.media.MediaPurpose.CAMPAIGN_COVER;
import static com.traceability.app.application.media.MediaPurpose.DELIVERY_EVIDENCE;
import static com.traceability.app.application.media.MediaPurpose.ORGANIZATION_LOGO;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code media_objects} en MongoDB real (ADR-051 F9): único en {@code objectKey}, único parcial
 * {@code (organizationId, purpose, sha256)} solo para la evidencia, y ningún metadato cuando el proveedor falla
 * (definición de hecho 15). Los índices los crea {@code MongoIndexInitializer} al arrancar (H-IDX-1).
 */
@SpringBootTest(classes = TraceabilityApplication.class)
@Testcontainers
@TestPropertySource(properties = {
    "crypto.anchor.poll.delay=9999999",
    "crypto.anchor.submit.delay=9999999",
    "crypto.anchor.stuck-monitor.delay=9999999",
    "crypto.web3j.private-key=0x1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef",
    "crypto.web3j.node-url=http://dummy-node",
    "spring.ai.openai.api-key=dummy-api-key"
})
class MediaObjectPersistenceIntegrationTest {

    @Container
    static MongoDBContainer mongo = new MongoDBContainer(DockerImageName.parse("mongo:6.0")).withCommand("--replSet", "rs0");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongo::getReplicaSetUrl);
    }

    static final Instant NOW = Instant.parse("2026-10-20T12:00:00Z");

    @Autowired private MediaObjectRepository repository;

    private InMemoryMediaStorageAdapter storage;
    private MediaUploadService service;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        storage = new InMemoryMediaStorageAdapter();
        service = new MediaUploadService(new ImageSanitizer(), storage, repository, Clock.systemUTC());
    }

    static MediaObjectDocument document(String objectKey, MediaPurpose purpose, String organizationId, String sha256) {
        return new MediaObjectDocument(null, objectKey, sha256, purpose, organizationId,
                purpose == CAMPAIGN_COVER ? "cmp-1" : null, "image/png", 10, 1, 1, "acct-1", NOW,
                null, null, null, null);
    }

    static final String SHA = "a".repeat(64);

    @Test
    void objectKey_isUnique() {
        repository.insert(document("01JB00000000000000000000A1", ORGANIZATION_LOGO, "org-1", SHA));

        assertThatThrownBy(() -> repository.insert(
                document("01JB00000000000000000000A1", CAMPAIGN_COVER, "org-2", "b".repeat(64))))
                .isInstanceOf(DuplicateKeyException.class);
    }

    // ADR-051 F9: subir dos veces la misma evidencia no duplica
    @Test
    void sameEvidenceBytes_inTheSameOrganization_collide() {
        repository.insert(document("01JB00000000000000000000B1", DELIVERY_EVIDENCE, "org-1", SHA));

        assertThatThrownBy(() -> repository.insert(document("01JB00000000000000000000B2", DELIVERY_EVIDENCE, "org-1", SHA)))
                .isInstanceOf(DuplicateKeyException.class);
    }

    // el único es parcial: solo la evidencia (logo y portada pueden repetir bytes; otra organización también)
    @Test
    void sameBytes_asLogoAndCover_orAsEvidenceOfAnotherOrganization_doNotCollide() {
        assertThatCode(() -> {
            repository.insert(document("01JB00000000000000000000C1", ORGANIZATION_LOGO, "org-1", SHA));
            repository.insert(document("01JB00000000000000000000C2", ORGANIZATION_LOGO, "org-1", SHA));
            repository.insert(document("01JB00000000000000000000C3", CAMPAIGN_COVER, "org-1", SHA));
            repository.insert(document("01JB00000000000000000000C4", DELIVERY_EVIDENCE, "org-1", SHA));
            repository.insert(document("01JB00000000000000000000C5", DELIVERY_EVIDENCE, "org-2", SHA));
        }).doesNotThrowAnyException();
        assertThat(repository.count()).isEqualTo(5);
    }

    // ADR-051, definición de hecho 15 en Mongo real: el proveedor falla dos veces → ningún metadato
    @Test
    void storageUnavailable_leavesMediaObjectsEmpty() {
        storage.failNextPuts(2);

        assertThatThrownBy(() -> service.store(DELIVERY_EVIDENCE, "org-1", null, "acct-1", TestImages.jpeg(16, 16)))
                .isInstanceOf(MediaStorageUnavailableException.class);

        assertThat(repository.count()).isZero();
    }

    @Test
    void store_persistsTheMetadata_andRepeatedEvidenceReturnsTheSameRef() {
        byte[] photo = TestImages.jpeg(24, 16);

        StoredMedia first = service.store(DELIVERY_EVIDENCE, "org-1", null, "acct-1", photo);
        StoredMedia second = service.store(DELIVERY_EVIDENCE, "org-1", null, "acct-2", photo);

        assertThat(second).isEqualTo(first);
        assertThat(repository.count()).isEqualTo(1);
        MediaObjectDocument stored = repository.findByOrganizationIdAndPurposeAndSha256("org-1", DELIVERY_EVIDENCE,
                first.sha256()).orElseThrow();
        assertThat(stored.objectKey()).isEqualTo(first.objectKey());
        assertThat(stored.id()).isNotNull();
        assertThat(new int[]{stored.width(), stored.height()}).containsExactly(24, 16);
    }
}
