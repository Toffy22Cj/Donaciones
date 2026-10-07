package com.traceability.api.auth;

import java.util.List;

/** Skeleton B3. */
public final class PublicRoutes {

    public enum Access { PUBLIC, OPTIONAL_JWT }

    public record Route(String method, String pattern, Access access) {}

    public static final List<Route> ROUTES = List.of();

    private PublicRoutes() {}

    public static java.util.Optional<Access> accessFor(String method, String path) {
        return java.util.Optional.empty();
    }
}
