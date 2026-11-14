package com.traceability.api.web.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** ADR-053 D3: la IP de la conexión, salvo detrás de un proxy de confianza; X-Forwarded-For nunca cuenta. */
class ClientIpResolverTest {

    private static MockHttpServletRequest from(String remote) {
        MockHttpServletRequest r = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        r.setRemoteAddr(remote);
        return r;
    }

    @Test
    void withoutTrustedProxiesHeadersAreIgnored() {
        ClientIpResolver resolver = new ClientIpResolver(List.of(), "CF-Connecting-IP");
        MockHttpServletRequest r = from("203.0.113.7");
        r.addHeader("X-Forwarded-For", "198.51.100.1");
        r.addHeader("CF-Connecting-IP", "198.51.100.2");
        assertThat(resolver.resolve(r)).isEqualTo("203.0.113.7");
    }

    @Test
    void xForwardedForIsNeverReadEvenFromATrustedProxy() {
        ClientIpResolver resolver = new ClientIpResolver(List.of("127.0.0.1"), "CF-Connecting-IP");
        MockHttpServletRequest r = from("127.0.0.1");
        r.addHeader("X-Forwarded-For", "198.51.100.1");
        assertThat(resolver.resolve(r)).isEqualTo("127.0.0.1");
    }

    @Test
    void trustedProxyHeaderIsUsed() {
        ClientIpResolver resolver = new ClientIpResolver(List.of("127.0.0.1", "10.0.0.0/8"), "CF-Connecting-IP");
        MockHttpServletRequest r = from("10.20.30.40");
        r.addHeader("CF-Connecting-IP", "198.51.100.2");
        assertThat(resolver.resolve(r)).isEqualTo("198.51.100.2");
    }

    @Test
    void untrustedConnectionCannotSpoofTheHeader() {
        ClientIpResolver resolver = new ClientIpResolver(List.of("127.0.0.1"), "CF-Connecting-IP");
        MockHttpServletRequest r = from("203.0.113.7");
        r.addHeader("CF-Connecting-IP", "198.51.100.2");
        assertThat(resolver.resolve(r)).isEqualTo("203.0.113.7");
    }

    @Test
    void malformedOrListHeaderFallsBackToTheConnection() {
        ClientIpResolver resolver = new ClientIpResolver(List.of("127.0.0.1"), "CF-Connecting-IP");
        for (String bad : List.of("198.51.100.2, 1.1.1.1", "evil.example", "999.1.1.1", "", "1.2.3")) {
            MockHttpServletRequest r = from("127.0.0.1");
            r.addHeader("CF-Connecting-IP", bad);
            assertThat(resolver.resolve(r)).as(bad).isEqualTo("127.0.0.1");
        }
    }

    @Test
    void ipv6TrustedRange() {
        ClientIpResolver resolver = new ClientIpResolver(List.of("::1", "fd00::/8"), "CF-Connecting-IP");
        MockHttpServletRequest r = from("fd12::5");
        r.addHeader("CF-Connecting-IP", "2001:db8::1");
        assertThat(resolver.resolve(r)).isEqualTo("2001:db8::1");
    }

    @Test
    void invalidTrustedProxyConfigurationPreventsStartup() {
        assertThatThrownBy(() -> new ClientIpResolver(List.of("proxy.local"), "CF-Connecting-IP"))
                .isInstanceOf(RateLimitConfigurationException.class);
        assertThatThrownBy(() -> new ClientIpResolver(List.of("10.0.0.0/33"), "CF-Connecting-IP"))
                .isInstanceOf(RateLimitConfigurationException.class);
        assertThatThrownBy(() -> new ClientIpResolver(List.of("127.0.0.1"), " "))
                .isInstanceOf(RateLimitConfigurationException.class);
    }
}
