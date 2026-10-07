package com.traceability.app.web;

import com.traceability.app.TraceabilityApplication;
import org.junit.jupiter.api.Test;
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
import java.time.Duration;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S-03 contra Tomcat real: el preflight de un origen permitido responde con ese origen exacto, sin credenciales, con
 * {@code Authorization}, {@code Command-Id} e {@code Intent-Token}, y sin pedir JWT; un origen no permitido no recibe
 * cabeceras CORS; la respuesta real expone {@code Location}.
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
    "traceability.web.cors.allowed-origins=http://localhost:5173, https://demo.paxfide.example"
})
class CorsHttpIntegrationTest {

    static final String ALLOWED = "https://demo.paxfide.example";

    @Container
    static MongoDBContainer mongo = new MongoDBContainer(DockerImageName.parse("mongo:6.0")).withCommand("--replSet", "rs0");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongo::getReplicaSetUrl);
    }

    @LocalServerPort private int port;
    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> preflight(String origin, String path, String method, String headers) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .header("Origin", origin)
                .header("Access-Control-Request-Method", method)
                .header("Access-Control-Request-Headers", headers)
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    static Set<String> list(HttpResponse<?> r, String header) {
        return r.headers().allValues(header).stream().flatMap(v -> Arrays.stream(v.split(",")))
                .map(String::trim).map(String::toLowerCase).collect(Collectors.toCollection(TreeSet::new));
    }

    @Test
    void preflightOfAnAllowedOrigin_toAProtectedRoute_isAnsweredWithoutJwt() throws Exception {
        HttpResponse<String> r = preflight(ALLOWED, "/api/v1/campaigns/c-1/close", "POST",
                "authorization,command-id,content-type");

        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.headers().allValues("Access-Control-Allow-Origin")).containsExactly(ALLOWED);
        assertThat(r.headers().firstValue("Access-Control-Allow-Credentials")).isEmpty();
        assertThat(list(r, "Access-Control-Allow-Methods")).contains("post");
        assertThat(list(r, "Access-Control-Allow-Headers")).contains("authorization", "command-id", "content-type");
    }

    @Test
    void preflight_allowsTheIntentToken_onThePublicStatusRoute() throws Exception {
        HttpResponse<String> r = preflight("http://localhost:5173", "/api/v1/public/donation-intents/i-1", "GET",
                "intent-token");

        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.headers().allValues("Access-Control-Allow-Origin")).containsExactly("http://localhost:5173");
        assertThat(list(r, "Access-Control-Allow-Headers")).contains("intent-token");
    }

    @Test
    void aNotAllowedOrigin_getsNoCorsHeaders() throws Exception {
        HttpResponse<String> r = preflight("https://evil.example", "/api/v1/me", "GET", "authorization");

        assertThat(r.statusCode()).isEqualTo(403);
        assertThat(r.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
    }

    @Test
    void anUnlistedHeader_isNotAllowed() throws Exception {
        HttpResponse<String> r = preflight(ALLOWED, "/api/v1/me", "GET", "x-custom-header");

        assertThat(r.statusCode()).isEqualTo(403);
    }

    @Test
    void theActualResponse_carriesTheOrigin_andExposesLocation() throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/api/v1/public/campaigns"))
                .header("Origin", ALLOWED).GET().build(), HttpResponse.BodyHandlers.ofString());

        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.headers().allValues("Access-Control-Allow-Origin")).containsExactly(ALLOWED);
        assertThat(list(r, "Access-Control-Expose-Headers")).contains("location");
        assertThat(r.headers().firstValue("Access-Control-Allow-Credentials")).isEmpty();
    }
}
