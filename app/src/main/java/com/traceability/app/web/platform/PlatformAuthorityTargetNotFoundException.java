package com.traceability.app.web.platform;

/** La cuenta a la que se concede o revoca la autoridad de plataforma no existe (§3.2). Sin datos de la cuenta. */
public class PlatformAuthorityTargetNotFoundException extends RuntimeException {
    public PlatformAuthorityTargetNotFoundException() {
        super("Platform authority target account NotFound");
    }
}
