package com.traceability.core.infrastructure.projection.mongo.documents;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Document(collection = "donation_projections")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DonationProjectionDocument {

    @Id
    private String projectionId; // fundId
    
    private String currency;
    private String campaignRef;
    
    @Builder.Default
    private FinancialSnapshot financialSnapshot = new FinancialSnapshot();
    
    @Builder.Default
    private List<AllocationProjection> allocations = new ArrayList<>();
    
    @Builder.Default
    private List<LogisticsProjection> logistics = new ArrayList<>();
    
    @Builder.Default
    private AuditMetadata auditMetadata = new AuditMetadata();
    
    @Builder.Default
    private String status = "ACTIVE"; // ACTIVE, PAUSED

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FinancialSnapshot {
        private String sourceTransactionId;
        private long originalAmount;
        private long clearedAmount;
        private long pendingAllocationAmount;
        private long refundedAmount;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AllocationProjection {
        private String allocationId;
        private String vendorId;
        private String requirementId;
        private long amount;
        private String status;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LogisticsProjection {
        private String assetId;
        private String allocationId;       // Propio de este activo (si fue asignado directamente)
        private String sourceAllocationId; // Heredado de parent (para hijos de split)
        private String parentAssetRef;     // Para hijos de split
        private String rootAssetRef;       // Para identificar linaje
        @Field(targetType = FieldType.DECIMAL128)
        private BigDecimal quantity;
        private String unitOfMeasure;
        private String assetType;
        private String currentLocation;
        private String currentCustodian;
        private String lifecycleStatus;
        private String statusBeforeSplit;  // Uso interno para compensación de splits
        // Convocatoria del activo (ADR-029 Enmienda 1, D7): SOLO del payload v3 del propio activo; v1/v2 → null.
        // Nunca se infiere del Fund ni de la donación. Índice y agregación para la narrativa: B5 (ADR-040 C5).
        private String campaignRef;
        // Estado previo a cada división, por childAssetId (D-SPLIT S6), como el agregado (splitsBeforeCompensation)
        private Map<String, String> splitsBeforeCompensation;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AuditMetadata {
        private long fundLastProcessedSequence;
        @Builder.Default
        private Map<String, Long> assetLastProcessedSequences = new HashMap<>();
    }
}
