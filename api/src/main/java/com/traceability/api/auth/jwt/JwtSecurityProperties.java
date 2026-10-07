package com.traceability.api.auth.jwt;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/** Skeleton B3. */
@Component
@ConfigurationProperties(prefix = "traceability.security.jwt")
public class JwtSecurityProperties implements org.springframework.beans.factory.InitializingBean {
    private String signingSecret;
    private String kid;
    private Duration ttl = Duration.ofHours(8);
    private Duration clockSkew = Duration.ofSeconds(30);
    private String previousSigningSecret;
    private String previousKid;
    private String previousAcceptUntil;
    private Clock clock = Clock.systemUTC();

    public String getSigningSecret() { return signingSecret; }
    public void setSigningSecret(String v) { this.signingSecret = v; }
    public String getKid() { return kid; }
    public void setKid(String v) { this.kid = v; }
    public Duration getTtl() { return ttl; }
    public void setTtl(Duration v) { this.ttl = v; }
    public Duration getClockSkew() { return clockSkew; }
    public void setClockSkew(Duration v) { this.clockSkew = v; }
    public String getPreviousSigningSecret() { return previousSigningSecret; }
    public void setPreviousSigningSecret(String v) { this.previousSigningSecret = v; }
    public String getPreviousKid() { return previousKid; }
    public void setPreviousKid(String v) { this.previousKid = v; }
    public String getPreviousAcceptUntil() { return previousAcceptUntil; }
    public void setPreviousAcceptUntil(String v) { this.previousAcceptUntil = v; }
    void setClock(Clock clock) { this.clock = clock; }

    @Override
    public void afterPropertiesSet() {}

    public boolean hasPreviousKey() { return false; }
    public Instant previousAcceptUntilInstant() { return null; }
}
