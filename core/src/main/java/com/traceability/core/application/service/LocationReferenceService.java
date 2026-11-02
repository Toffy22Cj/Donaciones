package com.traceability.core.application.service;

import com.traceability.core.application.exception.InvalidLocationReferenceException;
import com.traceability.core.infrastructure.projection.mongo.documents.LocationReferenceDocument;
import com.traceability.core.infrastructure.projection.mongo.repositories.LocationReferenceRepository;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Tabla determinista de lugar a zona pública (Fase 3, tarea 3.6): consulta exacta, sin heurística. ADR-052 añade el
 * punto de la zona ({@link #resolve}) y el alta sin ruta HTTP ({@link #register}).
 */
@Service
public class LocationReferenceService {

    private final LocationReferenceRepository repository;

    public LocationReferenceService(LocationReferenceRepository repository) {
        this.repository = repository;
    }

    public Optional<String> resolveZone(String rawLocation) {
        if (rawLocation == null) {
            return Optional.empty();
        }
        return repository.findById(rawLocation)
                .map(LocationReferenceDocument::getZoneName);
    }

    /**
     * ADR-052 D2: la zona y su punto, en una sola lectura. Sin coordenadas, con una sola o fuera de rango, la zona sale
     * sin punto.
     */
    public Optional<PublicZone> resolve(String rawLocation) {
        if (rawLocation == null) {
            return Optional.empty();
        }
        return repository.findById(rawLocation)
                .filter(d -> d.getZoneName() != null)
                .map(d -> new PublicZone(d.getZoneName(),
                        ZonePoint.of(d.getLatitude(), d.getLongitude()).orElse(null)));
    }

    /**
     * ADR-052 D4: guarda o sustituye la entrada. Las dos coordenadas van juntas o no van, y en rango (D1).
     *
     * @throws InvalidLocationReferenceException si la entrada no es válida; no se guarda nada
     */
    public void register(String rawLocation, String zoneName, Double latitude, Double longitude) {
        if (rawLocation == null || rawLocation.isBlank()) {
            throw new InvalidLocationReferenceException("rawLocation is required");
        }
        if (zoneName == null || zoneName.isBlank()) {
            throw new InvalidLocationReferenceException("zoneName is required");
        }
        if ((latitude == null) != (longitude == null)) {
            throw new InvalidLocationReferenceException("latitude and longitude go together");
        }
        if (latitude != null && ZonePoint.of(latitude, longitude).isEmpty()) {
            throw new InvalidLocationReferenceException("Coordinates out of range");
        }
        repository.save(new LocationReferenceDocument(rawLocation, zoneName.strip(), latitude, longitude));
    }
}
