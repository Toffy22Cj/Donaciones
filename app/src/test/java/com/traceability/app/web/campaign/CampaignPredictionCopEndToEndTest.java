package com.traceability.app.web.campaign;

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
import org.junit.jupiter.api.Test;
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
 * Predictor de punta a punta (Carlos, 2026-10-07): una meta creada por CV-01 en COP (unidades mínimas) da, por HTTP, la
 * misma predicción que scikit-learn con esa meta en pesos ({@code scripts/predictor/e2e_cop_expected.py} →
 * {@code predictor/e2e-cop-expected.json}). Las donaciones se confirman por el webhook simulado en instantes fijados
 * con un reloj controlable (el JWT usa el reloj del sistema, no este bean).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = TraceabilityApplication.class)
@Testcontainers
@Import(CampaignPredictionCopEndToEndTest.ControlledClock.class)
@TestPropertySource(properties = {
    "crypto.anchor.poll.delay=9999999",
    "crypto.anchor.submit.delay=9999999",
    "crypto.anchor.stuck-monitor.delay=9999999",
    "crypto.web3j.private-key=0x1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef",
    "crypto.web3j.node-url=http://dummy-node",
    "spring.ai.openai.api-key=dummy-api-key",
    "traceability.demo.simulated-payments=true",
    // Solo para tests
    "traceability.demo.webhook-secret=test-only-simulated-webhook-secret-0123456789"
})
class CampaignPredictionCopEndToEndTest {

    static final Instant C0 = Instant.now().truncatedTo(ChronoUnit.SECONDS);

    /** Reloj que el test mueve; lo usan los servicios de dominio a través de {@code ObjectProvider<Clock>}. */
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

    /** Intención con el proveedor simulado y su webhook ({@code payment.confirmed} o {@code payment.failed}). */
    private void donate(String publicCode, String amountMinor, String type) throws Exception {
        JsonNode intent = ok(send("POST", "/api/v1/public/campaigns/" + publicCode + "/donation-intents", null,
                "{\"amount\":\"" + amountMinor + "\",\"currency\":\"COP\",\"paymentMethod\":\"GATEWAY\"}"), 201);
        String url = intent.get("paymentRedirectUrl").asText();
        String event = "{\"type\":\"" + type + "\",\"paymentSessionId\":\"" + url.substring(url.lastIndexOf('/') + 1)
                + "\",\"providerEventId\":\"evt-" + UUID.randomUUID() + "\",\"amount\":\"" + amountMinor
                + "\",\"currency\":\"COP\"}";
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/webhooks/payments"))
                .header("Content-Type", "application/json").header("X-Simulated-Signature", signature.sign(event))
                .POST(HttpRequest.BodyPublishers.ofString(event)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
    }

    static double round4(double v) {
        return Math.round(v * 10_000.0) / 10_000.0;
    }

    @Test
    void aCopTargetCreatedByCv01_predictsTheSameAsScikitLearnWithTheTargetInPesos() throws Exception {
        JsonNode expected;
        try (InputStream in = getClass().getResourceAsStream("/predictor/e2e-cop-expected.json")) {
            expected = json.readTree(in);
        }
        // mundo: plataforma y organización verificada con su administrador
        String platformEmail = UUID.randomUUID() + "@platform.test";
        accounts.createAccount(new Email(platformEmail), "Pass123!Pass123!");
        String platform = bootstrap.bootstrap(platformEmail).value();
        Account rep = accounts.createAccount(new Email(UUID.randomUUID() + "@e2e.test"), "Pass123!Pass123!");
        AuditActor setup = new AuditActor.SystemAuditActor("e2e-predictor");
        Organization org = organizations.createOrganization(setup, OrganizationType.FOUNDATION, rep.getAccountId(), "Org E2E");
        String admin = accounts.createAccount(new Email(UUID.randomUUID() + "@e2e.test"), "Pass123!Pass123!").getAccountId().value();
        employees.addEmployee(setup, org.getOrganizationId(), new AccountId(admin));
        administrators.assignAdministrator(setup, org.getOrganizationId(), new AccountId(admin));
        String orgId = org.getOrganizationId().value();
        ok(send("POST", "/api/v1/platform/organizations/" + orgId + "/verify", platform, null), 200);

        // CV-01: meta de 8 000 000 COP = 800 000 000 unidades mínimas; 40 días desde C0 + 1 día
        Instant start = C0.plus(Duration.ofDays(1));
        JsonNode campaign = ok(send("POST", "/api/v1/organizations/" + orgId + "/campaigns", admin, """
                {"title":"E2E predictor","visibility":"PUBLIC","startDate":"%s","endDate":"%s",
                 "configuration":{"acceptedDonationTypes":["MONETARY"],"acceptedPaymentMethods":["GATEWAY"],
                 "currency":"COP","targetAmount":"800000000","targetPolicy":"FLEXIBLE"}}"""
                .formatted(start, start.plus(Duration.ofDays(40)))), 201);
        String code = campaign.get("publicCode").asText();

        ControlledClock.CLOCK.now = start.plus(Duration.ofDays(2));
        donate(code, "120000000", "payment.confirmed");                   // 1 200 000 COP el día 2
        ControlledClock.CLOCK.now = start.plus(Duration.ofHours(9 * 24 + 12));
        donate(code, "90000000", "payment.confirmed");                    // 900 000 COP el día 9,5
        donate(code, "50000000", "payment.failed");                       // un intento fallido
        ControlledClock.CLOCK.now = start.plus(Duration.ofDays(10));       // t = 0,25

        JsonNode p = ok(send("GET", "/api/v1/organizations/" + orgId + "/campaigns/"
                + campaign.get("campaignRef").asText() + "/prediction", admin, null), 200);

        assertThat(p.get("available").asBoolean()).as(p.toString()).isTrue();
        assertThat(p.get("modelVersion").asText()).isEqualTo(expected.get("modelVersion").asText());
        assertThat(p.get("pctTimeElapsed").asDouble()).isEqualTo(0.25);
        assertThat(p.get("probabilityReachTarget").asDouble())
                .isEqualTo(round4(expected.get("probabilityReachTarget").asDouble()));
        assertThat(p.get("estimatedFinalPctOfTarget").asDouble())
                .isEqualTo(round4(expected.get("finalPctOfTarget").asDouble()));
    }
}
