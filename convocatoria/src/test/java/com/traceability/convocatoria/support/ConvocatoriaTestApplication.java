package com.traceability.convocatoria.support;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.MongoTransactionManager;

/**
 * Configuración de test del módulo (implementation_plan.md §4.4): en producción el
 * {@link MongoTransactionManager} lo aporta {@code app}; en el primer corte no existe
 * {@code app → convocatoria} (B-1), así que los tests declaran el suyo, igual que
 * {@code IdentityTestApplication} y los {@code TestConfig} de {@code core}.
 */
@SpringBootApplication(scanBasePackages = "com.traceability.convocatoria")
public class ConvocatoriaTestApplication {

    @Bean
    public MongoTransactionManager transactionManager(MongoDatabaseFactory dbFactory) {
        return new MongoTransactionManager(dbFactory);
    }

    /** {@code IdentityPrincipalPort} lo implementa {@code identity}, fuera del classpath del módulo: fake de test. */
    @Bean
    public FakeIdentityPrincipalPort identityPrincipalPort() {
        return new FakeIdentityPrincipalPort();
    }

    /** X1: sin implementación de producción en este corte (ADR-038); fake de test. */
    @Bean
    public FakeOrganizationVerificationPort organizationVerificationPort() {
        return new FakeOrganizationVerificationPort();
    }

    /** Reloj controlable de test; en producción los servicios usan {@code Clock.systemUTC()}. */
    @Bean
    public MutableClock clock() {
        return new MutableClock();
    }
}
