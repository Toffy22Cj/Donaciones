package com.traceability.api.web.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.util.StreamUtils;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** ADR-053: el filtro (grupos, IP, correo del login, 429 fijo con Retry-After) sin contenedor. */
class RateLimitFilterTest {

    private final ObjectMapper json = new ObjectMapper();
    private final MutableClock clock = new MutableClock();

    private static RateLimitProperties.Quota quota(int limit, Duration window) {
        RateLimitProperties.Quota q = new RateLimitProperties.Quota();
        q.setLimit(limit);
        q.setWindow(window);
        return q;
    }

    private RateLimitProperties properties(int limit, int emailLimit) {
        RateLimitProperties p = new RateLimitProperties();
        for (RateLimitGroup g : RateLimitGroup.values()) {
            p.getGroups().put(g.configKey(), quota(limit, Duration.ofMinutes(1)));
        }
        p.setLoginEmail(quota(emailLimit, Duration.ofMinutes(15)));
        p.validate();
        return p;
    }

    private RateLimitFilter filter(RateLimitProperties p) {
        return new RateLimitFilter(p, new SlidingWindowRateLimiter(clock, 1000),
                new ClientIpResolver(List.of(), "CF-Connecting-IP"), json);
    }

    private static MockHttpServletRequest request(String method, String path, String ip) {
        MockHttpServletRequest r = new MockHttpServletRequest(method, path);
        r.setServletPath(path);
        r.setRemoteAddr(ip);
        return r;
    }

    private static MockHttpServletRequest login(String ip, String email) {
        MockHttpServletRequest r = request("POST", "/api/v1/auth/login", ip);
        r.setContentType("application/json");
        r.setContent(("{\"email\":\"" + email + "\",\"password\":\"x\"}").getBytes(StandardCharsets.UTF_8));
        return r;
    }

    /** Lo que el siguiente de la cadena (el controlador) llegó a leer. */
    private static final class Downstream extends HttpServlet {
        int calls;
        String body;

        @Override
        protected void service(HttpServletRequest req, HttpServletResponse resp) throws java.io.IOException {
            calls++;
            body = StreamUtils.copyToString(req.getInputStream(), StandardCharsets.UTF_8);
            resp.setStatus(401);
        }
    }

    private MockHttpServletResponse run(RateLimitFilter f, MockHttpServletRequest r, Downstream d) throws Exception {
        MockHttpServletResponse resp = new MockHttpServletResponse();
        f.doFilter(r, resp, new MockFilterChain(d));
        return resp;
    }

    @Test
    void nPlusOneLoginAttemptsGive429WithRetryAfterAndTheFixedBody() throws Exception {
        RateLimitFilter f = filter(properties(3, 100));
        Downstream d = new Downstream();
        for (int i = 0; i < 3; i++) {
            assertThat(run(f, login("203.0.113.1", "a" + i + "@x.org"), d).getStatus()).isEqualTo(401);
        }
        MockHttpServletResponse fourth = run(f, login("203.0.113.1", "other@x.org"), d);
        assertThat(fourth.getStatus()).isEqualTo(429);
        assertThat(fourth.getHeader("Retry-After")).isEqualTo("60");
        assertThat(fourth.getContentType()).isEqualTo("application/problem+json");
        assertThat(fourth.getContentAsByteArray()).isEqualTo(RateLimitFilter.tooManyRequestsBody(json));
        @SuppressWarnings("unchecked")
        Map<String, Object> body = json.readValue(fourth.getContentAsByteArray(), Map.class);
        assertThat(body).containsEntry("status", 429).containsEntry("title", "Too Many Requests");
        assertThat(d.calls).as("the 4th never reaches the controller").isEqualTo(3);
    }

    @Test
    void theLimitExpiresWithTheWindow() throws Exception {
        RateLimitFilter f = filter(properties(1, 100));
        Downstream d = new Downstream();
        run(f, login("203.0.113.1", "a@x.org"), d);
        assertThat(run(f, login("203.0.113.1", "a@x.org"), d).getStatus()).isEqualTo(429);
        clock.advance(Duration.ofMinutes(1));
        assertThat(run(f, login("203.0.113.1", "a@x.org"), d).getStatus()).isEqualTo(401);
    }

    @Test
    void sameAccountFromManyIpsIsLimitedByNormalizedEmail() throws Exception {
        RateLimitFilter f = filter(properties(100, 2));
        Downstream d = new Downstream();
        assertThat(run(f, login("198.51.100.1", "Victim@Example.org"), d).getStatus()).isEqualTo(401);
        assertThat(run(f, login("198.51.100.2", "  victim@example.ORG "), d).getStatus()).isEqualTo(401);
        MockHttpServletResponse third = run(f, login("198.51.100.3", "victim@example.org"), d);
        assertThat(third.getStatus()).isEqualTo(429);
        assertThat(third.getHeader("Retry-After")).isEqualTo("900");
        // otra cuenta desde la misma IP sigue pasando
        assertThat(run(f, login("198.51.100.3", "someone@example.org"), d).getStatus()).isEqualTo(401);
    }

    @Test
    void theControllerStillReadsTheWholeLoginBody() throws Exception {
        RateLimitFilter f = filter(properties(10, 10));
        Downstream d = new Downstream();
        run(f, login("203.0.113.1", "a@x.org"), d);
        assertThat(d.body).isEqualTo("{\"email\":\"a@x.org\",\"password\":\"x\"}");
    }

    @Test
    void anOversizedLoginBodyIsLimitedByIpOnlyAndPassedThroughIntact() throws Exception {
        RateLimitFilter f = filter(properties(10, 1));
        Downstream d = new Downstream();
        String padding = "x".repeat(RateLimitFilter.MAX_LOGIN_BODY_BYTES);
        String big = "{\"email\":\"a@x.org\",\"password\":\"" + padding + "\"}";
        for (int i = 0; i < 3; i++) {
            MockHttpServletRequest r = request("POST", "/api/v1/auth/login", "203.0.113.1");
            r.setContent(big.getBytes(StandardCharsets.UTF_8));
            assertThat(run(f, r, d).getStatus()).isEqualTo(401);
        }
        assertThat(d.body).isEqualTo(big);
    }

    @Test
    void eachClientIpHasItsOwnCounter() throws Exception {
        RateLimitFilter f = filter(properties(2, 100));
        Downstream d = new Downstream();
        run(f, request("POST", "/api/v1/auth/register", "203.0.113.1"), d);
        run(f, request("POST", "/api/v1/auth/register", "203.0.113.1"), d);
        assertThat(run(f, request("POST", "/api/v1/auth/register", "203.0.113.1"), d).getStatus()).isEqualTo(429);
        assertThat(run(f, request("POST", "/api/v1/auth/register", "203.0.113.2"), d).getStatus()).isEqualTo(401);
    }

    @Test
    void theXForwardedForHeaderDoesNotEvadeTheLimit() throws Exception {
        RateLimitFilter f = filter(properties(2, 100));
        Downstream d = new Downstream();
        for (int i = 0; i < 2; i++) {
            MockHttpServletRequest r = login("203.0.113.9", "u" + i + "@x.org");
            r.addHeader("X-Forwarded-For", "10.0.0." + i);
            run(f, r, d);
        }
        MockHttpServletRequest r = login("203.0.113.9", "u9@x.org");
        r.addHeader("X-Forwarded-For", "10.0.0.99");
        r.addHeader("CF-Connecting-IP", "10.0.0.98");
        assertThat(run(f, r, d).getStatus()).isEqualTo(429);
    }

    @Test
    void groupsHaveSeparateCountersAndUnlimitedRoutesPass() throws Exception {
        RateLimitFilter f = filter(properties(1, 100));
        Downstream d = new Downstream();
        assertThat(run(f, request("GET", "/api/v1/donations/tracking", "203.0.113.1"), d).getStatus()).isEqualTo(401);
        assertThat(run(f, request("GET", "/api/v1/donations/tracking/assets", "203.0.113.1"), d).getStatus())
                .isEqualTo(429);
        assertThat(run(f, request("GET", "/api/v1/public/donation-intents/i-1", "203.0.113.1"), d).getStatus())
                .isEqualTo(401);
        for (int i = 0; i < 5; i++) {
            assertThat(run(f, request("GET", "/api/v1/public/campaigns", "203.0.113.1"), d).getStatus())
                    .isEqualTo(401);
        }
    }

    @Test
    void repeatedSlashesDoNotEscapeTheGroup() throws Exception {
        RateLimitFilter f = filter(properties(1, 100));
        Downstream d = new Downstream();
        run(f, request("POST", "/api/v1/auth/register", "203.0.113.1"), d);
        assertThat(run(f, request("POST", "/api/v1//auth///register", "203.0.113.1"), d).getStatus()).isEqualTo(429);
    }

    @Test
    void uploadsAreLimitedBeforeAuthentication() throws Exception {
        RateLimitFilter f = filter(properties(1, 100));
        Downstream d = new Downstream();
        run(f, request("POST", "/api/v1/organizations/org-1/delivery-evidence", "203.0.113.1"), d);
        assertThat(run(f, request("PUT", "/api/v1/organizations/org-2/logo", "203.0.113.1"), d).getStatus())
                .isEqualTo(429);
    }

    @Test
    void disabledLetsEverythingThrough() throws Exception {
        RateLimitProperties p = properties(1, 1);
        p.setEnabled(false);
        RateLimitFilter f = filter(p);
        Downstream d = new Downstream();
        for (int i = 0; i < 5; i++) {
            assertThat(run(f, login("203.0.113.1", "a@x.org"), d).getStatus()).isEqualTo(401);
        }
    }
}
