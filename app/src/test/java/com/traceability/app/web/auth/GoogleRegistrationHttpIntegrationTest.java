package com.traceability.app.web.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.traceability.app.TraceabilityApplication;
import com.traceability.app.support.CapturedMail;
import com.traceability.app.support.GoogleTestTokens;
import com.traceability.contracts.authentication.TokenIssuerPort;
import identity.application.service.CreateAccountService;
import identity.domain.model.Email;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
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
 * [Propuesta] ADR-050 Enmienda 3 por HTTP: {@code POST /api/v1/auth/google/register}, tests GR-1 a GR-8. El JWKS es
 * un servidor local con una llave del test; nunca se llama a Google.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = TraceabilityApplication.class)
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
@Import(CapturedMail.class)
@TestPropertySource(properties = {
    "crypto.anchor.poll.delay=9999999",
    "crypto.anchor.submit.delay=9999999",
    "crypto.anchor.stuck-monitor.delay=9999999",
    "crypto.web3j.private-key=0x1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef",
    "crypto.web3j.node-url=http://dummy-node",
    "spring.ai.openai.api-key=dummy-api-key"
})
class GoogleRegistrationHttpIntegrationTest {

    @Container
    static MongoDBContainer mongo = new MongoDBContainer(DockerImageName.parse("mongo:6.0")).withCommand("--replSet", "rs0");

    static final GoogleTestTokens GOOGLE = new GoogleTestTokens();
    static final GoogleTestTokens.JwksServer JWKS = new GoogleTestTokens.JwksServer(GOOGLE.publicJwks());
    static final String PASSWORD = "Pass123!Pass123!";
    static final String V = "v1-2026-10";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongo::getReplicaSetUrl);
        registry.add("traceability.google.jwks-uri", JWKS::uri);
    }

    @AfterAll
    static void stopJwks() {
        JWKS.close();
    }

    @LocalServerPort private int port;
    @Autowired private CreateAccountService accounts;
    @Autowired private TokenIssuerPort tokens;
    @Autowired private MongoTemplate mongoTemplate;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    private static String newSub() {
        return "google-sub-" + UUID.randomUUID();
    }

    private static String newEmail() {
        return UUID.randomUUID() + "@g.test";
    }

    private HttpResponse<String> post(String path, String bearer, String body) throws Exception {
        HttpRequest.Builder r = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        if (bearer != null) r.header("Authorization", "Bearer " + bearer);
        return http.send(r.build(), HttpResponse.BodyHandlers.ofString());
    }

    private ObjectNode body(String idToken, String password) {
        ObjectNode b = json.createObjectNode();
        if (idToken != null) b.put("idToken", idToken);
        if (password != null) b.put("password", password);
        return b.put("acceptedTermsVersion", V).put("acceptedDataPolicyVersion", V);
    }

    private HttpResponse<String> register(ObjectNode body) throws Exception {
        return post("/api/v1/auth/google/register", null, body.toString());
    }

    private Document accountByEmail(String email) {
        return mongoTemplate.getCollection("accounts").find(new Document("email", email)).first();
    }

    private long accountsWithEmail(String email) {
        return mongoTemplate.getCollection("accounts").countDocuments(new Document("email", email));
    }

    private long audits(String accountId, String action) {
        return mongoTemplate.getCollection("identity_audit_log")
                .countDocuments(new Document("targetAccountId", accountId).append("action", action));
    }

    private String title(HttpResponse<String> r) throws Exception {
        return json.readTree(r.body()).get("title").asText();
    }

    // ── GR-1 ──

    @Test
    void gr1_registersAVerifiedLinkedAccountWithTheAcceptances_andTheTokenWorks() throws Exception {
        String email = newEmail();
        String sub = newSub();

        HttpResponse<String> r = register(body(GOOGLE.idToken(sub, email), PASSWORD));

        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        JsonNode created = json.readTree(r.body());
        String id = created.get("accountId").asText();
        assertThat(created.get("status").asText()).isEqualTo("ACTIVE");
        Document doc = accountByEmail(email);
        assertThat(doc.getString("_id")).isEqualTo(id);
        assertThat(doc.getString("googleSubject")).isEqualTo(sub);
        assertThat(doc.get("emailVerifiedAt")).isNotNull();
        assertThat(doc.getString("termsVersion")).isEqualTo(V);
        assertThat(doc.getString("dataPolicyVersion")).isEqualTo(V);
        assertThat(audits(id, "ACCOUNT_CREATED")).isEqualTo(1);
        assertThat(audits(id, "GOOGLE_LINKED")).isEqualTo(1);
        assertThat(audits(id, "EMAIL_VERIFIED")).isEqualTo(1);
        Document creation = mongoTemplate.getCollection("identity_audit_log").find(new Document("targetAccountId", id)
                .append("action", "ACCOUNT_CREATED")).first();
        assertThat(creation.get("changeSummary", Document.class).getString("via")).isEqualTo("GOOGLE");

        HttpResponse<String> me = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/me"))
                .header("Authorization", "Bearer " + created.get("token").asText()).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(me.statusCode()).isEqualTo(200);
        assertThat(json.readTree(me.body()).get("accountId").asText()).isEqualTo(id);
    }

    // ── GR-2 ──

    @Test
    void gr2_afterRegistering_bothGoogleAndThePasswordSignIn() throws Exception {
        String email = newEmail();
        String sub = newSub();
        assertThat(register(body(GOOGLE.idToken(sub, email), PASSWORD)).statusCode()).isEqualTo(201);

        HttpResponse<String> google = post("/api/v1/auth/google", null,
                json.createObjectNode().put("idToken", GOOGLE.idToken(sub, email)).toString());
        HttpResponse<String> password = post("/api/v1/auth/login", null,
                json.createObjectNode().put("email", email).put("password", PASSWORD).toString());

        assertThat(google.statusCode()).as(google.body()).isEqualTo(200);
        assertThat(password.statusCode()).as(password.body()).isEqualTo(200);
    }

    // ── GR-3 ──

    @Test
    void gr3_noVerificationMailIsSent() throws Exception {
        String email = newEmail();
        assertThat(register(body(GOOGLE.idToken(newSub(), email), PASSWORD)).statusCode()).isEqualTo(201);
        Thread.sleep(500);
        assertThat(CapturedMail.to(email)).isEmpty();
    }

    // ── GR-4 ──

    @Test
    void gr4_anEmailAlreadyRegistered_is409DuplicateEmail_andTheExistingAccountIsNotLinked() throws Exception {
        String email = newEmail();
        String existing = accounts.createAccount(new Email(email), PASSWORD).getAccountId().value();

        HttpResponse<String> r = register(body(GOOGLE.idToken(newSub(), email), PASSWORD));

        assertThat(r.statusCode()).isEqualTo(409);
        assertThat(title(r)).isEqualTo("DuplicateEmail");
        assertThat(accountsWithEmail(email)).isEqualTo(1);
        assertThat(mongoTemplate.getCollection("accounts").find(new Document("_id", existing)).first()
                .get("googleSubject")).isNull();
    }

    // ── GR-5 ──

    @Test
    void gr5_aGoogleAlreadyLinked_is409_andNoAccountIsCreated() throws Exception {
        String sub = newSub();
        String first = newEmail();
        assertThat(register(body(GOOGLE.idToken(sub, first), PASSWORD)).statusCode()).isEqualTo(201);
        String second = newEmail();

        HttpResponse<String> r = register(body(GOOGLE.idToken(sub, second), PASSWORD));

        assertThat(r.statusCode()).isEqualTo(409);
        assertThat(title(r)).isEqualTo("GoogleAccountAlreadyLinked");
        assertThat(accountsWithEmail(second)).isZero();
    }

    // ── GR-6 ──

    @Test
    void gr6_anInvalidIdToken_is400_withoutAccount() throws Exception {
        String email = newEmail();
        List<String> invalid = List.of(
                GOOGLE.sign(GOOGLE.claims(newSub(), email).issuer("https://evil.example.org").build()),
                GOOGLE.sign(GOOGLE.claims(newSub(), email).claim("email_verified", false).build()),
                "no-es-un-jwt");
        for (String token : invalid) {
            HttpResponse<String> r = register(body(token, PASSWORD));
            assertThat(r.statusCode()).isEqualTo(400);
            assertThat(title(r)).isEqualTo("GoogleIdTokenInvalid");
        }
        assertThat(accountsWithEmail(email)).isZero();
    }

    // ── GR-7 ──

    @Test
    void gr7_missingFields_shortPassword_orOldVersions_are400_beforeAskingGoogle() throws Exception {
        String email = newEmail();
        String idToken = GOOGLE.idToken(newSub(), email);
        int jwksBefore = JWKS.requests.get();

        assertThat(register(body(null, PASSWORD)).statusCode()).as("sin idToken").isEqualTo(400);
        assertThat(register(body(idToken, null)).statusCode()).as("sin contraseña").isEqualTo(400);
        HttpResponse<String> shortPassword = register(body(idToken, "corta"));
        assertThat(shortPassword.statusCode()).isEqualTo(400);
        assertThat(title(shortPassword)).isEqualTo("PasswordTooShort");
        assertThat(register(body(idToken, PASSWORD).put("acceptedTermsVersion", "v0-vieja")).statusCode())
                .as("términos no vigentes").isEqualTo(400);
        assertThat(register(body(idToken, PASSWORD).without("acceptedDataPolicyVersion")).statusCode())
                .as("sin la autorización de datos").isEqualTo(400);

        assertThat(accountsWithEmail(email)).isZero();
        assertThat(JWKS.requests.get()).as("no se llegó a verificar el idToken").isEqualTo(jwksBefore);
    }

    // ── GR-8 ──

    @Test
    void gr8_theIdTokenTheSubAndThePassword_neverReachTheLogs(CapturedOutput output) throws Exception {
        String email = newEmail();
        String sub = newSub();
        String idToken = GOOGLE.idToken(sub, email);

        HttpResponse<String> r = register(body(idToken, PASSWORD));

        assertThat(r.statusCode()).isEqualTo(201);
        assertThat(r.body()).doesNotContain(sub).doesNotContain(idToken).doesNotContain(PASSWORD);
        assertThat(output.getAll()).doesNotContain(sub).doesNotContain(idToken).doesNotContain(PASSWORD);
    }
}
