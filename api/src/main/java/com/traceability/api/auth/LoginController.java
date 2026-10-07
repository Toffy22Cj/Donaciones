package com.traceability.api.auth;

import com.traceability.contracts.authentication.AuthenticateAccountPort;
import com.traceability.contracts.authentication.AuthenticationFailedException;
import com.traceability.contracts.authentication.TokenIssuerPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/v1/auth/login} (ficha ID-01; ADR-038 §2.7; ADR-047):
 * <ul>
 *   <li>email o contraseña vacíos → 400, sin llamar a la autenticación (ID01-D2);</li>
 *   <li>cualquier fallo de autenticación → el mismo 401, sin motivo (ID01-D1);</li>
 *   <li>cualquier fallo al emitir el token → 500, nunca 401 (ID01-D4);</li>
 *   <li>éxito → {@code {"token": "..."}} y nada más (Q2).</li>
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

    public LoginController(AuthenticateAccountPort authenticateAccountPort, TokenIssuerPort tokenIssuerPort) {
        this.authenticateAccountPort = authenticateAccountPort;
        this.tokenIssuerPort = tokenIssuerPort;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request) {
        if (request == null || !StringUtils.hasText(request.email()) || !StringUtils.hasText(request.password())) {
            return problem(HttpStatus.BAD_REQUEST, "email and password are required");
        }

        String accountId;
        try {
            accountId = authenticateAccountPort.authenticate(request.email(), request.password());
        } catch (AuthenticationFailedException e) {
            log.debug("Login rejected");
            return problem(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        }

        try {
            return ResponseEntity.ok(new LoginResponse(tokenIssuerPort.issue(accountId)));
        } catch (RuntimeException e) {
            log.error("Token issuance failed for an authenticated account: {}", e.getClass().getSimpleName());
            return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Token could not be issued");
        }
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(status.getReasonPhrase());
        return ResponseEntity.status(status).contentType(PROBLEM_JSON).body(problem);
    }
}
