package com.traceability.api.application.dto;

import com.traceability.core.application.service.ZonePoint;

/**
 * ADR-052 D2: el punto de la zona pública de un lote o de un paso (WGS84, grados decimales). Nunca el de un lugar
 * exacto (D1).
 */
public record PublicZonePointDTO(double latitude, double longitude) {

    /** {@code null} sin punto: el campo falta en el JSON. */
    public static PublicZonePointDTO from(ZonePoint point) {
        return point == null ? null : new PublicZonePointDTO(point.latitude(), point.longitude());
    }
}
