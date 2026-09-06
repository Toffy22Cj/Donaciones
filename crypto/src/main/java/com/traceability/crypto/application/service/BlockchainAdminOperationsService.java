package com.traceability.crypto.application.service;

import com.traceability.crypto.application.port.out.BlockchainAnchorRepositoryPort;
import com.traceability.crypto.domain.Resolution;
import org.springframework.jmx.export.annotation.ManagedOperation;
import org.springframework.jmx.export.annotation.ManagedOperationParameter;
import org.springframework.jmx.export.annotation.ManagedResource;
import org.springframework.stereotype.Service;

import java.math.BigInteger;

@Service
@ManagedResource(objectName = "com.traceability.crypto.application.service:type=BlockchainAdminOperations", description = "Admin Operations for Blockchain Anchor")
public class BlockchainAdminOperationsService {

    private final BlockchainAnchorRepositoryPort repositoryPort;

    public BlockchainAdminOperationsService(BlockchainAnchorRepositoryPort repositoryPort) {
        this.repositoryPort = repositoryPort;
    }

    @ManagedOperation(description = "Resolves a STUCK MerkleBatch manually by either resubmitting with a new fee or abandoning it")
    @ManagedOperationParameter(name = "batchId", description = "The ID of the stuck batch")
    @ManagedOperationParameter(name = "resolutionStr", description = "The resolution: RESUBMIT or ABANDON")
    @ManagedOperationParameter(name = "maxFeePerGas", description = "The new maxFeePerGas for RESUBMIT (can be 0 or empty for ABANDON)")
    public void resolveStuckBatch(String batchId, String resolutionStr, String maxFeePerGas) {
        Resolution resolution = Resolution.valueOf(resolutionStr.toUpperCase());
        BigInteger maxFee = (maxFeePerGas != null && !maxFeePerGas.isBlank()) ? new BigInteger(maxFeePerGas) : null;
        
        repositoryPort.resolveStuckBatch(batchId, resolution, maxFee);
    }
}
