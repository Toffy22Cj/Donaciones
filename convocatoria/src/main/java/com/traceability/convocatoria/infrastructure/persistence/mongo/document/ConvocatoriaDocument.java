package com.traceability.convocatoria.infrastructure.persistence.mongo.document;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Set;

/**
 * Documento de {@code Convocatoria} (implementation_plan.md §4.2). {@code _id = campaignRef} (único);
 * {@code publicCode} único (ADR-037 §2.1).
 */
@Document(collection = ConvocatoriaDocument.COLLECTION)
public class ConvocatoriaDocument {

    public static final String COLLECTION = "convocatorias";

    @Id
    public String campaignRef;
    public String organizationRef;
    @Indexed(name = "uq_public_code", unique = true)
    public String publicCode;
    public String title;
    public String description;
    public String visibility;
    public Instant startDate;
    public Instant endDate;
    public String status;
    public Set<String> acceptedDonationTypes;
    public Set<String> acceptedPaymentMethods;
    public String currency;
    public Long targetAmount;
    public String targetPolicy;
    public String onTargetReached;
    public long configurationVersion;
}
