package com.traceability.core.infrastructure.projection.mongo.documents;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Fase 3, tarea 3.6: {@code rawLocation → zoneName}. ADR-052 D1: {@code latitude} y {@code longitude} opcionales, el
 * punto de la zona pública, nunca el del lugar exacto.
 */
@Document(collection = "location_reference")
public class LocationReferenceDocument {

    @Id
    private String rawLocation;
    private String zoneName;
    private Double latitude;
    private Double longitude;

    public LocationReferenceDocument() {
    }

    public LocationReferenceDocument(String rawLocation, String zoneName) {
        this(rawLocation, zoneName, null, null);
    }

    public LocationReferenceDocument(String rawLocation, String zoneName, Double latitude, Double longitude) {
        this.rawLocation = rawLocation;
        this.zoneName = zoneName;
        this.latitude = latitude;
        this.longitude = longitude;
    }

    public String getRawLocation() {
        return rawLocation;
    }

    public void setRawLocation(String rawLocation) {
        this.rawLocation = rawLocation;
    }

    public String getZoneName() {
        return zoneName;
    }

    public void setZoneName(String zoneName) {
        this.zoneName = zoneName;
    }

    public Double getLatitude() {
        return latitude;
    }

    public void setLatitude(Double latitude) {
        this.latitude = latitude;
    }

    public Double getLongitude() {
        return longitude;
    }

    public void setLongitude(Double longitude) {
        this.longitude = longitude;
    }
}
