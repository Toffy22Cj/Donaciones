package com.traceability.core.infrastructure.persistence.mongo;

import com.traceability.core.application.port.out.FundClearingEventsPort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link FundClearingEventsPort} sobre {@code event_store}, por {@code streamId $in}: lo cubre el índice
 * {@code idx_stream_sequence}. Solo trae los campos que usa el recaudado por mes (ficha R-6, G-6).
 */
@Component
public class MongoFundClearingEventsAdapter implements FundClearingEventsPort {

    private final MongoTemplate mongoTemplate;

    public MongoFundClearingEventsAdapter(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public List<ClearingEvent> findFundsCleared(Collection<String> fundIds) {
        if (fundIds.isEmpty()) {
            return List.of();
        }
        Query query = new Query(Criteria.where("streamId").in(fundIds).and("eventType").is("FUNDS_CLEARED"));
        query.fields().include("streamId").include("occurredAt").include("payload.clearedAmount")
                .include("payload.currency");
        return mongoTemplate.find(query, TraceabilityEventDocument.class).stream()
                .map(d -> new ClearingEvent(d.getStreamId(), Instant.parse(d.getOccurredAt()),
                        ((Number) d.getPayload().get("clearedAmount")).longValue(),
                        (String) d.getPayload().get("currency")))
                .toList();
    }

    @Override
    public Map<String, String> findGenesisCurrencies(Collection<String> fundIds) {
        if (fundIds.isEmpty()) {
            return Map.of();
        }
        Query query = new Query(Criteria.where("streamId").in(fundIds).and("sequence").is(1L));
        query.fields().include("streamId").include("payload.currency");
        Map<String, String> currencies = new HashMap<>();
        for (TraceabilityEventDocument d : mongoTemplate.find(query, TraceabilityEventDocument.class)) {
            Object currency = d.getPayload() == null ? null : d.getPayload().get("currency");
            if (currency != null) {
                currencies.put(d.getStreamId(), currency.toString());
            }
        }
        return currencies;
    }
}
