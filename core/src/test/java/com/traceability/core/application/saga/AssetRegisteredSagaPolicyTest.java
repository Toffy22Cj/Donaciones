package com.traceability.core.application.saga;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.core.application.command.FundCommandService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AssetRegisteredSagaPolicyTest {

    private FundCommandService fundCommandService;
    private ObjectMapper objectMapper;
    private AssetRegisteredSagaPolicy policy;

    @BeforeEach
    void setUp() {
        fundCommandService = mock(FundCommandService.class);
        objectMapper = new ObjectMapper();
        policy = new AssetRegisteredSagaPolicy(fundCommandService, objectMapper);
    }

    @Test
    void execute_rootAsset_confirmsAllocationUsingAllocationId() {
        // Arrange
        String payloadJson = "{" +
                "\"assetId\": \"ASSET-1\"," +
                "\"allocationId\": \"ALLOC-ROOT\"," +
                "\"sourceAllocationId\": \"ALLOC-CHILD-DECOY\"," +
                "\"fundId\": \"FUND-1\"" +
                "}";

        OutboxMessage message = new OutboxMessage(
                "MSG-1",
                "ASSET_REGISTRATION_SAGA",
                "ASSET-1",
                "FUND-1",
                payloadJson,
                OutboxStatus.PENDING,
                0,
                Instant.now(),
                Instant.now()
        );

        // Act
        policy.execute(message);

        // Assert
        verify(fundCommandService).confirmAllocation(
                eq("MSG-1"),
                eq("FUND-1"),
                eq("ALLOC-ROOT"),
                any()
        );
    }

    @Test
    void compensate_rootAsset_reversesAllocationUsingAllocationId() {
        // Arrange
        String payloadJson = "{" +
                "\"assetId\": \"ASSET-1\"," +
                "\"allocationId\": \"ALLOC-ROOT\"," +
                "\"sourceAllocationId\": \"ALLOC-CHILD-DECOY\"," +
                "\"fundId\": \"FUND-1\"" +
                "}";

        OutboxMessage message = new OutboxMessage(
                "MSG-1",
                "ASSET_REGISTRATION_SAGA",
                "ASSET-1",
                "FUND-1",
                payloadJson,
                OutboxStatus.PENDING,
                0,
                Instant.now(),
                Instant.now()
        );

        // Act
        policy.compensate(message);

        // Assert
        verify(fundCommandService).reverseAllocation(
                eq("MSG-1-comp"),
                eq("FUND-1"),
                eq("ALLOC-ROOT"),
                eq("Asset registration saga failed/compensated"),
                any()
        );
    }
}
