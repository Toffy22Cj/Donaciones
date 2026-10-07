package com.traceability.api.auth.jwt;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWT;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.JWTParser;
import com.nimbusds.jwt.SignedJWT;
import com.traceability.api.auth.jwt.JwtVerification.RejectReason;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.text.ParseException;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * Verificación del JWT en el orden exacto de ADR-047 D5 (P1):
 * <ol>
 *   <li>el {@code alg} debe ser {@code HS256} (lista blanca; {@code none} y cualquier otro se rechazan);</li>
 *   <li>el {@code kid} se busca <strong>solo</strong> como clave del mapa de claves configuradas (la anterior,
 *       únicamente hasta su {@code accept-until}); nunca se usa para construir nada. Un {@code kid} desconocido se
 *       rechaza;</li>
 *   <li>la firma, con la clave elegida por el {@code kid} y ninguna otra;</li>
 *   <li>{@code exp}, con la tolerancia de reloj.</li>
 * </ol>
 * La resolución del principal ({@code resolvePrincipal}) es el paso 5 y la hace {@link JwtAuthFilter}.
 */
@Component
public class JwtTokenVerifier {

    private record Key(byte[] secret, Instant acceptUntil) {}

    private final Map<String, Key> keysByKid;
    private final JwtSecurityProperties properties;
    private final Clock clock;

    @Autowired
    public JwtTokenVerifier(JwtSecurityProperties properties) {
        this(properties, Clock.systemUTC());
    }

    JwtTokenVerifier(JwtSecurityProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        Map<String, Key> keys = new HashMap<>();
        keys.put(properties.getKid(), new Key(properties.signingSecretBytes(), null));
        if (properties.hasPreviousKey()) {
            keys.put(properties.getPreviousKid(), new Key(properties.previousSigningSecretBytes(), properties.previousAcceptUntilInstant()));
        }
        this.keysByKid = Map.copyOf(keys);
    }

    public JwtVerification verify(String token) {
        if (!StringUtils.hasText(token)) {
            return JwtVerification.rejected(RejectReason.MALFORMED);
        }
        JWT jwt;
        try {
            jwt = JWTParser.parse(token);
        } catch (ParseException e) {
            return JwtVerification.rejected(RejectReason.MALFORMED);
        }

        // 1. alg
        if (!(jwt instanceof SignedJWT signed) || !JWSAlgorithm.HS256.equals(jwt.getHeader().getAlgorithm())) {
            return JwtVerification.rejected(RejectReason.ALGORITHM);
        }
        JWSHeader header = signed.getHeader();
        Instant now = clock.instant();

        // 2. kid: solo búsqueda en el mapa
        Key key = header.getKeyID() == null ? null : keysByKid.get(header.getKeyID());
        if (key == null || (key.acceptUntil() != null && now.isAfter(key.acceptUntil()))) {
            return JwtVerification.rejected(RejectReason.UNKNOWN_KID);
        }

        // 3. firma
        try {
            if (!signed.verify(new MACVerifier(key.secret()))) {
                return JwtVerification.rejected(RejectReason.SIGNATURE);
            }
        } catch (JOSEException e) {
            return JwtVerification.rejected(RejectReason.SIGNATURE);
        }

        // 4. exp
        JWTClaimsSet claims;
        try {
            claims = signed.getJWTClaimsSet();
        } catch (ParseException e) {
            return JwtVerification.rejected(RejectReason.MALFORMED);
        }
        Date exp = claims.getExpirationTime();
        if (exp == null || !StringUtils.hasText(claims.getSubject())) {
            return JwtVerification.rejected(RejectReason.MISSING_CLAIMS);
        }
        if (now.isAfter(exp.toInstant().plus(properties.getClockSkew()))) {
            return JwtVerification.rejected(RejectReason.EXPIRED);
        }
        return JwtVerification.accepted(claims.getSubject());
    }
}
