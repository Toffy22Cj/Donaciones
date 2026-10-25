package com.traceability.app.web.organization;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.app.TraceabilityApplication;
import com.traceability.app.web.campaign.DiscoveryCursorCodec;
import com.traceability.app.web.platform.OrganizationVerificationQueueController;
import com.traceability.contracts.SequenceRange;
import com.traceability.contracts.authentication.TokenIssuerPort;
import com.traceability.core.application.event.EventCanonicalMapper;
import com.traceability.core.application.event.EventPayloadRegistry;
import com.traceability.core.domain.event.DomainEventPayload;
import com.traceability.core.domain.fund.payloads.FundsClearedV2Payload;
import com.traceability.core.domain.physicalasset.payloads.AssetDeliveredPayload;
import com.traceability.core.domain.physicalasset.payloads.AssetDispatchedPayload;
import com.traceability.core.domain.physicalasset.payloads.AssetRegisteredV3Payload;
import com.traceability.core.infrastructure.persistence.mongo.TraceabilityEventDocument;
import com.traceability.crypto.application.port.out.MerkleBatchRepositoryPort;
import com.traceability.crypto.domain.AnchorStatus;
import com.traceability.crypto.domain.MerkleBatch;
import identity.application.service.AddEmployeeService;
import identity.application.service.AssignAdministratorService;
import identity.application.service.CreateAccountService;
import identity.application.service.CreateOrganizationService;
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
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ficha R-6, Bloque D por HTTP (plan D: tests 10e, 11c, 11e y 11f): {@code GET /organizations/{id}/integrity} (G-7) y
 * {@code GET /organizations/{id}/activity} (G-8), con la aplicación completa y Mongo real. Los eventos se escriben a
 * mano con su payload real, con donante, beneficiario, custodios e importes, para comprobar que nada de eso sale.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = TraceabilityApplication.class)
@Testcontainers
@TestPropertySource(properties = {
    "crypto.anchor.poll.delay=9999999",
    "crypto.anchor.submit.delay=9999999",
    "crypto.anchor.stuck-monitor.delay=9999999",
    "traceability.anchor.producer.interval-ms=9999999",
    "crypto.web3j.private-key=0x1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef",
    "crypto.web3j.node-url=http://dummy-node",
    "spring.ai.openai.api-key=dummy-api-key"
})
class OrganizationIntegrityAndActivityHttpIntegrationTest {

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
    @Autowired private MongoTemplate mongoTemplate;
    @Autowired private MerkleBatchRepositoryPort batches;
    @Autowired private DiscoveryCursorCodec cursors;

    private static final EventCanonicalMapper MAPPER = new EventCanonicalMapper();
    private static final AuditActor SETUP = new AuditActor.SystemAuditActor("r6-bloque-d-tests");
    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    private String orgId;
    private String admin;
    private String representative;
    private String employee;
    private String otherAdmin;
    private String otherOrgId;

    private String account() {
        return accounts.createAccount(new Email(UUID.randomUUID() + "@r6d.test"), "Pass123!Pass123!").getAccountId().value();
    }

    @BeforeEach
    void world() {
        Account rep = accounts.createAccount(new Email(UUID.randomUUID() + "@r6d.test"), "Pass123!Pass123!");
        Organization org = organizations.createOrganization(SETUP, OrganizationType.FOUNDATION, rep.getAccountId(), "Org R6D");
        orgId = org.getOrganizationId().value();
        representative = rep.getAccountId().value();
        admin = account();
        employees.addEmployee(SETUP, org.getOrganizationId(), new AccountId(admin));
        administrators.assignAdministrator(SETUP, org.getOrganizationId(), new AccountId(admin));
        employee = account();
        employees.addEmployee(SETUP, org.getOrganizationId(), new AccountId(employee));

        Account otherRep = accounts.createAccount(new Email(UUID.randomUUID() + "@r6d.test"), "Pass123!Pass123!");
        Organization other = organizations.createOrganization(SETUP, OrganizationType.FOUNDATION, otherRep.getAccountId(), "Otra");
        otherOrgId = other.getOrganizationId().value();
        otherAdmin = account();
        employees.addEmployee(SETUP, other.getOrganizationId(), new AccountId(otherAdmin));
        administrators.assignAdministrator(SETUP, other.getOrganizationId(), new AccountId(otherAdmin));

        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String fund = "fund-" + suffix;
        String asset = "asset-" + suffix;
        insert(fund, 1, "FUNDS_CLEARED", new FundsClearedV2Payload(orgId, 987654L, "tx-secret", "COP", "camp-1",
                "anon:secret-donor"), "2026-10-10T00:00:00Z", "batch-" + suffix);
        insert(asset, 1, "ASSET_REGISTERED", new AssetRegisteredV3Payload(asset, "BLANKET", new BigDecimal("10"), "UNITS",
                "warehouse-exact-1", "custodian-secret", null, asset, "alloc-1", null, orgId, "anon:secret-donor",
                "don-1", "camp-1"), "2026-10-11T00:00:00Z", "batch-" + suffix);
        insert(asset, 2, "ASSET_DISPATCHED", new AssetDispatchedPayload("carrier-secret", "warehouse-exact-1"),
                "2026-10-12T00:00:00Z", null);
        insert(asset, 3, "ASSET_DELIVERED", new AssetDeliveredPayload("final-custodian-secret", "beneficiary-secret",
                "site-exact-2", "sha256:" + "2".repeat(64), Instant.parse("2026-10-13T00:00:00Z")), "2026-10-13T00:00:00Z", null);
        batches.save(new MerkleBatch("batch-" + suffix, Map.of(fund, new SequenceRange(1, 1), asset, new SequenceRange(1, 1)),
                "root-secret-" + suffix, List.of("h1", "h2"), Instant.parse("2026-10-14T00:00:00Z"), AnchorStatus.ANCHORED,
                "sepolia", "0xcontract", 1L, "0xtx-secret-" + suffix, Instant.parse("2026-10-14T01:00:00Z"),
                Instant.parse("2026-10-14T02:00:00Z"), 9L, null));
    }

    private void insert(String stream, long sequence, String type, DomainEventPayload payload, String recordedAt,
                        String batchId) {
        String eventId = UUID.randomUUID().toString();
        String aggregate = stream.startsWith("fund") ? "Fund" : "PhysicalAsset";
        String schema = EventPayloadRegistry.getSchemaVersionForClass(payload.getClass());
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) MAPPER.toCanonicalMap(eventId, stream, aggregate, sequence, type,
                schema, Instant.parse(recordedAt), Instant.parse(recordedAt), "TEST", payload).get("payload");
        mongoTemplate.insert(TraceabilityEventDocument.builder().eventId(eventId).streamId(stream).aggregateType(aggregate)
                .sequence(sequence).eventType(type).schemaVersion(schema).occurredAt(recordedAt).recordedAt(recordedAt)
                .actorRef(new com.traceability.core.domain.event.HumanActor("actor-secret"))
                .payload(map).merkleBatchId(batchId).eventHash("h").previousHash("p").build());
    }

    private HttpResponse<String> get(String path, String account) throws Exception {
        HttpRequest.Builder r = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(15)).GET();
        if (account != null) r.header("Authorization", "Bearer " + tokens.issue(account));
        return http.send(r.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static final List<String> SECRETS = List.of("anon:secret-donor", "donorRef", "beneficiary", "custodian",
            "carrier", "actor", "warehouse-exact-1", "site-exact-2", "987654", "tx-secret", "root-secret", "0xtx-secret",
            "batchId", "merkleRoot", "transactionHash", "batch-", "evidence", "sha256:");

    // ---- G-7 ----

    // 10e y roles: ADMINISTRATOR y REPRESENTATIVE → 200 sin ids de lotes; EMPLOYEE y otra organización → el mismo 403
    @Test
    void t10e_integrity_forAdministratorAndRepresentative_withoutBatchIds_andTheSame403ForTheRest() throws Exception {
        for (String who : List.of(admin, representative)) {
            HttpResponse<String> r = get("/api/v1/organizations/" + orgId + "/integrity", who);
            assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
            assertThat(r.headers().firstValue("Cache-Control")).hasValue("no-store");
            JsonNode body = json.readTree(r.body());
            Set<String> fields = new TreeSet<>();
            body.fieldNames().forEachRemaining(fields::add);
            assertThat(fields).containsExactlyInAnyOrder("unanchoredEvents", "inPendingBatches", "underReviewEvents",
                    "lastAnchoredAt", "truncated", "checkedAt");
            assertThat(body.get("unanchoredEvents").asLong()).isEqualTo(2);
            assertThat(body.get("inPendingBatches").asLong()).isZero();
            assertThat(body.get("underReviewEvents").asLong()).isZero();
            assertThat(body.get("lastAnchoredAt").asText()).isEqualTo("2026-10-14T02:00:00Z");
            assertThat(body.get("truncated").asBoolean()).isFalse();
            assertThat(r.body()).doesNotContain("batch-").doesNotContain("root-secret").doesNotContain("0xtx-secret")
                    .doesNotContain("batchId").doesNotContain("merkleRoot").doesNotContain("transactionHash");
        }
        HttpResponse<String> asEmployee = get("/api/v1/organizations/" + orgId + "/integrity", employee);
        HttpResponse<String> asOther = get("/api/v1/organizations/" + orgId + "/integrity", otherAdmin);
        assertThat(asEmployee.statusCode()).isEqualTo(403);
        assertThat(asOther.statusCode()).isEqualTo(403);
        assertThat(json.readTree(asEmployee.body()).get("title")).isEqualTo(json.readTree(asOther.body()).get("title"));
        assertThat(json.readTree(asEmployee.body()).get("detail")).isEqualTo(json.readTree(asOther.body()).get("detail"));
        assertThat(get("/api/v1/organizations/" + orgId + "/integrity", null).statusCode()).isEqualTo(401);
    }

    // ---- G-8 ----

    // 11f y 11e: los tres roles → 200, sin actor, donante, beneficiario, custodio, ubicación exacta ni importe
    @Test
    void t11f_t11e_activity_forTheThreeRoles_withoutPersonalDataExactLocationsNorAmounts() throws Exception {
        for (String who : List.of(admin, representative, employee)) {
            HttpResponse<String> r = get("/api/v1/organizations/" + orgId + "/activity", who);
            assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
            assertThat(r.headers().firstValue("Cache-Control")).hasValue("no-store");
            JsonNode items = json.readTree(r.body()).get("items");
            List<String> types = new ArrayList<>();
            items.forEach(i -> types.add(i.get("type").asText()));
            assertThat(types).containsExactly("BATCH_ANCHORED", "ASSET_DELIVERED", "ASSET_DISPATCHED",
                    "ASSET_REGISTERED", "FUNDS_CLEARED");
            // BATCH_ANCHORED sin subject y con eventCount (P-R6D-1 (b)); los demás, con subject
            assertThat(items.get(0).has("subject")).isFalse();
            assertThat(items.get(0).get("eventCount").asLong()).isEqualTo(2);
            assertThat(items.get(1).get("subject").get("kind").asText()).isEqualTo("ASSET");
            assertThat(items.get(4).get("subject").get("kind").asText()).isEqualTo("FUND");
            assertThat(items.get(3).get("quantity").asText()).isEqualTo("10");
            assertThat(items.get(3).get("unitOfMeasure").asText()).isEqualTo("UNITS");
            for (JsonNode item : items) {
                Set<String> fields = new TreeSet<>();
                item.fieldNames().forEachRemaining(fields::add);
                assertThat(Set.of("type", "occurredAt", "subject", "eventCount", "quantity", "unitOfMeasure", "locationZone"))
                        .containsAll(fields);
            }
            for (String secret : SECRETS) {
                assertThat(r.body()).as(secret).doesNotContain(secret);
            }
            assertThat(json.readTree(r.body()).has("nextCursor")).isFalse();
        }
        HttpResponse<String> asOther = get("/api/v1/organizations/" + orgId + "/activity", otherAdmin);
        assertThat(asOther.statusCode()).isEqualTo(403);
        assertThat(get("/api/v1/organizations/" + orgId + "/activity", null).statusCode()).isEqualTo(401);
    }

    // 11c: un cursor inválido, manipulado, de otro propósito o de otra organización → 400
    @Test
    void t11c_anInvalidTamperedForeignPurposeOrOtherOrganizationCursor_is400() throws Exception {
        for (int i = 0; i < 25; i++) {
            insert("asset-many-" + i, 1, "ASSET_REGISTERED", new AssetRegisteredV3Payload("asset-many-" + i, "BLANKET",
                    BigDecimal.ONE, "UNITS", "w", "c", null, "asset-many-" + i, "a", null, orgId, "anon:x", "d", "c"),
                    String.format("2026-10-20T00:00:%02dZ", i), null);
        }
        JsonNode first = json.readTree(get("/api/v1/organizations/" + orgId + "/activity", admin).body());
        String next = first.get("nextCursor").asText();
        assertThat(first.get("items")).hasSize(20);
        HttpResponse<String> second = get("/api/v1/organizations/" + orgId + "/activity?cursor=" + enc(next), admin);
        assertThat(second.statusCode()).as(second.body()).isEqualTo(200);

        char last = next.charAt(next.length() - 1);
        String tampered = next.substring(0, next.length() - 1) + (last == 'A' ? 'B' : 'A');
        String discovery = cursors.encode("ABC123");
        String verificationQueue = cursors.encode(OrganizationVerificationQueueController.CURSOR_PURPOSE
                + "01JABCDEFGHJKMNPQRSTVWXYZ0");
        for (String bad : List.of("abc", tampered, discovery, verificationQueue)) {
            HttpResponse<String> r = get("/api/v1/organizations/" + orgId + "/activity?cursor=" + enc(bad), admin);
            assertThat(r.statusCode()).as(bad + ": " + r.body()).isEqualTo(400);
        }
        // El cursor de una organización no vale en otra
        HttpResponse<String> foreign = get("/api/v1/organizations/" + otherOrgId + "/activity?cursor=" + enc(next), otherAdmin);
        assertThat(foreign.statusCode()).as(foreign.body()).isEqualTo(400);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
