package com.traceability.api.auth;

import com.traceability.contracts.authentication.AcceptanceRequiredException;
import com.traceability.contracts.authentication.AcceptedLegalVersions;
import com.traceability.contracts.authentication.AuthenticateAccountPort;
import com.traceability.contracts.authentication.AuthenticatedAccount;
import com.traceability.contracts.authentication.AuthenticationFailedException;
import com.traceability.contracts.authentication.EmailNotVerifiedException;
import com.traceability.contracts.authentication.TokenIssuerPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;

/**
 * {@code POST /api/v1/auth/login} (ficha ID-01; ADR-038 §2.7; ADR-047):
 * <ul>
 *   <li>email o contraseña vacíos → 400, sin llamar a la autenticación (ID01-D2);</li>
 *   <li>cualquier fallo de autenticación → el mismo 401, sin motivo (ID01-D1). Se decide antes que las aceptaciones:
 *       una contraseña incorrecta nunca da 403 (ADR-050 Enmienda 1, parte 3, E3-2);</li>
 *   <li>contraseña correcta, cuenta activa y correo sin verificar → {@code 403 EmailNotVerified}, sin token y sin datos
 *       (ADR-050 Enmienda 2, E2-3); va antes que las aceptaciones;</li>
 *   <li>contraseña correcta y versiones legales vigentes pendientes que el cuerpo no trae ({@code acceptedTermsVersion?},
 *       {@code acceptedDataPolicyVersion?}) → {@code 403 AcceptanceRequired} con {@code {pendingTermsVersion?,
 *       pendingDataPolicyVersion?}} y sin token (ADR-050 D3, Q1 (a); E3-4; P-R5-5);</li>
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

    /** Las dos versiones son opcionales: solo hacen falta cuando la cuenta tiene alguna pendiente (ADR-050 D3). */
    public record LoginRequest(String email, String password, String acceptedTermsVersion,
                               String acceptedDataPolicyVersion) {}

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
            account = authenticateAccountPort.authenticate(request.email(), request.password(),
                    new AcceptedLegalVersions(request.acceptedTermsVersion(), request.acceptedDataPolicyVersion()));
        } catch (AuthenticationFailedException e) {
            log.debug("Login rejected");
            return problem(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        } catch (EmailNotVerifiedException e) {
            log.debug("Login requires a verified email");
            return emailNotVerified();
        } catch (AcceptanceRequiredException e) {
            log.debug("Login requires accepting the current legal versions");
            return acceptanceRequired(e);
        }

        try {
            return ResponseEntity.ok(new LoginResponse(tokenIssuerPort.issue(account.accountId(), issuedAt(account))));
        } catch (RuntimeException e) {
            log.error("Token issuance failed for an authenticated account: {}", e.getClass().getSimpleName());
            return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Token could not be issued");
        }
    }

    /** ADR-050 Enmienda 1, E1-2, en {@link LoginIssuedAt}: la misma regla que el login con Google (P-G-3). */
    private Instant issuedAt(AuthenticatedAccount account) {
        return LoginIssuedAt.of(clock, account);
    }

    private static ResponseEntity<ProblemDetail> emailNotVerified() {
        return LoginProblems.emailNotVerified();
    }

    private static ResponseEntity<ProblemDetail> acceptanceRequired(AcceptanceRequiredException e) {
        return LoginProblems.acceptanceRequired(e);
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String detail) {
        return LoginProblems.problem(status, detail);
    }
}
