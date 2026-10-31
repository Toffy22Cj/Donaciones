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
import identity.application.service.AddEmployeeService;
import identity.application.service.AssignAdministratorService;
import identity.application.service.BootstrapPlatformAuthorityService;
import identity.application.service.CreateAccountService;
import identity.domain.model.AccountId;
import identity.domain.model.AuditActor;
import identity.domain.model.Email;
import identity.domain.model.OrganizationId;
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
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [Plan] Ficha R-7 por HTTP (plan R-7 §3, tests 6 a 10), contra Tomcat real con {@code identity} real y el catálogo
 * DIVIPOLA de <b>muestra de test</b> ({@code src/test/resources/divipola/divipola.json}). Los NIT son sintéticos
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
class OrganizationProfileHttpIntegrationTest {

    static final String PASSWORD = "Pass123!Pass123!";
    static final String TERMS = "v1-2026-10";
    static final List<String> VERIFICATION_FIELDS = List.of("legalName", "nit", "nitCheckDigit", "specialTaxRegime",
            "contactPhone");

    @Container
    static MongoDBContainer mongo = new MongoDBContainer(DockerImageName.parse("mongo:6.0")).withCommand("--replSet", "rs0");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongo::getReplicaSetUrl);
    }

    @LocalServerPort private int port;
    @Autowired private TokenIssuerPort tokens;
    @Autowired private CreateAccountService accounts;
    @Autowired private AddEmployeeService employees;
    @Autowired private AssignAdministratorService administrators;
    @Autowired private BootstrapPlatformAuthorityService bootstrap;
    @Autowired private ConvocatoriaRepositoryPort convocatorias;
    @Autowired private MongoTemplate mongoTemplate;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();
    private static final AuditActor SETUP = new AuditActor.SystemAuditActor("r7-tests");
    private static String platformAdmin;

    private String account() {
        return accounts.createAccount(new Email(UUID.randomUUID() + "@r7.test"), PASSWORD).getAccountId().value();
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

    private String title(HttpResponse<String> r) throws Exception {
        return json.readTree(r.body()).get("title").asText();
    }

    /** Todos los campos de §3 válidos, con un NIT sintético. */
    private ObjectNode fullProfile(String nit, String checkDigit) {
        ObjectNode p = json.createObjectNode();
        p.put("description", "Comedores comunitarios en Medellín");
        p.put("website", "https://fundacion-r7.example.org");
        p.putObject("location").put("departmentCode", "05").put("municipalityCode", "05001");
        p.put("contactEmail", "contacto@fundacion-r7.example.org");
        p.put("legalName", "Fundación R7 Ejemplo");
        p.put("nit", nit);
        p.put("nitCheckDigit", checkDigit);
        p.put("specialTaxRegime", true);
        p.put("contactPhone", "3001234567");
        return p;
    }

    private HttpResponse<String> create(String representative, ObjectNode extra) throws Exception {
        ObjectNode body = json.createObjectNode().put("type", "FOUNDATION").put("name", "Fundación R7")
                .put("acceptedOrganizationTermsVersion", TERMS);
        if (extra != null) body.setAll(extra);
        return send("POST", "/api/v1/organizations", representative, body.toString());
    }

    private Document organizationDoc(String organizationId) {
        return mongoTemplate.getCollection("organizations").find(new Document("_id", organizationId)).first();
    }

    // ── 6 ──

    @Test
    void test6_createWithAndWithoutTheOptionalFields_andARepeatedNit_is409_withoutCreatingTheOrganization() throws Exception {
        String repA = account();
        String a = ok(create(repA, fullProfile("900000006", "9")), 201).get("organizationId").asText();
        assertThat(organizationDoc(a).getString("nit")).isEqualTo("900000006");
        assertThat(organizationDoc(a).getString("municipalityCode")).isEqualTo("05001");

        String repB = account();
        ok(create(repB, null), 201);

        String repC = account();
        long before = mongoTemplate.getCollection("organizations").countDocuments();
        HttpResponse<String> duplicate = create(repC, fullProfile("900000006", "9"));
        assertThat(duplicate.statusCode()).isEqualTo(409);
        assertThat(title(duplicate)).isEqualTo("OrganizationNitAlreadyRegistered");
        assertThat(mongoTemplate.getCollection("organizations").countDocuments()).isEqualTo(before);
        assertThat(mongoTemplate.getCollection("accounts").find(new Document("_id", repC)).first().get("organizationId"))
                .as("la cuenta no queda en ninguna organización").isNull();
    }

    @Test
    void test6_invalidFieldsAtCreation_are400_withoutCreating() throws Exception {
        long before = mongoTemplate.getCollection("organizations").countDocuments();
        ObjectNode badCheck = fullProfile("900000014", "9");
        ObjectNode otherDepartment = fullProfile("900000014", "8");
        ((ObjectNode) otherDepartment.get("location")).put("municipalityCode", "11001");
        ObjectNode unknownMunicipality = fullProfile("900000014", "8");
        ((ObjectNode) unknownMunicipality.get("location")).put("municipalityCode", "05999");
        ObjectNode httpSite = fullProfile("900000014", "8").put("website", "http://inseguro.example.org");

        for (ObjectNode bad : List.of(badCheck, otherDepartment, unknownMunicipality, httpSite)) {
            assertThat(create(account(), bad).statusCode()).as(bad.toString()).isEqualTo(400);
        }
        assertThat(mongoTemplate.getCollection("organizations").countDocuments()).isEqualTo(before);
    }

    // ── 7 ──

    @Test
    void test7_putProfile_byRepresentativeOrAdministrator_is200_otherwiseTheSame403_andTheAuditHasOnlyFieldNames()
            throws Exception {
        String rep = account();
        String org = ok(create(rep, null), 201).get("organizationId").asText();
        String admin = account();
        employees.addEmployee(SETUP, new OrganizationId(org), new AccountId(admin));
        administrators.assignAdministrator(SETUP, new OrganizationId(org), new AccountId(admin));
        String employee = account();
        employees.addEmployee(SETUP, new OrganizationId(org), new AccountId(employee));
        String outsider = account();
        String otherRep = account();
        ok(create(otherRep, null), 201);

        ObjectNode body = fullProfile("900000003", "7").put("name", "Fundación R7");
        HttpResponse<String> byRep = send("PUT", "/api/v1/organizations/" + org + "/profile", rep, body.toString());
        JsonNode profile = ok(byRep, 200);
        assertThat(byRep.headers().firstValue("Cache-Control")).hasValue("no-store");
        assertThat(profile.get("nit").asText()).isEqualTo("900000003");
        assertThat(profile.get("location").get("municipalityCode").asText()).isEqualTo("05001");

        // ausente o null borra el opcional, como PUT /account/profile
        ObjectNode cleared = fullProfile("900000003", "7").put("name", "Fundación R7");
        cleared.remove("website");
        cleared.putNull("contactPhone");
        JsonNode byAdmin = ok(send("PUT", "/api/v1/organizations/" + org + "/profile", admin, cleared.toString()), 200);
        assertThat(byAdmin.has("website")).isFalse();
        assertThat(byAdmin.has("contactPhone")).isFalse();

        HttpResponse<String> byEmployee = send("PUT", "/api/v1/organizations/" + org + "/profile", employee, body.toString());
        HttpResponse<String> byOutsider = send("PUT", "/api/v1/organizations/" + org + "/profile", otherRep, body.toString());
        HttpResponse<String> unknown = send("PUT", "/api/v1/organizations/" + UlidCreator.getUlid() + "/profile", rep, body.toString());
        HttpResponse<String> noOrg = send("PUT", "/api/v1/organizations/" + org + "/profile", outsider, body.toString());
        for (HttpResponse<String> r : List.of(byEmployee, byOutsider, unknown, noOrg)) {
            assertThat(r.statusCode()).isEqualTo(403);
            assertThat(json.readTree(r.body()).get("title").asText()).isEqualTo(json.readTree(byEmployee.body()).get("title").asText());
        }

        List<Document> audits = mongoTemplate.getCollection("identity_audit_log")
                .find(new Document("targetOrganizationId", org).append("action", "ORGANIZATION_PROFILE_UPDATED"))
                .into(new java.util.ArrayList<>());
        assertThat(audits).hasSize(2);
        String all = audits.toString();
        assertThat(all).contains("description", "nit").doesNotContain("900000003").doesNotContain("Comedores")
                .doesNotContain("3001234567").doesNotContain("contacto@");
    }

    @Test
    void test7_aVerifiedOrganization_cannotChangeItsIdentity_409_butCanChangeThePublicFields() throws Exception {
        String rep = account();
        String org = ok(create(rep, fullProfile("900000004", "4")), 201).get("organizationId").asText();
        ok(send("POST", "/api/v1/platform/organizations/" + org + "/verify", platformAdmin, null), 200);

        ObjectNode otherNit = fullProfile("900000008", "3").put("name", "Fundación R7");
        HttpResponse<String> locked = send("PUT", "/api/v1/organizations/" + org + "/profile", rep, otherNit.toString());
        assertThat(locked.statusCode()).isEqualTo(409);
        assertThat(title(locked)).isEqualTo("OrganizationProfileLocked");

        ObjectNode publicChange = fullProfile("900000004", "4").put("name", "Fundación R7").put("description", "Otra");
        assertThat(ok(send("PUT", "/api/v1/organizations/" + org + "/profile", rep, publicChange.toString()), 200)
                .get("description").asText()).isEqualTo("Otra");
    }

    // ── 8 ──

    @Test
    void test8_thePublicDetail_carriesOnlyThePublicProfile_neverTheVerificationDataNorTheOrganizationId() throws Exception {
        String rep = account();
        String org = ok(create(rep, fullProfile("900000001", "2")), 201).get("organizationId").asText();
        String code = UlidCreator.getMonotonicUlid().toString();
        convocatorias.insert(Convocatoria.create("camp-" + code, org, code, "Comedor", null, Visibility.PUBLIC,
                Instant.parse("2027-01-01T00:00:00Z"), Instant.parse("2027-02-01T00:00:00Z"),
                new ConvocatoriaConfiguration(EnumSet.of(DonationType.IN_KIND), Set.of(), null, null, null, null)));

        HttpResponse<String> r = send("GET", "/api/v1/public/campaigns/" + code, null, null);
        JsonNode detail = ok(r, 200);

        JsonNode profile = detail.get("organizationProfile");
        assertThat(profile.get("description").asText()).isEqualTo("Comedores comunitarios en Medellín");
        assertThat(profile.get("website").asText()).isEqualTo("https://fundacion-r7.example.org");
        assertThat(profile.get("contactEmail").asText()).isEqualTo("contacto@fundacion-r7.example.org");
        assertThat(profile.get("location")).isEqualTo(json.readTree(
                "{\"departmentName\":\"ANTIOQUIA\",\"municipalityName\":\"MEDELLÍN\"}"));
        for (String forbidden : List.of("nit", "nitCheckDigit", "legalName", "contactPhone", "specialTaxRegime",
                "organizationId", "900000001", "3001234567", "Fundación R7 Ejemplo", org)) {
            assertThat(r.body()).as(forbidden).doesNotContain(forbidden);
        }
    }

    // ── 9 ──

    @Test
    void test9_theQueueAndGetOrganization_showTheVerificationData_toThoseAllowed() throws Exception {
        String rep = account();
        String org = ok(create(rep, fullProfile("900000000", "5")), 201).get("organizationId").asText();

        JsonNode own = ok(send("GET", "/api/v1/organizations/" + org, rep, null), 200);
        for (String field : VERIFICATION_FIELDS) {
            assertThat(own.has(field)).as(field).isTrue();
        }
        assertThat(own.get("nit").asText()).isEqualTo("900000000");

        JsonNode queueItem = null;
        String cursor = null;
        do {
            JsonNode page = ok(send("GET", "/api/v1/platform/organizations?status=PENDING_VERIFICATION"
                    + (cursor == null ? "" : "&cursor=" + cursor), platformAdmin, null), 200);
            for (JsonNode i : page.get("items")) {
                if (i.get("organizationId").asText().equals(org)) queueItem = i;
            }
            cursor = page.has("nextCursor") ? page.get("nextCursor").asText() : null;
        } while (queueItem == null && cursor != null);
        assertThat(queueItem).isNotNull();
        assertThat(queueItem.get("legalName").asText()).isEqualTo("Fundación R7 Ejemplo");
        assertThat(queueItem.get("nitCheckDigit").asText()).isEqualTo("5");
    }

    // ── 10 ──

    @Test
    void test10_resubmit_returnsToTheQueueAsPending_andFromOtherStatesIs409() throws Exception {
        String rep = account();
        String org = ok(create(rep, fullProfile("900000007", "6")), 201).get("organizationId").asText();
        assertThat(title(send("POST", "/api/v1/organizations/" + org + "/verification/resubmit", rep, null)))
                .isEqualTo("VerificationResubmitNotAllowed");

        ok(send("POST", "/api/v1/platform/organizations/" + org + "/request-information", platformAdmin,
                "{\"message\":\"Adjunte el RUT\"}"), 200);
        JsonNode resubmitted = ok(send("POST", "/api/v1/organizations/" + org + "/verification/resubmit", rep, null), 200);

        assertThat(resubmitted).isEqualTo(json.readTree("{\"verificationStatus\":\"PENDING_VERIFICATION\"}"));
        JsonNode status = ok(send("GET", "/api/v1/organizations/" + org, rep, null), 200);
        assertThat(status.get("verificationStatus").asText()).isEqualTo("PENDING_VERIFICATION");
        assertThat(status.has("informationRequest")).isFalse();
        assertThat(send("POST", "/api/v1/organizations/" + org + "/verification/resubmit", account(), null).statusCode())
                .isEqualTo(403);
    }

    @Test
    void p2b_verifyWithoutLegalNameOrNit_is409VerificationDataMissing() throws Exception {
        String rep = account();
        String org = ok(create(rep, null), 201).get("organizationId").asText();

        HttpResponse<String> r = send("POST", "/api/v1/platform/organizations/" + org + "/verify", platformAdmin, null);

        assertThat(r.statusCode()).isEqualTo(409);
        assertThat(title(r)).isEqualTo("VerificationDataMissing");
    }
}
