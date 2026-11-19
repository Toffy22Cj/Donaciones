package com.traceability.app.web.asset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.app.TraceabilityApplication;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ADR-051 Enmienda 2, Tarea 2 (plan §3, grupos A a D) contra Tomcat y Mongo reales, con el proveedor en memoria: la
 * subida de las fotos de registrar, despachar y recibir ({@code POST …/asset-evidence}), su lectura por paso
 * ({@code GET …/evidence/{step}}, {@code …/evidence/steps}), los duplicados por propósito y la retirada F7.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = TraceabilityApplication.class)
@Testcontainers
@Import(StepEvidenceUploadReadRemovalHttpIntegrationTest.InMemoryStorage.class)
@TestPropertySource(properties = {
    "crypto.anchor.poll.delay=9999999",
    "crypto.anchor.submit.delay=9999999",
    "crypto.anchor.stuck-monitor.delay=9999999",
    "crypto.web3j.private-key=0x1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef",
    "crypto.web3j.node-url=http://dummy-node",
    "spring.ai.openai.api-key=dummy-api-key"
})
class StepEvidenceUploadReadRemovalHttpIntegrationTest {

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

    private static final String ORG = "org-t2";
    private static final String OTHER_ORG = "org-t2-other";
    private static final String EMPLOYEE = "acc-t2-employee";
    private static final String ADMIN_ONLY = "acc-t2-admin-only";
    private static final String REPRESENTATIVE = "acc-t2-representative";
    private static final String OTHER_EMPLOYEE = "acc-t2-other-employee";
    private static final String LONER = "acc-t2-loner";
    private static final String PLATFORM = "acc-t2-platform";
    private static final String ASSETS = "/api/v1/physical-assets";
    private static final String BOUNDARY = "t2-step-evidence-boundary-6a1d";
    private static final String REASON = "Aparece una persona en la foto — motivo-secreto-t2";
    private static final List<String> STEPS = List.of("REGISTERED", "DISPATCHED", "RECEIVED");
    private static final Map<String, String> PURPOSE = Map.of("REGISTERED", "REGISTRATION_EVIDENCE",
            "DISPATCHED", "DISPATCH_EVIDENCE", "RECEIVED", "RECEIPT_EVIDENCE");

    @LocalServerPort private int port;
    @Autowired private com.traceability.contracts.authentication.TokenIssuerPort tokens;
    @Autowired private MongoTemplate mongoTemplate;
    @Autowired private InMemoryMediaStorageAdapter storage;
    @MockitoBean private IdentityPrincipalPort identityPrincipalPort;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();
    private int seed = 100;

    @BeforeEach
    void principals() {
        Map<String, AuthorizationPrincipal> principals = Map.of(
                EMPLOYEE, new AuthorizationPrincipal(EMPLOYEE, ORG, Set.of(AuthorizationRole.EMPLOYEE), null),
                ADMIN_ONLY, new AuthorizationPrincipal(ADMIN_ONLY, ORG, Set.of(AuthorizationRole.ADMINISTRATOR), null),
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

    private static String newId() {
        return UUID.randomUUID().toString();
    }

    /** Multipart con el campo {@code step} (si se da) y el archivo. */
    private HttpResponse<byte[]> uploadStep(String org, String account, String step, byte[] file) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (step != null) {
            out.writeBytes(("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"step\"\r\n\r\n" + step + "\r\n")
                    .getBytes(StandardCharsets.UTF_8));
        }
        out.writeBytes(("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"foto.png\""
                + "\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.writeBytes(file);
        out.writeBytes(("\r\n--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8));
        HttpRequest.Builder r = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/organizations/" + org
                        + "/asset-evidence")).timeout(Duration.ofSeconds(20))
                .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray()))
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY);
        if (account != null) r.header("Authorization", "Bearer " + tokens.issue(account));
        return http.send(r.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private byte[] image(int s) {
        return TestImages.png(40 + s, 18);
    }

    /** Sube una foto propia (bytes distintos por semilla) a ese paso y devuelve su {@code evidenceRef}. */
    private String photo(String step, int s) throws Exception {
        HttpResponse<byte[]> r = uploadStep(ORG, EMPLOYEE, step, image(s));
        assertThat(r.statusCode()).as(text(r)).isEqualTo(201);
        return json.readTree(r.body()).get("evidenceRef").asText();
    }

    private String freshPhoto(String step) throws Exception {
        return photo(step, seed++);
    }

    record Lot(String assetRef, String registered, String dispatched, String received) {}

    private String jsonWith(String base, String evidenceRef) {
        return evidenceRef == null ? "{" + base + "}" : "{" + base + ",\"evidenceRef\":\"" + evidenceRef + "\"}";
    }

    /** Un lote registrado, despachado y recibido, cada paso con su foto (o sin ella si se da {@code null}). */
    private Lot lot(String registered, String dispatched, String received) throws Exception {
        String base = "\"assetType\":\"FOOD\",\"quantity\":\"10\",\"unitOfMeasure\":\"KGS\",\"custodianRef\":\"CUST-1\","
                + "\"currentLocation\":\"WH-1\",\"municipalityCode\":\"05001\"";
        HttpResponse<byte[]> r = send("POST", ASSETS + "/from-donation", EMPLOYEE, newId(), jsonWith(base, registered));
        assertThat(r.statusCode()).as(text(r)).isEqualTo(201);
        String assetRef = json.readTree(r.body()).get("assetRef").asText();
        dispatch(assetRef, dispatched);
        receive(assetRef, received);
        return new Lot(assetRef, registered, dispatched, received);
    }

    private void dispatch(String assetRef, String evidenceRef) throws Exception {
        HttpResponse<byte[]> r = send("POST", ASSETS + "/" + assetRef + "/dispatch", EMPLOYEE, newId(),
                jsonWith("\"carrierRef\":\"C-1\"", evidenceRef));
        assertThat(r.statusCode()).as(text(r)).isEqualTo(200);
    }

    private void receive(String assetRef, String evidenceRef) throws Exception {
        HttpResponse<byte[]> r = send("POST", ASSETS + "/" + assetRef + "/receive", EMPLOYEE, newId(),
                jsonWith("\"facilityLocation\":\"WH-2\",\"municipalityCode\":\"05001\",\"receiverRef\":\"REC-1\"", evidenceRef));
        assertThat(r.statusCode()).as(text(r)).isEqualTo(200);
    }

    private Lot lotWithPhotos() throws Exception {
        return lot(freshPhoto("REGISTERED"), freshPhoto("DISPATCHED"), freshPhoto("RECEIVED"));
    }

    private HttpResponse<byte[]> read(String assetRef, String step, String account) throws Exception {
        return send("GET", ASSETS + "/" + assetRef + "/evidence/" + step, account, null, null);
    }

    private HttpResponse<byte[]> steps(String assetRef, String account) throws Exception {
        return send("GET", ASSETS + "/" + assetRef + "/evidence/steps", account, null, null);
    }

    private HttpResponse<byte[]> remove(String account, String org, String purpose, String ref, String reason) throws Exception {
        return send("POST", "/api/v1/platform/organizations/" + org + "/media/remove", account, null,
                "{\"purpose\":\"" + purpose + "\",\"ref\":\"" + ref + "\"" + (reason == null ? "" : ",\"reason\":\"" + reason + "\"") + "}");
    }

    private Document media(String evidenceRef, String purpose) {
        return mongoTemplate.getCollection("media_objects").find(new Document("sha256", evidenceRef.substring(7))
                .append("purpose", purpose)).first();
    }

    private long mediaCount() {
        return mongoTemplate.getCollection("media_objects").countDocuments();
    }

    // ── A. Subida ──

    // test 1: cada paso → 201 con la huella de los bytes limpios; el objeto lleva el propósito del paso y sin vincular
    @Test
    void a1_eachStep_is201_withTheSha256OfTheStoredBytes_andThePurposeOfTheStep() throws Exception {
        for (String step : STEPS) {
            HttpResponse<byte[]> r = uploadStep(ORG, EMPLOYEE, step, image(seed++));
            assertThat(r.statusCode()).as(step + " " + text(r)).isEqualTo(201);
            JsonNode body = json.readTree(r.body());
            List<String> fields = new ArrayList<>();
            body.fieldNames().forEachRemaining(fields::add);
            assertThat(fields).containsExactly("evidenceRef");
            String ref = body.get("evidenceRef").asText();
            assertThat(ref).matches("^sha256:[0-9a-f]{64}$");
            Document doc = media(ref, PURPOSE.get(step));
            assertThat(doc).as(step).isNotNull();
            assertThat(doc.getString("organizationId")).isEqualTo(ORG);
            assertThat(doc.get("boundAssetId")).isNull();
            byte[] stored = storage.get(doc.getString("objectKey")).orElseThrow();
            assertThat(ref).isEqualTo("sha256:" + TestImages.sha256Hex(stored));
        }
    }

    // test 2: step ausente, DELIVERED o desconocido → 400, sin leer bytes ni crear nada
    @Test
    void a2_aMissingUnknownOrDeliveredStep_is400_andNothingIsReadOrCreated() throws Exception {
        long before = mediaCount();
        for (String step : new String[] {null, "DELIVERED", "dispatched", "NOPE", ""}) {
            HttpResponse<byte[]> r = uploadStep(ORG, EMPLOYEE, step, image(seed++));
            assertThat(r.statusCode()).as(String.valueOf(step) + " " + text(r)).isEqualTo(400);
        }
        assertThat(mediaCount()).isEqualTo(before);
        verify(storage, never()).put(anyString(), any(), anyString());
    }

    // test 3: 403 uniforme ANTES de leer bytes (otra organización, rol sin permiso, sin organización); 401 sin sesión
    @Test
    void a3_theSame403_beforeReadingBytes_andNothingIsStored() throws Exception {
        long before = mediaCount();
        for (String step : STEPS) {
            assertThat(uploadStep(OTHER_ORG, OTHER_EMPLOYEE, step, image(seed++)).statusCode()).as("otra org").isEqualTo(403);
            assertThat(uploadStep(ORG, OTHER_EMPLOYEE, step, image(seed++)).statusCode()).as("otro miembro, ruta ajena").isEqualTo(403);
            assertThat(uploadStep(ORG, REPRESENTATIVE, step, image(seed++)).statusCode()).as("rol").isEqualTo(403);
            assertThat(uploadStep(ORG, ADMIN_ONLY, step, image(seed++)).statusCode()).as("solo administrador").isEqualTo(403);
            assertThat(uploadStep(ORG, LONER, step, image(seed++)).statusCode()).as("sin organización").isEqualTo(403);
            assertThat(uploadStep(ORG, null, step, image(seed++)).statusCode()).as("sin sesión").isEqualTo(401);
        }
        // el mismo cuerpo de error para todos los 403 (no revela por qué)
        HttpResponse<byte[]> a = uploadStep(OTHER_ORG, OTHER_EMPLOYEE, "DISPATCHED", image(seed++));
        HttpResponse<byte[]> b = uploadStep(ORG, REPRESENTATIVE, "DISPATCHED", image(seed++));
        assertThat(text(a)).isEqualTo(text(b));
        assertThat(mediaCount()).isEqualTo(before);
        verify(storage, never()).put(anyString(), any(), anyString());
    }

    // test 4: 413 (más de 5 MB), 415 (no es imagen) y 503 (el proveedor no guarda), sin fila de metadato
    @Test
    void a4_413_415_and503_perStep_leaveNoMetadata() throws Exception {
        long before = mediaCount();
        for (String step : STEPS) {
            byte[] big = new byte[5 * 1024 * 1024 + 1024];
            byte[] png = image(seed++);
            System.arraycopy(png, 0, big, 0, png.length);
            HttpResponse<byte[]> tooBig = uploadStep(ORG, EMPLOYEE, step, big);
            assertThat(tooBig.statusCode()).as(step).isEqualTo(413);
            assertThat(json.readTree(tooBig.body()).get("title").asText()).isEqualTo("ImageTooLarge");

            assertThat(uploadStep(ORG, EMPLOYEE, step, "esto no es una imagen".getBytes(StandardCharsets.UTF_8)).statusCode())
                    .as(step).isEqualTo(415);

            doThrow(new MediaStorageUnavailableException("provider down")).when(storage).put(anyString(), any(), anyString());
            assertThat(uploadStep(ORG, EMPLOYEE, step, image(seed++)).statusCode()).as(step).isEqualTo(503);
            Mockito.reset(storage);
        }
        assertThat(mediaCount()).isEqualTo(before);
    }

    // ── B. Duplicados por propósito ──

    // test 6: la misma imagen dos veces al mismo paso → una fila y la misma huella
    @Test
    void b6_theSameImageTwiceInTheSameStep_isOneObject_andTheSameRef() throws Exception {
        for (String step : STEPS) {
            byte[] bytes = image(seed++);
            String first = json.readTree(uploadStep(ORG, EMPLOYEE, step, bytes).body()).get("evidenceRef").asText();
            long rows = mediaCount();
            HttpResponse<byte[]> again = uploadStep(ORG, EMPLOYEE, step, bytes);
            assertThat(again.statusCode()).as(step).isEqualTo(201);
            assertThat(json.readTree(again.body()).get("evidenceRef").asText()).isEqualTo(first);
            assertThat(mediaCount()).as(step).isEqualTo(rows);
            assertThat(mongoTemplate.getCollection("media_objects").countDocuments(
                    new Document("sha256", first.substring(7)).append("purpose", PURPOSE.get(step)))).isEqualTo(1);
        }
    }

    // test 7: la misma imagen en dos pasos → dos objetos independientes; vincular uno no vincula el otro
    @Test
    void b7_theSameImageInTwoSteps_isTwoSeparateObjects_eachBoundOnlyToItsStep() throws Exception {
        byte[] bytes = image(seed++);
        String dispatchRef = json.readTree(uploadStep(ORG, EMPLOYEE, "DISPATCHED", bytes).body()).get("evidenceRef").asText();
        String receiptRef = json.readTree(uploadStep(ORG, EMPLOYEE, "RECEIVED", bytes).body()).get("evidenceRef").asText();
        assertThat(receiptRef).isEqualTo(dispatchRef); // misma huella, objetos distintos
        assertThat(mongoTemplate.getCollection("media_objects").countDocuments(new Document("sha256", dispatchRef.substring(7)))).isEqualTo(2);
        Document dispatchDoc = media(dispatchRef, "DISPATCH_EVIDENCE");
        Document receiptDoc = media(receiptRef, "RECEIPT_EVIDENCE");
        assertThat(dispatchDoc.getString("objectKey")).isNotEqualTo(receiptDoc.getString("objectKey"));

        String assetRef = lot(null, null, null).assetRef(); // un lote ya recibido: se despacha otra vez con la foto de despacho
        dispatch(assetRef, dispatchRef);

        assertThat(media(dispatchRef, "DISPATCH_EVIDENCE").getString("boundAssetId")).isEqualTo(assetRef);
        assertThat(media(receiptRef, "RECEIPT_EVIDENCE").get("boundAssetId")).isNull();
    }

    // test 8: N subidas idénticas simultáneas → una sola fila, todas 201, sin 500 ni 409
    @Test
    void b8_simultaneousIdenticalUploads_leaveOneRow_andAllAre201() throws Exception {
        byte[] bytes = image(seed++);
        int n = 6;
        CyclicBarrier barrier = new CyclicBarrier(n);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<HttpResponse<byte[]>>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> {
                    barrier.await();
                    return uploadStep(ORG, EMPLOYEE, "RECEIVED", bytes);
                }));
            }
            String ref = null;
            for (Future<HttpResponse<byte[]>> f : futures) {
                HttpResponse<byte[]> r = f.get();
                assertThat(r.statusCode()).as(text(r)).isEqualTo(201);
                String got = json.readTree(r.body()).get("evidenceRef").asText();
                if (ref == null) ref = got;
                assertThat(got).isEqualTo(ref);
            }
            assertThat(mongoTemplate.getCollection("media_objects").countDocuments(
                    new Document("sha256", ref.substring(7)).append("purpose", "RECEIPT_EVIDENCE"))).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    // test 9: el índice de la entrega no cambia y el de los pasos existe, con su filtro
    @Test
    void b9_theDeliveryIndexIsUnchanged_andTheStepIndexExists() {
        List<org.springframework.data.mongodb.core.index.IndexInfo> indexes =
                mongoTemplate.indexOps("media_objects").getIndexInfo();
        var delivery = indexes.stream().filter(i -> i.getName().equals("uq_evidence_sha256")).findFirst().orElseThrow();
        assertThat(delivery.isUnique()).isTrue();
        assertThat(delivery.getPartialFilterExpression()).contains("DELIVERY_EVIDENCE").doesNotContain("DISPATCH_EVIDENCE");
        var step = indexes.stream().filter(i -> i.getName().equals("uq_step_evidence_sha256")).findFirst().orElseThrow();
        assertThat(step.isUnique()).isTrue();
        assertThat(step.getIndexFields()).extracting(f -> f.getKey()).containsExactly("organizationId", "purpose", "sha256");
        assertThat(step.getPartialFilterExpression()).contains("REGISTRATION_EVIDENCE", "DISPATCH_EVIDENCE", "RECEIPT_EVIDENCE")
                .doesNotContain("DELIVERY_EVIDENCE\"");
    }

    // ── C. Lectura ──

    // test 10: cada paso lee su foto (bytes y tipo correctos, private y no-store)
    @Test
    void c10_eachStepReadsItsOwnPhoto() throws Exception {
        Lot lot = lotWithPhotos();
        Map<String, String> refs = Map.of("registered", lot.registered(), "dispatched", lot.dispatched(), "received", lot.received());
        Map<String, String> purposes = Map.of("registered", "REGISTRATION_EVIDENCE", "dispatched", "DISPATCH_EVIDENCE",
                "received", "RECEIPT_EVIDENCE");
        for (var e : refs.entrySet()) {
            HttpResponse<byte[]> r = read(lot.assetRef(), e.getKey(), EMPLOYEE);
            assertThat(r.statusCode()).as(e.getKey()).isEqualTo(200);
            byte[] stored = storage.get(media(e.getValue(), purposes.get(e.getKey())).getString("objectKey")).orElseThrow();
            assertThat(r.body()).as(e.getKey()).isEqualTo(stored);
            assertThat(r.headers().firstValue("Cache-Control").orElse("")).contains("no-store").contains("private");
            assertThat(r.headers().firstValue("Content-Type").orElse("")).startsWith("image/");
        }
        // y cada paso da la SUYA, no la de otro
        assertThat(read(lot.assetRef(), "registered", EMPLOYEE).body()).isNotEqualTo(read(lot.assetRef(), "dispatched", EMPLOYEE).body());
    }

    // test 10 (alias): «delivered» da los mismos bytes que la ruta de la entrega de siempre
    @Test
    void c10_delivered_isTheAliasOfTheOldRoute() throws Exception {
        Lot lot = lot(null, null, null);
        String evidence = json.readTree(upload("/delivery-evidence", image(seed++)).body()).get("evidenceRef").asText();
        assertThat(send("POST", ASSETS + "/" + lot.assetRef() + "/deliver", EMPLOYEE, newId(),
                "{\"finalCustodianRef\":\"CUST-F\",\"beneficiaryRef\":\"BEN-1\",\"locationRef\":\"SITE-1\",\"municipalityCode\":\"05001\",\"evidenceRef\":\""
                        + evidence + "\"}").statusCode()).isEqualTo(200);

        HttpResponse<byte[]> old = send("GET", ASSETS + "/" + lot.assetRef() + "/evidence", EMPLOYEE, null, null);
        HttpResponse<byte[]> alias = read(lot.assetRef(), "delivered", EMPLOYEE);

        assertThat(old.statusCode()).isEqualTo(200);
        assertThat(alias.statusCode()).isEqualTo(200);
        assertThat(alias.body()).isEqualTo(old.body());
    }

    private HttpResponse<byte[]> upload(String route, byte[] file) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"e.png\""
                + "\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.writeBytes(file);
        out.writeBytes(("\r\n--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/organizations/" + ORG + route))
                .timeout(Duration.ofSeconds(20)).POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray()))
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .header("Authorization", "Bearer " + tokens.issue(EMPLOYEE)).build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    // test 11: sin foto → 404 (un paso sin foto, un paso que no existe y una huella sin metadato)
    @Test
    void c11_noPhoto_is404() throws Exception {
        Lot lot = lot(null, null, null);
        for (String step : new String[] {"registered", "dispatched", "received", "delivered"}) {
            assertThat(read(lot.assetRef(), step, EMPLOYEE).statusCode()).as(step).isEqualTo(404);
        }
        // paso desconocido
        assertThat(read(lot.assetRef(), "nope", EMPLOYEE).statusCode()).isEqualTo(404);
    }

    // test 12: 403 uniforme a quien no es de la organización; la plataforma sí lee, y recibe 404 de un activo inexistente
    @Test
    void c12_nonMembersGetTheSame403_platformReads_andANonexistentAssetIs404ForPlatformOnly() throws Exception {
        Lot lot = lotWithPhotos();
        HttpResponse<byte[]> other = read(lot.assetRef(), "dispatched", OTHER_EMPLOYEE);
        HttpResponse<byte[]> loner = read(lot.assetRef(), "dispatched", LONER);
        assertThat(other.statusCode()).isEqualTo(403);
        assertThat(loner.statusCode()).isEqualTo(403);
        assertThat(text(other)).isEqualTo(text(loner));
        assertThat(read(lot.assetRef(), "dispatched", null).statusCode()).isEqualTo(401);
        // miembros de cualquier rol
        assertThat(read(lot.assetRef(), "dispatched", REPRESENTATIVE).statusCode()).isEqualTo(200);
        assertThat(read(lot.assetRef(), "dispatched", ADMIN_ONLY).statusCode()).isEqualTo(200);
        assertThat(read(lot.assetRef(), "dispatched", PLATFORM).statusCode()).isEqualTo(200);
        // activo inexistente: 403 para un miembro (no revela si existe), 404 para la plataforma
        assertThat(read("no-existe", "dispatched", EMPLOYEE).statusCode()).isEqualTo(403);
        assertThat(read("no-existe", "dispatched", PLATFORM).statusCode()).isEqualTo(404);
    }

    // Q-T2-1: último evento del paso por defecto, ?sequence= para uno concreto
    @Test
    void c13_aRepeatedStep_readsTheLatestByDefault_andASpecificOneBySequence() throws Exception {
        Lot lot = lot(null, freshPhoto("DISPATCHED"), null);
        String second = freshPhoto("DISPATCHED");
        dispatch(lot.assetRef(), second); // re-despacho (el lote estaba recibido)

        List<JsonNode> entries = new ArrayList<>();
        json.readTree(steps(lot.assetRef(), EMPLOYEE).body()).forEach(entries::add);
        List<JsonNode> dispatches = entries.stream().filter(e -> e.get("step").asText().equals("dispatched")).toList();
        assertThat(dispatches).hasSize(2);
        long firstSeq = dispatches.get(0).get("sequence").asLong();
        long secondSeq = dispatches.get(1).get("sequence").asLong();
        assertThat(secondSeq).isGreaterThan(firstSeq);

        byte[] latest = read(lot.assetRef(), "dispatched", EMPLOYEE).body();
        byte[] byLatestSeq = send("GET", ASSETS + "/" + lot.assetRef() + "/evidence/dispatched?sequence=" + secondSeq, EMPLOYEE, null, null).body();
        byte[] byFirstSeq = send("GET", ASSETS + "/" + lot.assetRef() + "/evidence/dispatched?sequence=" + firstSeq, EMPLOYEE, null, null).body();
        assertThat(latest).isEqualTo(byLatestSeq);
        assertThat(byFirstSeq).isNotEqualTo(latest);
        // una sequence que no es de ese paso o no existe → 404; no numérica → 400
        assertThat(send("GET", ASSETS + "/" + lot.assetRef() + "/evidence/dispatched?sequence=99999", EMPLOYEE, null, null).statusCode()).isEqualTo(404);
        long registeredSeq = entries.stream().filter(e -> e.get("step").asText().equals("registered")).findFirst().orElseThrow().get("sequence").asLong();
        assertThat(send("GET", ASSETS + "/" + lot.assetRef() + "/evidence/dispatched?sequence=" + registeredSeq, EMPLOYEE, null, null).statusCode()).isEqualTo(404);
        assertThat(send("GET", ASSETS + "/" + lot.assetRef() + "/evidence/dispatched?sequence=abc", EMPLOYEE, null, null).statusCode()).isEqualTo(400);
    }

    // test 15: el listado no lleva huellas ni claves; trae sequence, fecha, hasPhoto y removed
    @Test
    void c15_theStepsList_hasNoFootprintsNorKeys_andOnlyMembersOrPlatformSeeIt() throws Exception {
        Lot lot = lot(freshPhoto("REGISTERED"), null, freshPhoto("RECEIVED"));
        HttpResponse<byte[]> r = steps(lot.assetRef(), EMPLOYEE);
        assertThat(r.statusCode()).isEqualTo(200);
        String body = text(r);
        assertThat(body).doesNotContain("sha256").doesNotContain(lot.registered().substring(7)).doesNotContain("objectKey")
                .doesNotContain("evidenceRef");
        JsonNode list = json.readTree(r.body());
        assertThat(list.isArray()).isTrue();
        List<String> shape = new ArrayList<>();
        list.get(0).fieldNames().forEachRemaining(shape::add);
        assertThat(shape).containsExactly("step", "sequence", "occurredAt", "hasPhoto", "removed");
        assertThat(list).extracting(n -> n.get("step").asText() + ":" + n.get("hasPhoto").asBoolean())
                .containsExactly("registered:true", "dispatched:false", "received:true");
        assertThat(steps(lot.assetRef(), PLATFORM).statusCode()).isEqualTo(200);
        assertThat(steps(lot.assetRef(), OTHER_EMPLOYEE).statusCode()).isEqualTo(403);
        assertThat(steps(lot.assetRef(), null).statusCode()).isEqualTo(401);
    }

    // test 14: nunca públicas — sin sesión no hay acceso, y ninguna ruta pública sirve estas fotos
    @Test
    void c14_neverPublic_theRoutesNeedASession_andNoPublicRouteServesThem() throws Exception {
        Lot lot = lotWithPhotos();
        for (String path : List.of(ASSETS + "/" + lot.assetRef() + "/evidence/registered", ASSETS + "/" + lot.assetRef() + "/evidence/steps",
                ASSETS + "/" + lot.assetRef() + "/evidence/dispatched?sequence=2")) {
            assertThat(send("GET", path, null, null, null).statusCode()).as(path).isEqualTo(401);
        }
        // las rutas públicas y del seguimiento no tienen estas fotos (ni con un código inventado)
        for (String path : List.of("/api/v1/donations/tracking/assets/" + lot.assetRef() + "/evidence/registered",
                "/api/v1/public/assets/" + lot.assetRef() + "/evidence/registered")) {
            HttpResponse<byte[]> r = send("GET", path, null, null, null);
            assertThat(r.statusCode()).as(path).isIn(401, 403, 404);
            assertThat(r.headers().firstValue("Content-Type").orElse("")).as(path).doesNotStartWith("image/");
        }
    }

    // test 13: retirada por F7 → 410 en la lectura y removed: true en el listado
    @Test
    void c13b_aRemovedPhoto_is410_andTheListSaysRemoved() throws Exception {
        Lot lot = lotWithPhotos();
        assertThat(remove(PLATFORM, ORG, "DISPATCH_EVIDENCE", lot.dispatched(), REASON).statusCode()).isEqualTo(204);

        HttpResponse<byte[]> gone = read(lot.assetRef(), "dispatched", EMPLOYEE);
        assertThat(gone.statusCode()).isEqualTo(410);
        assertThat(json.readTree(gone.body()).get("title").asText()).isEqualTo("EvidenceRemoved");
        // las otras siguen
        assertThat(read(lot.assetRef(), "registered", EMPLOYEE).statusCode()).isEqualTo(200);
        JsonNode list = json.readTree(steps(lot.assetRef(), EMPLOYEE).body());
        assertThat(list).extracting(n -> n.get("step").asText() + ":" + n.get("removed").asBoolean())
                .containsExactly("registered:false", "dispatched:true", "received:false");
    }

    // ── D. Retirada F7 de los tres propósitos ──

    // test 16: 204; bytes borrados una vez; metadato con deletedAt, deletedBy y reason; repetir → 204 sin segundo borrado
    @Test
    void d16_removalOfEachStepPurpose_deletesTheBytes_marksTheMetadata_andIsIdempotent() throws Exception {
        Lot lot = lotWithPhotos();
        Map<String, String> refs = Map.of("REGISTRATION_EVIDENCE", lot.registered(), "DISPATCH_EVIDENCE", lot.dispatched(),
                "RECEIPT_EVIDENCE", lot.received());
        for (var e : refs.entrySet()) {
            String key = media(e.getValue(), e.getKey()).getString("objectKey");
            Mockito.reset(storage);

            HttpResponse<byte[]> r = remove(PLATFORM, ORG, e.getKey(), e.getValue(), REASON);

            assertThat(r.statusCode()).as(e.getKey() + " " + text(r)).isEqualTo(204);
            assertThat(text(r)).doesNotContain("motivo-secreto-t2");
            verify(storage, times(1)).delete(key);
            assertThat(storage.get(key)).isEmpty();
            Document doc = media(e.getValue(), e.getKey());
            assertThat(doc.get("deletedAt")).isNotNull();
            assertThat(doc.getString("deletedBy")).isEqualTo(PLATFORM);
            assertThat(doc.getString("reason")).isEqualTo(REASON);

            Mockito.reset(storage);
            assertThat(remove(PLATFORM, ORG, e.getKey(), e.getValue(), REASON).statusCode()).as("repetida").isEqualTo(204);
            verify(storage, never()).delete(anyString()); // aserción negativa: sin segundo borrado
        }
    }

    // un 503 del proveedor deja el metadato sin marcar y se puede reintentar
    @Test
    void d16b_aProviderFailure_is503_leavesTheMetadataUnmarked_andCanBeRetried() throws Exception {
        String ref = freshPhoto("RECEIVED");
        doThrow(new MediaStorageUnavailableException("provider down")).when(storage).delete(anyString());

        assertThat(remove(PLATFORM, ORG, "RECEIPT_EVIDENCE", ref, REASON).statusCode()).isEqualTo(503);
        assertThat(media(ref, "RECEIPT_EVIDENCE").get("deletedAt")).isNull();

        Mockito.reset(storage);
        assertThat(remove(PLATFORM, ORG, "RECEIPT_EVIDENCE", ref, REASON).statusCode()).isEqualTo(204);
        assertThat(media(ref, "RECEIPT_EVIDENCE").get("deletedAt")).isNotNull();
    }

    // test 17: solo la plataforma retira (403 uniforme al resto); sin motivo, 400; en ambos casos no se borra nada
    @Test
    void d17_onlyThePlatformRemoves_andWithoutAReasonNothingIsDeleted() throws Exception {
        String ref = freshPhoto("DISPATCHED");
        Mockito.reset(storage);
        for (String account : List.of(EMPLOYEE, ADMIN_ONLY, REPRESENTATIVE, OTHER_EMPLOYEE, LONER)) {
            assertThat(remove(account, ORG, "DISPATCH_EVIDENCE", ref, REASON).statusCode()).as(account).isEqualTo(403);
        }
        assertThat(remove(PLATFORM, ORG, "DISPATCH_EVIDENCE", ref, null).statusCode()).isEqualTo(400);
        assertThat(remove(PLATFORM, ORG, "DISPATCH_EVIDENCE", "no-es-una-huella", REASON).statusCode()).isEqualTo(400);
        assertThat(remove(PLATFORM, ORG, "DISPATCH_EVIDENCE", "sha256:" + "0".repeat(64), REASON).statusCode()).isEqualTo(404);
        verify(storage, never()).delete(anyString());
        assertThat(media(ref, "DISPATCH_EVIDENCE").get("deletedAt")).isNull();
    }

    // la retirada del propósito de un paso no toca la foto de OTRO propósito con la misma huella
    @Test
    void d18_removingOnePurpose_doesNotTouchTheSameImageInAnotherPurpose() throws Exception {
        byte[] bytes = image(seed++);
        String dispatchRef = json.readTree(uploadStep(ORG, EMPLOYEE, "DISPATCHED", bytes).body()).get("evidenceRef").asText();
        uploadStep(ORG, EMPLOYEE, "RECEIVED", bytes);

        assertThat(remove(PLATFORM, ORG, "DISPATCH_EVIDENCE", dispatchRef, REASON).statusCode()).isEqualTo(204);

        assertThat(media(dispatchRef, "DISPATCH_EVIDENCE").get("deletedAt")).isNotNull();
        assertThat(media(dispatchRef, "RECEIPT_EVIDENCE").get("deletedAt")).isNull(); // aserción negativa
    }
}
