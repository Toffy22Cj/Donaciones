package com.traceability.app.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.app.TraceabilityApplication;
import com.traceability.contracts.authentication.TokenIssuerPort;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P2 de la segunda autorización, contra Tomcat real con {@code identity}, {@code convocatoria} y {@code core} reales:
 * {@code /me}, registro, listado de convocatorias de la organización, cerrar, CV-03 y retirar responsable,
 * descubrimiento público, activos y miembros de la organización, y rechazar / pedir información a una organización.
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
class P2EndpointsHttpIntegrationTest {

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

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();
    private static final AuditActor SETUP = new AuditActor.SystemAuditActor("p2-tests");
    private static final String START = Instant.now().plus(Duration.ofDays(1)).toString();
    private static final String END = Instant.now().plus(Duration.ofDays(60)).toString();

    private static String platformAdmin, org, representative, admin, admin2, employee, inactiveEmployee, loner;
    private static String otherOrg, otherAdmin;

    private String account() {
        return accounts.createAccount(new Email(UUID.randomUUID() + "@p2.test"), "Pass123!").getAccountId().value();
    }

    /** Organización sin verificar: {representative, organizationId}. */
    private String[] organization(String name) {
        Account rep = accounts.createAccount(new Email(UUID.randomUUID() + "@p2.test"), "Pass123!");
        Organization o = organizations.createOrganization(SETUP, OrganizationType.FOUNDATION, rep.getAccountId(), name);
        return new String[] {rep.getAccountId().value(), o.getOrganizationId().value()};
    }

    private String administratorOf(String organizationId) {
        String a = account();
        employees.addEmployee(SETUP, new identity.domain.model.OrganizationId(organizationId), new AccountId(a));
        administrators.assignAdministrator(SETUP, new identity.domain.model.OrganizationId(organizationId), new AccountId(a));
        return a;
    }

    private String employeeOf(String organizationId) {
        String e = account();
        employees.addEmployee(SETUP, new identity.domain.model.OrganizationId(organizationId), new AccountId(e));
        return e;
    }

    @BeforeEach
    void world() throws Exception {
        if (platformAdmin != null) return;
        String email = UUID.randomUUID() + "@platform.test";
        accounts.createAccount(new Email(email), "Pass123!");
        platformAdmin = bootstrap.bootstrap(email).value();
        String[] o = organization("Fundación P2");
        representative = o[0];
        org = o[1];
        admin = administratorOf(org);
        admin2 = administratorOf(org);
        employee = employeeOf(org);
        inactiveEmployee = employeeOf(org);
        deactivations.deactivateAccount(SETUP, new AccountId(inactiveEmployee));
        loner = account();
        String[] other = organization("Otra");
        otherOrg = other[1];
        otherAdmin = administratorOf(otherOrg);
        ok(send("POST", "/api/v1/platform/organizations/" + org + "/verify", platformAdmin, null), 200);
        ok(send("POST", "/api/v1/platform/organizations/" + otherOrg + "/verify", platformAdmin, null), 200);
    }

    private HttpResponse<String> send(String method, String path, String account, String body) throws Exception {
        return send(method, path, account, body, UUID.randomUUID().toString());
    }

    private HttpResponse<String> send(String method, String path, String account, String body, String commandId)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (account != null) request.header("Authorization", "Bearer " + tokens.issue(account));
        if (method.equals("POST") && commandId != null) request.header("Command-Id", commandId);
        if (body != null) request.header("Content-Type", "application/json");
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode ok(HttpResponse<String> r, int status) throws Exception {
        assertThat(r.statusCode()).as(r.body()).isEqualTo(status);
        return r.body().isEmpty() ? null : json.readTree(r.body());
    }

    private JsonNode createCampaign(String visibility, String configuration) throws Exception {
        String body = """
                {"title":"Convocatoria P2","visibility":"%s","startDate":"%s","endDate":"%s","configuration":%s}"""
                .formatted(visibility, START, END, configuration);
        return ok(send("POST", "/api/v1/organizations/" + org + "/campaigns", admin, body), 201);
    }

    static final String MONETARY = """
            {"acceptedDonationTypes":["MONETARY","IN_KIND"],"acceptedPaymentMethods":["GATEWAY"],"currency":"COP",
             "targetAmount":"5000000","targetPolicy":"FLEXIBLE"}""";
    static final String IN_KIND = "{\"acceptedDonationTypes\":[\"IN_KIND\"]}";

    // --- P2.1 /me (ficha N1) ---

    @Test
    void me_returnsExactlyTheFourFields_omittingNulls_withNoStore() throws Exception {
        HttpResponse<String> r = send("GET", "/api/v1/me", admin, null);

        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.headers().firstValue("Cache-Control")).hasValue("no-store");
        assertThat(json.readTree(r.body())).isEqualTo(json.readTree("""
                {"accountId":"%s","organizationId":"%s","roles":["ADMINISTRATOR","EMPLOYEE"]}""".formatted(admin, org)));
        assertThat(json.readTree(send("GET", "/api/v1/me", loner, null).body()))
                .isEqualTo(json.readTree("{\"accountId\":\"" + loner + "\",\"roles\":[]}"));
        assertThat(json.readTree(send("GET", "/api/v1/me", platformAdmin, null).body()).get("platformAuthority").asText())
                .isEqualTo("ADMINISTRATOR");
        assertThat(send("GET", "/api/v1/me", null, null).statusCode()).isEqualTo(401);
        assertThat(send("GET", "/api/v1/me", inactiveEmployee, null).statusCode()).isEqualTo(401);
    }

    // --- P2.2 registro ---

    @Test
    void register_createsAnActiveAccountThatCanLogIn_andRejectsDuplicatesAndInvalidInput() throws Exception {
        String email = UUID.randomUUID() + "@p2.test";
        String body = "{\"email\":\"" + email + "\",\"password\":\"Secret123!\"}";

        HttpResponse<String> created = send("POST", "/api/v1/auth/register", null, body, null);

        assertThat(created.statusCode()).isEqualTo(201);
        JsonNode account = json.readTree(created.body());
        assertThat(account.fieldNames()).toIterable().containsExactlyInAnyOrder("accountId", "status");
        assertThat(account.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(ok(send("POST", "/api/v1/auth/login", null, body, null), 200).get("token").asText()).isNotBlank();

        HttpResponse<String> duplicate = send("POST", "/api/v1/auth/register", null, body, null);
        assertThat(duplicate.statusCode()).isEqualTo(409);
        assertThat(duplicate.body()).contains("DuplicateEmail").doesNotContain(email);

        HttpResponse<String> badEmail = send("POST", "/api/v1/auth/register", null,
                "{\"email\":\"not-an-email\",\"password\":\"Secret123!\"}", null);
        assertThat(badEmail.statusCode()).isEqualTo(400);
        assertThat(badEmail.body()).doesNotContain("not-an-email");
        assertThat(send("POST", "/api/v1/auth/register", null, "{\"email\":\"" + UUID.randomUUID() + "@p2.test\"}", null)
                .statusCode()).isEqualTo(400);
        assertThat(send("POST", "/api/v1/auth/register", null, "{\"password\":\"x\"}", null).statusCode()).isEqualTo(400);
    }

    // --- P2.3 listado de la organización ---

    @Test
    void organizationCampaigns_onlyTheAdministratorOfThatOrganization_withResponsiblesAndAmounts() throws Exception {
        String campaignRef = createCampaign("PUBLIC", MONETARY).get("campaignRef").asText();
        ok(send("POST", "/api/v1/campaigns/" + campaignRef + "/employees", admin, "{\"employeeRef\":\"" + employee + "\"}"), 201);

        JsonNode items = ok(send("GET", "/api/v1/organizations/" + org + "/campaigns", admin, null), 200).get("items");

        JsonNode item = StreamSupport.stream(items.spliterator(), false)
                .filter(i -> i.get("campaignRef").asText().equals(campaignRef)).findFirst().orElseThrow();
        assertThat(item.fieldNames()).toIterable().containsExactlyInAnyOrder("campaignRef", "publicCode", "title",
                "status", "visibility", "currency", "targetAmount", "targetPolicy", "clearedAmount", "responsibles",
                "assignedEmployeeCount");
        assertThat(item.get("targetAmount").asText()).isEqualTo("5000000");
        assertThat(item.get("clearedAmount").asText()).isEqualTo("0");
        assertThat(item.get("assignedEmployeeCount").asLong()).isEqualTo(1);
        assertThat(item.get("responsibles").toString()).contains(employee).contains("EMPLOYEE");

        HttpResponse<String> asEmployee = send("GET", "/api/v1/organizations/" + org + "/campaigns", employee, null);
        HttpResponse<String> foreign = send("GET", "/api/v1/organizations/" + org + "/campaigns", otherAdmin, null);
        HttpResponse<String> absent = send("GET", "/api/v1/organizations/no-such-org/campaigns", admin, null);
        assertThat(asEmployee.statusCode()).isEqualTo(403);
        assertThat(foreign.statusCode()).isEqualTo(403);
        assertThat(absent.statusCode()).isEqualTo(403);
        assertThat(foreign.body()).isEqualTo(asEmployee.body()).isEqualTo(absent.body());
        assertThat(ok(send("GET", "/api/v1/organizations/" + otherOrg + "/campaigns", otherAdmin, null), 200).toString())
                .doesNotContain(campaignRef);
    }

    // --- P2.4 cerrar ---

    @Test
    void close_isTerminal_andIdempotentWithTheSameCommandId() throws Exception {
        JsonNode campaign = createCampaign("PUBLIC", IN_KIND);
        String campaignRef = campaign.get("campaignRef").asText();
        String commandId = UUID.randomUUID().toString();

        assertThat(send("POST", "/api/v1/campaigns/" + campaignRef + "/close", employee, null).statusCode()).isEqualTo(403);
        HttpResponse<String> closed = send("POST", "/api/v1/campaigns/" + campaignRef + "/close", admin, null, commandId);
        HttpResponse<String> again = send("POST", "/api/v1/campaigns/" + campaignRef + "/close", admin, null, commandId);
        HttpResponse<String> other = send("POST", "/api/v1/campaigns/" + campaignRef + "/close", admin, null);

        assertThat(json.readTree(ok(closed, 200).toString())).isEqualTo(json.readTree(
                "{\"campaignRef\":\"" + campaignRef + "\",\"status\":\"CLOSED\"}"));
        assertThat(again.statusCode()).isEqualTo(200);
        assertThat(again.body()).isEqualTo(closed.body());
        assertThat(other.statusCode()).isEqualTo(409);
        assertThat(other.body()).contains("CampaignAlreadyClosed");
        assertThat(ok(send("GET", "/api/v1/public/campaigns/" + campaign.get("publicCode").asText(), null, null), 200)
                .get("status").asText()).isEqualTo("CLOSED");
        assertThat(send("POST", "/api/v1/campaigns/" + campaignRef + "/employees", admin,
                "{\"employeeRef\":\"" + employee + "\"}").statusCode()).isEqualTo(409);
    }

    // --- P2.5 CV-03 y retirar responsable ---

    @Test
    void designateAdministrator_andRemoveResponsible_followTheDomainRules() throws Exception {
        String campaignRef = createCampaign("PUBLIC", IN_KIND).get("campaignRef").asText();
        String base = "/api/v1/campaigns/" + campaignRef;

        JsonNode designated = ok(send("POST", base + "/administrators", admin, "{\"administratorRef\":\"" + admin2 + "\"}"), 201);
        assertThat(designated.get("assignmentId").asText()).isNotBlank();
        assertThat(send("POST", base + "/administrators", admin, "{\"administratorRef\":\"" + admin2 + "\"}").statusCode())
                .as("ya es responsable").isEqualTo(409);
        assertThat(send("POST", base + "/administrators", admin, "{\"administratorRef\":\"" + employee + "\"}").statusCode())
                .as("un EMPLOYEE no es ADMINISTRATOR").isEqualTo(409);
        assertThat(send("POST", base + "/administrators", employee, "{\"administratorRef\":\"" + admin2 + "\"}").statusCode())
                .isEqualTo(403);
        assertThat(send("POST", base + "/administrators", admin, "{}").statusCode()).isEqualTo(400);
        ok(send("POST", base + "/employees", admin, "{\"employeeRef\":\"" + employee + "\"}"), 201);

        assertThat(send("POST", base + "/responsibles/" + employee + "/remove", admin,
                "{\"replacementRef\":\"" + admin + "\"}").statusCode()).as("reemplazo sin actingRole").isEqualTo(400);
        JsonNode removed = ok(send("POST", base + "/responsibles/" + employee + "/remove", admin, null), 200);
        assertThat(removed.fieldNames()).toIterable().containsExactly("removedAssignmentId");
        assertThat(send("POST", base + "/responsibles/" + employee + "/remove", admin, null).statusCode())
                .as("ya no es responsable").isEqualTo(409);
        HttpResponse<String> last = send("POST", base + "/responsibles/" + admin2 + "/remove", admin, null);
        assertThat(last.statusCode()).isEqualTo(409);
        assertThat(last.body()).contains("LastResponsibleRemovalWithoutReplacement");

        JsonNode replaced = ok(send("POST", base + "/responsibles/" + admin2 + "/remove", admin,
                "{\"replacementRef\":\"" + employee + "\",\"replacementActingRole\":\"EMPLOYEE\"}"), 200);
        assertThat(replaced.get("replacementAssignmentId").asText()).isNotBlank();
    }

    // --- P2.6 descubrimiento ---

    @Test
    void discovery_neverListsPrivateLinkNorClosed_paginatesWithAnOpaqueCursor_andHidesInternalIds() throws Exception {
        Set<String> publicCodes = new HashSet<>();
        for (int i = 0; i < 21; i++) {
            publicCodes.add(createCampaign("PUBLIC", MONETARY).get("publicCode").asText());
        }
        JsonNode privateLink = createCampaign("PRIVATE_LINK", MONETARY);
        JsonNode closed = createCampaign("PUBLIC", IN_KIND);
        ok(send("POST", "/api/v1/campaigns/" + closed.get("campaignRef").asText() + "/close", admin, null), 200);

        List<JsonNode> all = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            JsonNode page = ok(send("GET", "/api/v1/public/campaigns" + (cursor == null ? "" : "?cursor=" + cursor), null, null), 200);
            assertThat(page.get("items").size()).isLessThanOrEqualTo(20);
            page.get("items").forEach(all::add);
            cursor = page.has("nextCursor") ? page.get("nextCursor").asText() : null;
            pages++;
        } while (cursor != null && pages < 50);

        assertThat(pages).isGreaterThanOrEqualTo(2);
        Set<String> listed = new HashSet<>();
        all.forEach(i -> listed.add(i.get("publicCode").asText()));
        assertThat(listed).hasSize(all.size()).containsAll(publicCodes)
                .doesNotContain(privateLink.get("publicCode").asText(), closed.get("publicCode").asText());
        assertThat(all.get(0).fieldNames()).toIterable().containsExactlyInAnyOrder("publicCode", "title",
                "organizationName", "status", "startDate", "endDate", "acceptedDonationTypes", "currency",
                "targetAmount", "clearedAmount");
        assertThat(all.toString()).doesNotContain(org).doesNotContain(privateLink.get("campaignRef").asText());

        assertThat(send("GET", "/api/v1/public/campaigns?cursor=%21%21", null, null).statusCode()).isEqualTo(400);
        assertThat(send("GET", "/api/v1/public/campaigns?cursor=eA", null, null).statusCode()).isEqualTo(400);
    }

    // --- P2.7 activos y miembros ---

    @Test
    void organizationAssets_forAdministratorOrEmployee_withoutDonorData() throws Exception {
        String asset = ok(send("POST", "/api/v1/physical-assets/from-donation", employee, """
                {"assetType":"FOOD","quantity":"10","unitOfMeasure":"KGS","custodianRef":"CUST-1","currentLocation":"WH-1"}"""),
                201).get("assetRef").asText();

        JsonNode items = ok(send("GET", "/api/v1/organizations/" + org + "/physical-assets", admin, null), 200).get("items");
        JsonNode item = StreamSupport.stream(items.spliterator(), false)
                .filter(i -> i.get("assetRef").asText().equals(asset)).findFirst().orElseThrow();
        assertThat(item.get("lifecycleStatus").asText()).isEqualTo("REGISTERED");
        assertThat(item.get("quantity").asText()).isEqualTo("10.0000");
        assertThat(items.toString()).doesNotContain("donorRef").doesNotContain("anon:").doesNotContain("acct:");
        ok(send("GET", "/api/v1/organizations/" + org + "/physical-assets", employee, null), 200);

        HttpResponse<String> foreign = send("GET", "/api/v1/organizations/" + org + "/physical-assets", otherAdmin, null);
        HttpResponse<String> asRepresentativeOnly = send("GET", "/api/v1/organizations/" + org + "/physical-assets", representative, null);
        assertThat(foreign.statusCode()).isEqualTo(403);
        assertThat(asRepresentativeOnly.statusCode()).isEqualTo(403);
        assertThat(ok(send("GET", "/api/v1/organizations/" + otherOrg + "/physical-assets", otherAdmin, null), 200)
                .toString()).doesNotContain(asset);
    }

    @Test
    void members_forAdministratorOrRepresentative_withRolesAndStatus_andNoEmail() throws Exception {
        JsonNode items = ok(send("GET", "/api/v1/organizations/" + org + "/members", representative, null), 200).get("items");

        assertThat(items.toString()).doesNotContain("@p2.test").doesNotContain("email");
        JsonNode rep = StreamSupport.stream(items.spliterator(), false)
                .filter(m -> m.get("accountId").asText().equals(representative)).findFirst().orElseThrow();
        assertThat(rep.get("roles").toString()).isEqualTo("[\"REPRESENTATIVE\"]");
        JsonNode inactive = StreamSupport.stream(items.spliterator(), false)
                .filter(m -> m.get("accountId").asText().equals(inactiveEmployee)).findFirst().orElseThrow();
        assertThat(inactive.get("status").asText()).isEqualTo("INACTIVE");
        assertThat(items.size()).isEqualTo(5);
        ok(send("GET", "/api/v1/organizations/" + org + "/members", admin, null), 200);

        HttpResponse<String> asEmployee = send("GET", "/api/v1/organizations/" + org + "/members", employee, null);
        HttpResponse<String> foreign = send("GET", "/api/v1/organizations/" + org + "/members", otherAdmin, null);
        HttpResponse<String> absent = send("GET", "/api/v1/organizations/no-such-org/members", admin, null);
        assertThat(asEmployee.statusCode()).isEqualTo(403);
        assertThat(foreign.body()).isEqualTo(asEmployee.body()).isEqualTo(absent.body());
        assertThat(absent.statusCode()).isEqualTo(403);
    }

    // --- P2.8 rechazar y pedir información ---

    @Test
    void reject_andRequestInformation_onlyByThePlatform_with409OnAFinalState() throws Exception {
        String toReject = organization("Para rechazar")[1];
        String toAsk = organization("Para preguntar")[1];
        String platform = "/api/v1/platform/organizations/";

        assertThat(send("POST", platform + toReject + "/reject", admin, null).statusCode()).isEqualTo(403);
        assertThat(json.readTree(ok(send("POST", platform + toReject + "/reject", platformAdmin, null), 200).toString()))
                .isEqualTo(json.readTree("{\"organizationId\":\"" + toReject + "\",\"verificationStatus\":\"REJECTED\"}"));
        assertThat(send("POST", platform + toReject + "/reject", platformAdmin, null).statusCode()).isEqualTo(409);
        assertThat(send("POST", platform + toReject + "/verify", platformAdmin, null).statusCode()).isEqualTo(409);
        assertThat(send("POST", platform + toReject + "/request-information", platformAdmin, "{\"message\":\"x\"}")
                .statusCode()).isEqualTo(409);
        assertThat(send("POST", platform + org + "/reject", platformAdmin, null).statusCode()).as("ya VERIFIED").isEqualTo(409);

        assertThat(send("POST", platform + toAsk + "/request-information", admin, "{\"message\":\"\"}").statusCode())
                .as("autoriza antes de validar el mensaje").isEqualTo(403);
        assertThat(send("POST", platform + toAsk + "/request-information", platformAdmin, "{\"message\":\" \"}").statusCode())
                .isEqualTo(400);
        assertThat(send("POST", platform + toAsk + "/request-information", platformAdmin,
                "{\"message\":\"" + "a".repeat(2001) + "\"}").statusCode()).isEqualTo(400);
        assertThat(send("POST", platform + toAsk + "/request-information", platformAdmin, null).statusCode()).isEqualTo(400);
        assertThat(ok(send("POST", platform + toAsk + "/request-information", platformAdmin,
                "{\"message\":\"Falta el certificado de existencia\"}"), 200).get("verificationStatus").asText())
                .isEqualTo("NEEDS_MORE_INFORMATION");
        ok(send("POST", platform + toAsk + "/verify", platformAdmin, null), 200);
        assertThat(send("POST", platform + "no-such-org/reject", platformAdmin, null).statusCode()).isEqualTo(404);
    }
}
