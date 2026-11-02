package com.traceability.app.web.organization;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.f4b6a3.ulid.UlidCreator;
import com.traceability.app.TraceabilityApplication;
import com.traceability.contracts.authentication.TokenIssuerPort;
import com.traceability.convocatoria.application.port.out.ConvocatoriaRepositoryPort;
import com.traceability.convocatoria.domain.model.Convocatoria;
import com.traceability.convocatoria.domain.model.ConvocatoriaConfiguration;
import com.traceability.convocatoria.domain.model.DonationType;
import com.traceability.convocatoria.domain.model.Visibility;
import identity.application.service.BootstrapPlatformAuthorityService;
import identity.application.service.CreateAccountService;
import identity.domain.model.Email;
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
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.EnumSet;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [Plan] Preverificación de organizaciones, Nivel 1, por HTTP (plan-preverificacion-organizaciones.md §3, tests c, c',
 * d y e), contra Tomcat real con {@code identity} real. {@code missingForVerification} solo en la bandeja de la
 * plataforma, siempre presente, y nunca en las respuestas de la organización ni en las públicas. Los NIT son sintéticos
 * (P-R7-5): su dígito está bien calculado y no corresponden a ninguna entidad real.
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
class OrganizationPreverificationHttpIntegrationTest {

    static final String PASSWORD = "Pass123!Pass123!";
    static final String TERMS = "v1-2026-10";
    static final String FIELD = "missingForVerification";
    static final List<String> PROFILE_KEYS = List.of("description", "website", "departmentCode", "municipalityCode",
            "contactEmail", "legalName", "nit", "nitCheckDigit", "specialTaxRegime", "contactPhone");

    @Container
    static MongoDBContainer mongo = new MongoDBContainer(DockerImageName.parse("mongo:6.0")).withCommand("--replSet", "rs0");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongo::getReplicaSetUrl);
    }

    @LocalServerPort private int port;
    @Autowired private TokenIssuerPort tokens;
    @Autowired private CreateAccountService accounts;
    @Autowired private BootstrapPlatformAuthorityService bootstrap;
    @Autowired private ConvocatoriaRepositoryPort convocatorias;
    @Autowired private MongoTemplate mongoTemplate;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();
    private static String platformAdmin;

    private String account() {
        return accounts.createAccount(new Email(UUID.randomUUID() + "@preverificacion.test"), PASSWORD).getAccountId().value();
    }

    @BeforeEach
    void world() {
        if (platformAdmin != null) return;
        String email = UUID.randomUUID() + "@platform.test";
        accounts.createAccount(new Email(email), PASSWORD);
        platformAdmin = bootstrap.bootstrap(email).value();
    }

    private HttpResponse<String> send(String method, String path, String account, String body) throws Exception {
        HttpRequest.Builder r = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (account != null) r.header("Authorization", "Bearer " + tokens.issue(account));
        if (body != null) r.header("Content-Type", "application/json");
        return http.send(r.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode ok(HttpResponse<String> r, int status) throws Exception {
        assertThat(r.statusCode()).as(r.body()).isEqualTo(status);
        return r.body().isEmpty() ? null : json.readTree(r.body());
    }

    private ObjectNode profile(String legalName, String nit, String checkDigit) {
        ObjectNode p = json.createObjectNode();
        p.put("description", "Comedores comunitarios");
        p.put("contactEmail", "contacto@preverificacion.example.org");
        if (legalName != null) p.put("legalName", legalName);
        if (nit != null) p.put("nit", nit).put("nitCheckDigit", checkDigit);
        return p;
    }

    private String create(String representative, ObjectNode extra) throws Exception {
        ObjectNode body = json.createObjectNode().put("type", "FOUNDATION").put("name", "Fundación PV")
                .put("acceptedOrganizationTermsVersion", TERMS);
        if (extra != null) body.setAll(extra);
        return ok(send("POST", "/api/v1/organizations", representative, body.toString()), 201).get("organizationId").asText();
    }

    /** Todas las páginas de la bandeja, en el orden en que llegan. */
    private List<JsonNode> wholeQueue(String status) throws Exception {
        List<JsonNode> items = new ArrayList<>();
        String cursor = null;
        do {
            String query = (status == null ? "" : "status=" + status) + (cursor == null ? "" : "&cursor=" + cursor);
            HttpResponse<String> r = send("GET", "/api/v1/platform/organizations" + (query.isEmpty() ? "" : "?" + query),
                    platformAdmin, null);
            JsonNode page = ok(r, 200);
            assertThat(page.get("items").size()).isLessThanOrEqualTo(20);
            page.get("items").forEach(items::add);
            cursor = page.has("nextCursor") ? java.net.URLEncoder.encode(page.get("nextCursor").asText(),
                    java.nio.charset.StandardCharsets.UTF_8) : null;
        } while (cursor != null);
        return items;
    }

    private JsonNode queueItem(String organizationId) throws Exception {
        return wholeQueue(null).stream().filter(i -> i.get("organizationId").asText().equals(organizationId))
                .findFirst().orElseThrow();
    }

    private List<String> missing(JsonNode item) {
        assertThat(item.has(FIELD)).as("el ítem siempre lleva " + FIELD + ": " + item).isTrue();
        assertThat(item.get(FIELD).isArray()).isTrue();
        List<String> values = new ArrayList<>();
        item.get(FIELD).forEach(v -> values.add(v.asText()));
        return values;
    }

    private static void assertNoField(HttpResponse<String> r, String where) {
        assertThat(r.body()).as(where).doesNotContain(FIELD);
    }

    // ── c ──

    @Test
    void c_theQueueCarriesTheListInEveryItem_andNoOtherResponseCarriesIt() throws Exception {
        String repComplete = account();
        String complete = create(repComplete, profile("Fundación PV Completa", "900000006", "9"));
        String none = create(account(), null);
        String onlyNit = create(account(), profile(null, "900000014", "8"));
        String onlyLegalName = create(account(), profile("Fundación PV Solo Razón Social", null, null));
        // una organización anterior a R-7: el documento no tiene ninguna clave del perfil
        String beforeR7 = create(account(), null);
        Document unset = new Document();
        PROFILE_KEYS.forEach(k -> unset.append(k, ""));
        mongoTemplate.getCollection("organizations").updateOne(new Document("_id", beforeR7), new Document("$unset", unset));
        Document stored = mongoTemplate.getCollection("organizations").find(new Document("_id", beforeR7)).first();
        for (String k : PROFILE_KEYS) {
            assertThat(stored.containsKey(k)).as(k).isFalse();
        }

        List<JsonNode> queue = wholeQueue(null);
        assertThat(queue).isNotEmpty();
        for (JsonNode item : queue) {
            missing(item); // en cada elemento, también en los de otros tests
        }
        assertThat(missing(queueItem(complete))).isEqualTo(List.of());
        assertThat(queueItem(complete).get(FIELD).toString()).isEqualTo("[]");
        assertThat(missing(queueItem(none))).containsExactly("legalName", "nit");
        assertThat(missing(queueItem(onlyNit))).containsExactly("legalName");
        assertThat(missing(queueItem(onlyLegalName))).containsExactly("nit");
        assertThat(missing(queueItem(beforeR7))).containsExactly("legalName", "nit");
        // se calcula al leer: nada se guarda en Mongo
        for (String id : List.of(complete, none, onlyNit, onlyLegalName, beforeR7)) {
            assertThat(mongoTemplate.getCollection("organizations").find(new Document("_id", id)).first().toJson())
                    .doesNotContain(FIELD);
        }

        // negativas: ni la propia organización ni lo público llevan el campo, en ningún nivel
        assertNoField(ok200(send("GET", "/api/v1/organizations/" + complete, repComplete, null)), "GET /organizations/{id}");
        String repNone = account();
        String noneOwned = create(repNone, null);
        assertNoField(ok200(send("GET", "/api/v1/organizations/" + noneOwned, repNone, null)),
                "GET /organizations/{id}, incompleta");
        ObjectNode put = profile("Fundación PV Completa", "900000006", "9").put("name", "Fundación PV");
        assertNoField(ok200(send("PUT", "/api/v1/organizations/" + complete + "/profile", repComplete, put.toString())),
                "PUT /profile");
        ObjectNode putIncomplete = profile(null, null, null).put("name", "Fundación PV");
        assertNoField(ok200(send("PUT", "/api/v1/organizations/" + noneOwned + "/profile", repNone,
                putIncomplete.toString())), "PUT /profile, incompleta");

        String code = UlidCreator.getMonotonicUlid().toString();
        convocatorias.insert(Convocatoria.create("camp-" + code, none, code, "Comedor", null, Visibility.PUBLIC,
                Instant.parse("2027-01-01T00:00:00Z"), Instant.parse("2027-02-01T00:00:00Z"),
                new ConvocatoriaConfiguration(EnumSet.of(DonationType.IN_KIND), Set.of(), null, null, null, null)));
        assertNoField(ok200(send("GET", "/api/v1/public/campaigns/" + code, null, null)), "GET /public/campaigns/{code}");
    }

    private HttpResponse<String> ok200(HttpResponse<String> r) {
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return r;
    }

    // ── c' ──

    @Test
    void c2_theListFollowsTheProfileOnTheNextRead_andVerifyAnswersAccordingToIt() throws Exception {
        String rep = account();
        String org = create(rep, null);
        assertThat(missing(queueItem(org))).containsExactly("legalName", "nit");

        HttpResponse<String> refused = send("POST", "/api/v1/platform/organizations/" + org + "/verify", platformAdmin, null);
        assertThat(refused.statusCode()).isEqualTo(409);
        assertThat(json.readTree(refused.body()).get("title").asText()).isEqualTo("VerificationDataMissing");
        assertThat(missing(queueItem(org))).as("el 409 no cambia nada").containsExactly("legalName", "nit");

        ObjectNode onlyName = profile("Fundación PV Edita", null, null).put("name", "Fundación PV");
        ok(send("PUT", "/api/v1/organizations/" + org + "/profile", rep, onlyName.toString()), 200);
        assertThat(missing(queueItem(org))).containsExactly("nit");

        ObjectNode both = profile("Fundación PV Edita", "900000003", "7").put("name", "Fundación PV");
        ok(send("PUT", "/api/v1/organizations/" + org + "/profile", rep, both.toString()), 200);
        assertThat(missing(queueItem(org))).isEmpty();

        ok(send("POST", "/api/v1/platform/organizations/" + org + "/verify", platformAdmin, null), 200);
        assertThat(wholeQueue(null)).noneMatch(i -> i.get("organizationId").asText().equals(org));
    }

    // ── d ──

    @Test
    void d_theOrderAndThePaginationOfTheQueueDoNotChange() throws Exception {
        List<String> created = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            // alternando: las incompletas en posiciones pares, las completas en impares
            String nit = String.format("8%08d", i);
            created.add(create(account(), i % 2 == 0 ? null
                    : profile("Fundación PV " + i, nit, String.valueOf(identity.domain.model.Nit.checkDigitOf(nit)))));
        }

        List<JsonNode> queue = wholeQueue(null);
        List<String> ids = queue.stream().map(i -> i.get("organizationId").asText()).toList();
        assertThat(new HashSet<>(ids)).as("sin repetidos").hasSize(ids.size());
        assertThat(ids).as("todas las creadas, sin huecos").containsAll(created);
        assertThat(ids).as("el orden de hoy: por organizationId ascendente (orden de creación)").isSorted();
        List<String> ours = ids.stream().filter(created::contains).toList();
        assertThat(ours).isEqualTo(created.stream().sorted().toList());
        // la lista no agrupa: hay incompletas antes que completas en el orden de la cola
        List<Boolean> completeInOrder = queue.stream().filter(i -> created.contains(i.get("organizationId").asText()))
                .map(i -> missing(i).isEmpty()).toList();
        assertThat(completeInOrder).contains(true, false);
        assertThat(completeInOrder).isNotEqualTo(completeInOrder.stream().sorted(java.util.Comparator.reverseOrder()).toList());

        // la primera página sigue teniendo 20 y un cursor
        JsonNode first = ok(send("GET", "/api/v1/platform/organizations", platformAdmin, null), 200);
        assertThat(first.get("items").size()).isEqualTo(20);
        assertThat(first.has("nextCursor")).isTrue();
        // ?status= sigue filtrando solo por estado
        assertThat(wholeQueue("PENDING_VERIFICATION")).allMatch(i -> i.get("verificationStatus").asText()
                .equals("PENDING_VERIFICATION"));
    }

    // ── e ──

    @Test
    void e_whoeverIsNotPlatformGetsTheSame403AsToday_withoutTheField() throws Exception {
        String rep = account();
        create(rep, null);
        HttpResponse<String> asRepresentative = send("GET", "/api/v1/platform/organizations", rep, null);
        HttpResponse<String> asLoner = send("GET", "/api/v1/platform/organizations", account(), null);
        HttpResponse<String> asLonerWithBadInput = send("GET", "/api/v1/platform/organizations?status=VERIFIED&cursor=abc",
                account(), null);
        for (HttpResponse<String> r : List.of(asRepresentative, asLoner, asLonerWithBadInput)) {
            assertThat(r.statusCode()).as(r.body()).isEqualTo(403);
            assertThat(json.readTree(r.body()).get("title").asText())
                    .isEqualTo(json.readTree(asRepresentative.body()).get("title").asText());
            assertNoField(r, "403");
        }
        assertThat(send("GET", "/api/v1/platform/organizations", null, null).statusCode()).isEqualTo(401);
    }
}
