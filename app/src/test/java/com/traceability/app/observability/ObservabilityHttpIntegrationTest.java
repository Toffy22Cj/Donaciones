package com.traceability.app.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.app.TraceabilityApplication;
import com.traceability.contracts.authorization.AuthorizationPrincipal;
import com.traceability.contracts.authorization.AuthorizationRole;
import com.traceability.contracts.authorization.IdentityPrincipalPort;
import com.traceability.contracts.authentication.TokenIssuerPort;

/**
 * ADR-054 (D-01) contra Tomcat real: health en el puerto de gestión y
 * no en el de la API; el mismo {@code correlationId} en la línea de acceso y en la del evento escrito de una misma
 * petición, y distinto entre peticiones; ningún secreto (contraseña, JWT, cabecera {@code Authorization}) en el log.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = TraceabilityApplication.class)
@ExtendWith(OutputCaptureExtension.class)
@Testcontainers
@TestPropertySource(properties = {
    "crypto.anchor.poll.delay=9999999",
    "crypto.anchor.submit.delay=9999999",
    "crypto.anchor.stuck-monitor.delay=9999999",
    "crypto.web3j.private-key=0x1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef",
    "crypto.web3j.node-url=http://dummy-node",
    "spring.ai.openai.api-key=dummy-api-key"
})
class ObservabilityHttpIntegrationTest {

    private static final String ORG = "org-d01";
    private static final String EMPLOYEE = "acc-d01-employee";
    private static final String IN_KIND = """
            {"assetType":"FOOD","quantity":"10","unitOfMeasure":"KGS","custodianRef":"CUST-1","currentLocation":"WH-1","municipalityCode":"05001"}""";

    @Container
    static MongoDBContainer mongo = new MongoDBContainer(DockerImageName.parse("mongo:6.0")).withCommand("--replSet", "rs0");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongo::getReplicaSetUrl);
    }

    @LocalServerPort private int port;
    @LocalManagementPort private int managementPort;
    @Autowired private TokenIssuerPort tokens;
    @Autowired private org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler taskScheduler;
    @MockitoBean private IdentityPrincipalPort identityPrincipalPort;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void principal() {
        AuthorizationPrincipal p = new AuthorizationPrincipal(EMPLOYEE, ORG, Set.of(AuthorizationRole.EMPLOYEE), null);
        when(identityPrincipalPort.resolvePrincipal(anyString())).thenReturn(p);
        when(identityPrincipalPort.resolveAuthenticatedPrincipal(anyString(), any())).thenReturn(p);
    }

    private HttpResponse<String> get(int onPort, String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + onPort + path))
                .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> registerInKind(String token) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/physical-assets/from-donation"))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + token)
                .header("Command-Id", UUID.randomUUID().toString())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(IN_KIND)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void healthIsUpWithMongoOnTheManagementPort_andAbsentFromTheApiPort() throws Exception {
        HttpResponse<String> health = get(managementPort, "/actuator/health");
        assertThat(health.statusCode()).isEqualTo(200);
        JsonNode body = json.readTree(health.body());
        assertThat(body.path("status").asText()).isEqualTo("UP");
        assertThat(body.path("components").path("mongo").path("status").asText()).isEqualTo("UP");
        assertThat(body.path("components").has("mail")).isFalse();

        assertThat(managementPort).isNotEqualTo(port);
        assertThat(get(port, "/actuator/health").statusCode()).isEqualTo(404);
        // solo health e info: el resto de actuator no existe ni en el puerto de gestión
        assertThat(get(managementPort, "/actuator/env").statusCode()).isEqualTo(404);
        assertThat(get(managementPort, "/actuator/beans").statusCode()).isEqualTo(404);
    }

    @Test
    void oneRequestSharesOneCorrelationIdBetweenTheAccessLineAndTheEventLine() throws Exception {
        // se leen los eventos de Logback con su MDC, sin depender del formato de salida (texto o JSON), que en el
        // reactor completo puede haber fijado otro contexto de la misma JVM
        ch.qos.logback.classic.Logger root = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        root.addAppender(appender);
        try {
            String token = tokens.issue(EMPLOYEE);
            assertThat(registerInKind(token).statusCode()).isEqualTo(201);
            assertThat(registerInKind(token).statusCode()).isEqualTo(201);
        } finally {
            root.detachAppender(appender);
        }

        List<ch.qos.logback.classic.spi.ILoggingEvent> events = List.copyOf(appender.list);
        List<String> accessIds = events.stream()
                .filter(e -> e.getFormattedMessage().startsWith("HTTP POST /api/v1/physical-assets/from-donation -> 201"))
                .map(e -> e.getMDCPropertyMap().get("correlationId")).toList();
        List<String> eventIds = events.stream()
                .filter(e -> e.getFormattedMessage().startsWith("Event written ")
                        && e.getFormattedMessage().contains("eventType=ASSET_REGISTERED"))
                .map(e -> e.getMDCPropertyMap().get("correlationId")).toList();

        assertThat(accessIds).hasSize(2).doesNotContainNull();
        assertThat(eventIds).hasSize(2).doesNotContainNull();
        assertThat(eventIds).containsExactlyElementsOf(accessIds);
        assertThat(accessIds.get(0)).isNotEqualTo(accessIds.get(1));
        // la línea de acceso lleva el patrón de la ruta, nunca la URL con identificadores ni la consulta
        assertThat(events.stream().map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage))
                .noneMatch(m -> m.startsWith("HTTP ") && m.contains("?"));
    }

    @Test
    void eachScheduledRunGetsItsOwnSchedulerCorrelationId_andTheThreadIsCleanAfterwards() throws Exception {
        java.util.concurrent.BlockingQueue<String> seen = new java.util.concurrent.LinkedBlockingQueue<>();
        for (int i = 0; i < 2; i++) {
            taskScheduler.schedule(() -> seen.add(String.valueOf(org.slf4j.MDC.get("correlationId"))),
                    java.time.Instant.now());
        }
        String first = seen.poll(10, java.util.concurrent.TimeUnit.SECONDS);
        String second = seen.poll(10, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(first).startsWith("sched-");
        assertThat(second).startsWith("sched-").isNotEqualTo(first);
        // después de la vuelta, el hilo del scheduler no conserva el valor
        taskScheduler.schedule(() -> seen.add(String.valueOf(org.slf4j.MDC.getCopyOfContextMap())),
                java.time.Instant.now());
        assertThat(seen.poll(10, java.util.concurrent.TimeUnit.SECONDS)).doesNotContain(first).doesNotContain(second);
    }

    @Test
    void noSecretEverReachesTheLog(CapturedOutput output) throws Exception {
        String token = tokens.issue(EMPLOYEE);
        registerInKind(token);
        String password = "contraseña-que-no-debe-salir-" + UUID.randomUUID();
        http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        json.writeValueAsString(Map.of("email", "nadie@d01.test", "password", password))))
                .build(), HttpResponse.BodyHandlers.ofString());

        String all = output.getAll();
        assertThat(all).doesNotContain(token).doesNotContain(password).doesNotContain("Bearer ")
                .doesNotContain("nadie@d01.test");
    }
}
