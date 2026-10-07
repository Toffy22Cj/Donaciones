package com.traceability.core.application.saga;

import org.springframework.stereotype.Component;

/** Skeleton B1-bis. */
@Component
public class SplitPhysicalAssetSagaPolicy implements SagaPolicy {

    public static final String SAGA_TYPE = "ASSET_SPLIT_SAGA";

    @Override
    public String getSagaType() {
        return SAGA_TYPE;
    }

    @Override
    public void execute(OutboxMessage message) {
    }

    @Override
    public void compensate(OutboxMessage message) {
    }
}
