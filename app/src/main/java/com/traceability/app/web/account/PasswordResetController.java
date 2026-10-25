package com.traceability.app.web.account;

import com.traceability.api.web.InvalidRequestFieldException;
import identity.application.service.ChangeCredentialsService;
import identity.application.service.RequestPasswordResetService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Recuperación de la contraseña (ADR-050 D8; plan R-4). Las dos rutas son públicas ({@code PublicRoutes}) y solo leen el
 * cuerpo: un token en la query o en la ruta no se acepta. Nunca registran el token ni el correo. Sin {@code Command-Id}
 * (DH-34).
 * <ul>
 *   <li>{@code POST /api/v1/auth/password-reset {email}} → {@code 202} sin cuerpo y {@code no-store}, exista o no la
 *       cuenta (P-R4-3); correo vacío → 400.</li>
 *   <li>{@code POST /api/v1/auth/password-reset/confirm {token, newPassword}} → {@code 204}; un campo vacío → 400 sin
 *       llamar al dominio; token inválido por cualquier motivo → 400 {@code PasswordResetTokenInvalid}. La confirmación
 *       no inicia sesión.</li>
 * </ul>
 */
@RestController
public class PasswordResetController {

    public record PasswordResetRequest(String email) {}

    public record PasswordResetConfirmation(String token, String newPassword) {}

    private final RequestPasswordResetService requests;
    private final ChangeCredentialsService credentials;

    public PasswordResetController(RequestPasswordResetService requests, ChangeCredentialsService credentials) {
        this.requests = requests;
        this.credentials = credentials;
    }

    @PostMapping("/api/v1/auth/password-reset")
    public ResponseEntity<Void> request(@RequestBody(required = false) PasswordResetRequest body) {
        if (body == null || !StringUtils.hasText(body.email())) {
            throw new InvalidRequestFieldException("email");
        }
        requests.request(body.email());
        return ResponseEntity.status(HttpStatus.ACCEPTED).cacheControl(CacheControl.noStore()).build();
    }

    @PostMapping("/api/v1/auth/password-reset/confirm")
    public ResponseEntity<Void> confirm(@RequestBody(required = false) PasswordResetConfirmation body) {
        if (body == null || !StringUtils.hasText(body.token())) {
            throw new InvalidRequestFieldException("token");
        }
        if (!StringUtils.hasText(body.newPassword())) {
            throw new InvalidRequestFieldException("newPassword");
        }
        credentials.resetCredentials(body.token(), body.newPassword());
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
