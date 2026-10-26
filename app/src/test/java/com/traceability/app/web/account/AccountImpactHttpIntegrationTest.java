package com.traceability.app.web.account;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.app.TraceabilityApplication;
import com.traceability.contracts.authentication.TokenIssuerPort;
import identity.application.service.CreateAccountService;
import identity.domain.model.Email;
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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M-21 "Mi impacto" (plan de cierre §M-21; M21-4, M21-5, M21-6 y M21-8) contra Tomcat real: la ruta, la autorización,
 * la forma exacta de la respuesta y que una cuenta que nunca donó no crea su seudónimo. Las cifras con donaciones reales
 * las comprueba el golden path; los cálculos, {@code AccountImpactQueryTest}.
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
class AccountImpactHttpIntegrationTest {

    @Container
    static MongoDBContainer mongo = new MongoDBContainer(DockerImageName.parse("mongo:6.0")).withCommand("--replSet", "rs0");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongo::getReplicaSetUrl);
    }

    @LocalServerPort private int port;
    @Autowired private TokenIssuerPort tokens;
    @Autowired private CreateAccountService accounts;
    @Autowired private MongoTemplate mongoTemplate;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    private HttpResponse<String> impact(String accountId) throws Exception {
        HttpRequest.Builder r = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/account/impact")).GET();
        if (accountId != null) r.header("Authorization", "Bearer " + tokens.issue(accountId));
        return http.send(r.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void m21_4_m21_6_anAccountThatNeverDonated_zeros_theExactFields_andNoPseudonymIsCreated() throws Exception {
        String accountId = accounts.createAccount(new Email(UUID.randomUUID() + "@m21.test"), "Pass123!Pass123!")
                .getAccountId().value();
        long pseudonymsBefore = mongoTemplate.getCollection("donor_pseudonyms").countDocuments();

        HttpResponse<String> r = impact(accountId);

        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        JsonNode body = json.readTree(r.body());
        assertThat(body.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("donationsCount", "totals", "campaignsSupported", "deliveredUnits", "truncated");
        assertThat(body.get("donationsCount").asInt()).isZero();
        assertThat(body.get("totals")).isEmpty();
        assertThat(body.get("campaignsSupported").asInt()).isZero();
        assertThat(body.get("deliveredUnits")).isEmpty();
        assertThat(body.get("truncated").asBoolean()).isFalse();
        assertThat(r.headers().firstValue("Cache-Control").orElseThrow()).contains("private").contains("no-store");
        assertThat(r.body()).doesNotContain(accountId);
        assertThat(mongoTemplate.getCollection("donor_pseudonyms").countDocuments()).isEqualTo(pseudonymsBefore);
    }

    @Test
    void m21_5_withoutJwt_is401() throws Exception {
        assertThat(impact(null).statusCode()).isEqualTo(401);
    }
}
