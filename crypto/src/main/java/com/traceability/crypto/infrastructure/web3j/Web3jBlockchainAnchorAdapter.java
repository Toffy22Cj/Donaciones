package com.traceability.crypto.infrastructure.web3j;

import org.web3j.abi.EventEncoder;
import org.web3j.protocol.core.DefaultBlockParameter;
import org.web3j.protocol.core.methods.request.EthFilter;
import org.web3j.protocol.core.methods.response.EthLog;
import java.util.Optional;
import com.traceability.crypto.application.port.out.BlockchainAnchorSubmitterPort;
import com.traceability.crypto.domain.MerkleBatch;
import com.traceability.crypto.domain.exception.BlockchainAnchorTimeoutException;
import com.traceability.crypto.domain.exception.GasCapExceededException;
import com.traceability.crypto.infrastructure.web3j.generated.AnchorRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.web3j.crypto.Credentials;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.tx.gas.StaticGasProvider;
import org.web3j.utils.Numeric;

import java.math.BigInteger;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class Web3jBlockchainAnchorAdapter implements BlockchainAnchorSubmitterPort {

    private final Web3j web3j;
    private final Credentials credentials;
    private final long chainId;
    
    @Value("${crypto.anchor.gas.max-fee-per-gas-cap:100000000000}")
    private BigInteger maxFeePerGasCap;
    
    @Value("${crypto.anchor.submit.timeout-seconds:15}")
    private int submitTimeoutSeconds;

    @Value("${crypto.anchor.reconcile.lookback-blocks:2000}")
    private long reconcileLookbackBlocks = 2000;

    @Value("${crypto.anchor.reconcile.chunk-blocks:500}")
    private long reconcileChunkBlocks = 500;

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(Web3jBlockchainAnchorAdapter.class);

    public Web3jBlockchainAnchorAdapter(Web3j web3j, Credentials credentials, @Value("${crypto.anchor.chain-id:1337}") long chainId) {
        this.web3j = web3j;
        this.credentials = credentials;
        this.chainId = chainId;
    }

    @Override
    public String submitBatch(MerkleBatch batch) {
        if (batch.nonceUsed() == null) {
            throw new IllegalArgumentException("Batch must have an assigned nonce before submitting");
        }
        
        try {
            // 1. Dynamic Gas Estimation
            // Use sendAsync().get(...) to protect against unbounded blocking if the RPC node hangs
            BigInteger currentGasPrice;
            try {
                currentGasPrice = web3j.ethGasPrice().sendAsync()
                        .get(submitTimeoutSeconds, TimeUnit.SECONDS)
                        .getGasPrice();
            } catch (TimeoutException e) {
                // This is a DETERMINISTIC failure because we haven't sent anything yet.
                throw new com.traceability.crypto.domain.exception.BlockchainNodeCommunicationException(
                        "Timeout while checking gas price before transaction submission", e);
            } catch (Exception e) {
                throw new com.traceability.crypto.domain.exception.BlockchainNodeCommunicationException(
                        "Failed to check gas price before transaction submission", e);
            }
                    
            if (batch.maxFeePerGasOverride() != null) {
                // If override is provided, use it directly (bypass dynamic estimation and cap check)
                currentGasPrice = batch.maxFeePerGasOverride();
            } else {
                if (currentGasPrice.compareTo(maxFeePerGasCap) > 0) {
                    throw new GasCapExceededException("Current gas price " + currentGasPrice + " exceeds cap " + maxFeePerGasCap);
                }
            }

            // 2. Setup Explicit Nonce Transaction Manager
            BigInteger explicitNonce = BigInteger.valueOf(batch.nonceUsed());
            ExplicitNonceTransactionManager txManager = new ExplicitNonceTransactionManager(
                    web3j, credentials, chainId, explicitNonce
            );

            // 3. Load Contract Wrapper using the custom tx manager
            // Note: Gas limit is fixed for now, can be estimated dynamically
            BigInteger gasLimit = BigInteger.valueOf(300_000);
            AnchorRegistry registry = AnchorRegistry.load(
                    batch.smartContractAddress(),
                    web3j,
                    txManager,
                    new StaticGasProvider(currentGasPrice, gasLimit)
            );

            // 4. Encode root and send transaction
            byte[] rootBytes = Numeric.hexStringToByteArray(batch.merkleRoot());
            
            // Execute with timeout wrapper
            CompletableFuture<TransactionReceipt> futureReceipt = registry.anchorRoot(rootBytes).sendAsync();
            try {
                TransactionReceipt receipt = futureReceipt.get(submitTimeoutSeconds, TimeUnit.SECONDS);
                return receipt.getTransactionHash();
            } catch (TimeoutException e) {
                // IMPORTANT: futureReceipt.cancel(true) on a CompletableFuture DOES NOT interrupt the underlying OkHttp thread.
                // The network request to the node may still be in progress or even succeed later.
                // We signal a TimeoutException so the Scheduler treats this as AMBIGUOUS and forces a manual check.
                futureReceipt.cancel(true);
                throw new BlockchainAnchorTimeoutException("Timeout waiting for transaction submission response. State is ambiguous.", e);
            }

        } catch (BlockchainAnchorTimeoutException | GasCapExceededException | com.traceability.crypto.domain.exception.BlockchainNodeCommunicationException e) {
            throw e; // Rethrow explicit domain exceptions
        } catch (Exception e) {
            // Check if inner exception was TimeoutException from the execution wrapper
            if (e.getCause() instanceof TimeoutException) {
                throw new BlockchainAnchorTimeoutException("Timeout waiting for transaction submission response", e.getCause());
            }
            // Reintento de un envío ambiguo cuyo nonce ya se consumió: si la raíz está en el contrato, ese envío llegó
            if (nonceTooLow(e)) {
                Optional<String> anchoredTx = findAnchorTransaction(batch);
                if (anchoredTx.isPresent()) {
                    log.info("anchor.reconciled batchId={} nonce={} txHash={}: la raíz ya estaba en el contrato",
                            batch.batchId(), batch.nonceUsed(), anchoredTx.get());
                    return anchoredTx.get();
                }
                log.warn("anchor.nonce-consumed batchId={} nonce={}: la raíz no aparece en los últimos {} bloques",
                        batch.batchId(), batch.nonceUsed(), reconcileLookbackBlocks);
            }
            throw new RuntimeException("Unexpected error during transaction submission", e);
        }
    }

    static boolean nonceTooLow(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t.getMessage() != null && t.getMessage().toLowerCase(java.util.Locale.ROOT).contains("nonce too low")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Busca el evento {@code RootStored(root, sender)} de esta cartera con la raíz del lote, hacia atrás desde el último
     * bloque, en tramos de {@code crypto.anchor.reconcile.chunk-blocks} hasta {@code lookback-blocks}. Si está, devuelve
     * su transacción. Un fallo al consultar se trata como "no encontrado": el lote sigue como estaba.
     */
    Optional<String> findAnchorTransaction(MerkleBatch batch) {
        try {
            BigInteger latest = web3j.ethBlockNumber().send().getBlockNumber();
            BigInteger oldest = latest.subtract(BigInteger.valueOf(reconcileLookbackBlocks)).max(BigInteger.ZERO);
            BigInteger chunk = BigInteger.valueOf(Math.max(1, reconcileChunkBlocks));
            String root = Numeric.toHexString(Numeric.hexStringToByteArray(batch.merkleRoot()));
            String sender = Numeric.toHexStringWithPrefixZeroPadded(Numeric.toBigInt(credentials.getAddress()), 64);
            for (BigInteger to = latest; to.compareTo(oldest) >= 0; to = to.subtract(chunk)) {
                BigInteger from = to.subtract(chunk).add(BigInteger.ONE).max(oldest);
                EthFilter filter = new EthFilter(DefaultBlockParameter.valueOf(from), DefaultBlockParameter.valueOf(to),
                        batch.smartContractAddress());
                filter.addSingleTopic(EventEncoder.encode(AnchorRegistry.ROOTSTORED_EVENT));
                filter.addSingleTopic(root);
                filter.addSingleTopic(sender);
                EthLog logs = web3j.ethGetLogs(filter).send();
                if (logs.hasError()) {
                    // p. ej. el proveedor limita el rango: bajar crypto.anchor.reconcile.chunk-blocks
                    log.warn("anchor.reconcile.rpc-error batchId={} range={}-{} error={}", batch.batchId(), from, to,
                            logs.getError().getMessage());
                    return Optional.empty();
                }
                Optional<String> tx = logs.getLogs() == null ? Optional.empty() : logs.getLogs().stream()
                        .map(r -> ((EthLog.LogObject) r.get()).getTransactionHash())
                        .filter(java.util.Objects::nonNull)
                        .findFirst();
                if (tx.isPresent()) {
                    return tx;
                }
            }
            return Optional.empty();
        } catch (Exception lookup) {
            log.warn("anchor.reconcile.failed batchId={} cause={}", batch.batchId(), lookup.getClass().getSimpleName());
            return Optional.empty();
        }
    }

}
