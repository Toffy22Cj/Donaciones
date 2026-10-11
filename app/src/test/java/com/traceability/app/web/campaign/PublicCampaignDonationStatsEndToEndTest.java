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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Estadística pública de donaciones, de punta a punta: donaciones por el webhook simulado en tres días distintos (el
 * reloj controlado fija la hora de aplicación de los fondos) y un pago fallido. La ruta es pública, agrupa los días
 * hasta reunir al menos 3 donaciones, su total coincide con "lo recaudado" del detalle y no lleva datos de ninguna
 * donación suelta.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = TraceabilityApplication.class)
@Testcontainers
@Import(PublicCampaignDonationStatsEndToEndTest.ControlledClock.class)
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
class PublicCampaignDonationStatsEndToEndTest {

    static final ZoneId BOGOTA = ZoneId.of("America/Bogota");

    static class MovableClock extends Clock {
        volatile Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
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

    /** Espera a que los fondos de las donaciones confirmadas estén aplicados (el total llega a {@code total}). */
    private JsonNode statsWithTotal(String publicCode, String total) throws Exception {
        JsonNode s = null;
        for (int i = 0; i < 100; i++) {
            s = ok(send("GET", "/api/v1/public/campaigns/" + publicCode + "/donation-stats", null, null), 200);
            if (s.get("totalAmount").asText().equals(total)) return s;
            Thread.sleep(100);
        }
        return s;
    }

    @Test
    void agrupaPorDiasConAlMenosTresDonaciones_sinDatosDeDonacionesSueltas() throws Exception {
        String platformEmail = UUID.randomUUID() + "@platform.test";
        accounts.createAccount(new Email(platformEmail), "Pass123!Pass123!");
        String platform = bootstrap.bootstrap(platformEmail).value();
        Account rep = accounts.createAccount(new Email(UUID.randomUUID() + "@stats.test"), "Pass123!Pass123!");
        AuditActor setup = new AuditActor.SystemAuditActor("e2e-donation-stats");
        Organization org = organizations.createOrganization(setup, OrganizationType.FOUNDATION, rep.getAccountId(), "Org stats");
        String admin = accounts.createAccount(new Email(UUID.randomUUID() + "@stats.test"), "Pass123!Pass123!").getAccountId().value();
        employees.addEmployee(setup, org.getOrganizationId(), new AccountId(admin));
        administrators.assignAdministrator(setup, org.getOrganizationId(), new AccountId(admin));
        String orgId = org.getOrganizationId().value();
        ok(send("POST", "/api/v1/platform/organizations/" + orgId + "/verify", platform, null), 200);

        // mediodía en Bogotá, lejos del cambio de día
        Instant start = Instant.now().minus(Duration.ofDays(10)).truncatedTo(ChronoUnit.DAYS).plus(Duration.ofHours(17));
        ControlledClock.CLOCK.now = start.minus(Duration.ofHours(1));
        JsonNode campaign = ok(send("POST", "/api/v1/organizations/" + orgId + "/campaigns", admin, """
                {"title":"Estadística","visibility":"PUBLIC","startDate":"%s","endDate":"%s",
                 "configuration":{"acceptedDonationTypes":["MONETARY"],"acceptedPaymentMethods":["GATEWAY"],
                 "currency":"COP","targetAmount":"800000000","targetPolicy":"FLEXIBLE"}}"""
                .formatted(start, start.plus(Duration.ofDays(40)))), 201);
        String code = campaign.get("publicCode").asText();

        JsonNode empty = ok(send("GET", "/api/v1/public/campaigns/" + code + "/donation-stats", null, null), 200);
        assertThat(empty.get("totalAmount").asText()).isEqualTo("0");
        assertThat(empty.get("periods")).isEmpty();

        Instant dayA = start.plus(Duration.ofDays(1));
        Instant dayB = start.plus(Duration.ofDays(3));
        Instant dayC = start.plus(Duration.ofDays(5));
        ControlledClock.CLOCK.now = dayA;
        donate(code, "1000000", "payment.confirmed");
        donate(code, "2000000", "payment.confirmed");
        donate(code, "3000000", "payment.confirmed");
        donate(code, "9900000", "payment.failed");                         // no cuenta
        ControlledClock.CLOCK.now = dayB;
        donate(code, "4000000", "payment.confirmed");
        ControlledClock.CLOCK.now = dayC;
        donate(code, "5000000", "payment.confirmed");
        donate(code, "6000000", "payment.confirmed");

        JsonNode s = statsWithTotal(code, "21000000");
        String a = LocalDate.ofInstant(dayA, BOGOTA).toString();
        String b = LocalDate.ofInstant(dayB, BOGOTA).toString();
        String c = LocalDate.ofInstant(dayC, BOGOTA).toString();
        assertThat(s.get("currency").asText()).isEqualTo("COP");
        assertThat(s.get("targetAmount").asText()).isEqualTo("800000000");
        assertThat(s.get("totalAmount").asText()).isEqualTo("21000000");
        assertThat(s.get("donations").asInt()).isEqualTo(6);
        assertThat(s.get("minDonationsPerPeriod").asInt()).isEqualTo(3);
        assertThat(s.get("timeZone").asText()).isEqualTo("America/Bogota");
        assertThat(s.get("periods")).isEqualTo(json.readTree("""
                [{"from":"%s","to":"%s","amount":"6000000","donations":3},
                 {"from":"%s","to":"%s","amount":"15000000","donations":3}]""".formatted(a, a, b, c)));

        JsonNode detail = ok(send("GET", "/api/v1/public/campaigns/" + code, null, null), 200);
        assertThat(detail.get("clearedAmount").asText()).as("el total es lo recaudado").isEqualTo("21000000");
        assertThat(s.toString()).doesNotContain("intentId").doesNotContain("donorRef")
                .doesNotContain(campaign.get("campaignRef").asText()).doesNotContain(orgId);
    }

    @Test
    void unCodigoInexistente_daElMismo404QueElDetalle() throws Exception {
        HttpResponse<String> stats = send("GET", "/api/v1/public/campaigns/CV-ZZZZZZZZZZ/donation-stats", null, null);
        HttpResponse<String> detail = send("GET", "/api/v1/public/campaigns/CV-ZZZZZZZZZZ", null, null);
        assertThat(stats.statusCode()).isEqualTo(404);
        assertThat(detail.statusCode()).isEqualTo(404);
    }
}
