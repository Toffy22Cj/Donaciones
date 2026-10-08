package com.traceability.app.auth;

import com.traceability.api.web.CurrentActor;
import com.traceability.api.web.CurrentActorRouteValidator;
import com.traceability.core.application.authorization.CrossOrganizationAccessException;
import org.springframework.context.ApplicationContext;
import com.traceability.app.TraceabilityApplication;
import com.traceability.contracts.authentication.TokenIssuerPort;
import com.traceability.contracts.authorization.AuthorizationPrincipal;
import com.traceability.contracts.authorization.AuthorizationRole;
import com.traceability.contracts.authorization.IdentityPrincipalPort;
import com.traceability.core.domain.event.HumanActor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Plan B6-0, tests 1 y 2 contra Tomcat real ({@code RANDOM_PORT}): el filtro JWT de B3 y {@code @CurrentActor}
 * encadenados como en producción. Los controladores son solo de test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {TraceabilityApplication.class, CurrentActorRealServerIntegrationTest.ActorController.class})
@Testcontainers
@TestPropertySource(properties = {
    "crypto.anchor.poll.delay=9999999",
    "crypto.anchor.submit.delay=9999999",
    "crypto.anchor.stuck-monitor.delay=9999999",
    "crypto.web3j.private-key=0x1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef",
    "crypto.web3j.node-url=http://dummy-node",
    "spring.ai.openai.api-key=dummy-api-key"
})
class CurrentActorRealServerIntegrationTest {

    static final String PROTECTED_ACTOR = "/api/v1/b6-test/actor";
    static final String PROTECTED_PRINCIPAL = "/api/v1/b6-test/principal";
    static final String PROTECTED_FORBIDDEN = "/api/v1/b6-test/forbidden";
    static final String PROTECTED_BROKEN = "/api/v1/b6-test/broken";
    /**
     * Ruta de JWT opcional de {@code PublicRoutes}. Desde B6-b tiene un controlador real (CV-11); el de este test
     * exige además el parámetro {@code b60test}, así que Spring lo elige solo en estas peticiones.
     */
    static final String OPTIONAL_JWT = "/api/v1/public/campaigns/demo/donation-intents?b60test=1";

    /** Solo de test. */
    @RestController
    static class ActorController {
        @GetMapping(PROTECTED_ACTOR)
        String actor(@CurrentActor HumanActor actor) {
            return "actor:" + actor.accountId();
        }

        @GetMapping(PROTECTED_PRINCIPAL)
        String principal(@CurrentActor AuthorizationPrincipal principal) {
            return "principal:" + principal.accountId() + "/" + principal.organizationId() + "/" + principal.roles();
        }

        @GetMapping(PROTECTED_FORBIDDEN)
        String forbidden(@CurrentActor HumanActor actor) {
            throw new CrossOrganizationAccessException("org-x of " + actor.accountId());
        }

        @GetMapping(PROTECTED_BROKEN)
        String broken(@CurrentActor HumanActor actor) {
            throw new IllegalStateException("internal detail of " + actor.accountId());
        }

        @PostMapping(value = "/api/v1/public/campaigns/{publicCode}/donation-intents", params = "b60test")
        String optional(@CurrentActor Optional<HumanActor> actor) {
            return actor.map(a -> "some:" + a.accountId()).orElse("none");
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
    @Autowired private ApplicationContext context;

    @BeforeEach
    void principals() {
        when(identityPrincipalPort.resolvePrincipal(anyString())).thenAnswer(inv ->
                new AuthorizationPrincipal(inv.getArgument(0), "org-7", Set.of(AuthorizationRole.ADMINISTRATOR), null));
    }

    @Test
    void humanActor_isTheAccountOfTheJwt() throws Exception {
        RawHttp.Response r = RawHttp.send(port, "GET", PROTECTED_ACTOR, tokenIssuerPort.issue("acc-1"), null);

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body()).isEqualTo("actor:acc-1");
    }

    @Test
    void authorizationPrincipal_isTheWholeResolvedPrincipal() throws Exception {
        RawHttp.Response r = RawHttp.send(port, "GET", PROTECTED_PRINCIPAL, tokenIssuerPort.issue("acc-2"), null);

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body()).isEqualTo("principal:acc-2/org-7/[ADMINISTRATOR]");
    }

    @Test
    void aProtectedRouteWithoutJwt_is401_beforeReachingTheResolver() throws Exception {
        assertThat(RawHttp.send(port, "GET", PROTECTED_ACTOR, null, null).status()).isEqualTo(401);
    }

    @Test
    void optionalJwtRoute_withoutHeader_givesAnEmptyActor() throws Exception {
        RawHttp.Response r = RawHttp.send(port, "POST", OPTIONAL_JWT, null, "{}");

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body()).isEqualTo("none");
    }

    @Test
    void optionalJwtRoute_withAValidJwt_givesTheActor() throws Exception {
        RawHttp.Response r = RawHttp.send(port, "POST", OPTIONAL_JWT, tokenIssuerPort.issue("acc-3"), "{}");

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body()).isEqualTo("some:acc-3");
    }

    @Test
    void theRouteValidatorRunsInTheWebApplication() {
        assertThat(context.getBeansOfType(CurrentActorRouteValidator.class)).hasSize(1);
    }

    @Test
    void theExceptionHandler_answersOnTheRealServer_403AndOpaque500() throws Exception {
        RawHttp.Response forbidden = RawHttp.send(port, "GET", PROTECTED_FORBIDDEN, tokenIssuerPort.issue("acc-4"), null);
        RawHttp.Response broken = RawHttp.send(port, "GET", PROTECTED_BROKEN, tokenIssuerPort.issue("acc-5"), null);

        assertThat(forbidden.status()).isEqualTo(403);
        assertThat(forbidden.body()).contains("\"title\":\"Forbidden\"").doesNotContain("org-x").doesNotContain("acc-4");
        assertThat(broken.status()).isEqualTo(500);
        assertThat(broken.body()).contains("\"correlationId\"").doesNotContain("internal detail").doesNotContain("acc-5");
    }
}
