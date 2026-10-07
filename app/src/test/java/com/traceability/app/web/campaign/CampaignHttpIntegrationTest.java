package com.traceability.app.web.campaign;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.app.TraceabilityApplication;
import com.traceability.contracts.authentication.TokenIssuerPort;
import com.traceability.convocatoria.application.command.CloseConvocatoriaCommand;
import com.traceability.convocatoria.application.service.ConvocatoriaLifecycleService;
import identity.application.service.AddEmployeeService;
import identity.application.service.AssignAdministratorService;
import identity.application.service.BootstrapPlatformAuthorityService;
import identity.application.service.CreateAccountService;
import identity.application.service.CreateOrganizationService;
import identity.application.service.DeactivateAccountService;
import identity.domain.model.Account;
import identity.domain.model.AccountId;
import identity.domain.model.AuditActor;
import identity.domain.model.Email;
import identity.domain.model.Organization;
import identity.domain.model.OrganizationType;
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
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan B6-a, tests 1–10, contra Tomcat real con {@code identity} y {@code convocatoria} reales: pasos 1 y 2 del golden
 * path por HTTP (verificar organización, crear convocatoria, asignar responsable) y el detalle público CV-07.
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
    "spring.ai.openai.api-key=dummy-api-key"
})
class CampaignHttpIntegrationTest {

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
    @Autowired private DeactivateAccountService deactivations;
    @Autowired private ConvocatoriaLifecycleService lifecycle;
    @Autowired private MongoTemplate mongoTemplate;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();
    private static final AuditActor SETUP = new AuditActor.SystemAuditActor("b6a-tests");

    // un mundo por contexto (el bootstrap de plataforma es único)
    private static String platformAdmin;
    private static String org;
    private static String admin;
    private static String employee;
    private static String inactiveEmployee;
    private static String otherOrg;
    private static String otherAdmin;
    private static String otherEmployee;
    private static String unverifiedOrg;
    private static String unverifiedAdmin;

    private String account() {
        return accounts.createAccount(new Email(UUID.randomUUID() + "@b6a.test"), "Pass123!").getAccountId().value();
    }

    private String organization(String name, String[] adminOut, String... extraEmployees) {
        Account representative = accounts.createAccount(new Email(UUID.randomUUID() + "@b6a.test"), "Pass123!");
        Organization o = organizations.createOrganization(SETUP, OrganizationType.FOUNDATION,
                representative.getAccountId(), name);
        String a = account();
        employees.addEmployee(SETUP, o.getOrganizationId(), new AccountId(a));
        administrators.assignAdministrator(SETUP, o.getOrganizationId(), new AccountId(a));
        adminOut[0] = a;
        for (String e : extraEmployees) {
            employees.addEmployee(SETUP, o.getOrganizationId(), new AccountId(e));
        }
        return o.getOrganizationId().value();
    }

    @BeforeEach
    void world() throws Exception {
        if (platformAdmin != null) return;
        String email = UUID.randomUUID() + "@platform.test";
        accounts.createAccount(new Email(email), "Pass123!");
        platformAdmin = bootstrap.bootstrap(email).value();

        String[] out = new String[1];
        employee = account();
        inactiveEmployee = account();
        org = organization("Fundación Demo", out, employee, inactiveEmployee);
        admin = out[0];
        deactivations.deactivateAccount(SETUP, new AccountId(inactiveEmployee));
        otherEmployee = account();
        otherOrg = organization(null, out, otherEmployee);
        otherAdmin = out[0];
        unverifiedOrg = organization("Sin verificar", out);
        unverifiedAdmin = out[0];

        assertThat(send("POST", "/api/v1/platform/organizations/" + org + "/verify", platformAdmin, null, null).statusCode())
                .isEqualTo(200);
        assertThat(send("POST", "/api/v1/platform/organizations/" + otherOrg + "/verify", platformAdmin, null, null).statusCode())
                .isEqualTo(200);
    }

    private HttpResponse<String> send(String method, String path, String account, String commandId, String body)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (account != null) request.header("Authorization", "Bearer " + tokens.issue(account));
        if (commandId != null) request.header("Command-Id", commandId);
        if (body != null) request.header("Content-Type", "application/json");
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String newId() {
        return UUID.randomUUID().toString();
    }

    private static final String START = Instant.now().plus(Duration.ofDays(1)).toString();
    private static final String END = Instant.now().plus(Duration.ofDays(60)).toString();

    private static String monetaryBody(String visibility) {
        return """
                {"title":"Campaña demo","description":"desc","visibility":"%s","startDate":"%s","endDate":"%s",
                 "configuration":{"acceptedDonationTypes":["MONETARY","IN_KIND"],"acceptedPaymentMethods":["GATEWAY"],
                 "currency":"COP","targetAmount":"5000000","targetPolicy":"FLEXIBLE"}}""".formatted(visibility, START, END);
    }

    private static final String IN_KIND_BODY = """
            {"title":"Especie","visibility":"PUBLIC","startDate":"%s","endDate":"%s",
             "configuration":{"acceptedDonationTypes":["IN_KIND"]}}""".formatted(START, END);

    private HttpResponse<String> createCampaign(String organization, String actor, String commandId, String body)
            throws Exception {
        return send("POST", "/api/v1/organizations/" + organization + "/campaigns", actor, commandId, body);
    }

    private JsonNode created(String body) throws Exception {
        HttpResponse<String> r = createCampaign(org, admin, newId(), body);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        return json.readTree(r.body());
    }

    // --- Verificar organización (test 10) ---

    @Test
    void verify_onlyThePlatformAdministrator_once_andAnUnknownOrganizationIs404() throws Exception {
        String path = "/api/v1/platform/organizations/" + unverifiedOrg + "/verify";

        assertThat(send("POST", path, unverifiedAdmin, null, null).statusCode()).isEqualTo(403);
        assertThat(send("POST", "/api/v1/platform/organizations/" + org + "/verify", platformAdmin, null, null)
                .statusCode()).isEqualTo(409);
        assertThat(send("POST", "/api/v1/platform/organizations/no-such-org/verify", platformAdmin, null, null)
                .statusCode()).isEqualTo(404);
        assertThat(send("POST", path, null, null, null).statusCode()).isEqualTo(401);
    }

    @Test
    void verify_returnsTheNewStatus() throws Exception {
        String[] out = new String[1];
        String fresh = organization("Nueva", out);

        HttpResponse<String> r = send("POST", "/api/v1/platform/organizations/" + fresh + "/verify", platformAdmin, null, null);

        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(json.readTree(r.body())).isEqualTo(json.readTree(
                "{\"organizationId\":\"" + fresh + "\",\"verificationStatus\":\"VERIFIED\"}"));
    }

    // --- CV-01 (tests 1–5) ---

    @Test
    void cv01_createsAndAnswersExactlyCampaignRefAndPublicCode_andADuplicateIsIdentical() throws Exception {
        String commandId = newId();
        long before = mongoTemplate.getCollection("convocatorias").countDocuments();

        HttpResponse<String> first = createCampaign(org, admin, commandId, monetaryBody("PUBLIC"));
        HttpResponse<String> again = createCampaign(org, admin, commandId, monetaryBody("PUBLIC"));

        assertThat(first.statusCode()).isEqualTo(201);
        JsonNode body = json.readTree(first.body());
        assertThat(body.fieldNames()).toIterable().containsExactlyInAnyOrder("campaignRef", "publicCode");
        assertThat(body.get("publicCode").asText()).matches("[0-9A-HJKMNP-TV-Z]{26}");
        assertThat(again.statusCode()).isEqualTo(201);
        assertThat(again.body()).isEqualTo(first.body());
        assertThat(mongoTemplate.getCollection("convocatorias").countDocuments()).isEqualTo(before + 1);
    }

    @Test
    void cv01_aCommandIdOfAnotherCommand_is409() throws Exception {
        String commandId = newId();
        String campaignRef = created(monetaryBody("PUBLIC")).get("campaignRef").asText();
        assertThat(send("POST", "/api/v1/campaigns/" + campaignRef + "/employees", admin, commandId,
                "{\"employeeRef\":\"" + employee + "\"}").statusCode()).isEqualTo(201);

        HttpResponse<String> reused = createCampaign(org, admin, commandId, monetaryBody("PUBLIC"));

        assertThat(reused.statusCode()).isEqualTo(409);
        assertThat(reused.body()).contains("CommandIdReusedForDifferentCommand");
    }

    @Test
    void cv01_otherOrganization_andNonexistentOrganization_giveTheSame403_andUnverifiedIs409() throws Exception {
        HttpResponse<String> foreign = createCampaign(otherOrg, admin, newId(), monetaryBody("PUBLIC"));
        HttpResponse<String> absent = createCampaign("no-such-org", admin, newId(), monetaryBody("PUBLIC"));
        HttpResponse<String> notAdmin = createCampaign(org, employee, newId(), monetaryBody("PUBLIC"));
        HttpResponse<String> unverified = createCampaign(unverifiedOrg, unverifiedAdmin, newId(), monetaryBody("PUBLIC"));

        assertThat(foreign.statusCode()).isEqualTo(403);
        assertThat(absent.body()).isEqualTo(foreign.body());
        assertThat(absent.statusCode()).isEqualTo(403);
        assertThat(notAdmin.statusCode()).isEqualTo(403);
        assertThat(notAdmin.body()).isEqualTo(foreign.body());
        assertThat(unverified.statusCode()).isEqualTo(409);
        assertThat(unverified.body()).contains("OrganizationNotVerified");
    }

    @Test
    void cv01_invalidBodies_are400_withoutEcho_andWriteNothing() throws Exception {
        String past = Instant.now().minus(Duration.ofDays(2)).toString();
        Map<String, String> bodies = Map.ofEntries(
                Map.entry("fecha sin Z", monetaryBody("PUBLIC").replace(START, START.replace("Z", "+00:00"))),
                Map.entry("targetAmount no numérico", monetaryBody("PUBLIC").replace("\"5000000\"", "\"5e6\"")),
                Map.entry("targetAmount fuera de long", monetaryBody("PUBLIC").replace("\"5000000\"", "\"99999999999999999999\"")),
                Map.entry("enum desconocido", monetaryBody("PUBLIC").replace("FLEXIBLE", "WHATEVER_ZZZ")),
                Map.entry("D-1 moneda", monetaryBody("PUBLIC").replace("\"COP\"", "\"cop\"")),
                Map.entry("D-2 fecha pasada", monetaryBody("PUBLIC").replace(START, past)),
                Map.entry("D-7 medios en IN_KIND", IN_KIND_BODY.replace("[\"IN_KIND\"]", "[\"IN_KIND\"],\"acceptedPaymentMethods\":[\"CASH\"]")),
                Map.entry("D-8 sin visibilidad", monetaryBody("PUBLIC").replace("\"visibility\":\"PUBLIC\",", "")),
                Map.entry("D-9 descripción larga", monetaryBody("PUBLIC").replace("\"desc\"", "\"" + "x".repeat(5001) + "\"")),
                Map.entry("título ausente", monetaryBody("PUBLIC").replace("\"title\":\"Campaña demo\",", "")),
                Map.entry("sin configuración", "{\"title\":\"t\",\"visibility\":\"PUBLIC\",\"startDate\":\"" + START + "\",\"endDate\":\"" + END + "\"}"),
                Map.entry("JSON inválido", "{not json"));
        long campaigns = mongoTemplate.getCollection("convocatorias").countDocuments();
        long audits = mongoTemplate.getCollection("convocatoria_audit_log").countDocuments();

        for (Map.Entry<String, String> e : bodies.entrySet()) {
            HttpResponse<String> r = createCampaign(org, admin, newId(), e.getValue());
            assertThat(r.statusCode()).as(e.getKey() + ": " + r.body()).isEqualTo(400);
            assertThat(r.body()).as(e.getKey()).doesNotContain("WHATEVER_ZZZ").doesNotContain("99999999999999999999")
                    .doesNotContain("xxxxxxxxxx").doesNotContain("cop\"");
        }
        assertThat(mongoTemplate.getCollection("convocatorias").countDocuments()).isEqualTo(campaigns);
        assertThat(mongoTemplate.getCollection("convocatoria_audit_log").countDocuments()).isEqualTo(audits);
    }

    // --- CV-02 (test 7) ---

    @Test
    void cv02_assigns_andADuplicateIsIdentical() throws Exception {
        String campaignRef = created(monetaryBody("PUBLIC")).get("campaignRef").asText();
        String commandId = newId();
        String path = "/api/v1/campaigns/" + campaignRef + "/employees";

        HttpResponse<String> first = send("POST", path, admin, commandId, "{\"employeeRef\":\"" + employee + "\"}");
        HttpResponse<String> again = send("POST", path, admin, commandId, "{\"employeeRef\":\"" + employee + "\"}");

        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(json.readTree(first.body()).fieldNames()).toIterable().containsExactly("assignmentId");
        assertThat(again.statusCode()).isEqualTo(201);
        assertThat(again.body()).isEqualTo(first.body());
    }

    @Test
    void cv02_unknownCampaign_foreignCampaign_andNotAdministrator_giveTheSame403() throws Exception {
        String foreignCampaign = json.readTree(createCampaign(otherOrg, otherAdmin, newId(), monetaryBody("PUBLIC"))
                .body()).get("campaignRef").asText();
        String ownCampaign = created(monetaryBody("PUBLIC")).get("campaignRef").asText();
        String body = "{\"employeeRef\":\"" + employee + "\"}";

        HttpResponse<String> unknown = send("POST", "/api/v1/campaigns/" + newId() + "/employees", admin, newId(), body);
        HttpResponse<String> foreign = send("POST", "/api/v1/campaigns/" + foreignCampaign + "/employees", admin, newId(), body);
        HttpResponse<String> notAdmin = send("POST", "/api/v1/campaigns/" + ownCampaign + "/employees", employee, newId(), body);

        assertThat(unknown.statusCode()).isEqualTo(403);
        assertThat(foreign.statusCode()).isEqualTo(403);
        assertThat(notAdmin.statusCode()).isEqualTo(403);
        assertThat(foreign.body()).isEqualTo(unknown.body()).isEqualTo(notAdmin.body());
    }

    @Test
    void cv02_foreignNonexistentAndInactiveRecipients_giveTheSame409() throws Exception {
        String path = "/api/v1/campaigns/" + created(monetaryBody("PUBLIC")).get("campaignRef").asText() + "/employees";

        HttpResponse<String> foreign = send("POST", path, admin, newId(), "{\"employeeRef\":\"" + otherEmployee + "\"}");
        HttpResponse<String> absent = send("POST", path, admin, newId(), "{\"employeeRef\":\"" + newId() + "\"}");
        HttpResponse<String> inactive = send("POST", path, admin, newId(), "{\"employeeRef\":\"" + inactiveEmployee + "\"}");

        assertThat(foreign.statusCode()).isEqualTo(409);
        assertThat(absent.statusCode()).isEqualTo(409);
        assertThat(inactive.statusCode()).isEqualTo(409);
        assertThat(absent.body()).isEqualTo(foreign.body()).isEqualTo(inactive.body());
        assertThat(foreign.body()).doesNotContain(otherEmployee);
    }

    @Test
    void cv02_onAClosedCampaign_is409() throws Exception {
        String campaignRef = created(monetaryBody("PUBLIC")).get("campaignRef").asText();
        lifecycle.closeConvocatoria(new CloseConvocatoriaCommand(newId(), admin, campaignRef));

        HttpResponse<String> r = send("POST", "/api/v1/campaigns/" + campaignRef + "/employees", admin, newId(),
                "{\"employeeRef\":\"" + employee + "\"}");

        assertThat(r.statusCode()).isEqualTo(409);
        assertThat(r.body()).contains("ResponsibleAssignmentOnClosedCampaign");
    }

    // --- CV-07 (tests 8 y 9) ---

    @Test
    void cv07_monetary_hasExactlyThePublicFields_withoutJwt() throws Exception {
        String publicCode = created(monetaryBody("PUBLIC")).get("publicCode").asText();

        HttpResponse<String> r = send("GET", "/api/v1/public/campaigns/" + publicCode, null, null, null);

        assertThat(r.statusCode()).isEqualTo(200);
        JsonNode v = json.readTree(r.body());
        assertThat(v.fieldNames()).toIterable().containsExactlyInAnyOrder("organizationName", "title", "description",
                "status", "startDate", "endDate", "acceptedDonationTypes", "acceptedPaymentMethods", "currency",
                "targetAmount", "clearedAmount");
        assertThat(v.get("organizationName").asText()).isEqualTo("Fundación Demo");
        assertThat(v.get("targetAmount").asText()).isEqualTo("5000000");
        assertThat(v.get("clearedAmount").asText()).isEqualTo("0");
        assertThat(v.get("status").asText()).isEqualTo("OPEN");
        assertThat(r.body()).doesNotContain(publicCode).doesNotContain("targetPolicy").doesNotContain("FLEXIBLE")
                .doesNotContain(org);
    }

    @Test
    void cv07_inKind_hasNoMonetaryFields_andAnOrganizationWithoutNameOmitsIt() throws Exception {
        String publicCode = json.readTree(createCampaign(otherOrg, otherAdmin, newId(), IN_KIND_BODY).body())
                .get("publicCode").asText();

        JsonNode v = json.readTree(send("GET", "/api/v1/public/campaigns/" + publicCode, null, null, null).body());

        assertThat(v.fieldNames()).toIterable().containsExactlyInAnyOrder("title", "status", "startDate", "endDate",
                "acceptedDonationTypes");
    }

    @Test
    void cv07_privateLinkAndClosedCampaigns_areReturned_unknownAndMalformedCodesGiveTheSame404() throws Exception {
        JsonNode privateLink = created(monetaryBody("PRIVATE_LINK"));
        lifecycle.closeConvocatoria(new CloseConvocatoriaCommand(newId(), admin, privateLink.get("campaignRef").asText()));

        HttpResponse<String> found = send("GET", "/api/v1/public/campaigns/" + privateLink.get("publicCode").asText(),
                null, null, null);
        HttpResponse<String> unknown = send("GET", "/api/v1/public/campaigns/" + "0".repeat(26), null, null, null);
        HttpResponse<String> malformed = send("GET", "/api/v1/public/campaigns/abc", null, null, null);

        assertThat(found.statusCode()).isEqualTo(200);
        assertThat(json.readTree(found.body()).get("status").asText()).isEqualTo("CLOSED");
        assertThat(unknown.statusCode()).isEqualTo(404);
        assertThat(malformed.statusCode()).isEqualTo(404);
        assertThat(malformed.body()).isEqualTo(unknown.body());
    }

    @Test
    void cv07_thePublicCodeNeverReachesTheLogs(CapturedOutput output) throws Exception {
        String publicCode = created(monetaryBody("PUBLIC")).get("publicCode").asText();
        String unknownCode = "Z".repeat(26);

        send("GET", "/api/v1/public/campaigns/" + publicCode, null, null, null);
        send("GET", "/api/v1/public/campaigns/" + unknownCode, null, null, null);

        assertThat(output.getAll()).doesNotContain(publicCode).doesNotContain(unknownCode);
    }
}
