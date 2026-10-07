package com.traceability.core.application.query;

import com.traceability.core.application.port.out.SplitResolutionReadPort;
import com.traceability.core.application.saga.SplitResolutionStatus;
import org.springframework.stereotype.Service;

import java.util.Optional;

/** Skeleton B1-bis. */
@Service
public class SplitResolutionQueryService implements SplitResolutionReadPort {

    @Override
    public Optional<SplitResolutionStatus> findStatus(String parentAssetId, String childAssetId) {
        return Optional.empty();
    }
}
