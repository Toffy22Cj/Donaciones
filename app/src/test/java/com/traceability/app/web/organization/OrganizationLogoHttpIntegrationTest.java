package com.traceability.app.web.organization;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.app.TraceabilityApplication;
import com.traceability.app.application.media.MediaPurpose;
import com.traceability.app.application.media.MediaUploadService;
import com.traceability.app.application.media.TestImages;
import com.traceability.app.infrastructure.media.InMemoryMediaStorageAdapter;
import com.traceability.contracts.authentication.TokenIssuerPort;
import com.traceability.contracts.authorization.IdentityPrincipalPort;
import com.traceability.convocatoria.application.command.CreateConvocatoriaCommand;
import com.traceability.convocatoria.application.service.ConvocatoriaLifecycleService;
import com.traceability.convocatoria.domain.model.ConvocatoriaConfiguration;
import com.traceability.convocatoria.domain.model.DonationType;
import com.traceability.convocatoria.domain.model.PaymentMethod;
import com.traceability.convocatoria.domain.model.TargetPolicy;
import com.traceability.convocatoria.domain.model.Visibility;
import identity.application.service.AddEmployeeService;
import identity.application.service.AssignAdministratorService;
import identity.application.service.BootstrapPlatformAuthorityService;
import identity.application.service.CreateAccountService;
import identity.application.service.CreateOrganizationService;
import identity.application.service.OrganizationLogoReferenceService;
import identity.application.service.VerifyOrganizationService;
import identity.domain.model.Account;
import identity.domain.model.AccountId;
import identity.domain.model.AuditActor;
import identity.domain.model.Email;
import identity.domain.model.Organization;
import identity.domain.model.OrganizationId;
import identity.domain.model.OrganizationType;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

/**
 * ADR-051, Tarea 2 (logo de la organización) contra Tomcat real, con {@code identity}, {@code convocatoria} y
 * {@code media_objects} reales y el proveedor en memoria (plan de la Tarea 2 §5: T2-1 a T2-13 y T2-15). Cada test usa
 * su propia organización verificada, con una convocatoria {@code PUBLIC}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = TraceabilityApplication.class)
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
@Import(OrganizationLogoHttpIntegrationTest.InMemoryStorage.class)
@TestPropertySource(properties = {
    "crypto.anchor.poll.delay=9999999",
    "crypto.anchor.submit.delay=9999999",
    "crypto.anchor.stuck-monitor.delay=9999999",
    "crypto.web3j.private-key=0x1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef",
    "crypto.web3j.node-url=http://dummy-node",
    "spring.ai.openai.api-key=dummy-api-key"
})
class OrganizationLogoHttpIntegrationTest {

    @Container
    static MongoDBContainer mongo = new MongoDBContainer(DockerImageName.parse("mongo:6.0")).withCommand("--replSet", "rs0");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongo::getReplicaSetUrl);
    }

    /** El proveedor en memoria, espiado: sustituye al S3 ficticio de los tests (que nadie escucha). */
    @TestConfiguration
    static class InMemoryStorage {
        @Bean
        @Primary
        InMemoryMediaStorageAdapter inMemoryMediaStorage() {
            return Mockito.spy(new InMemoryMediaStorageAdapter());
        }
    }

    @LocalServerPort private int port;
    @Autowired private TokenIssuerPort tokens;
    @Autowired private CreateAccountService accounts;
    @Autowired private CreateOrganizationService organizations;
    @Autowired private AddEmployeeService employees;
    @Autowired private AssignAdministratorService administrators;
    @Autowired private BootstrapPlatformAuthorityService bootstrap;
    @Autowired private VerifyOrganizationService verifications;
    @Autowired private IdentityPrincipalPort principals;
    @Autowired private ConvocatoriaLifecycleService lifecycle;
    @Autowired private MongoTemplate mongoTemplate;
    @Autowired private InMemoryMediaStorageAdapter storage;
    @MockitoSpyBean private MediaUploadService uploads;
    @MockitoSpyBean private OrganizationLogoReferenceService references;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();
    private static final AuditActor SETUP = new AuditActor.SystemAuditActor("t2-logo-tests");
    private static String platformAdmin;

    /** Una organización verificada con una convocatoria pública. */
    record World(String org, String representative, String admin, String employee, String publicCode) {}

    @BeforeEach
    void resetSpies() {
        reset(storage, uploads, references);
        if (platformAdmin == null) {
            String email = UUID.randomUUID() + "@platform.t2.test";
            accounts.createAccount(new Email(email), "Pass123!Pass123!");
            platformAdmin = bootstrap.bootstrap(email).value();
        }
    }

    private String account() {
        return accounts.createAccount(new Email(UUID.randomUUID() + "@t2.test"), "Pass123!Pass123!").getAccountId().value();
    }

    private World world() {
        Account rep = accounts.createAccount(new Email(UUID.randomUUID() + "@t2.test"), "Pass123!Pass123!");
        Organization o = organizations.createOrganization(SETUP, OrganizationType.FOUNDATION, rep.getAccountId(),
                "Fundación Logo");
        OrganizationId id = o.getOrganizationId();
        String admin = account();
        employees.addEmployee(SETUP, id, new AccountId(admin));
        administrators.assignAdministrator(SETUP, id, new AccountId(admin));
        String employee = account();
        employees.addEmployee(SETUP, id, new AccountId(employee));
        verifications.verifyOrganization(principals.resolvePrincipal(platformAdmin), id);
        ConvocatoriaConfiguration config = new ConvocatoriaConfiguration(EnumSet.of(DonationType.MONETARY),
                Set.of(PaymentMethod.GATEWAY), "COP", 1_000_000L, TargetPolicy.FLEXIBLE, null);
        String publicCode = lifecycle.createConvocatoria(new CreateConvocatoriaCommand(UUID.randomUUID().toString(), admin,
                id.value(), "Convocatoria con logo", null, Visibility.PUBLIC, Instant.now().plus(Duration.ofDays(1)),
                Instant.now().plus(Duration.ofDays(60)), config)).publicCode();
        return new World(id.value(), rep.getAccountId().value(), admin, employee, publicCode);
    }

    // ── HTTP ──

    private HttpResponse<byte[]> send(String method, String path, String account, HttpRequest.BodyPublisher body,
                                      String contentType) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(20))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : body);
        if (account != null) request.header("Authorization", "Bearer " + tokens.issue(account));
        if (contentType != null) request.header("Content-Type", contentType);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private static final String BOUNDARY = "t2-logo-boundary-7f3a";

    private static byte[] multipart(byte[] file, String filename, String fileContentType) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + filename
                + "\"\r\nContent-Type: " + fileContentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.writeBytes(file);
        out.writeBytes(("\r\n--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    private HttpResponse<byte[]> put(World w, String account, byte[] file, String filename) throws Exception {
        return putTo(w.org(), account, file, filename);
    }

    private HttpResponse<byte[]> putTo(String org, String account, byte[] file, String filename) throws Exception {
        return send("PUT", "/api/v1/organizations/" + org + "/logo", account,
                HttpRequest.BodyPublishers.ofByteArray(multipart(file, filename, "image/png")),
                "multipart/form-data; boundary=" + BOUNDARY);
    }

    private HttpResponse<byte[]> publicLogo(String publicCode) throws Exception {
        return send("GET", "/api/v1/public/campaigns/" + publicCode + "/organization-logo", null, null, null);
    }

    private HttpResponse<byte[]> memberLogo(String org, String account) throws Exception {
        return send("GET", "/api/v1/organizations/" + org + "/logo", account, null, null);
    }

    private JsonNode body(HttpResponse<byte[]> r) throws IOException {
        return json.readTree(r.body());
    }

    private static String text(HttpResponse<byte[]> r) {
        return new String(r.body(), StandardCharsets.UTF_8);
    }

    // ── Estado ──

    private String logoKeyOf(String org) {
        Document d = mongoTemplate.getCollection("organizations").find(new Document("_id", org)).first();
        return d == null ? null : d.getString("logoObjectKey");
    }

    private List<Document> mediaOf(String org) {
        return mongoTemplate.getCollection("media_objects").find(new Document("organizationId", org)).into(new java.util.ArrayList<>());
    }

    private Document mediaByKey(String key) {
        return mongoTemplate.getCollection("media_objects").find(new Document("objectKey", key)).first();
    }

    private String uploadLogo(World w) throws Exception {
        HttpResponse<byte[]> r = put(w, w.representative(), TestImages.png(40, 30), "logo.png");
        assertThat(r.statusCode()).as(text(r)).isEqualTo(200);
        return logoKeyOf(w.org());
    }

    // ── T2-1 (criterio 3): formato por contenido ──

    @Test
    void t2_1_aJpgThatIsNotAnImage_is415_withNoMetadataNorReference() throws Exception {
        World w = world();

        HttpResponse<byte[]> r = put(w, w.representative(), "not an image at all".getBytes(StandardCharsets.UTF_8), "logo.jpg");

        assertThat(r.statusCode()).isEqualTo(415);
        assertThat(body(r).get("title").asText()).isEqualTo("UnsupportedImageFormat");
        assertThat(mediaOf(w.org())).isEmpty();
        assertThat(logoKeyOf(w.org())).isNull();
    }

    // ── T2-2 (criterio 4): dimensiones excesivas, pequeña en bytes ──

    @Test
    void t2_2_aSmallImageWithHugeDimensions_is413_andNothingIsStored() throws Exception {
        World w = world();
        byte[] bomb = TestImages.withPngDimensions(TestImages.png(8, 8), 9000, 9000);

        HttpResponse<byte[]> r = put(w, w.representative(), bomb, "logo.png");

        assertThat(r.statusCode()).isEqualTo(413);
        assertThat(body(r).get("title").asText()).isEqualTo("ImageTooLarge");
        verify(storage, never()).put(anyString(), any(), anyString());
        assertThat(logoKeyOf(w.org())).isNull();
    }

    // ── T2-3 (criterio 5; P-1): 2 MB en el controlador, 5 MB en Spring ──

    @Test
    void t2_3_moreThan2MB_is413FromTheController_withoutCallingTheUploadService() throws Exception {
        World w = world();

        HttpResponse<byte[]> r = put(w, w.representative(), new byte[MediaPurpose.ORGANIZATION_LOGO.maxBytes() + 1], "logo.png");

        assertThat(r.statusCode()).isEqualTo(413);
        assertThat(body(r).get("title").asText()).isEqualTo("ImageTooLarge");
        verify(uploads, never()).store(any(), any(), any(), any(), any());
    }

    @Test
    void t2_3_moreThan5MB_is413FromTheSpringLimit() throws Exception {
        World w = world();

        HttpResponse<byte[]> r = put(w, w.representative(), new byte[5 * 1024 * 1024 + 1], "logo.png");

        assertThat(r.statusCode()).isEqualTo(413);
        assertThat(body(r).get("title").asText()).isEqualTo("ImageTooLarge");
        verify(uploads, never()).store(any(), any(), any(), any(), any());
    }

    // ── T2-4 (F8; DD-01): permiso antes de leer los bytes ──

    @Test
    void t2_4_withoutPermission_theSame403_andNothingReachesTheProvider() throws Exception {
        World w = world();
        World other = world();
        byte[] logo = TestImages.png(20, 20);

        HttpResponse<byte[]> asEmployee = put(w, w.employee(), logo, "logo.png");
        HttpResponse<byte[]> fromAnotherOrganization = put(w, other.admin(), logo, "logo.png");
        HttpResponse<byte[]> unknownOrganization = putTo("no-such-org", w.admin(), logo, "logo.png");
        HttpResponse<byte[]> tooLargeAsEmployee = put(w, w.employee(), new byte[MediaPurpose.ORGANIZATION_LOGO.maxBytes() + 1], "logo.png");
        HttpResponse<byte[]> deleteAsEmployee = send("DELETE", "/api/v1/organizations/" + w.org() + "/logo", w.employee(), null, null);

        Map<String, HttpResponse<byte[]>> responses = Map.of("asEmployee", asEmployee, "fromAnotherOrganization",
                fromAnotherOrganization, "unknownOrganization", unknownOrganization, "tooLargeAsEmployee",
                tooLargeAsEmployee, "deleteAsEmployee", deleteAsEmployee);
        for (Map.Entry<String, HttpResponse<byte[]>> e : responses.entrySet()) {
            HttpResponse<byte[]> r = e.getValue();
            assertThat(r.statusCode()).as(e.getKey()).isEqualTo(403);
            assertThat(text(r)).isEqualTo(text(asEmployee));
        }
        verify(storage, never()).put(anyString(), any(), anyString());
        verify(uploads, never()).store(any(), any(), any(), any(), any());
        assertThat(logoKeyOf(w.org())).isNull();
    }

    // ── T2-5 (criterios 12 y 12b): lectura pública ──

    @Test
    void t2_5_thePublicLogo_isReadWithoutJwt_withCacheHeadersAndAQuotedETag() throws Exception {
        World w = world();
        HttpResponse<byte[]> uploaded = put(w, w.representative(), TestImages.withPngMetadataChunks(TestImages.png(40, 30)), "logo.png");
        assertThat(uploaded.statusCode()).isEqualTo(200);
        assertThat(body(uploaded).get("logoUrl").asText()).isEqualTo("/api/v1/organizations/" + w.org() + "/logo");
        String key = logoKeyOf(w.org());
        byte[] stored = storage.get(key).orElseThrow();

        HttpResponse<byte[]> r = publicLogo(w.publicCode());

        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).isEqualTo(stored);
        assertThat(TestImages.contains(r.body(), TestImages.SECRET_MARK)).isFalse();
        assertThat(r.headers().firstValue("Cache-Control")).contains("public, max-age=3600");
        assertThat(r.headers().firstValue("ETag")).contains("\"" + TestImages.sha256Hex(stored) + "\"");
        assertThat(r.headers().firstValue("Content-Type")).contains("image/png");
    }

    // ── T2-6 (criterio 12b): ninguna respuesta pública lleva el organizationId ──

    @Test
    void t2_6_noPublicResponse_containsTheOrganizationId() throws Exception {
        World w = world();
        uploadLogo(w);

        HttpResponse<byte[]> logo = publicLogo(w.publicCode());
        HttpResponse<byte[]> detail = send("GET", "/api/v1/public/campaigns/" + w.publicCode(), null, null, null);
        HttpResponse<byte[]> discovery = send("GET", "/api/v1/public/campaigns", null, null, null);

        assertThat(discoveryItem(w.publicCode()).toString()).doesNotContain(w.org());
        for (HttpResponse<byte[]> r : List.of(logo, detail, discovery)) {
            assertThat(r.statusCode()).isEqualTo(200);
            assertThat(TestImages.contains(r.body(), w.org())).isFalse();
            assertThat(r.headers().map().toString()).doesNotContain(w.org());
        }
    }

    // ── T2-7 (criterio 12c): lectura autenticada ──

    @Test
    void t2_7_theAuthenticatedLogo_isForMembersAndPlatformAdministrators() throws Exception {
        World w = world();
        World other = world();
        uploadLogo(w);
        byte[] stored = storage.get(logoKeyOf(w.org())).orElseThrow();

        HttpResponse<byte[]> anonymous = memberLogo(w.org(), null);
        HttpResponse<byte[]> fromAnotherOrganization = memberLogo(w.org(), other.admin());
        HttpResponse<byte[]> loner = memberLogo(w.org(), account());
        HttpResponse<byte[]> asEmployee = memberLogo(w.org(), w.employee());
        HttpResponse<byte[]> asPlatformAdmin = memberLogo(w.org(), platformAdmin);

        assertThat(anonymous.statusCode()).isEqualTo(401);
        assertThat(fromAnotherOrganization.statusCode()).isEqualTo(403);
        assertThat(text(loner)).isEqualTo(text(fromAnotherOrganization));
        assertThat(asEmployee.statusCode()).isEqualTo(200);
        assertThat(asEmployee.body()).isEqualTo(stored);
        assertThat(asEmployee.headers().firstValue("Cache-Control").orElseThrow()).contains("private").contains("no-store")
                .doesNotContain("public");
        assertThat(asPlatformAdmin.statusCode()).isEqualTo(200);
        assertThat(asPlatformAdmin.body()).isEqualTo(stored);
    }

    // ── T2-8 (criterio 14; Enmienda 1): 404, nunca 410, y siempre el mismo ──

    @Test
    void t2_8_everyMissingLogo_isTheSame404_neverA410() throws Exception {
        World withoutLogo = world();
        World deleted = world();
        World missingBytes = world();
        String deletedKey = uploadLogo(deleted);
        mongoTemplate.getCollection("media_objects").updateOne(new Document("objectKey", deletedKey),
                new Document("$set", new Document("deletedAt", java.util.Date.from(Instant.now()))
                        .append("deletedBy", "acct-test").append("reason", "REMOVED")));
        storage.delete(uploadLogo(missingBytes));

        List<HttpResponse<byte[]>> responses = List.of(
                publicLogo("PX-NOPE-0000"),
                publicLogo(withoutLogo.publicCode()),
                publicLogo(deleted.publicCode()),
                publicLogo(missingBytes.publicCode()),
                memberLogo(withoutLogo.org(), withoutLogo.employee()),
                memberLogo(deleted.org(), deleted.employee()),
                memberLogo(missingBytes.org(), missingBytes.employee()),
                memberLogo("no-such-org", platformAdmin));

        String first = text(responses.get(0));
        for (HttpResponse<byte[]> r : responses) {
            assertThat(r.statusCode()).isEqualTo(404);
            assertThat(text(r)).isEqualTo(first);
        }
        assertThat(body(responses.get(0)).get("title").asText()).isEqualTo("NotFound");
    }

    // ── T2-9 (criterio 16; F10): el anterior se borra después de confirmar ──

    @Test
    void t2_9_replacing_deletesThePreviousFileAfterConfirming_andMarksItsMetadata() throws Exception {
        World w = world();
        String first = uploadLogo(w);

        HttpResponse<byte[]> r = put(w, w.admin(), TestImages.png(50, 50), "logo2.png");

        assertThat(r.statusCode()).isEqualTo(200);
        String second = logoKeyOf(w.org());
        assertThat(second).isNotEqualTo(first);
        InOrder order = inOrder(references, storage);
        order.verify(references).replace(any(), eq(w.org()), eq(second));
        order.verify(storage).delete(first);
        assertThat(storage.get(first)).isEmpty();
        Document old = mediaByKey(first);
        assertThat(old.get("deletedAt")).isNotNull();
        assertThat(old.getString("deletedBy")).isEqualTo(w.admin());
        assertThat(old.getString("reason")).isEqualTo("REPLACED");
        assertThat(mediaByKey(second).get("deletedAt")).isNull();
    }

    @Test
    void t2_9_ifDeletingThePreviousFileFails_theResponseIsStill200_andTheOrphanIsLogged(CapturedOutput output) throws Exception {
        World w = world();
        String first = uploadLogo(w);
        doThrow(new com.traceability.contracts.media.MediaStorageUnavailableException("simulated")).when(storage).delete(first);

        HttpResponse<byte[]> r = put(w, w.admin(), TestImages.png(50, 50), "logo2.png");

        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(logoKeyOf(w.org())).isNotEqualTo(first);
        assertThat(output.getAll()).contains("media.orphan objectKey=" + first);
    }

    // ── T2-10 (F2; F10): si falla identity, el archivo nuevo queda huérfano y nada más cambia ──

    @Test
    void t2_10_ifTheIdentityWriteFails_theNewFileIsAnOrphan_andTheReferenceAndPreviousFileStay(CapturedOutput output)
            throws Exception {
        World w = world();
        String first = uploadLogo(w);
        doThrow(new IllegalStateException("simulated identity failure"))
                .when(references).replace(any(), eq(w.org()), anyString());

        HttpResponse<byte[]> r = put(w, w.admin(), TestImages.png(50, 50), "logo2.png");

        assertThat(r.statusCode()).isEqualTo(500);
        assertThat(logoKeyOf(w.org())).isEqualTo(first);
        assertThat(storage.get(first)).isPresent();
        assertThat(mediaByKey(first).get("deletedAt")).isNull();
        String orphan = mediaOf(w.org()).stream().map(d -> d.getString("objectKey")).filter(k -> !k.equals(first))
                .findFirst().orElseThrow();
        assertThat(output.getAll()).contains("media.orphan objectKey=" + orphan);
        verify(storage, never()).delete(anyString());
    }

    // ── T2-11 (criterio 15; D-T2-3): el proveedor falla ──

    @Test
    void t2_11_providerFailure_is503_withNoMetadataNorReference() throws Exception {
        World w = world();
        storage.failNextPuts(1);

        HttpResponse<byte[]> r = put(w, w.representative(), TestImages.png(20, 20), "logo.png");

        assertThat(r.statusCode()).isEqualTo(503);
        assertThat(body(r).get("title").asText()).isEqualTo("MediaStorageUnavailable");
        assertThat(mediaOf(w.org())).isEmpty();
        assertThat(logoKeyOf(w.org())).isNull();
    }

    // ── T2-12 (F8; F10): quitar ──

    @Test
    void t2_12_delete_removesTheReferenceThenTheFile_andTheReadsGive404() throws Exception {
        World w = world();
        String key = uploadLogo(w);

        HttpResponse<byte[]> r = send("DELETE", "/api/v1/organizations/" + w.org() + "/logo", w.representative(), null, null);

        assertThat(r.statusCode()).isEqualTo(204);
        assertThat(logoKeyOf(w.org())).isNull();
        InOrder order = inOrder(references, storage);
        order.verify(references).remove(any(), eq(w.org()));
        order.verify(storage).delete(key);
        assertThat(storage.get(key)).isEmpty();
        assertThat(mediaByKey(key).getString("reason")).isEqualTo("REMOVED");
        assertThat(publicLogo(w.publicCode()).statusCode()).isEqualTo(404);
        assertThat(memberLogo(w.org(), w.employee()).statusCode()).isEqualTo(404);
    }

    @Test
    void t2_12_deleteWithoutLogo_is204_withoutTouchingTheProvider() throws Exception {
        World w = world();

        HttpResponse<byte[]> r = send("DELETE", "/api/v1/organizations/" + w.org() + "/logo", w.admin(), null, null);

        assertThat(r.statusCode()).isEqualTo(204);
        verify(storage, never()).delete(anyString());
    }

    // ── T2-13 (F10; §4.1): dos PUT a la vez ──

    @Test
    void t2_13_twoSimultaneousUploads_leaveOneReferenced_andTheOtherNeverReferenced() throws Exception {
        World w = world();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<HttpResponse<byte[]>> a = executor.submit(() -> put(w, w.representative(), TestImages.png(30, 30), "a.png"));
            Future<HttpResponse<byte[]>> b = executor.submit(() -> put(w, w.admin(), TestImages.png(31, 31), "b.png"));
            assertThat(a.get().statusCode()).isEqualTo(200);
            assertThat(b.get().statusCode()).isEqualTo(200);
        } finally {
            executor.shutdownNow();
        }

        String finalKey = logoKeyOf(w.org());
        List<Document> media = mediaOf(w.org());
        assertThat(media).hasSize(2);
        assertThat(storage.get(finalKey)).isPresent();
        Document other = media.stream().filter(d -> !d.getString("objectKey").equals(finalKey)).findFirst().orElseThrow();
        assertThat(storage.get(other.getString("objectKey"))).isEmpty();
        assertThat(other.getString("reason")).isEqualTo("REPLACED");
    }

    // ── T2-15 (§2): campos de URL en las lecturas ──

    @Test
    void t2_15_theReadsCarryTheLogoUrl_onlyWhenThereIsALogo() throws Exception {
        World w = world();
        String detailPath = "/api/v1/public/campaigns/" + w.publicCode();
        String s18Path = "/api/v1/organizations/" + w.org();

        assertThat(body(send("GET", detailPath, null, null, null)).has("organizationLogoUrl")).isFalse();
        assertThat(discoveryItem(w.publicCode()).has("organizationLogoUrl")).isFalse();
        assertThat(body(send("GET", s18Path, w.representative(), null, null)).has("logoUrl")).isFalse();

        uploadLogo(w);

        String publicUrl = "/api/v1/public/campaigns/" + w.publicCode() + "/organization-logo";
        assertThat(body(send("GET", detailPath, null, null, null)).get("organizationLogoUrl").asText()).isEqualTo(publicUrl);
        assertThat(discoveryItem(w.publicCode()).get("organizationLogoUrl").asText()).isEqualTo(publicUrl);
        assertThat(body(send("GET", s18Path, w.representative(), null, null)).get("logoUrl").asText())
                .isEqualTo("/api/v1/organizations/" + w.org() + "/logo");
        // el nombre y la verificación siguen saliendo de la misma lectura (P-T2-7)
        JsonNode detail = body(send("GET", detailPath, null, null, null));
        assertThat(detail.get("organizationName").asText()).isEqualTo("Fundación Logo");
        assertThat(detail.get("organizationVerified").asBoolean()).isTrue();
    }

    private JsonNode discoveryItem(String publicCode) throws Exception {
        String cursor = null;
        for (int page = 0; page < 50; page++) {
            JsonNode r = body(send("GET", "/api/v1/public/campaigns" + (cursor == null ? "" : "?cursor=" + cursor), null, null, null));
            for (JsonNode item : r.get("items")) {
                if (item.get("publicCode").asText().equals(publicCode)) return item;
            }
            if (!r.has("nextCursor")) break;
            cursor = r.get("nextCursor").asText();
        }
        throw new AssertionError("convocatoria no encontrada en el descubrimiento");
    }
}
