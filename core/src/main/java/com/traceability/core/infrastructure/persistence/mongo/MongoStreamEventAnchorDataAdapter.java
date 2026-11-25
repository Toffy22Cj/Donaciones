package com.traceability.core.infrastructure.persistence.mongo;

import com.traceability.core.application.port.out.StreamEventAnchorDataPort;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Una consulta {@code {streamId}} ordenada por {@code sequence}, cubierta por {@code idx_stream_sequence}, que solo
 * proyecta {@code sequence}, {@code eventHash} y {@code merkleBatchId} (plan del Bloque C, versión 2, §2.3 (2)). Solo
 * lee el event store; no cambia ningún evento.
 */
@Component
public class MongoStreamEventAnchorDataAdapter implements StreamEventAnchorDataPort {

    private final MongoTemplate mongoTemplate;

    public MongoStreamEventAnchorDataAdapter(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public List<StreamEventAnchorData> findByStream(String streamId) {
        Query query = new Query(Criteria.where("streamId").is(streamId)).with(Sort.by("sequence"));
        query.fields().include("sequence").include("eventHash").include("merkleBatchId").include("eventType")
                .include("payload.evidenceRef");
        return mongoTemplate.find(query, TraceabilityEventDocument.class).stream()
                .map(e -> new StreamEventAnchorData(e.getSequence(), e.getEventHash(), e.getMerkleBatchId(),
                        evidenceRefOf(e)))
                .toList();
    }

    /** Los eventos que pueden llevar foto: registrar, despachar, recibir y entregar (ADR-051 Enmienda 2; ADR-021-D Enm. 2, E8). */
    private static final java.util.Set<String> PHOTO_EVENTS = java.util.Set.of(
            "ASSET_REGISTERED", "ASSET_DISPATCHED", "ASSET_RECEIVED", "ASSET_DELIVERED");

    /**
     * ADR-051, Tarea 5 (P-T5-1), ampliada por ADR-021-D Enmienda 2, E8 (Q-21D2-1 (a)): solo los eventos con foto posible
     * llevan huella; en cualquier otro evento, nulo aunque el payload la tenga.
     */
    private static String evidenceRefOf(TraceabilityEventDocument e) {
        if (!PHOTO_EVENTS.contains(e.getEventType()) || e.getPayload() == null) {
            return null;
        }
        Object evidenceRef = e.getPayload().get("evidenceRef");
        return evidenceRef instanceof String s ? s : null;
    }
}
