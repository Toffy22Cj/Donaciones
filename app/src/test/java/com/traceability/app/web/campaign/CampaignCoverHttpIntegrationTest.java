package com.traceability.app.web.campaign;

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
import com.traceability.convocatoria.application.command.CreateConvocatoriaResult;
import com.traceability.convocatoria.application.service.ConvocatoriaCoverReferenceService;
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
 * ADR-051, Tarea 3 (portada de la convocatoria; plan de cierre §T3, T3-1 a T3-9) contra Tomcat real, con
 * {@code identity}, {@code convocatoria} y {@code media_objects} reales y el proveedor en memoria. Calca el test de la
 * Tarea 2 (logo). Subir y quitar es solo del {@code ADMINISTRATOR} de la organización (C1 (a)); la lectura es pública
 * por el código de la convocatoria.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = TraceabilityApplication.class)
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
@Import(CampaignCoverHttpIntegrationTest.InMemoryStorage.class)
@TestPropertySource(properties = {
    "crypto.anchor.poll.delay=9999999",
    "crypto.anchor.submit.delay=9999999",
    "crypto.anchor.stuck-monitor.delay=9999999",
    "crypto.web3j.private-key=0x1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef",
    "crypto.web3j.node-url=http://dummy-node",
    "spring.ai.openai.api-key=dummy-api-key"
})
class CampaignCoverHttpIntegrationTest {

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
    @MockitoSpyBean private ConvocatoriaCoverReferenceService references;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();
    private static final AuditActor SETUP = new AuditActor.SystemAuditActor("t3-cover-tests");
    private static String platformAdmin;

    /** Una organización verificada con una convocatoria pública. */
    record World(String org, String representative, String admin, String employee, String campaignRef, String publicCode) {}

    @BeforeEach
    void resetSpies() {
        reset(storage, uploads, references);
        if (platformAdmin == null) {
            String email = UUID.randomUUID() + "@platform.t3.test";
            accounts.createAccount(new Email(email), "Pass123!Pass123!");
            platformAdmin = bootstrap.bootstrap(email).value();
        }
    }

    private String account() {
        return accounts.createAccount(new Email(UUID.randomUUID() + "@t3.test"), "Pass123!Pass123!").getAccountId().value();
    }

    private World world() {
        Account rep = accounts.createAccount(new Email(UUID.randomUUID() + "@t3.test"), "Pass123!Pass123!");
        Organization o = organizations.createOrganization(SETUP, OrganizationType.FOUNDATION, rep.getAccountId(),
                "Fundación Portada");
        OrganizationId id = o.getOrganizationId();
        String admin = account();
        employees.addEmployee(SETUP, id, new AccountId(admin));
        administrators.assignAdministrator(SETUP, id, new AccountId(admin));
        String employee = account();
        employees.addEmployee(SETUP, id, new AccountId(employee));
        verifications.verifyOrganization(principals.resolvePrincipal(platformAdmin), id);
        ConvocatoriaConfiguration config = new ConvocatoriaConfiguration(EnumSet.of(DonationType.MONETARY),
                Set.of(PaymentMethod.GATEWAY), "COP", 1_000_000L, TargetPolicy.FLEXIBLE, null);
        CreateConvocatoriaResult created = lifecycle.createConvocatoria(new CreateConvocatoriaCommand(
                UUID.randomUUID().toString(), admin, id.value(), "Convocatoria con portada", null, Visibility.PUBLIC,
                Instant.now().plus(Duration.ofDays(1)), Instant.now().plus(Duration.ofDays(60)), config));
        return new World(id.value(), rep.getAccountId().value(), admin, employee, created.campaignRef(), created.publicCode());
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

    private static final String BOUNDARY = "t3-cover-boundary-2c9d";

    private static byte[] multipart(byte[] file, String filename) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + filename
                + "\"\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.writeBytes(file);
        out.writeBytes(("\r\n--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    private static String coverPath(String org, String campaignRef) {
        return "/api/v1/organizations/" + org + "/campaigns/" + campaignRef + "/cover";
    }

    private HttpResponse<byte[]> put(World w, String account, byte[] file, String filename) throws Exception {
        return putTo(w.org(), w.campaignRef(), account, file, filename);
    }

    private HttpResponse<byte[]> putTo(String org, String campaignRef, String account, byte[] file, String filename)
            throws Exception {
        return send("PUT", coverPath(org, campaignRef), account,
                HttpRequest.BodyPublishers.ofByteArray(multipart(file, filename)), "multipart/form-data; boundary=" + BOUNDARY);
    }

    private HttpResponse<byte[]> delete(World w, String account) throws Exception {
        return send("DELETE", coverPath(w.org(), w.campaignRef()), account, null, null);
    }

    private HttpResponse<byte[]> publicCover(String publicCode) throws Exception {
        return send("GET", "/api/v1/public/campaigns/" + publicCode + "/cover", null, null, null);
    }

    private JsonNode body(HttpResponse<byte[]> r) throws IOException {
        return json.readTree(r.body());
    }

    private static String text(HttpResponse<byte[]> r) {
        return new String(r.body(), StandardCharsets.UTF_8);
    }

    // ── Estado ──

    private String coverKeyOf(String campaignRef) {
        Document d = mongoTemplate.getCollection("convocatorias").find(new Document("_id", campaignRef)).first();
        return d == null ? null : d.getString("coverObjectKey");
    }

    private List<Document> mediaOf(String org) {
        return mongoTemplate.getCollection("media_objects").find(new Document("organizationId", org)
                .append("purpose", "CAMPAIGN_COVER")).into(new java.util.ArrayList<>());
    }

    private Document mediaByKey(String key) {
        return mongoTemplate.getCollection("media_objects").find(new Document("objectKey", key)).first();
    }

    private String uploadCover(World w) throws Exception {
        HttpResponse<byte[]> r = put(w, w.admin(), TestImages.png(40, 30), "cover.png");
        assertThat(r.statusCode()).as(text(r)).isEqualTo(200);
        return coverKeyOf(w.campaignRef());
    }

    // ── T3-1: el ADMINISTRATOR sube un PNG; se guarda limpio ──

    @Test
    void t3_1_theAdministratorUploads_200WithThePublicUrl_andTheStoredBytesHaveNoMetadata() throws Exception {
        World w = world();

        HttpResponse<byte[]> r = put(w, w.admin(), TestImages.withPngMetadataChunks(TestImages.png(40, 30)), "cover.png");

        assertThat(r.statusCode()).as(text(r)).isEqualTo(200);
        assertThat(body(r).get("coverImageUrl").asText()).isEqualTo("/api/v1/public/campaigns/" + w.publicCode() + "/cover");
        String key = coverKeyOf(w.campaignRef());
        assertThat(key).isNotNull();
        assertThat(TestImages.contains(storage.get(key).orElseThrow(), TestImages.SECRET_MARK)).isFalse();
        Document meta = mediaByKey(key);
        assertThat(meta.getString("purpose")).isEqualTo("CAMPAIGN_COVER");
        assertThat(meta.getString("ownerRef")).isEqualTo(w.campaignRef());
        assertThat(meta.getString("uploadedBy")).isEqualTo(w.admin());
    }

    // ── T3-2 y T3-9 (C1 (a); F8; DD-01): solo ADMINISTRATOR, el permiso antes de leer los bytes ──

    @Test
    void t3_2_t3_9_withoutPermission_theSame403_andNothingReachesTheProvider() throws Exception {
        World w = world();
        World other = world();
        byte[] cover = TestImages.png(20, 20);

        HttpResponse<byte[]> asRepresentative = put(w, w.representative(), cover, "cover.png");
        Map<String, HttpResponse<byte[]>> responses = Map.of(
                "asRepresentative", asRepresentative,
                "asEmployee", put(w, w.employee(), cover, "cover.png"),
                "fromAnotherOrganization", put(w, other.admin(), cover, "cover.png"),
                "aCampaignOfAnotherOrganization", putTo(w.org(), other.campaignRef(), w.admin(), cover, "cover.png"),
                "unknownCampaign", putTo(w.org(), UUID.randomUUID().toString(), w.admin(), cover, "cover.png"),
                "tooLargeAsRepresentative", put(w, w.representative(), new byte[MediaPurpose.CAMPAIGN_COVER.maxBytes() + 1], "cover.png"),
                "deleteAsRepresentative", delete(w, w.representative()));

        for (Map.Entry<String, HttpResponse<byte[]>> e : responses.entrySet()) {
            assertThat(e.getValue().statusCode()).as(e.getKey()).isEqualTo(403);
            assertThat(text(e.getValue())).as(e.getKey()).isEqualTo(text(asRepresentative));
        }
        verify(storage, never()).put(anyString(), any(), anyString());
        verify(uploads, never()).store(any(), any(), any(), any(), any());
        assertThat(coverKeyOf(w.campaignRef())).isNull();
        assertThat(coverKeyOf(other.campaignRef())).isNull();
        assertThat(send("PUT", coverPath(w.org(), w.campaignRef()), null, HttpRequest.BodyPublishers.ofByteArray(
                multipart(cover, "cover.png")), "multipart/form-data; boundary=" + BOUNDARY).statusCode()).isEqualTo(401);
    }

    // ── T3-3 (criterios 3 a 5): formato y tamaño ──

    @Test
    void t3_3_moreThan2MB_is413_andANonImage_is415() throws Exception {
        World w = world();

        HttpResponse<byte[]> tooLarge = put(w, w.admin(), new byte[MediaPurpose.CAMPAIGN_COVER.maxBytes() + 1], "cover.png");

        assertThat(tooLarge.statusCode()).isEqualTo(413);
        assertThat(body(tooLarge).get("title").asText()).isEqualTo("ImageTooLarge");
        // P-1, como el logo: el límite de 2 MB lo da el controlador, sin llegar al servicio de subida
        verify(uploads, never()).store(eq(MediaPurpose.CAMPAIGN_COVER), any(), any(), any(), any());

        HttpResponse<byte[]> notAnImage = put(w, w.admin(), "not an image".getBytes(StandardCharsets.UTF_8), "cover.jpg");
        assertThat(notAnImage.statusCode()).isEqualTo(415);
        assertThat(body(notAnImage).get("title").asText()).isEqualTo("UnsupportedImageFormat");
        assertThat(mediaOf(w.org())).isEmpty();
        assertThat(coverKeyOf(w.campaignRef())).isNull();
    }

    // ── T3-4 (F10; criterio 16): reemplazar borra la anterior después de confirmar ──

    @Test
    void t3_4_replacing_deletesThePreviousFileAfterConfirming_andMarksItsMetadata() throws Exception {
        World w = world();
        String first = uploadCover(w);

        HttpResponse<byte[]> r = put(w, w.admin(), TestImages.png(50, 50), "cover2.png");

        assertThat(r.statusCode()).isEqualTo(200);
        String second = coverKeyOf(w.campaignRef());
        assertThat(second).isNotEqualTo(first);
        InOrder order = inOrder(references, storage);
        order.verify(references).replace(eq(w.admin()), eq(w.org()), eq(w.campaignRef()), eq(second));
        order.verify(storage).delete(first);
        assertThat(storage.get(first)).isEmpty();
        Document old = mediaByKey(first);
        assertThat(old.get("deletedAt")).isNotNull();
        assertThat(old.getString("deletedBy")).isEqualTo(w.admin());
        assertThat(old.getString("reason")).isEqualTo("REPLACED");
        assertThat(mediaByKey(second).get("deletedAt")).isNull();
    }

    @Test
    void t3_4_ifDeletingThePreviousFileFails_theResponseIsStill200_andTheOrphanIsLogged(CapturedOutput output) throws Exception {
        World w = world();
        String first = uploadCover(w);
        doThrow(new com.traceability.contracts.media.MediaStorageUnavailableException("simulated")).when(storage).delete(first);

        HttpResponse<byte[]> r = put(w, w.admin(), TestImages.png(50, 50), "cover2.png");

        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(coverKeyOf(w.campaignRef())).isNotEqualTo(first);
        assertThat(output.getAll()).contains("media.orphan objectKey=" + first);
    }

    // ── T3-5 (F8; F10): quitar ──

    @Test
    void t3_5_delete_removesTheReferenceThenTheFile_andThePublicReadGives404() throws Exception {
        World w = world();
        String key = uploadCover(w);

        HttpResponse<byte[]> r = delete(w, w.admin());

        assertThat(r.statusCode()).isEqualTo(204);
        assertThat(coverKeyOf(w.campaignRef())).isNull();
        InOrder order = inOrder(references, storage);
        order.verify(references).remove(eq(w.admin()), eq(w.org()), eq(w.campaignRef()));
        order.verify(storage).delete(key);
        assertThat(mediaByKey(key).getString("reason")).isEqualTo("REMOVED");
        assertThat(publicCover(w.publicCode()).statusCode()).isEqualTo(404);
    }

    @Test
    void t3_5_deleteWithoutCover_is204_withoutTouchingTheProvider() throws Exception {
        World w = world();

        HttpResponse<byte[]> r = delete(w, w.admin());

        assertThat(r.statusCode()).isEqualTo(204);
        verify(storage, never()).delete(anyString());
    }

    // ── T3-6 (F6; criterio 12): lectura pública ──

    @Test
    void t3_6_thePublicCover_isReadWithoutJwt_withCacheHeadersAndAQuotedETag() throws Exception {
        World w = world();
        String key = uploadCover(w);
        byte[] stored = storage.get(key).orElseThrow();

        HttpResponse<byte[]> r = publicCover(w.publicCode());

        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).isEqualTo(stored);
        assertThat(r.headers().firstValue("Cache-Control")).contains("public, max-age=3600");
        assertThat(r.headers().firstValue("ETag")).contains("\"" + TestImages.sha256Hex(stored) + "\"");
        assertThat(r.headers().firstValue("Content-Type")).contains("image/png");
    }

    @Test
    void t3_6_everyMissingCover_isTheSame404() throws Exception {
        World withoutCover = world();
        World deleted = world();
        World missingBytes = world();
        String deletedKey = uploadCover(deleted);
        mongoTemplate.getCollection("media_objects").updateOne(new Document("objectKey", deletedKey),
                new Document("$set", new Document("deletedAt", java.util.Date.from(Instant.now()))
                        .append("deletedBy", "acct-test").append("reason", "REMOVED")));
        storage.delete(uploadCover(missingBytes));

        List<HttpResponse<byte[]>> responses = List.of(publicCover("PX-NOPE-0000"), publicCover(withoutCover.publicCode()),
                publicCover(deleted.publicCode()), publicCover(missingBytes.publicCode()));

        String first = text(responses.get(0));
        for (HttpResponse<byte[]> r : responses) {
            assertThat(r.statusCode()).isEqualTo(404);
            assertThat(text(r)).isEqualTo(first);
        }
        assertThat(body(responses.get(0)).get("title").asText()).isEqualTo("NotFound");
    }

    // ── T3-7 y T3-8 (F6): coverImageUrl solo si hay portada; nada interno en lo público ──

    @Test
    void t3_7_t3_8_theReadsCarryTheCoverUrl_onlyWhenThereIsACover_andNoInternalIds() throws Exception {
        World w = world();
        String detailPath = "/api/v1/public/campaigns/" + w.publicCode();

        // T3-8: una convocatoria sin el campo se lee sin portada
        assertThat(body(send("GET", detailPath, null, null, null)).has("coverImageUrl")).isFalse();
        assertThat(discoveryItem(w.publicCode()).has("coverImageUrl")).isFalse();

        uploadCover(w);

        String url = "/api/v1/public/campaigns/" + w.publicCode() + "/cover";
        HttpResponse<byte[]> detail = send("GET", detailPath, null, null, null);
        assertThat(body(detail).get("coverImageUrl").asText()).isEqualTo(url);
        assertThat(discoveryItem(w.publicCode()).get("coverImageUrl").asText()).isEqualTo(url);
        HttpResponse<byte[]> cover = publicCover(w.publicCode());
        HttpResponse<byte[]> discovery = send("GET", "/api/v1/public/campaigns", null, null, null);
        for (HttpResponse<byte[]> r : List.of(detail, cover, discovery)) {
            assertThat(TestImages.contains(r.body(), w.org())).isFalse();
            assertThat(TestImages.contains(r.body(), w.campaignRef())).isFalse();
            assertThat(TestImages.contains(r.body(), coverKeyOf(w.campaignRef()))).isFalse();
            assertThat(r.headers().map().toString()).doesNotContain(w.org()).doesNotContain(w.campaignRef());
        }
    }

    // ── ADR-051, Tarea 5 (T5-6; F7): la plataforma retira una portada ──

    private HttpResponse<byte[]> platformRemove(String account, String org, String campaignRef) throws Exception {
        String body = "{\"purpose\":\"CAMPAIGN_COVER\",\"ref\":\"" + campaignRef + "\",\"reason\":\"portada con personas\"}";
        return send("POST", "/api/v1/platform/organizations/" + org + "/media/remove", account,
                HttpRequest.BodyPublishers.ofString(body), "application/json");
    }

    @Test
    void t5_6_thePlatformRemovesACover_theViewsLoseTheUrl_andThePublicReadIs404() throws Exception {
        World w = world();
        String key = uploadCover(w);

        HttpResponse<byte[]> asAdministrator = platformRemove(w.admin(), w.org(), w.campaignRef());
        assertThat(asAdministrator.statusCode()).isEqualTo(403);
        assertThat(coverKeyOf(w.campaignRef())).isEqualTo(key);

        HttpResponse<byte[]> r = platformRemove(platformAdmin, w.org(), w.campaignRef());

        assertThat(r.statusCode()).as(text(r)).isEqualTo(204);
        assertThat(coverKeyOf(w.campaignRef())).isNull();
        assertThat(storage.get(key)).isEmpty();
        Document meta = mediaByKey(key);
        assertThat(meta.get("deletedAt")).isNotNull();
        assertThat(meta.getString("deletedBy")).isEqualTo(platformAdmin);
        assertThat(meta.getString("reason")).isEqualTo("portada con personas");
        assertThat(publicCover(w.publicCode()).statusCode()).isEqualTo(404);
        assertThat(body(send("GET", "/api/v1/public/campaigns/" + w.publicCode(), null, null, null)).has("coverImageUrl")).isFalse();
        // P-T5-3: otra vez → 204
        assertThat(platformRemove(platformAdmin, w.org(), w.campaignRef()).statusCode()).isEqualTo(204);
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
