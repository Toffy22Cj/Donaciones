package com.traceability.app.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.app.TraceabilityApplication;
import identity.application.service.CreateAccountService;
import identity.application.service.DeactivateAccountService;
import identity.domain.model.AccountId;
import identity.domain.model.AuditActor;
import identity.domain.model.Email;
import org.bson.Document;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [Plan: nombre] ADR-050 D3 y Q1 (a) (Enmienda 1, parte 3, E3-4), contra Tomcat real con {@code identity} real:
 * {@code POST /api/v1/auth/login} exige las versiones legales vigentes. Numeración de la definición de hecho de la parte
 * 3: 1, 2, 3 (el 401 byte a byte, nunca 403) y 4.
 */
@org.springframework.context.annotation.Import(com.traceability.app.support.CapturedMail.class) // ADR-050 Enmienda 2: verificar el correo
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
class LoginAcceptanceHttpIntegrationTest {

    static final String PASSWORD = "Pass123!Pass123!";
    static final String CURRENT = "v1-2026-10";
    static final String LOGIN = "/api/v1/auth/login";

    @Container
    static MongoDBContainer mongo = new MongoDBContainer(DockerImageName.parse("mongo:6.0")).withCommand("--replSet", "rs0");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongo::getReplicaSetUrl);
    }

    @LocalServerPort private int port;
    @Autowired private CreateAccountService accounts;
    @Autowired private DeactivateAccountService deactivations;
    @Autowired private MongoTemplate mongoTemplate;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    private static String newEmail() {
        return UUID.randomUUID() + "@d3.test";
    }

    /** Sin aceptaciones, como las cuentas anteriores a R-5 y las de la semilla. */
    private String withoutAcceptances(String email) {
        return accounts.createAccount(new Email(email), PASSWORD).getAccountId().value();
    }

    private HttpResponse<String> login(String email, String password, String terms, String dataPolicy) throws Exception {
        var body = json.createObjectNode().put("email", email).put("password", password);
        if (terms != null) body.put("acceptedTermsVersion", terms);
        if (dataPolicy != null) body.put("acceptedDataPolicyVersion", dataPolicy);
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + LOGIN))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(), HttpResponse.BodyHandlers.ofString());
    }

    private Document accountDoc(String accountId) {
        return mongoTemplate.findById(accountId, Document.class, "accounts");
    }

    // ── 1 ──

    @Test
    void test1_pendingVersions_withoutThemInTheBody_are403AcceptanceRequired_withOnlyThePending_andNoToken()
            throws Exception {
        String email = newEmail();
        String accountId = withoutAcceptances(email);

        HttpResponse<String> r = login(email, PASSWORD, null, null);

        assertThat(r.statusCode()).as(r.body()).isEqualTo(403);
        assertThat(r.headers().firstValue("Content-Type")).hasValueSatisfying(v -> assertThat(v).startsWith("application/problem+json"));
        JsonNode body = json.readTree(r.body());
        assertThat(body.get("title").asText()).isEqualTo("AcceptanceRequired");
        assertThat(body.get("pendingTermsVersion").asText()).isEqualTo(CURRENT);
        assertThat(body.get("pendingDataPolicyVersion").asText()).isEqualTo(CURRENT);
        assertThat(body.has("token")).isFalse();
        assertThat(accountDoc(accountId).containsKey("termsVersion")).isFalse();
    }

    // ── 2 ──

    @Test
    void test2_repeatingTheLoginWithTheCurrentVersions_storesThem_andEmitsTheToken_andTheNextLoginNeedsNothing()
            throws Exception {
        String email = newEmail();
        String accountId = withoutAcceptances(email);
        assertThat(login(email, PASSWORD, null, null).statusCode()).isEqualTo(403);

        HttpResponse<String> accepted = login(email, PASSWORD, CURRENT, CURRENT);

        assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(200);
        String token = json.readTree(accepted.body()).get("token").asText();
        assertThat(token).isNotBlank();
        Document stored = accountDoc(accountId);
        assertThat(stored.getString("termsVersion")).isEqualTo(CURRENT);
        assertThat(stored.getString("dataPolicyVersion")).isEqualTo(CURRENT);
        assertThat(stored.getDate("termsAcceptedAt")).isNotNull();
        // el token emitido vale
        HttpResponse<String> me = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/me"))
                .header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(me.statusCode()).isEqualTo(200);

        assertThat(login(email, PASSWORD, null, null).statusCode()).isEqualTo(200);
    }

    // ── 3 ──

    @Test
    void test3_aWrongPassword_withPendingVersions_isTheSame401ByteForByte_never403_asAnUnknownOrInactiveAccount()
            throws Exception {
        String email = newEmail();
        withoutAcceptances(email);
        String inactive = newEmail();
        String inactiveId = withoutAcceptances(inactive);
        deactivations.deactivateAccount(new AuditActor.SystemAuditActor("d3-tests"), new AccountId(inactiveId));

        List<HttpResponse<String>> failures = List.of(
                login(email, "Wrong-Pass-123!", null, null),
                login(email, "Wrong-Pass-123!", CURRENT, CURRENT),
                login(newEmail(), PASSWORD, null, null),
                login(inactive, PASSWORD, null, null),
                login(inactive, PASSWORD, CURRENT, CURRENT));

        for (HttpResponse<String> r : failures) {
            assertThat(r.statusCode()).as(r.body()).isEqualTo(401);
            assertThat(r.body()).isEqualTo(failures.get(0).body()).doesNotContain("AcceptanceRequired")
                    .doesNotContain("pending");
        }
    }

    // ── 4 ──

    @Test
    void test4_otherVersionsThanTheCurrent_are403_withThePending_andNothingIsStored() throws Exception {
        String email = newEmail();
        String accountId = withoutAcceptances(email);

        HttpResponse<String> r = login(email, PASSWORD, "v0-2026-01", CURRENT);

        assertThat(r.statusCode()).as(r.body()).isEqualTo(403);
        assertThat(json.readTree(r.body()).get("pendingTermsVersion").asText()).isEqualTo(CURRENT);
        Document stored = accountDoc(accountId);
        assertThat(stored.containsKey("termsVersion")).isFalse();
        assertThat(stored.containsKey("dataPolicyVersion")).isFalse();
    }

    @Test
    void anAccountRegisteredWithTheCurrentVersions_logsInWithoutSendingThem() throws Exception {
        String email = newEmail();
        accounts.createAccount(new Email(email), PASSWORD, CURRENT, CURRENT);
        // ADR-050 Enmienda 2, E2-3: el registro público nace sin verificar
        com.traceability.app.support.CapturedMail.confirmVerification(port, email);

        assertThat(login(email, PASSWORD, null, null).statusCode()).isEqualTo(200);
    }
}
