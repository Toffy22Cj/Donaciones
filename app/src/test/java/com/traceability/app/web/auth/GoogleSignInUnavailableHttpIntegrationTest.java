package com.traceability.app.web.auth;

import com.traceability.app.TraceabilityApplication;
import com.traceability.app.support.GoogleTestTokens;
import identity.application.service.CreateAccountService;
import identity.domain.model.Email;
import org.junit.jupiter.api.AfterAll;
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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [Plan] ADR-050 D9 y P-G-9, test G-6: si el JWKS no responde, {@code POST /auth/google} da 503
 * {@code GoogleSignInUnavailable} antes de agotar los tiempos de espera, y el login con contraseña sigue funcionando.
 * El servidor JWKS es local y se deja colgado; nunca se llama a Google.
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
class GoogleSignInUnavailableHttpIntegrationTest {

    @Container
    static MongoDBContainer mongo = new MongoDBContainer(DockerImageName.parse("mongo:6.0")).withCommand("--replSet", "rs0");

    static final GoogleTestTokens GOOGLE = new GoogleTestTokens();
    static final GoogleTestTokens.JwksServer JWKS = new GoogleTestTokens.JwksServer(GOOGLE.publicJwks());

    static {
        JWKS.hang = new CountDownLatch(1);
    }

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

    private HttpResponse<String> post(String path, String body) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void g6_googleNotAnswering_is503GoogleSignInUnavailable_andThePasswordLoginStillWorks() throws Exception {
        long start = System.nanoTime();
        HttpResponse<String> r = post("/api/v1/auth/google",
                "{\"idToken\":\"" + GOOGLE.idToken("sub-g6", "g6@g.test") + "\"}");
        long seconds = (System.nanoTime() - start) / 1_000_000_000L;

        assertThat(r.statusCode()).isEqualTo(503);
        assertThat(r.body()).contains("\"title\":\"GoogleSignInUnavailable\"");
        assertThat(seconds).as("conexión y lectura de 2 s, con un reintento").isLessThan(15);

        String email = UUID.randomUUID() + "@g6.test";
        accounts.createAccount(new Email(email), "Pass123!Pass123!");
        assertThat(post("/api/v1/auth/login", "{\"email\":\"" + email + "\",\"password\":\"Pass123!Pass123!\","
                + "\"acceptedTermsVersion\":\"v1-2026-10\",\"acceptedDataPolicyVersion\":\"v1-2026-10\"}").statusCode())
                .isEqualTo(200);
    }
}
