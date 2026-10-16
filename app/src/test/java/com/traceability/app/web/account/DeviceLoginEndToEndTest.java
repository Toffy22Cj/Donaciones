package com.traceability.app.web.account;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.app.TraceabilityApplication;
import com.traceability.contracts.authentication.TokenIssuerPort;
import identity.application.service.ChangeCredentialsService;
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
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Entrar con biometría, de punta a punta con Mongo real: alta con sesión, entrar con el token del dispositivo (el JWT
 * sirve en {@code /me}), los fallos dan el mismo 401 que el login con contraseña, revocar solo lo propio, y cambiar la
 * contraseña o desactivar la cuenta cortan la entrada. En la base solo queda el hash del token.
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
class DeviceLoginEndToEndTest {

    @Container
    static MongoDBContainer mongo = new MongoDBContainer(DockerImageName.parse("mongo:6.0")).withCommand("--replSet", "rs0");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongo::getReplicaSetUrl);
    }

    @LocalServerPort private int port;
    @Autowired private TokenIssuerPort tokens;
    @Autowired private CreateAccountService accounts;
    @Autowired private ChangeCredentialsService credentials;
    @Autowired private DeactivateAccountService deactivate;
    @Autowired private MongoTemplate mongoTemplate;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    private HttpResponse<String> post(String path, String bearer, String body) throws Exception {
        HttpRequest.Builder r = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(15))
                .POST(body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (bearer != null) r.header("Authorization", "Bearer " + bearer);
        if (body != null) r.header("Content-Type", "application/json");
        return http.send(r.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> me(String bearer) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/me"))
                .header("Authorization", "Bearer " + bearer).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private String newAccount() {
        return accounts.createAccount(new Email(UUID.randomUUID() + "@device.test"), "Pass123!Pass123!").getAccountId().value();
    }

    private JsonNode enroll(String accountId) throws Exception {
        HttpResponse<String> r = post("/api/v1/auth/device-credentials", tokens.issue(accountId), "{\"deviceName\":\"Pixel de Ana\"}");
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        assertThat(r.headers().firstValue("Cache-Control")).hasValue("no-store");
        return json.readTree(r.body());
    }

    private HttpResponse<String> deviceLogin(String deviceToken) throws Exception {
        return post("/api/v1/auth/device-login", null, json.writeValueAsString(Map.of("deviceToken", deviceToken)));
    }

    @Test
    void conElTokenDelDispositivo_entra_yElJwtSirve() throws Exception {
        String account = newAccount();
        JsonNode enrolled = enroll(account);
        String deviceToken = enrolled.get("deviceToken").asText();

        HttpResponse<String> login = deviceLogin(deviceToken);
        assertThat(login.statusCode()).as(login.body()).isEqualTo(200);
        String jwt = json.readTree(login.body()).get("token").asText();
        HttpResponse<String> me = me(jwt);
        assertThat(me.statusCode()).isEqualTo(200);
        assertThat(json.readTree(me.body()).get("accountId").asText()).isEqualTo(account);

        Document stored = mongoTemplate.getCollection("device_credentials")
                .find(new Document("_id", enrolled.get("credentialId").asText())).first();
        assertThat(stored).isNotNull();
        assertThat(stored.toJson()).as("nunca el token en claro").doesNotContain(deviceToken);
        assertThat(stored.getString("deviceName")).isEqualTo("Pixel de Ana");
        assertThat(stored.get("lastUsedAt")).isNotNull();
    }

    @Test
    void losFallos_sonElMismo401QueElLoginConContrasena() throws Exception {
        HttpResponse<String> unknown = deviceLogin("no-es-un-token");
        HttpResponse<String> passwordLogin = post("/api/v1/auth/login", null,
                "{\"email\":\"nadie@device.test\",\"password\":\"Pass123!Pass123!\"}");
        assertThat(unknown.statusCode()).isEqualTo(401);
        assertThat(passwordLogin.statusCode()).isEqualTo(401);
        assertThat(json.readTree(unknown.body()).get("detail")).isEqualTo(json.readTree(passwordLogin.body()).get("detail"));

        assertThat(post("/api/v1/auth/device-login", null, "{}").statusCode()).isEqualTo(400);
        assertThat(post("/api/v1/auth/device-credentials", null, null).statusCode()).as("el alta exige sesión").isEqualTo(401);
    }

    @Test
    void revocar_soloLoPropio_yDespuesYaNoEntra() throws Exception {
        String ana = newAccount();
        String otra = newAccount();
        JsonNode enrolled = enroll(ana);
        String path = "/api/v1/auth/device-credentials/" + enrolled.get("credentialId").asText() + "/revoke";

        assertThat(post(path, tokens.issue(otra), null).statusCode()).as("de otra cuenta").isEqualTo(404);
        assertThat(deviceLogin(enrolled.get("deviceToken").asText()).statusCode()).isEqualTo(200);

        assertThat(post(path, tokens.issue(ana), null).statusCode()).isEqualTo(204);
        assertThat(deviceLogin(enrolled.get("deviceToken").asText()).statusCode()).isEqualTo(401);
        assertThat(post(path, tokens.issue(ana), null).statusCode()).as("ya revocada").isEqualTo(404);
    }

    @Test
    void cambiarLaContrasena_revocaTodosLosDispositivos() throws Exception {
        String account = newAccount();
        String phone = enroll(account).get("deviceToken").asText();
        String tablet = enroll(account).get("deviceToken").asText();

        credentials.changeCredentials(new AuditActor.AccountAuditActor(new AccountId(account)), new AccountId(account),
                "OtraClave123!OtraClave");

        assertThat(deviceLogin(phone).statusCode()).isEqualTo(401);
        assertThat(deviceLogin(tablet).statusCode()).isEqualTo(401);
    }

    @Test
    void cambiarLaPropiaContrasena_desdeLaApp_tambienCortaLaHuella() throws Exception {
        // POST /account/password (ADR-050 D5) fija credentialsNotBefore: la credencial anterior ya no entra.
        String account = newAccount();
        String phone = enroll(account).get("deviceToken").asText();
        Thread.sleep(5); // el cambio, estrictamente después del alta

        HttpResponse<String> changed = post("/api/v1/account/password", tokens.issue(account),
                "{\"currentPassword\":\"Pass123!Pass123!\",\"newPassword\":\"OtraClave123!OtraClave\"}");
        assertThat(changed.statusCode()).as(changed.body()).isEqualTo(200);

        assertThat(deviceLogin(phone).statusCode()).isEqualTo(401);
        // Activarla de nuevo, ya con la contraseña nueva, vuelve a funcionar.
        String newJwt = json.readTree(changed.body()).get("token").asText();
        HttpResponse<String> again = post("/api/v1/auth/device-credentials", newJwt, "{}");
        assertThat(again.statusCode()).as(again.body()).isEqualTo(201);
        assertThat(deviceLogin(json.readTree(again.body()).get("deviceToken").asText()).statusCode()).isEqualTo(200);
    }

    @Test
    void unaCuentaDesactivada_noEntraConLaHuella() throws Exception {
        String account = newAccount();
        String token = enroll(account).get("deviceToken").asText();

        deactivate.deactivateAccount(new AuditActor.SystemAuditActor("e2e-device-login"), new AccountId(account));

        assertThat(deviceLogin(token).statusCode()).isEqualTo(401);
    }
}
