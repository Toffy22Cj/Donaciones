package com.traceability.app.web.organization;

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
import identity.domain.model.AccountId;
import identity.domain.model.AuditActor;
import identity.domain.model.Email;
import identity.domain.model.OrganizationId;
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
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ficha S-18 (§7), contra Tomcat real con {@code identity} real: {@code GET /api/v1/organizations/{organizationId}},
 * el estado de verificación de la propia organización para su {@code REPRESENTATIVE} o {@code ADMINISTRATOR}.
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
class OrganizationVerificationStatusHttpIntegrationTest {

    static final String PASSWORD = "Pass123!Pass123!";
    /** ULID bien formado que no corresponde a ninguna organización. */
    static final String UNKNOWN_ORGANIZATION = "01JZZZZZZZZZZZZZZZZZZZZZZZ";

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
    private static final AuditActor SETUP = new AuditActor.SystemAuditActor("s18-tests");
    private static String platformAdmin;

    private String account() {
        return accounts.createAccount(new Email(UUID.randomUUID() + "@s18.test"), PASSWORD).getAccountId().value();
    }

    @BeforeEach
    void world() {
        if (platformAdmin != null) return;
        String email = UUID.randomUUID() + "@platform.test";
        accounts.createAccount(new Email(email), PASSWORD);
        platformAdmin = bootstrap.bootstrap(email).value();
    }

    private HttpResponse<String> send(String method, String path, String account, String body) throws Exception {
        return sendWith(method, path, account == null ? null : "Bearer " + tokens.issue(account), body);
    }

    private HttpResponse<String> sendWith(String method, String path, String authorization, String body) throws Exception {
        HttpRequest.Builder r = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (authorization != null) r.header("Authorization", authorization);
        if (body != null) r.header("Content-Type", "application/json");
        return http.send(r.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode ok(HttpResponse<String> r, int status) throws Exception {
        assertThat(r.statusCode()).as(r.body()).isEqualTo(status);
        return r.body().isEmpty() ? null : json.readTree(r.body());
    }

    /** Organización creada por HTTP; devuelve su id. */
    private String createdBy(String representative, String name) throws Exception {
        return ok(send("POST", "/api/v1/organizations", representative,
                "{\"type\":\"FOUNDATION\",\"name\":\"" + name + "\",\"acceptedOrganizationTermsVersion\":\"v1-2026-10\"}"), 201).get("organizationId").asText();
    }

    private HttpResponse<String> read(String organizationId, String account) throws Exception {
        return send("GET", "/api/v1/organizations/" + organizationId, account, null);
    }

    private static Set<String> fields(JsonNode node) {
        Set<String> names = new TreeSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    // --- §7.1 a §7.3: el estado tras cada decisión de la plataforma ---

    @Test
    void representative_readsItsNewOrganization_pendingVerification_withoutInformationRequest() throws Exception {
        String representative = account();
        String org = createdBy(representative, "Recién creada");

        JsonNode body = ok(read(org, representative), 200);

        assertThat(body.get("organizationId").asText()).isEqualTo(org);
        assertThat(body.get("name").asText()).isEqualTo("Recién creada");
        assertThat(body.get("type").asText()).isEqualTo("FOUNDATION");
        assertThat(body.get("verificationStatus").asText()).isEqualTo("PENDING_VERIFICATION");
        // la clave no existe; no basta con que valga null
        assertThat(body.has("informationRequest")).isFalse();
    }

    @Test
    void afterRequestInformation_showsTheExactMessage_andAfterVerify_theMessageIsGone() throws Exception {
        String representative = account();
        String org = createdBy(representative, "Con petición");
        ok(send("POST", "/api/v1/platform/organizations/" + org + "/request-information", platformAdmin,
                "{\"message\":\"Adjunte el RUT y el acta de constitución\"}"), 200);

        JsonNode needsInfo = ok(read(org, representative), 200);
        assertThat(needsInfo.get("verificationStatus").asText()).isEqualTo("NEEDS_MORE_INFORMATION");
        assertThat(needsInfo.get("informationRequest").asText()).isEqualTo("Adjunte el RUT y el acta de constitución");

        ok(send("POST", "/api/v1/platform/organizations/" + org + "/verify", platformAdmin, null), 200);

        JsonNode verified = ok(read(org, representative), 200);
        assertThat(verified.get("verificationStatus").asText()).isEqualTo("VERIFIED");
        assertThat(verified.has("informationRequest")).isFalse();
    }

    @Test
    void afterReject_isRejected_withoutInformationRequest() throws Exception {
        String representative = account();
        String org = createdBy(representative, "Rechazada");
        ok(send("POST", "/api/v1/platform/organizations/" + org + "/reject", platformAdmin, null), 200);

        JsonNode rejected = ok(read(org, representative), 200);
        assertThat(rejected.get("verificationStatus").asText()).isEqualTo("REJECTED");
        assertThat(rejected.has("informationRequest")).isFalse();
    }

    // --- §7.4 y §7.5: quién lo lee ---

    @Test
    void administratorOfTheOrganization_readsIt() throws Exception {
        String representative = account();
        String org = createdBy(representative, "Con administrador");
        String administrator = account();
        employees.addEmployee(SETUP, new OrganizationId(org), new AccountId(administrator));
        administrators.assignAdministrator(SETUP, new OrganizationId(org), new AccountId(administrator));

        JsonNode body = ok(read(org, administrator), 200);
        assertThat(body.get("organizationId").asText()).isEqualTo(org);
        assertThat(body.get("verificationStatus").asText()).isEqualTo("PENDING_VERIFICATION");
    }

    @Test
    void everyoneElse_getsTheSame403_withoutDistinguishingTheCase() throws Exception {
        String representative = account();
        String org = createdBy(representative, "Ajena a los demás");
        String employee = account();
        employees.addEmployee(SETUP, new OrganizationId(org), new AccountId(employee));
        String otherRepresentative = account();
        createdBy(otherRepresentative, "Otra organización");
        String loner = account();

        // ApiExceptionHandler arma el cuerpo sin `instance`: todos los casos dan el mismo cuerpo, byte a byte
        List<HttpResponse<String>> all = List.of(
                read(org, employee),
                read(org, otherRepresentative),
                read(org, loner),
                read(org, platformAdmin),
                read(UNKNOWN_ORGANIZATION, representative),
                read("no-es-un-ulid", representative));

        String reference = all.get(0).body();
        for (HttpResponse<String> r : all) {
            assertThat(r.statusCode()).as(r.body()).isEqualTo(403);
            JsonNode body = json.readTree(r.body());
            for (String leaked : List.of("organizationId", "name", "verificationStatus", "informationRequest")) {
                assertThat(body.has(leaked)).as(leaked).isFalse();
            }
            // `type` es el campo estándar del ProblemDetail (RFC 7807), nunca el tipo de la organización
            assertThat(body.get("type").asText()).isNotIn("FOUNDATION", "COMPANY");
            assertThat(r.body()).isEqualTo(reference);
        }
    }

    // --- §7.6: autenticación ---

    @Test
    void withoutJwt_withAnInvalidJwt_orWithAnInactiveAccount_is401() throws Exception {
        String representative = account();
        String org = createdBy(representative, "Para el 401");
        String inactive = account();
        employees.addEmployee(SETUP, new OrganizationId(org), new AccountId(inactive));
        administrators.assignAdministrator(SETUP, new OrganizationId(org), new AccountId(inactive));
        String inactiveToken = "Bearer " + tokens.issue(inactive);
        deactivations.deactivateAccount(SETUP, new AccountId(inactive));

        String path = "/api/v1/organizations/" + org;
        for (HttpResponse<String> r : List.of(
                sendWith("GET", path, null, null),
                sendWith("GET", path, "Bearer esto-no-es-un-jwt", null),
                sendWith("GET", path, inactiveToken, null))) {
            assertThat(r.statusCode()).as(r.body()).isEqualTo(401);
            assertThat(r.body()).doesNotContain("verificationStatus").doesNotContain(org);
        }
    }

    // --- §7.7 y §7.8: forma de la respuesta ---

    @Test
    void theResponse_isNoStore() throws Exception {
        String representative = account();
        String org = createdBy(representative, "Sin caché");

        HttpResponse<String> r = read(org, representative);
        ok(r, 200);
        assertThat(r.headers().firstValue("Cache-Control")).hasValueSatisfying(v -> assertThat(v).contains("no-store"));
    }

    @Test
    void theResponse_hasExactlyTheFieldsOfTheContract() throws Exception {
        String representative = account();
        String org = createdBy(representative, "Campos exactos");
        assertThat(fields(ok(read(org, representative), 200)))
                .containsExactly("name", "organizationId", "type", "verificationStatus");

        ok(send("POST", "/api/v1/platform/organizations/" + org + "/request-information", platformAdmin,
                "{\"message\":\"Falta un documento\"}"), 200);
        JsonNode needsInfo = ok(read(org, representative), 200);
        assertThat(fields(needsInfo))
                .containsExactly("informationRequest", "name", "organizationId", "type", "verificationStatus");
        for (String excluded : List.of("members", "email", "accountId", "roles")) {
            assertThat(needsInfo.has(excluded)).as(excluded).isFalse();
        }

        // sin nombre (el dominio lo admite, DD-04): la clave se omite, nunca llega como null
        String unnamedRepresentative = account();
        String unnamed = organizations.createOrganization(SETUP, OrganizationType.COMPANY,
                new AccountId(unnamedRepresentative)).getOrganizationId().value();
        JsonNode body = ok(read(unnamed, unnamedRepresentative), 200);
        assertThat(fields(body)).containsExactly("organizationId", "type", "verificationStatus");
        assertThat(body.get("type").asText()).isEqualTo("COMPANY");
    }
}
