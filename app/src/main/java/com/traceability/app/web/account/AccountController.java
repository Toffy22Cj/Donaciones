package com.traceability.app.web.account;

import com.traceability.api.web.CurrentActor;
import com.traceability.core.domain.event.HumanActor;
import identity.application.service.AccountEmailQuery;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Cuenta propia (ficha L-FRONTS, A3; M-26): {@code GET /api/v1/account} → {@code {email}}, {@code Cache-Control:
 * no-store}. JWT obligatorio; solo la cuenta del JWT, con o sin organización. Sin JWT válido, o con la cuenta
 * {@code INACTIVE}, el filtro da 401. El correo es un dato personal: no se registra en ningún log. {@code GET /me}
 * (ficha N1) sigue sin email.
 */
@RestController
public class AccountController {

    public record AccountResponse(String email) {}

    private final AccountEmailQuery emails;

    public AccountController(AccountEmailQuery emails) {
        this.emails = emails;
    }

    @GetMapping("/api/v1/account")
    public ResponseEntity<AccountResponse> account(@CurrentActor HumanActor actor) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new AccountResponse(emails.emailOf(actor.accountId())));
    }
}
