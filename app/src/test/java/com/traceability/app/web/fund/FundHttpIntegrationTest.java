package com.traceability.app.web.fund;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.app.TraceabilityApplication;
import com.traceability.contracts.authentication.TokenIssuerPort;
import com.traceability.contracts.authorization.AuthorizationPrincipal;
import com.traceability.contracts.authorization.AuthorizationRole;
import com.traceability.contracts.authorization.IdentityPrincipalPort;
import com.traceability.core.application.command.FundCommandService;
import com.traceability.core.application.port.out.EventStorePort;
import com.traceability.core.domain.event.SystemActor;
import com.traceability.core.domain.fund.AllocationIds;
import com.traceability.core.domain.fund.OrganizationRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Plan P1.1 (Camino A por HTTP), tests 1–3, contra Tomcat real con {@code core} real: fondos de la organización,
 * asignación y confirmación. Cierra H-B6C-1 y H-B6D-1.
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
    "saga.outbox.delay=200"
})
class FundHttpIntegrationTest {

    private static final String ORG = "org-p11";
    private static final String OTHER_ORG = "org-p11-other";
    private static final String ADMIN = "acc-admin";
    private static final String EMPLOYEE = "acc-employee";
    private static final String OTHER_ADMIN = "acc-other-admin";
    private static final String REPRESENTATIVE_ONLY = "acc-representative";
    private static final SystemActor SYSTEM = new SystemActor("p11-tests");

    @Container
    static MongoDBContainer mongo = new MongoDBContainer(DockerImageName.parse("mongo:6.0")).withCommand("--replSet", "rs0");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongo::getReplicaSetUrl);
    }

    @LocalServerPort private int port;
    @Autowired private TokenIssuerPort tokens;
    @Autowired private FundCommandService funds;
    @Autowired private EventStorePort eventStore;
    @MockitoBean private IdentityPrincipalPort identityPrincipalPort;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void principals() {
        Map<String, AuthorizationPrincipal> principals = Map.of(
                ADMIN, new AuthorizationPrincipal(ADMIN, ORG, Set.of(AuthorizationRole.ADMINISTRATOR), null),
                EMPLOYEE, new AuthorizationPrincipal(EMPLOYEE, ORG, Set.of(AuthorizationRole.EMPLOYEE), null),
                OTHER_ADMIN, new AuthorizationPrincipal(OTHER_ADMIN, OTHER_ORG, Set.of(AuthorizationRole.ADMINISTRATOR), null),
                REPRESENTATIVE_ONLY, new AuthorizationPrincipal(REPRESENTATIVE_ONLY, ORG, Set.of(AuthorizationRole.REPRESENTATIVE), null));
        when(identityPrincipalPort.resolvePrincipal(anyString())).thenAnswer(inv -> principals.get(inv.<String>getArgument(0)));
    }

    private String fund(String org, long amount, String campaignRef) {
        String fundId = UUID.randomUUID().toString();
        funds.clearFundsGenesis(UUID.randomUUID().toString(), fundId, new OrganizationRef(org), campaignRef,
                "anon:secret-donor", "COP", amount, "SRC", SYSTEM);
        return fundId;
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

    private HttpResponse<String> allocate(String account, String fundId, String commandId, String amount) throws Exception {
        return send("POST", "/api/v1/funds/" + fundId + "/allocations", account, commandId, "{\"amount\":\"" + amount + "\"}");
    }

    private JsonNode fundsOf(String account, String org) throws Exception {
        HttpResponse<String> r = send("GET", "/api/v1/organizations/" + org + "/funds", account, null, null);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return json.readTree(r.body()).get("items");
    }

    private static JsonNode byId(JsonNode items, String fundId) {
        return StreamSupport.stream(items.spliterator(), false).filter(i -> i.get("fundId").asText().equals(fundId))
                .findFirst().orElse(null);
    }

    @Test
    void theOrganizationFunds_listOnlyItsOwnFunds_withoutDonorData() throws Exception {
        String mine = fund(ORG, 1000, "camp-1");
        String theirs = fund(OTHER_ORG, 500, null);

        JsonNode items = fundsOf(EMPLOYEE, ORG);

        JsonNode f = byId(items, mine);
        assertThat(f).isNotNull();
        assertThat(byId(items, theirs)).isNull();
        assertThat(f.fieldNames()).toIterable().containsExactlyInAnyOrder("fundId", "campaignRef", "currency",
                "clearedAmount", "availableAmount", "allocations");
        assertThat(f.get("clearedAmount").asText()).isEqualTo("1000");
        assertThat(f.get("availableAmount").asText()).isEqualTo("1000");
        assertThat(items.toString()).doesNotContain("secret-donor").doesNotContain("donorRef");
    }

    @Test
    void anotherOrganization_andAnUnknownOne_giveTheSame403() throws Exception {
        HttpResponse<String> foreign = send("GET", "/api/v1/organizations/" + ORG + "/funds", OTHER_ADMIN, null, null);
        HttpResponse<String> unknown = send("GET", "/api/v1/organizations/no-such-org/funds", ADMIN, null, null);

        assertThat(foreign.statusCode()).isEqualTo(403);
        assertThat(unknown.statusCode()).isEqualTo(403);
        assertThat(unknown.body()).isEqualTo(foreign.body());
        // DD-31: solo ADMINISTRATOR o EMPLOYEE de la organización
        HttpResponse<String> noRole = send("GET", "/api/v1/organizations/" + ORG + "/funds", REPRESENTATIVE_ONLY, null, null);
        assertThat(noRole.statusCode()).isEqualTo(403);
        assertThat(noRole.body()).isEqualTo(foreign.body());
    }

    @Test
    void requestAllocation_isDeterministicAndIdempotent_andReducesTheAvailableAmount() throws Exception {
        String fundId = fund(ORG, 1000, null);
        String commandId = UUID.randomUUID().toString();

        HttpResponse<String> first = allocate(ADMIN, fundId, commandId, "300");
        HttpResponse<String> again = allocate(ADMIN, fundId, commandId, "300");

        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(json.readTree(first.body())).isEqualTo(json.readTree(
                "{\"allocationId\":\"" + AllocationIds.of(fundId, commandId) + "\",\"status\":\"REQUESTED\"}"));
        assertThat(again.statusCode()).isEqualTo(201);
        assertThat(again.body()).isEqualTo(first.body());
        assertThat(eventStore.loadStream(fundId)).hasSize(2);
        JsonNode f = byId(fundsOf(ADMIN, ORG), fundId);
        assertThat(f.get("availableAmount").asText()).isEqualTo("700");
        assertThat(f.get("allocations").get(0).get("status").asText()).isEqualTo("REQUESTED");
    }

    @Test
    void requestAllocation_rejections() throws Exception {
        String fundId = fund(ORG, 100, null);
        String foreignFund = fund(OTHER_ORG, 100, null);
        String reused = UUID.randomUUID().toString();
        allocate(ADMIN, fundId, reused, "10");

        assertThat(allocate(ADMIN, fundId, UUID.randomUUID().toString(), "101").statusCode()).isEqualTo(409);
        assertThat(allocate(EMPLOYEE, fundId, UUID.randomUUID().toString(), "10").statusCode()).isEqualTo(403);
        assertThat(send("POST", "/api/v1/funds/" + fundId + "/allocations/x/confirm", ADMIN, reused, null).statusCode())
                .isEqualTo(409);
        HttpResponse<String> foreign = allocate(ADMIN, foreignFund, UUID.randomUUID().toString(), "10");
        HttpResponse<String> missing = allocate(ADMIN, UUID.randomUUID().toString(), UUID.randomUUID().toString(), "10");
        assertThat(foreign.statusCode()).isEqualTo(403);
        assertThat(missing.statusCode()).isEqualTo(403);
        assertThat(missing.body()).isEqualTo(foreign.body());
        for (String amount : List.of("0", "-5", "1.5", "abc")) {
            assertThat(allocate(ADMIN, fundId, UUID.randomUUID().toString(), amount).statusCode()).as(amount).isEqualTo(400);
        }
        assertThat(eventStore.loadStream(fundId)).hasSize(2);
    }

    @Test
    void confirmAllocation_confirmsOnce() throws Exception {
        String fundId = fund(ORG, 1000, null);
        String allocationId = json.readTree(allocate(ADMIN, fundId, UUID.randomUUID().toString(), "400").body())
                .get("allocationId").asText();
        String path = "/api/v1/funds/" + fundId + "/allocations/" + allocationId + "/confirm";

        HttpResponse<String> confirmed = send("POST", path, ADMIN, UUID.randomUUID().toString(), null);
        HttpResponse<String> again = send("POST", path, ADMIN, UUID.randomUUID().toString(), null);

        assertThat(confirmed.statusCode()).isEqualTo(200);
        assertThat(json.readTree(confirmed.body())).isEqualTo(json.readTree(
                "{\"allocationId\":\"" + allocationId + "\",\"status\":\"CONFIRMED\"}"));
        assertThat(again.statusCode()).isEqualTo(200);
        assertThat(eventStore.loadStream(fundId)).hasSize(3);
        assertThat(byId(fundsOf(ADMIN, ORG), fundId).get("allocations").get(0).get("status").asText()).isEqualTo("CONFIRMED");
        assertThat(send("POST", "/api/v1/funds/" + fundId + "/allocations/" + UUID.randomUUID() + "/confirm", ADMIN,
                UUID.randomUUID().toString(), null).statusCode()).isEqualTo(409);
    }
}
