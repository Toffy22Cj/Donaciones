package com.traceability.app.web.asset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.app.TraceabilityApplication;
import com.traceability.app.application.media.TestImages;
import com.traceability.app.infrastructure.media.InMemoryMediaStorageAdapter;
import com.traceability.contracts.authorization.AuthorizationPrincipal;
import com.traceability.contracts.authorization.AuthorizationRole;
import com.traceability.contracts.authorization.IdentityPrincipalPort;
import com.traceability.core.application.port.out.EventStorePort;
import com.traceability.core.domain.event.DomainEvent;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * ADR-051, Tarea 4 (evidencia de entrega) contra Tomcat real, con {@code media_objects} y el Event Store reales y el
 * proveedor en memoria (plan T4 §5: T4-1 a T4-10, salvo T4-9b, que también está aquí por HTTP). La identidad es un
 * doble, como en {@code PhysicalAssetHttpIntegrationTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = TraceabilityApplication.class)
@Testcontainers
@Import(DeliveryEvidenceHttpIntegrationTest.InMemoryStorage.class)
@TestPropertySource(properties = {
    "crypto.anchor.poll.delay=9999999",
    "crypto.anchor.submit.delay=9999999",
    "crypto.anchor.stuck-monitor.delay=9999999",
    "crypto.web3j.private-key=0x1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef",
    "crypto.web3j.node-url=http://dummy-node",
    "spring.ai.openai.api-key=dummy-api-key"
})
class DeliveryEvidenceHttpIntegrationTest {

    @Container
    static MongoDBContainer mongo = new MongoDBContainer(DockerImageName.parse("mongo:6.0")).withCommand("--replSet", "rs0");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongo::getReplicaSetUrl);
    }

    @TestConfiguration
    static class InMemoryStorage {
        @Bean
        @Primary
        InMemoryMediaStorageAdapter inMemoryMediaStorage() {
            return Mockito.spy(new InMemoryMediaStorageAdapter());
        }
    }

    private static final String ORG = "org-t4";
    private static final String OTHER_ORG = "org-t4-other";
    private static final String EMPLOYEE = "acc-t4-employee";
    private static final String REPRESENTATIVE = "acc-t4-representative";
    private static final String OTHER_EMPLOYEE = "acc-t4-other-employee";
    private static final String ASSETS = "/api/v1/physical-assets";
    private static final String BOUNDARY = "t4-evidence-boundary-5c1e";

    @LocalServerPort private int port;
    @Autowired private com.traceability.contracts.authentication.TokenIssuerPort tokens;
    @Autowired private EventStorePort eventStore;
    @Autowired private MongoTemplate mongoTemplate;
    @Autowired private InMemoryMediaStorageAdapter storage;
    @MockitoBean private IdentityPrincipalPort identityPrincipalPort;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void principals() {
        Map<String, AuthorizationPrincipal> principals = Map.of(
                EMPLOYEE, new AuthorizationPrincipal(EMPLOYEE, ORG, Set.of(AuthorizationRole.EMPLOYEE), null),
                REPRESENTATIVE, new AuthorizationPrincipal(REPRESENTATIVE, ORG, Set.of(AuthorizationRole.REPRESENTATIVE), null),
                OTHER_EMPLOYEE, new AuthorizationPrincipal(OTHER_EMPLOYEE, OTHER_ORG, Set.of(AuthorizationRole.EMPLOYEE), null));
        when(identityPrincipalPort.resolvePrincipal(anyString())).thenAnswer(inv -> principals.get(inv.<String>getArgument(0)));
        when(identityPrincipalPort.resolveAuthenticatedPrincipal(anyString(), any()))
                .thenAnswer(inv -> principals.get(inv.<String>getArgument(0)));
        Mockito.reset(storage);
    }

    // ── HTTP ──

    private HttpResponse<String> send(String method, String path, String account, String commandId, String body)
            throws Exception {
        HttpRequest.Builder r = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(20))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (account != null) r.header("Authorization", "Bearer " + tokens.issue(account));
        if (commandId != null) r.header("Command-Id", commandId);
        if (body != null) r.header("Content-Type", "application/json");
        return http.send(r.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static byte[] multipart(byte[] file, String filename, String contentType) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + filename
                + "\"\r\nContent-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.writeBytes(file);
        out.writeBytes(("\r\n--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    private HttpResponse<String> upload(String org, String account, byte[] file, String filename) throws Exception {
        HttpRequest.Builder r = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                        + "/api/v1/organizations/" + org + "/delivery-evidence"))
                .timeout(Duration.ofSeconds(20))
                .POST(HttpRequest.BodyPublishers.ofByteArray(multipart(file, filename, "image/jpeg")))
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY);
        if (account != null) r.header("Authorization", "Bearer " + tokens.issue(account));
        return http.send(r.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Sube una foto propia (bytes distintos por semilla) y devuelve su evidenceRef. */
    private String photo(int seed) throws Exception {
        HttpResponse<String> r = upload(ORG, EMPLOYEE, TestImages.png(20 + seed, 16), "evidencia.png");
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        return json.readTree(r.body()).get("evidenceRef").asText();
    }

    private static final String IN_KIND = """
            {"assetType":"FOOD","quantity":"10","unitOfMeasure":"KGS","custodianRef":"CUST-1","currentLocation":"WH-1"}""";

    /** Un activo propio, despachado y recibido: lo único que admite entregar. */
    private String receivedAsset() throws Exception {
        HttpResponse<String> r = send("POST", ASSETS + "/from-donation", EMPLOYEE, newId(), IN_KIND);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        String assetRef = json.readTree(r.body()).get("assetRef").asText();
        assertThat(send("POST", ASSETS + "/" + assetRef + "/dispatch", EMPLOYEE, newId(), "{\"carrierRef\":\"C-1\"}")
                .statusCode()).isEqualTo(200);
        assertThat(send("POST", ASSETS + "/" + assetRef + "/receive", EMPLOYEE, newId(),
                "{\"facilityLocation\":\"WH-2\",\"receiverRef\":\"REC-1\"}").statusCode()).isEqualTo(200);
        return assetRef;
    }

    private HttpResponse<String> deliver(String account, String assetRef, String commandId, String evidenceRef)
            throws Exception {
        return send("POST", ASSETS + "/" + assetRef + "/deliver", account, commandId,
                "{\"finalCustodianRef\":\"CUST-F\",\"beneficiaryRef\":\"BEN-1\",\"locationRef\":\"SITE-1\",\"evidenceRef\":\""
                        + evidenceRef + "\"}");
    }

    private static String newId() {
        return UUID.randomUUID().toString();
    }

    private long eventsOf(String assetRef) {
        return eventStore.loadStream(assetRef).size();
    }

    private long deliveredEventsOf(String assetRef) {
        return eventStore.loadStream(assetRef).stream().map(DomainEvent::eventType)
                .filter(t -> t.name().equals("ASSET_DELIVERED")).count();
    }

    private Document evidenceDocument(String evidenceRef) {
        return mongoTemplate.getCollection("media_objects")
                .find(new Document("sha256", evidenceRef.substring("sha256:".length())).append("purpose", "DELIVERY_EVIDENCE"))
                .first();
    }

    // ── Subida (F2, F4, F8) ──

    // T4-1: JPEG con GPS → 201 {evidenceRef}, "sha256:" + SHA-256 de los bytes guardados (no de los subidos)
    @Test
    void t4_1_aJpegWithGps_is201_withTheSha256OfTheStoredBytes_withoutMetadata() throws Exception {
        byte[] uploaded = TestImages.withExifGpsXmpAndComment(TestImages.jpeg(64, 48));

        HttpResponse<String> r = upload(ORG, EMPLOYEE, uploaded, "foto.jpg");

        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        JsonNode body = json.readTree(r.body());
        List<String> fields = new ArrayList<>();
        body.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactly("evidenceRef");
        String evidenceRef = body.get("evidenceRef").asText();
        assertThat(evidenceRef).matches("^sha256:[0-9a-f]{64}$");
        Document doc = evidenceDocument(evidenceRef);
        assertThat(doc).isNotNull();
        assertThat(doc.getString("organizationId")).isEqualTo(ORG);
        assertThat(doc.get("boundAssetId")).isNull();
        byte[] stored = storage.get(doc.getString("objectKey")).orElseThrow();
        assertThat(evidenceRef).isEqualTo("sha256:" + TestImages.sha256Hex(stored))
                .isNotEqualTo("sha256:" + TestImages.sha256Hex(uploaded));
        assertThat(TestImages.contains(stored, TestImages.SECRET_MARK)).isFalse();
        // F9: los mismos bytes de la misma organización dan la misma huella sin duplicar
        HttpResponse<String> again = upload(ORG, EMPLOYEE, uploaded, "foto.jpg");
        assertThat(json.readTree(again.body()).get("evidenceRef").asText()).isEqualTo(evidenceRef);
        assertThat(mongoTemplate.getCollection("media_objects").countDocuments(
                new Document("sha256", doc.getString("sha256")).append("organizationId", ORG))).isEqualTo(1);
    }

    // T4-2: un .jpg que no es JPEG ni PNG → 415
    @Test
    void t4_2_aJpgThatIsNotAnImage_is415() throws Exception {
        HttpResponse<String> r = upload(ORG, EMPLOYEE, "not an image".getBytes(StandardCharsets.UTF_8), "foto.jpg");
        assertThat(r.statusCode()).isEqualTo(415);
        assertThat(json.readTree(r.body()).get("title").asText()).isEqualTo("UnsupportedImageFormat");
    }

    // T4-3: pocos bytes y dimensiones por encima del máximo → 413
    @Test
    void t4_3_dimensionsAboveTheMaximum_are413() throws Exception {
        HttpResponse<String> r = upload(ORG, EMPLOYEE, TestImages.withJpegDimensions(TestImages.jpeg(8, 8), 9000, 9000),
                "foto.jpg");
        assertThat(r.statusCode()).isEqualTo(413);
        assertThat(json.readTree(r.body()).get("title").asText()).isEqualTo("ImageTooLarge");
    }

    // T4-4: más de 5 MB → 413
    @Test
    void t4_4_moreThan5Mb_is413() throws Exception {
        byte[] big = new byte[5 * 1024 * 1024 + 1024];
        byte[] png = TestImages.png(8, 8);
        System.arraycopy(png, 0, big, 0, png.length);
        HttpResponse<String> r = upload(ORG, EMPLOYEE, big, "foto.png");
        assertThat(r.statusCode()).isEqualTo(413);
        assertThat(json.readTree(r.body()).get("title").asText()).isEqualTo("ImageTooLarge");
    }

    // T4-5: sin JWT → 401; otra organización o sin EMPLOYEE → el 403 uniforme, y nada se guarda
    @Test
    void t4_5_withoutJwt401_anotherOrganizationOrWithoutEmployee_theSame403_andNothingIsStored() throws Exception {
        byte[] image = TestImages.png(30, 30);
        assertThat(upload(ORG, null, image, "f.png").statusCode()).isEqualTo(401);
        HttpResponse<String> other = upload(ORG, OTHER_EMPLOYEE, image, "f.png");
        HttpResponse<String> representative = upload(ORG, REPRESENTATIVE, image, "f.png");
        assertThat(other.statusCode()).isEqualTo(403);
        assertThat(representative.statusCode()).isEqualTo(403);
        assertThat(json.readTree(other.body()).get("title")).isEqualTo(json.readTree(representative.body()).get("title"));
        Mockito.verify(storage, Mockito.never()).put(anyString(), any(), anyString());
    }

    // ── Entrega (F3) ──

    // T4-6: entregar con texto libre → 400 y ningún evento
    @Test
    void t4_6_deliveringWithAFreeTextEvidenceRef_is400_andNoEvent() throws Exception {
        String asset = receivedAsset();
        long before = eventsOf(asset);

        HttpResponse<String> r = deliver(EMPLOYEE, asset, newId(), "acta-1");

        assertThat(r.statusCode()).as(r.body()).isEqualTo(400);
        assertThat(json.readTree(r.body()).get("title").asText()).isEqualTo("InvalidEvidenceRef");
        assertThat(eventsOf(asset)).isEqualTo(before);
    }

    // Camino feliz: la foto queda vinculada a ese activo
    @Test
    void aDeliveryWithItsOwnPhoto_is200_andBindsThePhotoToThatAsset() throws Exception {
        String asset = receivedAsset();
        String evidenceRef = photo(1);

        HttpResponse<String> r = deliver(EMPLOYEE, asset, newId(), evidenceRef);

        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        assertThat(evidenceDocument(evidenceRef).getString("boundAssetId")).isEqualTo(asset);
        assertThat(deliveredEventsOf(asset)).isEqualTo(1);
    }

    // T4-7: huella de otra organización, inexistente o borrada → el mismo 400 EvidenceNotFound, sin eventos
    @Test
    void t4_7_aFootprintOfAnotherOrganizationUnknownOrDeleted_isTheSame400_withoutEvents() throws Exception {
        HttpResponse<String> foreign = upload(OTHER_ORG, OTHER_EMPLOYEE, TestImages.png(41, 17), "f.png");
        String foreignRef = json.readTree(foreign.body()).get("evidenceRef").asText();
        String unknownRef = "sha256:" + "0".repeat(64);
        String deletedRef = photo(2);
        mongoTemplate.getCollection("media_objects").updateOne(
                new Document("sha256", deletedRef.substring(7)).append("organizationId", ORG),
                new Document("$set", new Document("deletedAt", java.util.Date.from(Instant.now()))));

        List<String> bodies = new ArrayList<>();
        for (String ref : List.of(foreignRef, unknownRef, deletedRef)) {
            String asset = receivedAsset();
            long before = eventsOf(asset);
            HttpResponse<String> r = deliver(EMPLOYEE, asset, newId(), ref);
            assertThat(r.statusCode()).as(ref + ": " + r.body()).isEqualTo(400);
            JsonNode body = json.readTree(r.body());
            assertThat(body.get("title").asText()).isEqualTo("EvidenceNotFound");
            bodies.add(body.get("title").asText() + "|" + body.path("detail").asText() + "|" + body.path("status").asText());
            assertThat(eventsOf(asset)).isEqualTo(before);
        }
        assertThat(bodies).as("el mismo cuerpo en los tres casos").containsOnly(bodies.get(0));
        assertThat(evidenceDocument(foreignRef).get("boundAssetId")).isNull();
    }

    // T4-8: una foto ya vinculada, en un segundo activo → 409 y ningún evento nuevo en el segundo
    @Test
    void t4_8_aPhotoAlreadyBound_onASecondAsset_is409_andTheSecondDoesNotChange() throws Exception {
        String first = receivedAsset();
        String second = receivedAsset();
        String evidenceRef = photo(3);
        assertThat(deliver(EMPLOYEE, first, newId(), evidenceRef).statusCode()).isEqualTo(200);
        long before = eventsOf(second);

        HttpResponse<String> r = deliver(EMPLOYEE, second, newId(), evidenceRef);

        assertThat(r.statusCode()).as(r.body()).isEqualTo(409);
        assertThat(json.readTree(r.body()).get("title").asText()).isEqualTo("EvidenceAlreadyBound");
        assertThat(eventsOf(second)).isEqualTo(before);
        assertThat(evidenceDocument(evidenceRef).getString("boundAssetId")).isEqualTo(first);
    }

    // T4-9: la misma entrega con el mismo Command-Id → éxito, sin evento duplicado
    @Test
    void t4_9_theSameDeliveryWithTheSameCommandId_succeeds_withoutADuplicateEvent() throws Exception {
        String asset = receivedAsset();
        String evidenceRef = photo(4);
        String commandId = newId();

        assertThat(deliver(EMPLOYEE, asset, commandId, evidenceRef).statusCode()).isEqualTo(200);
        assertThat(deliver(EMPLOYEE, asset, commandId, evidenceRef).statusCode()).isEqualTo(200);

        assertThat(deliveredEventsOf(asset)).isEqualTo(1);
        assertThat(evidenceDocument(evidenceRef).getString("boundAssetId")).isEqualTo(asset);
    }

    // T4-9b: una entrega rechazada por permisos no vincula; la foto sirve después en otro activo
    @Test
    void t4_9b_aDeliveryRejectedForPermissions_doesNotBind_andThePhotoServesAnotherAssetLater() throws Exception {
        String asset = receivedAsset();
        String evidenceRef = photo(5);

        assertThat(deliver(OTHER_EMPLOYEE, asset, newId(), evidenceRef).statusCode()).isEqualTo(403);
        assertThat(deliver(REPRESENTATIVE, asset, newId(), evidenceRef).statusCode()).isEqualTo(403);
        assertThat(evidenceDocument(evidenceRef).get("boundAssetId")).isNull();

        String another = receivedAsset();
        assertThat(deliver(EMPLOYEE, another, newId(), evidenceRef).statusCode()).isEqualTo(200);
        assertThat(evidenceDocument(evidenceRef).getString("boundAssetId")).isEqualTo(another);
    }

    // T4-9b: rechazada por estado (sin despachar ni recibir) → no vincula
    @Test
    void t4_9b_aDeliveryRejectedByTheAssetState_doesNotBind() throws Exception {
        HttpResponse<String> registered = send("POST", ASSETS + "/from-donation", EMPLOYEE, newId(), IN_KIND);
        String asset = json.readTree(registered.body()).get("assetRef").asText();
        String evidenceRef = photo(6);

        assertThat(deliver(EMPLOYEE, asset, newId(), evidenceRef).statusCode()).isNotEqualTo(200);
        assertThat(evidenceDocument(evidenceRef).get("boundAssetId")).isNull();
    }

    // T4-10: dos entregas simultáneas de activos distintos con la misma foto → una gana y la otra recibe 409
    @Test
    void t4_10_twoSimultaneousDeliveriesOfDifferentAssetsWithTheSamePhoto_oneWinsAndTheOtherIs409() throws Exception {
        String a = receivedAsset();
        String b = receivedAsset();
        String evidenceRef = photo(7);
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (String asset : List.of(a, b)) {
                results.add(pool.submit(() -> {
                    barrier.await();
                    return deliver(EMPLOYEE, asset, newId(), evidenceRef).statusCode();
                }));
            }
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : results) {
                statuses.add(f.get());
            }
            assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        } finally {
            pool.shutdownNow();
        }
        String winner = evidenceDocument(evidenceRef).getString("boundAssetId");
        assertThat(winner).isIn(a, b);
        String loser = winner.equals(a) ? b : a;
        assertThat(deliveredEventsOf(winner)).isEqualTo(1);
        assertThat(deliveredEventsOf(loser)).isZero();
    }
}
