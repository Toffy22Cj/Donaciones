package com.traceability.crypto.infrastructure.web3j;

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
import org.web3j.utils.Numeric;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@Testcontainers
class AnchorRegistryIntegrationTest {

    @Container
    public static GenericContainer<?> ganache = new GenericContainer<>("trufflesuite/ganache:latest")
            .withExposedPorts(8545)
            .withCommand("--deterministic") // Ensures predictable accounts and private keys
            .waitingFor(Wait.forLogMessage(".*Listening on.*", 1));

    private static Web3j web3j;
    private static Credentials credentials;

    @BeforeAll
    static void setup() {
        String rpcUrl = "http://" + ganache.getHost() + ":" + ganache.getMappedPort(8545);
        web3j = Web3j.build(new HttpService(rpcUrl));
        // Deterministic account #0 in Ganache
        credentials = Credentials.create("0x4f3edf983ac636a65a842ce7c78d9aa706d3b113bce9c46f30d7d21715b23b1d");
    }

    @Test
    void shouldDeployContractAndAnchorRootWithCorrectIndexedEventExtraction() throws Exception {
        // 1. Deploy the contract using the binary compiled by Docker
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
        
        assertNotNull(deployReceipt.getContractAddress());
        
        // 2. Load the generated wrapper
        AnchorRegistry registry = AnchorRegistry.load(deployReceipt.getContractAddress(), web3j, credentials, new DefaultGasProvider());
        
        // 3. Anchor a root
        // Simulate a hex string exactly like MerkleBatch.merkleRoot() would return (e.g. 64 hex chars without 0x or with 0x)
        String hexRoot = "0x1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef";
        byte[] expectedBytes = Numeric.hexStringToByteArray(hexRoot);
        
        TransactionReceipt anchorReceipt = registry.anchorRoot(expectedBytes).send();
        assertEquals("0x1", anchorReceipt.getStatus());
        
        // 4. Extract and verify the event, explicitly validating the 'indexed' root behavior
        List<AnchorRegistry.RootStoredEventResponse> events = AnchorRegistry.getRootStoredEvents(anchorReceipt);
        assertEquals(1, events.size(), "Should have emitted exactly one RootStored event");
        
        AnchorRegistry.RootStoredEventResponse event = events.get(0);
        
        // The event root must exactly match the bytes we passed in.
        // This validates that Web3j decodes the 'indexed' bytes32 topic correctly into event.root,
        // which prevents the false ANCHOR_MISMATCH scenario.
        assertArrayEquals(expectedBytes, event.root, "The decoded indexed root bytes must match the exact bytes submitted");
        
        // Validate sender was decoded correctly
        assertEquals(credentials.getAddress().toLowerCase(), event.sender.toLowerCase());
    }
}
