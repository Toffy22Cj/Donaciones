package com.traceability.core.application.query;

import com.traceability.core.application.port.out.PhysicalAssetOperationalReadPort;
import com.traceability.core.application.port.out.PhysicalAssetOperationalView;
import com.traceability.core.application.saga.SplitResolutionStatus;
import com.traceability.core.domain.event.HumanActor;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class PhysicalAssetOperationalQueryService implements PhysicalAssetOperationalReadPort {

    @Override
    public PhysicalAssetOperationalView findOperationalView(String assetId, HumanActor actor) {
        throw new UnsupportedOperationException("B6-c");
    }

    @Override
    public Optional<SplitResolutionStatus> findSplitStatus(String parentAssetId, String childAssetId, HumanActor actor) {
        throw new UnsupportedOperationException("B6-c");
    }
}
