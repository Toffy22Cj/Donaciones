package com.traceability.core.infrastructure.persistence.mongo;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import java.time.Instant;

@Document(collection = "processed_commands")
public class ProcessedCommandDocument {
    @Id
    private String commandId;
    private Instant processedAt;
    /** Resultado que ganó el reclamo (barrera de la división, B1-bis); {@code null} en los reclamos de comando. */
    private String outcome;

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

    public String getOutcome() {
        return outcome;
    }

    public void setOutcome(String outcome) {
        this.outcome = outcome;
    }
}
