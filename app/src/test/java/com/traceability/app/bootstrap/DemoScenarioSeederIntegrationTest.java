package com.traceability.app.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.app.TraceabilityApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.mongodb.core.MongoTemplate;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Encargo 6, P5: con el perfil {@code demo-seed}, al arrancar sobre una base vacía queda TODO el escenario de la
 * presentación, creado por los servicios de aplicación y la API (nunca con inserciones directas). Sobre una base que
 * no está vacía, falla con un mensaje claro. El "día de la demo" se fija a 13 días vista para que el test no dependa de
 * la fecha en que corre; la convocatoria activa debe estar entonces entre el 15 % y el 50 % de su duración.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = TraceabilityApplication.class)
@Testcontainers
@ActiveProfiles("demo-seed")
@Import(DemoScenarioSeederIntegrationTest.ControlledClock.class)
@TestPropertySource(properties = {
    "crypto.anchor.poll.delay=9999999",
    "crypto.anchor.submit.delay=9999999",
    "crypto.anchor.stuck-monitor.delay=9999999",
    "traceability.anchor.producer.interval-ms=9999999",
    "crypto.web3j.private-key=0x1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef",
    "crypto.web3j.node-url=http://dummy-node",
    "spring.ai.openai.api-key=dummy-api-key",
    "traceability.demo.simulated-payments=true",
    // Solo para tests
    "traceability.demo.webhook-secret=test-only-simulated-webhook-secret-0123456789",
    "traceability.demo.seed.password=demo-local-password-test",
})
class DemoScenarioSeederIntegrationTest {

    static final Instant C0 = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    static final Instant DEMO_DAY = C0.plus(Duration.ofDays(13));
    static final Path OUTPUT;

    static {
        try {
            OUTPUT = Files.createTempDirectory("demo-seed").resolve("credenciales-locales.md");
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static class MovableClock extends Clock {
        volatile Instant now = C0;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @TestConfiguration
    static class ControlledClock {
        static final MovableClock CLOCK = new MovableClock();

        @Bean
        @Primary
        Clock controlledClock() {
            return CLOCK;
        }
    }

    @Container
    static MongoDBContainer mongo = new MongoDBContainer(DockerImageName.parse("mongo:6.0")).withCommand("--replSet", "rs0");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongo::getReplicaSetUrl);
        registry.add("traceability.demo.seed.demo-day", DEMO_DAY::toString);
        registry.add("traceability.demo.seed.output-file", OUTPUT::toString);
    }

    @LocalServerPort private int port;
    @Autowired private DemoScenarioSeeder seeder;
    @Autowired private MongoTemplate mongoTemplate;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    private HttpResponse<String> get(String path, String bearer) throws Exception {
        HttpRequest.Builder r = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET();
        if (bearer != null) r.header("Authorization", "Bearer " + bearer);
        return http.send(r.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode ok(HttpResponse<String> r) throws Exception {
        assertThat(r.statusCode()).as(r.body()).isBetween(200, 202);
        return json.readTree(r.body());
    }

    private String login(String email) throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"email\":\"" + email + "\",\"password\":\"demo-local-password-test\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as(email + " " + r.body()).isEqualTo(200);
        return json.readTree(r.body()).get("token").asText();
    }

    private static List<String> texts(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }

    @Test
    void anEmptyDatabase_endsWithTheWholeDemoScenario_createdThroughServicesAndTheApi() throws Exception {
        String content = Files.readString(OUTPUT);

        // cuentas que pueden iniciar sesión, con sus papeles
        String platform = login("plataforma@demo.paxfide.local");
        String representative = login("representante@demo.paxfide.local");
        String admin = login("administrador@demo.paxfide.local");
        String employee1 = login("empleado1@demo.paxfide.local");
        String employee2 = login("empleado2@demo.paxfide.local");
        String donor = login("donante@demo.paxfide.local");
        login("representante@empresa-aliada.demo.paxfide.local");
        assertThat(ok(get("/api/v1/me", platform)).get("platformAuthority").asText()).isEqualTo("ADMINISTRATOR");
        assertThat(texts(ok(get("/api/v1/me", representative)).get("roles"))).contains("REPRESENTATIVE");
        assertThat(texts(ok(get("/api/v1/me", admin)).get("roles"))).containsExactlyInAnyOrder("ADMINISTRATOR", "EMPLOYEE");
        assertThat(texts(ok(get("/api/v1/me", employee1)).get("roles"))).containsExactly("EMPLOYEE");
        assertThat(texts(ok(get("/api/v1/me", employee2)).get("roles"))).containsExactly("EMPLOYEE");
        assertThat(ok(get("/api/v1/me", donor)).has("organizationId")).isFalse();
        String organizationId = ok(get("/api/v1/me", admin)).get("organizationId").asText();

        // una organización verificada y otra pendiente en la cola de la plataforma
        JsonNode queue = ok(get("/api/v1/platform/organizations", platform));
        assertThat(queue.get("items")).hasSize(1);
        assertThat(queue.get("items").get(0).get("name").asText()).isEqualTo("Empresa Aliada Demo");

        // convocatorias: activa (en el descubrimiento), cerrada y privada por enlace (fuera del descubrimiento)
        Map<String, String> codes = Map.of(
                "activa", between(content, "activa"), "cerrada", between(content, "cerrada"), "privada", between(content, "privada"));
        List<String> discovered = new ArrayList<>();
        ok(get("/api/v1/public/campaigns", null)).get("items").forEach(i -> discovered.add(i.get("publicCode").asText()));
        assertThat(discovered).contains(codes.get("activa")).doesNotContain(codes.get("cerrada"), codes.get("privada"));
        assertThat(ok(get("/api/v1/public/campaigns/" + codes.get("cerrada"), null)).get("status").asText()).isEqualTo("CLOSED");
        assertThat(ok(get("/api/v1/public/campaigns/" + codes.get("privada"), null)).get("status").asText()).isEqualTo("OPEN");

        // D-06: el empleado 2 fue responsable de la cerrada (histórica) y ahora lo es de la privada
        List<String> mine = new ArrayList<>();
        ok(get("/api/v1/me/campaigns", employee2)).get("items").forEach(i -> mine.add(i.get("publicCode").asText()));
        assertThat(mine).contains(codes.get("privada"));

        // seguimiento con contenido: logística por los caminos A (con división) y B
        String tracking = trackingCode(content);
        JsonNode t = ok(get("/api/v1/donations/tracking", tracking));
        assertThat(t.get("logistics").size()).isGreaterThanOrEqualTo(2);
        assertThat(ok(get("/api/v1/donations/tracking/integrity", tracking)).has("batches")).isTrue();

        // narrativa de la convocatoria: sus hechos ya tienen entregas
        JsonNode narrative = ok(get("/api/v1/public/campaigns/" + codes.get("activa") + "/narrative", null));
        assertThat(narrative.get("facts").get("unitsDelivered").asLong()).isPositive();

        // el día de la demo, la convocatoria activa está entre el 15 % y el 50 %: la predicción da cifra
        String campaignRef = mongoTemplate.findOne(org.springframework.data.mongodb.core.query.Query.query(
                org.springframework.data.mongodb.core.query.Criteria.where("publicCode").is(codes.get("activa"))),
                org.bson.Document.class, "convocatorias").getString("_id");
        Instant before = ControlledClock.CLOCK.now;
        try {
            ControlledClock.CLOCK.now = DEMO_DAY;
            JsonNode p = ok(get("/api/v1/organizations/" + organizationId + "/campaigns/" + campaignRef + "/prediction", admin));
            assertThat(p.get("available").asBoolean()).as(p.toString()).isTrue();
            assertThat(p.get("pctTimeElapsed").asDouble()).isBetween(0.15, 0.50);
        } finally {
            ControlledClock.CLOCK.now = before;
        }

        // el fichero de credenciales: solo locales, con publicCode y trackingCode
        assertThat(content).contains("solo locales").contains("demo-local-password-test")
                .contains(codes.get("activa")).contains(tracking).contains("plataforma@demo.paxfide.local");
    }

    @Test
    void onADatabaseThatIsNotEmpty_itFailsWithAClearMessage_andCreatesNothing() {
        long accounts = mongoTemplate.getCollection("accounts").countDocuments();
        assertThatThrownBy(() -> seeder.seed(port))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no está vacía")
                .hasMessageContaining("accounts");
        assertThat(mongoTemplate.getCollection("accounts").countDocuments()).isEqualTo(accounts);
    }

    /** El fichero lista cada convocatoria como "| <clave> | `<publicCode>` |". */
    private static String between(String content, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\| " + key + " \\|[^|]*\\| `([^`]+)` \\|").matcher(content);
        assertThat(m.find()).as("publicCode de " + key + " en el fichero").isTrue();
        return m.group(1);
    }

    private static String trackingCode(String content) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\| donación anónima \\|[^|]*\\| `([^`]+)` \\|").matcher(content);
        assertThat(m.find()).as("trackingCode de la donación anónima en el fichero").isTrue();
        return m.group(1);
    }
}
