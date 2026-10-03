package com.traceability.convocatoria.application.port.out;

import com.traceability.convocatoria.application.audit.ConvocatoriaAuditEntry;

import java.util.List;

/**
 * Audit log append-only del módulo, en la misma transacción que la escritura que registra (ADR-037 §2, §6).
 */
public interface ConvocatoriaAuditLogPort {

    void append(ConvocatoriaAuditEntry entry);

    /** Entradas de una convocatoria en orden de inserción. */
    List<ConvocatoriaAuditEntry> findByCampaignRef(String campaignRef);
}
