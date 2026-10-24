package com.traceability.core.infrastructure.persistence.mongo;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Document(collection = "event_store")
@CompoundIndex(name = "idx_stream_sequence", def = "{'streamId': 1, 'sequence': 1}", unique = true)
// Plan B5 (DD-33): búsqueda de los activos de una convocatoria por su génesis; solo un índice, ningún cambio de evento
@CompoundIndex(name = "idx_event_type_campaign", def = "{'eventType': 1, 'payload.campaignRef': 1}")
// Ficha R-6, Bloque B (P-R6B-7): el directorio de fondos de una organización (MongoFundDirectoryAdapter, GET /funds y
// el recaudado por mes) busca por la génesis y ordena por recordedAt y streamId. Parcial: solo las génesis de fondos.
// Ningún cambio de evento. Medido: 202 000 claves y 514 ms → 500 claves y 3 ms (plan-r6-bloque-b.md, Aprobación)
@CompoundIndex(name = "idx_fund_genesis_organization", def = "{'payload.organizationRef': 1, 'recordedAt': 1, 'streamId': 1}",
        partialFilter = "{'sequence': 1, 'aggregateType': 'Fund'}")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TraceabilityEventDocument {

    @Id
    private String eventId;
    
    private String streamId;
    private String aggregateType;
    private long sequence;
    private String eventType;
    private String schemaVersion;
    private String occurredAt;
    private String recordedAt;
    private com.traceability.core.domain.event.ActorRef actorRef;
    private String origin;
    
    private Map<String, Object> payload;
    
    private String merkleBatchId;
    
    private String previousHash;
    private String eventHash;
}
