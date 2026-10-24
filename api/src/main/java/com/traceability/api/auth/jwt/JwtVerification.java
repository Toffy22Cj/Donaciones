package com.traceability.api.auth.jwt;

import java.time.Instant;

/**
 * Resultado de {@link JwtTokenVerifier#verify}. El motivo del rechazo es interno: solo se registra en DEBUG y nunca
 * llega a la respuesta, que es siempre el mismo 401 (ADR-047 D5). {@code issuedAt} es el {@code iat} del token, que el
 * filtro compara con {@code credentialsNotBefore} (ADR-050 D4).
 */
public record JwtVerification(String subject, Instant issuedAt, RejectReason rejectReason) {

    public enum RejectReason { MALFORMED, ALGORITHM, UNKNOWN_KID, SIGNATURE, EXPIRED, MISSING_CLAIMS }

    public static JwtVerification accepted(String subject, Instant issuedAt) {
        return new JwtVerification(subject, issuedAt, null);
    }

    public static JwtVerification rejected(RejectReason reason) {
        return new JwtVerification(null, null, reason);
    }

    public boolean isAccepted() {
        return rejectReason == null;
    }
}
