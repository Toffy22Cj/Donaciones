package com.traceability.crypto.infrastructure.web3j;

import com.traceability.crypto.domain.AnchorStatus;
import com.traceability.crypto.domain.MerkleBatch;
import org.junit.jupiter.api.Test;
import org.web3j.crypto.Credentials;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.Request;
import org.web3j.protocol.core.Response;
import org.web3j.protocol.core.methods.request.EthFilter;
import org.web3j.protocol.core.methods.response.EthBlockNumber;
import org.web3j.protocol.core.methods.response.EthLog;
import org.web3j.protocol.exceptions.TransactionException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Un reintento con "nonce too low": si la raíz del lote ya está en el contrato (evento RootStored de esta cartera),
 * ese envío llegó y se devuelve su transacción; si no, o si el nodo falla, no se inventa nada.
 */
class AnchorReconciliationTest {

    private static final Credentials WALLET =
            Credentials.create("0x4f3edf983ac636a65a842ce7c78d9aa706d3b113bce9c46f30d7d21715b23b1d");
    private static final String ROOT = "0x" + "ab".repeat(32);
    private static final MerkleBatch BATCH = new MerkleBatch("b1", Map.of(), ROOT, Instant.now(),
            AnchorStatus.SUBMITTING, "sepolia", "0x00000000000000000000000000000000000000aa", 4L, null, null, null,
            null, null);

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Web3j node(long latest, List<EthLog> answers, List<EthFilter> seen) throws Exception {
        Web3j web3j = mock(Web3j.class);
        EthBlockNumber number = new EthBlockNumber();
        number.setResult("0x" + Long.toHexString(latest));
        Request blockReq = mock(Request.class);
        when(blockReq.send()).thenReturn(number);
        when(web3j.ethBlockNumber()).thenReturn(blockReq);
        java.util.Iterator<EthLog> it = answers.iterator();
        when(web3j.ethGetLogs(any())).thenAnswer(inv -> {
            seen.add(inv.getArgument(0));
            Request logsReq = mock(Request.class);
            when(logsReq.send()).thenReturn(it.hasNext() ? it.next() : empty());
            return logsReq;
        });
        return web3j;
    }

    private static EthLog empty() {
        EthLog log = new EthLog();
        log.setResult(new ArrayList<>());
        return log;
    }

    private static EthLog withTx(String txHash) {
        EthLog.LogObject entry = new EthLog.LogObject();
        entry.setTransactionHash(txHash);
        EthLog log = new EthLog();
        log.setResult(new ArrayList<>(List.of(entry)));
        return log;
    }

    private static Web3jBlockchainAnchorAdapter adapter(Web3j web3j) {
        return new Web3jBlockchainAnchorAdapter(web3j, WALLET, 11155111L);
    }

    @Test
    void recognisesNonceTooLow_anywhereInTheCauseChain() {
        Exception e = new RuntimeException("x", new java.util.concurrent.ExecutionException(
                new TransactionException("JsonRpcError thrown with code -32000. Message: nonce too low: next nonce 5, tx nonce 4")));
        assertThat(Web3jBlockchainAnchorAdapter.nonceTooLow(e)).isTrue();
        assertThat(Web3jBlockchainAnchorAdapter.nonceTooLow(new RuntimeException("insufficient funds"))).isFalse();
    }

    @Test
    void ifTheRootIsInTheContract_itsTransactionIsReturned_searchingBackwardsInChunks() throws Exception {
        List<EthFilter> seen = new ArrayList<>();
        Web3j web3j = node(10_000, List.of(empty(), withTx("0xfeed")), seen);
        Optional<String> tx = adapter(web3j).findAnchorTransaction(BATCH);
        assertThat(tx).contains("0xfeed");
        assertThat(seen).hasSize(2);
        // el primer tramo acaba en el último bloque; los topics llevan el evento, la raíz y esta cartera
        assertThat(seen.get(0).getToBlock().getValue()).isEqualTo("0x2710");
        assertThat(seen.get(0).getTopics()).hasSize(3);
        assertThat(String.valueOf(seen.get(0).getTopics().get(1).getValue())).isEqualTo(ROOT);
        assertThat(String.valueOf(seen.get(0).getTopics().get(2).getValue()).toLowerCase())
                .endsWith(WALLET.getAddress().substring(2).toLowerCase());
    }

    @Test
    void ifTheRootIsNotThere_orTheNodeRefusesTheRange_nothingIsInvented() throws Exception {
        assertThat(adapter(node(10_000, List.of(), new ArrayList<>())).findAnchorTransaction(BATCH)).isEmpty();
        EthLog refused = new EthLog();
        refused.setError(new Response.Error(-32600, "block range too large"));
        assertThat(adapter(node(10_000, List.of(refused), new ArrayList<>())).findAnchorTransaction(BATCH)).isEmpty();
    }
}
