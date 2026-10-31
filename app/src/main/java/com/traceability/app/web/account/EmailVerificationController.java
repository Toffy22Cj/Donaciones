package com.traceability.app.web.account;

import com.traceability.api.web.InvalidRequestFieldException;
import identity.application.service.EmailVerificationService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * [Plan] Verificación del correo (ADR-050 Enmienda 2, E2-2), con la forma de {@code PasswordResetController}. Las dos
 * rutas son públicas ({@code PublicRoutes}) y solo leen el cuerpo: un token en la query o en la ruta no se acepta.
 * Nunca registran el token ni el correo. Sin {@code Command-Id} (DH-34).
 * <ul>
 *   <li>{@code POST /api/v1/auth/email-verification {email}} → {@code 202} sin cuerpo y {@code no-store}, exista o no
 *       la cuenta y esté o no verificada; correo vacío → 400.</li>
 *   <li>{@code POST /api/v1/auth/email-verification/confirm {token}} → {@code 204}; token vacío → 400 sin llamar al
 *       dominio; inválido por cualquier motivo → 400 {@code EmailVerificationTokenInvalid}. No inicia sesión.</li>
 * </ul>
 */
@RestController
public class EmailVerificationController {

    public record EmailVerificationRequest(String email) {}

    public record EmailVerificationConfirmation(String token) {}

    private final EmailVerificationService verifications;

    public EmailVerificationController(EmailVerificationService verifications) {
        this.verifications = verifications;
    }

    @PostMapping("/api/v1/auth/email-verification")
    public ResponseEntity<Void> request(@RequestBody(required = false) EmailVerificationRequest body) {
        if (body == null || !StringUtils.hasText(body.email())) {
            throw new InvalidRequestFieldException("email");
        }
        verifications.request(body.email());
        return ResponseEntity.status(HttpStatus.ACCEPTED).cacheControl(CacheControl.noStore()).build();
    }

    @PostMapping("/api/v1/auth/email-verification/confirm")
    public ResponseEntity<Void> confirm(@RequestBody(required = false) EmailVerificationConfirmation body) {
        if (body == null || !StringUtils.hasText(body.token())) {
            throw new InvalidRequestFieldException("token");
        }
        verifications.confirm(body.token());
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
