package com.traceability.core.application.service;

import com.traceability.core.infrastructure.projection.mongo.documents.LocationReferenceDocument;
import com.traceability.core.infrastructure.projection.mongo.repositories.LocationReferenceRepository;
import org.springframework.stereotype.Service;

import java.util.Optional;

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
}
