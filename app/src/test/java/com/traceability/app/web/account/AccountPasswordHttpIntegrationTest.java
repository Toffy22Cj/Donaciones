package com.traceability.app.web.account;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.app.TraceabilityApplication;
import com.traceability.contracts.authentication.TokenIssuerPort;
import identity.application.service.CreateAccountService;
import identity.application.service.DeactivateAccountService;
import identity.application.service.ReactivateAccountService;
import identity.domain.model.AccountId;
import identity.domain.model.AuditActor;
import identity.domain.model.Email;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-050 D4 y D5 (plan R-3 + R-2), contra Tomcat real con {@code identity} real: {@code POST /api/v1/account/password}
 * cambia la contraseña, revoca todos los tokens anteriores con {@code credentialsNotBefore} y devuelve un token nuevo con
 * {@code iat = credentialsNotBefore}. Numeración de la definición de hecho de ADR-050. Los casos del mismo segundo fijan
 * el reloj del contexto, que leen los servicios de {@code identity}, el emisor y el verificador.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = TraceabilityApplication.class)
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
@Import(AccountPasswordHttpIntegrationTest.TestBeans.class)
@TestPropertySource(properties = {
    "crypto.anchor.poll.delay=9999999",
    "crypto.anchor.submit.delay=9999999",
    "crypto.anchor.stuck-monitor.delay=9999999",
    "crypto.web3j.private-key=0x1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef",
    "crypto.web3j.node-url=http://dummy-node",
    "spring.ai.openai.api-key=dummy-api-key"
})
class AccountPasswordHttpIntegrationTest {

    static final String PASSWORD = "Pass123!Pass123!";
    static final String NEW_PASSWORD = "NewPass456!NewPass456!";
    static final String PATH = "/api/v1/account/password";

    /** Reloj que el test puede fijar en un instante; si no, la hora real. */
    static final class PinnableClock extends Clock {
        volatile Instant pinned;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { Instant p = pinned; return p != null ? p : Instant.now(); }
    }

    @TestConfiguration
    static class TestBeans {
        static final PinnableClock CLOCK = new PinnableClock();

        @Bean @Primary Clock pinnableClock() { return CLOCK; }
    }

    @Container
    static MongoDBContainer mongo = new MongoDBContainer(DockerImageName.parse("mongo:6.0")).withCommand("--replSet", "rs0");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongo::getReplicaSetUrl);
    }

    @LocalServerPort private int port;
    @Autowired private TokenIssuerPort tokens;
    @Autowired private CreateAccountService accounts;
    @Autowired private DeactivateAccountService deactivations;
    @Autowired private ReactivateAccountService reactivations;
    @Autowired private MongoTemplate mongoTemplate;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();
    private static final AuditActor SETUP = new AuditActor.SystemAuditActor("r3-r2-tests");

    @BeforeEach
    void realClock() {
        TestBeans.CLOCK.pinned = null;
    }

    /** Fija el reloj a mitad de un segundo, para que todas las peticiones del test caigan en él. */
    private static Instant pinToTheMiddleOfASecond() {
        Instant t = Instant.now().truncatedTo(ChronoUnit.SECONDS).plusMillis(750);
        TestBeans.CLOCK.pinned = t;
        return t;
    }

    private static String newEmail() {
        return UUID.randomUUID() + "@r3.test";
    }

    private String account(String email) {
        return accounts.createAccount(new Email(email), PASSWORD).getAccountId().value();
    }

    private HttpResponse<String> send(String method, String path, String token, String body) throws Exception {
        HttpRequest.Builder r = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (token != null) r.header("Authorization", "Bearer " + token);
        if (body != null) r.header("Content-Type", "application/json");
        return http.send(r.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String login(String email, String password) throws Exception {
        HttpResponse<String> r = send("POST", "/api/v1/auth/login", null,
                "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return json.readTree(r.body()).get("token").asText();
    }

    private HttpResponse<String> changePassword(String token, String current, String next) throws Exception {
        return send("POST", PATH, token, json.createObjectNode()
                .put("currentPassword", current).put("newPassword", next).toString());
    }

    /** 200 con exactamente {@code {token}} y {@code no-store}; devuelve el token. */
    private String changed(HttpResponse<String> r) throws Exception {
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        assertThat(r.headers().firstValue("Cache-Control")).hasValue("no-store");
        JsonNode body = json.readTree(r.body());
        assertThat(body.size()).as(r.body()).isEqualTo(1);
        return body.get("token").asText();
    }

    private int statusWith(String token) throws Exception {
        return send("GET", "/api/v1/account", token, null).statusCode();
    }

    private static Instant iatOf(String token) throws Exception {
        String payload = new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]));
        return Instant.ofEpochSecond(new ObjectMapper().readTree(payload).get("iat").asLong());
    }

    private Document accountDocument(String accountId) {
        return mongoTemplate.findOne(Query.query(Criteria.where("_id").is(accountId)), Document.class, "accounts");
    }

    private long credentialsChangedAudits(String accountId) {
        return mongoTemplate.count(Query.query(Criteria.where("targetAccountId").is(accountId)
                .and("action").is("CREDENTIALS_CHANGED")), "identity_audit_log");
    }

    private String title(HttpResponse<String> r) throws Exception {
        return json.readTree(r.body()).get("title").asText();
    }

    /** ADR-050 test 8: el hash, {@code credentialsNotBefore} y la auditoría no cambian; la sesión sigue. */
    private void assertNothingChanged(String email, String accountId, String token, Document before) throws Exception {
        Document after = accountDocument(accountId);
        assertThat(after.getString("passwordHash")).isEqualTo(before.getString("passwordHash"));
        assertThat(after.get("credentialsNotBefore")).isEqualTo(before.get("credentialsNotBefore"));
        assertThat(credentialsChangedAudits(accountId)).isZero();
        assertThat(statusWith(token)).as("la sesión actual sigue").isEqualTo(200);
        login(email, PASSWORD);
    }

    // --- 1 y 6 ---

    @Test
    void afterTheChange_thePreviousTokenIs401_theSame401AsWithoutJwt_andTheNewOneWorks() throws Exception {
        String email = newEmail();
        account(email);
        String previous = login(email, PASSWORD);

        String next = changed(changePassword(previous, PASSWORD, NEW_PASSWORD));

        HttpResponse<String> revoked = send("GET", "/api/v1/account", previous, null);
        HttpResponse<String> noJwt = send("GET", "/api/v1/account", null, null);
        assertThat(revoked.statusCode()).isEqualTo(401);
        assertThat(revoked.body()).isEqualTo(noJwt.body());
        assertThat(statusWith(next)).isEqualTo(200);
        login(email, NEW_PASSWORD);
        assertThat(send("POST", "/api/v1/auth/login", null,
                "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}").statusCode()).isEqualTo(401);
    }

    // --- 2 y 3 ---

    @Test
    void aTokenIssuedInTheSameSecondBeforeTheChange_is401_andTheReturnedTokenWorksInThatSameSecond() throws Exception {
        String email = newEmail();
        String accountId = account(email);
        Instant t = pinToTheMiddleOfASecond();
        String sameSecond = login(email, PASSWORD);
        assertThat(iatOf(sameSecond)).isEqualTo(t.truncatedTo(ChronoUnit.SECONDS));

        String returned = changed(changePassword(sameSecond, PASSWORD, NEW_PASSWORD));

        Instant cnb = accountDocument(accountId).getDate("credentialsNotBefore").toInstant();
        assertThat(cnb).isEqualTo(t.truncatedTo(ChronoUnit.SECONDS).plusSeconds(1));
        assertThat(iatOf(returned)).as("iat = credentialsNotBefore").isEqualTo(cnb);
        assertThat(statusWith(sameSecond)).isEqualTo(401);
        assertThat(statusWith(returned)).isEqualTo(200);
        assertThat(TestBeans.CLOCK.instant()).as("todo pasó en el mismo segundo").isEqualTo(t);
    }

    // --- 3b ---

    @Test
    void twoChangesInTheSameSecond_theTokenOfTheFirstIs401_andTheTokenOfTheSecondWorks() throws Exception {
        String email = newEmail();
        account(email);
        pinToTheMiddleOfASecond();
        String initial = login(email, PASSWORD);

        String first = changed(changePassword(initial, PASSWORD, NEW_PASSWORD));
        String second = changed(changePassword(first, NEW_PASSWORD, "ThirdPass789!ThirdPass789!"));

        assertThat(iatOf(second)).isAfter(iatOf(first));
        assertThat(statusWith(initial)).isEqualTo(401);
        assertThat(statusWith(first)).isEqualTo(401);
        assertThat(statusWith(second)).isEqualTo(200);
    }

    // --- 4 ---

    @Test
    void deactivateReactivate_andAPreviousTokenIs401() throws Exception {
        String email = newEmail();
        String accountId = account(email);
        Instant t = pinToTheMiddleOfASecond();
        String previous = login(email, PASSWORD);
        assertThat(statusWith(previous)).isEqualTo(200);

        deactivations.deactivateAccount(SETUP, new AccountId(accountId));
        reactivations.reactivateAccount(SETUP, new AccountId(accountId));

        assertThat(statusWith(previous)).isEqualTo(401);
        // ADR-050 Enmienda 1, E1-2 (plan P-R3-3, E-4): el login del mismo segundo emite con iat = credentialsNotBefore
        // y su token vale; ya no hace falta avanzar el reloj
        assertThat(TestBeans.CLOCK.instant()).as("el mismo segundo de la revocación").isEqualTo(t);
        assertThat(statusWith(login(email, PASSWORD))).as("tras reactivar, se inicia sesión de nuevo").isEqualTo(200);
    }

    // --- ADR-050 Enmienda 1 (plan P-R3-3), E-1 y E-2 ---

    @Test
    void p_r3_3_changeThePasswordAndLogInInTheSameSecond_theTokenWorks() throws Exception {
        String email = newEmail();
        account(email);
        Instant t = pinToTheMiddleOfASecond();
        String initial = login(email, PASSWORD);

        changed(changePassword(initial, PASSWORD, NEW_PASSWORD));
        String afterLogin = login(email, NEW_PASSWORD);

        assertThat(TestBeans.CLOCK.instant()).as("todo en el mismo segundo").isEqualTo(t);
        assertThat(statusWith(afterLogin)).isEqualTo(200);
        assertThat(statusWith(initial)).as("E-3: el token anterior sigue revocado").isEqualTo(401);
    }

    @Test
    void p_r3_3_twoChangesInARowAndAnImmediateLogin_theTokenWorks() throws Exception {
        String email = newEmail();
        account(email);
        pinToTheMiddleOfASecond();
        String initial = login(email, PASSWORD);

        String first = changed(changePassword(initial, PASSWORD, NEW_PASSWORD));
        changed(changePassword(first, NEW_PASSWORD, "ThirdPass789!ThirdPass789!"));
        String afterLogin = login(email, "ThirdPass789!ThirdPass789!");

        assertThat(statusWith(afterLogin)).isEqualTo(200);
        assertThat(statusWith(initial)).isEqualTo(401);
        assertThat(statusWith(first)).isEqualTo(401);
    }

    // --- 5 ---

    @Test
    void anAccountWithoutCredentialsNotBefore_keepsItsToken() throws Exception {
        String email = newEmail();
        String accountId = account(email);
        String token = login(email, PASSWORD);

        assertThat(accountDocument(accountId).get("credentialsNotBefore")).isNull();
        assertThat(statusWith(token)).isEqualTo(200);
        assertThat(statusWith(tokens.issue(accountId))).isEqualTo(200);
    }

    // --- 8, P-R3-1 y 9 ---

    @Test
    void wrongCurrentPassword_is400CurrentPasswordIncorrect_never401_andNothingChanges() throws Exception {
        String email = newEmail();
        String accountId = account(email);
        String token = login(email, PASSWORD);
        Document before = accountDocument(accountId);

        HttpResponse<String> r = changePassword(token, "NotTheCurrent-123", NEW_PASSWORD);

        assertThat(r.statusCode()).as(r.body()).isEqualTo(400);
        assertThat(title(r)).isEqualTo("CurrentPasswordIncorrect");
        assertNothingChanged(email, accountId, token, before);
    }

    @Test
    void wrongCurrentPasswordAndShortNewOne_isCurrentPasswordIncorrect() throws Exception {
        String email = newEmail();
        String accountId = account(email);
        String token = login(email, PASSWORD);
        Document before = accountDocument(accountId);

        HttpResponse<String> r = changePassword(token, "NotTheCurrent-123", "short");

        assertThat(r.statusCode()).as(r.body()).isEqualTo(400);
        assertThat(title(r)).isEqualTo("CurrentPasswordIncorrect");
        assertNothingChanged(email, accountId, token, before);
    }

    @Test
    void shortNewPassword_is400PasswordTooShort_andNothingChanges() throws Exception {
        String email = newEmail();
        String accountId = account(email);
        String token = login(email, PASSWORD);
        Document before = accountDocument(accountId);

        HttpResponse<String> r = changePassword(token, PASSWORD, "elevenchars");

        assertThat(r.statusCode()).as(r.body()).isEqualTo(400);
        assertThat(title(r)).isEqualTo("PasswordTooShort");
        assertNothingChanged(email, accountId, token, before);
    }

    // --- campos vacíos y sin JWT ---

    @Test
    void emptyFields_are400_withoutCallingTheDomain() throws Exception {
        String email = newEmail();
        String accountId = account(email);
        String token = login(email, PASSWORD);
        Document before = accountDocument(accountId);

        for (String body : new String[]{
                "{}",
                "{\"currentPassword\":\"\",\"newPassword\":\"" + NEW_PASSWORD + "\"}",
                "{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"   \"}",
                "{\"currentPassword\":\"" + PASSWORD + "\"}"}) {
            HttpResponse<String> r = send("POST", PATH, token, body);
            assertThat(r.statusCode()).as(body + " -> " + r.body()).isEqualTo(400);
            assertThat(title(r)).isNotEqualTo("CurrentPasswordIncorrect").isNotEqualTo("PasswordTooShort");
        }
        assertNothingChanged(email, accountId, token, before);
    }

    @Test
    void withoutJwt_orWithAnInvalidOne_is401_andNothingChanges() throws Exception {
        String email = newEmail();
        String accountId = account(email);
        Document before = accountDocument(accountId);
        String body = json.createObjectNode().put("currentPassword", PASSWORD).put("newPassword", NEW_PASSWORD).toString();

        assertThat(send("POST", PATH, null, body).statusCode()).isEqualTo(401);
        assertThat(send("POST", PATH, "not-a-jwt", body).statusCode()).isEqualTo(401);

        assertThat(accountDocument(accountId).getString("passwordHash")).isEqualTo(before.getString("passwordHash"));
        assertThat(credentialsChangedAudits(accountId)).isZero();
    }

    // --- ninguna contraseña ni token en los logs ---

    @Test
    void neitherPasswordsNorTokens_reachTheLogs(CapturedOutput output) throws Exception {
        String email = newEmail();
        account(email);
        String token = login(email, PASSWORD);
        int from = output.getAll().length();

        String returned = changed(changePassword(token, PASSWORD, NEW_PASSWORD));
        changePassword(returned, "NotTheCurrent-123", "AnotherPass-000");
        changePassword(returned, NEW_PASSWORD, "short");

        String logs = output.getAll().substring(from);
        assertThat(logs).doesNotContain(PASSWORD).doesNotContain(NEW_PASSWORD)
                .doesNotContain("NotTheCurrent-123").doesNotContain("AnotherPass-000");
        for (String t : new String[]{token, returned}) {
            for (String part : t.split("\\.")) {
                assertThat(logs).doesNotContain(part);
            }
        }
    }
}
