package com.traceability.app.config;

import identity.application.port.out.PasswordHasherPort;
import identity.infrastructure.security.BCryptPasswordHasherAdapter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.MongoTransactionManager;

@Configuration
public class TraceabilityInfrastructureConfig {

    @Bean
    public MongoTransactionManager transactionManager(MongoDatabaseFactory databaseFactory) {
        return new MongoTransactionManager(databaseFactory);
    }

    // identity's BCrypt adapter is a plain class (not a @Component); app wires it as the PasswordHasherPort.
    @Bean
    public PasswordHasherPort passwordHasherPort() {
        return new BCryptPasswordHasherAdapter();
    }
}
