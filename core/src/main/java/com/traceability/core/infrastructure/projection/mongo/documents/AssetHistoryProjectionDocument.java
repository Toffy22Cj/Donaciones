package com.traceability.core.infrastructure.projection.mongo.documents;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.ArrayList;
import java.util.List;

/**
 * ADR-015: Detailed history per assetId: full list of transitions with timestamp and location.
 */
@Document(collection = "asset_history")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AssetHistoryProjectionDocument {

    @Id
    private String assetId;

    @Builder.Default
    private List<AssetTransition> transitions = new ArrayList<>();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AssetTransition {
        private String eventType;
        private String timestamp;
        private String location;
        private String custodian;
        private String status;
        private long sequence;
    }
}
