package com.traceability.app.web.asset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.app.TraceabilityApplication;
import com.traceability.contracts.authentication.TokenIssuerPort;
import com.traceability.contracts.authorization.AuthorizationPrincipal;
import com.traceability.contracts.authorization.AuthorizationRole;
import com.traceability.contracts.authorization.IdentityPrincipalPort;
import com.traceability.core.application.command.FundCommandService;
import com.traceability.core.application.security.AssetRefService;
import com.traceability.core.application.security.TrackingCodeService;
import com.traceability.core.domain.event.SystemActor;
import com.traceability.core.domain.fund.OrganizationRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.mongodb.core.MongoTemplate;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Enmienda 2 de ADR-029 y el plan de la zona en cada paso (tests 10, 11, 13, 14 y 15), contra Tomcat real con todos
 * los módulos y el catálogo DIVIPOLA de prueba ({@code 05001} MEDELLÍN y {@code 05002} ABEJORRAL, de ANTIOQUIA, y
 * {@code 11001} BOGOTÁ, D.C.):
 * <ul>
 *   <li>{@code municipalityCode} es obligatorio al registrar (los dos caminos), recibir y entregar: sin él, con un código
 *       que no está en el catálogo o con otra forma → 400 {@code BadRequest}, sin escribir ningún evento;</li>
 *   <li>con {@code location_reference} vacía, la historia pública, la logística del donante y la vista del empleado
 *       muestran la zona sellada de cada paso, también el despacho;</li>
 *   <li>ninguna salida pública lleva el texto libre de la ubicación ni el beneficiario.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = TraceabilityApplication.class)
@org.springframework.context.annotation.Import(com.traceability.app.infrastructure.media.InMemoryMediaStorageConfig.class)
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
class ZoneInEachStepHttpIntegrationTest {

    private static final String ORG = "org-zone";
    private static final String EMPLOYEE = "acc-zone-employee";
    private static final String BASE = "/api/v1/physical-assets";
    /** El texto libre de cada paso: privado, nunca en una salida pública. */
    private static final List<String> PRIVATE_TEXT = List.of("Bodega norte privada", "Centro de acopio privado",
            "Escuela rural privada", "BEN-PRIVADO-1");

    @Container
    static MongoDBContainer mongo = new MongoDBContainer(DockerImageName.parse("mongo:6.0")).withCommand("--replSet", "rs0");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongo::getReplicaSetUrl);
    }

    @LocalServerPort private int port;
    @Autowired private TokenIssuerPort tokens;
    @Autowired private FundCommandService funds;
    @Autowired private TrackingCodeService trackingCodes;
    @Autowired private AssetRefService assetRefs;
    @Autowired private MongoTemplate mongoTemplate;
    @MockitoBean private IdentityPrincipalPort identityPrincipalPort;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void principals() {
        Map<String, AuthorizationPrincipal> principals = Map.of(
                EMPLOYEE, new AuthorizationPrincipal(EMPLOYEE, ORG, Set.of(AuthorizationRole.EMPLOYEE), null));
        when(identityPrincipalPort.resolvePrincipal(anyString())).thenAnswer(inv -> principals.get(inv.<String>getArgument(0)));
        when(identityPrincipalPort.resolveAuthenticatedPrincipal(anyString(), any()))
                .thenAnswer(inv -> principals.get(inv.<String>getArgument(0)));
        // la zona pública no puede salir de la tabla manual: está vacía en todo el test
        mongoTemplate.dropCollection("location_reference");
    }

    private HttpResponse<String> send(String method, String path, String bearer, String commandId, String body)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (bearer != null) request.header("Authorization", "Bearer " + bearer);
        if (commandId != null) request.header("Command-Id", commandId);
        if (body != null) request.header("Content-Type", "application/json");
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        return send("POST", path, tokens.issue(EMPLOYEE), UUID.randomUUID().toString(), body);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return send("GET", path, tokens.issue(EMPLOYEE), null, null);
    }

    private long storedEvents() {
        return mongoTemplate.getCollection("event_store").countDocuments();
    }

    private long assetEvents() {
        return mongoTemplate.getCollection("event_store").countDocuments(new org.bson.Document("aggregateType", "PhysicalAsset"));
    }

    private String fundWithAllocation(String allocationId) {
        String fundId = UUID.randomUUID().toString();
        SystemActor system = new SystemActor("zone-http-tests");
        funds.clearFundsGenesis(UUID.randomUUID().toString(), fundId, new OrganizationRef(ORG), "camp-zone", "anon:d",
                "COP", 1000L, "SRC", system);
        funds.requestAllocation(UUID.randomUUID().toString(), fundId, allocationId, 100L, system);
        return fundId;
    }

    private static String registerBody(String fundId, String allocationId, String municipality) {
        return """
                {"fundId":"%s","assetType":"FOOD","quantity":"10","unitOfMeasure":"KGS","custodianRef":"C",
                 "currentLocation":"Bodega norte privada","allocationId":"%s"%s}""".formatted(fundId, allocationId,
                municipality == null ? "" : ",\"municipalityCode\":\"" + municipality + "\"");
    }

    private static String fromDonationBody(String municipality) {
        return """
                {"assetType":"FOOD","quantity":"10","unitOfMeasure":"KGS","custodianRef":"C",
                 "currentLocation":"Bodega norte privada"%s}""".formatted(
                municipality == null ? "" : ",\"municipalityCode\":\"" + municipality + "\"");
    }

    private static String receiveBody(String municipality) {
        return "{\"facilityLocation\":\"Centro de acopio privado\",\"receiverRef\":\"REC-1\""
                + (municipality == null ? "" : ",\"municipalityCode\":\"" + municipality + "\"") + "}";
    }

    private String deliverBody(String municipality) throws Exception {
        return "{\"finalCustodianRef\":\"CUST-F\",\"beneficiaryRef\":\"BEN-PRIVADO-1\",\"locationRef\":\"Escuela rural privada\","
                + "\"evidenceRef\":\"" + evidenceRef() + "\""
                + (municipality == null ? "" : ",\"municipalityCode\":\"" + municipality + "\"") + "}";
    }

    private int photos;

    private String evidenceRef() throws Exception {
        String boundary = "zone-evidence";
        java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
        body.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"entrega.png\""
                + "\r\nContent-Type: image/png\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        body.writeBytes(com.traceability.app.application.media.TestImages.png(30 + photos++, 21));
        body.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                        + "/api/v1/organizations/" + ORG + "/delivery-evidence"))
                .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer " + tokens.issue(EMPLOYEE))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        return json.readTree(r.body()).get("evidenceRef").asText();
    }

    private void assertBadRequest(HttpResponse<String> r) throws Exception {
        assertThat(r.statusCode()).as(r.body()).isEqualTo(400);
        assertThat(json.readTree(r.body()).get("title").asText()).isEqualTo("BadRequest");
    }

    // test 10: sin municipalityCode, con un código que no está en el catálogo o con otra forma → 400, sin eventos
    @Test
    void test10_withoutOrWithAnUnknownOrMalformedMunicipality_theFourRoutesAre400_andWriteNothing() throws Exception {
        String allocationId = UUID.randomUUID().toString();
        String fundId = fundWithAllocation(allocationId);
        HttpResponse<String> ok = post(BASE + "/register", registerBody(fundId, allocationId, "05001"));
        assertThat(ok.statusCode()).as(ok.body()).isEqualTo(201);
        String assetRef = json.readTree(ok.body()).get("assetRef").asText();
        assertThat(post(BASE + "/" + assetRef + "/dispatch", "{\"carrierRef\":\"CARRIER-1\"}").statusCode()).isEqualTo(200);

        long before = storedEvents();
        long assetEventsBefore = assetEvents();
        List<String> invalid = new ArrayList<>();
        invalid.add(null);
        invalid.addAll(List.of("99999", "1234", "0500A"));
        for (String code : invalid) {
            String other = UUID.randomUUID().toString();
            assertBadRequest(post(BASE + "/register", registerBody(fundWithAllocation(other), other, code)));
            assertBadRequest(post(BASE + "/from-donation", fromDonationBody(code)));
            assertBadRequest(post(BASE + "/" + assetRef + "/receive", receiveBody(code)));
            assertBadRequest(post(BASE + "/" + assetRef + "/deliver", deliverBody(code)));
        }
        // cada fundWithAllocation escribe sus dos eventos de Fund; ningún evento de activo
        assertThat(assetEvents()).isEqualTo(assetEventsBefore);
        assertThat(storedEvents() - before).isEqualTo(2L * invalid.size());
    }

    // tests 11, 13, 14 y 15: el recorrido completo con location_reference vacía
    @Test
    void test11_13_14_15_eachStepShowsItsSealedZone_alsoTheDispatch_andNoPublicOutputCarriesThePrivateText()
            throws Exception {
        String allocationId = UUID.randomUUID().toString();
        String fundId = fundWithAllocation(allocationId);
        HttpResponse<String> registered = post(BASE + "/register", registerBody(fundId, allocationId, "05001"));
        assertThat(registered.statusCode()).as(registered.body()).isEqualTo(201);
        String assetId = json.readTree(registered.body()).get("assetRef").asText();
        assertThat(post(BASE + "/" + assetId + "/dispatch", "{\"carrierRef\":\"CARRIER-1\"}").statusCode()).isEqualTo(200);
        assertThat(post(BASE + "/" + assetId + "/receive", receiveBody("05002")).statusCode()).isEqualTo(200);
        HttpResponse<String> delivered = post(BASE + "/" + assetId + "/deliver", deliverBody("11001"));
        assertThat(delivered.statusCode()).as(delivered.body()).isEqualTo(200);

        // test 11: la historia pública, paso a paso, con la zona sellada (también DISPATCHED)
        String code = trackingCodes.generate(fundId, Instant.now().plusSeconds(3600));
        String historyPath = "/api/v1/donations/tracking/assets/" + assetRefs.computeRef(assetId) + "/history";
        JsonNode history = null;
        String historyBody = null;
        Instant deadline = Instant.now().plusSeconds(30);
        while (Instant.now().isBefore(deadline)) {
            HttpResponse<String> r = send("GET", historyPath, code, null, null);
            if (r.statusCode() == 200 && json.readTree(r.body()).get("history").size() == 4) {
                historyBody = r.body();
                history = json.readTree(r.body()).get("history");
                break;
            }
            Thread.sleep(200);
        }
        assertThat(history).as("la proyección escribe los cuatro pasos").isNotNull();
        List<String> statuses = new ArrayList<>();
        List<String> zones = new ArrayList<>();
        history.forEach(step -> {
            statuses.add(step.get("status").asText());
            zones.add(step.get("locationZone").asText());
        });
        assertThat(statuses).containsExactly("REGISTERED", "DISPATCHED", "RECEIVED", "DELIVERED");
        assertThat(zones).containsExactly("MEDELLÍN, ANTIOQUIA", "MEDELLÍN, ANTIOQUIA", "ABEJORRAL, ANTIOQUIA",
                "BOGOTÁ, D.C.");

        // test 13: la logística del donante, con la zona sellada del último paso
        HttpResponse<String> tracking = send("GET", "/api/v1/donations/tracking", code, null, null);
        assertThat(tracking.statusCode()).as(tracking.body()).isEqualTo(200);
        JsonNode logistics = json.readTree(tracking.body()).get("logistics");
        assertThat(logistics).hasSize(1);
        assertThat(logistics.get(0).get("locationZone").asText()).isEqualTo("BOGOTÁ, D.C.");

        // test 15: ninguna salida pública lleva el texto libre ni el beneficiario
        for (String body : List.of(historyBody, tracking.body())) {
            for (String secret : PRIVATE_TEXT) {
                assertThat(body).doesNotContain(secret);
            }
        }

        // test 14 (P6): la vista operativa y la lista de la organización llevan el municipio
        JsonNode operational = json.readTree(get(BASE + "/" + assetId).body());
        assertThat(operational.get("municipalityCode").asText()).isEqualTo("11001");
        assertThat(operational.get("zoneName").asText()).isEqualTo("BOGOTÁ, D.C.");
        HttpResponse<String> list = get("/api/v1/organizations/" + ORG + "/physical-assets");
        assertThat(list.statusCode()).as(list.body()).isEqualTo(200);
        JsonNode item = null;
        for (JsonNode candidate : json.readTree(list.body()).get("items")) {
            if (assetId.equals(candidate.get("assetRef").asText())) item = candidate;
        }
        assertThat(item).isNotNull();
        assertThat(item.get("municipalityCode").asText()).isEqualTo("11001");
        assertThat(item.get("zoneName").asText()).isEqualTo("BOGOTÁ, D.C.");
    }

    // el Camino B también sella la zona (vista operativa)
    @Test
    void pathB_registersWithItsZone() throws Exception {
        HttpResponse<String> r = post(BASE + "/from-donation", fromDonationBody("05002"));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        JsonNode operational = json.readTree(get(BASE + "/" + json.readTree(r.body()).get("assetRef").asText()).body());
        assertThat(operational.get("municipalityCode").asText()).isEqualTo("05002");
        assertThat(operational.get("zoneName").asText()).isEqualTo("ABEJORRAL, ANTIOQUIA");
    }
}
