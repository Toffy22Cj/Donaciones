package com.traceability.app.web.asset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.app.TraceabilityApplication;
import com.traceability.app.application.media.EvidenceRedactor;
import com.traceability.app.application.media.MediaObjectStore;
import com.traceability.app.application.media.TestImages;
import com.traceability.app.infrastructure.media.InMemoryMediaStorageAdapter;
import com.traceability.contracts.authorization.AuthorizationPrincipal;
import com.traceability.contracts.authorization.AuthorizationRole;
import com.traceability.contracts.authorization.IdentityPrincipalPort;
import com.traceability.contracts.authorization.PlatformAuthority;
import com.traceability.contracts.media.MediaStorageUnavailableException;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ADR-051 Enmienda 2, Tarea 3 (plan §3, grupos B, C y D) contra Tomcat y Mongo reales: la copia censurada de la foto de
 * entrega (generación bajo demanda y concurrencia), su publicación solo por el {@code ADMINISTRATOR} con el historial que
 * solo crece, y la retirada F7 de la entrega que anula la publicación y borra la copia en la misma operación.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = TraceabilityApplication.class)
@Testcontainers
@Import(RedactedEvidenceHttpIntegrationTest.InMemoryStorage.class)
@TestPropertySource(properties = {
    "crypto.anchor.poll.delay=9999999",
    "crypto.anchor.submit.delay=9999999",
    "crypto.anchor.stuck-monitor.delay=9999999",
    "crypto.web3j.private-key=0x1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef",
    "crypto.web3j.node-url=http://dummy-node",
    "spring.ai.openai.api-key=dummy-api-key"
})
class RedactedEvidenceHttpIntegrationTest {

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

    private static final String ORG = "org-t3";
    private static final String OTHER_ORG = "org-t3-other";
    private static final String EMPLOYEE = "acc-t3-employee";
    private static final String ADMIN = "acc-t3-admin";
    private static final String OTHER_ADMIN = "acc-t3-other-admin";
    private static final String REPRESENTATIVE = "acc-t3-representative";
    private static final String OTHER_EMPLOYEE = "acc-t3-other-employee";
    private static final String LONER = "acc-t3-loner";
    private static final String PLATFORM = "acc-t3-platform";
    private static final String ASSETS = "/api/v1/physical-assets";
    private static final String BOUNDARY = "t3-redacted-boundary-3f7c";
    private static final String REASON = "Aparece una persona en la foto — motivo-secreto-t3";
    private static final AtomicInteger SEEDS = new AtomicInteger(5000);

    @LocalServerPort private int port;
    @Autowired private com.traceability.contracts.authentication.TokenIssuerPort tokens;
    @Autowired private MongoTemplate mongoTemplate;
    @Autowired private InMemoryMediaStorageAdapter storage;
    @MockitoSpyBean private MediaObjectStore metadataSpy;
    @MockitoSpyBean private EvidenceRedactor redactorSpy;
    @MockitoBean private IdentityPrincipalPort identityPrincipalPort;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void principals() {
        Map<String, AuthorizationPrincipal> principals = Map.of(
                EMPLOYEE, new AuthorizationPrincipal(EMPLOYEE, ORG, Set.of(AuthorizationRole.EMPLOYEE), null),
                ADMIN, new AuthorizationPrincipal(ADMIN, ORG, Set.of(AuthorizationRole.ADMINISTRATOR, AuthorizationRole.EMPLOYEE), null),
                OTHER_ADMIN, new AuthorizationPrincipal(OTHER_ADMIN, OTHER_ORG, Set.of(AuthorizationRole.ADMINISTRATOR), null),
                REPRESENTATIVE, new AuthorizationPrincipal(REPRESENTATIVE, ORG, Set.of(AuthorizationRole.REPRESENTATIVE), null),
                OTHER_EMPLOYEE, new AuthorizationPrincipal(OTHER_EMPLOYEE, OTHER_ORG, Set.of(AuthorizationRole.EMPLOYEE), null),
                LONER, new AuthorizationPrincipal(LONER, null, Set.of(), null),
                PLATFORM, new AuthorizationPrincipal(PLATFORM, null, Set.of(), PlatformAuthority.ADMINISTRATOR));
        when(identityPrincipalPort.resolvePrincipal(anyString())).thenAnswer(inv -> principals.get(inv.<String>getArgument(0)));
        when(identityPrincipalPort.resolveAuthenticatedPrincipal(anyString(), any()))
                .thenAnswer(inv -> principals.get(inv.<String>getArgument(0)));
        Mockito.reset(storage, metadataSpy, redactorSpy);
    }

    // ── HTTP ──

    private HttpResponse<byte[]> send(String method, String path, String account, String commandId, String body)
            throws Exception {
        HttpRequest.Builder r = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(30))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (account != null) r.header("Authorization", "Bearer " + tokens.issue(account));
        if (commandId != null) r.header("Command-Id", commandId);
        if (body != null) r.header("Content-Type", "application/json");
        return http.send(r.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private static String text(HttpResponse<byte[]> r) {
        return new String(r.body(), StandardCharsets.UTF_8);
    }

    private static String newId() {
        return UUID.randomUUID().toString();
    }

    private String deliveryPhoto() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"e.png\""
                + "\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.writeBytes(TestImages.png(40 + SEEDS.getAndIncrement(), 30));
        out.writeBytes(("\r\n--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8));
        HttpResponse<byte[]> r = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                        + "/api/v1/organizations/" + ORG + "/delivery-evidence")).timeout(Duration.ofSeconds(20))
                .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray()))
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .header("Authorization", "Bearer " + tokens.issue(EMPLOYEE)).build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(r.statusCode()).as(text(r)).isEqualTo(201);
        return json.readTree(r.body()).get("evidenceRef").asText();
    }

    /** Un lote entregado con su foto de entrega; devuelve el activo. */
    private String delivered() throws Exception {
        HttpResponse<byte[]> r = send("POST", ASSETS + "/from-donation", EMPLOYEE, newId(),
                "{\"assetType\":\"FOOD\",\"quantity\":\"10\",\"unitOfMeasure\":\"KGS\",\"custodianRef\":\"CUST-1\",\"currentLocation\":\"WH-1\",\"municipalityCode\":\"05001\"}");
        assertThat(r.statusCode()).as(text(r)).isEqualTo(201);
        String assetRef = json.readTree(r.body()).get("assetRef").asText();
        assertThat(send("POST", ASSETS + "/" + assetRef + "/dispatch", EMPLOYEE, newId(), "{\"carrierRef\":\"C-1\"}").statusCode()).isEqualTo(200);
        assertThat(send("POST", ASSETS + "/" + assetRef + "/receive", EMPLOYEE, newId(),
                "{\"facilityLocation\":\"WH-2\",\"municipalityCode\":\"05001\",\"receiverRef\":\"REC-1\"}").statusCode()).isEqualTo(200);
        String evidence = deliveryPhoto();
        assertThat(send("POST", ASSETS + "/" + assetRef + "/deliver", EMPLOYEE, newId(),
                "{\"finalCustodianRef\":\"CUST-F\",\"beneficiaryRef\":\"BEN-1\",\"locationRef\":\"SITE-1\",\"municipalityCode\":\"05001\",\"evidenceRef\":\""
                        + evidence + "\"}").statusCode()).isEqualTo(200);
        evidenceByAsset.put(assetRef, evidence);
        return assetRef;
    }

    private final Map<String, String> evidenceByAsset = new java.util.HashMap<>();

    private Document original(String assetRef) {
        String ref = evidenceByAsset.get(assetRef);
        return mongoTemplate.getCollection("media_objects").find(new Document("sha256", ref.substring(7))
                .append("purpose", "DELIVERY_EVIDENCE").append("organizationId", ORG)).first();
    }

    private Document copyOf(String assetRef) {
        return mongoTemplate.getCollection("media_objects").find(new Document("purpose", "DELIVERY_EVIDENCE_REDACTED")
                .append("organizationId", ORG).append("ownerRef", original(assetRef).getString("objectKey"))).first();
    }

    private long copiesOf(String assetRef) {
        return mongoTemplate.getCollection("media_objects").countDocuments(new Document("purpose", "DELIVERY_EVIDENCE_REDACTED")
                .append("ownerRef", original(assetRef).getString("objectKey")));
    }

    private HttpResponse<byte[]> redacted(String assetRef, String account) throws Exception {
        return send("GET", ASSETS + "/" + assetRef + "/evidence/redacted", account, null, null);
    }

    private HttpResponse<byte[]> publish(String assetRef, String account, String body) throws Exception {
        return send("PUT", ASSETS + "/" + assetRef + "/evidence/redacted/publication", account, null, body);
    }

    private static final String CONFIRMED = "{\"confirmNoIdentifiablePerson\":true}";

    private HttpResponse<byte[]> unpublish(String assetRef, String account) throws Exception {
        return send("DELETE", ASSETS + "/" + assetRef + "/evidence/redacted/publication", account, null, null);
    }

    private JsonNode publication(String assetRef, String account) throws Exception {
        HttpResponse<byte[]> r = send("GET", ASSETS + "/" + assetRef + "/evidence/redacted/publication", account, null, null);
        assertThat(r.statusCode()).as(text(r)).isEqualTo(200);
        return json.readTree(r.body());
    }

    private HttpResponse<byte[]> removeDelivery(String assetRef) throws Exception {
        return send("POST", "/api/v1/platform/organizations/" + ORG + "/media/remove", PLATFORM, null,
                "{\"purpose\":\"DELIVERY_EVIDENCE\",\"ref\":\"" + evidenceByAsset.get(assetRef) + "\",\"reason\":\"" + REASON + "\"}");
    }

    private List<String> actions(JsonNode publication) {
        List<String> out = new ArrayList<>();
        publication.get("decisions").forEach(d -> out.add(d.get("action").asText()));
        return out;
    }

    // ── B. Generación y concurrencia ──

    // test 6: la primera lectura genera y guarda; la segunda devuelve lo mismo sin generar otra vez
    @Test
    void b6_theFirstReadGenerates_theSecondDoesNotGenerateAgain() throws Exception {
        String asset = delivered();
        Mockito.reset(storage);

        HttpResponse<byte[]> first = redacted(asset, EMPLOYEE);
        HttpResponse<byte[]> second = redacted(asset, EMPLOYEE);

        assertThat(first.statusCode()).as(text(first)).isEqualTo(200);
        assertThat(first.headers().firstValue("Content-Type").orElse("")).isEqualTo("image/png");
        assertThat(first.headers().firstValue("Cache-Control").orElse("")).contains("no-store").contains("private");
        assertThat(second.body()).isEqualTo(first.body());
        verify(storage, times(1)).put(anyString(), any(), anyString()); // aserción: una sola generación
        Document copy = copyOf(asset);
        assertThat(copy.getString("sha256")).isNotEqualTo(original(asset).getString("sha256")); // otra huella: otro objeto
        assertThat(copy.getString("contentType")).isEqualTo("image/png");
        assertThat(copy.get("boundAssetId")).isNull();
        assertThat(evidenceByAsset.get(asset)).isEqualTo("sha256:" + original(asset).getString("sha256")); // el evento no cambia
    }

    // test 7c (carrera real): lecturas simultáneas sin copia previa → todas 200 con los mismos bytes y UNA sola copia
    @Test
    void b7_simultaneousReads_allAre200_withTheSameBytes_andOneCopy() throws Exception {
        String asset = delivered();
        int n = 6;
        CyclicBarrier barrier = new CyclicBarrier(n);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<HttpResponse<byte[]>>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> {
                    barrier.await();
                    return redacted(asset, EMPLOYEE);
                }));
            }
            byte[] bytes = null;
            for (Future<HttpResponse<byte[]>> f : futures) {
                HttpResponse<byte[]> r = f.get();
                assertThat(r.statusCode()).as(text(r)).isEqualTo(200); // aserción negativa: ni 500 ni 409
                if (bytes == null) bytes = r.body();
                assertThat(r.body()).isEqualTo(bytes);
            }
            assertThat(copiesOf(asset)).isEqualTo(1);
            // sin archivos huérfanos: el original y la copia, y nada más de este lote
            assertThat(storage.keys()).contains(original(asset).getString("objectKey"), copyOf(asset).getString("objectKey"));
        } finally {
            pool.shutdownNow();
        }
    }

    // test 7c (determinista): quien pierde la carrera —su búsqueda no vio la copia ganadora— choca con el índice, borra su
    // archivo y devuelve la ganadora
    @Test
    void b7b_theLoserOfTheRace_collidesWithTheIndex_deletesItsFile_andReturnsTheWinner() throws Exception {
        String asset = delivered();
        byte[] winner = redacted(asset, EMPLOYEE).body();
        Set<String> keysBefore = Set.copyOf(storage.keys());
        Mockito.reset(storage);
        // el perdedor no ve la copia en su primera búsqueda (como si la ganadora aún no se hubiera guardado)
        doReturn(Optional.empty()).doCallRealMethod().when(metadataSpy)
                .findByOrganizationIdAndPurposeAndOwnerRef(anyString(), any(), anyString());

        HttpResponse<byte[]> loser = redacted(asset, EMPLOYEE);

        assertThat(loser.statusCode()).as(text(loser)).isEqualTo(200); // nunca 500 ni 409
        assertThat(loser.body()).isEqualTo(winner);
        assertThat(copiesOf(asset)).isEqualTo(1);
        verify(storage, times(1)).put(anyString(), any(), anyString()); // generó la suya…
        verify(storage, times(1)).delete(anyString());                   // …y la borró
        assertThat(storage.keys()).containsExactlyInAnyOrderElementsOf(keysBefore); // sin huérfanos
    }

    // test 8: con bytes DISTINTOS en cada generación, sigue habiendo una sola copia (índice por ownerRef, no por huella)
    @Test
    void b8_withDifferentBytesInEachGeneration_stillOneCopy_becauseOfTheOwnerRefIndex() throws Exception {
        String asset = delivered();
        doAnswer(inv -> {
            EvidenceRedactor.Redacted real = (EvidenceRedactor.Redacted) inv.callRealMethod();
            byte[] noisy = real.png().clone();
            noisy[noisy.length - 20] ^= (byte) (SEEDS.incrementAndGet() & 0x7F | 1);
            return new EvidenceRedactor.Redacted(noisy, real.width(), real.height());
        }).when(redactorSpy).redact(any());
        byte[] winner = redacted(asset, EMPLOYEE).body();
        doReturn(Optional.empty()).doCallRealMethod().when(metadataSpy)
                .findByOrganizationIdAndPurposeAndOwnerRef(anyString(), any(), anyString());

        HttpResponse<byte[]> loser = redacted(asset, EMPLOYEE);

        assertThat(loser.statusCode()).as(text(loser)).isEqualTo(200);
        assertThat(loser.body()).isEqualTo(winner); // la ganadora, no los bytes distintos del perdedor
        assertThat(copiesOf(asset)).isEqualTo(1);
    }

    // test 9: sin foto de entrega → 404; retirada por F7 → 410
    @Test
    void b9_withoutADeliveryPhoto_is404_andAfterTheRemoval_is410() throws Exception {
        HttpResponse<byte[]> created = send("POST", ASSETS + "/from-donation", EMPLOYEE, newId(),
                "{\"assetType\":\"FOOD\",\"quantity\":\"10\",\"unitOfMeasure\":\"KGS\",\"custodianRef\":\"CUST-1\",\"currentLocation\":\"WH-1\",\"municipalityCode\":\"05001\"}");
        String notDelivered = json.readTree(created.body()).get("assetRef").asText();
        assertThat(redacted(notDelivered, EMPLOYEE).statusCode()).isEqualTo(404);

        String asset = delivered();
        assertThat(redacted(asset, EMPLOYEE).statusCode()).isEqualTo(200);
        assertThat(removeDelivery(asset).statusCode()).isEqualTo(204);
        HttpResponse<byte[]> gone = redacted(asset, EMPLOYEE);
        assertThat(gone.statusCode()).isEqualTo(410);
        assertThat(json.readTree(gone.body()).get("title").asText()).isEqualTo("EvidenceRemoved");
    }

    // test 10: 403 uniforme a quien no es de la organización; los miembros de cualquier rol y la plataforma la leen
    @Test
    void b10_nonMembersGetTheSame403_membersOfAnyRoleAndPlatformRead() throws Exception {
        String asset = delivered();
        HttpResponse<byte[]> other = redacted(asset, OTHER_EMPLOYEE);
        HttpResponse<byte[]> loner = redacted(asset, LONER);
        assertThat(other.statusCode()).isEqualTo(403);
        assertThat(text(other)).isEqualTo(text(loner));
        assertThat(redacted(asset, null).statusCode()).isEqualTo(401);
        for (String account : List.of(EMPLOYEE, REPRESENTATIVE, ADMIN, PLATFORM)) {
            assertThat(redacted(asset, account).statusCode()).as(account).isEqualTo(200);
        }
        assertThat(copiesOf(asset)).isEqualTo(1);
    }

    // ── C. Publicación ──

    // test 10 (E2-5): solo el ADMINISTRATOR de la organización publica, con la confirmación explícita
    @Test
    void c11_onlyTheOrganizationAdministratorPublishes_withTheExplicitConfirmation() throws Exception {
        String asset = delivered();
        redacted(asset, ADMIN);
        for (String account : List.of(EMPLOYEE, REPRESENTATIVE, OTHER_ADMIN, PLATFORM, LONER)) {
            assertThat(publish(asset, account, CONFIRMED).statusCode()).as(account).isEqualTo(403);
            assertThat(unpublish(asset, account).statusCode()).as("despublicar " + account).isEqualTo(403);
            // un cuerpo inválido de quien no puede es 403, no 400 (no revela nada)
            assertThat(publish(asset, account, "{}").statusCode()).as(account + " sin confirmación").isEqualTo(403);
        }
        assertThat(publish(asset, null, CONFIRMED).statusCode()).isEqualTo(401);
        // aserción negativa: ni una entrada en el historial
        assertThat(publication(asset, ADMIN).get("decisions")).isEmpty();

        for (String body : new String[] {"{}", "{\"confirmNoIdentifiablePerson\":false}", "{\"confirmNoIdentifiablePerson\":\"si\"}", "no es json"}) {
            assertThat(publish(asset, ADMIN, body).statusCode()).as(body).isEqualTo(400);
        }
        assertThat(publication(asset, ADMIN).get("decisions")).isEmpty();
        assertThat(publish(asset, ADMIN, CONFIRMED).statusCode()).isEqualTo(204);
        assertThat(publication(asset, ADMIN).get("published").asBoolean()).isTrue();
    }

    // test 10b: publicar, despublicar y volver a publicar deja tres entradas; las repetidas no añaden
    @Test
    void c12_theHistoryOnlyGrows_threeDecisions_andRepeatsAddNothing() throws Exception {
        String asset = delivered();
        redacted(asset, ADMIN);

        assertThat(publish(asset, ADMIN, CONFIRMED).statusCode()).isEqualTo(204);
        JsonNode afterFirst = publication(asset, ADMIN);
        JsonNode first = afterFirst.get("decisions").get(0);
        assertThat(publish(asset, ADMIN, CONFIRMED).statusCode()).isEqualTo(204); // ya publicada: sin entrada
        assertThat(publication(asset, ADMIN).get("decisions")).hasSize(1);
        assertThat(unpublish(asset, ADMIN).statusCode()).isEqualTo(204);
        assertThat(unpublish(asset, ADMIN).statusCode()).isEqualTo(204);          // ya despublicada: sin entrada
        assertThat(publish(asset, ADMIN, CONFIRMED).statusCode()).isEqualTo(204);

        JsonNode now = publication(asset, ADMIN);
        assertThat(actions(now)).containsExactly("PUBLISH", "UNPUBLISH", "PUBLISH");
        assertThat(now.get("decisions").get(0)).isEqualTo(first); // la primera conserva su at, su by y su confirmación
        assertThat(first.get("by").asText()).isEqualTo(ADMIN);
        assertThat(first.get("confirmNoIdentifiablePerson").asBoolean()).isTrue();
        assertThat(now.get("decisions").get(1).get("confirmNoIdentifiablePerson").isNull()).isTrue();
        assertThat(now.get("published").asBoolean()).isTrue();
        // `published` coincide con la última entrada
        assertThat(copyOf(asset).getBoolean("published")).isTrue();
        assertThat(unpublish(asset, ADMIN).statusCode()).isEqualTo(204);
        assertThat(copyOf(asset).getBoolean("published")).isFalse();
    }

    // Q-T3-8: la lectura del historial es solo del ADMINISTRATOR, sin bytes
    @Test
    void c12b_theHistoryRead_isForTheAdministratorOnly_andCarriesNoBytes() throws Exception {
        String asset = delivered();
        redacted(asset, ADMIN);
        publish(asset, ADMIN, CONFIRMED);

        HttpResponse<byte[]> r = send("GET", ASSETS + "/" + asset + "/evidence/redacted/publication", ADMIN, null, null);
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.headers().firstValue("Content-Type").orElse("")).contains("json");
        assertThat(r.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        List<String> shape = new ArrayList<>();
        json.readTree(r.body()).get("decisions").get(0).fieldNames().forEachRemaining(shape::add);
        assertThat(shape).containsExactly("action", "at", "by", "confirmNoIdentifiablePerson");
        for (String account : List.of(EMPLOYEE, REPRESENTATIVE, OTHER_ADMIN, PLATFORM, LONER)) {
            HttpResponse<byte[]> denied = send("GET", ASSETS + "/" + asset + "/evidence/redacted/publication", account, null, null);
            assertThat(denied.statusCode()).as(account).isEqualTo(403);
            assertThat(text(denied)).doesNotContain(ADMIN); // aserción negativa: ningún accountId
        }
        assertThat(send("GET", ASSETS + "/" + asset + "/evidence/redacted/publication", null, null, null).statusCode()).isEqualTo(401);
        // sin copia generada: no publicada y sin decisiones
        String other = delivered();
        JsonNode empty = publication(other, ADMIN);
        assertThat(empty.get("published").asBoolean()).isFalse();
        assertThat(empty.get("decisions")).isEmpty();
    }

    // dos PUT simultáneos → una sola entrada PUBLISH (la escritura condicional)
    @Test
    void c13_twoSimultaneousPublishes_leaveOneEntry() throws Exception {
        String asset = delivered();
        redacted(asset, ADMIN);
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<HttpResponse<byte[]>>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    barrier.await();
                    return publish(asset, ADMIN, CONFIRMED);
                }));
            }
            for (Future<HttpResponse<byte[]>> f : futures) assertThat(f.get().statusCode()).isEqualTo(204);
        } finally {
            pool.shutdownNow();
        }
        assertThat(actions(publication(asset, ADMIN))).containsExactly("PUBLISH");
    }

    // copia sin generar → 409 RedactedCopyNotGenerated; retirada → 410
    @Test
    void c16_publishingWithoutAGeneratedCopy_is409_andARemovedPhotoIs410() throws Exception {
        String asset = delivered();
        HttpResponse<byte[]> notGenerated = publish(asset, ADMIN, CONFIRMED);
        assertThat(notGenerated.statusCode()).isEqualTo(409);
        assertThat(json.readTree(notGenerated.body()).get("title").asText()).isEqualTo("RedactedCopyNotGenerated");
        assertThat(copiesOf(asset)).isZero(); // aserción negativa: el PUT no la generó

        redacted(asset, ADMIN);
        assertThat(removeDelivery(asset).statusCode()).isEqualTo(204);
        HttpResponse<byte[]> gone = publish(asset, ADMIN, CONFIRMED);
        assertThat(gone.statusCode()).isEqualTo(410);
        assertThat(json.readTree(gone.body()).get("title").asText()).isEqualTo("EvidenceRemoved");
    }

    // la copia no sale por ninguna ruta pública (la del seguimiento es la Tarea 4, bloqueada)
    @Test
    void c9_theCopyIsNeverServedByAPublicRoute() throws Exception {
        String asset = delivered();
        redacted(asset, ADMIN);
        publish(asset, ADMIN, CONFIRMED);
        for (String path : List.of("/api/v1/donations/tracking/assets/" + asset + "/evidence-preview",
                "/api/v1/public/assets/" + asset + "/evidence-preview", ASSETS + "/" + asset + "/evidence/redacted")) {
            HttpResponse<byte[]> r = send("GET", path, null, null, null);
            assertThat(r.statusCode()).as(path).isIn(401, 403, 404);
            assertThat(r.headers().firstValue("Content-Type").orElse("")).as(path).doesNotStartWith("image/");
        }
    }

    // ── D. Retirada F7 de la entrega con su copia ──

    // test 11 (DoD): publicada y retirada → sin bytes los dos, una entrada UNPUBLISH de la plataforma, sin publicación vigente
    @Test
    void d17_aPublishedCopy_removedWithItsOriginal_isUnpublishedByThePlatform_andBothLoseTheirBytes() throws Exception {
        String asset = delivered();
        byte[] copyBytes = redacted(asset, ADMIN).body();
        assertThat(publish(asset, ADMIN, CONFIRMED).statusCode()).isEqualTo(204);
        String originalKey = original(asset).getString("objectKey");
        String copyKey = copyOf(asset).getString("objectKey");
        Mockito.reset(storage);

        HttpResponse<byte[]> r = removeDelivery(asset);

        assertThat(r.statusCode()).as(text(r)).isEqualTo(204);
        assertThat(text(r)).doesNotContain("motivo-secreto-t3");
        verify(storage, times(1)).delete(originalKey);
        verify(storage, times(1)).delete(copyKey);
        assertThat(storage.get(originalKey)).isEmpty();
        assertThat(storage.get(copyKey)).isEmpty();
        for (Document doc : List.of(original(asset), copyOf(asset))) {
            assertThat(doc.get("deletedAt")).isNotNull();
            assertThat(doc.getString("deletedBy")).isEqualTo(PLATFORM);
            assertThat(doc.getString("reason")).isEqualTo(REASON);
        }
        JsonNode state = publication(asset, ADMIN);
        assertThat(actions(state)).containsExactly("PUBLISH", "UNPUBLISH");
        assertThat(state.get("decisions").get(1).get("by").asText()).isEqualTo(PLATFORM);
        assertThat(state.get("published").asBoolean()).isFalse(); // el estado «vigente» es falso
        assertThat(copyBytes).isNotEmpty();
    }

    // sin copia: como antes; copia sin publicar: se borra sin entrada UNPUBLISH
    @Test
    void d18_withoutACopy_asBefore_andAnUnpublishedCopyIsDeletedWithoutAnUnpublishEntry() throws Exception {
        String noCopy = delivered();
        Mockito.reset(storage);
        assertThat(removeDelivery(noCopy).statusCode()).isEqualTo(204);
        verify(storage, times(1)).delete(anyString()); // solo la original
        assertThat(copiesOf(noCopy)).isZero();

        String unpublished = delivered();
        redacted(unpublished, ADMIN);
        Mockito.reset(storage);
        assertThat(removeDelivery(unpublished).statusCode()).isEqualTo(204);
        verify(storage, times(2)).delete(anyString());
        assertThat(copyOf(unpublished).get("deletedAt")).isNotNull();
        assertThat(publication(unpublished, ADMIN).get("decisions")).isEmpty(); // aserción negativa: sin UNPUBLISH
    }

    // idempotente: repetir → 204, sin segundo borrado ni segunda entrada
    @Test
    void d19_repeatingTheRemoval_is204_withoutASecondDeleteNorASecondUnpublish() throws Exception {
        String asset = delivered();
        redacted(asset, ADMIN);
        publish(asset, ADMIN, CONFIRMED);
        assertThat(removeDelivery(asset).statusCode()).isEqualTo(204);
        Mockito.reset(storage);

        assertThat(removeDelivery(asset).statusCode()).isEqualTo(204);

        verify(storage, never()).delete(anyString());
        assertThat(actions(publication(asset, ADMIN))).containsExactly("PUBLISH", "UNPUBLISH");
    }

    // fallo a mitad (a): el proveedor falla al borrar la COPIA → 503; la publicación ya quedó anulada; reintentar termina
    @Test
    void d20a_aFailureDeletingTheCopy_is503_thePublicationIsAlreadyUnpublished_andTheRetryFinishes() throws Exception {
        String asset = delivered();
        redacted(asset, ADMIN);
        publish(asset, ADMIN, CONFIRMED);
        String copyKey = copyOf(asset).getString("objectKey");
        doThrow(new MediaStorageUnavailableException("provider down")).when(storage).delete(copyKey);

        assertThat(removeDelivery(asset).statusCode()).isEqualTo(503);

        assertThat(actions(publication(asset, ADMIN))).containsExactly("PUBLISH", "UNPUBLISH"); // anulada ANTES de borrar bytes
        assertThat(publication(asset, ADMIN).get("published").asBoolean()).isFalse();
        assertThat(original(asset).get("deletedAt")).isNull(); // el metadato de la original, sin marcar: se puede reintentar
        assertThat(copyOf(asset).get("deletedAt")).isNull();

        Mockito.reset(storage);
        assertThat(removeDelivery(asset).statusCode()).isEqualTo(204);

        assertThat(storage.get(copyKey)).isEmpty();
        assertThat(storage.get(original(asset).getString("objectKey"))).isEmpty();
        assertThat(original(asset).get("deletedAt")).isNotNull();
        assertThat(copyOf(asset).get("deletedAt")).isNotNull();
        assertThat(actions(publication(asset, ADMIN))).containsExactly("PUBLISH", "UNPUBLISH"); // una sola anulación en total
    }

    // fallo a mitad (b): la copia ya se borró y falla la ORIGINAL → 503; reintentar termina y no deja nada a medias
    @Test
    void d20b_aFailureDeletingTheOriginal_isRetriable_andLeavesNothingHalfDone() throws Exception {
        String asset = delivered();
        redacted(asset, ADMIN);
        publish(asset, ADMIN, CONFIRMED);
        String originalKey = original(asset).getString("objectKey");
        String copyKey = copyOf(asset).getString("objectKey");
        doThrow(new MediaStorageUnavailableException("provider down")).when(storage).delete(originalKey);

        assertThat(removeDelivery(asset).statusCode()).isEqualTo(503);

        assertThat(storage.get(copyKey)).isEmpty();                    // la copia ya no tiene bytes
        assertThat(copyOf(asset).get("deletedAt")).isNotNull();        // y está marcada
        assertThat(original(asset).get("deletedAt")).isNull();         // la original, aún no: reintentable
        assertThat(publication(asset, ADMIN).get("published").asBoolean()).isFalse();

        Mockito.reset(storage);
        assertThat(removeDelivery(asset).statusCode()).isEqualTo(204);

        assertThat(storage.get(originalKey)).isEmpty();
        assertThat(original(asset).get("deletedAt")).isNotNull();
        assertThat(actions(publication(asset, ADMIN))).containsExactly("PUBLISH", "UNPUBLISH");
        // y la copia no se vuelve a borrar ni a marcar con otro motivo
        verify(storage, never()).delete(copyKey);
    }
}
