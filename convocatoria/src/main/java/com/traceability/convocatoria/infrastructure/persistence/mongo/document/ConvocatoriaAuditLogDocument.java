package com.traceability.convocatoria.infrastructure.persistence.mongo.document;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Map;

/**
 * Audit log append-only del módulo (ADR-037 §6). {@code sequence} fija el orden de inserción dentro de un proceso.
 */
@Document(collection = ConvocatoriaAuditLogDocument.COLLECTION)
public class ConvocatoriaAuditLogDocument {

    public static final String COLLECTION = "convocatoria_audit_log";

    @Id
    public String entryId;
    public String action;
    public String campaignRef;
    public String actorRef;
    public String targetRef;
    public boolean selfAssigned;
    public String commandId;
    public Instant occurredAt;
    public long sequence;
    public Map<String, String> details;
}
