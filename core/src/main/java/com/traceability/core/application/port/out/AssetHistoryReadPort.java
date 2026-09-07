package com.traceability.core.application.port.out;

import java.util.Optional;

public interface AssetHistoryReadPort {
    Optional<AssetHistoryReadModel> getHistory(String assetId);
}
