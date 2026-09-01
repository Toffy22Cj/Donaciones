package com.traceability.crypto.infrastructure.persistence.mongo;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "web3_nonce_counter")
public class Web3NonceCounterDocument {

    @Id
    private String id; // Format: <network>-<address>

    private long nextNonce;

    public Web3NonceCounterDocument() {}

    public Web3NonceCounterDocument(String id, long nextNonce) {
        this.id = id;
        this.nextNonce = nextNonce;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public long getNextNonce() {
        return nextNonce;
    }

    public void setNextNonce(long nextNonce) {
        this.nextNonce = nextNonce;
    }
}
