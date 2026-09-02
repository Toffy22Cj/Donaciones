package com.traceability.crypto.infrastructure.web3j;

import com.traceability.crypto.domain.AnchorStatus;
import com.traceability.crypto.domain.MerkleBatch;
import com.traceability.crypto.domain.exception.BlockchainAnchorTimeoutException;
import com.traceability.crypto.domain.exception.GasCapExceededException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.web3j.crypto.Credentials;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.Request;
import org.web3j.protocol.core.methods.response.EthGasPrice;

import java.io.IOException;
import java.math.BigInteger;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class Web3jBlockchainAnchorAdapterTest {

    @Mock
    private Web3j web3j;

    @Mock
    private Credentials credentials;

    private Web3jBlockchainAnchorAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new Web3jBlockchainAnchorAdapter(web3j, credentials, 1337L);
        ReflectionTestUtils.setField(adapter, "maxFeePerGasCap", BigInteger.valueOf(100_000_000_000L));
        ReflectionTestUtils.setField(adapter, "submitTimeoutSeconds", 5);
    }

    @Test
    void shouldThrowIllegalArgumentExceptionIfNonceIsNull() {
        MerkleBatch batchWithoutNonce = new MerkleBatch(
                "batch-1", 1, 10, "0xabcdef", Instant.now(), AnchorStatus.SUBMITTING,
                "network", "0xContract", null, null, null, null, null, null
        );

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> {
            adapter.submitBatch(batchWithoutNonce);
        });
        assertTrue(ex.getMessage().contains("must have an assigned nonce"));
    }

    @Test
    void shouldThrowGasCapExceededExceptionWhenGasPriceIsTooHigh() throws IOException {
        MerkleBatch batch = new MerkleBatch(
                "batch-1", 1, 10, "0xabcdef", Instant.now(), AnchorStatus.SUBMITTING,
                "network", "0xContract", 42L, null, null, null, null, null
        );

        // Mock gas price response to be higher than cap
        EthGasPrice highGasPriceResponse = new EthGasPrice();
        highGasPriceResponse.setResult("0x174876E800"); // 100,000,000,000 in hex
        // Wait, 100 Gwei is 100,000,000,000. Let's make it 200 Gwei
        highGasPriceResponse.setResult("0x2E90EDD000"); // 200,000,000,000
        
        Request<?, EthGasPrice> mockRequest = mock(Request.class);
        when(mockRequest.sendAsync()).thenReturn(CompletableFuture.completedFuture(highGasPriceResponse));
        when(web3j.ethGasPrice()).thenReturn((Request) mockRequest);

        GasCapExceededException ex = assertThrows(GasCapExceededException.class, () -> {
            adapter.submitBatch(batch);
        });
        
        assertTrue(ex.getMessage().contains("exceeds cap"));
    }

    @Test
    void shouldConstructExplicitNonceTransactionManagerWithExactBatchNonce() throws Exception {
        MerkleBatch batch = new MerkleBatch(
                "batch-1", 1, 10, "0xabcdef", Instant.now(), AnchorStatus.SUBMITTING,
                "network", "0xContract", 42L, null, null, null, null, null
        );

        // Mock acceptable gas price
        EthGasPrice normalGasPriceResponse = new EthGasPrice();
        normalGasPriceResponse.setResult("0x0"); 
        Request<?, EthGasPrice> mockRequest = mock(Request.class);
        when(mockRequest.sendAsync()).thenReturn(CompletableFuture.completedFuture(normalGasPriceResponse));
        when(web3j.ethGasPrice()).thenReturn((Request) mockRequest);

        // We only care about verifying the TxManager instantiation, not the actual sendAsync (which would require deep mocking).
        // By intercepting the construction, we prove the exact nonce from the batch is passed down.
        try (var mockedConstruction = mockConstruction(ExplicitNonceTransactionManager.class, 
                (mock, context) -> {
                    assertEquals(4, context.arguments().size());
                    assertEquals(web3j, context.arguments().get(0));
                    assertEquals(credentials, context.arguments().get(1));
                    assertEquals(1337L, context.arguments().get(2));
                    assertEquals(BigInteger.valueOf(42L), context.arguments().get(3), "The exact batch nonce must be passed to the TransactionManager");
                })) {
            
            try {
                adapter.submitBatch(batch);
            } catch (Exception expectedBecauseSendAsyncFails) {
                // Ignore, we just want to verify the intercept before it fails on sending
            }
            
            assertEquals(1, mockedConstruction.constructed().size(), "Should have instantiated exactly one TxManager");
        }
    }
}
