package com.traceability.crypto.infrastructure.web3j;

import com.traceability.crypto.application.port.out.BlockchainTransactionReceiptPort;
import com.traceability.crypto.domain.TransactionConfirmation;
import com.traceability.crypto.infrastructure.web3j.generated.AnchorRegistry;
import org.springframework.stereotype.Component;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.response.TransactionReceipt;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Component
public class Web3jBlockchainTransactionReceiptAdapter implements BlockchainTransactionReceiptPort {

    private final Web3j web3j;
    private final long timeoutSeconds;

    public Web3jBlockchainTransactionReceiptAdapter(Web3j web3j) {
        this.web3j = web3j;
        this.timeoutSeconds = 15;
    }

    @Override
    public Long getCurrentBlockNumber() {
        try {
            return web3j.ethBlockNumber().sendAsync().get(timeoutSeconds, TimeUnit.SECONDS).getBlockNumber().longValue();
        } catch (Exception e) {
            throw new RuntimeException("Failed to get current block number", e);
        }
    }

    @Override
    public Optional<TransactionConfirmation> getTransactionConfirmation(String txHash, String smartContractAddress) {
        try {
            Optional<TransactionReceipt> receiptOpt = web3j.ethGetTransactionReceipt(txHash)
                    .sendAsync().get(timeoutSeconds, TimeUnit.SECONDS)
                    .getTransactionReceipt();

            if (receiptOpt.isEmpty()) {
                return Optional.empty();
            }

            TransactionReceipt receipt = receiptOpt.get();
            
            // Transaction reverted
            if ("0x0".equals(receipt.getStatus())) {
                return Optional.of(new TransactionConfirmation(
                        true,
                        receipt.getBlockNumber().longValue(),
                        null
                ));
            }

            // Mined successfully. Try to decode the RootStored event
            byte[] anchoredRootBytes = null;

            // Load the contract with a read-only transaction manager because we only need to decode logs.
            org.web3j.tx.ReadonlyTransactionManager tm = new org.web3j.tx.ReadonlyTransactionManager(web3j, smartContractAddress);
            AnchorRegistry registry = AnchorRegistry.load(smartContractAddress, web3j, tm, new org.web3j.tx.gas.DefaultGasProvider());
            List<AnchorRegistry.RootStoredEventResponse> events = registry.getRootStoredEvents(receipt);

            if (!events.isEmpty()) {
                // There should only be one root stored event per our transaction design
                anchoredRootBytes = events.get(0).root;
            }

            return Optional.of(new TransactionConfirmation(
                    false,
                    receipt.getBlockNumber().longValue(),
                    anchoredRootBytes
            ));

        } catch (Exception e) {
            throw new RuntimeException("Failed to fetch transaction receipt for tx " + txHash, e);
        }
    }
}
