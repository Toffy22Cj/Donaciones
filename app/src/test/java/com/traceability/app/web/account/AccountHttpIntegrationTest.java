package com.traceability.app.web.account;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.app.TraceabilityApplication;
import com.traceability.contracts.authentication.TokenIssuerPort;
import identity.application.service.AddEmployeeService;
import identity.application.service.CreateAccountService;
import identity.application.service.CreateOrganizationService;
import identity.application.service.DeactivateAccountService;
import identity.domain.model.AccountId;
import identity.domain.model.AuditActor;
import identity.domain.model.Email;
import identity.domain.model.Organization;
import identity.domain.model.OrganizationType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ficha L-FRONTS, A3 (M-26), contra Tomcat real con {@code identity} real: {@code GET /api/v1/account} devuelve el
 * correo de la propia cuenta, con o sin organización, sin que aparezca en ningún log. {@code GET /me} (ficha N1) no
 * cambia: lo cubre {@code P2EndpointsHttpIntegrationTest}.
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
class AccountHttpIntegrationTest {

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
    @Autowired private DeactivateAccountService deactivations;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();
    private static final AuditActor SETUP = new AuditActor.SystemAuditActor("l-fronts-a3-tests");

    private static String newEmail() {
        return UUID.randomUUID() + "@a3.test";
    }

    private String account(String email) {
        return accounts.createAccount(new Email(email), PASSWORD).getAccountId().value();
    }

    private HttpResponse<String> send(String method, String path, String authorization, String body) throws Exception {
        HttpRequest.Builder r = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (authorization != null) r.header("Authorization", authorization);
        if (body != null) r.header("Content-Type", "application/json");
        return http.send(r.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> readAccount(String accountId) throws Exception {
        return send("GET", "/api/v1/account", "Bearer " + tokens.issue(accountId), null);
    }

    private JsonNode me(String accountId) throws Exception {
        HttpResponse<String> r = send("GET", "/api/v1/me", "Bearer " + tokens.issue(accountId), null);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return json.readTree(r.body());
    }

    private void assertExactlyTheEmail(HttpResponse<String> r, String email) throws Exception {
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        assertThat(r.headers().firstValue("Cache-Control")).hasValue("no-store");
        assertThat(json.readTree(r.body())).isEqualTo(json.createObjectNode().put("email", email));
    }

    // --- 200: la propia cuenta, con y sin organización ---

    @Test
    void accountWithoutOrganization_donor_gets200_withExactlyItsEmail_andNoStore() throws Exception {
        String email = newEmail();
        String donor = account(email);
        assertThat(me(donor).has("organizationId")).as("la cuenta no tiene organización").isFalse();

        assertExactlyTheEmail(readAccount(donor), email);
    }

    @Test
    void accountWithOrganization_gets200_withExactlyItsEmail() throws Exception {
        String representativeEmail = newEmail();
        String representative = account(representativeEmail);
        Organization o = organizations.createOrganization(SETUP, OrganizationType.FOUNDATION,
                new AccountId(representative), "Organización A3");
        String employeeEmail = newEmail();
        String employee = account(employeeEmail);
        employees.addEmployee(SETUP, o.getOrganizationId(), new AccountId(employee));
        assertThat(me(representative).get("organizationId").asText()).isEqualTo(o.getOrganizationId().value());
        assertThat(me(employee).get("organizationId").asText()).isEqualTo(o.getOrganizationId().value());

        assertExactlyTheEmail(readAccount(representative), representativeEmail);
        assertExactlyTheEmail(readAccount(employee), employeeEmail);
    }

    @Test
    void theEmailIsTheNormalizedOneOfTheRegistration() throws Exception {
        String local = "Ana.Prueba." + UUID.randomUUID().toString().substring(0, 8);
        HttpResponse<String> registered = send("POST", "/api/v1/auth/register", null,
                "{\"email\":\"  " + local + "@Ejemplo.ORG \",\"password\":\"" + PASSWORD + "\"}");
        assertThat(registered.statusCode()).as(registered.body()).isEqualTo(201);
        String accountId = json.readTree(registered.body()).get("accountId").asText();

        assertExactlyTheEmail(readAccount(accountId), local.toLowerCase(java.util.Locale.ROOT) + "@ejemplo.org");
    }

    @Test
    void eachAccountGetsOnlyItsOwnEmail() throws Exception {
        String emailA = newEmail();
        String emailB = newEmail();
        String a = account(emailA);
        String b = account(emailB);

        HttpResponse<String> readA = readAccount(a);
        HttpResponse<String> readB = readAccount(b);

        assertExactlyTheEmail(readA, emailA);
        assertExactlyTheEmail(readB, emailB);
        assertThat(readA.body()).doesNotContain(emailB);
        assertThat(readB.body()).doesNotContain(emailA);
    }

    // --- 401 ---

    @Test
    void withoutJwt_withAnInvalidJwt_orWithAnInactiveAccount_is401() throws Exception {
        String email = newEmail();
        String inactive = account(email);
        deactivations.deactivateAccount(SETUP, new AccountId(inactive));

        HttpResponse<String> noJwt = send("GET", "/api/v1/account", null, null);
        HttpResponse<String> invalidJwt = send("GET", "/api/v1/account", "Bearer not-a-jwt", null);
        HttpResponse<String> inactiveAccount = readAccount(inactive);

        assertThat(noJwt.statusCode()).isEqualTo(401);
        assertThat(invalidJwt.statusCode()).isEqualTo(401);
        assertThat(inactiveAccount.statusCode()).isEqualTo(401);
        assertThat(inactiveAccount.body()).doesNotContain(email);
    }

    // --- el correo no aparece en ningún log ---

    @Test
    void theEmailNeverReachesTheLogs(CapturedOutput output) throws Exception {
        String email = newEmail();
        String accountId = account(email);
        // Solo cuenta lo que se registra a partir de aquí: el alta de la cuenta no es de esta ficha.
        int from = output.getAll().length();

        assertExactlyTheEmail(readAccount(accountId), email);
        assertThat(send("GET", "/api/v1/account", "Bearer not-a-jwt", null).statusCode()).isEqualTo(401);

        assertThat(output.getAll().substring(from)).doesNotContain(email);
    }
}
