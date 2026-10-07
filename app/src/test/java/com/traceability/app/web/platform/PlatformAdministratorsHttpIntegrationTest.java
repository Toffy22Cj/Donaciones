package com.traceability.app.web.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.app.TraceabilityApplication;
import com.traceability.contracts.authentication.TokenIssuerPort;
import identity.application.service.BootstrapPlatformAuthorityService;
import identity.application.service.CreateAccountService;
import identity.application.service.DeactivateAccountService;
import identity.domain.model.AccountId;
import identity.domain.model.AuditActor;
import identity.domain.model.Email;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Autorización (3) de Carlos, §3.2, contra Tomcat real con {@code identity} real: conceder, revocar y listar
 * administradores de plataforma. Ante un estado que ya es el pedido, 409; la plataforma nunca se queda sin ningún
 * administrador, tampoco con dos revocaciones cruzadas simultáneas. Un solo test recorre el ciclo, porque el contador
 * de administradores es global en la base de esta clase.
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
class PlatformAdministratorsHttpIntegrationTest {

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
    @Autowired private BootstrapPlatformAuthorityService bootstrap;
    @Autowired private DeactivateAccountService deactivations;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    private String account() {
        return accounts.createAccount(new Email(UUID.randomUUID() + "@platform.test"), PASSWORD).getAccountId().value();
    }

    private HttpResponse<String> send(String method, String path, String account, String body) throws Exception {
        HttpRequest.Builder r = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (account != null) r.header("Authorization", "Bearer " + tokens.issue(account));
        if (body != null) r.header("Content-Type", "application/json");
        return http.send(r.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> grant(String by, String target) throws Exception {
        return send("POST", "/api/v1/platform/administrators", by, "{\"accountId\":\"" + target + "\"}");
    }

    private HttpResponse<String> revoke(String by, String target) throws Exception {
        return send("POST", "/api/v1/platform/administrators/" + target + "/revoke", by, null);
    }

    private String title(HttpResponse<String> r) throws Exception {
        return json.readTree(r.body()).get("title").asText();
    }

    private List<String> administrators(String by) throws Exception {
        HttpResponse<String> r = send("GET", "/api/v1/platform/administrators", by, null);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        JsonNode items = json.readTree(r.body()).get("items");
        for (JsonNode item : items) {
            assertThat(item.fieldNames()).toIterable().containsExactlyInAnyOrder("accountId", "status");
        }
        return StreamSupport.stream(items.spliterator(), false).map(i -> i.get("accountId").asText()).toList();
    }

    @Test
    void grantRevokeAndList_withTheMostRestrictiveAnswers_andNeverWithoutAnAdministrator() throws Exception {
        String email = UUID.randomUUID() + "@platform.test";
        accounts.createAccount(new Email(email), PASSWORD);
        String first = bootstrap.bootstrap(email).value();
        String second = account();
        String outsider = account();

        // conceder
        HttpResponse<String> granted = grant(first, second);
        assertThat(granted.statusCode()).as(granted.body()).isEqualTo(201);
        assertThat(json.readTree(granted.body()).get("platformAuthority").asText()).isEqualTo("ADMINISTRATOR");
        assertThat(administrators(first)).containsExactlyInAnyOrder(first, second);
        assertThat(json.readTree(send("GET", "/api/v1/me", second, null).body()).get("platformAuthority").asText())
                .isEqualTo("ADMINISTRATOR");

        // el estado ya pedido → 409; inexistente → 404; inactiva → 409
        HttpResponse<String> again = grant(first, second);
        assertThat(again.statusCode()).isEqualTo(409);
        assertThat(title(again)).isEqualTo("PlatformAuthorityAlreadyGranted");
        assertThat(grant(first, UUID.randomUUID().toString()).statusCode()).isEqualTo(404);
        String inactive = account();
        deactivations.deactivateAccount(new AuditActor.SystemAuditActor("tests"), new AccountId(inactive));
        HttpResponse<String> toInactive = grant(first, inactive);
        assertThat(toInactive.statusCode()).isEqualTo(409);
        assertThat(title(toInactive)).isEqualTo("PlatformAuthorityTargetInactive");
        assertThat(send("POST", "/api/v1/platform/administrators", first, "{}").statusCode()).isEqualTo(400);

        // quien no es de la plataforma: el mismo 403, también con un cuerpo inválido, y sin efecto
        String forbidden = title(grant(outsider, outsider));
        for (HttpResponse<String> r : List.of(grant(outsider, outsider),
                send("POST", "/api/v1/platform/administrators", outsider, "{}"),
                revoke(outsider, first), send("GET", "/api/v1/platform/administrators", outsider, null))) {
            assertThat(r.statusCode()).isEqualTo(403);
            assertThat(title(r)).isEqualTo(forbidden);
        }
        assertThat(send("GET", "/api/v1/platform/administrators", null, null).statusCode()).isEqualTo(401);
        assertThat(administrators(first)).containsExactlyInAnyOrder(first, second);

        // revocar: el revocado pierde el acceso en la petición siguiente; repetir → 409
        assertThat(revoke(first, second).statusCode()).isEqualTo(200);
        assertThat(send("GET", "/api/v1/platform/administrators", second, null).statusCode()).isEqualTo(403);
        HttpResponse<String> notHeld = revoke(first, second);
        assertThat(notHeld.statusCode()).isEqualTo(409);
        assertThat(title(notHeld)).isEqualTo("PlatformAuthorityNotHeld");
        assertThat(revoke(first, UUID.randomUUID().toString()).statusCode()).isEqualTo(404);

        // el último no se revoca, ni a sí mismo
        HttpResponse<String> last = revoke(first, first);
        assertThat(last.statusCode()).isEqualTo(409);
        assertThat(title(last)).isEqualTo("LastPlatformAdministrator");
        assertThat(administrators(first)).containsExactly(first);

        // dos administradores que se revocan el uno al otro a la vez: nunca quedan cero
        String a = first;
        for (int round = 0; round < 8; round++) {
            String b = account();
            assertThat(grant(a, b).statusCode()).isEqualTo(201);
            CountDownLatch go = new CountDownLatch(1);
            String finalA = a;
            CompletableFuture<HttpResponse<String>> ab = CompletableFuture.supplyAsync(() -> race(go, finalA, b));
            CompletableFuture<HttpResponse<String>> ba = CompletableFuture.supplyAsync(() -> race(go, b, finalA));
            go.countDown();
            List<Integer> statuses = new ArrayList<>(List.of(ab.get().statusCode(), ba.get().statusCode()));
            assertThat(statuses).as("ronda " + round).containsOnlyOnce(200);
            String survivor = ab.get().statusCode() == 200 ? a : b;
            assertThat(administrators(survivor)).as("ronda " + round).containsExactly(survivor);
            a = survivor;
        }
    }

    private HttpResponse<String> race(CountDownLatch go, String by, String target) {
        try {
            go.await();
            return revoke(by, target);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
