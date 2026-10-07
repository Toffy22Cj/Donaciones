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
import java.util.List;
import java.util.UUID;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Autorización (3) de Carlos, §3.4, contra Tomcat real: {@code GET /api/v1/me/campaigns} devuelve solo las
 * asignaciones activas de quien llama en su organización, con su papel y el estado de la convocatoria.
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
class MyAssignedCampaignsHttpIntegrationTest {

    static final String PASSWORD = "Pass123!Pass123!";

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

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();
    private static final AuditActor SETUP = new AuditActor.SystemAuditActor("my-campaigns");
    private static final String START = Instant.now().plus(Duration.ofDays(1)).toString();
    private static final String END = Instant.now().plus(Duration.ofDays(60)).toString();

    private String account() {
        return accounts.createAccount(new Email(UUID.randomUUID() + "@mine.test"), PASSWORD).getAccountId().value();
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
        return json.readTree(r.body());
    }

    private String member(OrganizationId org, boolean administrator) {
        String a = account();
        employees.addEmployee(SETUP, org, new AccountId(a));
        if (administrator) administrators.assignAdministrator(SETUP, org, new AccountId(a));
        return a;
    }

    private String campaign(String org, String admin, String title) throws Exception {
        String body = """
                {"title":"%s","visibility":"PUBLIC","startDate":"%s","endDate":"%s",
                 "configuration":{"acceptedDonationTypes":["IN_KIND"]}}""".formatted(title, START, END);
        return ok(send("POST", "/api/v1/organizations/" + org + "/campaigns", admin, body), 201).get("campaignRef").asText();
    }

    private List<JsonNode> mine(String account) throws Exception {
        HttpResponse<String> r = send("GET", "/api/v1/me/campaigns", account, null);
        assertThat(r.headers().firstValue("Cache-Control")).hasValueSatisfying(v -> assertThat(v).contains("no-store"));
        return StreamSupport.stream(ok(r, 200).get("items").spliterator(), false).toList();
    }

    @Test
    void eachResponsible_seesOnlyTheirOwnActiveAssignments_withTheirRoleAndTheCampaignStatus() throws Exception {
        String platformEmail = UUID.randomUUID() + "@platform.test";
        accounts.createAccount(new Email(platformEmail), PASSWORD);
        String platform = bootstrap.bootstrap(platformEmail).value();
        String rep = account();
        OrganizationId org = organizations.createOrganization(SETUP, OrganizationType.FOUNDATION, new AccountId(rep), "Mías")
                .getOrganizationId();
        ok(send("POST", "/api/v1/platform/organizations/" + org.value() + "/verify", platform, null), 200);
        String admin = member(org, true);
        String admin2 = member(org, true);
        String employee = member(org, false);
        String idle = member(org, false);

        String first = campaign(org.value(), admin, "Primera");
        String second = campaign(org.value(), admin, "Segunda");
        ok(send("POST", "/api/v1/campaigns/" + first + "/employees", admin, "{\"employeeRef\":\"" + employee + "\"}"), 201);
        ok(send("POST", "/api/v1/campaigns/" + first + "/administrators", admin, "{\"administratorRef\":\"" + admin2 + "\"}"), 201);
        ok(send("POST", "/api/v1/campaigns/" + second + "/administrators", admin, "{\"administratorRef\":\"" + admin2 + "\"}"), 201);

        List<JsonNode> employeeSees = mine(employee);
        assertThat(employeeSees).hasSize(1);
        JsonNode item = employeeSees.get(0);
        assertThat(item.fieldNames()).toIterable().containsExactlyInAnyOrder("campaignRef", "publicCode", "title", "status",
                "actingRole", "assignedAt");
        assertThat(item.get("campaignRef").asText()).isEqualTo(first);
        assertThat(item.get("title").asText()).isEqualTo("Primera");
        assertThat(item.get("actingRole").asText()).isEqualTo("EMPLOYEE");
        assertThat(item.get("status").asText()).isEqualTo("OPEN");

        assertThat(mine(admin2)).extracting(n -> n.get("campaignRef").asText()).containsExactlyInAnyOrder(first, second);
        assertThat(mine(admin2)).allMatch(n -> n.get("actingRole").asText().equals("ADMINISTRATOR"));
        assertThat(mine(idle)).isEmpty();
        assertThat(mine(admin)).isEmpty();
        assertThat(mine(account())).isEmpty();
        assertThat(send("GET", "/api/v1/me/campaigns", null, null).statusCode()).isEqualTo(401);

        // retirado (DD-50), desaparece; cerrada, sigue con su estado
        ok(send("POST", "/api/v1/campaigns/" + first + "/responsibles/" + employee + "/remove", admin, null), 200);
        assertThat(mine(employee)).isEmpty();
        ok(send("POST", "/api/v1/campaigns/" + second + "/close", admin, null), 200);
        assertThat(mine(admin2)).filteredOn(n -> n.get("campaignRef").asText().equals(second))
                .singleElement().satisfies(n -> assertThat(n.get("status").asText()).isEqualTo("CLOSED"));

        // un parámetro no permite ver las de otro
        assertThat(ok(send("GET", "/api/v1/me/campaigns?accountId=" + admin2, idle, null), 200).get("items")).isEmpty();
    }
}
