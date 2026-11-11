package com.traceability.app.web.auth;

import com.traceability.api.auth.LoginIssuedAt;
import com.traceability.api.web.InvalidRequestFieldException;
import com.traceability.contracts.authentication.AuthenticatedAccount;
import com.traceability.contracts.authentication.TokenIssuerPort;
import identity.application.service.CreateAccountService;
import identity.domain.exception.LegalVersionNotCurrentException;
import identity.domain.model.Account;
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
 * [Propuesta] {@code POST /api/v1/auth/google/register} (ADR-050 Enmienda 3, E3-1 a E3-4), pública. Cuerpo
 * {@code {idToken, password, acceptedTermsVersion, acceptedDataPolicyVersion}}: el alta de
 * {@code POST /auth/register} con el correo que prueba Google en lugar de uno escrito.
 * <ul>
 *   <li>{@code 201 {accountId, status, token}}: la cuenta nace verificada, con las aceptaciones y vinculada, así que
 *       la sesión empieza ya (el {@code iat} de E1-2, como el login);</li>
 *   <li>{@code idToken} o contraseña vacíos → 400 sin llamar al dominio; versiones ausentes o no vigentes → 400 con el
 *       campo (ADR-050 D2); contraseña corta → 400 {@code PasswordTooShort};</li>
 *   <li>{@code idToken} inválido → 400 {@code GoogleIdTokenInvalid}; Google sin responder → 503
 *       {@code GoogleSignInUnavailable};</li>
 *   <li>esa cuenta de Google ya vinculada → 409 {@code GoogleAccountAlreadyLinked}; el correo ya registrado → 409
 *       {@code DuplicateEmail}.</li>
 * </ul>
 * Nunca registra el {@code idToken}, la contraseña ni el correo.
 */
@RestController
public class GoogleRegistrationController {

    private static final Logger log = LoggerFactory.getLogger(GoogleRegistrationController.class);

    public record GoogleRegisterRequest(String idToken, String password, String acceptedTermsVersion,
                                        String acceptedDataPolicyVersion) {}

    public record GoogleRegisterResponse(String accountId, String status, String token) {}

    private final CreateAccountService accounts;
    private final TokenIssuerPort tokens;
    private final Clock clock;

    public GoogleRegistrationController(CreateAccountService accounts, TokenIssuerPort tokens,
                                        ObjectProvider<Clock> clock) {
        this.accounts = accounts;
        this.tokens = tokens;
        this.clock = clock.getIfAvailable(Clock::systemUTC);
    }

    @PostMapping("/api/v1/auth/google/register")
    public ResponseEntity<GoogleRegisterResponse> register(@RequestBody(required = false) GoogleRegisterRequest request) {
        if (request == null || !StringUtils.hasText(request.idToken())) {
            throw new InvalidRequestFieldException("idToken");
        }
        if (!StringUtils.hasText(request.password())) {
            throw new InvalidRequestFieldException("password");
        }
        Account account;
        try {
            account = accounts.createAccountWithGoogle(request.idToken(), request.password(),
                    request.acceptedTermsVersion(), request.acceptedDataPolicyVersion());
        } catch (LegalVersionNotCurrentException e) {
            throw new InvalidRequestFieldException(e.getField());
        }
        String accountId = account.getAccountId().value();
        String token;
        try {
            token = tokens.issue(accountId, LoginIssuedAt.of(clock,
                    new AuthenticatedAccount(accountId, account.getCredentialsNotBefore())));
        } catch (RuntimeException e) {
            // la cuenta ya existe: la persona puede entrar con Google o con su contraseña
            log.error("Token issuance failed after a Google registration: {}", e.getClass().getSimpleName());
            token = null;
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new GoogleRegisterResponse(accountId, account.getStatus().name(), token));
    }
}
