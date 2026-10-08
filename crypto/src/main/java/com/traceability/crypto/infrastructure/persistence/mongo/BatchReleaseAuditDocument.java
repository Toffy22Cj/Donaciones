package com.traceability.crypto.infrastructure.persistence.mongo;

import com.traceability.contracts.SequenceRange;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Auditoría de RELEASE de batches COLLECTING_FAILED (Enmienda 1 de ADR-039 §2.3). Solo se inserta. */
@Document(collection = "batch_release_audit")
public class BatchReleaseAuditDocument {

    @Id
    public String id;
    @Indexed
    public String batchId;
    public Map<String, SequenceRange> coverage;
    public List<String> eventIds;
    public String operator;
    public String reason;
    public Instant releasedAt;
}
