package com.traceability.api.auth.jwt;

import com.traceability.contracts.authentication.TokenIssuerPort;
import org.springframework.stereotype.Component;

import java.time.Clock;

/** Skeleton B3. */
@Component
public class NimbusTokenIssuer implements TokenIssuerPort {

    @org.springframework.beans.factory.annotation.Autowired
    public NimbusTokenIssuer(JwtSecurityProperties properties) {
        this(properties, Clock.systemUTC());
    }

    NimbusTokenIssuer(JwtSecurityProperties properties, Clock clock) {
    }

    @Override
    public String issue(String accountId) {
        throw new UnsupportedOperationException("B3: pendiente");
    }
}
