package com.traceability.app.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.app.TraceabilityApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La semilla de la demo local (perfil {@code dev}): las cinco cuentas entran con la contraseña de la variable, el
 * administrador tiene organización y roles, la plataforma tiene autoridad, y un segundo arranque no crea nada.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = TraceabilityApplication.class)
@Testcontainers
@ActiveProfiles("dev")
@TestPropertySource(properties = {
    "crypto.anchor.poll.delay=9999999",
    "crypto.anchor.submit.delay=9999999",
    "crypto.anchor.stuck-monitor.delay=9999999",
    "crypto.web3j.private-key=0x1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef",
    "crypto.web3j.node-url=http://dummy-node",
    "spring.ai.openai.api-key=dummy-api-key",
    // Solo para tests
    "traceability.demo.webhook-secret=test-only-simulated-webhook-secret-0123456789",
    "traceability.demo.seed.enabled=true",
    "traceability.demo.seed.password=test-only-seed-password"
})
class DemoSeedRunnerIntegrationTest {

    @Container
    static MongoDBContainer mongo = new MongoDBContainer(DockerImageName.parse("mongo:6.0")).withCommand("--replSet", "rs0");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongo::getReplicaSetUrl);
    }

    @LocalServerPort private int port;
    @Autowired private DemoSeedRunner seed;
    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    private JsonNode me(String email) throws Exception {
        HttpResponse<String> login = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"email\":\"" + email + "\",\"password\":\"test-only-seed-password\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(login.statusCode()).as(email).isEqualTo(200);
        String token = json.readTree(login.body()).get("token").asText();
        return json.readTree(http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/me"))
                .header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofString()).body());
    }

    @Test
    void theSeedCreatesTheDemoWorld_andIsIdempotent() throws Exception {
        seed.run(null);

        JsonNode admin = me(DemoSeedRunner.ADMINISTRATOR);
        assertThat(admin.get("roles").toString()).contains("ADMINISTRATOR").contains("EMPLOYEE");
        String organizationId = admin.get("organizationId").asText();
        assertThat(me(DemoSeedRunner.EMPLOYEE).get("organizationId").asText()).isEqualTo(organizationId);
        assertThat(me(DemoSeedRunner.REPRESENTATIVE).get("roles").toString()).contains("REPRESENTATIVE");
        assertThat(me(DemoSeedRunner.PLATFORM).get("platformAuthority").asText()).isEqualTo("ADMINISTRATOR");
        assertThat(me(DemoSeedRunner.DONOR).has("organizationId")).isFalse();
    }
}
