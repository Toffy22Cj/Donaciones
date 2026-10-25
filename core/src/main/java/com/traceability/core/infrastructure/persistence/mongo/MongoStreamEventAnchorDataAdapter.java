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
        query.fields().include("sequence").include("eventHash").include("merkleBatchId");
        return mongoTemplate.find(query, TraceabilityEventDocument.class).stream()
                .map(e -> new StreamEventAnchorData(e.getSequence(), e.getEventHash(), e.getMerkleBatchId()))
                .toList();
    }
}
