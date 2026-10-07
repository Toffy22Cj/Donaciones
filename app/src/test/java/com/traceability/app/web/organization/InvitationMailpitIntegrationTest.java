package com.traceability.app.web.organization;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.app.TraceabilityApplication;
import com.traceability.contracts.authentication.TokenIssuerPort;
import identity.application.service.AddEmployeeService;
import identity.application.service.AssignAdministratorService;
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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-049, definición de hecho 12: el correo de invitación sale por SMTP real (Mailpit, el mismo de
 * {@code scripts/demo}) con el nombre de la organización, el rol, el enlace con el token en el fragmento y la
 * caducidad, y sin datos de quien invita.
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
class InvitationMailpitIntegrationTest {

    @Container
    static MongoDBContainer mongo = new MongoDBContainer(DockerImageName.parse("mongo:6.0")).withCommand("--replSet", "rs0");

    @Container
    static GenericContainer<?> mailpit = new GenericContainer<>(DockerImageName.parse("axllent/mailpit:v1.21"))
            .withExposedPorts(1025, 8025).waitingFor(Wait.forHttp("/api/v1/messages").forPort(8025));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongo::getReplicaSetUrl);
        registry.add("spring.mail.host", mailpit::getHost);
        registry.add("spring.mail.port", () -> mailpit.getMappedPort(1025));
    }

    @LocalServerPort private int port;
    @Autowired private TokenIssuerPort tokens;
    @Autowired private CreateAccountService accounts;
    @Autowired private CreateOrganizationService organizations;
    @Autowired private AddEmployeeService employees;
    @Autowired private AssignAdministratorService administrators;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    private JsonNode mailpitGet(String path) throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("http://" + mailpit.getHost() + ":"
                + mailpit.getMappedPort(8025) + path)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).isEqualTo(200);
        return json.readTree(r.body());
    }

    @Test
    void theInvitationMail_goesThroughRealSmtp_withTheLinkInTheFragment_andNoThirdPartyData() throws Exception {
        AuditActor setup = new AuditActor.SystemAuditActor("mailpit-test");
        String adminEmail = "admin-" + UUID.randomUUID().toString().substring(0, 8) + "@mailpit.test";
        String rep = accounts.createAccount(new Email("rep-" + UUID.randomUUID().toString().substring(0, 8) + "@mailpit.test"),
                "Pass123!Pass123!").getAccountId().value();
        OrganizationId org = organizations.createOrganization(setup, OrganizationType.FOUNDATION, new AccountId(rep),
                "Fundación Correo Real").getOrganizationId();
        String admin = accounts.createAccount(new Email(adminEmail), "Pass123!Pass123!").getAccountId().value();
        employees.addEmployee(setup, org, new AccountId(admin));
        administrators.assignAdministrator(setup, org, new AccountId(admin));

        String invitee = "invitada-" + UUID.randomUUID().toString().substring(0, 8) + "@mailpit.test";
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                        + "/api/v1/organizations/" + org.value() + "/invitations"))
                .header("Authorization", "Bearer " + tokens.issue(admin)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"email\":\"" + invitee + "\",\"role\":\"ADMINISTRATOR\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as(r.body()).isEqualTo(202);

        JsonNode messages = mailpitGet("/api/v1/messages");
        assertThat(messages.get("messages_count").asInt()).isEqualTo(1);
        JsonNode summary = messages.get("messages").get(0);
        assertThat(summary.get("To").get(0).get("Address").asText()).isEqualTo(invitee);
        assertThat(summary.get("Subject").asText()).isEqualTo("Invitación a Fundación Correo Real en PaxFide");
        JsonNode message = mailpitGet("/api/v1/message/" + summary.get("ID").asText());
        String text = message.get("Text").asText();
        assertThat(text).containsPattern(Pattern.compile(
                Pattern.quote("https://web.tests.paxfide.local/invitaciones#token=") + "[A-Za-z0-9_-]{43}"));
        assertThat(text).contains("Fundación Correo Real").contains("administrador").contains("caduca el");
        assertThat(text).doesNotContain(adminEmail).doesNotContain(admin).doesNotContain(rep).doesNotContain(org.value())
                .doesNotContain("?token");
        assertThat(message.get("From").get("Address").asText()).isEqualTo("no-reply@tests.paxfide.local");
    }
}
