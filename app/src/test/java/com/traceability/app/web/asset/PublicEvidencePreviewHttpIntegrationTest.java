package com.traceability.app.web.asset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.app.TraceabilityApplication;
import com.traceability.app.application.media.TestImages;
import com.traceability.contracts.authentication.TokenIssuerPort;
import com.traceability.contracts.authorization.AuthorizationPrincipal;
import com.traceability.contracts.authorization.AuthorizationRole;
import com.traceability.contracts.authorization.IdentityPrincipalPort;
import com.traceability.contracts.authorization.PlatformAuthority;
import com.traceability.core.application.command.FundCommandService;
import com.traceability.core.application.security.AssetRefService;
import com.traceability.core.application.security.TrackingCodeService;
import com.traceability.core.domain.event.SystemActor;
import com.traceability.core.domain.fund.OrganizationRef;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * ADR-021-D Enmienda 2 y ADR-051 Enmienda 2, Tarea 4 (definición de hecho, puntos 1 a 6), contra Tomcat y Mongo reales con
 * todos los módulos: la copia censurada de la foto de entrega en el seguimiento público.
 * <ul>
 *   <li>copia publicada → el paso de la entrega lleva {@code evidencePreviewUrl} y la ruta devuelve el PNG;</li>
 *   <li>nunca generada, sin publicar, despublicada, retirada por F7 y sin foto de entrega → sin campo y el mismo 404, con
 *   el cuerpo idéntico byte a byte;</li>
 *   <li>un código que no cubre el activo → el mismo 401 que el historial, nunca 404;</li>
 *   <li>nada de la original, su {@code objectKey} ni de quien publicó sale por ninguna respuesta pública;</li>
 *   <li>la huella ({@code evidenceRef}) es la de la original antes y después de publicar; los pasos de registrar, despachar
 *   y recibir con foto la llevan también (Q-21D2-1 (a)).</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = TraceabilityApplication.class)
@org.springframework.context.annotation.Import(com.traceability.app.infrastructure.media.InMemoryMediaStorageConfig.class)
@Testcontainers
@TestPropertySource(properties = {
    "crypto.anchor.poll.delay=9999999",
    "crypto.anchor.submit.delay=9999999",
    "crypto.anchor.stuck-monitor.delay=9999999",
    "crypto.web3j.private-key=0x1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef",
    "crypto.web3j.node-url=http://dummy-node",
    "spring.ai.openai.api-key=dummy-api-key",
    "saga.outbox.delay=200"
})
class PublicEvidencePreviewHttpIntegrationTest {

    private static final String ORG = "org-prev";
    private static final String EMPLOYEE = "acc-prev-employee";
    private static final String ADMIN = "acc-prev-administrator-4f2a";
    private static final String PLATFORM = "acc-prev-platform";
    private static final String BASE = "/api/v1/physical-assets";
    private static final String CONFIRMED = "{\"confirmNoIdentifiablePerson\":true}";
    private static final String BOUNDARY = "prev-boundary-7d1e";
    private static final AtomicInteger SEEDS = new AtomicInteger(7000);

    @Container
    static MongoDBContainer mongo = new MongoDBContainer(DockerImageName.parse("mongo:6.0")).withCommand("--replSet", "rs0");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongo::getReplicaSetUrl);
    }

    @LocalServerPort private int port;
    @Autowired private TokenIssuerPort tokens;
    @Autowired private FundCommandService funds;
    @Autowired private TrackingCodeService trackingCodes;
    @Autowired private AssetRefService assetRefs;
    @Autowired private MongoTemplate mongoTemplate;
    @MockitoBean private IdentityPrincipalPort identityPrincipalPort;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    /** Un activo entregado de un fondo: el código del donante, la referencia opaca y la huella de la foto. */
    private record Delivered(String assetId, String fundId, String code, String ref, String evidenceRef) {
        String previewPath() {
            return "/api/v1/donations/tracking/assets/" + ref + "/evidence-preview";
        }

        String historyPath() {
            return "/api/v1/donations/tracking/assets/" + ref + "/history";
        }
    }

    @BeforeEach
    void principals() {
        Map<String, AuthorizationPrincipal> principals = Map.of(
                EMPLOYEE, new AuthorizationPrincipal(EMPLOYEE, ORG, Set.of(AuthorizationRole.EMPLOYEE), null),
                ADMIN, new AuthorizationPrincipal(ADMIN, ORG, Set.of(AuthorizationRole.ADMINISTRATOR, AuthorizationRole.EMPLOYEE), null),
                PLATFORM, new AuthorizationPrincipal(PLATFORM, null, Set.of(), PlatformAuthority.ADMINISTRATOR));
        when(identityPrincipalPort.resolvePrincipal(anyString())).thenAnswer(inv -> principals.get(inv.<String>getArgument(0)));
        when(identityPrincipalPort.resolveAuthenticatedPrincipal(anyString(), any()))
                .thenAnswer(inv -> principals.get(inv.<String>getArgument(0)));
    }

    // ── HTTP ──

    private HttpResponse<byte[]> send(String method, String path, String bearer, String commandId, String body) throws Exception {
        HttpRequest.Builder r = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(30))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (bearer != null) r.header("Authorization", "Bearer " + bearer);
        if (commandId != null) r.header("Command-Id", commandId);
        if (body != null) r.header("Content-Type", "application/json");
        return http.send(r.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private HttpResponse<byte[]> as(String account, String method, String path, String body) throws Exception {
        return send(method, path, tokens.issue(account), "GET".equals(method) || "DELETE".equals(method) ? null
                : UUID.randomUUID().toString(), body);
    }

    private static String text(HttpResponse<byte[]> r) {
        return new String(r.body(), StandardCharsets.UTF_8);
    }

    private String photo(int step) throws Exception {
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

    private String fundWithAllocation(String allocationId) {
        String fundId = UUID.randomUUID().toString();
        SystemActor system = new SystemActor("preview-http-tests");
        funds.clearFundsGenesis(UUID.randomUUID().toString(), fundId, new OrganizationRef(ORG), "camp-prev", "anon:d",
                "COP", 1000L, "SRC", system);
        funds.requestAllocation(UUID.randomUUID().toString(), fundId, allocationId, 100L, system);
        return fundId;
    }

    /** Un activo de un fondo, recorrido hasta entregar con {@code deliveryEvidence}; espera a que la historia tenga 4 pasos. */
    private Delivered deliver(String deliveryEvidence) throws Exception {
        String allocationId = UUID.randomUUID().toString();
        String fundId = fundWithAllocation(allocationId);
        HttpResponse<byte[]> registered = as(EMPLOYEE, "POST", BASE + "/register", """
                {"fundId":"%s","assetType":"FOOD","quantity":"10","unitOfMeasure":"KGS","custodianRef":"C",
                 "currentLocation":"Bodega norte privada","allocationId":"%s","municipalityCode":"05001"}"""
                .formatted(fundId, allocationId));
        assertThat(registered.statusCode()).as(text(registered)).isEqualTo(201);
        String assetId = json.readTree(registered.body()).get("assetRef").asText();
        assertThat(as(EMPLOYEE, "POST", BASE + "/" + assetId + "/dispatch", "{\"carrierRef\":\"CARRIER-1\"}").statusCode()).isEqualTo(200);
        assertThat(as(EMPLOYEE, "POST", BASE + "/" + assetId + "/receive",
                "{\"facilityLocation\":\"Centro de acopio privado\",\"receiverRef\":\"REC-1\",\"municipalityCode\":\"05002\"}")
                .statusCode()).isEqualTo(200);
        HttpResponse<byte[]> delivered = as(EMPLOYEE, "POST", BASE + "/" + assetId + "/deliver", """
                {"finalCustodianRef":"CUST-F","beneficiaryRef":"BEN-PRIVADO-1","locationRef":"Escuela rural privada",
                 "evidenceRef":"%s","municipalityCode":"11001"}""".formatted(deliveryEvidence));
        assertThat(delivered.statusCode()).as(text(delivered)).isEqualTo(200);

        Delivered d = new Delivered(assetId, fundId, trackingCodes.generate(fundId, Instant.now().plusSeconds(3600)),
                assetRefs.computeRef(assetId), deliveryEvidence);
        Instant deadline = Instant.now().plusSeconds(30);
        while (Instant.now().isBefore(deadline)) {
            HttpResponse<byte[]> r = send("GET", d.historyPath(), d.code(), null, null);
            if (r.statusCode() == 200 && json.readTree(r.body()).get("history").size() == 4) {
                return d;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("la proyección no escribió los cuatro pasos");
    }

    /** Un activo registrado de un fondo, sin entregar (y por tanto sin foto de entrega). */
    private Delivered registeredOnly() throws Exception {
        String allocationId = UUID.randomUUID().toString();
        String fundId = fundWithAllocation(allocationId);
        HttpResponse<byte[]> registered = as(EMPLOYEE, "POST", BASE + "/register", """
                {"fundId":"%s","assetType":"FOOD","quantity":"10","unitOfMeasure":"KGS","custodianRef":"C",
                 "currentLocation":"Bodega norte privada","allocationId":"%s","municipalityCode":"05001"}"""
                .formatted(fundId, allocationId));
        assertThat(registered.statusCode()).as(text(registered)).isEqualTo(201);
        String assetId = json.readTree(registered.body()).get("assetRef").asText();
        Delivered d = new Delivered(assetId, fundId, trackingCodes.generate(fundId, Instant.now().plusSeconds(3600)),
                assetRefs.computeRef(assetId), null);
        Instant deadline = Instant.now().plusSeconds(30);
        while (Instant.now().isBefore(deadline)) {
            if (send("GET", d.historyPath(), d.code(), null, null).statusCode() == 200) return d;
            Thread.sleep(200);
        }
        throw new AssertionError("la proyección no escribió el registro");
    }

    private Delivered deliveredWithPhoto() throws Exception {
        return deliver(photo(0));
    }

    private JsonNode deliveryStep(Delivered d) throws Exception {
        HttpResponse<byte[]> r = send("GET", d.historyPath(), d.code(), null, null);
        assertThat(r.statusCode()).as(text(r)).isEqualTo(200);
        return json.readTree(r.body()).get("history").get(3);
    }

    private HttpResponse<byte[]> preview(Delivered d) throws Exception {
        return send("GET", d.previewPath(), d.code(), null, null);
    }

    private void publish(Delivered d) throws Exception {
        assertThat(as(ADMIN, "GET", BASE + "/" + d.assetId() + "/evidence/redacted", null).statusCode()).isEqualTo(200); // la genera
        HttpResponse<byte[]> r = as(ADMIN, "PUT", BASE + "/" + d.assetId() + "/evidence/redacted/publication", CONFIRMED);
        assertThat(r.statusCode()).as(text(r)).isEqualTo(204);
    }

    private HttpResponse<byte[]> remove(Delivered d) throws Exception {
        return as(PLATFORM, "POST", "/api/v1/platform/organizations/" + ORG + "/media/remove",
                "{\"purpose\":\"DELIVERY_EVIDENCE\",\"ref\":\"" + d.evidenceRef() + "\",\"reason\":\"motivo-secreto-prev\"}");
    }

    private Document original(Delivered d) {
        return mongoTemplate.getCollection("media_objects").find(new Document("sha256", d.evidenceRef().substring(7))
                .append("purpose", "DELIVERY_EVIDENCE").append("organizationId", ORG)).first();
    }

    private Document copyOf(Delivered d) {
        return mongoTemplate.getCollection("media_objects").find(new Document("purpose", "DELIVERY_EVIDENCE_REDACTED")
                .append("organizationId", ORG).append("ownerRef", original(d).getString("objectKey"))).first();
    }

    // ── tests ──

    // 1 y 4: la copia publicada se anuncia y se sirve; la huella sigue siendo la de la original
    @Test
    void t1_aPublishedCopy_isAnnouncedAndServed_andTheFootprintIsStillTheOriginals() throws Exception {
        Delivered d = deliveredWithPhoto();
        assertThat(deliveryStep(d).has("evidencePreviewUrl")).isFalse();
        assertThat(deliveryStep(d).get("evidenceRef").asText()).isEqualTo(d.evidenceRef());

        publish(d);

        JsonNode step = deliveryStep(d);
        assertThat(step.get("evidencePreviewUrl").asText()).isEqualTo(d.previewPath());
        assertThat(step.get("evidenceRef").asText()).isEqualTo(d.evidenceRef()); // 4: la de la original, no la de la copia
        assertThat(step.get("evidenceRef").asText()).isNotEqualTo("sha256:" + copyOf(d).getString("sha256"));
        HttpResponse<byte[]> r = preview(d);
        assertThat(r.statusCode()).as(text(r)).isEqualTo(200);
        assertThat(r.headers().firstValue("Content-Type").orElse("")).isEqualTo("image/png");
        assertThat(r.headers().firstValue("Cache-Control").orElse("")).contains("no-store").contains("private");
        // es la copia censurada, no la original: los mismos bytes que la ruta de la organización
        byte[] viaOrganization = as(EMPLOYEE, "GET", BASE + "/" + d.assetId() + "/evidence/redacted", null).body();
        assertThat(r.body()).isEqualTo(viaOrganization);
        assertThat(r.body()).isNotEmpty();
    }

    // 2: los cinco casos sin copia visible → sin campo y el MISMO 404, cuerpo idéntico byte a byte
    @Test
    void t2_everyCaseWithoutAVisibleCopy_hasNoField_andTheSame404WithTheSameBody() throws Exception {
        Delivered neverGenerated = deliveredWithPhoto();

        Delivered generatedNotPublished = deliveredWithPhoto();
        assertThat(as(ADMIN, "GET", BASE + "/" + generatedNotPublished.assetId() + "/evidence/redacted", null).statusCode()).isEqualTo(200);

        Delivered unpublished = deliveredWithPhoto();
        publish(unpublished);
        assertThat(as(ADMIN, "DELETE", BASE + "/" + unpublished.assetId() + "/evidence/redacted/publication", null).statusCode())
                .isEqualTo(204);

        Delivered removed = deliveredWithPhoto();
        publish(removed);
        assertThat(remove(removed).statusCode()).isEqualTo(204);

        Delivered noPhoto = registeredOnly(); // un activo que aún no se entregó: no hay foto de entrega

        List<Delivered> cases = List.of(neverGenerated, generatedNotPublished, unpublished, removed, noPhoto);
        List<String> bodies = new ArrayList<>();
        for (Delivered d : cases) {
            HttpResponse<byte[]> r = preview(d);
            assertThat(r.statusCode()).as(d.assetId()).isEqualTo(404);
            assertThat(d == noPhoto || deliveryStep(d).has("evidencePreviewUrl") == false).as(d.assetId()).isTrue();
            assertThat(text(r)).doesNotContainIgnoringCase("publish").doesNotContainIgnoringCase("remov")
                    .doesNotContainIgnoringCase("retir").doesNotContainIgnoringCase("revisi");
            // aserción negativa: el cuerpo no menciona nada específico del activo (ni su referencia ni su identificador)
            bodies.add(text(r).replace(d.ref(), "<REF>").replace(d.assetId(), "<ID>"));
        }
        assertThat(bodies).allMatch(b -> b.equals(bodies.get(0)), "cuerpos idénticos entre los cinco casos");
        assertThat(bodies.get(0)).isNotEmpty();
        // la huella de la original sigue ahí en el paso retirado (E7: evidenceRef igual)
        assertThat(deliveryStep(removed).get("evidenceRef").asText()).isEqualTo(removed.evidenceRef());
        assertThat(deliveryStep(unpublished).get("evidenceRef").asText()).isEqualTo(unpublished.evidenceRef());
    }

    // 2 (negativa): un código que no cubre el activo → el mismo 401 que el historial, nunca 404
    @Test
    void t2b_aCodeThatDoesNotCoverTheAsset_isTheSame401AsTheHistory_neverA404() throws Exception {
        Delivered a = deliveredWithPhoto();
        publish(a);
        Delivered other = deliveredWithPhoto();

        for (String path : List.of(a.previewPath(), "/api/v1/donations/tracking/assets/inventada/evidence-preview")) {
            HttpResponse<byte[]> r = send("GET", path, other.code(), null, null);
            assertThat(r.statusCode()).as(path).isEqualTo(401);
        }
        HttpResponse<byte[]> preview401 = send("GET", a.previewPath(), other.code(), null, null);
        HttpResponse<byte[]> history401 = send("GET", a.historyPath(), other.code(), null, null);
        assertThat(history401.statusCode()).isEqualTo(401);
        assertThat(text(preview401)).isEqualTo(text(history401).replace("/history", "/evidence-preview"));
        // sin código y con código basura
        assertThat(send("GET", a.previewPath(), null, null, null).statusCode()).isEqualTo(401);
        assertThat(send("GET", a.previewPath(), "basura", null, null).statusCode()).isEqualTo(401);
    }

    // 3: ninguna respuesta pública lleva la original, su objectKey ni los datos de quien publicó
    @Test
    void t3_noPublicResponseCarriesTheOriginalItsObjectKeyOrWhoPublished() throws Exception {
        Delivered d = deliveredWithPhoto();
        publish(d);
        String originalKey = original(d).getString("objectKey");
        String copyKey = copyOf(d).getString("objectKey");
        byte[] originalBytes = null;
        HttpResponse<byte[]> history = send("GET", d.historyPath(), d.code(), null, null);
        HttpResponse<byte[]> tracking = send("GET", "/api/v1/donations/tracking", d.code(), null, null);
        HttpResponse<byte[]> image = preview(d);
        assertThat(history.statusCode()).isEqualTo(200);
        assertThat(tracking.statusCode()).isEqualTo(200);
        assertThat(image.statusCode()).isEqualTo(200);

        for (HttpResponse<byte[]> r : List.of(history, tracking, image)) {
            // aserción negativa sobre el cuerpo (como texto Latin-1, para cubrir también los bytes de la imagen) y las cabeceras
            String body = new String(r.body(), StandardCharsets.ISO_8859_1);
            String headers = r.headers().map().toString();
            for (String secret : List.of(originalKey, copyKey, ADMIN, "publishedBy", "confirmNoIdentifiablePerson",
                    "objectKey", "ownerRef", "media_objects", "Bodega norte privada", "BEN-PRIVADO-1")) {
                assertThat(body).as("cuerpo contiene " + secret).doesNotContain(secret);
                assertThat(headers).as("cabeceras contienen " + secret).doesNotContain(secret);
            }
        }
        // la imagen pública NO es la original: las dos tienen bytes distintos
        byte[] original = as(EMPLOYEE, "GET", BASE + "/" + d.assetId() + "/evidence", null).body();
        assertThat(image.body()).isNotEqualTo(original);
    }

    // 5 (Q-21D2-1 (a)): las fotos de registrar, despachar y recibir llevan su huella en el seguimiento
    @Test
    void t5_theOtherStepsWithAPhoto_carryTheirFootprint_andTheOnesWithoutIt_areNotRegistered() throws Exception {
        String allocationId = UUID.randomUUID().toString();
        String fundId = fundWithAllocation(allocationId);
        // la huella de cada paso sale de la subida de ese paso
        String registerRef = stepPhoto("REGISTERED");
        HttpResponse<byte[]> registered = as(EMPLOYEE, "POST", BASE + "/register", """
                {"fundId":"%s","assetType":"FOOD","quantity":"10","unitOfMeasure":"KGS","custodianRef":"C",
                 "currentLocation":"Bodega norte privada","allocationId":"%s","municipalityCode":"05001","evidenceRef":"%s"}"""
                .formatted(fundId, allocationId, registerRef));
        assertThat(registered.statusCode()).as(text(registered)).isEqualTo(201);
        String assetId = json.readTree(registered.body()).get("assetRef").asText();
        String dispatchRef = stepPhoto("DISPATCHED");
        assertThat(as(EMPLOYEE, "POST", BASE + "/" + assetId + "/dispatch",
                "{\"carrierRef\":\"CARRIER-1\",\"evidenceRef\":\"" + dispatchRef + "\"}").statusCode()).isEqualTo(200);
        // recibir SIN foto
        assertThat(as(EMPLOYEE, "POST", BASE + "/" + assetId + "/receive",
                "{\"facilityLocation\":\"Centro\",\"receiverRef\":\"REC-1\",\"municipalityCode\":\"05002\"}").statusCode()).isEqualTo(200);
        String deliveryRef = photo(0);
        assertThat(as(EMPLOYEE, "POST", BASE + "/" + assetId + "/deliver", """
                {"finalCustodianRef":"CUST-F","beneficiaryRef":"BEN-1","locationRef":"SITE","evidenceRef":"%s","municipalityCode":"11001"}"""
                .formatted(deliveryRef)).statusCode()).isEqualTo(200);

        String code = trackingCodes.generate(fundId, Instant.now().plusSeconds(3600));
        String path = "/api/v1/donations/tracking/assets/" + assetRefs.computeRef(assetId) + "/history";
        JsonNode history = null;
        Instant deadline = Instant.now().plusSeconds(30);
        while (Instant.now().isBefore(deadline) && history == null) {
            HttpResponse<byte[]> r = send("GET", path, code, null, null);
            if (r.statusCode() == 200 && json.readTree(r.body()).get("history").size() == 4) history = json.readTree(r.body()).get("history");
            else Thread.sleep(200);
        }
        assertThat(history).isNotNull();
        assertThat(history.get(0).get("evidenceRegistered").asBoolean()).as(history.toString()).isTrue();
        assertThat(history.get(0).get("evidenceRef").asText()).isEqualTo(registerRef);
        assertThat(history.get(1).get("evidenceRegistered").asBoolean()).isTrue();
        assertThat(history.get(1).get("evidenceRef").asText()).isEqualTo(dispatchRef);
        assertThat(history.get(2).get("evidenceRegistered").asBoolean()).isFalse(); // recibir sin foto
        assertThat(history.get(2).has("evidenceRef")).isFalse();
        assertThat(history.get(3).get("evidenceRef").asText()).isEqualTo(deliveryRef);
        // la copia no existe para esos pasos: solo la entrega puede llevar evidencePreviewUrl
        for (int i = 0; i < 3; i++) assertThat(history.get(i).has("evidencePreviewUrl")).isFalse();
    }

    private String stepPhoto(String step) throws Exception {
        String boundary = "prev-step-" + step;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"step\"\r\n\r\n" + step + "\r\n")
                .getBytes(StandardCharsets.UTF_8));
        out.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"p.png\""
                + "\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.writeBytes(TestImages.png(40 + SEEDS.getAndIncrement(), 30));
        out.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        HttpResponse<byte[]> r = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                        + "/api/v1/organizations/" + ORG + "/asset-evidence")).timeout(Duration.ofSeconds(20))
                .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray()))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Authorization", "Bearer " + tokens.issue(EMPLOYEE)).build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(r.statusCode()).as(text(r)).isEqualTo(201);
        return json.readTree(r.body()).get("evidenceRef").asText();
    }

    // 6 (E6): el código en la consulta nunca sustituye a la cabecera, y con cabecera se ignora
    @Test
    void t6_aCodeInTheQuery_neverReplacesTheHeader_andNothingEchoesIt() throws Exception {
        Delivered d = deliveredWithPhoto();
        publish(d);

        for (String param : List.of("code", "trackingCode", "token", "access_token")) {
            HttpResponse<byte[]> r = send("GET", d.previewPath() + "?" + param + "=" + d.code(), null, null, null);
            assertThat(r.statusCode()).as(param).isEqualTo(401);
            assertThat(text(r)).doesNotContain(d.code());
        }
        HttpResponse<byte[]> ok = send("GET", d.previewPath() + "?code=otro&trackingCode=x", d.code(), null, null);
        assertThat(ok.statusCode()).isEqualTo(200);
        assertThat(new String(ok.body(), StandardCharsets.ISO_8859_1)).doesNotContain(d.code());
        assertThat(ok.headers().map().toString()).doesNotContain(d.code());
        // y la URL anunciada no lleva el código: ni ruta, ni consulta, ni fragmento
        String url = deliveryStep(d).get("evidencePreviewUrl").asText();
        assertThat(url).doesNotContain(d.code()).doesNotContain("?").doesNotContain("#");
    }
}
