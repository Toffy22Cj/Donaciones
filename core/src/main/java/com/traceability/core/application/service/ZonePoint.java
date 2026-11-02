package com.traceability.core.application.service;

import java.util.Optional;

/**
 * ADR-052 D1: el punto representativo de una zona pública (WGS84, grados decimales), nunca el de un lugar exacto.
 */
public record ZonePoint(double latitude, double longitude) {

    public ZonePoint {
        if (!isValid(latitude, longitude)) {
            throw new IllegalArgumentException("Coordinates out of range");
        }
    }

    /** Los dos valores, finitos y en rango; si no, ningún punto (nunca uno inventado). */
    public static Optional<ZonePoint> of(Double latitude, Double longitude) {
        if (latitude == null || longitude == null || !isValid(latitude, longitude)) {
            return Optional.empty();
        }
        return Optional.of(new ZonePoint(latitude, longitude));
    }

    static boolean isValid(double latitude, double longitude) {
        return Double.isFinite(latitude) && Double.isFinite(longitude)
                && latitude >= -90 && latitude <= 90 && longitude >= -180 && longitude <= 180;
    }
}
