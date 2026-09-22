package com.traceability.core.infrastructure.authorization;

import com.traceability.contracts.authorization.IdentityPrincipalPort;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration
public class TestIdentityPrincipalPortConfig {

    @Bean
    @Primary
    public TestIdentityPrincipalPort testIdentityPrincipalPort() {
        return new TestIdentityPrincipalPort();
    }
}
