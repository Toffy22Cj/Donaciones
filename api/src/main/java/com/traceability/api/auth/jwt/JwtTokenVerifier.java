package com.traceability.api.auth.jwt;

import org.springframework.stereotype.Component;

import java.time.Clock;

/** Skeleton B3. */
@Component
public class JwtTokenVerifier {

    @org.springframework.beans.factory.annotation.Autowired
    public JwtTokenVerifier(JwtSecurityProperties properties) {
        this(properties, Clock.systemUTC());
    }

    JwtTokenVerifier(JwtSecurityProperties properties, Clock clock) {
    }

    public JwtVerification verify(String token) {
        return JwtVerification.accepted("skeleton");
    }
}
