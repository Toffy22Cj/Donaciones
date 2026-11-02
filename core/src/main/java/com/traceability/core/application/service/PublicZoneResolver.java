package com.traceability.core.application.service;

import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * [Plan] La zona pública de un paso del seguimiento (enmienda 2 de ADR-029, D5): la zona sellada en el evento, si la
 * tiene; si no (eventos anteriores a la enmienda, o un despacho o una división de un activo anterior, E-1), el respaldo
 * de {@code location_reference} con el texto libre del mismo evento, como hasta ahora. Nunca el catálogo actual.
 */
@Service
public class PublicZoneResolver {

    private final LocationReferenceService locations;

    public PublicZoneResolver(LocationReferenceService locations) {
        this.locations = locations;
    }

    public Optional<String> resolve(String sealedZoneName, String rawLocation) {
        if (sealedZoneName != null) {
            return Optional.of(sealedZoneName);
        }
        return locations.resolveZone(rawLocation);
    }
}
