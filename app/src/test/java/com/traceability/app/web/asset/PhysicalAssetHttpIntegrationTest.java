package com.traceability.app.web.asset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.app.TraceabilityApplication;
import com.traceability.contracts.authentication.TokenIssuerPort;
import com.traceability.contracts.authorization.AuthorizationPrincipal;
import com.traceability.contracts.authorization.AuthorizationRole;
import com.traceability.contracts.authorization.IdentityPrincipalPort;
import com.traceability.core.application.command.FundCommandService;
import com.traceability.core.application.port.out.EventStorePort;
import com.traceability.core.domain.event.DomainEvent;
import com.traceability.core.domain.event.SystemActor;
import com.traceability.core.domain.fund.OrganizationRef;
import com.traceability.core.domain.physicalasset.payloads.AssetRegisteredV3Payload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
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
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Plan B6-c, tests 5–8, contra Tomcat real ({@code RANDOM_PORT}) con el filtro JWT, {@code core} y la saga reales:
 * el recorrido de los pasos 4, 4B y 5 del golden path por HTTP (criterios 7, 8 y 15–17), la idempotencia por
 * {@code Command-Id}, la respuesta uniforme ante "otra organización" e "inexistente" y el {@code donorRef} del servidor.
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
class PhysicalAssetHttpIntegrationTest {

    private static final String ORG = "org-b6c";
    private static final String OTHER_ORG = "org-b6c-other";
    private static final String EMPLOYEE = "acc-employee";
    private static final String OTHER_EMPLOYEE = "acc-other-employee";
    private static final String BASE = "/api/v1/physical-assets";

    @Container
    static MongoDBContainer mongo = new MongoDBContainer(DockerImageName.parse("mongo:6.0")).withCommand("--replSet", "rs0");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongo::getReplicaSetUrl);
    }

    @LocalServerPort private int port;
    @Autowired private TokenIssuerPort tokens;
    @Autowired private EventStorePort eventStore;
    @Autowired private FundCommandService funds;
    @Autowired private MongoTemplate mongoTemplate;
    @MockitoBean private IdentityPrincipalPort identityPrincipalPort;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void principals() {
        Map<String, AuthorizationPrincipal> principals = Map.of(
                EMPLOYEE, new AuthorizationPrincipal(EMPLOYEE, ORG, Set.of(AuthorizationRole.EMPLOYEE), null),
                OTHER_EMPLOYEE, new AuthorizationPrincipal(OTHER_EMPLOYEE, OTHER_ORG, Set.of(AuthorizationRole.EMPLOYEE), null));
        when(identityPrincipalPort.resolvePrincipal(anyString())).thenAnswer(inv -> principals.get(inv.<String>getArgument(0)));
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

    private HttpResponse<String> post(String path, String commandId, String body) throws Exception {
        return send("POST", path, EMPLOYEE, commandId, body);
    }

    private static String newId() {
        return UUID.randomUUID().toString();
    }

    private static final String IN_KIND = """
            {"assetType":"FOOD","quantity":"10","unitOfMeasure":"KGS","custodianRef":"CUST-1","currentLocation":"WH-1"}""";

    private String registerInKind() throws Exception {
        HttpResponse<String> r = post(BASE + "/from-donation", newId(), IN_KIND);
        assertThat(r.statusCode()).isEqualTo(201);
        return json.readTree(r.body()).get("assetRef").asText();
    }

    private JsonNode read(String assetRef) throws Exception {
        HttpResponse<String> r = send("GET", BASE + "/" + assetRef, EMPLOYEE, null, null);
        assertThat(r.statusCode()).isEqualTo(200);
        return json.readTree(r.body());
    }

    private void logistics(String assetRef) throws Exception {
        assertThat(post(BASE + "/" + assetRef + "/dispatch", newId(), "{\"carrierRef\":\"CARRIER-1\"}").body())
                .isEqualTo("{\"assetRef\":\"" + assetRef + "\",\"status\":\"DISPATCHED\"}");
        assertThat(post(BASE + "/" + assetRef + "/receive", newId(),
                "{\"facilityLocation\":\"WH-2\",\"receiverRef\":\"REC-1\"}").statusCode()).isEqualTo(200);
        HttpResponse<String> delivered = post(BASE + "/" + assetRef + "/deliver", newId(),
                "{\"finalCustodianRef\":\"CUST-F\",\"beneficiaryRef\":\"BEN-1\",\"locationRef\":\"SITE-1\",\"evidenceRef\":\"EV-1\"}");
        assertThat(delivered.statusCode()).isEqualTo(200);
        assertThat(delivered.body()).isEqualTo("{\"assetRef\":\"" + assetRef + "\",\"status\":\"DELIVERED\"}");
    }

    @Test
    void goldenPath_registerInKind_split_waitForTheChild_andDeliverBoth() throws Exception {
        HttpResponse<String> registered = post(BASE + "/from-donation", newId(), IN_KIND);
        assertThat(registered.statusCode()).isEqualTo(201);
        JsonNode body = json.readTree(registered.body());
        assertThat(body.fieldNames()).toIterable().containsExactlyInAnyOrder("assetRef", "status", "donationRef");
        assertThat(body.get("status").asText()).isEqualTo("REGISTERED");
        String parent = body.get("assetRef").asText();

        HttpResponse<String> split = post(BASE + "/" + parent + "/split", newId(), "{\"quantity\":\"4\"}");
        assertThat(split.statusCode()).isEqualTo(202);
        JsonNode accepted = json.readTree(split.body());
        String child = accepted.get("childAssetRef").asText();
        assertThat(accepted.get("parentAssetRef").asText()).isEqualTo(parent);
        assertThat(accepted.get("status").asText()).isEqualTo("PENDING");
        String location = split.headers().firstValue("Location").orElseThrow();
        assertThat(location).isEqualTo(BASE + "/" + parent + "/splits/" + child);

        // El guion de la demo espera a que la saga cree el hijo (golden path, paso 4B)
        String status = "PENDING";
        Instant deadline = Instant.now().plusSeconds(30);
        while (!"CHILD_CREATED".equals(status) && Instant.now().isBefore(deadline)) {
            HttpResponse<String> r = send("GET", location, EMPLOYEE, null, null);
            assertThat(r.statusCode()).isEqualTo(200);
            status = json.readTree(r.body()).get("status").asText();
            if (!"CHILD_CREATED".equals(status)) Thread.sleep(200);
        }
        assertThat(status).isEqualTo("CHILD_CREATED");

        // Criterios 15 y 16
        assertThat(read(child).get("quantity").asText()).isEqualTo("4.0000");
        assertThat(read(parent).get("quantity").asText()).isEqualTo("6.0000");
        AssetRegisteredV3Payload parentGenesis = (AssetRegisteredV3Payload) eventStore.loadStream(parent).get(0).payload();
        AssetRegisteredV3Payload childGenesis = (AssetRegisteredV3Payload) eventStore.loadStream(child).get(0).payload();
        assertThat(childGenesis.organizationRef()).isEqualTo(ORG);
        assertThat(childGenesis.donorRef()).isEqualTo(parentGenesis.donorRef());
        assertThat(childGenesis.donationRef()).isEqualTo(parentGenesis.donationRef());
        assertThat(childGenesis.campaignRef()).isEqualTo(parentGenesis.campaignRef());

        // Criterios 8 y 17
        logistics(parent);
        logistics(child);
        assertThat(read(parent).get("lifecycleStatus").asText()).isEqualTo("DELIVERED");
        assertThat(read(child).get("lifecycleStatus").asText()).isEqualTo("DELIVERED");
    }

    @Test
    void registerPathA_overHttp_takesTheOrganizationFromTheJwt_andTheCampaignFromTheFund() throws Exception {
        String fundId = newId();
        String allocationId = newId();
        SystemActor system = new SystemActor("b6c-http-tests");
        funds.clearFundsGenesis(newId(), fundId, new OrganizationRef(ORG), "camp-b6c", "anon:d", "COP", 1000L, "SRC", system);
        funds.requestAllocation(newId(), fundId, allocationId, 100L, system);

        HttpResponse<String> r = post(BASE + "/register", newId(), """
                {"fundId":"%s","assetType":"FOOD","quantity":"1","unitOfMeasure":"KGS","custodianRef":"C",
                 "currentLocation":"WH-1","allocationId":"%s"}""".formatted(fundId, allocationId));

        assertThat(r.statusCode()).isEqualTo(201);
        JsonNode body = json.readTree(r.body());
        assertThat(body.fieldNames()).toIterable().containsExactlyInAnyOrder("assetRef", "status", "campaignRef");
        assertThat(body.get("campaignRef").asText()).isEqualTo("camp-b6c");
    }

    @Test
    void theSameCommandId_givesTheSameStatusAndBody_andAnotherCommandWithIt_is409() throws Exception {
        String register = newId();
        HttpResponse<String> first = post(BASE + "/from-donation", register, IN_KIND);
        HttpResponse<String> again = post(BASE + "/from-donation", register, IN_KIND);
        String assetRef = json.readTree(first.body()).get("assetRef").asText();
        String dispatch = newId();
        HttpResponse<String> d1 = post(BASE + "/" + assetRef + "/dispatch", dispatch, "{\"carrierRef\":\"C\"}");
        HttpResponse<String> d2 = post(BASE + "/" + assetRef + "/dispatch", dispatch, "{\"carrierRef\":\"C\"}");
        String split = newId();
        HttpResponse<String> s1 = post(BASE + "/" + registerInKind() + "/split", split, "{\"quantity\":\"1\"}");

        assertThat(again.statusCode()).isEqualTo(first.statusCode());
        assertThat(again.body()).isEqualTo(first.body());
        assertThat(d2.statusCode()).isEqualTo(d1.statusCode()).isEqualTo(200);
        assertThat(d2.body()).isEqualTo(d1.body());
        assertThat(eventStore.loadStream(assetRef)).hasSize(2);
        assertThat(s1.statusCode()).isEqualTo(202);

        HttpResponse<String> reused = post(BASE + "/" + assetRef + "/receive", dispatch,
                "{\"facilityLocation\":\"W\",\"receiverRef\":\"R\"}");
        assertThat(reused.statusCode()).isEqualTo(409);
        assertThat(reused.body()).contains("\"title\":\"CommandIdReused\"").doesNotContain(dispatch);
        assertThat(eventStore.loadStream(assetRef)).hasSize(2);
    }

    @Test
    void withoutCommandId_orWithAnInvalidBody_is400_andNothingIsWritten() throws Exception {
        long before = mongoTemplate.count(new Query(), "traceability_events");

        HttpResponse<String> noCommandId = post(BASE + "/from-donation", null, IN_KIND);
        HttpResponse<String> badQuantity = post(BASE + "/from-donation", newId(), IN_KIND.replace("\"10\"", "\"-7.123456\""));
        HttpResponse<String> missing = post(BASE + "/from-donation", newId(), "{\"assetType\":\"FOOD\"}");

        assertThat(noCommandId.statusCode()).isEqualTo(400);
        assertThat(badQuantity.statusCode()).isEqualTo(400);
        assertThat(badQuantity.body()).doesNotContain("7.123456");
        assertThat(missing.statusCode()).isEqualTo(400);
        assertThat(mongoTemplate.count(new Query(), "traceability_events")).isEqualTo(before);
    }

    @Test
    void otherOrganization_andNonexistentAsset_giveTheSame403_byteForByte() throws Exception {
        String assetRef = registerInKind();
        String missing = newId();
        List<String[]> calls = List.of(
                new String[]{"GET", "", null},
                new String[]{"GET", "/splits/" + newId(), null},
                new String[]{"POST", "/dispatch", "{\"carrierRef\":\"C\"}"},
                new String[]{"POST", "/receive", "{\"facilityLocation\":\"W\",\"receiverRef\":\"R\"}"},
                new String[]{"POST", "/split", "{\"quantity\":\"1\"}"},
                new String[]{"POST", "/deliver",
                        "{\"finalCustodianRef\":\"C\",\"beneficiaryRef\":\"B\",\"locationRef\":\"L\",\"evidenceRef\":\"E\"}"});

        for (String[] call : calls) {
            String commandId = "POST".equals(call[0]) ? newId() : null;
            HttpResponse<String> foreign = send(call[0], BASE + "/" + assetRef + call[1], OTHER_EMPLOYEE, commandId, call[2]);
            HttpResponse<String> absent = send(call[0], BASE + "/" + missing + call[1], EMPLOYEE,
                    "POST".equals(call[0]) ? newId() : null, call[2]);

            assertThat(foreign.statusCode()).as(call[0] + call[1]).isEqualTo(403);
            assertThat(absent.statusCode()).as(call[0] + call[1]).isEqualTo(403);
            assertThat(absent.body()).as(call[0] + call[1]).isEqualTo(foreign.body());
            assertThat(foreign.body()).doesNotContain(assetRef).doesNotContain(ORG);
        }
        assertThat(eventStore.loadStream(assetRef)).hasSize(1);
    }

    @Test
    void aSplitThatDoesNotExistInTheOwnOrganization_is404() throws Exception {
        HttpResponse<String> r = send("GET", BASE + "/" + registerInKind() + "/splits/" + newId(), EMPLOYEE, null, null);

        assertThat(r.statusCode()).isEqualTo(404);
    }

    @Test
    void aDonorRefSentByTheClient_isIgnored_theServerGeneratesAnOpaqueOne() throws Exception {
        HttpResponse<String> r = post(BASE + "/from-donation", newId(),
                IN_KIND.replace("{", "{\"donorRef\":\"Juan Perez CC 123\",\"organizationRef\":\"" + OTHER_ORG + "\","));

        assertThat(r.statusCode()).isEqualTo(201);
        String assetRef = json.readTree(r.body()).get("assetRef").asText();
        DomainEvent genesis = eventStore.loadStream(assetRef).get(0);
        AssetRegisteredV3Payload payload = (AssetRegisteredV3Payload) genesis.payload();
        assertThat(payload.donorRef()).startsWith("anon:").doesNotContain("Juan");
        assertThat(payload.organizationRef()).isEqualTo(ORG);
        assertThat(r.body()).doesNotContain("donorRef").doesNotContain("anon:");
    }

    @Test
    void theOperationalView_hasNoDonorNorGenealogy() throws Exception {
        JsonNode view = read(registerInKind());

        assertThat(view.fieldNames()).toIterable().containsExactlyInAnyOrder("assetRef", "lifecycleStatus",
                "currentCustodianRef", "currentLocation", "quantity", "unitOfMeasure");
        assertThat(view.get("quantity").asText()).isEqualTo("10.0000");
    }
}
