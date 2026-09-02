package com.traceability.crypto.application.port.out;

import com.traceability.crypto.domain.TransactionConfirmation;

import java.util.Optional;

public interface BlockchainTransactionReceiptPort {
    /**
     * Gets the current highest block number on the network.
     */
    Long getCurrentBlockNumber();

    /**
     * Gets confirmation details of a transaction if it has been mined.
     * @return Optional.empty() if the transaction is still pending or not found.
     */
    Optional<TransactionConfirmation> getTransactionConfirmation(String txHash, String smartContractAddress);
}
