package com.traceability.crypto.infrastructure.web3j;

import org.web3j.crypto.Credentials;
import org.web3j.protocol.Web3j;
import org.web3j.tx.RawTransactionManager;

import java.io.IOException;
import java.math.BigInteger;

/**
 * A Web3j TransactionManager that forces an exact nonce instead of resolving it dynamically.
 * This is crucial for avoiding TOCTOU conditions and ensuring that we use the atomic nonce
 * reserved from our persistence layer.
 */
public class ExplicitNonceTransactionManager extends RawTransactionManager {
    
    private final BigInteger explicitNonce;
    
    public ExplicitNonceTransactionManager(Web3j web3j, Credentials credentials, long chainId, BigInteger explicitNonce) {
        super(web3j, credentials, chainId);
        this.explicitNonce = explicitNonce;
    }

    @Override
    protected BigInteger getNonce() throws IOException {
        return explicitNonce;
    }
}
