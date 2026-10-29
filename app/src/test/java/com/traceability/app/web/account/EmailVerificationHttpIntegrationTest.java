package com.traceability.app.web.account;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.app.TraceabilityApplication;
import com.traceability.app.support.CapturedMail;
import identity.application.service.CreateAccountService;
import identity.domain.model.Email;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.mail.SimpleMailMessage;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [Plan] ADR-050 Enmienda 2 por HTTP, contra Tomcat real con {@code identity} real (plan §2). Numeración de la
 * Definición de hecho: 1, 2, 3, 4, 6, 10, 11 y 12, más {@code emailVerified} en {@code GET /account} (E2-1). Los
 * tests 5, 7, 8, 9 y 13 están en {@code EmailVerificationServicesIntegrationTest} ({@code identity}).
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
class EmailVerificationHttpIntegrationTest {

    @Container
    static MongoDBContainer mongo = new MongoDBContainer(DockerImageName.parse("mongo:6.0")).withCommand("--replSet", "rs0");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongo::getReplicaSetUrl);
    }

    static final String PASSWORD = "Pass123!Pass123!";
    static final String NEW_PASSWORD = "NewPass456!NewPass456!";
    static final String V = "v1-2026-10";
    static final String RESEND = "/api/v1/auth/email-verification";
    static final String CONFIRM = "/api/v1/auth/email-verification/confirm";

    @LocalServerPort private int port;
    @Autowired private CreateAccountService accounts;
    @Autowired private MongoTemplate mongoTemplate;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void reset() {
        CapturedMail.SENDER.gate = null;
    }

    private static String newEmail() {
        return UUID.randomUUID() + "@e2.test";
    }

    private HttpResponse<String> send(String method, String path, String token, String body) throws Exception {
        HttpRequest.Builder r = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (token != null) r.header("Authorization", "Bearer " + token);
        if (body != null) r.header("Content-Type", "application/json");
        return http.send(r.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> register(String email) throws Exception {
        return send("POST", "/api/v1/auth/register", null, json.createObjectNode().put("email", email)
                .put("password", PASSWORD).put("acceptedTermsVersion", V).put("acceptedDataPolicyVersion", V).toString());
    }

    private HttpResponse<String> login(String email, String password) throws Exception {
        return send("POST", "/api/v1/auth/login", null,
                json.createObjectNode().put("email", email).put("password", password).toString());
    }

    private String token(HttpResponse<String> login) throws Exception {
        assertThat(login.statusCode()).as(login.body()).isEqualTo(200);
        return json.readTree(login.body()).get("token").asText();
    }

    private HttpResponse<String> confirm(String token) throws Exception {
        return send("POST", CONFIRM, null, json.createObjectNode().put("token", token).toString());
    }

    private HttpResponse<String> resend(String email) throws Exception {
        return send("POST", RESEND, null, json.createObjectNode().put("email", email).toString());
    }

    private String registerAndVerify(String email) throws Exception {
        assertThat(register(email).statusCode()).isEqualTo(201);
        assertThat(confirm(CapturedMail.verificationToken(email)).statusCode()).isEqualTo(204);
        return token(login(email, PASSWORD));
    }

    private Document accountByEmail(String email) {
        return mongoTemplate.getCollection("accounts").find(new Document("email", email)).first();
    }

    // ── 1 ──

    @Test
    void test1_register_is201BeforeTheMailIsSent_andTheMailCarriesTheTokenInTheFragment() throws Exception {
        String email = newEmail();
        CapturedMail.SENDER.gate = new CountDownLatch(1);

        HttpResponse<String> r = register(email);

        assertThat(r.statusCode()).isEqualTo(201);
        assertThat(CapturedMail.to(email)).as("el envío sigue bloqueado: la respuesta no lo esperó").isEmpty();
        assertThat(accountByEmail(email).get("emailVerifiedAt")).isNull();
        CapturedMail.SENDER.gate.countDown();
        SimpleMailMessage mail = CapturedMail.mailsTo(email, 1).get(0);
        assertThat(mail.getText()).containsPattern("https://web\\.tests\\.paxfide\\.local/verificar-correo#token=[A-Za-z0-9_-]{43}");
    }

    // ── 2 ──

    @Test
    void test2_unverified_rightPassword_is403EmailNotVerified_andAWrongPasswordIsByteForByteTheUnknownEmail401() throws Exception {
        String email = newEmail();
        assertThat(register(email).statusCode()).isEqualTo(201);

        HttpResponse<String> forbidden = login(email, PASSWORD);
        assertThat(forbidden.statusCode()).isEqualTo(403);
        assertThat(forbidden.headers().firstValue("Content-Type")).hasValueSatisfying(v -> assertThat(v).startsWith("application/problem+json"));
        assertThat(json.readTree(forbidden.body()).get("title").asText()).isEqualTo("EmailNotVerified");
        assertThat(forbidden.body()).doesNotContain("token").doesNotContain(email);

        HttpResponse<String> wrong = login(email, "Wrong-Password-123");
        HttpResponse<String> unknown = login(newEmail(), "Wrong-Password-123");
        assertThat(wrong.statusCode()).isEqualTo(401);
        assertThat(wrong.body()).isEqualTo(unknown.body());
    }

    // ── 3 y GET /account ──

    @Test
    void test3_confirm_is204_setsEmailVerifiedAt_andTheNextLoginIs200_andGetAccountSaysVerified() throws Exception {
        String email = newEmail();
        assertThat(register(email).statusCode()).isEqualTo(201);

        HttpResponse<String> r = confirm(CapturedMail.verificationToken(email));

        assertThat(r.statusCode()).isEqualTo(204);
        assertThat(r.body()).isEmpty();
        assertThat(accountByEmail(email).getDate("emailVerifiedAt")).isNotNull();
        String jwt = token(login(email, PASSWORD));
        assertThat(json.readTree(send("GET", "/api/v1/account", jwt, null).body()))
                .isEqualTo(json.readTree("{\"email\":\"" + email + "\",\"emailVerified\":true}"));
    }

    // ── 4 ──

    @Test
    void test4_invalidTokens_areTheSame400_withTheSameBody() throws Exception {
        String email = newEmail();
        assertThat(register(email).statusCode()).isEqualTo(201);
        String used = CapturedMail.verificationToken(email);
        assertThat(confirm(used).statusCode()).isEqualTo(204);

        List<HttpResponse<String>> invalid = List.of(confirm("unknown-" + UUID.randomUUID()), confirm(used));
        for (HttpResponse<String> r : invalid) {
            assertThat(r.statusCode()).isEqualTo(400);
            assertThat(json.readTree(r.body()).get("title").asText()).isEqualTo("EmailVerificationTokenInvalid");
        }
        assertThat(withoutCorrelation(invalid.get(0).body())).isEqualTo(withoutCorrelation(invalid.get(1).body()));
        assertThat(send("POST", CONFIRM, null, "{}").statusCode()).isEqualTo(400);
    }

    private JsonNode withoutCorrelation(String body) throws Exception {
        com.fasterxml.jackson.databind.node.ObjectNode n = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(body);
        n.remove("correlationId");
        n.remove("instance");
        return n;
    }

    // ── 6 ──

    @Test
    void test6_resend_answersTheSame202_forUnverifiedVerifiedUnknownAndInactive_andOnlyTheFirstSends() throws Exception {
        String unverified = newEmail();
        assertThat(register(unverified).statusCode()).isEqualTo(201);
        String verified = newEmail();
        registerAndVerify(verified);
        String inactive = newEmail();
        String inactiveJwt = registerAndVerify(inactive);
        assertThat(send("POST", "/api/v1/account/deactivate", inactiveJwt,
                json.createObjectNode().put("currentPassword", PASSWORD).toString()).statusCode()).isEqualTo(204);
        // fuera del enfriamiento del correo del registro
        mongoTemplate.getCollection("email_verification_tokens").updateMany(new Document(),
                new Document("$set", new Document("createdAt", java.util.Date.from(java.time.Instant.now().minusSeconds(600)))));
        int before = CapturedMail.to(unverified).size();

        List<HttpResponse<String>> responses = new ArrayList<>();
        for (String email : List.of(unverified, verified, newEmail(), inactive)) {
            responses.add(resend(email));
        }

        for (HttpResponse<String> r : responses) {
            assertThat(r.statusCode()).isEqualTo(202);
            assertThat(r.body()).isEmpty();
            assertThat(r.headers().firstValue("Cache-Control")).hasValue("no-store");
            Map<String, List<String>> h = new java.util.TreeMap<>(r.headers().map());
            h.remove("date");
            Map<String, List<String>> first = new java.util.TreeMap<>(responses.get(0).headers().map());
            first.remove("date");
            assertThat(h).isEqualTo(first);
        }
        assertThat(CapturedMail.mailsTo(unverified, before + 1)).hasSize(before + 1);
        assertThat(CapturedMail.to(verified).stream().filter(m -> m.getText().contains("verificar-correo")).count()).isEqualTo(1);
    }

    // ── 10 ──

    @Test
    void test10_theTokenNeverAppearsInLogsResponsesOrTheDatabase_andIsNotAcceptedInTheQueryNorThePath(CapturedOutput output)
            throws Exception {
        String email = newEmail();
        HttpResponse<String> registered = register(email);
        String token = CapturedMail.verificationToken(email);

        assertThat(registered.body()).doesNotContain(token);
        assertThat(send("POST", CONFIRM + "?token=" + token, null, null).statusCode()).isEqualTo(400);
        assertThat(send("POST", CONFIRM + "/" + token, null, null).statusCode()).isIn(400, 401, 404);
        assertThat(accountByEmail(email).get("emailVerifiedAt")).isNull();
        HttpResponse<String> ok = confirm(token);
        assertThat(ok.statusCode()).isEqualTo(204);
        assertThat(ok.body()).doesNotContain(token);

        assertThat(output.getAll()).doesNotContain(token).doesNotContain(email);
        for (Document d : mongoTemplate.getCollection("email_verification_tokens").find()) {
            assertThat(d.toJson()).doesNotContain(token);
        }
    }

    // ── 11 ──

    @Test
    void test11_changingResettingAndDeactivating_eachSendOneNotice_withTheSubjects_andWithoutToken() throws Exception {
        String email = newEmail();
        String jwt = registerAndVerify(email);

        assertThat(send("POST", "/api/v1/account/password", jwt, json.createObjectNode()
                .put("currentPassword", PASSWORD).put("newPassword", NEW_PASSWORD).toString()).statusCode()).isEqualTo(200);
        assertThat(send("POST", "/api/v1/auth/password-reset", null,
                json.createObjectNode().put("email", email).toString()).statusCode()).isEqualTo(202);
        String resetToken = resetToken(email);
        assertThat(send("POST", "/api/v1/auth/password-reset/confirm", null, json.createObjectNode()
                .put("token", resetToken).put("newPassword", PASSWORD).toString()).statusCode()).isEqualTo(204);
        String jwt2 = token(login(email, PASSWORD));
        assertThat(send("POST", "/api/v1/account/deactivate", jwt2,
                json.createObjectNode().put("currentPassword", PASSWORD).toString()).statusCode()).isEqualTo(204);

        List<String> subjects = new ArrayList<>();
        for (SimpleMailMessage m : CapturedMail.mailsTo(email, 5)) {
            subjects.add(m.getSubject());
        }
        assertThat(subjects).contains("Tu contraseña de PaxFide cambió", "Restableciste tu contraseña de PaxFide",
                "Desactivaste tu cuenta de PaxFide");
        for (SimpleMailMessage m : CapturedMail.to(email)) {
            if (m.getSubject().startsWith("Tu contraseña") || m.getSubject().startsWith("Restableciste")
                    || m.getSubject().startsWith("Desactivaste")) {
                assertThat(m.getText()).doesNotContain("#token=").doesNotContain(resetToken)
                        .doesNotContain(PASSWORD).doesNotContain(NEW_PASSWORD);
            }
        }
    }

    private static String resetToken(String email) throws Exception {
        Pattern link = Pattern.compile("/recuperar#token=([A-Za-z0-9_-]+)");
        for (int i = 0; i < 400; i++) {
            for (SimpleMailMessage m : CapturedMail.to(email)) {
                Matcher matcher = link.matcher(m.getText());
                if (matcher.find()) {
                    return matcher.group(1);
                }
            }
            Thread.sleep(25);
        }
        throw new AssertionError("No llegó el correo de recuperación");
    }

    // ── 12 ──

    @Test
    void test12_aFailingSmtpWhenNotifying_keepsTheOperationConfirmed_andLogsTheAccountIdNotTheEmail(CapturedOutput output)
            throws Exception {
        String email = "smtp-fail-" + UUID.randomUUID() + "@e2.test";
        String accountId = accounts.createAccount(new Email(email), PASSWORD).getAccountId().value();
        String jwt = token(send("POST", "/api/v1/auth/login", null, json.createObjectNode().put("email", email)
                .put("password", PASSWORD).put("acceptedTermsVersion", V).put("acceptedDataPolicyVersion", V).toString()));

        HttpResponse<String> changed = send("POST", "/api/v1/account/password", jwt, json.createObjectNode()
                .put("currentPassword", PASSWORD).put("newPassword", NEW_PASSWORD).toString());

        assertThat(changed.statusCode()).isEqualTo(200);
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (!output.getAll().contains("security-notice.mail.failed") && System.nanoTime() < deadline) {
            Thread.sleep(25);
        }
        assertThat(output.getAll()).contains("security-notice.mail.failed accountId=" + accountId + " kind=PASSWORD_CHANGED")
                .doesNotContain(email);
        assertThat(login(email, NEW_PASSWORD).statusCode()).as("el cambio quedó confirmado").isNotEqualTo(401);
    }
}
