package com.traceability.app.web.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.app.TraceabilityApplication;
import com.traceability.contracts.authentication.TokenIssuerPort;
import identity.application.service.BootstrapPlatformAuthorityService;
import identity.application.service.CreateAccountService;
import identity.application.service.CreateOrganizationService;
import identity.application.service.DeactivateAccountService;
import identity.domain.model.AccountId;
import identity.domain.model.AuditActor;
import identity.domain.model.Email;
import identity.domain.model.OrganizationType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-050 D7 y test 26 (plan de cierre, §D7), contra Tomcat real con {@code identity} real:
 * {@code POST /api/v1/platform/accounts/{accountId}/reactivate}. Solo el administrador de plataforma; el resto, el
 * mismo 403 que las demás rutas de plataforma, antes de leer la cuenta. Los tokens anteriores a la desactivación siguen
 * rechazados (D4); la persona vuelve a iniciar sesión.
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
class PlatformAccountReactivationHttpIntegrationTest {

    static final String PASSWORD = "Pass123!Pass123!";
    static final AuditActor SETUP = new AuditActor.SystemAuditActor("d7-http-tests");

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
    @Autowired private BootstrapPlatformAuthorityService bootstrap;
    @Autowired private DeactivateAccountService deactivations;
    @Autowired private MongoTemplate mongoTemplate;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    private static String platform;

    @BeforeEach
    void platformAdministrator() {
        if (platform != null) return;
        String email = UUID.randomUUID() + "@d7.test";
        accounts.createAccount(new Email(email), PASSWORD);
        platform = bootstrap.bootstrap(email).value();
    }

    private String account(String email) {
        return accounts.createAccount(new Email(email), PASSWORD).getAccountId().value();
    }

    private String account() {
        return account(UUID.randomUUID() + "@d7.test");
    }

    private HttpResponse<String> send(String method, String path, String bearer, String body) throws Exception {
        HttpRequest.Builder r = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (bearer != null) r.header("Authorization", "Bearer " + bearer);
        if (body != null) r.header("Content-Type", "application/json");
        return http.send(r.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> reactivate(String byAccount, String target) throws Exception {
        return send("POST", "/api/v1/platform/accounts/" + target + "/reactivate",
                byAccount == null ? null : tokens.issue(byAccount), null);
    }

    private String status(String accountId) {
        return mongoTemplate.findById(accountId, org.bson.Document.class, "accounts").getString("status");
    }

    private long reactivationAudits(String accountId) {
        return mongoTemplate.count(Query.query(Criteria.where("targetAccountId").is(accountId)
                .and("action").is("ACCOUNT_REACTIVATED")), "identity_audit_log");
    }

    private String inactive() {
        String id = account();
        deactivations.deactivateAccount(SETUP, new AccountId(id));
        return id;
    }

    @Test
    void d7_1_thePlatformAdministrator_reactivates_with200AndTheBody() throws Exception {
        String target = inactive();

        HttpResponse<String> r = reactivate(platform, target);

        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        JsonNode body = json.readTree(r.body());
        assertThat(body.fieldNames()).toIterable().containsExactlyInAnyOrder("accountId", "status");
        assertThat(body.get("accountId").asText()).isEqualTo(target);
        assertThat(body.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(r.headers().firstValue("Cache-Control")).hasValueSatisfying(v -> assertThat(v).contains("no-store"));
        assertThat(status(target)).isEqualTo("ACTIVE");
        assertThat(reactivationAudits(target)).isEqualTo(1);
    }

    // test 26: el 403 uniforme, el mismo que el de las otras rutas de plataforma, y nada cambia
    @Test
    void d7_2_test26_membersOfAnOrganizationAndAccountsWithoutOne_receiveTheSame403_andNothingChanges() throws Exception {
        String target = inactive();
        String representative = account();
        organizations.createOrganization(SETUP, OrganizationType.FOUNDATION, new AccountId(representative), "Fundación D7");
        String outsider = account();
        String reference = send("GET", "/api/v1/platform/administrators", tokens.issue(outsider), null).body();

        for (String by : new String[]{representative, outsider}) {
            HttpResponse<String> r = reactivate(by, target);
            assertThat(r.statusCode()).as(r.body()).isEqualTo(403);
            assertThat(withoutCorrelation(r.body())).isEqualTo(withoutCorrelation(reference));
        }
        // la propia cuenta desactivada ni siquiera se autentica: su token da 401 (ADR-047 D5), no puede pedirlo
        assertThat(reactivate(target, target).statusCode()).isEqualTo(401);

        assertThat(status(target)).isEqualTo("INACTIVE");
        assertThat(reactivationAudits(target)).isZero();
    }

    // el 403 llega antes de leer la cuenta: con una cuenta inexistente, también 403 (no 404)
    @Test
    void d7_3_theRefusalComesBeforeReadingTheAccount() throws Exception {
        assertThat(reactivate(account(), UUID.randomUUID().toString()).statusCode()).isEqualTo(403);
    }

    // D4: un token emitido antes de desactivar sigue rechazado después de reactivar
    @Test
    void d7_4_aTokenIssuedBeforeTheDeactivation_isStill401AfterReactivating() throws Exception {
        String target = account();
        String oldToken = tokens.issue(target, Instant.now().minusSeconds(5));
        assertThat(send("GET", "/api/v1/me", oldToken, null).statusCode()).isEqualTo(200);
        deactivations.deactivateAccount(SETUP, new AccountId(target));

        assertThat(reactivate(platform, target).statusCode()).isEqualTo(200);

        assertThat(send("GET", "/api/v1/me", oldToken, null).statusCode()).isEqualTo(401);
    }

    // la persona vuelve a iniciar sesión y su token nuevo vale
    @Test
    void d7_5_afterReactivating_aNewLoginGivesAWorkingToken() throws Exception {
        String email = UUID.randomUUID() + "@d7.test";
        String target = account(email);
        deactivations.deactivateAccount(SETUP, new AccountId(target));
        String login = "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD
                // ADR-050 D3: la cuenta del test no aceptó nada al crearse; el login acepta las vigentes
                + "\",\"acceptedTermsVersion\":\"v1-2026-10\",\"acceptedDataPolicyVersion\":\"v1-2026-10\"}";
        assertThat(send("POST", "/api/v1/auth/login", null, login).statusCode()).isEqualTo(401);

        assertThat(reactivate(platform, target).statusCode()).isEqualTo(200);

        HttpResponse<String> logged = send("POST", "/api/v1/auth/login", null, login);
        assertThat(logged.statusCode()).as(logged.body()).isEqualTo(200);
        String token = json.readTree(logged.body()).get("token").asText();
        assertThat(send("GET", "/api/v1/me", token, null).statusCode()).isEqualTo(200);
    }

    // P-D7-3: una cuenta ya activa → 200 con el mismo cuerpo y ninguna auditoría nueva
    @Test
    void d7_6_anAlreadyActiveAccount_is200_withoutANewAudit() throws Exception {
        String target = inactive();
        assertThat(reactivate(platform, target).statusCode()).isEqualTo(200);

        HttpResponse<String> again = reactivate(platform, target);

        assertThat(again.statusCode()).isEqualTo(200);
        assertThat(json.readTree(again.body()).get("status").asText()).isEqualTo("ACTIVE");
        assertThat(reactivationAudits(target)).isEqualTo(1);
    }

    // P-D7-2: inexistente, pedida por la plataforma → 404 (solo la ve la plataforma, como DD-70)
    @Test
    void d7_7_anUnknownAccount_askedByThePlatform_is404() throws Exception {
        HttpResponse<String> r = reactivate(platform, UUID.randomUUID().toString());

        assertThat(r.statusCode()).as(r.body()).isEqualTo(404);
        assertThat(json.readTree(r.body()).get("title").asText()).isEqualTo("NotFound");
    }

    @Test
    void d7_8_withoutAJwt_is401() throws Exception {
        assertThat(reactivate(null, inactive()).statusCode()).isEqualTo(401);
    }

    private String withoutCorrelation(String body) throws Exception {
        com.fasterxml.jackson.databind.node.ObjectNode node = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(body);
        node.remove("correlationId");
        node.remove("instance");
        return node.toString();
    }
}
