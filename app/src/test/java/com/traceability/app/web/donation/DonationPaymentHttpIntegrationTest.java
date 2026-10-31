package com.traceability.app.web.donation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.app.TraceabilityApplication;
import com.traceability.app.application.payments.PaymentEventsMonitoring;
import com.traceability.app.application.payments.SimulatedWebhookSignature;
import com.traceability.contracts.authentication.TokenIssuerPort;
import com.traceability.convocatoria.application.command.CreateConvocatoriaCommand;
import com.traceability.convocatoria.application.service.ConvocatoriaLifecycleService;
import com.traceability.convocatoria.domain.model.ConvocatoriaConfiguration;
import com.traceability.convocatoria.domain.model.DonationType;
import com.traceability.convocatoria.domain.model.PaymentMethod;
import com.traceability.convocatoria.domain.model.TargetPolicy;
import com.traceability.convocatoria.domain.model.Visibility;
import identity.application.service.AddEmployeeService;
import identity.application.service.AssignAdministratorService;
import identity.application.service.BootstrapPlatformAuthorityService;
import identity.application.service.CreateAccountService;
import identity.application.service.CreateOrganizationService;
import identity.application.service.DonorPseudonymService;
import identity.application.service.VerifyOrganizationService;
import identity.domain.model.Account;
import identity.domain.model.AccountId;
import identity.domain.model.AuditActor;
import identity.domain.model.Email;
import identity.domain.model.Organization;
import identity.domain.model.OrganizationType;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
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
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan B6-b contra Tomcat real, con el proveedor simulado activo y {@code identity}, {@code convocatoria}, el
 * orquestador de ADR-045 y {@code core} reales: CV-11, webhook simulado (Tx 1 → Tx 2), consulta con
 * {@code statusToken}, {@code trackingCode} válido para el seguimiento de Fase 3, ADR-048 y {@code /account/donations}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = TraceabilityApplication.class)
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = {
    "crypto.anchor.poll.delay=9999999",
    "crypto.anchor.submit.delay=9999999",
    "crypto.anchor.stuck-monitor.delay=9999999",
    "crypto.web3j.private-key=0x1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef",
    "crypto.web3j.node-url=http://dummy-node",
    "spring.ai.openai.api-key=dummy-api-key",
    "traceability.demo.simulated-payments=true",
    // Solo para tests; nunca un valor productivo
    "traceability.demo.webhook-secret=test-only-simulated-webhook-secret-0123456789"
})
class DonationPaymentHttpIntegrationTest {

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
    @Autowired private VerifyOrganizationService verifications;
    @Autowired private ConvocatoriaLifecycleService lifecycle;
    @Autowired private DonorPseudonymService pseudonyms;
    @Autowired private SimulatedWebhookSignature signature;
    @Autowired private PaymentEventsMonitoring monitoring;
    @Autowired private MongoTemplate mongoTemplate;
    @Autowired private com.traceability.contracts.authorization.IdentityPrincipalPort principals;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();
    private static final AuditActor SETUP = new AuditActor.SystemAuditActor("b6b-tests");

    private static String publicCode;
    private static String organizationId;
    private static String organizationAdmin;
    private static ConvocatoriaConfiguration config;
    private static String donor;
    private static String otherDonor;

    private String account() {
        return accounts.createAccount(new Email(UUID.randomUUID() + "@b6b.test"), "Pass123!Pass123!").getAccountId().value();
    }

    @BeforeEach
    void world() {
        if (publicCode != null) return;
        String email = UUID.randomUUID() + "@platform.test";
        accounts.createAccount(new Email(email), "Pass123!Pass123!");
        String platformAdmin = bootstrap.bootstrap(email).value();
        Account representative = accounts.createAccount(new Email(UUID.randomUUID() + "@b6b.test"), "Pass123!Pass123!");
        Organization org = organizations.createOrganization(SETUP, OrganizationType.FOUNDATION,
                representative.getAccountId(), "Fundación B6-b");
        String admin = account();
        employees.addEmployee(SETUP, org.getOrganizationId(), new AccountId(admin));
        administrators.assignAdministrator(SETUP, org.getOrganizationId(), new AccountId(admin));
        // ficha R-7, P2 (b): razón social y NIT sintético antes de verificar (P-R7-4)
        com.traceability.app.support.OrganizationVerificationData.prepare(mongoTemplate, org.getOrganizationId());
        verifications.verifyOrganization(principals.resolvePrincipal(platformAdmin), org.getOrganizationId());
        organizationId = org.getOrganizationId().value();
        organizationAdmin = admin;
        config = new ConvocatoriaConfiguration(EnumSet.of(DonationType.MONETARY),
                Set.of(PaymentMethod.GATEWAY, PaymentMethod.BANK_TRANSFER), "COP", 10_000_000L, TargetPolicy.FLEXIBLE, null);
        publicCode = lifecycle.createConvocatoria(new CreateConvocatoriaCommand(UUID.randomUUID().toString(), admin,
                org.getOrganizationId().value(), "Invierno", null, Visibility.PUBLIC,
                Instant.now().plus(Duration.ofDays(1)), Instant.now().plus(Duration.ofDays(90)), config)).publicCode();
        donor = account();
        otherDonor = account();
    }

    private HttpResponse<String> send(String method, String path, String account, String commandId, String body,
                                      String... headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(15))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (account != null) request.header("Authorization", "Bearer " + tokens.issue(account));
        if (commandId != null) request.header("Command-Id", commandId);
        if (body != null) request.header("Content-Type", "application/json");
        for (int i = 0; i < headers.length; i += 2) request.header(headers[i], headers[i + 1]);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String newId() {
        return UUID.randomUUID().toString();
    }

    private static String gateway(long amount) {
        return "{\"amount\":\"" + amount + "\",\"currency\":\"COP\",\"paymentMethod\":\"GATEWAY\"}";
    }

    private JsonNode createIntent(String account, long amount) throws Exception {
        HttpResponse<String> r = send("POST", "/api/v1/public/campaigns/" + publicCode + "/donation-intents", account,
                newId(), gateway(amount));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        return json.readTree(r.body());
    }

    private static String sessionOf(JsonNode created) {
        String url = created.get("paymentRedirectUrl").asText();
        return url.substring(url.lastIndexOf('/') + 1);
    }

    private HttpResponse<String> webhook(String type, String sessionId, String eventId, long amount) throws Exception {
        String body = "{\"type\":\"" + type + "\",\"paymentSessionId\":\"" + sessionId + "\",\"providerEventId\":\""
                + eventId + "\",\"amount\":\"" + amount + "\",\"currency\":\"COP\"}";
        return send("POST", "/api/v1/webhooks/payments", null, null, body,
                SimulatedPaymentWebhookController.SIGNATURE_HEADER, signature.sign(body));
    }

    private JsonNode status(JsonNode created) throws Exception {
        HttpResponse<String> r = send("GET", "/api/v1/public/donation-intents/" + created.get("intentId").asText(), null,
                null, null, DonationIntentController.INTENT_TOKEN_HEADER, created.get("statusToken").asText());
        assertThat(r.statusCode()).isEqualTo(200);
        return json.readTree(r.body());
    }

    private Document intentDoc(String intentId) {
        return mongoTemplate.getCollection("donation_intents").find(new Document("_id", intentId)).first();
    }

    private long cleared() throws Exception {
        return json.readTree(send("GET", "/api/v1/public/campaigns/" + publicCode, null, null, null).body())
                .get("clearedAmount").asLong();
    }

    // --- Webhook simulado, Tx 1 → Tx 2, trackingCode (Enmienda 3 §5) ---

    @Test
    void anonymousGatewayDonation_webhookConfirmsAndAppliesFunds_andTheTrackingCodeWorksInPhase3Tracking() throws Exception {
        long before = cleared();
        JsonNode created = createIntent(null, 1234);
        assertThat(created.fieldNames()).toIterable().containsExactlyInAnyOrder("intentId", "statusToken", "paymentRedirectUrl");
        assertThat(status(created).fieldNames()).toIterable().containsExactly("status");
        assertThat(status(created).get("status").asText()).isEqualTo("PENDING");

        assertThat(webhook("payment.confirmed", sessionOf(created), "evt-" + newId(), 1234).statusCode()).isEqualTo(200);

        JsonNode after = status(created);
        assertThat(after.get("status").asText()).isEqualTo("CONFIRMED");
        String trackingCode = after.get("trackingCode").asText();
        assertThat(status(created).get("trackingCode").asText()).isEqualTo(trackingCode);
        assertThat(cleared()).isEqualTo(before + 1234);
        Document doc = intentDoc(created.get("intentId").asText());
        assertThat(doc.getString("paymentProvider")).isEqualTo("SIMULATED");
        assertThat(doc.getString("confirmationSource")).isEqualTo("PAYMENT_PROVIDER");
        assertThat(doc.getString("donorRef")).startsWith("anon:");
        assertThat(doc.get("fundsAppliedAt")).isNotNull();

        // El trackingCode es el de Fase 3: el seguimiento lo acepta en cuanto la proyección procesa la génesis
        int trackingStatus = 0;
        Instant deadline = Instant.now().plusSeconds(30);
        while (trackingStatus != 200 && Instant.now().isBefore(deadline)) {
            trackingStatus = send("GET", "/api/v1/donations/tracking", null, null, null,
                    "Authorization", "Bearer " + trackingCode).statusCode();
            if (trackingStatus != 200) Thread.sleep(250);
        }
        assertThat(trackingStatus).isEqualTo(200);
    }

    /**
     * Ficha R-6, G-1 (test 3), con la implementación real del puerto: el seguimiento lleva el título de la convocatoria
     * y el nombre de su organización; sigue sin fondo ni donante (DD-76).
     */
    @Test
    void theTracking_carriesTheCampaignTitleAndTheOrganizationName_withoutFundNorDonor() throws Exception {
        JsonNode created = createIntent(null, 777);
        assertThat(webhook("payment.confirmed", sessionOf(created), "evt-" + newId(), 777).statusCode()).isEqualTo(200);
        String trackingCode = status(created).get("trackingCode").asText();

        HttpResponse<String> tracking = null;
        Instant deadline = Instant.now().plusSeconds(30);
        while ((tracking == null || tracking.statusCode() != 200) && Instant.now().isBefore(deadline)) {
            tracking = send("GET", "/api/v1/donations/tracking", null, null, null, "Authorization", "Bearer " + trackingCode);
            if (tracking.statusCode() != 200) Thread.sleep(250);
        }
        assertThat(tracking.statusCode()).as(tracking.body()).isEqualTo(200);
        JsonNode body = json.readTree(tracking.body());
        assertThat(body.get("campaignTitle").asText()).isEqualTo("Invierno");
        assertThat(body.get("organizationName").asText()).isEqualTo("Fundación B6-b");
        // ficha R-6, G-2: una sola liquidación (la génesis del pago): primera = última, ISO-8601 UTC
        JsonNode snapshot = body.get("financialSnapshot");
        assertThat(snapshot.get("firstClearedAt").asText()).endsWith("Z");
        assertThat(snapshot.get("lastClearedAt").asText()).isEqualTo(snapshot.get("firstClearedAt").asText());
        assertThat(body.has("fundId")).isFalse();
        assertThat(body.has("donorRef")).isFalse();
        assertThat(tracking.body()).doesNotContain("anon:").doesNotContain("acct:");
    }

    @Test
    void duplicateWebhook_hasNoEffect_andAFailureAfterConfirmationIsIgnored() throws Exception {
        JsonNode created = createIntent(null, 500);
        String eventId = "evt-" + newId();
        assertThat(webhook("payment.confirmed", sessionOf(created), eventId, 500).statusCode()).isEqualTo(200);
        long once = cleared();

        assertThat(webhook("payment.confirmed", sessionOf(created), eventId, 500).statusCode()).isEqualTo(200);
        assertThat(webhook("payment.failed", sessionOf(created), "evt-" + newId(), 500).statusCode()).isEqualTo(200);

        assertThat(cleared()).isEqualTo(once);
        assertThat(status(created).get("status").asText()).isEqualTo("CONFIRMED");
    }

    @Test
    void failureOnPending_isFailed_andALateConfirmationIsRecordedAsUnacceptable_withoutChangingTheState() throws Exception {
        JsonNode created = createIntent(null, 700);
        long before = cleared();
        long recorded = monitoring.getUnacceptablePaymentEvents();
        String lateEvent = "evt-late-" + newId();

        assertThat(webhook("payment.failed", sessionOf(created), "evt-" + newId(), 700).statusCode()).isEqualTo(200);
        assertThat(status(created).get("status").asText()).isEqualTo("FAILED");
        assertThat(webhook("payment.confirmed", sessionOf(created), lateEvent, 700).statusCode()).isEqualTo(200);
        assertThat(webhook("payment.confirmed", sessionOf(created), lateEvent, 700).statusCode()).isEqualTo(200);

        assertThat(status(created).get("status").asText()).isEqualTo("FAILED");
        assertThat(cleared()).isEqualTo(before);
        Document record = mongoTemplate.getCollection("unacceptable_payment_events")
                .find(new Document("providerEventId", lateEvent)).first();
        assertThat(record).isNotNull();
        assertThat(record.getString("intentId")).isEqualTo(created.get("intentId").asText());
        assertThat(record.getLong("amount")).isEqualTo(700L);
        assertThat(monitoring.getUnacceptablePaymentEvents()).isEqualTo(recorded + 1);
        assertThat(monitoring.getUnacceptablePaymentEventsSinceStart()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void aWebhookWithABadSignatureOrAMismatchedAmount_changesNothing() throws Exception {
        JsonNode created = createIntent(null, 900);
        String body = "{\"type\":\"payment.confirmed\",\"paymentSessionId\":\"" + sessionOf(created)
                + "\",\"providerEventId\":\"evt-x\",\"amount\":\"900\",\"currency\":\"COP\"}";

        HttpResponse<String> unsigned = send("POST", "/api/v1/webhooks/payments", null, null, body,
                SimulatedPaymentWebhookController.SIGNATURE_HEADER, "00");
        HttpResponse<String> mismatch = webhook("payment.confirmed", sessionOf(created), "evt-" + newId(), 901);

        assertThat(unsigned.statusCode()).isEqualTo(401);
        assertThat(mismatch.statusCode()).isEqualTo(409);
        assertThat(status(created).get("status").asText()).isEqualTo("PENDING");
    }

    @Test
    void twoIdenticalWebhooksAtTheSameTime_confirmAndApplyOnce() throws Exception {
        JsonNode created = createIntent(null, 333);
        long before = cleared();
        String eventId = "evt-" + newId();
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            Callable<Integer> call = () -> {
                barrier.await();
                return webhook("payment.confirmed", sessionOf(created), eventId, 333).statusCode();
            };
            results.add(pool.submit(call));
        }
        for (Future<Integer> f : results) assertThat(f.get()).isEqualTo(200);
        pool.shutdown();

        assertThat(cleared()).isEqualTo(before + 333);
        assertThat(mongoTemplate.getCollection("event_store")
                .countDocuments(new Document("streamId", intentDoc(created.get("intentId").asText()).getString("fundId"))))
                .isEqualTo(1);
    }

    // --- statusToken (D6) ---

    @Test
    void statusToken_absentWrongOrInTheUrl_givesTheSame404_asAnUnknownIntent(CapturedOutput output) throws Exception {
        JsonNode created = createIntent(null, 100);
        String intentId = created.get("intentId").asText();
        String token = created.get("statusToken").asText();

        HttpResponse<String> absent = send("GET", "/api/v1/public/donation-intents/" + intentId, null, null, null);
        HttpResponse<String> wrong = send("GET", "/api/v1/public/donation-intents/" + intentId, null, null, null,
                DonationIntentController.INTENT_TOKEN_HEADER, token + "x");
        HttpResponse<String> inUrl = send("GET", "/api/v1/public/donation-intents/" + intentId + "?intentToken=" + token
                + "&Intent-Token=" + token, null, null, null);
        HttpResponse<String> unknown = send("GET", "/api/v1/public/donation-intents/" + newId(), null, null, null,
                DonationIntentController.INTENT_TOKEN_HEADER, token);

        for (HttpResponse<String> r : List.of(absent, wrong, inUrl, unknown)) {
            assertThat(r.statusCode()).isEqualTo(404);
            assertThat(r.body()).isEqualTo(unknown.body());
        }
        assertThat(output.getAll()).doesNotContain(token);
        assertThat(token).matches("[A-Za-z0-9_-]{43}");
    }

    @Test
    void aDuplicateCv11_returnsTheSameIntentWithANewToken_andTheOldOneStopsWorking() throws Exception {
        // DD-18 sustituida por Carlos (2026-10-07): el reenvío emite un statusToken nuevo y anula el anterior
        String commandId = newId();
        String path = "/api/v1/public/campaigns/" + publicCode + "/donation-intents";

        JsonNode first = json.readTree(send("POST", path, null, commandId, gateway(10)).body());
        HttpResponse<String> again = send("POST", path, null, commandId, gateway(10));

        assertThat(again.statusCode()).isEqualTo(201);
        JsonNode second = json.readTree(again.body());
        assertThat(second.get("intentId")).isEqualTo(first.get("intentId"));
        assertThat(second.get("paymentRedirectUrl")).isEqualTo(first.get("paymentRedirectUrl"));
        assertThat(second.get("statusToken").asText()).isNotBlank().isNotEqualTo(first.get("statusToken").asText());
        String statusPath = "/api/v1/public/donation-intents/" + first.get("intentId").asText();
        assertThat(send("GET", statusPath, null, null, null, DonationIntentController.INTENT_TOKEN_HEADER,
                first.get("statusToken").asText()).statusCode()).isEqualTo(404);
        assertThat(status(second).get("status").asText()).isEqualTo("PENDING");
    }

    @Test
    void cv11_withAnUnknownPublicCode_is404_andWithAnInvalidBody_is400() throws Exception {
        String path = "/api/v1/public/campaigns/" + "Z".repeat(26) + "/donation-intents";

        assertThat(send("POST", path, null, newId(), gateway(10)).statusCode()).isEqualTo(404);
        assertThat(send("POST", "/api/v1/public/campaigns/" + publicCode + "/donation-intents", null, newId(),
                "{\"amount\":\"-5\",\"currency\":\"COP\",\"paymentMethod\":\"GATEWAY\"}").statusCode()).isEqualTo(400);
        assertThat(send("POST", "/api/v1/public/campaigns/" + publicCode + "/donation-intents", null, null,
                gateway(10)).statusCode()).isEqualTo(400);
    }

    // --- ADR-048 y /account/donations ---

    @Test
    void withJwt_theDonorRefIsTheStablePseudonymOfTheAccount_andTheClientCannotChooseIt() throws Exception {
        String path = "/api/v1/public/campaigns/" + publicCode + "/donation-intents";
        JsonNode a = json.readTree(send("POST", path, donor, newId(),
                "{\"amount\":\"11\",\"currency\":\"COP\",\"paymentMethod\":\"GATEWAY\",\"donorRef\":\"acct:someone-else\"}").body());
        JsonNode b = createIntent(donor, 12);
        JsonNode c = createIntent(otherDonor, 13);
        JsonNode anon1 = createIntent(null, 14);
        JsonNode anon2 = createIntent(null, 15);

        String refA = intentDoc(a.get("intentId").asText()).getString("donorRef");
        assertThat(refA).isEqualTo("acct:" + pseudonyms.existingPseudonymFor(donor).orElseThrow());
        assertThat(intentDoc(b.get("intentId").asText()).getString("donorRef")).isEqualTo(refA);
        assertThat(intentDoc(c.get("intentId").asText()).getString("donorRef")).startsWith("acct:").isNotEqualTo(refA);
        assertThat(intentDoc(anon1.get("intentId").asText()).getString("donorRef")).startsWith("anon:")
                .isNotEqualTo(intentDoc(anon2.get("intentId").asText()).getString("donorRef"));
        assertThat(refA).doesNotContain(donor);
    }

    @Test
    void accountDonations_listsOnlyTheOwnDonations_withTheTrackingCodeOnceApplied_andNeverThePseudonym(
            CapturedOutput output) throws Exception {
        String me = account();
        JsonNode mine = createIntent(me, 4321);
        createIntent(otherDonor, 99);
        webhook("payment.confirmed", sessionOf(mine), "evt-" + newId(), 4321);

        HttpResponse<String> r = send("GET", "/api/v1/account/donations", me, null, null);

        assertThat(r.statusCode()).isEqualTo(200);
        JsonNode items = json.readTree(r.body()).get("items");
        assertThat(items).hasSize(1);
        JsonNode item = items.get(0);
        assertThat(item.get("intentId").asText()).isEqualTo(mine.get("intentId").asText());
        assertThat(item.get("amount").asText()).isEqualTo("4321");
        assertThat(item.get("campaignTitle").asText()).isEqualTo("Invierno");
        assertThat(item.get("trackingCode").asText()).isEqualTo(status(mine).get("trackingCode").asText());
        String pseudonym = pseudonyms.existingPseudonymFor(me).orElseThrow();
        assertThat(r.body()).doesNotContain(pseudonym).doesNotContain("acct:").doesNotContain("donorRef");
        assertThat(output.getAll()).doesNotContain(pseudonym);
        assertThat(send("GET", "/api/v1/account/donations", null, null, null).statusCode()).isEqualTo(401);
        assertThat(json.readTree(send("GET", "/api/v1/account/donations", account(), null, null).body())
                .get("items")).isEmpty();
    }

    // --- A2: historial con la Enmienda 5 de ADR-037 (plan-a2-historial-donaciones.md §4) ---

    private String campaign(String admin, String org, String title, Visibility visibility) {
        return lifecycle.createConvocatoria(new CreateConvocatoriaCommand(UUID.randomUUID().toString(), admin, org, title,
                null, visibility, Instant.now().plus(Duration.ofDays(1)), Instant.now().plus(Duration.ofDays(90)),
                config)).publicCode();
    }

    private JsonNode donateTo(String code, String account, String body) throws Exception {
        HttpResponse<String> r = send("POST", "/api/v1/public/campaigns/" + code + "/donation-intents", account, newId(), body);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        return json.readTree(r.body());
    }

    private static String bankTransfer(long amount) {
        return "{\"amount\":\"" + amount + "\",\"currency\":\"COP\",\"paymentMethod\":\"BANK_TRANSFER\"}";
    }

    private JsonNode historyOf(String account) throws Exception {
        HttpResponse<String> r = send("GET", "/api/v1/account/donations", account, null, null);
        assertThat(r.statusCode()).isEqualTo(200);
        return json.readTree(r.body());
    }

    private static JsonNode item(JsonNode history, String intentId) {
        for (JsonNode i : history.get("items")) {
            if (i.get("intentId").asText().equals(intentId)) return i;
        }
        throw new AssertionError("intención no encontrada en el historial");
    }

    /** F-1, F-3 y F-4; criterio 4 por HTTP. */
    @Test
    void history_carriesDatesOrganizationMethodAndPublicCode_newestFirst() throws Exception {
        String me = account();
        String privateCode = campaign(organizationAdmin, organizationId, "Privada", Visibility.PRIVATE_LINK);
        JsonNode confirmed = createIntent(me, 500);
        webhook("payment.confirmed", sessionOf(confirmed), "evt-" + newId(), 500);
        Thread.sleep(5); // createdAt distinto
        JsonNode pending = donateTo(privateCode, me, bankTransfer(700));

        JsonNode history = historyOf(me);

        JsonNode first = history.get("items").get(0);
        JsonNode second = history.get("items").get(1);
        assertThat(first.get("intentId").asText()).isEqualTo(pending.get("intentId").asText());
        assertThat(second.get("intentId").asText()).isEqualTo(confirmed.get("intentId").asText());

        JsonNode c = item(history, confirmed.get("intentId").asText());
        assertThat(c.get("createdAt").asText()).endsWith("Z");
        assertThat(Instant.parse(c.get("confirmedAt").asText())).isAfterOrEqualTo(Instant.parse(c.get("createdAt").asText()));
        assertThat(c.get("organizationName").asText()).isEqualTo("Fundación B6-b");
        assertThat(c.get("paymentMethod").asText()).isEqualTo("GATEWAY");
        assertThat(c.get("publicCode").asText()).isEqualTo(publicCode);

        JsonNode p = item(history, pending.get("intentId").asText());
        assertThat(p.has("confirmedAt")).isFalse();
        assertThat(p.get("paymentMethod").asText()).isEqualTo("BANK_TRANSFER");
        assertThat(p.get("campaignTitle").asText()).isEqualTo("Privada");
        assertThat(p.get("organizationName").asText()).isEqualTo("Fundación B6-b");
    }

    /** F-2 (aserción explícita): una donación a una PRIVATE_LINK nunca trae su publicCode. */
    @Test
    void aDonationToAPrivateLinkCampaign_neverCarriesItsPublicCode() throws Exception {
        String me = account();
        String privateCode = campaign(organizationAdmin, organizationId, "Solo con enlace", Visibility.PRIVATE_LINK);
        JsonNode intent = donateTo(privateCode, me, bankTransfer(300));

        HttpResponse<String> r = send("GET", "/api/v1/account/donations", me, null, null);

        JsonNode i = item(json.readTree(r.body()), intent.get("intentId").asText());
        assertThat(i.has("publicCode")).isFalse();
        assertThat(r.body()).doesNotContain(privateCode);
    }

    /** Criterio 6 (aserción negativa): ni organizationRef ni donorRef salen por HTTP. */
    @Test
    void history_neverCarriesTheOrganizationRefNorTheDonorRef() throws Exception {
        String me = account();
        createIntent(me, 123);

        HttpResponse<String> r = send("GET", "/api/v1/account/donations", me, null, null);

        assertThat(r.body()).doesNotContain("organizationRef").doesNotContain("donorRef")
                .doesNotContain(organizationId).doesNotContain("acct:")
                .doesNotContain(pseudonyms.existingPseudonymFor(me).orElseThrow());
    }

    /** Criterio 7 (aserción negativa): crear (sin confirmar) y leer el historial no escribe eventos. */
    @Test
    void creatingAndReadingTheHistory_writesNoEvent() throws Exception {
        String me = account();
        long before = mongoTemplate.getCollection("event_store").countDocuments();

        donateTo(publicCode, me, bankTransfer(450));
        historyOf(me);

        assertThat(mongoTemplate.getCollection("event_store").countDocuments()).isEqualTo(before);
    }

    /** F-5 (P-A2-5): una organización sin nombre, sembrada en Mongo (por HTTP el nombre es obligatorio, DD-68). */
    @Test
    void anOrganizationWithoutName_givesTheItemWithoutOrganizationName() throws Exception {
        String rep = account();
        String unnamed = identity.domain.model.OrganizationId.generate().value();
        mongoTemplate.getCollection("organizations").insertOne(new Document("_id", unnamed).append("type", "FOUNDATION")
                .append("verificationStatus", "VERIFIED")
                .append("members", List.of(new Document("accountId", rep)
                        .append("roles", List.of("REPRESENTATIVE", "ADMINISTRATOR", "EMPLOYEE")))));
        mongoTemplate.getCollection("accounts").updateOne(new Document("_id", rep),
                new Document("$set", new Document("organizationId", unnamed)));
        String code = campaign(rep, unnamed, "Sin nombre", Visibility.PUBLIC);
        String me = account();
        JsonNode intent = donateTo(code, me, bankTransfer(200));

        JsonNode i = item(historyOf(me), intent.get("intentId").asText());

        assertThat(i.has("organizationName")).isFalse();
        assertThat(i.get("campaignTitle").asText()).isEqualTo("Sin nombre");
        assertThat(i.get("publicCode").asText()).isEqualTo(code);
    }

    @Test
    void forgettingThePseudonym_emptiesTheHistory_andLeavesTheEventsUntouched() throws Exception {
        String me = account();
        JsonNode mine = createIntent(me, 77);
        webhook("payment.confirmed", sessionOf(mine), "evt-" + newId(), 77);
        String fundId = intentDoc(mine.get("intentId").asText()).getString("fundId");
        long events = mongoTemplate.getCollection("event_store").countDocuments(new Document("streamId", fundId));

        assertThat(pseudonyms.forget(me)).isTrue();

        assertThat(json.readTree(send("GET", "/api/v1/account/donations", me, null, null).body()).get("items")).isEmpty();
        assertThat(mongoTemplate.getCollection("event_store").countDocuments(new Document("streamId", fundId)))
                .isEqualTo(events);
    }
}
