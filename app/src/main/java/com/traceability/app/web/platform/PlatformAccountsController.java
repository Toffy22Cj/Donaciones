package com.traceability.app.web.platform;

import com.traceability.api.web.CurrentActor;
import com.traceability.contracts.authorization.AuthorizationPrincipal;
import identity.application.service.ReactivateAccountService;
import identity.domain.exception.AccountNotFoundException;
import identity.domain.model.AccountId;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ADR-050 D7 y test 26 (plan de cierre, §D7): {@code POST /api/v1/platform/accounts/{accountId}/reactivate} →
 * {@code 200 {accountId, status}}. Solo el administrador de plataforma; el resto, el mismo 403, antes de leer la cuenta.
 * Inexistente → 404, que solo ve la plataforma (P-D7-2, como DD-70). Ya activa → 200 igual, sin auditoría (P-D7-3).
 * Sin {@code Command-Id} (DH-34): repetirla no tiene efecto.
 */
@RestController
public class PlatformAccountsController {

    public record ReactivationResponse(String accountId, String status) {}

    private final ReactivateAccountService reactivations;

    public PlatformAccountsController(ReactivateAccountService reactivations) {
        this.reactivations = reactivations;
    }

    @PostMapping("/api/v1/platform/accounts/{accountId}/reactivate")
    public ResponseEntity<ReactivationResponse> reactivate(@CurrentActor AuthorizationPrincipal principal,
                                                           @PathVariable("accountId") String accountId) {
        try {
            reactivations.reactivateAccount(principal, new AccountId(accountId));
        } catch (AccountNotFoundException e) {
            throw new PlatformAccountNotFoundException();
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new ReactivationResponse(accountId, "ACTIVE"));
    }
}
