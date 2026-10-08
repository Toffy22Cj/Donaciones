package com.traceability.app.web.tracking;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.app.TraceabilityApplication;
import com.traceability.app.application.payments.SimulatedWebhookSignature;
import com.traceability.contracts.authentication.TokenIssuerPort;
import identity.application.service.AddEmployeeService;
import identity.application.service.AssignAdministratorService;
import identity.application.service.BootstrapPlatformAuthorityService;
import identity.application.service.CreateAccountService;
import identity.application.service.CreateOrganizationService;
import identity.domain.model.Account;
import identity.domain.model.AccountId;
import identity.domain.model.AuditActor;
import identity.domain.model.Email;
import identity.domain.model.Organization;
import identity.domain.model.OrganizationType;
import com.traceability.app.scheduler.BlockchainAnchorProducer;
import com.traceability.convocatoria.application.port.out.DonationIntentRepositoryPort;
import com.traceability.crypto.application.port.out.MerkleBatchRepositoryPort;
import com.traceability.crypto.domain.AnchorStatus;
import com.traceability.crypto.domain.MerkleBatch;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Encargo 6, P4, de punta a punta con Mongo real: dos donaciones confirmadas por el webhook simulado, un lote real del
 * productor ({@code BlockchainAnchorProducer.produceBatch}) que contiene eventos de las dos, marcado {@code ANCHORED}
 * como lo haría el anclaje. El donante ve, con su {@code trackingCode}, el estado del anclaje y el resultado de
 * {@code verifyBatch}; nunca nada de la otra donación.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = TraceabilityApplication.class)
@Testcontainers
@TestPropertySource(properties = {
    "crypto.anchor.poll.delay=9999999",
    "crypto.anchor.submit.delay=9999999",
    "crypto.anchor.stuck-monitor.delay=9999999",
    "traceability.anchor.producer.interval-ms=9999999",
    "crypto.web3j.private-key=0x1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef",
    "crypto.web3j.node-url=http://dummy-node",
    "spring.ai.openai.api-key=dummy-api-key",
    "traceability.demo.simulated-payments=true",
    // sin caché: el test altera un evento y vuelve a preguntar enseguida
    "traceability.integrity.verification-cache-ttl=PT0S",
    // Solo para tests
    "traceability.demo.webhook-secret=test-only-simulated-webhook-secret-0123456789"
})
class TrackingIntegrityHttpIntegrationTest {

    @Container
    static MongoDBContainer mongo = new MongoDBContainer(DockerImageName.parse("mongo:6.0")).withCommand("--replSet", "rs0");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongo::getReplicaSetUrl);
    }

    @LocalServerPort private int port;
    @Autowired private TokenIssuerPort tokens;
    @Autowired private CreateAccountService accounts;
    @Autowired private CreateOrganizationService organizations;
    @Autowired private AddEmployeeService employees;
    @Autowired private AssignAdministratorService administrators;
    @Autowired private BootstrapPlatformAuthorityService bootstrap;
    @Autowired private SimulatedWebhookSignature signature;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    private HttpResponse<String> send(String method, String path, String account, String body) throws Exception {
        HttpRequest.Builder r = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(15))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (account != null) r.header("Authorization", "Bearer " + tokens.issue(account));
        if (method.equals("POST")) r.header("Command-Id", UUID.randomUUID().toString());
        if (body != null) r.header("Content-Type", "application/json");
        return http.send(r.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode ok(HttpResponse<String> r, int status) throws Exception {
        assertThat(r.statusCode()).as(r.body()).isEqualTo(status);
        return r.body().isEmpty() ? null : json.readTree(r.body());
    }

    @Autowired private BlockchainAnchorProducer producer;
    @Autowired private MerkleBatchRepositoryPort merkleBatches;
    @Autowired private DonationIntentRepositoryPort intents;
    @Autowired private MongoTemplate mongoTemplate;

    record Donation(String intentId, String fundId, String trackingCode) {}

    /** Intención con el proveedor simulado, confirmada por su webhook; devuelve su {@code trackingCode}. */
    private Donation donate(String publicCode, String amountMinor) throws Exception {
        JsonNode intent = ok(send("POST", "/api/v1/public/campaigns/" + publicCode + "/donation-intents", null,
                "{\"amount\":\"" + amountMinor + "\",\"currency\":\"COP\",\"paymentMethod\":\"GATEWAY\"}"), 201);
        String url = intent.get("paymentRedirectUrl").asText();
        String event = "{\"type\":\"payment.confirmed\",\"paymentSessionId\":\"" + url.substring(url.lastIndexOf('/') + 1)
                + "\",\"providerEventId\":\"evt-" + UUID.randomUUID() + "\",\"amount\":\"" + amountMinor
                + "\",\"currency\":\"COP\"}";
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/webhooks/payments"))
                .header("Content-Type", "application/json").header("X-Simulated-Signature", signature.sign(event))
                .POST(HttpRequest.BodyPublishers.ofString(event)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        String intentId = intent.get("intentId").asText();
        JsonNode status = ok(http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                + "/api/v1/public/donation-intents/" + intentId)).header("Intent-Token", intent.get("statusToken").asText())
                .GET().build(), HttpResponse.BodyHandlers.ofString()), 200);
        return new Donation(intentId, intents.findById(intentId).orElseThrow().getFundId(), status.get("trackingCode").asText());
    }

    private HttpResponse<String> tracking(String path, String trackingCode) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/donations/tracking" + path))
                .header("Authorization", "Bearer " + trackingCode).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Lo que haría el anclaje al confirmarse la transacción: el lote pasa a ANCHORED con su hash y bloque. */
    private void anchorAllPending() {
        for (MerkleBatch b : merkleBatches.findByStatus(AnchorStatus.PENDING)) {
            merkleBatches.save(new MerkleBatch(b.batchId(), b.coverage(), b.merkleRoot(), b.leafHashes(), b.createdAt(),
                    AnchorStatus.ANCHORED, "ganache-local", "0x00000000000000000000000000000000000000aa", 0L,
                    "0x" + "ab".repeat(32), Instant.now(), Instant.now(), 7L, null));
        }
    }

    static double round4(double v) {
        return Math.round(v * 10_000.0) / 10_000.0;
    }

    @Test
    void theDonorSeesTheAnchorAndTheVerification_andNothingOfTheOtherDonation() throws Exception {
        String platformEmail = UUID.randomUUID() + "@platform.test";
        accounts.createAccount(new Email(platformEmail), "Pass123!Pass123!");
        String platform = bootstrap.bootstrap(platformEmail).value();
        Account rep = accounts.createAccount(new Email(UUID.randomUUID() + "@e2e.test"), "Pass123!Pass123!");
        AuditActor setup = new AuditActor.SystemAuditActor("e2e-integrity");
        Organization org = organizations.createOrganization(setup, OrganizationType.FOUNDATION, rep.getAccountId(), "Org E2E");
        String admin = accounts.createAccount(new Email(UUID.randomUUID() + "@e2e.test"), "Pass123!Pass123!").getAccountId().value();
        employees.addEmployee(setup, org.getOrganizationId(), new AccountId(admin));
        administrators.assignAdministrator(setup, org.getOrganizationId(), new AccountId(admin));
        String orgId = org.getOrganizationId().value();
        ok(send("POST", "/api/v1/platform/organizations/" + orgId + "/verify", platform, null), 200);
        Instant start = Instant.now();
        JsonNode campaign = ok(send("POST", "/api/v1/organizations/" + orgId + "/campaigns", admin, """
                {"title":"E2E integridad","visibility":"PUBLIC","startDate":"%s","endDate":"%s",
                 "configuration":{"acceptedDonationTypes":["MONETARY"],"acceptedPaymentMethods":["GATEWAY"],
                 "currency":"COP","targetAmount":"800000000","targetPolicy":"FLEXIBLE"}}"""
                .formatted(start, start.plus(Duration.ofDays(40)))), 201);
        String code = campaign.get("publicCode").asText();
        Donation mine = donate(code, "120000000");
        Donation other = donate(code, "90000000");

        // antes de producir un lote: nada anclado, sus eventos pendientes
        JsonNode before = ok(tracking("/integrity", mine.trackingCode()), 200);
        assertThat(before.get("batches")).isEmpty();
        assertThat(before.get("unanchoredEvents").asInt()).isPositive();

        producer.produceBatch();
        anchorAllPending();
        String batchId = mongoTemplate.findOne(Query.query(Criteria.where("streamId").is(mine.fundId())),
                org.bson.Document.class, "event_store").getString("merkleBatchId");
        assertThat(batchId).isNotNull();
        assertThat(mongoTemplate.count(Query.query(Criteria.where("streamId").is(other.fundId())
                .and("merkleBatchId").is(batchId)), "event_store")).as("el lote tiene eventos de las dos").isPositive();

        HttpResponse<String> r = tracking("/integrity", mine.trackingCode());
        JsonNode match = ok(r, 200);
        assertThat(r.headers().firstValue("Cache-Control")).hasValue("no-store");
        assertThat(match.get("unanchoredEvents").asInt()).isZero();
        assertThat(match.get("batches")).hasSize(1);
        JsonNode b = match.get("batches").get(0);
        assertThat(b.get("anchorStatus").asText()).isEqualTo("ANCHORED");
        assertThat(b.get("network").asText()).isEqualTo("ganache-local");
        assertThat(b.get("transactionHash").asText()).isEqualTo("0x" + "ab".repeat(32));
        assertThat(b.get("merkleRoot").asText()).isNotBlank();
        assertThat(b.get("confirmedBlockNumber").asLong()).isEqualTo(7L);
        assertThat(b.get("eventsOfThisDonation").asInt()).isPositive();
        assertThat(b.get("verification").get("result").asText()).isEqualTo("MATCH");
        assertThat(r.body()).doesNotContain(other.fundId()).doesNotContain(mine.fundId()).doesNotContain(batchId)
                .doesNotContain("donorRef").doesNotContain("affectedSequences").doesNotContain("coverage");

        // alguien altera un evento de la OTRA donación en la base: el lote deja de cuadrar, pero no es de esta
        mongoTemplate.updateFirst(Query.query(Criteria.where("streamId").is(other.fundId())),
                Update.update("eventHash", "f".repeat(64)), "event_store");
        JsonNode mismatch = ok(tracking("/integrity", mine.trackingCode()), 200);
        JsonNode v = mismatch.get("batches").get(0).get("verification");
        assertThat(v.get("result").asText()).isEqualTo("MISMATCH");
        assertThat(v.get("reason").asText()).isEqualTo("ROOT_MISMATCH");
        assertThat(v.get("affectsThisDonation").asBoolean()).isFalse();
        assertThat(mismatch.toString()).doesNotContain(other.fundId()).doesNotContain(batchId);

        // un código que no vale: la misma respuesta que el resto del seguimiento (el filtro del trackingCode da 401;
        // solo cambia "instance", que es la ruta pedida)
        JsonNode bad = json.readTree(tracking("/integrity", "no-es-un-codigo").body());
        HttpResponse<String> badTrackingResponse = tracking("", "no-es-un-codigo");
        JsonNode badTracking = json.readTree(badTrackingResponse.body());
        assertThat(tracking("/integrity", "no-es-un-codigo").statusCode()).isEqualTo(badTrackingResponse.statusCode())
                .isEqualTo(401);
        assertThat(bad.get("detail")).isEqualTo(badTracking.get("detail"));
        assertThat(bad.get("title")).isEqualTo(badTracking.get("title"));
    }

}
