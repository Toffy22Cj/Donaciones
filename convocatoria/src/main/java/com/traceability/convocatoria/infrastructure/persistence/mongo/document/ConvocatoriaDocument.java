package com.traceability.convocatoria.infrastructure.persistence.mongo.document;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Set;

/**
 * Documento de {@code Convocatoria} (implementation_plan.md §4.2). {@code _id = campaignRef} (único);
 * {@code publicCode} único (ADR-037 §2.1). Ficha R-1 §6: un índice por cada orden del descubrimiento; el orden por
 * defecto sigue usando {@code uq_public_code}.
 */
@Document(collection = ConvocatoriaDocument.COLLECTION)
@CompoundIndexes({
        @CompoundIndex(name = ConvocatoriaDocument.DISCOVERY_ENDING_SOON_INDEX,
                def = "{'visibility': 1, 'status': 1, 'endDate': 1, 'publicCode': 1}"),
        @CompoundIndex(name = ConvocatoriaDocument.DISCOVERY_RECENT_INDEX,
                def = "{'visibility': 1, 'status': 1, 'startDate': -1, 'publicCode': 1}")
})
public class ConvocatoriaDocument {

    public static final String COLLECTION = "convocatorias";
    public static final String DISCOVERY_ENDING_SOON_INDEX = "idx_discovery_ending_soon";
    public static final String DISCOVERY_RECENT_INDEX = "idx_discovery_recent";

    @Id
    public String campaignRef;
    public String organizationRef;
    @Indexed(name = "uq_public_code", unique = true)
    public String publicCode;
    public String title;
    /**
     * Ficha R-1 §4: el título normalizado ({@code SearchTextNormalizer}) para buscar sin mayúsculas ni tildes. No es
     * dominio: lo escribe el mapeador al crear ({@code title} es inmutable) y, en las existentes, el relleno al arrancar.
     */
    public String titleSearch;
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
    /** ADR-051, Tarea 3: la {@code objectKey} de la portada; ausente si no hay. */
    public String coverObjectKey;
}
