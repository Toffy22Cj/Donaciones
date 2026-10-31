package com.traceability.app.web.auth;

import com.traceability.api.auth.LoginIssuedAt;
import com.traceability.api.auth.LoginProblems;
import com.traceability.contracts.authentication.AcceptanceRequiredException;
import com.traceability.contracts.authentication.AcceptedLegalVersions;
import com.traceability.contracts.authentication.AuthenticatedAccount;
import com.traceability.contracts.authentication.AuthenticationFailedException;
import com.traceability.contracts.authentication.EmailNotVerifiedException;
import com.traceability.contracts.authentication.TokenIssuerPort;
import identity.application.service.GoogleSignInService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;

/**
 * [Plan] {@code POST /api/v1/auth/google} (ADR-050 D9), pública. Cuerpo {@code {idToken, acceptedTermsVersion?,
 * acceptedDataPolicyVersion?}} (P-G-2). Responde como el login con contraseña ({@code LoginController}):
 * {@code 200 {token}} con el {@code iat} de E1-2 ({@link LoginIssuedAt}, P-G-3); el mismo 401 sin motivo para un
 * {@code idToken} inválido o una cuenta sin vínculo o {@code INACTIVE}; 403 {@code EmailNotVerified}; 403
 * {@code AcceptanceRequired}. Si Google no responde, 503 {@code GoogleSignInUnavailable} (P-G-9). Nunca registra el
 * {@code idToken}.
 */
@RestController
public class GoogleSignInController {

    private static final Logger log = LoggerFactory.getLogger(GoogleSignInController.class);

    public record GoogleSignInRequest(String idToken, String acceptedTermsVersion, String acceptedDataPolicyVersion) {}

    public record GoogleSignInResponse(String token) {}

    private final GoogleSignInService google;
    private final TokenIssuerPort tokens;
    private final Clock clock;

    public GoogleSignInController(GoogleSignInService google, TokenIssuerPort tokens, ObjectProvider<Clock> clock) {
        this.google = google;
        this.tokens = tokens;
        this.clock = clock.getIfAvailable(Clock::systemUTC);
    }

    @PostMapping("/api/v1/auth/google")
    public ResponseEntity<?> signIn(@RequestBody(required = false) GoogleSignInRequest request) {
        if (request == null || !StringUtils.hasText(request.idToken())) {
            return LoginProblems.problem(HttpStatus.BAD_REQUEST, "idToken is required");
        }
        AuthenticatedAccount account;
        try {
            account = google.authenticate(request.idToken(),
                    new AcceptedLegalVersions(request.acceptedTermsVersion(), request.acceptedDataPolicyVersion()));
        } catch (AuthenticationFailedException e) {
            log.debug("Google sign-in rejected");
            return LoginProblems.unauthorized();
        } catch (EmailNotVerifiedException e) {
            return LoginProblems.emailNotVerified();
        } catch (AcceptanceRequiredException e) {
            return LoginProblems.acceptanceRequired(e);
        }
        try {
            return ResponseEntity.ok(new GoogleSignInResponse(tokens.issue(account.accountId(), LoginIssuedAt.of(clock, account))));
        } catch (RuntimeException e) {
            log.error("Token issuance failed for a Google sign-in: {}", e.getClass().getSimpleName());
            return LoginProblems.problem(HttpStatus.INTERNAL_SERVER_ERROR, "Token could not be issued");
        }
    }
}
