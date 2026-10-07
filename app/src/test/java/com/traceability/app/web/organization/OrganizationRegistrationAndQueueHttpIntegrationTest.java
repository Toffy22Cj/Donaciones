package com.traceability.app.web.organization;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.app.TraceabilityApplication;
import com.traceability.app.web.campaign.DiscoveryCursorCodec;
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
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Autorización (3) de Carlos, §3.1, contra Tomcat real con {@code identity} real: crear organización (R9) y la cola de
 * verificación del administrador de plataforma con cursor opaco (T-35).
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
class OrganizationRegistrationAndQueueHttpIntegrationTest {

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
    @Autowired private DiscoveryCursorCodec cursors;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();
    private static final AuditActor SETUP = new AuditActor.SystemAuditActor("org-tests");
    private static String platformAdmin, orgAdmin;

    private String account() {
        return accounts.createAccount(new Email(UUID.randomUUID() + "@org.test"), PASSWORD).getAccountId().value();
    }

    @BeforeEach
    void world() {
        if (platformAdmin != null) return;
        String email = UUID.randomUUID() + "@platform.test";
        accounts.createAccount(new Email(email), PASSWORD);
        platformAdmin = bootstrap.bootstrap(email).value();
        String rep = account();
        OrganizationId org = organizations.createOrganization(SETUP, OrganizationType.FOUNDATION, new AccountId(rep), "Con admin")
                .getOrganizationId();
        orgAdmin = account();
        employees.addEmployee(SETUP, org, new AccountId(orgAdmin));
        administrators.assignAdministrator(SETUP, org, new AccountId(orgAdmin));
    }

    private HttpResponse<String> send(String method, String path, String account, String body) throws Exception {
        HttpRequest.Builder r = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (account != null) r.header("Authorization", "Bearer " + tokens.issue(account));
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

    private String createdBy(String account, String name) throws Exception {
        return ok(send("POST", "/api/v1/organizations", account,
                "{\"type\":\"FOUNDATION\",\"name\":\"" + name + "\"}"), 201).get("organizationId").asText();
    }

    private static String q(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }

    // --- crear organización (R9) ---

    @Test
    void anyRegisteredUser_createsAnOrganization_asItsRepresentative_pendingVerification() throws Exception {
        String user = account();
        JsonNode created = ok(send("POST", "/api/v1/organizations", user,
                "{\"type\":\"COMPANY\",\"name\":\"  Empresa Solidaria  \"}"), 201);
        assertThat(created.get("verificationStatus").asText()).isEqualTo("PENDING_VERIFICATION");
        assertThat(created.fieldNames()).toIterable().containsExactlyInAnyOrder("organizationId", "verificationStatus");

        JsonNode me = ok(send("GET", "/api/v1/me", user, null), 200);
        assertThat(me.get("organizationId").asText()).isEqualTo(created.get("organizationId").asText());
        assertThat(me.get("roles")).extracting(JsonNode::asText).containsExactly("REPRESENTATIVE");

        // ya pertenece a una organización (ADR-026, invariante 1): 409, también para un administrador de otra
        HttpResponse<String> again = send("POST", "/api/v1/organizations", user, "{\"type\":\"COMPANY\",\"name\":\"Otra\"}");
        assertThat(again.statusCode()).isEqualTo(409);
        assertThat(title(again)).isEqualTo("AccountAlreadyBelongsToOrganization");
        assertThat(send("POST", "/api/v1/organizations", orgAdmin, "{\"type\":\"COMPANY\",\"name\":\"Otra\"}").statusCode())
                .isEqualTo(409);
    }

    @Test
    void createOrganization_validatesTypeAndName_andRequiresAuthentication() throws Exception {
        String user = account();
        assertThat(send("POST", "/api/v1/organizations", null, "{\"type\":\"FOUNDATION\",\"name\":\"X\"}").statusCode())
                .isEqualTo(401);
        for (String body : List.of("{\"name\":\"X\"}", "{\"type\":\"NGO\",\"name\":\"X\"}",
                "{\"type\":\"foundation\",\"name\":\"X\"}", "{\"type\":\"FOUNDATION\"}",
                "{\"type\":\"FOUNDATION\",\"name\":\"   \"}",
                "{\"type\":\"FOUNDATION\",\"name\":\"" + "n".repeat(201) + "\"}")) {
            HttpResponse<String> r = send("POST", "/api/v1/organizations", user, body);
            assertThat(r.statusCode()).as(body).isEqualTo(400);
            assertThat(r.body()).doesNotContain("nnnnnnnnnn");
        }
        assertThat(send("POST", "/api/v1/organizations", user, null).statusCode()).isEqualTo(400);
        // nada de lo anterior la incorporó a una organización
        assertThat(ok(send("GET", "/api/v1/me", user, null), 200).has("organizationId")).isFalse();
        ok(send("POST", "/api/v1/organizations", user, "{\"type\":\"FOUNDATION\",\"name\":\"" + "n".repeat(200) + "\"}"), 201);
    }

    // --- cola de verificación ---

    @Test
    void theQueue_listsPendingOrganizations_page_by_page_withAnOpaqueCursor_andWithoutMembers() throws Exception {
        List<String> created = new ArrayList<>();
        for (int i = 0; i < 23; i++) created.add(createdBy(account(), "Cola " + i));
        String verified = createdBy(account(), "Verificada");
        ok(send("POST", "/api/v1/platform/organizations/" + verified + "/verify", platformAdmin, null), 200);

        List<String> seen = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            HttpResponse<String> r = send("GET", "/api/v1/platform/organizations"
                    + (cursor == null ? "" : "?cursor=" + q(cursor)), platformAdmin, null);
            JsonNode page = ok(r, 200);
            assertThat(r.headers().firstValue("Cache-Control")).hasValueSatisfying(v -> assertThat(v).contains("no-store"));
            assertThat(page.get("items").size()).isLessThanOrEqualTo(20);
            for (JsonNode item : page.get("items")) {
                assertThat(item.fieldNames()).toIterable().isSubsetOf("organizationId", "name", "type",
                        "verificationStatus", "informationRequest");
                seen.add(item.get("organizationId").asText());
            }
            cursor = page.has("nextCursor") ? page.get("nextCursor").asText() : null;
            if (cursor != null) {
                // opaco: no contiene el id en claro ni en Base64
                assertThat(cursor).doesNotContain(seen.get(seen.size() - 1));
                assertThat(new String(java.util.Base64.getUrlDecoder().decode(cursor), StandardCharsets.ISO_8859_1))
                        .doesNotContain(seen.get(seen.size() - 1));
            }
            pages++;
        } while (cursor != null && pages < 10);

        assertThat(pages).isGreaterThanOrEqualTo(2);
        assertThat(seen).doesNotHaveDuplicates().containsAll(created).doesNotContain(verified);
        assertThat(seen).isSortedAccordingTo(String::compareTo);
    }

    @Test
    void theQueue_filtersByPendingStatus_andShowsThePlatformsOwnRequest() throws Exception {
        String needsInfo = createdBy(account(), "Falta info");
        ok(send("POST", "/api/v1/platform/organizations/" + needsInfo + "/request-information", platformAdmin,
                "{\"message\":\"Adjunte el RUT\"}"), 200);
        String pending = createdBy(account(), "Pendiente");

        List<String> onlyInfo = new ArrayList<>();
        String cursor = null;
        do {
            JsonNode page = ok(send("GET", "/api/v1/platform/organizations?status=NEEDS_MORE_INFORMATION"
                    + (cursor == null ? "" : "&cursor=" + q(cursor)), platformAdmin, null), 200);
            for (JsonNode item : page.get("items")) {
                assertThat(item.get("verificationStatus").asText()).isEqualTo("NEEDS_MORE_INFORMATION");
                onlyInfo.add(item.get("organizationId").asText());
                if (item.get("organizationId").asText().equals(needsInfo)) {
                    assertThat(item.get("informationRequest").asText()).isEqualTo("Adjunte el RUT");
                    assertThat(item.get("name").asText()).isEqualTo("Falta info");
                    assertThat(item.get("type").asText()).isEqualTo("FOUNDATION");
                }
            }
            cursor = page.has("nextCursor") ? page.get("nextCursor").asText() : null;
        } while (cursor != null);
        assertThat(onlyInfo).contains(needsInfo).doesNotContain(pending);

        for (String status : List.of("VERIFIED", "REJECTED", "pending", "")) {
            assertThat(send("GET", "/api/v1/platform/organizations?status=" + status, platformAdmin, null).statusCode())
                    .as(status).isEqualTo(400);
        }
    }

    @Test
    void onlyThePlatformAdministrator_readsTheQueue_everyoneElseGetsTheSame403() throws Exception {
        String representative = account();
        createdBy(representative, "Ajena");
        HttpResponse<String> asOrgAdmin = send("GET", "/api/v1/platform/organizations", orgAdmin, null);
        HttpResponse<String> asRepresentative = send("GET", "/api/v1/platform/organizations", representative, null);
        HttpResponse<String> asLoner = send("GET", "/api/v1/platform/organizations?status=VERIFIED", account(), null);
        // autoriza antes de validar: un estado o un cursor inválidos no cambian el 403
        HttpResponse<String> badCursor = send("GET", "/api/v1/platform/organizations?cursor=abc", orgAdmin, null);
        for (HttpResponse<String> r : List.of(asOrgAdmin, asRepresentative, asLoner, badCursor)) {
            assertThat(r.statusCode()).isEqualTo(403);
            assertThat(json.readTree(r.body()).get("title")).isEqualTo(json.readTree(asOrgAdmin.body()).get("title"));
        }
        assertThat(send("GET", "/api/v1/platform/organizations", null, null).statusCode()).isEqualTo(401);
    }

    @Test
    void anAlteredOrForeignCursor_is400_andTheCursorsOfTheQueueAndOfTheDiscoveryAreNotInterchangeable() throws Exception {
        for (String bad : List.of("abc", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", cursors.encode("01ARZ3NDEKTSV4RRFFQ69G5FAV"),
                cursors.encode("verification-queue:not-a-ulid"))) {
            assertThat(send("GET", "/api/v1/platform/organizations?cursor=" + q(bad), platformAdmin, null).statusCode())
                    .as(bad).isEqualTo(400);
        }
        // un cursor válido de la cola, alterado en un carácter
        String valid = cursors.encode("verification-queue:" + "01ARZ3NDEKTSV4RRFFQ69G5FAV");
        assertThat(send("GET", "/api/v1/platform/organizations?cursor=" + q(valid), platformAdmin, null).statusCode())
                .isEqualTo(200);
        char last = valid.charAt(valid.length() - 2);
        String altered = valid.substring(0, valid.length() - 2) + (last == 'A' ? 'B' : 'A') + valid.charAt(valid.length() - 1);
        assertThat(send("GET", "/api/v1/platform/organizations?cursor=" + q(altered), platformAdmin, null).statusCode())
                .isEqualTo(400);
        // el cursor de la cola no vale en el descubrimiento público
        assertThat(send("GET", "/api/v1/public/campaigns?cursor=" + q(valid), null, null).statusCode()).isEqualTo(400);
    }
}
