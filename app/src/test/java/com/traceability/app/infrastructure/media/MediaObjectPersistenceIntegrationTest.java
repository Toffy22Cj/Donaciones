package com.traceability.app.infrastructure.media;

import com.traceability.app.TraceabilityApplication;
import com.traceability.app.application.media.DuplicateEvidenceException;
import com.traceability.app.application.media.DuplicateRedactedCopyException;
import com.traceability.app.application.media.PublicationDecision;
import com.traceability.app.application.media.ImageSanitizer;
import com.traceability.app.application.media.MediaObject;
import com.traceability.app.application.media.MediaObjectStore;
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
 * (definición de hecho 15). Los índices los crea {@code MongoIndexInitializer} al arrancar (H-IDX-1). El servicio usa
 * el adaptador real {@link MongoMediaObjectStore}, que traduce el choque del índice de la evidencia a
 * {@link DuplicateEvidenceException}.
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
    @Autowired private org.springframework.data.mongodb.core.MongoTemplate mongoTemplate;

    private InMemoryMediaStorageAdapter storage;
    private MediaObjectStore store;
    private MediaUploadService service;
    @Autowired private org.springframework.context.ApplicationContext context;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        storage = new InMemoryMediaStorageAdapter();
        store = new MongoMediaObjectStore(repository, mongoTemplate);
        service = new MediaUploadService(new ImageSanitizer(), storage, store, Clock.systemUTC());
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

    // Tarea 2 (§4.7): lectura por clave y borrado lógico de F10, con deletedAt, deletedBy y reason (F9)
    @Test
    void findByObjectKey_andMarkDeleted_keepTheMetadataWithTheDeletion() {
        repository.insert(document("01JB00000000000000000000D1", ORGANIZATION_LOGO, "org-1", SHA));

        MediaObject alive = store.findByObjectKey("01JB00000000000000000000D1").orElseThrow();
        assertThat(alive.deletedAt()).isNull();
        assertThat(alive.sha256()).isEqualTo(SHA);
        assertThat(alive.contentType()).isEqualTo("image/png");

        Instant deletedAt = Instant.parse("2026-10-21T10:00:00Z");
        store.markDeleted("01JB00000000000000000000D1", "acct-9", deletedAt, "REPLACED");

        MediaObjectDocument doc = repository.findAll().get(0);
        assertThat(doc.deletedAt()).isEqualTo(deletedAt);
        assertThat(doc.deletedBy()).isEqualTo("acct-9");
        assertThat(doc.reason()).isEqualTo("REPLACED");
        assertThat(doc.sha256()).isEqualTo(SHA);
        assertThat(repository.count()).isEqualTo(1);
        assertThat(store.findByObjectKey("01JB00000000000000000000D1").orElseThrow().deletedAt()).isEqualTo(deletedAt);
        assertThat(store.findByObjectKey("01JB0000000000000000000NONE")).isEmpty();
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

    static MediaObject mediaObject(String objectKey, MediaPurpose purpose, String organizationId, String sha256) {
        return new MediaObject(objectKey, sha256, purpose, organizationId, purpose == CAMPAIGN_COVER ? "cmp-1" : null,
                "image/png", 10, 1, 1, "acct-1", NOW);
    }

    // el puerto de salida (ADR-051 F9, iteración DT-2): lo que se inserta es lo que se lee, sin el documento de Mongo
    @Test
    void theStore_insertsAndFindsTheMetadata() {
        MediaObject inserted = mediaObject("01JB00000000000000000000D1", DELIVERY_EVIDENCE, "org-1", SHA);

        store.insert(inserted);

        assertThat(store.findByOrganizationIdAndPurposeAndSha256("org-1", DELIVERY_EVIDENCE, SHA)).contains(inserted);
        assertThat(store.findByOrganizationIdAndPurposeAndSha256("org-2", DELIVERY_EVIDENCE, SHA)).isEmpty();
        assertThat(repository.findAll()).singleElement().satisfies(document -> {
            assertThat(document.boundAssetId()).isNull();
            assertThat(document.deletedAt()).isNull();
            assertThat(document.deletedBy()).isNull();
            assertThat(document.reason()).isNull();
        });
    }

    // el adaptador traduce el choque de uq_evidence_sha256 a la excepción nombrada de la capa de aplicación
    @Test
    void theStore_translatesADuplicateEvidence_toDuplicateEvidenceException() {
        store.insert(mediaObject("01JB00000000000000000000E1", DELIVERY_EVIDENCE, "org-1", SHA));

        assertThatThrownBy(() -> store.insert(mediaObject("01JB00000000000000000000E2", DELIVERY_EVIDENCE, "org-1", SHA)))
                .isInstanceOf(DuplicateEvidenceException.class);
    }

    // otro choque (la clave del objeto) no es una evidencia duplicada: no se traduce
    @Test
    void theStore_doesNotTranslateADuplicateObjectKey() {
        store.insert(mediaObject("01JB00000000000000000000F1", ORGANIZATION_LOGO, "org-1", SHA));

        assertThatThrownBy(() -> store.insert(mediaObject("01JB00000000000000000000F1", CAMPAIGN_COVER, "org-1", "c".repeat(64))))
                .isInstanceOf(DuplicateKeyException.class)
                .isNotInstanceOf(DuplicateEvidenceException.class);
    }

    @Test
    void theApplicationWiresTheMongoStore() {
        assertThat(context.getBeansOfType(MediaObjectStore.class).values())
                .singleElement().isInstanceOf(MongoMediaObjectStore.class);
    }

    // ---- ADR-051, Tarea 4 (F3; plan T4 §3, P-T4-5 y §7): el vínculo de una evidencia con un activo ----

    private static MediaObjectDocument evidence(String objectKey, String organizationId, String sha256, String boundAssetId,
                                                Instant deletedAt) {
        return new MediaObjectDocument(null, objectKey, sha256, DELIVERY_EVIDENCE, organizationId, null, "image/png", 10,
                1, 1, "acct-1", NOW, boundAssetId, deletedAt, null, null);
    }

    private String boundAssetIdOf(String objectKey) {
        return repository.findByObjectKey(objectKey).orElseThrow().boundAssetId();
    }

    @Test
    void bindEvidence_bindsAnUnboundEvidence_andAgainTheSameAssetWithoutError() {
        repository.insert(evidence("01JB0000000000000000000E01", "org-1", SHA, null, null));

        assertThat(store.bindEvidence("org-1", SHA, "asset-1")).isEqualTo(MediaObjectStore.BindResult.BOUND);
        assertThat(boundAssetIdOf("01JB0000000000000000000E01")).isEqualTo("asset-1");
        // F3: reintentar el mismo activo con la misma foto se permite
        assertThat(store.bindEvidence("org-1", SHA, "asset-1")).isEqualTo(MediaObjectStore.BindResult.BOUND);
    }

    @Test
    void bindEvidence_toAnotherAsset_isBoundToOther_andDoesNotChangeTheBinding() {
        repository.insert(evidence("01JB0000000000000000000E02", "org-1", SHA, "asset-1", null));

        assertThat(store.bindEvidence("org-1", SHA, "asset-2")).isEqualTo(MediaObjectStore.BindResult.BOUND_TO_OTHER);
        assertThat(boundAssetIdOf("01JB0000000000000000000E02")).isEqualTo("asset-1");
    }

    // F3: inexistente, de otra organización, que no es evidencia o borrada → NOT_FOUND, el mismo en todos los casos
    @Test
    void bindEvidence_unknownOtherOrganizationNotEvidenceOrDeleted_isNotFound_andBindsNothing() {
        repository.insert(evidence("01JB0000000000000000000E03", "org-2", SHA, null, null));
        repository.insert(document("01JB0000000000000000000E04", ORGANIZATION_LOGO, "org-1", SHA));
        repository.insert(evidence("01JB0000000000000000000E05", "org-1", "c".repeat(64), null, NOW));

        assertThat(store.bindEvidence("org-1", "d".repeat(64), "asset-1")).isEqualTo(MediaObjectStore.BindResult.NOT_FOUND);
        assertThat(store.bindEvidence("org-1", SHA, "asset-1")).isEqualTo(MediaObjectStore.BindResult.NOT_FOUND);
        assertThat(store.bindEvidence("org-1", "c".repeat(64), "asset-1")).isEqualTo(MediaObjectStore.BindResult.NOT_FOUND);
        assertThat(repository.findAll()).allSatisfy(d -> assertThat(d.boundAssetId()).isNull());
    }

    // Un borrado ya vinculado a otro activo sigue siendo NOT_FOUND (no se revela el vínculo de una foto retirada)
    @Test
    void bindEvidence_onADeletedEvidenceBoundToAnotherAsset_isNotFound() {
        repository.insert(evidence("01JB0000000000000000000E06", "org-1", SHA, "asset-1", NOW));

        assertThat(store.bindEvidence("org-1", SHA, "asset-2")).isEqualTo(MediaObjectStore.BindResult.NOT_FOUND);
    }

    // Plan T4, §7: la escritura condicional usa uq_evidence_sha256 (IXSCAN), no COLLSCAN
    @Test
    void bindEvidence_isAConditionalUpdateOnTheEvidenceIndex() {
        for (int i = 0; i < 50; i++) {
            repository.insert(evidence(String.format("01JB00000000000000000F%04d", i), "org-" + (i % 5),
                    String.format("%064x", i), null, null));
        }
        org.bson.Document target = new org.bson.Document("profile", 2);
        mongoTemplate.getDb().runCommand(target);
        try {
            assertThat(store.bindEvidence("org-2", String.format("%064x", 7), "asset-x"))
                    .isEqualTo(MediaObjectStore.BindResult.BOUND);
        } finally {
            mongoTemplate.getDb().runCommand(new org.bson.Document("profile", 0));
        }
        java.util.List<org.bson.Document> updates = new java.util.ArrayList<>();
        mongoTemplate.getDb().getCollection("system.profile")
                .find(new org.bson.Document("ns", mongoTemplate.getDb().getName() + ".media_objects").append("op", "update"))
                .into(updates);
        assertThat(updates).hasSize(1);
        System.out.println("[medición plan T4 §7] bindEvidence: planSummary=" + updates.get(0).get("planSummary")
                + " keysExamined=" + updates.get(0).get("keysExamined") + " docsExamined=" + updates.get(0).get("docsExamined"));
        assertThat(updates.get(0).getString("planSummary")).startsWith("IXSCAN").contains("organizationId: 1, purpose: 1, sha256: 1");
        assertThat(((Number) updates.get(0).get("docsExamined")).intValue()).isEqualTo(1);
    }

    // ---- ADR-051 Enmienda 2, E2-1 (plan de la Tarea 1): el vínculo con el propósito del paso ----

    private static MediaObjectDocument stepEvidence(String objectKey, MediaPurpose purpose, String sha256, String boundAssetId) {
        return new MediaObjectDocument(null, objectKey, sha256, purpose, "org-1", null, "image/png", 10, 1, 1, "acct-1", NOW,
                boundAssetId, null, null, null);
    }

    @Test
    void bindEvidenceWithAPurpose_bindsOnlyAPhotoOfThatSamePurpose() {
        repository.insert(stepEvidence("01JB0000000000000000000S01", MediaPurpose.DISPATCH_EVIDENCE, SHA, null));

        // una foto de despacho no existe para la recepción, ni para el registro, ni para la entrega
        assertThat(store.bindEvidence("org-1", SHA, "asset-1", MediaPurpose.RECEIPT_EVIDENCE))
                .isEqualTo(MediaObjectStore.BindResult.NOT_FOUND);
        assertThat(store.bindEvidence("org-1", SHA, "asset-1", MediaPurpose.REGISTRATION_EVIDENCE))
                .isEqualTo(MediaObjectStore.BindResult.NOT_FOUND);
        assertThat(store.bindEvidence("org-1", SHA, "asset-1")).isEqualTo(MediaObjectStore.BindResult.NOT_FOUND);
        assertThat(boundAssetIdOf("01JB0000000000000000000S01")).isNull();

        assertThat(store.bindEvidence("org-1", SHA, "asset-1", MediaPurpose.DISPATCH_EVIDENCE))
                .isEqualTo(MediaObjectStore.BindResult.BOUND);
        assertThat(boundAssetIdOf("01JB0000000000000000000S01")).isEqualTo("asset-1");
        // el mismo activo otra vez, sin error; otro activo, BOUND_TO_OTHER y el vínculo no cambia
        assertThat(store.bindEvidence("org-1", SHA, "asset-1", MediaPurpose.DISPATCH_EVIDENCE))
                .isEqualTo(MediaObjectStore.BindResult.BOUND);
        assertThat(store.bindEvidence("org-1", SHA, "asset-2", MediaPurpose.DISPATCH_EVIDENCE))
                .isEqualTo(MediaObjectStore.BindResult.BOUND_TO_OTHER);
        assertThat(boundAssetIdOf("01JB0000000000000000000S01")).isEqualTo("asset-1");
    }

    // la entrega no cambia: el método de tres argumentos sigue vinculando solo DELIVERY_EVIDENCE
    @Test
    void theThreeArgumentBindEvidence_stillBindsOnlyDeliveryEvidence() {
        repository.insert(evidence("01JB0000000000000000000S02", "org-1", SHA, null, null));
        assertThat(store.bindEvidence("org-1", SHA, "asset-1", MediaPurpose.DISPATCH_EVIDENCE))
                .isEqualTo(MediaObjectStore.BindResult.NOT_FOUND);
        assertThat(store.bindEvidence("org-1", SHA, "asset-1")).isEqualTo(MediaObjectStore.BindResult.BOUND);
    }

    // ---- ADR-051 Enmienda 2, Tarea 2 (plan §2.2): el índice único de las fotos de los pasos ----

    private static MediaObject stepObject(String objectKey, MediaPurpose purpose, String organizationId, String sha256) {
        return new MediaObject(objectKey, sha256, purpose, organizationId, null, "image/png", 10, 1, 1, "acct-1", NOW, null);
    }

    // la misma imagen dos veces en el mismo propósito y organización → DuplicateEvidenceException (el índice, traducido)
    @Test
    void insert_theSameStepEvidenceTwice_isADuplicate_forEachStepPurpose() {
        for (MediaPurpose purpose : new MediaPurpose[] {MediaPurpose.REGISTRATION_EVIDENCE, MediaPurpose.DISPATCH_EVIDENCE,
                MediaPurpose.RECEIPT_EVIDENCE}) {
            String sha = String.format("%064x", purpose.ordinal() + 900);
            store.insert(stepObject("01JB00000000000000000S1" + purpose.ordinal(), purpose, "org-1", sha));

            assertThatThrownBy(() -> store.insert(stepObject("01JB00000000000000000S2" + purpose.ordinal(), purpose, "org-1", sha)))
                    .as(purpose.name()).isInstanceOf(DuplicateEvidenceException.class);
            assertThat(repository.findAll().stream().filter(d -> d.sha256().equals(sha))).hasSize(1);
        }
    }

    // en propósitos distintos, o en otra organización, la misma imagen son objetos separados
    @Test
    void insert_theSameImageInAnotherPurposeOrOrganization_isAllowed() {
        String sha = String.format("%064x", 4242);
        store.insert(stepObject("01JB00000000000000000T01", MediaPurpose.DISPATCH_EVIDENCE, "org-1", sha));

        store.insert(stepObject("01JB00000000000000000T02", MediaPurpose.RECEIPT_EVIDENCE, "org-1", sha));
        store.insert(stepObject("01JB00000000000000000T03", MediaPurpose.DISPATCH_EVIDENCE, "org-2", sha));

        assertThat(repository.findAll().stream().filter(d -> d.sha256().equals(sha))).hasSize(3);
    }

    // ---- ADR-051 Enmienda 2, Tarea 3 (plan §2.2 y §2.4): la copia censurada y su historial de publicación ----

    private static MediaObject copy(String objectKey, String org, String originalKey, String sha) {
        return new MediaObject(objectKey, sha, MediaPurpose.DELIVERY_EVIDENCE_REDACTED, org, originalKey, "image/png", 10, 480,
                360, "acct-1", NOW, null);
    }

    // Q-T3-1: la unicidad de la copia es por su original (ownerRef)
    @Test
    void insert_aSecondCopyOfTheSameOriginal_isADuplicateRedactedCopy_withoutADuplicateEvidenceException() {
        store.insert(copy("01JB00000000000000000R01", "org-1", "ORIGINAL-A", String.format("%064x", 11)));

        assertThatThrownBy(() -> store.insert(copy("01JB00000000000000000R02", "org-1", "ORIGINAL-A", String.format("%064x", 12))))
                .isInstanceOf(DuplicateRedactedCopyException.class)
                .isNotInstanceOf(DuplicateEvidenceException.class);
        assertThat(repository.findAll().stream().filter(d -> "ORIGINAL-A".equals(d.ownerRef()))).hasSize(1);
    }

    // Q-T3-1 (el hallazgo): dos originales distintas pueden dar copias IDÉNTICAS (misma huella): las dos se guardan
    @Test
    void insert_twoCopiesWithTheSameFootprint_ofDifferentOriginals_areBothAllowed() {
        String sha = String.format("%064x", 77);
        store.insert(copy("01JB00000000000000000R03", "org-1", "ORIGINAL-B", sha));
        store.insert(copy("01JB00000000000000000R04", "org-1", "ORIGINAL-C", sha));

        assertThat(repository.findAll().stream().filter(d -> sha.equals(d.sha256()))).hasSize(2);
        assertThat(store.findByOrganizationIdAndPurposeAndOwnerRef("org-1", MediaPurpose.DELIVERY_EVIDENCE_REDACTED, "ORIGINAL-C"))
                .get().extracting(MediaObject::objectKey).isEqualTo("01JB00000000000000000R04");
        assertThat(store.findByOrganizationIdAndPurposeAndOwnerRef("org-2", MediaPurpose.DELIVERY_EVIDENCE_REDACTED, "ORIGINAL-C")).isEmpty();
    }

    @Test
    void theRedactedIndex_exists_andNoShaIndexCoversTheCopy() {
        var indexes = mongoTemplate.indexOps("media_objects").getIndexInfo();
        var owner = indexes.stream().filter(i -> i.getName().equals("uq_redacted_owner")).findFirst().orElseThrow();
        assertThat(owner.isUnique()).isTrue();
        assertThat(owner.getIndexFields()).extracting(f -> f.getKey()).containsExactly("organizationId", "purpose", "ownerRef");
        assertThat(owner.getPartialFilterExpression()).contains("DELIVERY_EVIDENCE_REDACTED");
        for (var index : indexes) {
            if (index.getName().equals("uq_evidence_sha256") || index.getName().equals("uq_step_evidence_sha256")) {
                assertThat(index.getPartialFilterExpression()).as(index.getName()).doesNotContain("DELIVERY_EVIDENCE_REDACTED");
            }
        }
    }

    private static PublicationDecision decision(PublicationDecision.Action action, String by, int minute) {
        return new PublicationDecision(action, Instant.parse("2026-11-19T10:0" + minute + ":00Z"), by,
                action == PublicationDecision.Action.PUBLISH ? Boolean.TRUE : null);
    }

    // E2-5: publicar, despublicar y volver a publicar deja tres entradas en orden; las repetidas no añaden nada
    @Test
    void appendPublicationDecision_isConditional_neverReplaces_andKeepsTheOrder() {
        store.insert(copy("01JB00000000000000000R05", "org-1", "ORIGINAL-D", String.format("%064x", 5)));
        String key = "01JB00000000000000000R05";

        assertThat(store.appendPublicationDecision(key, decision(PublicationDecision.Action.PUBLISH, "admin-1", 1))).isTrue();
        PublicationDecision first = store.findPublication(key).orElseThrow().decisions().get(0);
        assertThat(store.appendPublicationDecision(key, decision(PublicationDecision.Action.PUBLISH, "admin-2", 2))).isFalse();
        assertThat(store.appendPublicationDecision(key, decision(PublicationDecision.Action.UNPUBLISH, "admin-1", 3))).isTrue();
        assertThat(store.appendPublicationDecision(key, decision(PublicationDecision.Action.UNPUBLISH, "admin-1", 4))).isFalse();
        assertThat(store.appendPublicationDecision(key, decision(PublicationDecision.Action.PUBLISH, "admin-3", 5))).isTrue();

        var publication = store.findPublication(key).orElseThrow();
        assertThat(publication.decisions()).extracting(PublicationDecision::action, PublicationDecision::by).containsExactly(
                org.assertj.core.groups.Tuple.tuple(PublicationDecision.Action.PUBLISH, "admin-1"),
                org.assertj.core.groups.Tuple.tuple(PublicationDecision.Action.UNPUBLISH, "admin-1"),
                org.assertj.core.groups.Tuple.tuple(PublicationDecision.Action.PUBLISH, "admin-3"));
        assertThat(publication.decisions().get(0)).isEqualTo(first); // la primera no cambió: ni `at`, ni `by`, ni la confirmación
        assertThat(publication.published()).isTrue();
    }

    // una copia retirada por F7 no se puede publicar (la escritura condicional no coincide)
    @Test
    void aDeletedCopy_cannotBePublished() {
        store.insert(copy("01JB00000000000000000R06", "org-1", "ORIGINAL-E", String.format("%064x", 6)));
        store.markDeleted("01JB00000000000000000R06", "platform-1", NOW, "motivo");

        assertThat(store.appendPublicationDecision("01JB00000000000000000R06", decision(PublicationDecision.Action.PUBLISH, "a", 1))).isFalse();
        assertThat(store.findPublication("01JB00000000000000000R06").orElseThrow().decisions()).isEmpty();
    }

    // markDeleted conserva el historial (antes reconstruía el documento entero)
    @Test
    void markDeleted_keepsThePublicationHistory() {
        store.insert(copy("01JB00000000000000000R07", "org-1", "ORIGINAL-F", String.format("%064x", 7)));
        store.appendPublicationDecision("01JB00000000000000000R07", decision(PublicationDecision.Action.PUBLISH, "admin-1", 1));

        store.markDeleted("01JB00000000000000000R07", "platform-1", NOW, "motivo");

        var publication = store.findPublication("01JB00000000000000000R07").orElseThrow();
        assertThat(publication.decisions()).hasSize(1);
        assertThat(repository.findByObjectKey("01JB00000000000000000R07").orElseThrow().deletedAt()).isNotNull();
    }

    // E2-5 / DoD 10b: el almacén no ofrece ninguna operación que reemplace o borre entradas del historial
    @Test
    void theStoreInterface_offersNoOperationThatReplacesOrDeletesDecisions() {
        for (var method : MediaObjectStore.class.getMethods()) {
            String name = method.getName().toLowerCase();
            if (name.contains("decision") || name.contains("publication")) {
                assertThat(name).as(method.toString()).doesNotContain("replace", "delete", "remove", "clear", "set", "update", "overwrite");
            }
        }
        assertThat(java.util.Arrays.stream(MediaObjectStore.class.getMethods()).map(java.lang.reflect.Method::getName))
                .contains("appendPublicationDecision", "findPublication");
    }
}
