package com.traceability.app.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.app.TraceabilityApplication;

/** ADR-054 D1: con MongoDB parado, health responde 503 y DOWN (el contexto se descarta al terminar). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = TraceabilityApplication.class)
@Testcontainers
@DirtiesContext
@TestPropertySource(properties = {
    "crypto.anchor.poll.delay=9999999",
    "crypto.anchor.submit.delay=9999999",
    "crypto.anchor.stuck-monitor.delay=9999999",
    "crypto.web3j.private-key=0x1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef",
    "crypto.web3j.node-url=http://dummy-node",
    "spring.ai.openai.api-key=dummy-api-key"
})
class HealthWithMongoDownIntegrationTest {

    @Container
    static MongoDBContainer mongo = new MongoDBContainer(DockerImageName.parse("mongo:6.0")).withCommand("--replSet", "rs0");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        // sin esperar 30 s a que el driver se rinda
        registry.add("spring.data.mongodb.uri",
                () -> mongo.getReplicaSetUrl() + "?serverSelectionTimeoutMS=2000&connectTimeoutMS=2000");
    }

    @LocalManagementPort private int managementPort;
    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> health() throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + managementPort + "/actuator/health"))
                .timeout(Duration.ofSeconds(30)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void healthGoesDownWhenMongoStops() throws Exception {
        assertThat(health().statusCode()).isEqualTo(200);

        mongo.stop();

        HttpResponse<String> down = health();
        assertThat(down.statusCode()).isEqualTo(503);
        JsonNode body = new ObjectMapper().readTree(down.body());
        assertThat(body.path("status").asText()).isEqualTo("DOWN");
        assertThat(body.path("components").path("mongo").path("status").asText()).isEqualTo("DOWN");
    }
}
