package com.traceability.app.web.location;

import identity.application.port.out.LocationCatalogPort;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/**
 * [Plan] {@code GET /api/v1/public/locations} (ficha R-7 §4 y §6), pública: el catálogo DIVIPOLA para los dos
 * desplegables de la web, {@code {catalogVersion, departments: [{code, name, municipalities: [{code, name}]}]}}, con
 * {@code Cache-Control: public, max-age=86400}.
 */
@RestController
public class PublicLocationsController {

    private final LocationCatalogPort locations;

    public PublicLocationsController(LocationCatalogPort locations) {
        this.locations = locations;
    }

    @GetMapping("/api/v1/public/locations")
    public ResponseEntity<LocationCatalogPort.Catalog> catalog() {
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePublic())
                .body(locations.catalog());
    }
}
