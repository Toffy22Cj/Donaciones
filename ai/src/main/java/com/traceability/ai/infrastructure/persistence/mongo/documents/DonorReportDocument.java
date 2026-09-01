package com.traceability.ai.infrastructure.persistence.mongo.documents;

import com.traceability.ai.domain.narrative.NarrativeSource;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document(collection = "donor_reports")
public class DonorReportDocument {

    @Id
    private String id;
    
    private String donationId;
    private String narrativeText;
    private NarrativeSource source;
    private String modelIdentifier;
    private String promptTemplateVersion;
    private String sourceFactsHash;
    private long auditFactsSequence;
    private Instant generatedAt;
    private Instant nextRetryAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getDonationId() {
        return donationId;
    }

    public void setDonationId(String donationId) {
        this.donationId = donationId;
    }

    public String getNarrativeText() {
        return narrativeText;
    }

    public void setNarrativeText(String narrativeText) {
        this.narrativeText = narrativeText;
    }

    public NarrativeSource getSource() {
        return source;
    }

    public void setSource(NarrativeSource source) {
        this.source = source;
    }

    public String getModelIdentifier() {
        return modelIdentifier;
    }

    public void setModelIdentifier(String modelIdentifier) {
        this.modelIdentifier = modelIdentifier;
    }

    public String getPromptTemplateVersion() {
        return promptTemplateVersion;
    }

    public void setPromptTemplateVersion(String promptTemplateVersion) {
        this.promptTemplateVersion = promptTemplateVersion;
    }

    public String getSourceFactsHash() {
        return sourceFactsHash;
    }

    public void setSourceFactsHash(String sourceFactsHash) {
        this.sourceFactsHash = sourceFactsHash;
    }

    public long getAuditFactsSequence() {
        return auditFactsSequence;
    }

    public void setAuditFactsSequence(long auditFactsSequence) {
        this.auditFactsSequence = auditFactsSequence;
    }

    public Instant getGeneratedAt() {
        return generatedAt;
    }

    public void setGeneratedAt(Instant generatedAt) {
        this.generatedAt = generatedAt;
    }

    public Instant getNextRetryAt() {
        return nextRetryAt;
    }

    public void setNextRetryAt(Instant nextRetryAt) {
        this.nextRetryAt = nextRetryAt;
    }
}
