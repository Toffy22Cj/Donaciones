package com.traceability.app.web.campaign;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.app.TraceabilityApplication;
import com.traceability.contracts.authentication.TokenIssuerPort;
import identity.application.service.AddEmployeeService;
import identity.application.service.AssignAdministratorService;
import identity.application.service.BootstrapPlatformAuthorityService;
import identity.application.service.CreateAccountService;
import identity.application.service.CreateOrganizationService;
import identity.domain.model.AccountId;
import identity.domain.model.AuditActor;
import identity.domain.model.Email;
import identity.domain.model.OrganizationId;
import identity.domain.model.OrganizationType;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Autorización (3) de Carlos, §3.5; definición de hecho de la Enmienda 4 de ADR-037: edición directa antes de la
 * primera donación y, después, solicitud aprobada por otro {@code ADMINISTRATOR} o el {@code REPRESENTATIVE}.
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
    "traceability.demo.simulated-payments=true",
    // Solo para tests
    "traceability.demo.webhook-secret=test-only-simulated-webhook-secret-0123456789"
})
class CampaignConfigurationChangeHttpIntegrationTest {

    static final String PASSWORD = "Pass123!Pass123!";
    static final String BOTH = """
            {"acceptedDonationTypes":["MONETARY","IN_KIND"],"acceptedPaymentMethods":["GATEWAY"],"currency":"COP",
             "targetAmount":"5000000","targetPolicy":"FLEXIBLE"}""";
    static final String MONETARY_ONLY = """
            {"acceptedDonationTypes":["MONETARY"],"acceptedPaymentMethods":["GATEWAY"],"currency":"COP",
             "targetAmount":"5000000","targetPolicy":"FLEXIBLE"}""";
    static final String IN_KIND_ONLY = "{\"acceptedDonationTypes\":[\"IN_KIND\"]}";
    static final String OTHER_TARGET = """
            {"acceptedDonationTypes":["MONETARY","IN_KIND"],"acceptedPaymentMethods":["GATEWAY"],"currency":"COP",
             "targetAmount":"9000000","targetPolicy":"FLEXIBLE"}""";

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
    @Autowired private MongoTemplate mongoTemplate;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();
    private static final AuditActor SETUP = new AuditActor.SystemAuditActor("config-tests");
    private static final String START = Instant.now().plus(Duration.ofDays(1)).toString();
    private static final String END = Instant.now().plus(Duration.ofDays(60)).toString();
    private static String org, representative, adminA, adminB, employee, otherAdmin;

    private String account() {
        return accounts.createAccount(new Email(UUID.randomUUID() + "@config.test"), PASSWORD).getAccountId().value();
    }

    private String member(OrganizationId o, boolean administrator) {
        String a = account();
        employees.addEmployee(SETUP, o, new AccountId(a));
        if (administrator) administrators.assignAdministrator(SETUP, o, new AccountId(a));
        return a;
    }

    @BeforeEach
    void world() throws Exception {
        if (org != null) return;
        String platformEmail = UUID.randomUUID() + "@platform.test";
        accounts.createAccount(new Email(platformEmail), PASSWORD);
        String platform = bootstrap.bootstrap(platformEmail).value();
        representative = account();
        OrganizationId o = organizations.createOrganization(SETUP, OrganizationType.FOUNDATION, new AccountId(representative),
                "Config").getOrganizationId();
        org = o.value();
        adminA = member(o, true);
        adminB = member(o, true);
        employee = member(o, false);
        OrganizationId other = organizations.createOrganization(SETUP, OrganizationType.COMPANY, new AccountId(account()),
                "Otra").getOrganizationId();
        otherAdmin = member(other, true);
        ok(send("POST", "/api/v1/platform/organizations/" + org + "/verify", platform, null), 200);
    }

    private HttpResponse<String> send(String method, String path, String account, String body) throws Exception {
        HttpRequest.Builder r = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
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

    private String title(HttpResponse<String> r) throws Exception {
        return json.readTree(r.body()).get("title").asText();
    }

    private JsonNode campaign(String configuration) throws Exception {
        String body = """
                {"title":"Configurable","visibility":"PUBLIC","startDate":"%s","endDate":"%s","configuration":%s}"""
                .formatted(START, END, configuration);
        return ok(send("POST", "/api/v1/organizations/" + org + "/campaigns", adminA, body), 201);
    }

    private static String change(long expected, String configuration) {
        return "{\"expectedConfigurationVersion\":" + expected + ",\"configuration\":" + configuration + "}";
    }

    private HttpResponse<String> edit(String by, String ref, long expected, String configuration) throws Exception {
        return send("POST", "/api/v1/campaigns/" + ref + "/configuration", by, change(expected, configuration));
    }

    private HttpResponse<String> request(String by, String ref, long expected, String configuration) throws Exception {
        return send("POST", "/api/v1/campaigns/" + ref + "/configuration-change-requests", by, change(expected, configuration));
    }

    private HttpResponse<String> decide(String by, String ref, String requestId, String decision) throws Exception {
        return send("POST", "/api/v1/campaigns/" + ref + "/configuration-change-requests/" + requestId + "/" + decision, by, null);
    }

    private List<JsonNode> list(String by, String ref) throws Exception {
        return StreamSupport.stream(ok(send("GET", "/api/v1/campaigns/" + ref + "/configuration-change-requests", by, null), 200)
                .get("items").spliterator(), false).toList();
    }

    private void donate(String publicCode) throws Exception {
        ok(send("POST", "/api/v1/public/campaigns/" + publicCode + "/donation-intents", null,
                "{\"amount\":\"150000\",\"currency\":\"COP\",\"paymentMethod\":\"GATEWAY\"}"), 201);
    }

    private List<String> publicTypes(String publicCode) throws Exception {
        JsonNode view = ok(send("GET", "/api/v1/public/campaigns/" + publicCode, null, null), 200);
        return StreamSupport.stream(view.get("acceptedDonationTypes").spliterator(), false).map(JsonNode::asText).sorted().toList();
    }

    @Test
    void directEditBeforeTheFirstDonation_thenOnlyByARequestApprovedBySomeoneElse() throws Exception {
        JsonNode c = campaign(BOTH);
        String ref = c.get("campaignRef").asText();
        String code = c.get("publicCode").asText();

        // antes de la primera donación: edición directa
        assertThat(ok(edit(adminA, ref, 1, MONETARY_ONLY), 200).get("configurationVersion").asLong()).isEqualTo(2);
        assertThat(publicTypes(code)).containsExactly("MONETARY");
        donate(code);
        HttpResponse<String> direct = edit(adminA, ref, 2, BOTH);
        assertThat(direct.statusCode()).isEqualTo(409);
        assertThat(title(direct)).isEqualTo("CampaignAlreadyHasDonations");

        // después: solicitud
        JsonNode created = ok(request(adminA, ref, 2, BOTH), 201);
        assertThat(created.get("status").asText()).isEqualTo("PENDING");
        assertThat(created.get("baseConfigurationVersion").asLong()).isEqualTo(2);
        String requestId = created.get("requestId").asText();
        HttpResponse<String> second = request(adminB, ref, 2, BOTH);
        assertThat(second.statusCode()).isEqualTo(409);
        assertThat(title(second)).isEqualTo("ConfigurationChangeRequestAlreadyPending");

        // nadie aprueba lo suyo
        HttpResponse<String> self = decide(adminA, ref, requestId, "approve");
        assertThat(self.statusCode()).isEqualTo(403);
        assertThat(title(self)).isEqualTo("SelfApprovalNotAllowed");

        List<JsonNode> pending = list(representative, ref);
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).get("status").asText()).isEqualTo("PENDING");
        assertThat(pending.get(0).get("requestedBy").asText()).isEqualTo(adminA);
        assertThat(pending.get(0).get("proposedConfiguration").get("acceptedDonationTypes")).extracting(JsonNode::asText)
                .containsExactly("IN_KIND", "MONETARY");

        JsonNode approved = ok(decide(adminB, ref, requestId, "approve"), 200);
        assertThat(approved.get("status").asText()).isEqualTo("APPROVED");
        assertThat(approved.get("configurationVersion").asLong()).isEqualTo(3);
        assertThat(publicTypes(code)).containsExactly("IN_KIND", "MONETARY");
        HttpResponse<String> again = decide(representative, ref, requestId, "approve");
        assertThat(again.statusCode()).isEqualTo(409);
        assertThat(title(again)).isEqualTo("ConfigurationChangeRequestNotPending");
        JsonNode decided = list(adminA, ref).get(0);
        assertThat(decided.get("decidedBy").asText()).isEqualTo(adminB);
        assertThat(decided.get("resultingConfigurationVersion").asLong()).isEqualTo(3);

        // la intención anterior conserva la versión con la que se creó
        Document intent = mongoTemplate.getCollection("donation_intents").find(new Document("campaignRef", ref)).first();
        assertThat(intent.get("configurationVersion", Number.class).longValue()).isEqualTo(2);

        // el representante también aprueba
        String byRep = ok(request(adminB, ref, 3, MONETARY_ONLY), 201).get("requestId").asText();
        assertThat(ok(decide(representative, ref, byRep, "approve"), 200).get("configurationVersion").asLong()).isEqualTo(4);
    }

    @Test
    void withDonations_monetaryCannotBeRemoved_andMonetaryTermsCannotChange() throws Exception {
        JsonNode c = campaign(BOTH);
        String ref = c.get("campaignRef").asText();
        donate(c.get("publicCode").asText());
        HttpResponse<String> removal = request(adminA, ref, 1, IN_KIND_ONLY);
        assertThat(removal.statusCode()).isEqualTo(409);
        assertThat(title(removal)).isEqualTo("MonetaryRemovalNotAllowed");
        HttpResponse<String> target = request(adminA, ref, 1, OTHER_TARGET);
        assertThat(target.statusCode()).isEqualTo(409);
        assertThat(title(target)).isEqualTo("MonetaryTermsChangeNotSupported");
        assertThat(request(adminA, ref, 7, BOTH).statusCode()).isEqualTo(409);
        assertThat(list(adminA, ref)).isEmpty();
    }

    @Test
    void approvingTheAdditionOfMonetary_opensItsLedger_andTheCampaignStartsTakingMoney() throws Exception {
        JsonNode c = campaign(IN_KIND_ONLY);
        String ref = c.get("campaignRef").asText();
        assertThat(mongoTemplate.getCollection("campaign_funding_ledgers").countDocuments(new Document("_id", ref))).isZero();
        String requestId = ok(request(adminA, ref, 1, BOTH), 201).get("requestId").asText();
        ok(decide(adminB, ref, requestId, "approve"), 200);
        assertThat(mongoTemplate.getCollection("campaign_funding_ledgers").countDocuments(new Document("_id", ref))).isEqualTo(1);
        donate(c.get("publicCode").asText());
    }

    @Test
    void ifTheConfigurationMovedOn_theApprovalFails_andTheRequestStaysPendingUntilWithdrawn() throws Exception {
        String ref = campaign(BOTH).get("campaignRef").asText();
        String requestId = ok(request(adminA, ref, 1, MONETARY_ONLY), 201).get("requestId").asText();
        ok(edit(adminB, ref, 1, BOTH), 200); // sin donaciones, la edición directa avanza a la versión 2
        HttpResponse<String> stale = decide(adminB, ref, requestId, "approve");
        assertThat(stale.statusCode()).isEqualTo(409);
        assertThat(title(stale)).isEqualTo("ConfigurationVersionConflict");
        assertThat(list(adminA, ref).get(0).get("status").asText()).isEqualTo("PENDING");

        // el solicitante la retira
        assertThat(ok(decide(adminA, ref, requestId, "reject"), 200).get("status").asText()).isEqualTo("REJECTED");
        assertThat(decide(adminB, ref, requestId, "approve").statusCode()).isEqualTo(409);
        assertThat(decide(adminB, ref, requestId, "reject").statusCode()).isEqualTo(409);
        // otra, rechazada por el representante
        String next = ok(request(adminA, ref, 2, MONETARY_ONLY), 201).get("requestId").asText();
        ok(decide(representative, ref, next, "reject"), 200);
    }

    @Test
    void aClosedCampaign_acceptsNoChanges_norApprovals() throws Exception {
        String ref = campaign(BOTH).get("campaignRef").asText();
        String requestId = ok(request(adminA, ref, 1, MONETARY_ONLY), 201).get("requestId").asText();
        ok(send("POST", "/api/v1/campaigns/" + ref + "/close", adminA, null), 200);
        assertThat(decide(adminB, ref, requestId, "approve").statusCode()).isEqualTo(409);
        assertThat(request(adminB, ref, 1, BOTH).statusCode()).isEqualTo(409);
        assertThat(edit(adminA, ref, 1, MONETARY_ONLY).statusCode()).isEqualTo(409);
    }

    @Test
    void onlyTheRightPeople_ofTheOrganization_getPastTheSame403() throws Exception {
        String ref = campaign(BOTH).get("campaignRef").asText();
        String requestId = ok(request(adminA, ref, 1, MONETARY_ONLY), 201).get("requestId").asText();
        String forbidden = title(request(employee, ref, 1, BOTH));
        String sibling = campaign(BOTH).get("campaignRef").asText(); // misma organización, otra convocatoria
        for (HttpResponse<String> r : List.of(
                request(employee, ref, 1, BOTH), request(representative, ref, 1, BOTH), request(otherAdmin, ref, 1, BOTH),
                request(adminA, UUID.randomUUID().toString(), 1, BOTH), edit(employee, ref, 1, BOTH),
                decide(employee, ref, requestId, "approve"), decide(otherAdmin, ref, requestId, "approve"),
                decide(adminB, ref, UUID.randomUUID().toString(), "approve"),
                decide(adminB, sibling, requestId, "approve"), decide(adminB, sibling, requestId, "reject"),
                send("GET", "/api/v1/campaigns/" + ref + "/configuration-change-requests", employee, null),
                send("GET", "/api/v1/campaigns/" + ref + "/configuration-change-requests", otherAdmin, null))) {
            assertThat(r.statusCode()).as(r.body()).isEqualTo(403);
            assertThat(title(r)).isEqualTo(forbidden);
        }
        assertThat(list(adminA, ref).get(0).get("status").asText()).isEqualTo("PENDING");
        // validación con nombre (400) para quien sí puede
        assertThat(send("POST", "/api/v1/campaigns/" + ref + "/configuration", adminA, "{\"configuration\":" + BOTH + "}")
                .statusCode()).isEqualTo(400);
    }
}
