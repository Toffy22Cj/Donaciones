package com.traceability.api.auth;

import com.traceability.contracts.authentication.AcceptanceRequiredException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

/**
 * [Plan] Las respuestas de error de un inicio de sesión, comunes al de contraseña ({@link LoginController}) y al de
 * Google (ADR-050 D9): el mismo 401 sin motivo (ID01-D1), el 403 {@code EmailNotVerified} (Enmienda 2, E2-3) y el 403
 * {@code AcceptanceRequired} (D3, E3-4). Así un cliente no distingue por el cuerpo con qué se intentó entrar.
 */
public final class LoginProblems {

    private static final MediaType PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON;

    private LoginProblems() {
    }

    public static ResponseEntity<ProblemDetail> unauthorized() {
        return problem(HttpStatus.UNAUTHORIZED, "Invalid credentials");
    }

    /** ADR-050 Enmienda 2, E2-3: {@code title = EmailNotVerified}, sin token ni datos de la cuenta. */
    public static ResponseEntity<ProblemDetail> emailNotVerified() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "The email must be verified");
        problem.setTitle("EmailNotVerified");
        return ResponseEntity.status(HttpStatus.FORBIDDEN).contentType(PROBLEM_JSON).body(problem);
    }

    /** E3-4 (P-R5-5): {@code title = AcceptanceRequired} y solo las versiones pendientes; nunca el texto ni un token. */
    public static ResponseEntity<ProblemDetail> acceptanceRequired(AcceptanceRequiredException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN,
                "The current legal versions must be accepted");
        problem.setTitle("AcceptanceRequired");
        if (e.pendingTermsVersion() != null) {
            problem.setProperty("pendingTermsVersion", e.pendingTermsVersion());
        }
        if (e.pendingDataPolicyVersion() != null) {
            problem.setProperty("pendingDataPolicyVersion", e.pendingDataPolicyVersion());
        }
        return ResponseEntity.status(HttpStatus.FORBIDDEN).contentType(PROBLEM_JSON).body(problem);
    }

    public static ResponseEntity<ProblemDetail> problem(HttpStatus status, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(status.getReasonPhrase());
        return ResponseEntity.status(status).contentType(PROBLEM_JSON).body(problem);
    }
}
