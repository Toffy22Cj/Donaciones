package com.traceability.core.application.saga;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.core.application.command.FundCommandService;
import org.springframework.stereotype.Component;

@Component
public class AssetRegisteredSagaPolicy implements SagaPolicy {

    private final FundCommandService fundCommandService;
    private final ObjectMapper objectMapper;

    public AssetRegisteredSagaPolicy(FundCommandService fundCommandService, ObjectMapper objectMapper) {
        this.fundCommandService = fundCommandService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getSagaType() {
        return "ASSET_REGISTRATION_SAGA";
    }

    @Override
    public void execute(OutboxMessage message) {
        try {
            JsonNode payload = objectMapper.readTree(message.payload());
            String allocationId = payload.path("sourceAllocationId").asText(null);
            String fundId = payload.path("fundId").asText(null); // Assuming fundId is known or correlationId is the fundId
            
            // For simplicity, we might assume the correlationId contains the fundId, or it's in the payload.
            // Let's use correlationId as fundId if not present in payload.
            if (fundId == null || fundId.isEmpty() || fundId.equals("null")) {
                fundId = message.correlationId();
            }

            if (allocationId != null && !allocationId.isEmpty() && !allocationId.equals("null") && fundId != null) {
                // messageId is globally unique, great for idempotency
                fundCommandService.confirmAllocation(message.messageId(), fundId, allocationId);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to process ASSET_REGISTRATION_SAGA", e);
        }
    }

    @Override
    public void compensate(OutboxMessage message) {
        try {
            JsonNode payload = objectMapper.readTree(message.payload());
            String allocationId = payload.path("sourceAllocationId").asText(null);
            String fundId = payload.path("fundId").asText(null);
            
            if (fundId == null || fundId.isEmpty() || fundId.equals("null")) {
                fundId = message.correlationId();
            }

            if (allocationId != null && !allocationId.isEmpty() && !allocationId.equals("null") && fundId != null) {
                fundCommandService.reverseAllocation(message.messageId() + "-comp", fundId, allocationId, "Asset registration saga failed/compensated");
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to compensate ASSET_REGISTRATION_SAGA", e);
        }
    }
}
