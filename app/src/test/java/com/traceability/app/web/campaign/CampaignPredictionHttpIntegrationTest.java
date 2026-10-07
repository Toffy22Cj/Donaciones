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
import identity.domain.model.Account;
import identity.domain.model.AccountId;
import identity.domain.model.AuditActor;
import identity.domain.model.Email;
import identity.domain.model.Organization;
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
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P3 contra Tomcat real: la ruta de predicción es de solo lectura, siempre {@code kind: "ESTIMATE"} con la versión
 * del modelo y la advertencia de datos sintéticos, solo para {@code ADMINISTRATOR} o {@code REPRESENTATIVE} de la
 * organización, y STRICT nunca tiene estimación. La estimación con datos la prueba {@code CampaignPredictionUseCaseTest}
 * (CV-01 no admite convocatorias ya empezadas, así que aquí todas están aún sin empezar).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = TraceabilityApplication.class)
@Testcontainers
@TestPropertySource(properties = {
    "crypto.anchor.poll.delay=9999999",
    "crypto.anchor.submit.delay=9999999",
    "crypto.anchor.stuck-monitor.delay=9999999",
    "crypto.web3j.private-key=0x1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef",
    "crypto.web3j.node-url=http://dummy-node",
    "spring.ai.openai.api-key=dummy-api-key"
})
class CampaignPredictionHttpIntegrationTest {

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
    private static final AuditActor SETUP = new AuditActor.SystemAuditActor("p3-tests");
    private static final String START = Instant.now().plus(Duration.ofDays(1)).toString();
    private static final String END = Instant.now().plus(Duration.ofDays(60)).toString();

    private static String org, admin, representative, employee, otherOrg, otherAdmin;
    private static String flexible, strict, inKind;

    private String account() {
        return accounts.createAccount(new Email(UUID.randomUUID() + "@p3.test"), "Pass123!Pass123!").getAccountId().value();
    }

    private String[] organization(String platformAdmin) throws Exception {
        Account rep = accounts.createAccount(new Email(UUID.randomUUID() + "@p3.test"), "Pass123!Pass123!");
        Organization o = organizations.createOrganization(SETUP, OrganizationType.FOUNDATION, rep.getAccountId(), "Org P3");
        String a = account();
        employees.addEmployee(SETUP, o.getOrganizationId(), new AccountId(a));
        administrators.assignAdministrator(SETUP, o.getOrganizationId(), new AccountId(a));
        String e = account();
        employees.addEmployee(SETUP, o.getOrganizationId(), new AccountId(e));
        String id = o.getOrganizationId().value();
        assertThat(send("POST", "/api/v1/platform/organizations/" + id + "/verify", platformAdmin, null).statusCode())
                .isEqualTo(200);
        return new String[] {id, a, rep.getAccountId().value(), e};
    }

    private String campaign(String configuration) throws Exception {
        String body = """
                {"title":"Convocatoria P3","visibility":"PUBLIC","startDate":"%s","endDate":"%s","configuration":%s}"""
                .formatted(START, END, configuration);
        HttpResponse<String> r = send("POST", "/api/v1/organizations/" + org + "/campaigns", admin, body);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        return json.readTree(r.body()).get("campaignRef").asText();
    }

    static String monetary(String policy) {
        return """
                {"acceptedDonationTypes":["MONETARY"],"acceptedPaymentMethods":["GATEWAY"],"currency":"COP",
                 "targetAmount":"8000000","targetPolicy":"%s"}""".formatted(policy);
    }

    @BeforeEach
    void world() throws Exception {
        if (org != null) return;
        String email = UUID.randomUUID() + "@platform.test";
        accounts.createAccount(new Email(email), "Pass123!Pass123!");
        String platformAdmin = bootstrap.bootstrap(email).value();
        String[] mine = organization(platformAdmin);
        org = mine[0]; admin = mine[1]; representative = mine[2]; employee = mine[3];
        String[] other = organization(platformAdmin);
        otherOrg = other[0]; otherAdmin = other[1];
        flexible = campaign(monetary("FLEXIBLE"));
        strict = campaign(monetary("STRICT"));
        inKind = campaign("{\"acceptedDonationTypes\":[\"IN_KIND\"]}");
    }

    private HttpResponse<String> send(String method, String path, String account, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (account != null) request.header("Authorization", "Bearer " + tokens.issue(account));
        if (method.equals("POST")) request.header("Command-Id", UUID.randomUUID().toString());
        if (body != null) request.header("Content-Type", "application/json");
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> prediction(String organization, String campaignRef, String account) throws Exception {
        return send("GET", "/api/v1/organizations/" + organization + "/campaigns/" + campaignRef + "/prediction", account, null);
    }

    private Map<String, Long> counts() {
        Map<String, Long> counts = new TreeMap<>();
        for (String c : mongoTemplate.getCollectionNames()) {
            counts.put(c, mongoTemplate.getCollection(c).countDocuments(new Document()));
        }
        return counts;
    }

    @Test
    void theAdministrator_getsAnEstimateEnvelope_withVersionAndSyntheticWarning_andNoStore() throws Exception {
        HttpResponse<String> r = prediction(org, flexible, admin);

        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.headers().firstValue("Cache-Control")).hasValue("no-store");
        JsonNode body = json.readTree(r.body());
        assertThat(body.get("kind").asText()).isEqualTo("ESTIMATE");
        assertThat(body.get("modelVersion").asText()).isEqualTo("baseline-0.2.0");
        assertThat(body.get("warning").asText()).isEqualTo("modelo entrenado con datos sintéticos");
        assertThat(body.get("available").asBoolean()).isFalse();
        assertThat(body.get("unavailableReason").asText()).isEqualTo("NOT_STARTED");
        assertThat(body.has("probabilityReachTarget")).isFalse();
        assertThat(r.body()).doesNotContain(flexible).doesNotContain(org).doesNotContain("donorRef");
    }

    @Test
    void strict_neverHasAnEstimate_andInKindHasNoMonetaryTarget() throws Exception {
        JsonNode s = json.readTree(prediction(org, strict, representative).body());
        JsonNode k = json.readTree(prediction(org, inKind, admin).body());

        assertThat(s.get("available").asBoolean()).isFalse();
        assertThat(s.get("unavailableReason").asText()).isEqualTo("STRICT_POLICY_EXCLUDED");
        assertThat(s.get("unavailableText").asText()).contains("STRICT");
        assertThat(k.get("unavailableReason").asText()).isEqualTo("NO_MONETARY_TARGET");
    }

    @Test
    void onlyAdministratorOrRepresentativeOfTheOrganization_andEverythingElseIsTheSame403() throws Exception {
        assertThat(prediction(org, flexible, representative).statusCode()).isEqualTo(200);

        HttpResponse<String> asEmployee = prediction(org, flexible, employee);
        HttpResponse<String> otherOrganization = prediction(org, flexible, otherAdmin);
        HttpResponse<String> foreignCampaign = prediction(otherOrg, flexible, otherAdmin);
        HttpResponse<String> unknownCampaign = prediction(org, "no-such-campaign", admin);

        for (HttpResponse<String> r : java.util.List.of(asEmployee, otherOrganization, foreignCampaign, unknownCampaign)) {
            assertThat(r.statusCode()).isEqualTo(403);
            assertThat(r.body()).isEqualTo(asEmployee.body());
        }
        assertThat(prediction(org, flexible, null).statusCode()).isEqualTo(401);
    }

    @Test
    void readingAPrediction_writesNothing() throws Exception {
        Map<String, Long> before = counts();

        prediction(org, flexible, admin);
        prediction(org, strict, admin);
        prediction(org, flexible, employee);

        assertThat(counts()).isEqualTo(before);
    }
}
