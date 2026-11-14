package com.traceability.api.web.ratelimit;

import java.util.List;
import java.util.Optional;

import org.springframework.util.AntPathMatcher;

/**
 * ADR-053 D1: las superficies con límite de intentos, la única lista. Cada grupo tiene su cuota y su ventana en
 * {@code traceability.rate-limit.groups.<clave>} ({@link #configKey()}); sin ellas la aplicación no arranca. Una ruta
 * que no está aquí no tiene límite.
 */
public enum RateLimitGroup {

    LOGIN("login", List.of(route("POST", "/api/v1/auth/login"), route("POST", "/api/v1/auth/google"))),
    REGISTRATION("registration", List.of(route("POST", "/api/v1/auth/register"),
            route("POST", "/api/v1/auth/google/register"))),
    ACCOUNT_RECOVERY("account-recovery", List.of(route("POST", "/api/v1/auth/password-reset"),
            route("POST", "/api/v1/auth/password-reset/confirm"),
            route("POST", "/api/v1/auth/email-verification"),
            route("POST", "/api/v1/auth/email-verification/confirm"))),
    /** Adivinar trackingCode (ADR-041). */
    TRACKING("tracking", List.of(route("*", "/api/v1/donations/tracking/**"))),
    /** Adivinar Intent-Token (Enmienda 3 de ADR-037, D6). */
    DONATION_INTENT_READ("donation-intent-read", List.of(route("GET", "/api/v1/public/donation-intents/*"))),
    DONATION_INTENT_CREATE("donation-intent-create",
            List.of(route("POST", "/api/v1/public/campaigns/*/donation-intents"))),
    PAYMENT_WEBHOOK("payment-webhook", List.of(route("POST", "/api/v1/webhooks/payments"))),
    /** Subidas de fotos (ADR-051): logo, portada y evidencia de entrega. */
    MEDIA_UPLOAD("media-upload", List.of(route("PUT", "/api/v1/organizations/*/logo"),
            route("PUT", "/api/v1/organizations/*/campaigns/*/cover"),
            route("POST", "/api/v1/organizations/*/delivery-evidence")));

    record Route(String method, String pattern) {}

    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    private final String configKey;
    private final List<Route> routes;

    RateLimitGroup(String configKey, List<Route> routes) {
        this.configKey = configKey;
        this.routes = routes;
    }

    private static Route route(String method, String pattern) {
        return new Route(method, pattern);
    }

    public String configKey() {
        return configKey;
    }

    List<Route> routes() {
        return routes;
    }

    /**
     * @param path ruta ya normalizada por {@link RateLimitFilter#normalizedPath}: decodificada y sin {@code ;} ni
     *             {@code .}/{@code ..} (los quita el contenedor) y con las barras repetidas reducidas a una
     */
    public static Optional<RateLimitGroup> of(String method, String path) {
        for (RateLimitGroup group : values()) {
            for (Route r : group.routes) {
                if (("*".equals(r.method) || r.method.equalsIgnoreCase(method)) && MATCHER.match(r.pattern, path)) {
                    return Optional.of(group);
                }
            }
        }
        return Optional.empty();
    }
}
