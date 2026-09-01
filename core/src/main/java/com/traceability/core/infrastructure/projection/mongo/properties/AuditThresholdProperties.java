package com.traceability.core.infrastructure.projection.mongo.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "traceability.audit.thresholds")
@Data
public class AuditThresholdProperties {
    
    // Default 72 hours (in seconds)
    private long dispatchedToReceived = 259200;
    
    // Default 48 hours (in seconds)
    private long dispatchedToDelivered = 172800;
    
    // Default 48 hours (in seconds)
    private long receivedToDelivered = 172800;
}
