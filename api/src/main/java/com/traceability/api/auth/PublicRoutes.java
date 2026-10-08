package com.traceability.api.auth;

import org.springframework.util.AntPathMatcher;

import java.util.List;
import java.util.Optional;

/**
 * Lista explícita y completa de las rutas bajo {@code /api/v1} que no exigen JWT (plan B3 §2.3.1, condición de Q1).
 * Es la única fuente de verdad del filtro *deny-by-default*: todo lo que no está aquí exige un JWT válido.
 *
 * <p>Incluye rutas que aún no existen (registro, convocatoria pública, intención, narrativa, webhook) para que el
 * criterio 3 del golden path no falle cuando entren. Toda ruta pública nueva exige editar esta lista y la tabla del
 * plan en el mismo PR; el test de inventario de {@code app} falla ante cualquier ruta sin clasificar.
 */
public final class PublicRoutes {

    public enum Access {
        /** Sin JWT. Si la ruta tiene su propio mecanismo (seguimiento, firma del webhook), lo aplica su filtro o su controlador. */
        PUBLIC,
        /** Sin cabecera, anónimo. Con cabecera, se verifica como en una ruta protegida y un token inválido da 401. */
        OPTIONAL_JWT
    }

    /** {@code method} es un método HTTP o {@code *} (cualquiera). */
    public record Route(String method, String pattern, Access access) {}

    public static final List<Route> ROUTES = List.of(
            // ADR-041: el seguimiento tiene su propio mecanismo (TrackingCodeAuthFilter), nunca el JWT
            new Route("*", "/api/v1/donations/tracking/**", Access.PUBLIC),
            new Route("POST", "/api/v1/auth/login", Access.PUBLIC),
            new Route("POST", "/api/v1/auth/register", Access.PUBLIC),
            new Route("GET", "/api/v1/public/campaigns", Access.PUBLIC),
            new Route("GET", "/api/v1/public/campaigns/{publicCode}", Access.PUBLIC),
            new Route("POST", "/api/v1/public/campaigns/{publicCode}/donation-intents", Access.OPTIONAL_JWT),
            new Route("GET", "/api/v1/public/campaigns/{publicCode}/narrative", Access.PUBLIC),
            // Enmienda 3 de ADR-037, D6: el acceso lo da la cabecera Intent-Token, que valida el controlador (plan B6-b)
            new Route("GET", "/api/v1/public/donation-intents/{intentId}", Access.PUBLIC),
            // firma del proveedor (simulado en la demo), nunca JWT
            new Route("POST", "/api/v1/webhooks/payments", Access.PUBLIC));

    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    private PublicRoutes() {}

    /**
     * @param path ruta ya normalizada (sin {@code ..}, {@code //}, {@code ;} ni codificaciones de esos caracteres)
     * @return el acceso de la ruta, o vacío si está protegida
     */
    public static Optional<Access> accessFor(String method, String path) {
        for (Route route : ROUTES) {
            if (("*".equals(route.method()) || route.method().equalsIgnoreCase(method)) && MATCHER.match(route.pattern(), path)) {
                return Optional.of(route.access());
            }
        }
        return Optional.empty();
    }
}
