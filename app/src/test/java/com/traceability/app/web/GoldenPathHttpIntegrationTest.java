package com.traceability.app.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.app.TraceabilityApplication;
import com.traceability.app.application.payments.SimulatedWebhookSignature;
import com.traceability.app.web.donation.DonationIntentController;
import com.traceability.app.web.donation.SimulatedPaymentWebhookController;
import com.traceability.contracts.authentication.TokenIssuerPort;
import com.traceability.core.application.command.FundCommandService;
import com.traceability.core.application.port.out.EventStorePort;
import com.traceability.core.domain.event.DomainEvent;
import com.traceability.core.domain.event.SystemActor;
import com.traceability.core.domain.physicalasset.payloads.AssetDeliveredPayload;
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
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.mongodb.core.MongoTemplate;
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
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan B6-d: el recorrido de la demo por HTTP, de punta a punta, contra Tomcat real y todos los módulos reales
 * (golden path §2, pasos 1–5 y 7 sin narrativa ni anclaje). Criterios de §8 cubiertos: 1, 2, 3, 4, 5, 6, 7 (Camino A),
 * 8, 9, 15, 16 y 17. Dos datos no los da ninguna ruta y el test los toma por servicio o de la base de datos, como
 * hallazgos: la asignación del Camino A (H-B6C-1) y el {@code fundId} que el empleado necesita para registrarlo
 * (H-B6D-1).
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
    "saga.outbox.delay=200",
    "traceability.demo.simulated-payments=true",
    // Solo para tests; nunca un valor productivo
    "traceability.demo.webhook-secret=test-only-simulated-webhook-secret-0123456789"
})
class GoldenPathHttpIntegrationTest {

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
    @Autowired private FundCommandService funds;
    @Autowired private EventStorePort eventStore;
    @Autowired private SimulatedWebhookSignature signature;
    @Autowired private MongoTemplate mongoTemplate;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();
    private static final AuditActor SETUP = new AuditActor.SystemAuditActor("golden-path-tests");

    private String account() {
        return accounts.createAccount(new Email(UUID.randomUUID() + "@golden.test"), "Pass123!").getAccountId().value();
    }

    private HttpResponse<String> send(String method, String path, String account, String body, String... headers)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(15))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (account != null) request.header("Authorization", "Bearer " + tokens.issue(account));
        if (body != null) request.header("Content-Type", "application/json");
        if (method.equals("POST")) request.header("Command-Id", UUID.randomUUID().toString());
        for (int i = 0; i < headers.length; i += 2) request.header(headers[i], headers[i + 1]);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode ok(HttpResponse<String> r, int status) throws Exception {
        assertThat(r.statusCode()).as(r.body()).isEqualTo(status);
        return r.body().isEmpty() ? null : json.readTree(r.body());
    }

    private JsonNode until(String what, Callable<JsonNode> read, Predicate<JsonNode> done) throws Exception {
        Instant deadline = Instant.now().plusSeconds(30);
        JsonNode last = null;
        while (Instant.now().isBefore(deadline)) {
            last = read.call();
            if (last != null && done.test(last)) return last;
            Thread.sleep(250);
        }
        throw new AssertionError(what + " not reached; last: " + last);
    }

    interface Callable<T> {
        T call() throws Exception;
    }

    private JsonNode donate(String donorAccount, String publicCode, long amount) throws Exception {
        JsonNode created = ok(send("POST", "/api/v1/public/campaigns/" + publicCode + "/donation-intents", donorAccount,
                "{\"amount\":\"" + amount + "\",\"currency\":\"COP\",\"paymentMethod\":\"GATEWAY\"}"), 201);
        String url = created.get("paymentRedirectUrl").asText();
        String event = "{\"type\":\"payment.confirmed\",\"paymentSessionId\":\"" + url.substring(url.lastIndexOf('/') + 1)
                + "\",\"providerEventId\":\"evt-" + UUID.randomUUID() + "\",\"amount\":\"" + amount + "\",\"currency\":\"COP\"}";
        ok(send("POST", "/api/v1/webhooks/payments", null, event,
                SimulatedPaymentWebhookController.SIGNATURE_HEADER, signature.sign(event)), 200);
        return created;
    }

    private JsonNode intentStatus(JsonNode created) throws Exception {
        return ok(send("GET", "/api/v1/public/donation-intents/" + created.get("intentId").asText(), null, null,
                DonationIntentController.INTENT_TOKEN_HEADER, created.get("statusToken").asText()), 200);
    }

    private void logistics(String employee, String assetRef) throws Exception {
        String base = "/api/v1/physical-assets/" + assetRef;
        ok(send("POST", base + "/dispatch", employee, "{\"carrierRef\":\"carrier-1\"}"), 200);
        ok(send("POST", base + "/receive", employee, "{\"facilityLocation\":\"site-1\",\"receiverRef\":\"rec-1\"}"), 200);
        ok(send("POST", base + "/deliver", employee,
                "{\"finalCustodianRef\":\"cust-f\",\"beneficiaryRef\":\"ben-1\",\"locationRef\":\"site-1\",\"evidenceRef\":\"ev-1\"}"), 200);
    }

    @Test
    void theDemoPath_fromVerificationToPublicTracking_overHttp() throws Exception {
        // Mundo: plataforma, organización PENDING_VERIFICATION con REPRESENTATIVE, un ADMINISTRATOR y un EMPLOYEE
        String platformEmail = UUID.randomUUID() + "@platform.test";
        accounts.createAccount(new Email(platformEmail), "Pass123!");
        String platformAdmin = bootstrap.bootstrap(platformEmail).value();
        Account representative = accounts.createAccount(new Email(UUID.randomUUID() + "@golden.test"), "Pass123!");
        Organization org = organizations.createOrganization(SETUP, OrganizationType.FOUNDATION,
                representative.getAccountId(), "Fundación Golden Path");
        String orgId = org.getOrganizationId().value();
        String admin = account();
        employees.addEmployee(SETUP, org.getOrganizationId(), new AccountId(admin));
        administrators.assignAdministrator(SETUP, org.getOrganizationId(), new AccountId(admin));
        String employee = account();
        employees.addEmployee(SETUP, org.getOrganizationId(), new AccountId(employee));

        // Paso 1 (criterio 1): la plataforma verifica la organización
        assertThat(ok(send("POST", "/api/v1/platform/organizations/" + orgId + "/verify", platformAdmin, null), 200)
                .get("verificationStatus").asText()).isEqualTo("VERIFIED");

        // Paso 2 (criterio 2): convocatoria PUBLIC, FLEXIBLE, y un EMPLOYEE responsable
        JsonNode campaign = ok(send("POST", "/api/v1/organizations/" + orgId + "/campaigns", admin, """
                {"title":"Invierno","visibility":"PUBLIC","startDate":"%s","endDate":"%s",
                 "configuration":{"acceptedDonationTypes":["MONETARY","IN_KIND"],"acceptedPaymentMethods":["GATEWAY"],
                 "currency":"COP","targetAmount":"1000000","targetPolicy":"FLEXIBLE"}}"""
                .formatted(Instant.now().plus(Duration.ofDays(1)), Instant.now().plus(Duration.ofDays(90)))), 201);
        String publicCode = campaign.get("publicCode").asText();
        ok(send("POST", "/api/v1/campaigns/" + campaign.get("campaignRef").asText() + "/employees", admin,
                "{\"employeeRef\":\"" + employee + "\"}"), 201);

        // Paso 3: el donante ve la convocatoria por su publicCode y dona sin cuenta (3A) y con cuenta (3B)
        assertThat(ok(send("GET", "/api/v1/public/campaigns/" + publicCode, null, null), 200)
                .get("organizationName").asText()).isEqualTo("Fundación Golden Path");
        JsonNode anonymous = donate(null, publicCode, 60_000);
        String donor = account();
        JsonNode withAccount = donate(donor, publicCode, 40_000);

        // Criterios 3, 4 y 5: las dos liquidadas, por el mismo flujo de Fund
        JsonNode anonymousStatus = intentStatus(anonymous);
        assertThat(anonymousStatus.get("status").asText()).isEqualTo("CONFIRMED");
        assertThat(intentStatus(withAccount).get("status").asText()).isEqualTo("CONFIRMED");
        assertThat(ok(send("GET", "/api/v1/public/campaigns/" + publicCode, null, null), 200)
                .get("clearedAmount").asText()).isEqualTo("100000");
        String trackingCode = anonymousStatus.get("trackingCode").asText();

        // Criterio 6: la donación autenticada aparece en el historial de la cuenta
        JsonNode history = ok(send("GET", "/api/v1/account/donations", donor, null), 200).get("items");
        assertThat(history).hasSize(1);
        assertThat(history.get(0).get("intentId").asText()).isEqualTo(withAccount.get("intentId").asText());
        assertThat(history.get(0).has("trackingCode")).isTrue();

        // Paso 4, Camino A (criterio 7): el empleado registra un activo comprado con el Fund de la donación anónima.
        // H-B6D-1: ninguna ruta da el fundId; H-B6C-1: la asignación no tiene ruta
        String fundId = mongoTemplate.getCollection("donation_intents")
                .find(new Document("_id", anonymous.get("intentId").asText())).first().getString("fundId");
        String allocationId = UUID.randomUUID().toString();
        funds.requestAllocation(UUID.randomUUID().toString(), fundId, allocationId, 50_000L, new SystemActor("golden-path"));
        JsonNode parent = ok(send("POST", "/api/v1/physical-assets/register", employee, """
                {"fundId":"%s","assetType":"BLANKET","quantity":"10","unitOfMeasure":"UNITS","custodianRef":"cust-1",
                 "currentLocation":"warehouse-1","allocationId":"%s"}""".formatted(fundId, allocationId)), 201);
        String parentRef = parent.get("assetRef").asText();
        assertThat(parent.get("campaignRef").asText()).isEqualTo(campaign.get("campaignRef").asText());

        // Paso 4B (criterios 15 y 16): división; el guion espera al hijo
        HttpResponse<String> split = send("POST", "/api/v1/physical-assets/" + parentRef + "/split", employee,
                "{\"quantity\":\"4\"}");
        String childRef = ok(split, 202).get("childAssetRef").asText();
        String location = split.headers().firstValue("Location").orElseThrow();
        until("child created", () -> ok(send("GET", location, employee, null), 200),
                s -> s.get("status").asText().equals("CHILD_CREATED"));
        assertThat(ok(send("GET", "/api/v1/physical-assets/" + childRef, employee, null), 200).get("quantity").asText())
                .isEqualTo("4.0000");
        assertThat(ok(send("GET", "/api/v1/physical-assets/" + parentRef, employee, null), 200).get("quantity").asText())
                .isEqualTo("6.0000");

        // Paso 5 (criterios 8, 9 y 17): padre e hijo hasta DELIVERED, con beneficiaryRef sellado
        logistics(employee, parentRef);
        logistics(employee, childRef);
        for (String ref : List.of(parentRef, childRef)) {
            assertThat(ok(send("GET", "/api/v1/physical-assets/" + ref, employee, null), 200).get("lifecycleStatus")
                    .asText()).isEqualTo("DELIVERED");
            List<DomainEvent> stream = eventStore.loadStream(ref);
            assertThat(stream.get(stream.size() - 1).payload()).isInstanceOfSatisfying(AssetDeliveredPayload.class,
                    p -> assertThat(p.beneficiaryRef()).isEqualTo("ben-1"));
        }

        // Paso 7: el donante sigue su donación con el trackingCode real; la logística llega por la proyección
        JsonNode tracking = until("logistics projected", () -> {
            HttpResponse<String> r = send("GET", "/api/v1/donations/tracking", null, null, "Authorization", "Bearer " + trackingCode);
            return r.statusCode() == 200 ? json.readTree(r.body()) : null;
        }, t -> t.get("logistics").size() >= 1
                && java.util.stream.StreamSupport.stream(t.get("logistics").spliterator(), false)
                .allMatch(i -> i.get("lifecycleStatus").asText().equals("DELIVERED")));
        String publicAssetRef = tracking.get("logistics").get(0).get("assetRef").asText();
        assertThat(publicAssetRef).isNotIn(parentRef, childRef);
        ok(send("GET", "/api/v1/donations/tracking/assets/" + publicAssetRef + "/history", null, null,
                "Authorization", "Bearer " + trackingCode), 200);
    }
}
