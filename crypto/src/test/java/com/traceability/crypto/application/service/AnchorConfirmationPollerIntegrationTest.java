package com.traceability.crypto.application.service;

import com.traceability.crypto.application.port.out.BlockchainAnchorRepositoryPort;
import com.traceability.crypto.domain.AnchorStatus;
import com.traceability.crypto.domain.MerkleBatch;
import com.traceability.crypto.infrastructure.web3j.Web3jBlockchainTransactionReceiptAdapter;
import com.traceability.crypto.infrastructure.web3j.generated.AnchorRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.web3j.crypto.Credentials;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.protocol.http.HttpService;
import org.web3j.tx.RawTransactionManager;
import org.web3j.tx.TransactionManager;
import org.web3j.tx.gas.DefaultGasProvider;
import org.web3j.utils.Numeric;

import java.math.BigInteger;
import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

public class AnchorConfirmationPollerIntegrationTest {

    private static GenericContainer<?> ganacheContainer;
    private static Web3j web3j;
    private static AnchorRegistry registry;
    // =====================================================================
    // WARNING: DO NOT USE IN PRODUCTION
    // This is the predefined deterministic account #0 in Ganache.
    // It is strictly for local testing and contains no real funds.
    // =====================================================================
    private static final String PRIVATE_KEY = "0x4f3edf983ac636a65a842ce7c78d9aa706d3b113bce9c46f30d7d21715b23b1d";
    private static final long CHAIN_ID = 1337;

    @BeforeAll
    static void startGanache() throws Exception {
        ganacheContainer = new GenericContainer<>(DockerImageName.parse("trufflesuite/ganache:latest"))
                .withExposedPorts(8545)
                .withCommand("--deterministic")
                .waitingFor(Wait.forLogMessage(".*Listening on.*", 1));

        ganacheContainer.start();

        String rpcUrl = "http://" + ganacheContainer.getHost() + ":" + ganacheContainer.getMappedPort(8545);
        web3j = Web3j.build(new HttpService(rpcUrl));

        Credentials credentials = Credentials.create(PRIVATE_KEY);
        TransactionManager tm = new RawTransactionManager(web3j, credentials, web3j.ethChainId().send().getChainId().longValue());

        String binary = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("src/test/resources/solidity/build/AnchorRegistry.bin"))).trim();
        String txHash = tm.sendTransaction(
                DefaultGasProvider.GAS_PRICE,
                DefaultGasProvider.GAS_LIMIT,
                null,
                binary,
                BigInteger.ZERO
        ).getTransactionHash();

        org.web3j.tx.response.TransactionReceiptProcessor receiptProcessor = new org.web3j.tx.response.PollingTransactionReceiptProcessor(web3j, 1000, 15);
        TransactionReceipt deployReceipt = receiptProcessor.waitForTransactionReceipt(txHash);

        registry = AnchorRegistry.load(deployReceipt.getContractAddress(), web3j, credentials, new DefaultGasProvider());
    }

    @AfterAll
    static void stopGanache() {
        if (ganacheContainer != null) {
            ganacheContainer.stop();
        }
    }

    @Test
    void endToEndPollerTransitionToAnchored() throws Exception {
        // 1. Submit a REAL transaction to Ganache
        String expectedRootHex = "0x1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef";
        byte[] expectedRootBytes = Numeric.hexStringToByteArray(expectedRootHex);
        
        TransactionReceipt receipt = registry.anchorRoot(expectedRootBytes).send();
        String txHash = receipt.getTransactionHash();
        
        // 2. Setup the Port and the Poller
        Web3jBlockchainTransactionReceiptAdapter receiptAdapter = new Web3jBlockchainTransactionReceiptAdapter(web3j);
        BlockchainAnchorRepositoryPort repositoryPort = mock(BlockchainAnchorRepositoryPort.class);
        
        AnchorConfirmationPoller poller = new AnchorConfirmationPoller(repositoryPort, receiptAdapter);
        // We set required confirmations to 0 because Ganache mines instantly and stays on that block unless we mine more
        ReflectionTestUtils.setField(poller, "confirmationsRequired", 0L);
        ReflectionTestUtils.setField(poller, "receiptTimeoutSeconds", 3600L);
        
        // 3. Mock the repository to return the batch in SUBMITTED state
        MerkleBatch batch = new MerkleBatch("batch-ganache", 1, 10, expectedRootHex, Instant.now(), AnchorStatus.SUBMITTED,
                "test-network", registry.getContractAddress(), 1L, txHash, Instant.now().minusSeconds(10), null, null, null);
        
        when(repositoryPort.findSubmittedOlderFirst()).thenReturn(List.of(batch));

        // 4. Run the Poller!
        poller.pollSubmittedBatches();
        
        // 5. Verify the transition was correct end-to-end
        // It should have queried Web3j, got the receipt, decoded the root, compared the hex against the domain byte[], and marked ANCHORED.
        verify(repositoryPort).markAnchored(eq("batch-ganache"), eq(receipt.getBlockNumber().longValue()), any(Instant.class));
        verify(repositoryPort, never()).markAnchorMismatch(anyString());
        verify(repositoryPort, never()).markFailed(anyString());
        verify(repositoryPort, never()).markStuck(anyString());
    }
}
