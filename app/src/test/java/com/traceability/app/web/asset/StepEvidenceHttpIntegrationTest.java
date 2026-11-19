package com.traceability.app.web.asset;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.app.TraceabilityApplication;
import com.traceability.app.application.media.MediaPurpose;
import com.traceability.app.infrastructure.media.MediaObjectDocument;
import com.traceability.contracts.authorization.AuthorizationPrincipal;
import com.traceability.contracts.authorization.AuthorizationRole;
import com.traceability.contracts.authorization.IdentityPrincipalPort;
import com.traceability.core.application.port.out.EventStorePort;
import com.traceability.core.domain.event.DomainEvent;
import com.traceability.core.domain.physicalasset.payloads.AssetDispatchedV3Payload;
import com.traceability.core.domain.physicalasset.payloads.AssetReceivedV3Payload;
import com.traceability.core.domain.physicalasset.payloads.AssetRegisteredV5Payload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * ADR-051 Enmienda 2, E2-1 (plan de la Tarea 1, grupo D, tests 6 a 11 y 15) contra Tomcat real, con Mongo real: el
 * {@code evidenceRef} opcional de registrar, despachar y recibir. Las fotos se crean directamente en
 * {@code media_objects} (la ruta de subida de estos pasos es de la tarea 2).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = TraceabilityApplication.class)
@Testcontainers
@TestPropertySource(properties = {
    "crypto.anchor.poll.delay=9999999",
    "crypto.anchor.submit.delay=9999999",
    "crypto.anchor.stuck-monitor.delay=9999999",
    "crypto.web3j.private-key=0x1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef",
    "crypto.web3j.node-url=http://dummy-node",
    "spring.ai.openai.api-key=dummy-api-key"
})
class StepEvidenceHttpIntegrationTest {

    @Container
    static MongoDBContainer mongo = new MongoDBContainer(DockerImageName.parse("mongo:6.0")).withCommand("--replSet", "rs0");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongo::getReplicaSetUrl);
    }

    private static final String ORG = "org-t1";
    private static final String OTHER_ORG = "org-t1-other";
    private static final String EMPLOYEE = "acc-t1-employee";
    private static final String REPRESENTATIVE = "acc-t1-representative";
    private static final String OTHER_EMPLOYEE = "acc-t1-other-employee";
    private static final String ASSETS = "/api/v1/physical-assets";
    private static final Instant NOW = Instant.parse("2026-11-19T10:00:00Z");

    @LocalServerPort private int port;
    @Autowired private com.traceability.contracts.authentication.TokenIssuerPort tokens;
    @Autowired private EventStorePort eventStore;
    @Autowired private MongoTemplate mongoTemplate;
    @MockitoBean private IdentityPrincipalPort identityPrincipalPort;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();
    private int seed;

    @BeforeEach
    void principals() {
        Map<String, AuthorizationPrincipal> principals = Map.of(
                EMPLOYEE, new AuthorizationPrincipal(EMPLOYEE, ORG, Set.of(AuthorizationRole.EMPLOYEE), null),
                REPRESENTATIVE, new AuthorizationPrincipal(REPRESENTATIVE, ORG, Set.of(AuthorizationRole.REPRESENTATIVE), null),
                OTHER_EMPLOYEE, new AuthorizationPrincipal(OTHER_EMPLOYEE, OTHER_ORG, Set.of(AuthorizationRole.EMPLOYEE), null));
        when(identityPrincipalPort.resolvePrincipal(anyString())).thenAnswer(inv -> principals.get(inv.<String>getArgument(0)));
        when(identityPrincipalPort.resolveAuthenticatedPrincipal(anyString(), any()))
                .thenAnswer(inv -> principals.get(inv.<String>getArgument(0)));
    }

    private HttpResponse<String> send(String path, String account, String commandId, String body) throws Exception {
        HttpRequest.Builder r = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(20))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json");
        if (account != null) r.header("Authorization", "Bearer " + tokens.issue(account));
        if (commandId != null) r.header("Command-Id", commandId);
        return http.send(r.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String newId() {
        return UUID.randomUUID().toString();
    }

    /** Una foto de ese propósito, ya subida (creada a mano en {@code media_objects}); devuelve su {@code evidenceRef}. */
    private String photo(MediaPurpose purpose, String organization) {
        String sha = String.format("%064x", UUID.randomUUID().getMostSignificantBits() ^ (++seed * 7919L)).substring(0, 64);
        mongoTemplate.insert(new MediaObjectDocument(null, "obj-" + UUID.randomUUID(), sha, purpose, organization, null,
                "image/png", 10, 1, 1, EMPLOYEE, NOW, null, null, null, null));
        return "sha256:" + sha;
    }

    private String boundAssetOf(String evidenceRef) {
        MediaObjectDocument d = mongoTemplate.findOne(new Query(Criteria.where("sha256")
                .is(evidenceRef.substring("sha256:".length()))), MediaObjectDocument.class);
        return d == null ? "<no existe>" : d.boundAssetId();
    }

    private static String inKind(String evidenceRef) {
        String base = "\"assetType\":\"FOOD\",\"quantity\":\"10\",\"unitOfMeasure\":\"KGS\",\"custodianRef\":\"CUST-1\","
                + "\"currentLocation\":\"WH-1\",\"municipalityCode\":\"05001\"";
        return evidenceRef == null ? "{" + base + "}" : "{" + base + ",\"evidenceRef\":\"" + evidenceRef + "\"}";
    }

    private String register(String evidenceRef) throws Exception {
        HttpResponse<String> r = send(ASSETS + "/from-donation", EMPLOYEE, newId(), inKind(evidenceRef));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        return json.readTree(r.body()).get("assetRef").asText();
    }

    private HttpResponse<String> dispatch(String account, String assetRef, String evidenceRef) throws Exception {
        String body = evidenceRef == null ? "{\"carrierRef\":\"C-1\"}"
                : "{\"carrierRef\":\"C-1\",\"evidenceRef\":\"" + evidenceRef + "\"}";
        return send(ASSETS + "/" + assetRef + "/dispatch", account, newId(), body);
    }

    private HttpResponse<String> receive(String account, String assetRef, String evidenceRef) throws Exception {
        String body = "{\"facilityLocation\":\"WH-2\",\"municipalityCode\":\"05001\",\"receiverRef\":\"REC-1\""
                + (evidenceRef == null ? "}" : ",\"evidenceRef\":\"" + evidenceRef + "\"}");
        return send(ASSETS + "/" + assetRef + "/receive", account, newId(), body);
    }

    private List<DomainEvent> events(String assetRef) {
        return eventStore.loadStream(assetRef);
    }

    // test 5 y 15: sin evidenceRef todo funciona como hoy, con la versión nueva y null
    @Test
    void t15_withoutAnEvidenceRef_theThreeStepsWork_andWriteTheNewVersionsWithNull() throws Exception {
        String assetRef = register(null);
        assertThat(dispatch(EMPLOYEE, assetRef, null).statusCode()).isEqualTo(200);
        assertThat(receive(EMPLOYEE, assetRef, null).statusCode()).isEqualTo(200);

        List<DomainEvent> events = events(assetRef);
        assertThat(events).hasSize(3);
        assertThat(((AssetRegisteredV5Payload) events.get(0).payload()).evidenceRef()).isNull();
        assertThat(((AssetDispatchedV3Payload) events.get(1).payload()).evidenceRef()).isNull();
        assertThat(((AssetReceivedV3Payload) events.get(2).payload()).evidenceRef()).isNull();
    }

    // test 6: con una foto de cada paso, el evento la lleva y la foto queda vinculada a ese activo
    @Test
    void t6_withAPhotoInEachStep_theEventsCarryThemAndThePhotosAreBoundToTheAsset() throws Exception {
        String registration = photo(MediaPurpose.REGISTRATION_EVIDENCE, ORG);
        String dispatchPhoto = photo(MediaPurpose.DISPATCH_EVIDENCE, ORG);
        String receipt = photo(MediaPurpose.RECEIPT_EVIDENCE, ORG);

        String assetRef = register(registration);
        assertThat(dispatch(EMPLOYEE, assetRef, dispatchPhoto).statusCode()).isEqualTo(200);
        assertThat(receive(EMPLOYEE, assetRef, receipt).statusCode()).isEqualTo(200);

        List<DomainEvent> events = events(assetRef);
        assertThat(((AssetRegisteredV5Payload) events.get(0).payload()).evidenceRef()).isEqualTo(registration);
        assertThat(((AssetDispatchedV3Payload) events.get(1).payload()).evidenceRef()).isEqualTo(dispatchPhoto);
        assertThat(((AssetReceivedV3Payload) events.get(2).payload()).evidenceRef()).isEqualTo(receipt);
        assertThat(boundAssetOf(registration)).isEqualTo(assetRef);
        assertThat(boundAssetOf(dispatchPhoto)).isEqualTo(assetRef);
        assertThat(boundAssetOf(receipt)).isEqualTo(assetRef);
    }

    // test 7: formato inválido → 400 y el Command-Id no se consume
    @Test
    void t7_anInvalidEvidenceRef_is400_andTheCommandIdIsNotConsumed() throws Exception {
        String assetRef = register(null);
        String commandId = newId();

        HttpResponse<String> bad = send(ASSETS + "/" + assetRef + "/dispatch", EMPLOYEE, commandId,
                "{\"carrierRef\":\"C-1\",\"evidenceRef\":\"acta-1\"}");
        assertThat(bad.statusCode()).as(bad.body()).isEqualTo(400);
        assertThat(events(assetRef)).hasSize(1);

        // el mismo Command-Id, ahora con un cuerpo válido, se procesa
        HttpResponse<String> good = send(ASSETS + "/" + assetRef + "/dispatch", EMPLOYEE, commandId, "{\"carrierRef\":\"C-1\"}");
        assertThat(good.statusCode()).as(good.body()).isEqualTo(200);
        assertThat(events(assetRef)).hasSize(2);
    }

    // test 8: inexistente, de otra organización, de otro paso (otro propósito) → 400 y ningún evento; la foto sigue libre
    @Test
    void t8_aPhotoThatDoesNotExistForThatStep_is400_writesNothing_andLeavesThePhotoFree() throws Exception {
        String assetRef = register(null);
        String missing = "sha256:" + "0".repeat(64);
        String ofAnotherOrganization = photo(MediaPurpose.DISPATCH_EVIDENCE, OTHER_ORG);
        String ofAnotherStep = photo(MediaPurpose.RECEIPT_EVIDENCE, ORG);
        String ofDelivery = photo(MediaPurpose.DELIVERY_EVIDENCE, ORG);

        for (String evidenceRef : List.of(missing, ofAnotherOrganization, ofAnotherStep, ofDelivery)) {
            HttpResponse<String> r = dispatch(EMPLOYEE, assetRef, evidenceRef);
            assertThat(r.statusCode()).as(evidenceRef + " " + r.body()).isEqualTo(400);
        }
        assertThat(events(assetRef)).hasSize(1);
        assertThat(boundAssetOf(ofAnotherStep)).isNull();
        assertThat(boundAssetOf(ofDelivery)).isNull();
        // y la foto de otro paso sí sirve en el suyo
        assertThat(dispatch(EMPLOYEE, assetRef, null).statusCode()).isEqualTo(200);
        assertThat(receive(EMPLOYEE, assetRef, ofAnotherStep).statusCode()).isEqualTo(200);
    }

    // test 9: la misma foto en otro activo → 409 y ningún evento (solo «otro activo» es 409; Q-T1-2)
    @Test
    void t9_thePhotoOfAnotherAsset_is409_andWritesNothing() throws Exception {
        String photo = photo(MediaPurpose.DISPATCH_EVIDENCE, ORG);
        String first = register(null);
        String second = register(null);
        assertThat(dispatch(EMPLOYEE, first, photo).statusCode()).isEqualTo(200);

        HttpResponse<String> r = dispatch(EMPLOYEE, second, photo);

        assertThat(r.statusCode()).as(r.body()).isEqualTo(409);
        assertThat(events(second)).hasSize(1);
        assertThat(boundAssetOf(photo)).isEqualTo(first);
    }

    // test 10: un rechazo por permisos (otra organización, otro rol) o por estado no vincula la foto
    @Test
    void t10_aRejectedStep_doesNotBindThePhoto() throws Exception {
        String assetRef = register(null);
        String photo = photo(MediaPurpose.DISPATCH_EVIDENCE, ORG);

        assertThat(dispatch(OTHER_EMPLOYEE, assetRef, photo).statusCode()).isEqualTo(403);
        assertThat(dispatch(REPRESENTATIVE, assetRef, photo).statusCode()).isEqualTo(403);
        assertThat(boundAssetOf(photo)).isNull();
        assertThat(events(assetRef)).hasSize(1);

        // por estado: recibir un activo sin despachar
        String receiptPhoto = photo(MediaPurpose.RECEIPT_EVIDENCE, ORG);
        assertThat(receive(EMPLOYEE, assetRef, receiptPhoto).statusCode()).isGreaterThanOrEqualTo(400);
        assertThat(boundAssetOf(receiptPhoto)).isNull();
        assertThat(events(assetRef)).hasSize(1);
    }

    // test 11: el mismo Command-Id con la misma foto es un no-op sin error ni segundo evento
    @Test
    void t11_theSameCommandIdAndPhotoResent_isANoOp() throws Exception {
        String assetRef = register(null);
        String photo = photo(MediaPurpose.DISPATCH_EVIDENCE, ORG);
        String commandId = newId();
        String body = "{\"carrierRef\":\"C-1\",\"evidenceRef\":\"" + photo + "\"}";

        assertThat(send(ASSETS + "/" + assetRef + "/dispatch", EMPLOYEE, commandId, body).statusCode()).isEqualTo(200);
        assertThat(send(ASSETS + "/" + assetRef + "/dispatch", EMPLOYEE, commandId, body).statusCode()).isEqualTo(200);

        assertThat(events(assetRef)).hasSize(2);
        assertThat(boundAssetOf(photo)).isEqualTo(assetRef);
    }
}
