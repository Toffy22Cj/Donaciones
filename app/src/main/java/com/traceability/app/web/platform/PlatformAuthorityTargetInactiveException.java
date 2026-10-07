package com.traceability.app.web.platform;

/** La cuenta a la que se concede o revoca la autoridad de plataforma está inactiva (§3.2). Sin datos de la cuenta. */
public class PlatformAuthorityTargetInactiveException extends RuntimeException {
    public PlatformAuthorityTargetInactiveException() {
        super("Platform authority target account Inactive");
    }
}
