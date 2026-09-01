package com.traceability.core.infrastructure.projection.mongo.documents;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * ADR-011: Technical collection for quick resolution of incoming PhysicalAsset events
 * to their corresponding DonationProjection (Fund).
 * Never read as source of truth; fully rebuildable from event_store.
 */
@Document(collection = "asset_index")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AssetIndexDocument {
    @Id
    private String assetId;
    
    // As explicitly defined: projectionId strictly corresponds to fundId.
    private String projectionId;
    
    private String rootAssetRef;
    
    private long lastAppliedSequence;
}
