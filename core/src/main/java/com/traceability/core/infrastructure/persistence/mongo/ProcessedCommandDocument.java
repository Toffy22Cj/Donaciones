package com.traceability.core.infrastructure.persistence.mongo;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import java.time.Instant;

@Document(collection = "processed_commands")
public class ProcessedCommandDocument {
    @Id
    private String commandId;
    private Instant processedAt;

    public ProcessedCommandDocument() {}

    public ProcessedCommandDocument(String commandId, Instant processedAt) {
        this.commandId = commandId;
        this.processedAt = processedAt;
    }

    public String getCommandId() {
        return commandId;
    }

    public void setCommandId(String commandId) {
        this.commandId = commandId;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }

    public void setProcessedAt(Instant processedAt) {
        this.processedAt = processedAt;
    }
}
