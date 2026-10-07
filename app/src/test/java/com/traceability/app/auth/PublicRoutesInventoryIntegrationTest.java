package com.traceability.app.auth;

import com.traceability.api.auth.PublicRoutes;
import com.traceability.app.TraceabilityApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * Inventario de rutas (plan B3, condición de Q1): toda ruta bajo {@code /api/v1} es pública según
 * {@link PublicRoutes} o está en {@link #PROTECTED}. Una ruta nueva en ninguna de las dos hace fallar el build hasta
 * que alguien decida.
 */
@SpringBootTest(classes = TraceabilityApplication.class)
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
    "crypto.anchor.poll.delay=9999999",
    "crypto.anchor.submit.delay=9999999",
    "crypto.anchor.stuck-monitor.delay=9999999",
    "crypto.web3j.private-key=0x1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef",
    "crypto.web3j.node-url=http://dummy-node",
    "spring.ai.openai.api-key=dummy-api-key"
})
class PublicRoutesInventoryIntegrationTest {

    /** Rutas protegidas por JWT, decididas una a una ("MÉTODO /patrón"). */
    static final Set<String> PROTECTED = Set.of(
            // B6-a: convocatoria y verificación (plan-b6-a-convocatoria-http.md §2)
            "POST /api/v1/organizations/{organizationId}/campaigns",
            "POST /api/v1/campaigns/{campaignRef}/employees",
            "POST /api/v1/platform/organizations/{organizationId}/verify",
            // B6-c: activos y división (plan-b6-c-activos-http.md §2.2)
            "POST /api/v1/physical-assets/register",
            "POST /api/v1/physical-assets/from-donation",
            "POST /api/v1/physical-assets/{assetRef}/split",
            "GET /api/v1/physical-assets/{assetRef}/splits/{childAssetRef}",
            "POST /api/v1/physical-assets/{assetRef}/dispatch",
            "POST /api/v1/physical-assets/{assetRef}/receive",
            "POST /api/v1/physical-assets/{assetRef}/deliver",
            "GET /api/v1/physical-assets/{assetRef}",
            // B6-b: historial de la cuenta (ADR-048; plan-b6-b-donacion-pago-http.md §1.3)
            "GET /api/v1/account/donations",
            // P1.1: Camino A por HTTP (plan-p1-camino-a-http.md §2.2)
            "GET /api/v1/organizations/{organizationId}/funds",
            "POST /api/v1/funds/{fundId}/allocations",
            "POST /api/v1/funds/{fundId}/allocations/{allocationId}/confirm");

    @Container
    static MongoDBContainer mongo = new MongoDBContainer(DockerImageName.parse("mongo:6.0")).withCommand("--replSet", "rs0");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongo::getReplicaSetUrl);
    }

    @Autowired @Qualifier("requestMappingHandlerMapping") private RequestMappingHandlerMapping mappings;
    @Autowired private MockMvc mvc;

    record Endpoint(String method, String pattern) {
        String concretePath() {
            return pattern.replaceAll("\\{[^/]+}", "x").replace("**", "x");
        }
    }

    private List<Endpoint> apiEndpoints() {
        List<Endpoint> endpoints = new ArrayList<>();
        for (RequestMappingInfo info : mappings.getHandlerMethods().keySet()) {
            Set<String> methods = new TreeSet<>();
            info.getMethodsCondition().getMethods().forEach(m -> methods.add(m.name()));
            if (methods.isEmpty()) {
                for (RequestMethod m : RequestMethod.values()) methods.add(m.name());
            }
            for (String pattern : info.getPatternValues()) {
                if (pattern.startsWith("/api/v1/")) {
                    methods.forEach(m -> endpoints.add(new Endpoint(m, pattern)));
                }
            }
        }
        return endpoints;
    }

    @Test
    void everyApiRoute_isEitherExplicitlyPublicOrExplicitlyProtected() {
        List<Endpoint> endpoints = apiEndpoints();
        assertThat(endpoints).extracting(Endpoint::pattern)
                .contains("/api/v1/auth/login", "/api/v1/donations/tracking",
                        "/api/v1/donations/tracking/narrative", "/api/v1/donations/tracking/assets/{assetRef}/history");

        List<String> unclassified = endpoints.stream()
                .filter(e -> PublicRoutes.accessFor(e.method(), e.concretePath()).isEmpty())
                .map(e -> e.method() + " " + e.pattern())
                .filter(e -> !PROTECTED.contains(e))
                .toList();

        assertThat(unclassified).as("rutas sin decidir si son públicas o protegidas").isEmpty();
    }

    @Test
    void publicRoutes_areNotRejectedByTheJwtFilter_andProtectedOnesAre() throws Exception {
        byte[] jwt401 = mvc.perform(request(HttpMethod.GET, "/api/v1/unclassified-route")).andReturn()
                .getResponse().getContentAsByteArray();
        assertThat(new String(jwt401)).isNotEmpty();

        for (Endpoint e : apiEndpoints()) {
            byte[] body = mvc.perform(request(HttpMethod.valueOf(e.method()), e.concretePath())).andReturn()
                    .getResponse().getContentAsByteArray();
            if (PublicRoutes.accessFor(e.method(), e.concretePath()).isPresent()) {
                assertThat(body).as(e.method() + " " + e.pattern()).isNotEqualTo(jwt401);
            } else {
                assertThat(body).as(e.method() + " " + e.pattern()).isEqualTo(jwt401);
            }
        }
    }
}
