package com.traceability.api.auth;

import com.traceability.contracts.authentication.AuthenticateAccountPort;
import com.traceability.contracts.authentication.AuthenticatedAccount;
import com.traceability.contracts.authentication.AuthenticationFailedException;
import com.traceability.contracts.authentication.TokenIssuerPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * {@code POST /api/v1/auth/login} (ficha ID-01; ADR-038 §2.7; ADR-047):
 * <ul>
 *   <li>email o contraseña vacíos → 400, sin llamar a la autenticación (ID01-D2);</li>
 *   <li>cualquier fallo de autenticación → el mismo 401, sin motivo (ID01-D1);</li>
 *   <li>cualquier fallo al emitir el token → 500, nunca 401 (ID01-D4);</li>
 *   <li>éxito → {@code {"token": "..."}} y nada más (Q2), con {@code iat = max(segundo actual truncado,
 *       credentialsNotBefore)} (ADR-050 Enmienda 1, E1-2): justo después de una revocación, el token del login vale.</li>
 * </ul>
 * Nunca registra la contraseña ni el token (ADR-047 D7).
 */
@RestController
@RequestMapping("/api/v1/auth")
public class LoginController {

    private static final Logger log = LoggerFactory.getLogger(LoginController.class);
    private static final MediaType PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON;

    public record LoginRequest(String email, String password) {}

    public record LoginResponse(String token) {}

    private final AuthenticateAccountPort authenticateAccountPort;
    private final TokenIssuerPort tokenIssuerPort;
    private final Clock clock;

    /** El reloj de la aplicación si hay uno, como el emisor y el verificador del token; si no, el del sistema. */
    @Autowired
    public LoginController(AuthenticateAccountPort authenticateAccountPort, TokenIssuerPort tokenIssuerPort,
                           ObjectProvider<Clock> clock) {
        this(authenticateAccountPort, tokenIssuerPort, clock.getIfAvailable(Clock::systemUTC));
    }

    LoginController(AuthenticateAccountPort authenticateAccountPort, TokenIssuerPort tokenIssuerPort, Clock clock) {
        this.authenticateAccountPort = authenticateAccountPort;
        this.tokenIssuerPort = tokenIssuerPort;
        this.clock = clock;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request) {
        if (request == null || !StringUtils.hasText(request.email()) || !StringUtils.hasText(request.password())) {
            return problem(HttpStatus.BAD_REQUEST, "email and password are required");
        }

        AuthenticatedAccount account;
        try {
            account = authenticateAccountPort.authenticate(request.email(), request.password());
        } catch (AuthenticationFailedException e) {
            log.debug("Login rejected");
            return problem(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        }

        try {
            return ResponseEntity.ok(new LoginResponse(tokenIssuerPort.issue(account.accountId(), issuedAt(account))));
        } catch (RuntimeException e) {
            log.error("Token issuance failed for an authenticated account: {}", e.getClass().getSimpleName());
            return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Token could not be issued");
        }
    }

    /** ADR-050 Enmienda 1, E1-2: {@code max(segundo actual truncado, credentialsNotBefore)}; sin este, el segundo actual. */
    private Instant issuedAt(AuthenticatedAccount account) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        Instant credentialsNotBefore = account.credentialsNotBefore();
        return credentialsNotBefore != null && credentialsNotBefore.isAfter(now) ? credentialsNotBefore : now;
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(status.getReasonPhrase());
        return ResponseEntity.status(status).contentType(PROBLEM_JSON).body(problem);
    }
}
