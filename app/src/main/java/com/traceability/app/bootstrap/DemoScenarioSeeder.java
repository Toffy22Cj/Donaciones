package com.traceability.app.bootstrap;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Semilla del escenario de la demo (encargo 6, P5). Esqueleto. */
@Component
@Profile("demo-seed")
public class DemoScenarioSeeder {

    /** Lo que la semilla deja creado (para el fichero de credenciales y los tests). */
    public record SeedResult(String organizationId, String pendingOrganizationId, String activeCampaignRef,
                             String activePublicCode, String closedPublicCode, String privatePublicCode,
                             java.util.Map<String, String> trackingCodes, java.nio.file.Path outputFile) {}

    public SeedResult seed(int port) {
        throw new UnsupportedOperationException("pendiente");
    }
}
