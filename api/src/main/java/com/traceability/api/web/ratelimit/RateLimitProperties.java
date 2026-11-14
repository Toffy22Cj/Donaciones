package com.traceability.api.web.ratelimit;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * ADR-053 D4: cuotas y ventanas del límite de intentos, solo por configuración ({@code traceability.rate-limit.*}),
 * con <em>fail-fast</em>: cada grupo de {@link RateLimitGroup} necesita su cuota, una clave de grupo desconocida (una
 * errata) impide arrancar, y el login necesita además la cuota por correo ({@code login-email}).
 */
@ConfigurationProperties(prefix = "traceability.rate-limit")
public class RateLimitProperties {

    public static class Quota {
        private int limit;
        private Duration window;

        public int getLimit() { return limit; }
        public void setLimit(int limit) { this.limit = limit; }
        public Duration getWindow() { return window; }
        public void setWindow(Duration window) { this.window = window; }
    }

    public static class ClientIp {
        private List<String> trustedProxies = new ArrayList<>();
        private String header = "CF-Connecting-IP";

        public List<String> getTrustedProxies() { return trustedProxies; }
        public void setTrustedProxies(List<String> trustedProxies) { this.trustedProxies = trustedProxies; }
        public String getHeader() { return header; }
        public void setHeader(String header) { this.header = header; }
    }

    private boolean enabled = true;
    private int maxKeys = 100_000;
    private Duration sweepInterval = Duration.ofMinutes(1);
    private Map<String, Quota> groups = new LinkedHashMap<>();
    private Quota loginEmail;
    private ClientIp clientIp = new ClientIp();

    /** Comprueba la configuración completa; la llama {@link RateLimitConfig} al arrancar. */
    void validate() {
        if (!enabled) {
            return;
        }
        if (maxKeys <= 0) {
            throw new RateLimitConfigurationException("traceability.rate-limit.max-keys must be positive");
        }
        if (sweepInterval == null || sweepInterval.isNegative() || sweepInterval.isZero()) {
            throw new RateLimitConfigurationException("traceability.rate-limit.sweep-interval must be positive");
        }
        Set<String> known = new TreeSet<>();
        for (RateLimitGroup group : RateLimitGroup.values()) {
            known.add(group.configKey());
            requireQuota("groups." + group.configKey(), groups.get(group.configKey()));
        }
        for (String key : groups.keySet()) {
            if (!known.contains(key)) {
                throw new RateLimitConfigurationException("unknown traceability.rate-limit.groups." + key
                        + " (known: " + known + ")");
            }
        }
        requireQuota("login-email", loginEmail);
    }

    private static void requireQuota(String name, Quota quota) {
        if (quota == null) {
            throw new RateLimitConfigurationException("traceability.rate-limit." + name + " must be configured");
        }
        if (quota.limit <= 0) {
            throw new RateLimitConfigurationException("traceability.rate-limit." + name + ".limit must be positive");
        }
        if (quota.window == null || quota.window.isNegative() || quota.window.isZero()) {
            throw new RateLimitConfigurationException("traceability.rate-limit." + name + ".window must be positive");
        }
    }

    Quota quotaOf(RateLimitGroup group) {
        return groups.get(group.configKey());
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getMaxKeys() { return maxKeys; }
    public void setMaxKeys(int maxKeys) { this.maxKeys = maxKeys; }
    public Duration getSweepInterval() { return sweepInterval; }
    public void setSweepInterval(Duration sweepInterval) { this.sweepInterval = sweepInterval; }
    public Map<String, Quota> getGroups() { return groups; }
    public void setGroups(Map<String, Quota> groups) { this.groups = groups; }
    public Quota getLoginEmail() { return loginEmail; }
    public void setLoginEmail(Quota loginEmail) { this.loginEmail = loginEmail; }
    public ClientIp getClientIp() { return clientIp; }
    public void setClientIp(ClientIp clientIp) { this.clientIp = clientIp; }
}
