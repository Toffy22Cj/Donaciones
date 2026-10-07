package com.traceability.api.auth.jwt;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;

/**
 * Fabrica tokens a mano (también inválidos) para los tests de la definición de hecho de ADR-047 (P4).
 */
public final class JwtTestSupport {

    public static final String CURRENT_SECRET = "current-signing-secret-0123456789abcdef";   // 39 bytes
    public static final String PREVIOUS_SECRET = "previous-signing-secret-0123456789abcdef"; // 40 bytes
    public static final String CURRENT_KID = "k2";
    public static final String PREVIOUS_KID = "k1";
    public static final Instant NOW = Instant.parse("2026-10-07T10:00:00Z");

    private JwtTestSupport() {}

    public static Clock clockAt(Instant instant) {
        return Clock.fixed(instant, ZoneOffset.UTC);
    }

    /** Clave actual sola, validada con el reloj dado. */
    public static JwtSecurityProperties currentOnly(Clock clock) throws Exception {
        JwtSecurityProperties p = new JwtSecurityProperties();
        p.setSigningSecret(CURRENT_SECRET);
        p.setKid(CURRENT_KID);
        p.setClock(clock);
        p.afterPropertiesSet();
        return p;
    }

    /** Clave actual y anterior, aceptada hasta {@code acceptUntil}; validada en {@code startup}. */
    public static JwtSecurityProperties withPrevious(Instant startup, Instant acceptUntil) throws Exception {
        JwtSecurityProperties p = new JwtSecurityProperties();
        p.setSigningSecret(CURRENT_SECRET);
        p.setKid(CURRENT_KID);
        p.setPreviousSigningSecret(PREVIOUS_SECRET);
        p.setPreviousKid(PREVIOUS_KID);
        p.setPreviousAcceptUntil(acceptUntil.toString());
        p.setClock(clockAt(startup));
        p.afterPropertiesSet();
        return p;
    }

    public static JWTClaimsSet claims(String sub, Instant iat, Instant exp) {
        JWTClaimsSet.Builder b = new JWTClaimsSet.Builder();
        if (sub != null) b.subject(sub);
        if (iat != null) b.issueTime(Date.from(iat));
        if (exp != null) b.expirationTime(Date.from(exp));
        return b.build();
    }

    public static JWTClaimsSet validClaims() {
        return claims("acc-1", NOW, NOW.plus(Duration.ofHours(8)));
    }

    public static String hs256(String kid, String secret, JWTClaimsSet claims) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).keyID(kid).type(JOSEObjectType.JWT).build(), claims);
        jwt.sign(new MACSigner(secret.getBytes(StandardCharsets.UTF_8)));
        return jwt.serialize();
    }

    public static String hs512(String kid, JWTClaimsSet claims) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS512).keyID(kid).build(), claims);
        jwt.sign(new MACSigner((CURRENT_SECRET + CURRENT_SECRET).getBytes(StandardCharsets.UTF_8)));
        return jwt.serialize();
    }

    public static String rs256(String kid, JWTClaimsSet claims) throws Exception {
        RSAKey rsa = new RSAKeyGenerator(2048).keyID(kid).generate();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(kid).build(), claims);
        jwt.sign(new RSASSASigner(rsa));
        return jwt.serialize();
    }

    /** {@code alg: none}, sin firma ({@code header.payload.}). */
    public static String algNone(JWTClaimsSet claims) {
        return new PlainJWT(claims).serialize();
    }

    /** Cambia el payload de un token firmado y conserva su cabecera y su firma. */
    public static String withTamperedPayload(String token, JWTClaimsSet newClaims) {
        String[] parts = token.split("\\.");
        String payload = com.nimbusds.jose.util.Base64URL.encode(newClaims.toString()).toString();
        return parts[0] + "." + payload + "." + parts[2];
    }
}
