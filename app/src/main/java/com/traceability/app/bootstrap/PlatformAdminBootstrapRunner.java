package com.traceability.app.bootstrap;

import identity.application.service.BootstrapPlatformAuthorityService;
import identity.domain.model.AccountId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Runner that executes initial platform administrator bootstrap during startup (ADR-038 §2.4).
 */
@Component
@ConditionalOnProperty(name = "traceability.bootstrap.platform-admin.enabled", havingValue = "true")
public class PlatformAdminBootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PlatformAdminBootstrapRunner.class);

    private final BootstrapPlatformAuthorityService bootstrapService;
    private final String bootstrapEmail;

    public PlatformAdminBootstrapRunner(
            BootstrapPlatformAuthorityService bootstrapService,
            @Value("${traceability.bootstrap.platform-admin.email:}") String bootstrapEmail
    ) {
        this.bootstrapService = bootstrapService;
        this.bootstrapEmail = bootstrapEmail;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        try {
            AccountId accountId = bootstrapService.bootstrap(bootstrapEmail);
            log.info("Platform administrator bootstrap succeeded for accountId: {}", accountId.value());
        } catch (Exception e) {
            log.error("Platform administrator bootstrap failed: {}", e.getClass().getSimpleName());
            throw e;
        }
    }
}
