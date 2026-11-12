package com.traceability.crypto.infrastructure.web3j;

import com.traceability.crypto.application.port.out.MerkleBatchRepositoryPort;
import org.junit.jupiter.api.Test;
import org.web3j.crypto.Credentials;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.Request;
import org.web3j.protocol.core.methods.response.EthGetTransactionCount;

import java.math.BigInteger;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** El contador de nonce sube hasta el de la cartera en la red al arrancar; si el nodo falla, no se toca. */
class NonceCounterChainSyncTest {

    private static final Credentials WALLET =
            Credentials.create("0x4f3edf983ac636a65a842ce7c78d9aa706d3b113bce9c46f30d7d21715b23b1d");

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Web3j nodeAnswering(CompletableFuture<EthGetTransactionCount> answer) {
        Web3j web3j = mock(Web3j.class);
        Request request = mock(Request.class);
        when(request.sendAsync()).thenReturn(answer);
        when(web3j.ethGetTransactionCount(eq(WALLET.getAddress()), eq(DefaultBlockParameterName.PENDING)))
                .thenReturn(request);
        return web3j;
    }

    @Test
    void theCounterIsRaisedToTheWalletNonceOnTheNetwork() {
        EthGetTransactionCount count = new EthGetTransactionCount();
        count.setResult("0x4");
        MerkleBatchRepositoryPort repository = mock(MerkleBatchRepositoryPort.class);
        new NonceCounterChainSync(nodeAnswering(CompletableFuture.completedFuture(count)), WALLET, repository,
                "sepolia", "0xabc").syncWithChain();
        verify(repository).seedNonceCounter("sepolia", "0xabc", BigInteger.valueOf(4).longValueExact());
    }

    @Test
    void ifTheNodeFails_theCounterIsLeftAsItWas_andStartupGoesOn() {
        MerkleBatchRepositoryPort repository = mock(MerkleBatchRepositoryPort.class);
        new NonceCounterChainSync(nodeAnswering(CompletableFuture.failedFuture(new java.io.IOException("caído"))),
                WALLET, repository, "sepolia", "0xabc").syncWithChain();
        verify(repository, never()).seedNonceCounter(anyString(), anyString(), anyLong());
    }
}
