package com.traceability.app.bootstrap.historic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.app.TraceabilityApplication;
import com.traceability.app.bootstrap.historic.DemoHistoricSeeder.Expected;
import com.traceability.app.bootstrap.historic.DemoHistoricSeeder.SeededAsset;
import com.traceability.app.bootstrap.historic.DemoHistoricSeeder.SeededCampaign;
import com.traceability.contracts.SequenceRange;
import com.traceability.core.application.integrity.EventIntegrityChecker;
import com.traceability.core.domain.shared.EventTime;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
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
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Plan del paso 2 (2026-11-16): con los perfiles {@code dev,demo-seed,demo-historico}, sobre una base vacía corre la
 * semilla de la demo sin cambios y, después, la histórica añade el escenario con fechas pasadas por los servicios de
 * aplicación. Comprueba la respuesta de la predicción para cada convocatoria, los meses de fondos, los lotes y, sobre
 * todo, los invariantes: {@code occurredAt <= recordedAt}, {@code recordedAt} real y la cadena de hashes intacta.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = TraceabilityApplication.class)
@Testcontainers
@ActiveProfiles({"dev", "demo-seed", "demo-historico"})
@Import(com.traceability.app.infrastructure.media.InMemoryMediaStorageConfig.class)
@TestPropertySource(properties = {
    "crypto.anchor.poll.delay=9999999",
    "crypto.anchor.submit.delay=9999999",
    "crypto.anchor.stuck-monitor.delay=9999999",
    "traceability.anchor.producer.interval-ms=9999999",
    "crypto.web3j.private-key=0x1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef",
    "crypto.web3j.node-url=http://dummy-node",
    "spring.ai.openai.api-key=dummy-api-key",
    // Solo para tests
    "traceability.demo.webhook-secret=test-only-simulated-webhook-secret-0123456789",
    "traceability.demo.seed.password=demo-local-password-test",
    "traceability.discovery.cursor-key=dGVzdC1vbmx5LWN1cnNvci1rZXktMzItYnl0ZXMtISE=",
})
class DemoHistoricSeederIntegrationTest {

    static final Instant TEST_START = Instant.now();
    static final Path OUTPUT = tempFile();

    static Path tempFile() {
        try {
            return Files.createTempDirectory("demo-historico").resolve("credenciales-locales.md");
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @Container
    static MongoDBContainer mongo = new MongoDBContainer(DockerImageName.parse("mongo:6.0")).withCommand("--replSet", "rs0");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongo::getReplicaSetUrl);
        registry.add("traceability.demo.seed.output-file", OUTPUT::toString);
    }

    @LocalServerPort private int port;
    @Autowired private DemoHistoricSeedRunner runner;
    @Autowired private DemoHistoricSeeder seeder;
    @Autowired private MongoTemplate mongoTemplate;
    @Autowired private EventIntegrityChecker integrity;
    @Autowired private Clock clock;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();
    private String administratorToken;

    private JsonNode get(String path) throws Exception {
        if (administratorToken == null) {
            HttpResponse<String> login = http.send(HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + port + "/api/v1/auth/login"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"email\":\"" + DemoHistoricSeeder.ADMINISTRATOR
                            + "\",\"password\":\"demo-local-password-test\"}"))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertThat(login.statusCode()).as(login.body()).isEqualTo(200);
            administratorToken = json.readTree(login.body()).get("token").asText();
        }
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Authorization", "Bearer " + administratorToken).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as(path + " " + r.body()).isEqualTo(200);
        return json.readTree(r.body());
    }

    private DemoHistoricSeeder.Result seeded() {
        assertThat(runner.result()).as("la semilla histórica corrió al arrancar").isNotNull();
        return runner.result();
    }

    private SeededCampaign campaign(String key) {
        return seeded().campaigns().stream().filter(c -> c.key().equals(key)).findFirst().orElseThrow();
    }

    private String org() {
        return seeded().organizationId();
    }

    private static List<String> reasons(JsonNode history) {
        List<String> out = new ArrayList<>();
        history.get("cuts").forEach(c -> out.add(c.path("unavailableReason").asText("AVAILABLE")));
        return out;
    }

    @Test
    void everyCampaign_answersThePredictionAsTheScenarioSays() throws Exception {
        assertThat(seeded().campaigns()).hasSize(DemoHistoricSeeder.SCENARIO.size()).hasSize(10);
        for (SeededCampaign c : seeded().campaigns()) {
            JsonNode p = get("/api/v1/organizations/" + org() + "/campaigns/" + c.campaignRef() + "/prediction");
            assertThat(p.get("kind").asText()).isEqualTo("ESTIMATE");
            assertThat(p.get("warning").asText()).isEqualTo("modelo entrenado con datos sintéticos");
            if (c.expected() == Expected.FIGURE) {
                assertThat(p.get("available").asBoolean()).as(c.title() + " " + p).isTrue();
                assertThat(p.get("probabilityReachTarget").asDouble()).as(c.title()).isBetween(0.0, 1.0);
                assertThat(p.get("pctTimeElapsed").asDouble()).as(c.title()).isBetween(0.15, 0.50);
                assertThat(p.has("unavailableReason")).isFalse();
            } else {
                assertThat(p.get("available").asBoolean()).as(c.title() + " " + p).isFalse();
                assertThat(p.get("unavailableReason").asText()).as(c.title()).isEqualTo(c.expected().name());
                assertThat(p.has("probabilityReachTarget")).as(c.title() + ": sin cifra").isFalse();
            }
        }
        // cuatro casos con cifra; los otros seis, cada uno con su motivo
        assertThat(seeded().campaigns().stream().filter(c -> c.expected() == Expected.FIGURE)).hasSize(4);
    }

    @Test
    void theHistory_hasPastCutsWithWhatWasRaisedThen_andTheEarlyClosedOneSaysCampaignEnded() throws Exception {
        String base = "/api/v1/organizations/" + org() + "/campaigns/";

        JsonNode forty = get(base + campaign("forty").campaignRef() + "/prediction/history");
        assertThat(reasons(forty)).containsExactly("AVAILABLE", "AVAILABLE", "FUTURE_CUT");
        double at15 = forty.get("cuts").get(0).get("pctRaisedAtCut").asDouble();
        double at25 = forty.get("cuts").get(1).get("pctRaisedAtCut").asDouble();
        assertThat(at15).as("lo recaudado en el corte sale de FUNDS_CLEARED fechados en el pasado").isGreaterThan(0.0);
        assertThat(at25).isGreaterThan(at15);

        JsonNode after = get(base + campaign("after").campaignRef() + "/prediction/history");
        assertThat(reasons(after)).containsExactly("AVAILABLE", "AVAILABLE", "AVAILABLE");

        // Cerrada al 5 %: el corte del 15 % ya pasó y los otros dos no; ninguno llegará nunca (fix del 2026-11-15)
        JsonNode closed = get(base + campaign("closedEarly").campaignRef() + "/prediction/history");
        assertThat(reasons(closed)).containsExactly("CAMPAIGN_ENDED", "CAMPAIGN_ENDED", "CAMPAIGN_ENDED")
                .doesNotContain("FUTURE_CUT");

        JsonNode strict = get(base + campaign("strict").campaignRef() + "/prediction/history");
        assertThat(strict.get("available").asBoolean()).isFalse();
        assertThat(strict.get("unavailableReason").asText()).isEqualTo("STRICT_POLICY_EXCLUDED");
    }

    @Test
    void theMonthlyFunds_spanSeveralMonths_inTwoCurrencies() throws Exception {
        JsonNode monthly = get("/api/v1/organizations/" + org() + "/funds/monthly?months=12");
        Set<String> copMonths = new TreeSet<>();
        Set<String> currencies = new TreeSet<>();
        for (JsonNode item : monthly.get("items")) {
            currencies.add(item.get("currency").asText());
            if ("COP".equals(item.get("currency").asText()) && Long.parseLong(item.get("clearedAmount").asText()) > 0) {
                copMonths.add(item.get("month").asText());
            }
        }
        assertThat(copMonths).as("meses con fondos en COP: " + monthly).hasSizeGreaterThanOrEqualTo(4);
        assertThat(currencies).contains("COP", "USD");
    }

    @Test
    void occurredAtIsInThePast_recordedAtIsReal_andNeverTheOtherWayRound() {
        List<Document> events = mongoTemplate.findAll(Document.class, "event_store");
        assertThat(events).isNotEmpty();
        int backdated = 0;
        for (Document e : events) {
            Instant occurredAt = instant(e.get("occurredAt"));
            Instant recordedAt = instant(e.get("recordedAt"));
            assertThat(occurredAt).as("occurredAt <= recordedAt en " + e.get("streamId") + "#" + e.get("sequence"))
                    .isBeforeOrEqualTo(recordedAt);
            assertThat(recordedAt).as("recordedAt es de esta ejecución").isAfterOrEqualTo(TEST_START.minusSeconds(5));
            if (occurredAt.isBefore(TEST_START.minus(Duration.ofDays(1)))) {
                backdated++;
            }
        }
        assertThat(backdated).as("eventos fechados en el pasado").isGreaterThan(50);
    }

    @Test
    void theHashChain_ofEverySeededStream_verifies() {
        Map<String, SequenceRange> coverage = new HashMap<>();
        for (Document e : mongoTemplate.findAll(Document.class, "event_store")) {
            String stream = e.getString("streamId");
            long sequence = ((Number) e.get("sequence")).longValue();
            coverage.merge(stream, new SequenceRange(1, sequence),
                    (a, b) -> new SequenceRange(1, Math.max(a.toSequence(), b.toSequence())));
        }
        assertThat(coverage).hasSizeGreaterThan(50);
        assertThat(integrity.check(coverage)).as("ningún evento con hash o cadena rotos").isEmpty();
    }

    @Test
    void theLots_areInEveryState_inTwoCampaigns_andOnlyTheSplitChildHasTheRealDate() throws Exception {
        List<SeededAsset> assets = seeded().assets();
        assertThat(assets.stream().map(SeededAsset::campaignKey).distinct()).containsExactlyInAnyOrder("forty", "after");
        Map<String, String> status = new HashMap<>();
        for (JsonNode a : get("/api/v1/organizations/" + org() + "/physical-assets").get("items")) {
            status.put(a.get("assetRef").asText(), a.get("lifecycleStatus").asText());
        }
        Set<String> seen = new TreeSet<>();
        for (SeededAsset a : assets) {
            assertThat(status).as("el lote " + a.assetRef() + " está en el listado").containsKey(a.assetRef());
            assertThat(a.state()).startsWith(status.get(a.assetRef()));
            seen.add(status.get(a.assetRef()));
            Document genesis = mongoTemplate.findOne(new org.springframework.data.mongodb.core.query.Query(
                    org.springframework.data.mongodb.core.query.Criteria.where("streamId").is(a.assetRef())
                            .and("sequence").is(1L)), Document.class, "event_store");
            assertThat(genesis).as("génesis de " + a.assetRef()).isNotNull();
            boolean real = !instant(genesis.get("occurredAt")).isBefore(TEST_START.minusSeconds(5));
            assertThat(real).as(a.state() + ": fecha real solo en el hijo de la división").isEqualTo(a.occurredAtIsReal());
        }
        assertThat(seen).containsExactlyInAnyOrder("REGISTERED", "DISPATCHED", "RECEIVED", "DELIVERED");
        assertThat(assets.stream().filter(SeededAsset::occurredAtIsReal)).hasSize(1);
    }

    // ADR-051 Enmienda 2, Tarea 2, §2.5: los lotes recibidos y entregados llevan una foto de ejemplo en cada paso (para las
    // capturas de la web); los demás, ninguna. Las fotos son imágenes sintéticas de la semilla, no de personas.
    @Test
    void theReceivedAndDeliveredLots_haveAStepPhotoInEveryStep_theOthersNone() throws Exception {
        int withPhotos = 0;
        for (SeededAsset a : seeded().assets()) {
            boolean expected = a.state().startsWith("RECEIVED") || a.state().startsWith("DELIVERED");
            JsonNode steps = get("/api/v1/physical-assets/" + a.assetRef() + "/evidence/steps");
            for (String step : List.of("registered", "dispatched", "received")) {
                for (JsonNode entry : steps) {
                    if (entry.get("step").asText().equals(step)) {
                        assertThat(entry.get("hasPhoto").asBoolean()).as(a.state() + " " + step).isEqualTo(expected);
                    }
                }
                if (expected) {
                    HttpResponse<byte[]> image = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                                    + "/api/v1/physical-assets/" + a.assetRef() + "/evidence/" + step))
                            .header("Authorization", "Bearer " + administratorToken).GET().build(),
                            HttpResponse.BodyHandlers.ofByteArray());
                    assertThat(image.statusCode()).as(a.state() + " " + step).isEqualTo(200);
                    assertThat(image.headers().firstValue("Content-Type").orElse("")).startsWith("image/");
                }
            }
            if (expected) withPhotos++;
        }
        assertThat(withPhotos).as("hay lotes con foto en los pasos").isGreaterThanOrEqualTo(2);
    }

    @Test
    void theIntents_areNotAllConfirmed_pendingFailedAndRejectedExist() {
        Map<String, Integer> byStatus = new HashMap<>();
        Set<String> refs = new TreeSet<>();
        seeded().campaigns().forEach(c -> refs.add(c.campaignRef()));
        for (Document i : mongoTemplate.findAll(Document.class, "donation_intents")) {
            if (refs.contains(i.getString("campaignRef"))) {
                byStatus.merge(i.getString("status"), 1, Integer::sum);
            }
        }
        int confirmed = seeded().campaigns().stream().mapToInt(SeededCampaign::confirmed).sum();
        assertThat(byStatus.get("CONFIRMED")).as(byStatus.toString()).isEqualTo(confirmed);
        assertThat(byStatus.get("FAILED")).isEqualTo(13);
        assertThat(byStatus.get("PENDING")).isEqualTo(3);
        assertThat(byStatus.get("FUNDING_REJECTED")).isEqualTo(1);
    }

    @Test
    void theDemoSeedScenario_isStillThere_untouched() throws Exception {
        List<String> titles = new ArrayList<>();
        get("/api/v1/organizations/" + org() + "/campaigns").get("items").forEach(c -> titles.add(c.get("title").asText()));
        assertThat(titles).contains("Abrigo para el invierno", "Útiles escolares 2026", "Kits de higiene (enlace privado)");
        assertThat(titles.stream().filter(t -> t.startsWith(DemoHistoricSeeder.TITLE_PREFIX))).hasSize(10);
        assertThat(titles).hasSize(13);
    }

    @Test
    void afterSeeding_theClockAndTheEventTime_areTheRealTime_inThisAndAnyOtherThread() throws Exception {
        assertThat(EventTime.isArmed()).as("armado mientras vive el contexto del perfil").isTrue();
        assertThat(Duration.between(clock.instant(), Instant.now()).abs()).isLessThan(Duration.ofSeconds(5));
        assertThat(Duration.between(EventTime.now(), Instant.now()).abs()).isLessThan(Duration.ofSeconds(5));
        // Un JWT emitido ahora por otro hilo (el del servidor) vale: su reloj no quedó en el pasado
        assertThat(get("/api/v1/me").get("organizationId").asText()).isEqualTo(org());
    }

    @Test
    void runningItAgain_failsWithAClearMessage_andCreatesNothing() {
        long before = mongoTemplate.getCollection("convocatorias").countDocuments();
        long events = mongoTemplate.getCollection("event_store").countDocuments();

        assertThatThrownBy(seeder::seed).isInstanceOf(DemoHistoricPreconditionException.class)
                .hasMessageContaining("ya corrió");

        assertThat(mongoTemplate.getCollection("convocatorias").countDocuments()).isEqualTo(before);
        assertThat(mongoTemplate.getCollection("event_store").countDocuments()).isEqualTo(events);
    }

    private static Instant instant(Object stored) {
        if (stored instanceof Date d) {
            return d.toInstant();
        }
        return Instant.parse(String.valueOf(stored));
    }
}
