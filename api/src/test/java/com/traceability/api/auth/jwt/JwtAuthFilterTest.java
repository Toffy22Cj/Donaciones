package com.traceability.api.auth.jwt;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.contracts.authorization.AuthorizationPrincipal;
import com.traceability.contracts.authorization.IdentityPrincipalPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.traceability.api.auth.jwt.JwtTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Filtro JWT *deny-by-default* (plan B3 §2.3 y §2.3.1, condición de Q1): cualquier fallo es el mismo 401, las rutas
 * públicas de la lista explícita no pasan por el JWT y la ruta de JWT opcional nunca degrada un token inválido a
 * anónimo.
 */
@ExtendWith(OutputCaptureExtension.class)
class JwtAuthFilterTest {

    @RestController
    static class EchoController {
        @RequestMapping("/**")
        String echo(@RequestAttribute(name = JwtAuthFilter.PRINCIPAL_ATTRIBUTE, required = false) AuthorizationPrincipal principal) {
            return principal == null ? "anonymous" : principal.accountId();
        }
    }

    private IdentityPrincipalPort identity;
    private MockMvc mvc;
    private Level previousLevel;

    @BeforeEach
    void setUp() throws Exception {
        identity = mock(IdentityPrincipalPort.class);
        when(identity.resolvePrincipal(anyString()))
                .thenAnswer(inv -> new AuthorizationPrincipal(inv.getArgument(0), null, Set.of(), null));
        JwtTokenVerifier verifier = new JwtTokenVerifier(currentOnly(clockAt(NOW)), clockAt(NOW));
        mvc = MockMvcBuilders.standaloneSetup(new EchoController())
                .addFilters(new JwtAuthFilter(verifier, identity, new ObjectMapper()))
                .build();
        Logger logger = (Logger) LoggerFactory.getLogger("com.traceability.api.auth");
        previousLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
    }

    @AfterEach
    void restoreLogLevel() {
        ((Logger) LoggerFactory.getLogger("com.traceability.api.auth")).setLevel(previousLevel);
    }

    private static String valid() throws Exception {
        return hs256(CURRENT_KID, CURRENT_SECRET, validClaims());
    }

    private MvcResult perform(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request).andReturn();
    }

    private static MockHttpServletRequestBuilder bearer(MockHttpServletRequestBuilder r, String token) {
        return r.header("Authorization", "Bearer " + token);
    }

    @Test
    void aValidToken_reachesTheController_withThePrincipal() throws Exception {
        MvcResult r = perform(bearer(get("/api/v1/accounts/me"), valid()));

        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(r.getResponse().getContentAsString()).isEqualTo("acc-1");
    }

    @Test
    void everyFailure_isTheSame401_byteForByte() throws Exception {
        when(identity.resolvePrincipal("inactive")).thenThrow(new IllegalStateException("Account is inactive: inactive"));
        when(identity.resolvePrincipal("ghost")).thenThrow(new IllegalArgumentException("Account not found: ghost"));

        List<MockHttpServletRequestBuilder> failures = new ArrayList<>();
        failures.add(get("/api/v1/accounts/me"));
        failures.add(get("/api/v1/accounts/me").header("Authorization", "Basic dXNlcjpwYXNz"));
        failures.add(get("/api/v1/accounts/me").header("Authorization", "Bearer "));
        failures.add(bearer(get("/api/v1/accounts/me"), "garbage"));
        failures.add(bearer(get("/api/v1/accounts/me"), algNone(validClaims())));
        failures.add(bearer(get("/api/v1/accounts/me"), hs512(CURRENT_KID, validClaims())));
        failures.add(bearer(get("/api/v1/accounts/me"), hs256("unknown", CURRENT_SECRET, validClaims())));
        failures.add(bearer(get("/api/v1/accounts/me"), hs256(CURRENT_KID, PREVIOUS_SECRET, validClaims())));
        failures.add(bearer(get("/api/v1/accounts/me"), hs256(CURRENT_KID, CURRENT_SECRET, claims("acc-1", NOW.minus(Duration.ofHours(9)), NOW.minus(Duration.ofHours(1))))));
        failures.add(bearer(get("/api/v1/accounts/me"), hs256(CURRENT_KID, CURRENT_SECRET, claims("inactive", NOW, NOW.plusSeconds(60)))));
        failures.add(bearer(get("/api/v1/accounts/me"), hs256(CURRENT_KID, CURRENT_SECRET, claims("ghost", NOW, NOW.plusSeconds(60)))));

        MvcResult first = perform(failures.get(0));
        assertThat(first.getResponse().getStatus()).isEqualTo(401);
        assertThat(first.getResponse().getContentType()).isEqualTo("application/problem+json");
        for (MockHttpServletRequestBuilder f : failures) {
            MvcResult r = perform(f);
            assertThat(r.getResponse().getStatus()).isEqualTo(401);
            assertThat(r.getResponse().getContentAsByteArray()).isEqualTo(first.getResponse().getContentAsByteArray());
        }
        assertThat(first.getResponse().getContentAsString())
                .doesNotContain("inactive").doesNotContain("kid").doesNotContain("expired").doesNotContain("signature");
    }

    @Test
    void aDataAccessFailureWhileResolvingThePrincipal_isNotDisguisedAs401() throws Exception {
        when(identity.resolvePrincipal("acc-1")).thenThrow(new DataAccessResourceFailureException("mongo down"));

        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() -> perform(bearer(get("/api/v1/accounts/me"), valid())));
        boolean found = false;
        for (Throwable t = thrown; t != null; t = t.getCause()) {
            found |= t instanceof DataAccessResourceFailureException;
        }
        assertThat(found).as("la excepción de acceso a datos se propaga: %s", thrown).isTrue();
    }

    @Test
    void theExplicitPublicRoutes_doNotRequireAToken() throws Exception {
        for (MockHttpServletRequestBuilder r : List.of(
                get("/api/v1/donations/tracking"),
                get("/api/v1/donations/tracking/assets/a-1/history"),
                get("/api/v1/donations/tracking/narrative"),
                post("/api/v1/auth/login"),
                post("/api/v1/auth/register"),
                get("/api/v1/public/campaigns"),
                get("/api/v1/public/campaigns/CV-ABC123"),
                get("/api/v1/public/campaigns/CV-ABC123/narrative"),
                post("/api/v1/public/campaigns/CV-ABC123/donation-intents"),
                post("/api/v1/webhooks/payments"))) {
            MvcResult result = perform(r);
            assertThat(result.getResponse().getStatus()).as(result.getRequest().getRequestURI()).isEqualTo(200);
            assertThat(result.getResponse().getContentAsString()).isEqualTo("anonymous");
        }
    }

    @Test
    void publicMeansThatMethodAndPath_only() throws Exception {
        assertThat(perform(post("/api/v1/public/campaigns/CV-ABC123")).getResponse().getStatus()).isEqualTo(401);
        assertThat(perform(get("/api/v1/auth/login")).getResponse().getStatus()).isEqualTo(401);
        assertThat(perform(get("/api/v1/public/campaigns/CV-ABC123/donation-intents/x")).getResponse().getStatus()).isEqualTo(401);
        assertThat(perform(get("/api/v1/webhooks/payments")).getResponse().getStatus()).isEqualTo(401);
        assertThat(perform(get("/api/v1/public/campaigns/CV-ABC123/anything-else")).getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void pathTricks_neverTurnAProtectedRouteIntoAPublicOne() throws Exception {
        for (String uri : List.of(
                "/api/v1/donations/tracking/../../accounts/me",
                "/api/v1/public/campaigns/x/..%2f..%2f..%2faccounts",
                "/api/v1/auth/login;jsessionid=x",
                "/api/v1//public/campaigns",
                "/api/v1/public/campaigns/%2e%2e")) {
            // URI en bruto: MockMvc normalizaría la plantilla antes de llegar al filtro
            MockHttpServletRequestBuilder r = get("/placeholder").with(req -> {
                req.setRequestURI(uri);
                return req;
            });
            assertThat(perform(r).getResponse().getStatus()).as(uri).isEqualTo(401);
        }
    }

    @Test
    void optionalJwt_withoutHeader_isAnonymous_withAnInvalidToken_is401_andWithAValidToken_hasThePrincipal() throws Exception {
        String intents = "/api/v1/public/campaigns/CV-ABC123/donation-intents";

        assertThat(perform(post(intents)).getResponse().getContentAsString()).isEqualTo("anonymous");
        assertThat(perform(bearer(post(intents), "garbage")).getResponse().getStatus()).isEqualTo(401);
        assertThat(perform(post(intents).header("Authorization", "Basic x")).getResponse().getStatus()).isEqualTo(401);
        assertThat(perform(bearer(post(intents), valid())).getResponse().getContentAsString()).isEqualTo("acc-1");
    }

    @Test
    void pathsOutsideApiV1_areNotFiltered() throws Exception {
        assertThat(perform(get("/actuator/health")).getResponse().getStatus()).isEqualTo(200);
    }

    // 9 (parte del filtro)
    @Test
    void neitherTheTokenNorTheSecret_appearInTheLogs(CapturedOutput output) throws Exception {
        String good = valid();
        String forged = hs256(CURRENT_KID, PREVIOUS_SECRET, validClaims());
        perform(bearer(get("/api/v1/accounts/me"), good));
        perform(bearer(get("/api/v1/accounts/me"), forged));
        perform(bearer(get("/api/v1/accounts/me"), hs256("unknown-kid", CURRENT_SECRET, validClaims())));

        String logs = output.getAll();
        assertThat(logs).contains("SIGNATURE");   // el motivo interno sí se registra, en DEBUG
        for (String token : List.of(good, forged)) {
            for (String part : token.split("\\.")) {
                assertThat(logs).doesNotContain(part);
            }
        }
        assertThat(logs).doesNotContain(CURRENT_SECRET).doesNotContain(PREVIOUS_SECRET).doesNotContain("Bearer");
    }
}
