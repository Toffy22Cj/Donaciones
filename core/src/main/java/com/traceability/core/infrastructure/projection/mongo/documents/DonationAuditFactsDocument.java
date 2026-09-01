package com.traceability.core.infrastructure.projection.mongo.documents;

import com.traceability.contracts.FinancialFlagDTO;
import com.traceability.contracts.TransitionFactDTO;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Document(collection = "donation_audit_facts")
@Data
public class DonationAuditFactsDocument {

    @Id
    private String fundId;
    
    private List<TransitionFactDTO> transitions = new ArrayList<>();
    
    private List<FinancialFlagDTO> financialFlags = new ArrayList<>();
    
    private Instant generatedAt;
    
    private AuditFactsMetadata auditMetadata = new AuditFactsMetadata();
    
    @Data
    public static class AuditFactsMetadata {
        private long fundLastProcessedSequence = 0;
        private Map<String, Long> assetLastProcessedSequences = new HashMap<>();
    }
}
