package com.traceability.app.web.platform;

/** La cuenta que la plataforma quiere reactivar no existe (ADR-050 D7; plan de cierre, P-D7-2). Sin datos de la cuenta. */
public class PlatformAccountNotFoundException extends RuntimeException {
    public PlatformAccountNotFoundException() {
        super("Platform account target NotFound");
    }
}
