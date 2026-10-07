package com.traceability.api.auth.jwt;

/**
 * Resultado de {@link JwtTokenVerifier#verify}. El motivo del rechazo es interno: solo se registra en DEBUG y nunca
 * llega a la respuesta, que es siempre el mismo 401 (ADR-047 D5).
 */
public record JwtVerification(String subject, RejectReason rejectReason) {

    public enum RejectReason { MALFORMED, ALGORITHM, UNKNOWN_KID, SIGNATURE, EXPIRED, MISSING_CLAIMS }

    public static JwtVerification accepted(String subject) {
        return new JwtVerification(subject, null);
    }

    public static JwtVerification rejected(RejectReason reason) {
        return new JwtVerification(null, reason);
    }

    public boolean isAccepted() {
        return rejectReason == null;
    }
}
