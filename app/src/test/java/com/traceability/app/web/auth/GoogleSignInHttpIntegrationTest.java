package com.traceability.app.web.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.traceability.app.TraceabilityApplication;
import com.traceability.app.support.CapturedMail;
import com.traceability.app.support.GoogleTestTokens;
import com.traceability.contracts.authentication.TokenIssuerPort;
import identity.application.service.CreateAccountService;
import identity.application.service.DeactivateAccountService;
import identity.domain.model.AccountId;
import identity.domain.model.AuditActor;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [Plan] ADR-050 D9 por HTTP, con las tres ampliaciones de Manuel (#164): tests 27 a 29 y G-1 a G-18 del plan de
 * Google. El JWKS es un servidor local con una llave del test; nunca se llama a Google. Google caído (G-6) y sin ID de
 * cliente (G-8) van en sus propias clases, porque el JWKS y su configuración son del contexto.
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
class GoogleSignInHttpIntegrationTest {

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
    @Autowired private DeactivateAccountService deactivations;
    @Autowired private TokenIssuerPort tokens;
    @Autowired private MongoTemplate mongoTemplate;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    private record Acct(String id, String email) {}

    /** Una cuenta de sistema (verificada, P-E2V-2) que ya aceptó las versiones vigentes. */
    private Acct account() throws Exception {
        String email = UUID.randomUUID() + "@g.test";
        String id = accounts.createAccount(new Email(email), PASSWORD).getAccountId().value();
        mongoTemplate.getCollection("accounts").updateOne(new Document("_id", id), new Document("$set",
                new Document("termsVersion", V).append("termsAcceptedAt", new java.util.Date())
                        .append("dataPolicyVersion", V).append("dataPolicyAcceptedAt", new java.util.Date())));
        return new Acct(id, email);
    }

    private static String newSub() {
        return "google-sub-" + UUID.randomUUID();
    }

    private HttpResponse<String> send(String method, String path, String accountId, String body) throws Exception {
        HttpRequest.Builder r = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (accountId != null) r.header("Authorization", "Bearer " + tokens.issue(accountId));
        if (body != null) r.header("Content-Type", "application/json");
        return http.send(r.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> google(String idToken) throws Exception {
        return send("POST", "/api/v1/auth/google", null, json.createObjectNode().put("idToken", idToken).toString());
    }

    private HttpResponse<String> link(String accountId, String idToken, String password) throws Exception {
        ObjectNode body = json.createObjectNode().put("idToken", idToken);
        if (password != null) body.put("currentPassword", password);
        return send("POST", "/api/v1/account/google/link", accountId, body.toString());
    }

    private HttpResponse<String> unlink(String accountId, String password) throws Exception {
        ObjectNode body = json.createObjectNode();
        if (password != null) body.put("currentPassword", password);
        return send("DELETE", "/api/v1/account/google/link", accountId, body.toString());
    }

    private Document accountDoc(String id) {
        return mongoTemplate.getCollection("accounts").find(new Document("_id", id)).first();
    }

    private long audits(String accountId, String action) {
        return mongoTemplate.getCollection("identity_audit_log")
                .countDocuments(new Document("targetAccountId", accountId).append("action", action));
    }

    /** El cuerpo sin {@code instance}, que lleva la ruta y por eso difiere entre los dos logins. */
    private JsonNode withoutInstance(String body) throws Exception {
        ObjectNode n = (ObjectNode) json.readTree(body);
        n.remove("instance");
        return n;
    }

    private HttpResponse<String> wrongPasswordLogin() throws Exception {
        return send("POST", "/api/v1/auth/login", null, json.createObjectNode().put("email", account().email())
                .put("password", "Wrong-Password-123").toString());
    }

    // ── 27 ──

    @Test
    void test27_invalidIdTokens_areTheSame401AsAWrongPassword() throws Exception {
        Acct a = account();
        String sub = newSub();
        assertThat(link(a.id(), GOOGLE.idToken(sub, a.email()), PASSWORD).statusCode()).isEqualTo(204);
        JsonNode wrong = withoutInstance(wrongPasswordLogin().body());

        List<String> invalid = List.of(
                GOOGLE.sign(GOOGLE.claims(sub, a.email()).issuer("https://evil.example.org").build()),
                GOOGLE.sign(GOOGLE.claims(sub, a.email()).audience(List.of("otro-cliente")).build()),
                GOOGLE.sign(GOOGLE.claims(sub, a.email()).claim("email_verified", false).build()),
                GOOGLE.sign(GOOGLE.claims(sub, a.email()).expirationTime(new java.util.Date(System.currentTimeMillis() - 3_600_000))
                        .issueTime(new java.util.Date(System.currentTimeMillis() - 7_200_000)).build()),
                GoogleTestTokens.sign(GOOGLE.claims(sub, a.email()).build(),
                        new com.nimbusds.jose.jwk.gen.RSAKeyGenerator(2048).keyID("otra").generate()),
                "no-es-un-jwt");
        for (String token : invalid) {
            HttpResponse<String> r = google(token);
            assertThat(r.statusCode()).isEqualTo(401);
            assertThat(withoutInstance(r.body())).isEqualTo(wrong);
        }
        assertThat(google("").statusCode()).as("sin idToken").isEqualTo(400);
    }

    // ── 28 ──

    @Test
    void test28_aValidIdTokenOfAnUnlinkedAccountsEmail_is401_andNothingIsLinked() throws Exception {
        Acct a = account();

        HttpResponse<String> r = google(GOOGLE.idToken(newSub(), a.email()));

        assertThat(r.statusCode()).isEqualTo(401);
        assertThat(accountDoc(a.id()).get("googleSubject")).isNull();
    }

    // ── 29 ──

    @Test
    void test29_afterLinking_googleSignsInThatAccount_withTheE12IssuedAt() throws Exception {
        Acct a = account();
        String sub = newSub();
        assertThat(link(a.id(), GOOGLE.idToken(sub, a.email()), PASSWORD).statusCode()).isEqualTo(204);
        // justo después de cambiar la contraseña: credentialsNotBefore está en el segundo siguiente (D4); con la regla de
        // E1-2 el token vale, con el reloj solo no
        assertThat(send("POST", "/api/v1/account/password", a.id(), json.createObjectNode()
                .put("currentPassword", PASSWORD).put("newPassword", "NewPass456!NewPass456!").toString()).statusCode())
                .isEqualTo(200);

        HttpResponse<String> r = google(GOOGLE.idToken(sub, a.email()));

        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        String token = json.readTree(r.body()).get("token").asText();
        HttpResponse<String> me = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/me"))
                .header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(me.statusCode()).isEqualTo(200);
        assertThat(json.readTree(me.body()).get("accountId").asText()).isEqualTo(a.id());
    }

    // ── G-1, G-2, G-3 ──

    @Test
    void g1_aLinkedButInactiveAccount_isTheSame401() throws Exception {
        Acct a = account();
        String sub = newSub();
        assertThat(link(a.id(), GOOGLE.idToken(sub, a.email()), PASSWORD).statusCode()).isEqualTo(204);
        deactivations.deactivateAccount(new AuditActor.SystemAuditActor("g-tests"), new AccountId(a.id()));

        HttpResponse<String> r = google(GOOGLE.idToken(sub, a.email()));

        assertThat(r.statusCode()).isEqualTo(401);
        assertThat(withoutInstance(r.body())).isEqualTo(withoutInstance(wrongPasswordLogin().body()));
    }

    @Test
    void g2_aLinkedAccountWithoutAVerifiedEmail_is403EmailNotVerified_andLinkingWithAnotherEmailDoesNotVerify() throws Exception {
        String email = UUID.randomUUID() + "@g.test";
        String id = accounts.createAccount(new Email(email), PASSWORD, V, V).getAccountId().value();
        String sub = newSub();
        assertThat(link(id, GOOGLE.idToken(sub, "otra-" + email), PASSWORD).statusCode()).isEqualTo(204);
        assertThat(accountDoc(id).get("emailVerifiedAt")).isNull();

        HttpResponse<String> r = google(GOOGLE.idToken(sub, "otra-" + email));

        assertThat(r.statusCode()).isEqualTo(403);
        assertThat(json.readTree(r.body()).get("title").asText()).isEqualTo("EmailNotVerified");
    }

    @Test
    void pg1_linkingAGoogleWithTheSameEmail_verifiesTheAccount_withAnAuditViaGoogleLink() throws Exception {
        String email = UUID.randomUUID() + "@g.test";
        String id = accounts.createAccount(new Email(email), PASSWORD, V, V).getAccountId().value();

        assertThat(link(id, GOOGLE.idToken(newSub(), email.toUpperCase(java.util.Locale.ROOT)), PASSWORD).statusCode())
                .isEqualTo(204);

        assertThat(accountDoc(id).get("emailVerifiedAt")).isNotNull();
        Document audit = mongoTemplate.getCollection("identity_audit_log").find(new Document("targetAccountId", id)
                .append("action", "EMAIL_VERIFIED")).first();
        assertThat(audit.get("changeSummary", Document.class).getString("via")).isEqualTo("GOOGLE_LINK");
    }

    @Test
    void g3_withoutTheCurrentAcceptances_is403AcceptanceRequired_andRepeatingWithTheVersionsSignsIn() throws Exception {
        String email = UUID.randomUUID() + "@g.test";
        String id = accounts.createAccount(new Email(email), PASSWORD).getAccountId().value();
        String sub = newSub();
        assertThat(link(id, GOOGLE.idToken(sub, email), PASSWORD).statusCode()).isEqualTo(204);
        String idToken = GOOGLE.idToken(sub, email);

        HttpResponse<String> pending = google(idToken);
        assertThat(pending.statusCode()).isEqualTo(403);
        assertThat(json.readTree(pending.body()).get("title").asText()).isEqualTo("AcceptanceRequired");

        HttpResponse<String> accepted = send("POST", "/api/v1/auth/google", null, json.createObjectNode()
                .put("idToken", idToken).put("acceptedTermsVersion", V).put("acceptedDataPolicyVersion", V).toString());
        assertThat(accepted.statusCode()).isEqualTo(200);
        assertThat(accountDoc(id).getString("termsVersion")).isEqualTo(V);
    }

    // ── G-4, G-5 ──

    @Test
    void g4_conflictsWhenLinking_andRepeatingTheSameLinkChangesNothing() throws Exception {
        Acct a = account();
        Acct b = account();
        String sub = newSub();
        assertThat(link(a.id(), GOOGLE.idToken(sub, a.email()), PASSWORD).statusCode()).isEqualTo(204);

        HttpResponse<String> taken = link(b.id(), GOOGLE.idToken(sub, a.email()), PASSWORD);
        assertThat(taken.statusCode()).isEqualTo(409);
        assertThat(json.readTree(taken.body()).get("title").asText()).isEqualTo("GoogleAccountAlreadyLinked");

        HttpResponse<String> second = link(a.id(), GOOGLE.idToken(newSub(), a.email()), PASSWORD);
        assertThat(second.statusCode()).isEqualTo(409);
        assertThat(json.readTree(second.body()).get("title").asText()).isEqualTo("AccountAlreadyLinkedToGoogle");

        assertThat(link(a.id(), GOOGLE.idToken(sub, a.email()), PASSWORD).statusCode()).isEqualTo(204);
        assertThat(audits(a.id(), "GOOGLE_LINKED")).isEqualTo(1);
    }

    @Test
    void g5_linkingWithoutSession_orWithAnInvalidOrEmptyIdToken() throws Exception {
        Acct a = account();
        assertThat(send("POST", "/api/v1/account/google/link", null, "{\"idToken\":\"x\",\"currentPassword\":\"x\"}")
                .statusCode()).isEqualTo(401);
        HttpResponse<String> bad = link(a.id(), "no-es-un-jwt", PASSWORD);
        assertThat(bad.statusCode()).isEqualTo(400);
        assertThat(json.readTree(bad.body()).get("title").asText()).isEqualTo("GoogleIdTokenInvalid");
        assertThat(link(a.id(), "", PASSWORD).statusCode()).isEqualTo(400);
        assertThat(accountDoc(a.id()).get("googleSubject")).isNull();
    }

    // ── G-7 ──

    @Test
    void g7_theJwksIsCached_twoSignInsDoNotFetchItAgain() throws Exception {
        Acct a = account();
        String sub = newSub();
        assertThat(link(a.id(), GOOGLE.idToken(sub, a.email()), PASSWORD).statusCode()).isEqualTo(204);
        int before = JWKS.requests.get();

        assertThat(google(GOOGLE.idToken(sub, a.email())).statusCode()).isEqualTo(200);
        assertThat(google(GOOGLE.idToken(sub, a.email())).statusCode()).isEqualTo(200);

        assertThat(JWKS.requests.get()).isEqualTo(before).isEqualTo(1);
    }

    // ── G-10 ──

    @Test
    void g10_theIdTokenTheSubAndTheEmail_neverReachTheLogsNorTheResponses(CapturedOutput output) throws Exception {
        Acct a = account();
        String sub = newSub();
        String idToken = GOOGLE.idToken(sub, a.email());
        HttpResponse<String> linked = link(a.id(), idToken, PASSWORD);
        HttpResponse<String> signedIn = google(idToken);
        HttpResponse<String> rejected = google(GOOGLE.idToken(newSub(), a.email()));

        for (HttpResponse<String> r : List.of(linked, signedIn, rejected)) {
            assertThat(r.body()).doesNotContain(sub).doesNotContain(idToken).doesNotContain(a.email());
        }
        assertThat(output.getAll()).doesNotContain(sub).doesNotContain(idToken);
    }

    // ── G-14 a G-18 (ampliaciones de Manuel) ──

    @Test
    void g14_linkingWithAWrongOrMissingPassword_isTheDeactivation400_withoutLinkingNorVerifyingTheIdToken() throws Exception {
        Acct a = account();
        HttpResponse<String> deactivation = send("POST", "/api/v1/account/deactivate", a.id(),
                json.createObjectNode().put("currentPassword", "Wrong-Password-123").toString());
        int jwksBefore = JWKS.requests.get();

        HttpResponse<String> wrong = link(a.id(), GOOGLE.idToken(newSub(), a.email()), "Wrong-Password-123");
        HttpResponse<String> missing = link(a.id(), GOOGLE.idToken(newSub(), a.email()), null);

        for (HttpResponse<String> r : List.of(wrong, missing)) {
            assertThat(r.statusCode()).isEqualTo(deactivation.statusCode()).isEqualTo(400);
            assertThat(withoutCorrelation(r.body())).isEqualTo(withoutCorrelation(deactivation.body()));
        }
        assertThat(json.readTree(wrong.body()).get("title").asText()).isEqualTo("CurrentPasswordIncorrect");
        assertThat(accountDoc(a.id()).get("googleSubject")).isNull();
        assertThat(JWKS.requests.get()).as("no se llegó a verificar el idToken").isEqualTo(jwksBefore);
    }

    private JsonNode withoutCorrelation(String body) throws Exception {
        ObjectNode n = (ObjectNode) json.readTree(body);
        n.remove("instance");
        n.remove("correlationId");
        return n;
    }

    @Test
    void g15_unlinking_removesTheSub_audits_andGoogleNoLongerSignsIn() throws Exception {
        Acct a = account();
        String sub = newSub();
        assertThat(link(a.id(), GOOGLE.idToken(sub, a.email()), PASSWORD).statusCode()).isEqualTo(204);

        assertThat(unlink(a.id(), PASSWORD).statusCode()).isEqualTo(204);

        assertThat(accountDoc(a.id()).get("googleSubject")).isNull();
        assertThat(audits(a.id(), "GOOGLE_UNLINKED")).isEqualTo(1);
        assertThat(google(GOOGLE.idToken(sub, a.email())).statusCode()).isEqualTo(401);
    }

    @Test
    void g16_unlinkingWithAWrongPassword_withoutSession_orWithoutALink() throws Exception {
        Acct a = account();
        String sub = newSub();
        assertThat(link(a.id(), GOOGLE.idToken(sub, a.email()), PASSWORD).statusCode()).isEqualTo(204);

        HttpResponse<String> wrong = unlink(a.id(), "Wrong-Password-123");
        assertThat(wrong.statusCode()).isEqualTo(400);
        assertThat(json.readTree(wrong.body()).get("title").asText()).isEqualTo("CurrentPasswordIncorrect");
        assertThat(accountDoc(a.id()).getString("googleSubject")).isEqualTo(sub);
        assertThat(send("DELETE", "/api/v1/account/google/link", null, "{\"currentPassword\":\"x\"}").statusCode())
                .isEqualTo(401);

        Acct unlinked = account();
        assertThat(unlink(unlinked.id(), PASSWORD).statusCode()).isEqualTo(204);
        assertThat(audits(unlinked.id(), "GOOGLE_UNLINKED")).isZero();
        Thread.sleep(500);
        assertThat(CapturedMail.to(unlinked.email())).isEmpty();
    }

    @Test
    void g17_linkingAndUnlinking_sendOneNoticeEach_withoutTheSubNorTheIdToken() throws Exception {
        Acct a = account();
        String sub = newSub();
        String idToken = GOOGLE.idToken(sub, a.email());
        assertThat(link(a.id(), idToken, PASSWORD).statusCode()).isEqualTo(204);
        assertThat(unlink(a.id(), PASSWORD).statusCode()).isEqualTo(204);

        List<String> subjects = new ArrayList<>();
        for (SimpleMailMessage m : CapturedMail.mailsTo(a.email(), 2)) {
            subjects.add(m.getSubject());
            assertThat(m.getText()).doesNotContain(sub).doesNotContain(idToken).doesNotContain("#token=");
        }
        assertThat(subjects).containsExactlyInAnyOrder("Vinculaste tu cuenta de Google a PaxFide",
                "Desvinculaste tu cuenta de Google de PaxFide");
    }

    @Test
    void g18_afterUnlinking_theSameGoogleCanBeLinkedToAnotherAccount() throws Exception {
        Acct a = account();
        Acct b = account();
        String sub = newSub();
        assertThat(link(a.id(), GOOGLE.idToken(sub, a.email()), PASSWORD).statusCode()).isEqualTo(204);
        assertThat(unlink(a.id(), PASSWORD).statusCode()).isEqualTo(204);

        assertThat(link(b.id(), GOOGLE.idToken(sub, b.email()), PASSWORD).statusCode()).isEqualTo(204);
        assertThat(accountDoc(b.id()).getString("googleSubject")).isEqualTo(sub);
    }
}
