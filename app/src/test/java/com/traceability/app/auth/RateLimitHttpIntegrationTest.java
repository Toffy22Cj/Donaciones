package com.traceability.app.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.app.TraceabilityApplication;

import identity.application.service.CreateAccountService;
import identity.domain.model.Email;

/**
 * ADR-053 (T-03) contra Tomcat real: el filtro está registrado y va antes de la autenticación; el 429 lleva
 * {@code Retry-After} y el mismo cuerpo para todos; el 401 uniforme del login se conserva y el 429 no distingue una
 * cuenta que existe de una que no; {@code X-Forwarded-For} no evita el límite.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = TraceabilityApplication.class)
@Testcontainers
@TestPropertySource(properties = {
    "crypto.anchor.poll.delay=9999999",
    "crypto.anchor.submit.delay=9999999",
    "crypto.anchor.stuck-monitor.delay=9999999",
    "crypto.web3j.private-key=0x1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef",
    "crypto.web3j.node-url=http://dummy-node",
    "spring.ai.openai.api-key=dummy-api-key",
    "traceability.rate-limit.login-email.limit=3",
    "traceability.rate-limit.login-email.window=PT10M",
    "traceability.rate-limit.groups.media-upload.limit=2",
    "traceability.rate-limit.groups.media-upload.window=PT10M",
    "traceability.rate-limit.groups.registration.limit=2",
    "traceability.rate-limit.groups.registration.window=PT10M"
})
class RateLimitHttpIntegrationTest {

    private static final String PASSWORD = "una-contraseña-larga-de-prueba";

    @Container
    static MongoDBContainer mongo = new MongoDBContainer(DockerImageName.parse("mongo:6.0")).withCommand("--replSet", "rs0");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongo::getReplicaSetUrl);
    }

    @LocalServerPort private int port;
    @Autowired private CreateAccountService accounts;
    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    private HttpResponse<String> post(String path, String body, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        for (int i = 0; i < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> login(String email, String password) throws Exception {
        return post("/api/v1/auth/login",
                json.createObjectNode().put("email", email).put("password", password).toString());
    }

    @Test
    void the429DoesNotRevealWhetherTheAccountExists_andThe401StaysUniform() throws Exception {
        accounts.createAccount(new Email("existe@ratelimit.test"), PASSWORD);

        String first401 = null;
        for (int i = 0; i < 3; i++) {
            HttpResponse<String> existing = login("existe@ratelimit.test", "contraseña-equivocada-" + i);
            HttpResponse<String> missing = login("no-existe@ratelimit.test", "contraseña-equivocada-" + i);
            assertThat(existing.statusCode()).isEqualTo(401);
            assertThat(missing.statusCode()).isEqualTo(401);
            assertThat(existing.body()).isEqualTo(missing.body());
            if (first401 == null) {
                first401 = existing.body();
            }
            assertThat(existing.body()).isEqualTo(first401);
        }

        // la cuarta, con la contraseña correcta incluida: el límite cuenta intentos, no fallos
        HttpResponse<String> existing = login("EXISTE@ratelimit.test", PASSWORD);
        HttpResponse<String> missing = login("no-existe@ratelimit.test", PASSWORD);
        assertThat(existing.statusCode()).isEqualTo(429);
        assertThat(missing.statusCode()).isEqualTo(429);
        assertThat(existing.body()).isEqualTo(missing.body());
        assertThat(existing.headers().firstValue("Content-Type")).hasValue("application/problem+json");
        long retryAfter = Long.parseLong(existing.headers().firstValue("Retry-After").orElseThrow());
        assertThat(retryAfter).isBetween(1L, 600L);
        assertThat(missing.headers().firstValue("Retry-After")).isPresent();
    }

    @Test
    void protectedUploadsAreLimitedBeforeTheJwtCheck() throws Exception {
        String path = "/api/v1/organizations/org-x/delivery-evidence";
        assertThat(post(path, "{}").statusCode()).isEqualTo(401);
        assertThat(post(path, "{}").statusCode()).isEqualTo(401);
        HttpResponse<String> third = post(path, "{}");
        assertThat(third.statusCode()).isEqualTo(429);
        assertThat(third.headers().firstValue("Retry-After")).isPresent();
    }

    @Test
    void aFakeXForwardedForDoesNotEvadeTheLimit() throws Exception {
        String body = "{}";
        post("/api/v1/auth/register", body, "X-Forwarded-For", "10.1.1.1");
        post("/api/v1/auth/register", body, "X-Forwarded-For", "10.1.1.2");
        HttpResponse<String> third = post("/api/v1/auth/register", body,
                "X-Forwarded-For", "10.1.1.3", "CF-Connecting-IP", "10.1.1.4");
        assertThat(third.statusCode()).isEqualTo(429);
    }
}
