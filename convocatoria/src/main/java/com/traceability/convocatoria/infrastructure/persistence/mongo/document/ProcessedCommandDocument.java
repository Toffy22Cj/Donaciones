package com.traceability.convocatoria.infrastructure.persistence.mongo.document;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Map;

/**
 * Registro de comandos procesados del módulo (Enmienda §3.5, N12; I1): {@code _id = commandId}, tipo de comando
 * y resultado original en el mismo documento. Colección propia, distinta de {@code processed_commands} de {@code core}.
 */
@Document(collection = ProcessedCommandDocument.COLLECTION)
public class ProcessedCommandDocument {

    public static final String COLLECTION = "convocatoria_processed_commands";

    @Id
    public String commandId;
    public String commandType;
    public Map<String, String> result;
    public Instant processedAt;
}
