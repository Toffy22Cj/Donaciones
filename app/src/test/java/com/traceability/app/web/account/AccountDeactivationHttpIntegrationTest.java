package com.traceability.app.web.account;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.app.TraceabilityApplication;
import com.traceability.contracts.authentication.TokenIssuerPort;
import identity.application.service.AddEmployeeService;
import identity.application.service.AssignAdministratorService;
import identity.application.service.BootstrapPlatformAuthorityService;
import identity.application.service.CreateAccountService;
import identity.application.service.CreateOrganizationService;
import identity.domain.model.AccountId;
import identity.domain.model.AuditActor;
import identity.domain.model.Email;
import identity.domain.model.OrganizationId;
import identity.domain.model.OrganizationType;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
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
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [Plan: nombre] ADR-050 D6 y Q3 (a) (plan D6), contra Tomcat real con {@code identity} y {@code convocatoria} reales:
 * {@code POST /api/v1/account/deactivate}. Numeración de la definición de hecho de ADR-050: 24 (las tres excepciones,
 * con la cuenta {@code ACTIVE} y sin auditoría) y 25 (desactivar → la siguiente petición con su token da 401).
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
class AccountDeactivationHttpIntegrationTest {

    static final String PASSWORD = "Pass123!Pass123!";
    static final String PATH = "/api/v1/account/deactivate";
    private static final String START = Instant.now().plus(Duration.ofDays(1)).toString();
    private static final String END = Instant.now().plus(Duration.ofDays(60)).toString();

    @Container
    static MongoDBContainer mongo = new MongoDBContainer(DockerImageName.parse("mongo:6.0")).withCommand("--replSet", "rs0");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongo::getReplicaSetUrl);
    }

    @LocalServerPort private int port;
    @Autowired private TokenIssuerPort tokens;
    @Autowired private CreateAccountService accounts;
    @Autowired private CreateOrganizationService organizations;
    @Autowired private AddEmployeeService employees;
    @Autowired private AssignAdministratorService administrators;
    @Autowired private BootstrapPlatformAuthorityService bootstrap;
    @Autowired private MongoTemplate mongoTemplate;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();
    private static final AuditActor SETUP = new AuditActor.SystemAuditActor("d6-tests");

    private static String platformAdmin, org, representative, admin;

    private String account(String email) {
        return accounts.createAccount(new Email(email), PASSWORD).getAccountId().value();
    }

    private static String email() {
        return "u" + UUID.randomUUID().toString().substring(0, 12) + "@d6.test";
    }

    @BeforeEach
    void world() throws Exception {
        if (org != null) return;
        String platformEmail = email();
        account(platformEmail);
        platformAdmin = bootstrap.bootstrap(platformEmail).value();
        representative = account(email());
        OrganizationId o = organizations.createOrganization(SETUP, OrganizationType.FOUNDATION,
                new AccountId(representative), "Fundación D6").getOrganizationId();
        org = o.value();
        admin = account(email());
        employees.addEmployee(SETUP, o, new AccountId(admin));
        administrators.assignAdministrator(SETUP, o, new AccountId(admin));
        // ficha R-7, P2 (b): razón social y NIT sintético antes de verificar (P-R7-4)
        com.traceability.app.support.OrganizationVerificationData.prepare(mongoTemplate, org);
        ok(send("POST", "/api/v1/platform/organizations/" + org + "/verify", tokens.issue(platformAdmin), null), 200);
    }

    private HttpResponse<String> send(String method, String path, String token, String body) throws Exception {
        HttpRequest.Builder r = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (token != null) r.header("Authorization", "Bearer " + token);
        if (method.equals("POST")) r.header("Command-Id", UUID.randomUUID().toString());
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

    private HttpResponse<String> deactivate(String token, String currentPassword) throws Exception {
        return send("POST", PATH, token, json.createObjectNode().put("currentPassword", currentPassword).toString());
    }

    private Document accountDoc(String accountId) {
        return mongoTemplate.findOne(Query.query(Criteria.where("_id").is(accountId)), Document.class, "accounts");
    }

    private long deactivationAudits(String accountId) {
        return mongoTemplate.count(Query.query(Criteria.where("targetAccountId").is(accountId)
                .and("action").is("ACCOUNT_DEACTIVATED")), "identity_audit_log");
    }

    /** 409 con el título dado; la cuenta sigue ACTIVE, sin credentialsNotBefore nuevo y sin auditoría. */
    private void refused(String accountId, String expectedTitle) throws Exception {
        Object notBeforeBefore = accountDoc(accountId).get("credentialsNotBefore");
        String token = tokens.issue(accountId);
        HttpResponse<String> r = deactivate(token, PASSWORD);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(409);
        assertThat(title(r)).isEqualTo(expectedTitle);
        Document after = accountDoc(accountId);
        assertThat(after.getString("status")).isEqualTo("ACTIVE");
        assertThat(after.get("credentialsNotBefore")).isEqualTo(notBeforeBefore);
        assertThat(deactivationAudits(accountId)).isZero();
        // el token sigue valiendo
        ok(send("GET", "/api/v1/me", token, null), 200);
    }

    // ── 24 ──

    @Test
    void test24_thePlatformAdministrator_cannotDeactivateTheirAccount() throws Exception {
        refused(platformAdmin, "PlatformAdministratorDeactivation");
    }

    @Test
    void test24_theRepresentative_mustTransferTheRoleFirst() throws Exception {
        refused(representative, "RepresentativeTransferRequired");
    }

    @Test
    void test24_anActiveCampaignResponsible_cannotDeactivateTheirAccount() throws Exception {
        String responsible = account(email());
        employees.addEmployee(SETUP, new OrganizationId(org), new AccountId(responsible));
        String body = """
                {"title":"Responsables D6","visibility":"PUBLIC","startDate":"%s","endDate":"%s",
                 "configuration":{"acceptedDonationTypes":["IN_KIND"]}}""".formatted(START, END);
        String campaignRef = ok(send("POST", "/api/v1/organizations/" + org + "/campaigns", tokens.issue(admin), body), 201)
                .get("campaignRef").asText();
        ok(send("POST", "/api/v1/campaigns/" + campaignRef + "/employees", tokens.issue(admin),
                "{\"employeeRef\":\"" + responsible + "\"}"), 201);

        refused(responsible, "ActiveCampaignResponsible");
    }

    // ── 25 ──

    @Test
    void test25_deactivating_revokesEveryEarlierToken_andKeepsTheMembership() throws Exception {
        String member = account(email());
        employees.addEmployee(SETUP, new OrganizationId(org), new AccountId(member));
        String token = tokens.issue(member);
        String other = tokens.issue(member);
        Document before = accountDoc(member);

        HttpResponse<String> r = deactivate(token, PASSWORD);

        assertThat(r.statusCode()).as(r.body()).isEqualTo(204);
        assertThat(r.body()).isEmpty();
        Document after = accountDoc(member);
        assertThat(after.getString("status")).isEqualTo("INACTIVE");
        assertThat(after.get("credentialsNotBefore")).isNotNull();
        // D6: la membresía no cambia
        assertThat(after.get("organizationId")).isEqualTo(before.get("organizationId"));
        assertThat(send("GET", "/api/v1/organizations/" + org + "/members", tokens.issue(admin), null).body())
                .contains(member);
        assertThat(deactivationAudits(member)).isEqualTo(1);
        // la siguiente petición con cualquier token anterior, también el de la petición, da 401
        assertThat(send("GET", "/api/v1/me", token, null).statusCode()).isEqualTo(401);
        assertThat(send("GET", "/api/v1/me", other, null).statusCode()).isEqualTo(401);
    }

    // ── 400, 401 y logs ──

    @Test
    void aWrongCurrentPassword_is400_neverA401_andNothingChanges() throws Exception {
        String member = account(email());
        String token = tokens.issue(member);

        HttpResponse<String> r = deactivate(token, "Wrong-Pass-123!");

        assertThat(r.statusCode()).as(r.body()).isEqualTo(400);
        assertThat(title(r)).isEqualTo("CurrentPasswordIncorrect");
        assertThat(accountDoc(member).getString("status")).isEqualTo("ACTIVE");
        assertThat(deactivationAudits(member)).isZero();
        ok(send("GET", "/api/v1/me", token, null), 200);
    }

    @Test
    void anEmptyCurrentPassword_is400_withoutReachingTheDomain() throws Exception {
        String member = account(email());
        String token = tokens.issue(member);

        assertThat(deactivate(token, "").statusCode()).isEqualTo(400);
        assertThat(send("POST", PATH, token, "{}").statusCode()).isEqualTo(400);
        assertThat(send("POST", PATH, token, null).statusCode()).isEqualTo(400);
        assertThat(accountDoc(member).getString("status")).isEqualTo("ACTIVE");
    }

    @Test
    void withoutAJwt_is401() throws Exception {
        assertThat(send("POST", PATH, null, "{\"currentPassword\":\"" + PASSWORD + "\"}").statusCode()).isEqualTo(401);
    }

    @Test
    void thePassword_neverAppearsInTheLogs(CapturedOutput output) throws Exception {
        String member = account(email());
        String wrong = "Never-Logged-" + UUID.randomUUID();
        deactivate(tokens.issue(member), wrong);
        ok(deactivate(tokens.issue(member), PASSWORD), 204);

        assertThat(output.getAll()).doesNotContain(wrong).doesNotContain(PASSWORD);
    }
}
