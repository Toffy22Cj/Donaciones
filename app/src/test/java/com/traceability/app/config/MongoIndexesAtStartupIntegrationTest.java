package com.traceability.app.config;

import com.traceability.app.TraceabilityApplication;
import com.traceability.convocatoria.infrastructure.persistence.mongo.document.CampaignAssignmentDocument;
import com.traceability.convocatoria.infrastructure.persistence.mongo.document.ConfigurationChangeRequestDocument;
import com.traceability.convocatoria.infrastructure.persistence.mongo.document.DonationIntentDocument;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.MongoPersistentEntityIndexResolver;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * H-IDX-1 (Carlos, 2026-10-08): con la base vacía y sin una sola petición, al terminar el arranque existen todas las
 * colecciones y todos los índices que el código declara. Si alguno falta, este test falla. El {@code application.yml}
 * de test no activa {@code auto-index-creation}: lo que hay aquí lo crea {@link MongoIndexInitializer}.
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
class MongoIndexesAtStartupIntegrationTest {

    @Container
    static MongoDBContainer mongo = new MongoDBContainer(DockerImageName.parse("mongo:6.0")).withCommand("--replSet", "rs0");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongo::getReplicaSetUrl);
    }

    @Autowired private MongoTemplate mongoTemplate;
    @Autowired private MongoMappingContext mappingContext;

    private Map<String, Document> indexes(String collection) {
        Map<String, Document> byName = new HashMap<>();
        mongoTemplate.getCollection(collection).listIndexes().into(new ArrayList<>())
                .forEach(i -> byName.put(i.getString("name"), i));
        return byName;
    }

    private Document index(String collection, String name) {
        Document index = indexes(collection).get(name);
        assertThat(index).as(collection + "." + name).isNotNull();
        return index;
    }

    private void unique(String collection, String name, Document keys) {
        Document index = index(collection, name);
        assertThat(index.get("key", Document.class)).as(collection + "." + name).isEqualTo(keys);
        assertThat(index.getBoolean("unique", false)).as(collection + "." + name + " único").isTrue();
    }

    private void uniquePartial(String collection, String name, Document keys, Document filter) {
        unique(collection, name, keys);
        assertThat(index(collection, name).get("partialFilterExpression", Document.class))
                .as(collection + "." + name + " parcial").isEqualTo(filter);
    }

    @Test
    void theCriticalUniqueAndPartialIndexes_existRightAfterStartup() {
        // el event store: un evento por (stream, secuencia); es la barrera de concurrencia de los agregados
        unique("event_store", "idx_stream_sequence", new Document("streamId", 1).append("sequence", 1));
        index("event_store", "idx_event_type_campaign");
        // un EMPLOYEE solo puede ser responsable de una convocatoria activa
        uniquePartial(CampaignAssignmentDocument.COLLECTION, CampaignAssignmentDocument.ACTIVE_EMPLOYEE_INDEX,
                new Document("employeeRef", 1), new Document("status", "ACTIVE").append("actingRole", "EMPLOYEE"));
        // intenciones de donación: un fondo, una sesión de pago y un evento del proveedor por intención
        unique(DonationIntentDocument.COLLECTION, "uq_fund_id", new Document("fundId", 1));
        uniquePartial(DonationIntentDocument.COLLECTION, "uq_payment_session_id", new Document("paymentSessionId", 1),
                new Document("paymentSessionId", new Document("$type", "string")));
        assertThat(index(DonationIntentDocument.COLLECTION, DonationIntentDocument.PROVIDER_EVENT_INDEX)
                .getBoolean("unique", false)).isTrue();
        assertThat(index(DonationIntentDocument.COLLECTION, DonationIntentDocument.PENDING_APPLICATION_INDEX)
                .get("partialFilterExpression", Document.class)).isEqualTo(new Document("status", "CONFIRMED"));
        index(DonationIntentDocument.COLLECTION, "idx_campaign_ref");
        index(DonationIntentDocument.COLLECTION, DonationIntentDocument.DONOR_REF_INDEX);
        assertThat(index("unacceptable_payment_events", "uq_provider_event").getBoolean("unique", false)).isTrue();
        uniquePartial(ConfigurationChangeRequestDocument.COLLECTION, ConfigurationChangeRequestDocument.PENDING_INDEX,
                new Document("campaignRef", 1), new Document("status", "PENDING"));
        unique("convocatorias", "uq_public_code", new Document("publicCode", 1));
        // identidad
        unique("accounts", "email", new Document("email", 1));
        unique("donor_pseudonyms", "pseudonym", new Document("pseudonym", 1));
        unique("organization_invitations", "tokenHash", new Document("tokenHash", 1));
        // anclaje
        unique("merkle_batches", "batchId", new Document("batchId", 1));
        // reclamos de comandos: la unicidad es la de _id, que existe con la colección
        for (String claims : List.of("processed_commands", "convocatoria_processed_commands", "outbox")) {
            assertThat(mongoTemplate.collectionExists(claims)).as(claims).isTrue();
            index(claims, "_id_");
        }
    }

    @Test
    void everyCollectionAndIndexDeclaredByTheCode_existsRightAfterStartup() {
        var resolver = new MongoPersistentEntityIndexResolver(mappingContext);
        List<Class<?>> documents = MongoIndexInitializer.documentClasses();
        assertThat(documents).hasSizeGreaterThanOrEqualTo(29);
        for (Class<?> type : documents) {
            String collection = mappingContext.getRequiredPersistentEntity(type).getCollection();
            assertThat(mongoTemplate.collectionExists(collection)).as(collection).isTrue();
            Map<String, Document> existing = indexes(collection);
            for (var definition : resolver.resolveIndexFor(type)) {
                String name = definition.getIndexOptions().getString("name");
                Document actual = existing.get(name);
                assertThat(actual).as(collection + "." + name).isNotNull();
                assertThat(actual.get("key", Document.class)).as(collection + "." + name)
                        .isEqualTo(definition.getIndexKeys());
                assertThat(actual.getBoolean("unique", false)).as(collection + "." + name + " único")
                        .isEqualTo(definition.getIndexOptions().getBoolean("unique", false));
                assertThat(actual.get("partialFilterExpression")).as(collection + "." + name + " parcial")
                        .isEqualTo(definition.getIndexOptions().get("partialFilterExpression"));
            }
        }
    }
}
