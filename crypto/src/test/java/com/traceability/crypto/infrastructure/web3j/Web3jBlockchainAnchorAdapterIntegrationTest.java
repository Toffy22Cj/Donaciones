package com.traceability.crypto.infrastructure.web3j;

import com.traceability.crypto.domain.AnchorStatus;
import com.traceability.crypto.domain.MerkleBatch;
import com.traceability.crypto.infrastructure.web3j.generated.AnchorRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.web3j.crypto.Credentials;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.protocol.http.HttpService;
import org.web3j.tx.RawTransactionManager;
import org.web3j.tx.TransactionManager;
import org.web3j.tx.gas.DefaultGasProvider;
import org.web3j.tx.response.PollingTransactionReceiptProcessor;
import org.web3j.tx.response.TransactionReceiptProcessor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;

@Testcontainers
class Web3jBlockchainAnchorAdapterIntegrationTest {

    @Container
    public static GenericContainer<?> ganache = new GenericContainer<>("trufflesuite/ganache:latest")
            .withExposedPorts(8545)
            .withCommand("--deterministic") 
            .waitingFor(Wait.forLogMessage(".*Listening on.*", 1));

    private static Web3j web3j;
    private static Credentials credentials;
    private static String contractAddress;
    private static Web3jBlockchainAnchorAdapter adapter;

    @BeforeAll
    static void setup() throws Exception {
        String rpcUrl = "http://" + ganache.getHost() + ":" + ganache.getMappedPort(8545);
        web3j = Web3j.build(new HttpService(rpcUrl));
        // =====================================================================
        // WARNING: DO NOT USE IN PRODUCTION
        // This is the predefined deterministic account #0 in Ganache.
        // It is strictly for local testing and contains no real funds.
        // =====================================================================
        credentials = Credentials.create("0x4f3edf983ac636a65a842ce7c78d9aa706d3b113bce9c46f30d7d21715b23b1d");

        // 1. Deploy the contract using standard resolution (nonce 0)
        String binary = new String(Files.readAllBytes(Paths.get("src/test/resources/solidity/build/AnchorRegistry.bin"))).trim();
        TransactionManager tm = new RawTransactionManager(web3j, credentials, web3j.ethChainId().send().getChainId().longValue());
        String txHash = tm.sendTransaction(
                DefaultGasProvider.GAS_PRICE,
                DefaultGasProvider.GAS_LIMIT,
                null,
                binary,
                BigInteger.ZERO
        ).getTransactionHash();
        
        TransactionReceiptProcessor receiptProcessor = new PollingTransactionReceiptProcessor(web3j, 1000, 15);
        TransactionReceipt deployReceipt = receiptProcessor.waitForTransactionReceipt(txHash);
        contractAddress = deployReceipt.getContractAddress();
        assertNotNull(contractAddress);

        // Current nonce for the account is now 1 (0 was used for deployment)
        
        // Setup the adapter
        adapter = new Web3jBlockchainAnchorAdapter(web3j, credentials, web3j.ethChainId().send().getChainId().longValue());
        ReflectionTestUtils.setField(adapter, "maxFeePerGasCap", BigInteger.valueOf(100_000_000_000L)); // 100 Gwei
        ReflectionTestUtils.setField(adapter, "submitTimeoutSeconds", 15);
    }

    @Test
    void shouldSuccessfullySendWithExactNonceAndRejectIncorrectNonceProvingNoAutoResolution() throws Exception {
        // A valid bytes32 hex string (64 chars)
        String validRoot1 = "0x1111111111111111111111111111111111111111111111111111111111111111";
        String validRoot2 = "0x2222222222222222222222222222222222222222222222222222222222222222";

        // --- STEP 1: Send with exact correct nonce (which is 1) ---
        MerkleBatch correctBatch = new MerkleBatch(
                "batch-1", 1, 10, validRoot1, Instant.now(), AnchorStatus.SUBMITTING,
                "network", contractAddress, 1L, null, null, null, null, null
        );

        String txHash = adapter.submitBatch(correctBatch);
        assertNotNull(txHash);

        // Verify the transaction was indeed mined with nonce 1
        org.web3j.protocol.core.methods.response.Transaction tx = web3j.ethGetTransactionByHash(txHash).send().getTransaction().orElseThrow();
        assertEquals(BigInteger.ONE, tx.getNonce(), "The transaction must have been mined exactly with the nonce 1 we provided");

        // --- STEP 2: Send with an intentionally incorrect nonce (e.g. 1 again) ---
        // If Web3j were auto-resolving, it would automatically query the node, see the next nonce is 2, 
        // use 2, and the transaction would succeed. 
        // Since our ExplicitNonceTransactionManager forces the nonce to 1, the node will reject it immediately 
        // with "nonce too low" or similar error.
        
        MerkleBatch collisionBatch = new MerkleBatch(
                "batch-2", 11, 20, validRoot2, Instant.now(), AnchorStatus.SUBMITTING,
                "network", contractAddress, 1L, null, null, null, null, null
        );

        Exception ex = assertThrows(Exception.class, () -> {
            adapter.submitBatch(collisionBatch);
        });

        // We expect an error containing "nonce" somewhere in its message or cause, because Ganache rejects it.
        // The error usually wraps inside ExecutionException -> RuntimeException
        assertTrue(ex.getMessage().toLowerCase().contains("nonce") || 
                   (ex.getCause() != null && ex.getCause().getMessage().toLowerCase().contains("nonce")),
                   "Error must complain about nonce too low, proving web3j didn't auto-resolve it to 2. Message: " + ex.getMessage());
    }
}
