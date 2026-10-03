package com.traceability.convocatoria.infrastructure.persistence.mongo.adapter;

import com.traceability.convocatoria.application.audit.ConvocatoriaAuditAction;
import com.traceability.convocatoria.application.audit.ConvocatoriaAuditEntry;
import com.traceability.convocatoria.application.port.out.ConvocatoriaAuditLogPort;
import com.traceability.convocatoria.infrastructure.persistence.mongo.document.ConvocatoriaAuditLogDocument;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Adaptador Mongo del audit log append-only (ADR-037 §6): solo inserta, nunca actualiza ni borra.
 * {@code sequence} es monotónica dentro del proceso y fija el orden de lectura.
 */
@Component
public class MongoConvocatoriaAuditLogAdapter implements ConvocatoriaAuditLogPort {

    private final MongoTemplate mongoTemplate;
    private final AtomicLong sequence = new AtomicLong(System.currentTimeMillis() * 1000);

    public MongoConvocatoriaAuditLogAdapter(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public void append(ConvocatoriaAuditEntry entry) {
        ConvocatoriaAuditLogDocument d = new ConvocatoriaAuditLogDocument();
        d.entryId = entry.entryId();
        d.action = entry.action().name();
        d.campaignRef = entry.campaignRef();
        d.actorRef = entry.actorRef();
        d.targetRef = entry.targetRef();
        d.selfAssigned = entry.selfAssigned();
        d.commandId = entry.commandId();
        d.occurredAt = entry.occurredAt();
        d.sequence = sequence.incrementAndGet();
        d.details = entry.details();
        mongoTemplate.insert(d);
    }

    @Override
    public List<ConvocatoriaAuditEntry> findByCampaignRef(String campaignRef) {
        Query query = Query.query(Criteria.where("campaignRef").is(campaignRef)).with(Sort.by("sequence"));
        return mongoTemplate.find(query, ConvocatoriaAuditLogDocument.class).stream()
                .map(d -> new ConvocatoriaAuditEntry(d.entryId, ConvocatoriaAuditAction.valueOf(d.action),
                        d.campaignRef, d.actorRef, d.targetRef, d.selfAssigned, d.commandId, d.occurredAt, d.details))
                .toList();
    }
}
