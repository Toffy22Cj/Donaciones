package com.traceability.api.auth.jwt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * Configuración del JWT (ADR-047 D3 y D4; plan B3 §2.3), con <em>fail-fast</em> al arrancar.
 *
 * <ul>
 *   <li>{@code signing-secret}: obligatorio, de al menos 32 bytes, sin valor por defecto ({@code JWT_SIGNING_SECRET}).</li>
 *   <li>{@code kid}: obligatorio; identifica la clave actual en la cabecera del token.</li>
 *   <li>Clave anterior opcional: {@code previous-signing-secret}, {@code previous-kid} y {@code previous-accept-until}
 *       van juntos. El fin de aceptación es obligatorio y no puede quedar más allá de arranque + {@code ttl} (P2).</li>
 * </ul>
 *
 * <p>Ningún mensaje de error ni de log incluye un secreto (ADR-047 D7).
 */
@Component
@ConfigurationProperties(prefix = "traceability.security.jwt")
public class JwtSecurityProperties implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(JwtSecurityProperties.class);
    static final int MIN_SECRET_BYTES = 32;

    private String signingSecret;
    private String kid;
    private Duration ttl = Duration.ofHours(8);
    private Duration clockSkew = Duration.ofSeconds(30);
    private String previousSigningSecret;
    private String previousKid;
    private String previousAcceptUntil;

    private Clock clock = Clock.systemUTC();
    private Instant previousAcceptUntilInstant;

    @Override
    public void afterPropertiesSet() {
        requireSecret("signing-secret (JWT_SIGNING_SECRET)", signingSecret);
        if (!StringUtils.hasText(kid) || isUnresolved(kid)) {
            throw new IllegalStateException("traceability.security.jwt.kid must be configured");
        }
        if (ttl == null || ttl.isNegative() || ttl.isZero()) {
            throw new IllegalStateException("traceability.security.jwt.ttl must be positive");
        }
        if (clockSkew == null || clockSkew.isNegative()) {
            throw new IllegalStateException("traceability.security.jwt.clock-skew must not be negative");
        }
        validatePreviousKey();
    }

    private void validatePreviousKey() {
        boolean anyPrevious = StringUtils.hasText(previousSigningSecret) || StringUtils.hasText(previousKid)
                || StringUtils.hasText(previousAcceptUntil);
        if (!anyPrevious) {
            previousAcceptUntilInstant = null;
            return;
        }
        requireSecret("previous-signing-secret (JWT_SIGNING_SECRET_PREVIOUS)", previousSigningSecret);
        if (!StringUtils.hasText(previousKid) || isUnresolved(previousKid)) {
            throw new IllegalStateException("traceability.security.jwt.previous-kid is required with a previous key");
        }
        if (previousKid.equals(kid)) {
            throw new IllegalStateException("traceability.security.jwt.previous-kid must differ from kid");
        }
        if (!StringUtils.hasText(previousAcceptUntil) || isUnresolved(previousAcceptUntil)) {
            throw new IllegalStateException(
                    "JWT_SIGNING_SECRET_PREVIOUS_ACCEPT_UNTIL is required with a previous key (ADR-047 D4)");
        }
        Instant until;
        try {
            until = Instant.parse(previousAcceptUntil.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalStateException("JWT_SIGNING_SECRET_PREVIOUS_ACCEPT_UNTIL must be an ISO-8601 instant");
        }
        Instant latest = clock.instant().plus(ttl);
        if (until.isAfter(latest)) {
            throw new IllegalStateException("JWT_SIGNING_SECRET_PREVIOUS_ACCEPT_UNTIL (" + until
                    + ") must not be later than startup + ttl (" + latest + ") (ADR-047 D4)");
        }
        previousAcceptUntilInstant = until;
        log.warn("JWT previous signing key '{}' is still accepted until {}. Remove it from the configuration "
                + "once that instant has passed (ADR-047 D4).", previousKid, until);
    }

    private static void requireSecret(String name, String value) {
        if (!StringUtils.hasText(value) || isUnresolved(value)) {
            throw new IllegalStateException("JWT " + name + " must be configured");
        }
        if (value.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("JWT " + name + " must be at least " + MIN_SECRET_BYTES + " bytes");
        }
    }

    private static boolean isUnresolved(String value) {
        return value.trim().startsWith("${");
    }

    public boolean hasPreviousKey() {
        return previousAcceptUntilInstant != null;
    }

    /** Fin de aceptación de la clave anterior, o {@code null} si no hay clave anterior. */
    public Instant previousAcceptUntilInstant() {
        return previousAcceptUntilInstant;
    }

    byte[] signingSecretBytes() {
        return signingSecret.getBytes(StandardCharsets.UTF_8);
    }

    byte[] previousSigningSecretBytes() {
        return previousSigningSecret.getBytes(StandardCharsets.UTF_8);
    }

    /** Solo para tests: instante de arranque contra el que se valida {@code previous-accept-until}. */
    void setClock(Clock clock) {
        this.clock = clock;
    }

    public String getSigningSecret() { return signingSecret; }
    public void setSigningSecret(String signingSecret) { this.signingSecret = signingSecret; }
    public String getKid() { return kid; }
    public void setKid(String kid) { this.kid = kid; }
    public Duration getTtl() { return ttl; }
    public void setTtl(Duration ttl) { this.ttl = ttl; }
    public Duration getClockSkew() { return clockSkew; }
    public void setClockSkew(Duration clockSkew) { this.clockSkew = clockSkew; }
    public String getPreviousSigningSecret() { return previousSigningSecret; }
    public void setPreviousSigningSecret(String previousSigningSecret) { this.previousSigningSecret = previousSigningSecret; }
    public String getPreviousKid() { return previousKid; }
    public void setPreviousKid(String previousKid) { this.previousKid = previousKid; }
    public String getPreviousAcceptUntil() { return previousAcceptUntil; }
    public void setPreviousAcceptUntil(String previousAcceptUntil) { this.previousAcceptUntil = previousAcceptUntil; }

    /** Nunca expone los secretos (ADR-047 D7). */
    @Override
    public String toString() {
        return "JwtSecurityProperties{kid=" + kid + ", ttl=" + ttl + ", clockSkew=" + clockSkew
                + ", previousKid=" + previousKid + ", previousAcceptUntil=" + previousAcceptUntil + "}";
    }
}
