package com.traceability.app.web.asset;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.app.TraceabilityApplication;
import com.traceability.app.application.media.TestImages;
import com.traceability.app.infrastructure.media.InMemoryMediaStorageAdapter;
import com.traceability.contracts.authorization.AuthorizationPrincipal;
import com.traceability.contracts.authorization.AuthorizationRole;
import com.traceability.contracts.authorization.IdentityPrincipalPort;
import com.traceability.contracts.authorization.PlatformAuthority;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ADR-051, Tarea 5 (plan de cierre §T5; criterios 12 y 14): leer la evidencia de entrega y retirarla por la plataforma
 * (F6, F7), contra Tomcat real con el Event Store y {@code media_objects} reales, el proveedor en memoria y los
 * principales de un doble, como en la Tarea 4. La retirada del logo y de la portada se prueba en sus tests (T5-6).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = TraceabilityApplication.class)
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
@Import(DeliveryEvidenceReadAndRemovalHttpIntegrationTest.InMemoryStorage.class)
@TestPropertySource(properties = {
    "crypto.anchor.poll.delay=9999999",
    "crypto.anchor.submit.delay=9999999",
    "crypto.anchor.stuck-monitor.delay=9999999",
    "crypto.web3j.private-key=0x1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef",
    "crypto.web3j.node-url=http://dummy-node",
    "spring.ai.openai.api-key=dummy-api-key"
})
class DeliveryEvidenceReadAndRemovalHttpIntegrationTest {

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

    private static final String ORG = "org-t5";
    private static final String OTHER_ORG = "org-t5-other";
    private static final String EMPLOYEE = "acc-t5-employee";
    private static final String REPRESENTATIVE = "acc-t5-representative";
    private static final String OTHER_EMPLOYEE = "acc-t5-other-employee";
    private static final String LONER = "acc-t5-loner";
    private static final String PLATFORM = "acc-t5-platform";
    private static final String ASSETS = "/api/v1/physical-assets";
    private static final String BOUNDARY = "t5-evidence-boundary-8d2b";
    private static final String REASON = "Aparece una persona en la foto — motivo-secreto-t5";

    @LocalServerPort private int port;
    @Autowired private com.traceability.contracts.authentication.TokenIssuerPort tokens;
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
                OTHER_EMPLOYEE, new AuthorizationPrincipal(OTHER_EMPLOYEE, OTHER_ORG, Set.of(AuthorizationRole.EMPLOYEE), null),
                LONER, new AuthorizationPrincipal(LONER, null, Set.of(), null),
                PLATFORM, new AuthorizationPrincipal(PLATFORM, null, Set.of(), PlatformAuthority.ADMINISTRATOR));
        when(identityPrincipalPort.resolvePrincipal(anyString())).thenAnswer(inv -> principals.get(inv.<String>getArgument(0)));
        when(identityPrincipalPort.resolveAuthenticatedPrincipal(anyString(), any()))
                .thenAnswer(inv -> principals.get(inv.<String>getArgument(0)));
        Mockito.reset(storage);
    }

    // ── HTTP ──

    private HttpResponse<byte[]> send(String method, String path, String account, String commandId, String body)
            throws Exception {
        HttpRequest.Builder r = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(20))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (account != null) r.header("Authorization", "Bearer " + tokens.issue(account));
        if (commandId != null) r.header("Command-Id", commandId);
        if (body != null) r.header("Content-Type", "application/json");
        return http.send(r.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private static String text(HttpResponse<byte[]> r) {
        return new String(r.body(), StandardCharsets.UTF_8);
    }

    private String photo(int seed) throws Exception {
        HttpResponse<String> r = upload(seed);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        return json.readTree(r.body()).get("evidenceRef").asText();
    }

    private HttpResponse<String> upload(int seed) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"e.png\""
                + "\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.writeBytes(TestImages.png(40 + seed, 18));
        out.writeBytes(("\r\n--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8));
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                        + "/api/v1/organizations/" + ORG + "/delivery-evidence")).timeout(Duration.ofSeconds(20))
                .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray()))
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .header("Authorization", "Bearer " + tokens.issue(EMPLOYEE)).build(), HttpResponse.BodyHandlers.ofString());
        return r;
    }

    private static final String IN_KIND = """
            {"assetType":"FOOD","quantity":"10","unitOfMeasure":"KGS","custodianRef":"CUST-1","currentLocation":"WH-1"}""";

    private String receivedAsset() throws Exception {
        HttpResponse<byte[]> r = send("POST", ASSETS + "/from-donation", EMPLOYEE, newId(), IN_KIND);
        assertThat(r.statusCode()).as(text(r)).isEqualTo(201);
        String assetRef = json.readTree(r.body()).get("assetRef").asText();
        assertThat(send("POST", ASSETS + "/" + assetRef + "/dispatch", EMPLOYEE, newId(), "{\"carrierRef\":\"C-1\"}")
                .statusCode()).isEqualTo(200);
        assertThat(send("POST", ASSETS + "/" + assetRef + "/receive", EMPLOYEE, newId(),
                "{\"facilityLocation\":\"WH-2\",\"receiverRef\":\"REC-1\"}").statusCode()).isEqualTo(200);
        return assetRef;
    }

    record Delivered(String assetRef, String evidenceRef) {}

    private Delivered deliveredAsset(int seed) throws Exception {
        String assetRef = receivedAsset();
        String evidenceRef = photo(seed);
        HttpResponse<byte[]> r = send("POST", ASSETS + "/" + assetRef + "/deliver", EMPLOYEE, newId(),
                "{\"finalCustodianRef\":\"CUST-F\",\"beneficiaryRef\":\"BEN-1\",\"locationRef\":\"SITE-1\",\"evidenceRef\":\""
                        + evidenceRef + "\"}");
        assertThat(r.statusCode()).as(text(r)).isEqualTo(200);
        return new Delivered(assetRef, evidenceRef);
    }

    private HttpResponse<byte[]> evidence(String assetRef, String account) throws Exception {
        return send("GET", ASSETS + "/" + assetRef + "/evidence", account, null, null);
    }

    private HttpResponse<byte[]> remove(String account, String org, String body) throws Exception {
        return send("POST", "/api/v1/platform/organizations/" + org + "/media/remove", account, null, body);
    }

    private static String removal(String purpose, String ref, String reason) {
        StringBuilder b = new StringBuilder("{");
        if (purpose != null) b.append("\"purpose\":\"").append(purpose).append("\",");
        if (ref != null) b.append("\"ref\":\"").append(ref).append("\",");
        if (reason != null) b.append("\"reason\":\"").append(reason).append("\",");
        if (b.length() > 1) b.setLength(b.length() - 1);
        return b.append("}").toString();
    }

    private Document media(String evidenceRef) {
        return mongoTemplate.getCollection("media_objects").find(new Document("sha256", evidenceRef.substring(7))
                .append("purpose", "DELIVERY_EVIDENCE")).first();
    }

    private static String newId() {
        return UUID.randomUUID().toString();
    }

    // ── T5-1 (criterio 12; F6): miembro de la organización del activo, o la plataforma ──

    @Test
    void t5_1_aMemberOfTheAssetsOrganization_andThePlatform_readTheStoredBytes_privateNoStore() throws Exception {
        Delivered d = deliveredAsset(1);
        byte[] stored = storage.get(media(d.evidenceRef()).getString("objectKey")).orElseThrow();

        for (String who : List.of(EMPLOYEE, REPRESENTATIVE, PLATFORM)) {
            HttpResponse<byte[]> r = evidence(d.assetRef(), who);
            assertThat(r.statusCode()).as(who + ": " + text(r)).isEqualTo(200);
            assertThat(r.body()).isEqualTo(stored);
            assertThat(r.headers().firstValue("Cache-Control").orElseThrow()).contains("private").contains("no-store")
                    .doesNotContain("public");
            assertThat(r.headers().firstValue("Content-Type")).contains("image/png");
        }
    }

    // ── T5-2 (F6; DD-01, DD-12): el 403 uniforme; sin JWT, 401 ──

    @Test
    void t5_2_anotherOrganizationOrAnAccountWithoutOne_theSame403_andWithoutJwt401() throws Exception {
        Delivered d = deliveredAsset(2);

        HttpResponse<byte[]> fromAnotherOrganization = evidence(d.assetRef(), OTHER_EMPLOYEE);
        HttpResponse<byte[]> loner = evidence(d.assetRef(), LONER);
        HttpResponse<byte[]> unknownAsset = evidence(UUID.randomUUID().toString(), EMPLOYEE);

        assertThat(fromAnotherOrganization.statusCode()).isEqualTo(403);
        assertThat(text(loner)).isEqualTo(text(fromAnotherOrganization));
        assertThat(text(unknownAsset)).isEqualTo(text(fromAnotherOrganization));
        assertThat(evidence(d.assetRef(), null).statusCode()).isEqualTo(401);
        verify(storage, never()).get(anyString());
    }

    // ── T5-3: sin entrega, o entregado con texto libre heredado → 404 ──

    @Test
    void t5_3_anAssetWithoutDelivery_orDeliveredWithLegacyFreeText_is404() throws Exception {
        String notDelivered = receivedAsset();
        Delivered legacy = deliveredAsset(3);
        // Un evento antiguo, de antes de la Tarea 4: el evidenceRef era texto libre
        mongoTemplate.getCollection("event_store").updateOne(
                new Document("streamId", legacy.assetRef()).append("eventType", "ASSET_DELIVERED"),
                new Document("$set", new Document("payload.evidenceRef", "acta-1")));

        HttpResponse<byte[]> withoutDelivery = evidence(notDelivered, EMPLOYEE);
        HttpResponse<byte[]> withFreeText = evidence(legacy.assetRef(), EMPLOYEE);

        assertThat(withoutDelivery.statusCode()).isEqualTo(404);
        assertThat(withFreeText.statusCode()).isEqualTo(404);
        assertThat(text(withFreeText)).isEqualTo(text(withoutDelivery)).doesNotContain("acta-1");
    }

    // ── T5-4 (criterio 14; F7): tras la retirada, 410; los bytes fuera; el metadato con motivo, autor y fecha ──

    @Test
    void t5_4_afterThePlatformRemovesIt_theReadIs410_theBytesAreGone_andTheMetadataKeepsTheTrace() throws Exception {
        Delivered d = deliveredAsset(4);
        String key = media(d.evidenceRef()).getString("objectKey");

        HttpResponse<byte[]> r = remove(PLATFORM, ORG, removal("DELIVERY_EVIDENCE", d.evidenceRef(), REASON));

        assertThat(r.statusCode()).as(text(r)).isEqualTo(204);
        assertThat(storage.get(key)).isEmpty();
        Document meta = media(d.evidenceRef());
        assertThat(meta.get("deletedAt")).isNotNull();
        assertThat(meta.getString("deletedBy")).isEqualTo(PLATFORM);
        assertThat(meta.getString("reason")).isEqualTo(REASON);
        HttpResponse<byte[]> read = evidence(d.assetRef(), EMPLOYEE);
        assertThat(read.statusCode()).isEqualTo(410);
        assertThat(json.readTree(read.body()).get("title").asText()).isEqualTo("EvidenceRemoved");
        // P-T5-3: retirar algo ya retirado → 204, sin volver a tocar el proveedor
        Mockito.reset(storage);
        assertThat(remove(PLATFORM, ORG, removal("DELIVERY_EVIDENCE", d.evidenceRef(), REASON)).statusCode()).isEqualTo(204);
        verify(storage, never()).delete(anyString());
    }

    // ── T5-5 (F7): solo la plataforma retira; el resto, el 403 uniforme y nada cambia ──

    @Test
    void t5_5_theRemovalByAnyoneElse_isThe403_andNothingChanges() throws Exception {
        Delivered d = deliveredAsset(5);
        String key = media(d.evidenceRef()).getString("objectKey");

        HttpResponse<byte[]> asEmployee = remove(EMPLOYEE, ORG, removal("DELIVERY_EVIDENCE", d.evidenceRef(), REASON));
        for (HttpResponse<byte[]> r : List.of(asEmployee,
                remove(REPRESENTATIVE, ORG, removal("DELIVERY_EVIDENCE", d.evidenceRef(), REASON)),
                remove(LONER, ORG, removal("DELIVERY_EVIDENCE", d.evidenceRef(), REASON)),
                // autoriza antes de validar: un cuerpo inválido de quien no es de la plataforma también es 403
                remove(EMPLOYEE, ORG, "{}"))) {
            assertThat(r.statusCode()).isEqualTo(403);
            assertThat(text(r)).isEqualTo(text(asEmployee));
        }
        verify(storage, never()).delete(anyString());
        assertThat(media(d.evidenceRef()).get("deletedAt")).isNull();
        assertThat(storage.get(key)).isPresent();
        assertThat(remove(null, ORG, removal("DELIVERY_EVIDENCE", d.evidenceRef(), REASON)).statusCode()).isEqualTo(401);
    }

    // ── P-T5-2: el cuerpo de la retirada ──

    @Test
    void t5_theRemovalBody_isValidated_andAnUnknownEvidenceIs404() throws Exception {
        Delivered d = deliveredAsset(6);

        assertThat(remove(PLATFORM, ORG, removal(null, d.evidenceRef(), REASON)).statusCode()).isEqualTo(400);
        assertThat(remove(PLATFORM, ORG, removal("SELFIE", d.evidenceRef(), REASON)).statusCode()).isEqualTo(400);
        assertThat(remove(PLATFORM, ORG, removal("DELIVERY_EVIDENCE", null, REASON)).statusCode()).isEqualTo(400);
        assertThat(remove(PLATFORM, ORG, removal("DELIVERY_EVIDENCE", "acta-1", REASON)).statusCode()).isEqualTo(400);
        assertThat(remove(PLATFORM, ORG, removal("DELIVERY_EVIDENCE", d.evidenceRef(), null)).statusCode()).isEqualTo(400);
        assertThat(remove(PLATFORM, ORG, removal("DELIVERY_EVIDENCE", d.evidenceRef(), "   ")).statusCode()).isEqualTo(400);
        assertThat(remove(PLATFORM, ORG, removal("DELIVERY_EVIDENCE", d.evidenceRef(), "x".repeat(501))).statusCode())
                .isEqualTo(400);
        // de otra organización o inexistente → 404 (solo lo ve la plataforma)
        assertThat(remove(PLATFORM, OTHER_ORG, removal("DELIVERY_EVIDENCE", d.evidenceRef(), REASON)).statusCode())
                .isEqualTo(404);
        assertThat(remove(PLATFORM, ORG, removal("DELIVERY_EVIDENCE", "sha256:" + "0".repeat(64), REASON)).statusCode())
                .isEqualTo(404);
        assertThat(media(d.evidenceRef()).get("deletedAt")).isNull();
    }

    // ── T5-8: el motivo no aparece en los logs ni en ninguna respuesta ──

    @Test
    void t5_8_theReason_appearsInNoLogNorResponse(CapturedOutput output) throws Exception {
        Delivered d = deliveredAsset(7);

        HttpResponse<byte[]> removed = remove(PLATFORM, ORG, removal("DELIVERY_EVIDENCE", d.evidenceRef(), REASON));
        HttpResponse<byte[]> read = evidence(d.assetRef(), EMPLOYEE);

        assertThat(removed.statusCode()).isEqualTo(204);
        assertThat(text(removed)).doesNotContain("motivo-secreto-t5");
        assertThat(text(read)).doesNotContain("motivo-secreto-t5");
        assertThat(output.getAll()).doesNotContain("motivo-secreto-t5");
    }

    // ── Deuda 1 (plan de las deudas 1 a 3 §1; P-DB1-1): volver a subir los bytes de una evidencia retirada → 410 ──

    @Test
    void db1_reuploadingTheBytesOfARemovedEvidence_is410_andStoresNothing() throws Exception {
        Delivered d = deliveredAsset(8);
        assertThat(remove(PLATFORM, ORG, removal("DELIVERY_EVIDENCE", d.evidenceRef(), REASON)).statusCode()).isEqualTo(204);
        long mediaBefore = mongoTemplate.getCollection("media_objects").countDocuments();
        Set<String> keysBefore = Set.copyOf(storage.keys());
        Mockito.reset(storage);

        HttpResponse<String> again = upload(8);

        assertThat(again.statusCode()).as(again.body()).isEqualTo(410);
        assertThat(json.readValue(again.body(), Map.class)).containsExactlyInAnyOrderEntriesOf(Map.of(
                "type", "about:blank", "title", "EvidenceRemoved", "status", 410, "detail", "The resource was removed."));
        assertThat(mongoTemplate.getCollection("media_objects").countDocuments()).isEqualTo(mediaBefore);
        assertThat(storage.keys()).containsExactlyInAnyOrderElementsOf(keysBefore);
        verify(storage, never()).put(anyString(), any(), anyString());
        // control: otra foto de la misma organización sigue siendo un 201
        assertThat(upload(9).statusCode()).isEqualTo(201);
    }
}
