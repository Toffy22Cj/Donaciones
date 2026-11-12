package com.traceability.crypto.infrastructure.web3j;

import com.traceability.crypto.application.port.out.MerkleBatchRepositoryPort;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.web3j.crypto.Credentials;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;

import java.math.BigInteger;
import java.util.concurrent.TimeUnit;

/**
 * Al arrancar, sube el contador de nonce de MongoDB ({@code web3_nonce_counter}) hasta el nonce que la cartera ya tiene
 * en la red ({@code eth_getTransactionCount} con {@code pending}).
 * <p>
 * El contador solo vive en la base: si se vacía (una semilla nueva) o la cartera envió transacciones por otro lado
 * (desplegar el contrato desde la misma cuenta), empezaba en 0 y cada envío fallaba con {@code nonce too low}. Usa
 * {@link MerkleBatchRepositoryPort#seedNonceCounter}, que solo sube el valor ({@code $max}): nunca reutiliza un nonce
 * ya asignado. Corre antes de que arranquen los programadores. Si el nodo no responde, avisa y sigue: el contador queda
 * como estaba.
 */
@Component
public class NonceCounterChainSync {

    private static final Logger log = LoggerFactory.getLogger(NonceCounterChainSync.class);

    private final Web3j web3j;
    private final Credentials credentials;
    private final MerkleBatchRepositoryPort repository;
    private final String network;
    private final String smartContractAddress;

    public NonceCounterChainSync(Web3j web3j, Credentials credentials, MerkleBatchRepositoryPort repository,
                                 @Value("${crypto.anchor.network:polygon-mumbai}") String network,
                                 @Value("${crypto.anchor.smart-contract:0x0000000000000000000000000000000000000000}")
                                 String smartContractAddress) {
        this.web3j = web3j;
        this.credentials = credentials;
        this.repository = repository;
        this.network = network;
        this.smartContractAddress = smartContractAddress;
    }

    @PostConstruct
    void syncWithChain() {
        try {
            BigInteger onChain = web3j.ethGetTransactionCount(credentials.getAddress(), DefaultBlockParameterName.PENDING)
                    .sendAsync().get(15, TimeUnit.SECONDS).getTransactionCount();
            repository.seedNonceCounter(network, smartContractAddress, onChain.longValueExact());
            log.info("web3.nonce.synced network={} nextNonceAtLeast={}", network, onChain);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("web3.nonce.sync.interrupted network={}", network);
        } catch (Exception e) {
            log.warn("web3.nonce.sync.failed network={} cause={}", network, e.getClass().getSimpleName());
        }
    }
}
