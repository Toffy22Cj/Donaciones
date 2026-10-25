package com.traceability.app.web.account;

import com.traceability.api.web.CurrentActor;
import com.traceability.api.web.InvalidRequestFieldException;
import com.traceability.contracts.authentication.TokenIssuerPort;
import com.traceability.contracts.authorization.AuthorizationPrincipal;
import identity.application.service.ChangeCredentialsService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * {@code POST /api/v1/account/password} (ADR-050 D5; plan R-3 + R-2). JWT obligatorio; solo la cuenta del JWT.
 * <ul>
 *   <li>{@code currentPassword} o {@code newPassword} vacíos → 400 sin llamar al dominio;</li>
 *   <li>contraseña actual incorrecta → 400 {@code CurrentPasswordIncorrect}, nunca 401 (ADR-043 T-1); nueva de menos de
 *       12 caracteres → 400 {@code PasswordTooShort}. En los dos casos no cambia nada;</li>
 *   <li>éxito → {@code 200 {token}}, {@code Cache-Control: no-store}. El token sale con {@code iat =
 *       credentialsNotBefore}: la sesión actual sigue y todas las demás quedan revocadas (D4).</li>
 * </ul>
 * Si el cambio se confirma y falla la emisión del token → 500 ({@code TokenIssuanceException}): la contraseña sí cambió
 * y todas las sesiones, también la actual, quedaron revocadas. Sin {@code Command-Id}: Identity no lo usa (DH-34).
 * Nunca registra las contraseñas ni el token.
 */
@RestController
public class AccountPasswordController {

    public record ChangePasswordRequest(String currentPassword, String newPassword) {}

    public record ChangePasswordResponse(String token) {}

    private final ChangeCredentialsService credentials;
    private final TokenIssuerPort tokens;

    public AccountPasswordController(ChangeCredentialsService credentials, TokenIssuerPort tokens) {
        this.credentials = credentials;
        this.tokens = tokens;
    }

    @PostMapping("/api/v1/account/password")
    public ResponseEntity<ChangePasswordResponse> changePassword(@CurrentActor AuthorizationPrincipal principal,
                                                                 @RequestBody(required = false) ChangePasswordRequest request) {
        if (request == null || !StringUtils.hasText(request.currentPassword())) {
            throw new InvalidRequestFieldException("currentPassword");
        }
        if (!StringUtils.hasText(request.newPassword())) {
            throw new InvalidRequestFieldException("newPassword");
        }
        Instant credentialsNotBefore = credentials.changeOwnCredentials(principal, request.currentPassword(), request.newPassword());
        String token = tokens.issue(principal.accountId(), credentialsNotBefore);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new ChangePasswordResponse(token));
    }
}
