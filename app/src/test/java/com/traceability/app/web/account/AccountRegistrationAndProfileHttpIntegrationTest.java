package com.traceability.app.web.account;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.app.TraceabilityApplication;
import identity.application.service.CreateAccountService;
import identity.application.service.CreateOrganizationService;
import identity.domain.model.AccountId;
import identity.domain.model.AuditActor;
import identity.domain.model.Email;
import identity.domain.model.OrganizationType;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-050 D1, D2 y D10 (plan R-5, primera parte), contra Tomcat real: test 19 (registro sin aceptación o con una versión
 * vieja → 400 y sin cuenta), el perfil ({@code GET}/{@code PUT /api/v1/account/profile}) y test 23 (el nombre no aparece
 * en {@code /me}, {@code /account}, la lista de miembros ni el descubrimiento público, ni en los logs).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = TraceabilityApplication.class)
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = {
    "crypto.anchor.poll.delay=9999999",
    "crypto.anchor.submit.delay=9999999",
    "crypto.anchor.stuck-monitor.delay=9999999",
    "crypto.web3j.private-key=0x1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef",
    "crypto.web3j.node-url=http://dummy-node",
    "spring.ai.openai.api-key=dummy-api-key"
})
class AccountRegistrationAndProfileHttpIntegrationTest {

    static final String PASSWORD = "Pass123!Pass123!";
    static final String CURRENT = "v1-2026-10";
    static final String PROFILE = "/api/v1/account/profile";
    /** Un nombre que no puede aparecer por casualidad en ninguna respuesta. */
    static final String GIVEN = "Zuleika" + UUID.randomUUID().toString().substring(0, 8);
    static final String FAMILY = "Quintanilla" + UUID.randomUUID().toString().substring(0, 8);

    @Container
    static MongoDBContainer mongo = new MongoDBContainer(DockerImageName.parse("mongo:6.0")).withCommand("--replSet", "rs0");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongo::getReplicaSetUrl);
    }

    @LocalServerPort private int port;
    @Autowired private CreateAccountService accounts;
    @Autowired private CreateOrganizationService organizations;
    @Autowired private MongoTemplate mongoTemplate;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    private static String newEmail() {
        return UUID.randomUUID() + "@r5.test";
    }

    private HttpResponse<String> send(String method, String path, String token, String body) throws Exception {
        HttpRequest.Builder r = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (token != null) r.header("Authorization", "Bearer " + token);
        if (body != null) r.header("Content-Type", "application/json");
        return http.send(r.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> register(String email, String terms, String dataPolicy) throws Exception {
        var body = json.createObjectNode().put("email", email).put("password", PASSWORD);
        if (terms != null) body.put("acceptedTermsVersion", terms);
        if (dataPolicy != null) body.put("acceptedDataPolicyVersion", dataPolicy);
        return send("POST", "/api/v1/auth/register", null, body.toString());
    }

    private String login(String email) throws Exception {
        HttpResponse<String> r = send("POST", "/api/v1/auth/login", null,
                "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}");
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return json.readTree(r.body()).get("token").asText();
    }

    private Document accountDocument(String email) {
        return mongoTemplate.findOne(Query.query(Criteria.where("email").is(email)), Document.class, "accounts");
    }

    // --- 19 ---

    @Test
    void registration_withBothCurrentVersions_is201_andStoresThemWithTheDate() throws Exception {
        String email = newEmail();

        HttpResponse<String> r = register(email, CURRENT, CURRENT);

        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        Document stored = accountDocument(email);
        assertThat(stored.getString("termsVersion")).isEqualTo(CURRENT);
        assertThat(stored.getString("dataPolicyVersion")).isEqualTo(CURRENT);
        assertThat(stored.getDate("termsAcceptedAt")).isNotNull();
        assertThat(stored.getDate("dataPolicyAcceptedAt")).isNotNull();
    }

    @Test
    void registration_withoutAnAcceptance_orWithAnOldVersionOfEither_is400_andCreatesNoAccount() throws Exception {
        String email = newEmail();

        for (String[] versions : List.of(new String[] {null, CURRENT}, new String[] {CURRENT, null},
                new String[] {"v0-2026-01", CURRENT}, new String[] {CURRENT, "v0-2026-01"}, new String[] {null, null})) {
            HttpResponse<String> r = register(email, versions[0], versions[1]);
            assertThat(r.statusCode()).as(String.join(",", String.valueOf(versions[0]), String.valueOf(versions[1])))
                    .isEqualTo(400);
            assertThat(json.readTree(r.body()).get("title").asText()).isEqualTo("BadRequest");
        }

        assertThat(accountDocument(email)).isNull();
        assertThat(mongoTemplate.count(Query.query(Criteria.where("action").is("ACCOUNT_CREATED")
                .and("changeSummary.email").exists(true)), "identity_audit_log")).isZero();
    }

    @Test
    void organization_withoutTheCurrentTermsVersion_is400_andWithIt_isCreatedAndStoresIt() throws Exception {
        String email = newEmail();
        assertThat(register(email, CURRENT, CURRENT).statusCode()).isEqualTo(201);
        String token = login(email);

        HttpResponse<String> without = send("POST", "/api/v1/organizations", token,
                "{\"type\":\"FOUNDATION\",\"name\":\"Fundación R5\"}");
        HttpResponse<String> old = send("POST", "/api/v1/organizations", token,
                "{\"type\":\"FOUNDATION\",\"name\":\"Fundación R5\",\"acceptedOrganizationTermsVersion\":\"v0-2026-01\"}");
        HttpResponse<String> ok = send("POST", "/api/v1/organizations", token,
                "{\"type\":\"FOUNDATION\",\"name\":\"Fundación R5\",\"acceptedOrganizationTermsVersion\":\"" + CURRENT + "\"}");

        assertThat(without.statusCode()).isEqualTo(400);
        assertThat(old.statusCode()).isEqualTo(400);
        assertThat(ok.statusCode()).as(ok.body()).isEqualTo(201);
        String organizationId = json.readTree(ok.body()).get("organizationId").asText();
        Document org = mongoTemplate.findOne(Query.query(Criteria.where("_id").is(organizationId)), Document.class,
                "organizations");
        assertThat(org.getString("organizationTermsVersion")).isEqualTo(CURRENT);
        assertThat(org.getString("organizationTermsAcceptedBy")).isEqualTo(accountDocument(email).getString("_id"));
        assertThat(org.getDate("organizationTermsAcceptedAt")).isNotNull();
    }

    // --- D1: perfil ---

    @Test
    void profile_getAndPut_withNoStore_andNullClearsAField() throws Exception {
        String email = newEmail();
        register(email, CURRENT, CURRENT);
        String token = login(email);

        HttpResponse<String> empty = send("GET", PROFILE, token, null);
        assertThat(empty.statusCode()).isEqualTo(200);
        assertThat(empty.headers().firstValue("Cache-Control")).hasValue("no-store");
        assertThat(json.readTree(empty.body()).get("email").asText()).isEqualTo(email);
        assertThat(json.readTree(empty.body()).has("givenName")).isFalse();

        HttpResponse<String> put = send("PUT", PROFILE, token,
                "{\"givenName\":\"  " + GIVEN + "  \",\"familyName\":\"" + FAMILY + "\"}");
        assertThat(put.statusCode()).as(put.body()).isEqualTo(200);
        assertThat(put.headers().firstValue("Cache-Control")).hasValue("no-store");
        JsonNode body = json.readTree(put.body());
        assertThat(body.get("givenName").asText()).isEqualTo(GIVEN);
        assertThat(body.get("familyName").asText()).isEqualTo(FAMILY);

        HttpResponse<String> cleared = send("PUT", PROFILE, token, "{\"givenName\":\"" + GIVEN + "\",\"familyName\":null}");
        assertThat(json.readTree(cleared.body()).has("familyName")).isFalse();
        assertThat(json.readTree(send("GET", PROFILE, token, null).body()).get("givenName").asText()).isEqualTo(GIVEN);
    }

    @Test
    void profile_aBlankOrTooLongField_is400_andNothingChanges() throws Exception {
        String email = newEmail();
        register(email, CURRENT, CURRENT);
        String token = login(email);
        send("PUT", PROFILE, token, "{\"givenName\":\"" + GIVEN + "\"}");

        HttpResponse<String> blank = send("PUT", PROFILE, token, "{\"givenName\":\"   \",\"familyName\":\"X\"}");
        HttpResponse<String> tooLong = send("PUT", PROFILE, token,
                "{\"givenName\":\"" + "x".repeat(101) + "\",\"familyName\":\"X\"}");

        assertThat(blank.statusCode()).isEqualTo(400);
        assertThat(tooLong.statusCode()).isEqualTo(400);
        assertThat(json.readTree(blank.body()).get("title").asText()).isEqualTo("BadRequest");
        JsonNode now = json.readTree(send("GET", PROFILE, token, null).body());
        assertThat(now.get("givenName").asText()).isEqualTo(GIVEN);
        assertThat(now.has("familyName")).isFalse();
    }

    @Test
    void profile_withoutJwt_is401() throws Exception {
        assertThat(send("GET", PROFILE, null, null).statusCode()).isEqualTo(401);
        assertThat(send("PUT", PROFILE, null, "{\"givenName\":\"X\"}").statusCode()).isEqualTo(401);
    }

    // --- 23 ---

    @Test
    void theName_neverAppearsInMe_account_members_publicDiscovery_norTheLogs(CapturedOutput output) throws Exception {
        String email = newEmail();
        String accountId = accounts.createAccount(new Email(email), PASSWORD, CURRENT, CURRENT).getAccountId().value();
        String organizationId = organizations.createOrganization(new AuditActor.AccountAuditActor(new AccountId(accountId)),
                OrganizationType.FOUNDATION, new AccountId(accountId), "Fundación R5 nombres", CURRENT)
                .getOrganizationId().value();
        String token = login(email);
        int from = output.getAll().length();

        assertThat(send("PUT", PROFILE, token, "{\"givenName\":\"" + GIVEN + "\",\"familyName\":\"" + FAMILY + "\"}")
                .statusCode()).isEqualTo(200);

        for (String path : List.of("/api/v1/me", "/api/v1/account", "/api/v1/organizations/" + organizationId + "/members",
                "/api/v1/organizations/" + organizationId)) {
            HttpResponse<String> r = send("GET", path, token, null);
            assertThat(r.statusCode()).as(path).isEqualTo(200);
            assertThat(r.body()).as(path).doesNotContain(GIVEN).doesNotContain(FAMILY);
        }
        assertThat(send("GET", "/api/v1/public/campaigns", null, null).body()).doesNotContain(GIVEN).doesNotContain(FAMILY);
        assertThat(output.getAll().substring(from)).doesNotContain(GIVEN).doesNotContain(FAMILY);
    }
}
