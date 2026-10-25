package com.traceability.api.auth.jwt;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.traceability.contracts.authentication.TokenIssuanceException;
import com.traceability.contracts.authentication.TokenIssuerPort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.Instant;
import java.util.Date;

/**
 * Emisión del JWT (ADR-047 D2, D4 y D5): HS256 con la clave actual, cabecera {@code kid} y claims exactamente
 * {@code sub}, {@code iat} y {@code exp}. Cualquier fallo es una {@link TokenIssuanceException} sin material
 * criptográfico en el mensaje (D7).
 */
@Component
public class NimbusTokenIssuer implements TokenIssuerPort {

    private final JwtSecurityProperties properties;
    private final Clock clock;

    /** El reloj de la aplicación si hay uno (como los servicios de {@code identity}); si no, el del sistema. */
    @Autowired
    public NimbusTokenIssuer(JwtSecurityProperties properties, ObjectProvider<Clock> clock) {
        this(properties, clock.getIfAvailable(Clock::systemUTC));
    }

    NimbusTokenIssuer(JwtSecurityProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public String issue(String accountId) {
        return issue(accountId, clock.instant());
    }

    /** ADR-050 D5: {@code iat = issuedAt} y {@code exp = issuedAt + ttl}. */
    @Override
    public String issue(String accountId, Instant issuedAt) {
        if (!StringUtils.hasText(accountId)) {
            throw new TokenIssuanceException("Cannot issue a token without accountId", null);
        }
        if (issuedAt == null) {
            throw new TokenIssuanceException("Cannot issue a token without issuedAt", null);
        }
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .subject(accountId)
                    .issueTime(Date.from(issuedAt))
                    .expirationTime(Date.from(issuedAt.plus(properties.getTtl())))
                    .build();
            JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.HS256)
                    .keyID(properties.getKid())
                    .type(JOSEObjectType.JWT)
                    .build();
            SignedJWT jwt = new SignedJWT(header, claims);
            jwt.sign(new MACSigner(properties.signingSecretBytes()));
            return jwt.serialize();
        } catch (Exception e) {
            // Solo el tipo: el mensaje de la causa podría contener material de la clave
            throw new TokenIssuanceException("Token issuance failed: " + e.getClass().getSimpleName(), null);
        }
    }
}
