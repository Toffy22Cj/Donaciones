package com.traceability.core.infrastructure.projection.mongo.documents;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "location_reference")
public class LocationReferenceDocument {

    @Id
    private String rawLocation;

    private String zoneName;

    public LocationReferenceDocument() {
    }

    public LocationReferenceDocument(String rawLocation, String zoneName) {
        this.rawLocation = rawLocation;
        this.zoneName = zoneName;
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
}
