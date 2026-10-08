package com.traceability.convocatoria.application.query;

import com.traceability.convocatoria.application.audit.ConvocatoriaAuditAction;
import com.traceability.convocatoria.application.audit.ConvocatoriaAuditEntry;
import com.traceability.convocatoria.application.port.out.ConvocatoriaAuditLogPort;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Cuándo se cerró una convocatoria y cuándo cambió su configuración, según su registro de auditoría. Lo usan las
 * estimaciones históricas (encargo 6, P3) para no reconstruir un corte pasado con atributos que entonces no tenía.
 * Solo lectura.
 */
@Component
public class CampaignTimelineQuery {

    /** @param closedAt {@code null} si sigue abierta */
    public record CampaignTimeline(Instant closedAt, List<Instant> configurationChangedAt) {}

    private final ConvocatoriaAuditLogPort auditLog;

    public CampaignTimelineQuery(ConvocatoriaAuditLogPort auditLog) {
        this.auditLog = auditLog;
    }

    public CampaignTimeline timelineOf(String campaignRef) {
        List<ConvocatoriaAuditEntry> entries = auditLog.findByCampaignRef(campaignRef);
        Instant closedAt = entries.stream().filter(e -> e.action() == ConvocatoriaAuditAction.CONVOCATORIA_CLOSED)
                .map(ConvocatoriaAuditEntry::occurredAt).min(Instant::compareTo).orElse(null);
        List<Instant> changes = entries.stream()
                .filter(e -> e.action() == ConvocatoriaAuditAction.CONFIGURATION_EDITED
                        || e.action() == ConvocatoriaAuditAction.CONFIGURATION_CHANGE_APPROVED)
                .map(ConvocatoriaAuditEntry::occurredAt).sorted().toList();
        return new CampaignTimeline(closedAt, changes);
    }
}
