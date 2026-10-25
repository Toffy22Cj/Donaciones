package com.traceability.app.web.account;

import com.traceability.api.web.CurrentActor;
import com.traceability.api.web.InvalidRequestFieldException;
import com.traceability.contracts.authorization.AuthorizationPrincipal;
import identity.application.service.DeactivateAccountService;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * [Plan: nombre] {@code POST /api/v1/account/deactivate} (ADR-050 D6 y Q3 (a); plan D6). JWT obligatorio; solo la
 * cuenta del JWT.
 * <ul>
 *   <li>{@code currentPassword} vacío → 400 sin llamar al dominio;</li>
 *   <li>contraseña actual incorrecta → 400 {@code CurrentPasswordIncorrect}, nunca 401 (P-D6-1, ADR-043 T-1);</li>
 *   <li>administrador de plataforma → 409 {@code PlatformAdministratorDeactivation}; representante → 409 {@code
 *       RepresentativeTransferRequired}; responsable activo de una convocatoria → 409 {@code ActiveCampaignResponsible}.
 *       En los tres casos la cuenta sigue {@code ACTIVE} y nada cambia;</li>
 *   <li>éxito → {@code 204}. Todos los tokens anteriores, también el de esta petición, quedan revocados (D4).</li>
 * </ul>
 * La membresía no cambia (D6). Sin {@code Command-Id}: Identity no lo usa (DH-34). Nunca registra la contraseña.
 */
@RestController
public class AccountDeactivationController {

    public record DeactivateAccountRequest(String currentPassword) {}

    private final DeactivateAccountService deactivation;

    public AccountDeactivationController(DeactivateAccountService deactivation) {
        this.deactivation = deactivation;
    }

    @PostMapping("/api/v1/account/deactivate")
    public ResponseEntity<Void> deactivate(@CurrentActor AuthorizationPrincipal principal,
                                           @RequestBody(required = false) DeactivateAccountRequest request) {
        if (request == null || !StringUtils.hasText(request.currentPassword())) {
            throw new InvalidRequestFieldException("currentPassword");
        }
        deactivation.deactivateOwnAccount(principal, request.currentPassword());
        return ResponseEntity.noContent().build();
    }
}
