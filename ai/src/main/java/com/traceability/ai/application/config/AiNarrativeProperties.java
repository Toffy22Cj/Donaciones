package com.traceability.ai.application.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
@ConfigurationProperties(prefix = "ai.narrative")
public class AiNarrativeProperties {
    
    private Duration fallbackRetryInterval = Duration.ofMinutes(15);
    private String promptTemplateVersion = "v1.0";
    private String modelIdentifier = "gpt-4o-mini";
    
    public Duration getFallbackRetryInterval() {
        return fallbackRetryInterval;
    }
    
    public void setFallbackRetryInterval(Duration fallbackRetryInterval) {
        this.fallbackRetryInterval = fallbackRetryInterval;
    }

    public String getPromptTemplateVersion() {
        return promptTemplateVersion;
    }

    public void setPromptTemplateVersion(String promptTemplateVersion) {
        this.promptTemplateVersion = promptTemplateVersion;
    }

    public String getModelIdentifier() {
        return modelIdentifier;
    }

    public void setModelIdentifier(String modelIdentifier) {
        this.modelIdentifier = modelIdentifier;
    }
}
