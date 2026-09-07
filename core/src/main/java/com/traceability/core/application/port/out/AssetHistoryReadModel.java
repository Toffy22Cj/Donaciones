package com.traceability.core.application.port.out;

import java.util.List;

public record AssetHistoryReadModel(
        String assetId,
        List<AssetTransitionReadModel> transitions
) {
}
