package com.traceability.app.auth;

import com.traceability.app.TraceabilityApplication;
import com.traceability.contracts.authentication.TokenIssuerPort;
import com.traceability.contracts.authorization.AuthorizationPrincipal;
import com.traceability.contracts.authorization.IdentityPrincipalPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Decisión de Carlos (2026-10-07, plan B3 §2.3): si la base de datos falla al resolver el principal, el filtro
 * propaga el error y la respuesta es <strong>500, nunca 401</strong>. Un fallo de infraestructura no es un fallo de
 * autenticación: un 401 haría creer al cliente que su token no vale y ocultaría la caída. Lo esencial es que el
 * controlador no se ejecuta. Comprobado contra Tomcat real ({@code RANDOM_PORT}), no contra MockMvc.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {TraceabilityApplication.class, PrincipalResolutionFailureRealServerIntegrationTest.CountingController.class})
@Testcontainers
@TestPropertySource(properties = {
    "crypto.anchor.poll.delay=9999999",
    "crypto.anchor.submit.delay=9999999",
    "crypto.anchor.stuck-monitor.delay=9999999",
    "crypto.web3j.private-key=0x1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef",
    "crypto.web3j.node-url=http://dummy-node",
    "spring.ai.openai.api-key=dummy-api-key"
})
class PrincipalResolutionFailureRealServerIntegrationTest {

    static final String PROTECTED = "/api/v1/b3-test/counted";
    static final AtomicInteger INVOCATIONS = new AtomicInteger();

    /** Solo de test: cuenta cuántas veces se ejecuta la ruta protegida. */
    @RestController
    static class CountingController {
        @GetMapping(PROTECTED)
        String counted() {
            INVOCATIONS.incrementAndGet();
            return "reached";
        }
    }

    @Container
    static MongoDBContainer mongo = new MongoDBContainer(DockerImageName.parse("mongo:6.0")).withCommand("--replSet", "rs0");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongo::getReplicaSetUrl);
    }

    @LocalServerPort private int port;
    @Autowired private TokenIssuerPort tokenIssuerPort;
    @MockitoBean private IdentityPrincipalPort identityPrincipalPort;

    @BeforeEach
    void reset() {
        INVOCATIONS.set(0);
    }

    @Test
    void control_aResolvablePrincipal_reachesTheController() throws Exception {
        when(identityPrincipalPort.resolvePrincipal(anyString()))
                .thenAnswer(inv -> new AuthorizationPrincipal(inv.getArgument(0), null, Set.of(), null));

        RawHttp.Response r = RawHttp.send(port, "GET", PROTECTED, tokenIssuerPort.issue("acc-1"), null);

        assertThat(r.status()).isEqualTo(200);
        assertThat(INVOCATIONS.get()).isEqualTo(1);
    }

    @Test
    void aDatabaseFailureWhileResolvingThePrincipal_is500_never401_andTheControllerNeverRuns() throws Exception {
        when(identityPrincipalPort.resolvePrincipal(anyString()))
                .thenThrow(new DataAccessResourceFailureException("mongo down"));

        RawHttp.Response r = RawHttp.send(port, "GET", PROTECTED, tokenIssuerPort.issue("acc-1"), null);

        assertThat(r.status()).isEqualTo(500);
        assertThat(INVOCATIONS.get()).isZero();
        assertThat(r.body()).doesNotContain("reached").doesNotContain("mongo down");
    }
}
