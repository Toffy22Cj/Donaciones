package com.traceability.core.application.saga;

import org.springframework.jmx.export.annotation.ManagedResource;
import org.springframework.stereotype.Component;

import java.util.List;

/** Skeleton B1-bis. */
@Component
@ManagedResource(objectName = "com.traceability.core:type=SagaOutboxAdministration")
public class SagaOutboxAdministration {

    public long getQuarantinedCount() {
        return 0;
    }

    public List<String> listQuarantined(String sagaType, int limit) {
        return List.of();
    }

    public void retryResolution(String messageId, String operator, String note) {
    }

    public void markResolvedManually(String messageId, String operator, String note) {
    }
}
