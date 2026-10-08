package com.traceability.app.web.platform;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.traceability.api.web.CurrentActor;
import com.traceability.api.web.InvalidRequestFieldException;
import com.traceability.contracts.authorization.AuthorizationPrincipal;
import identity.application.service.GrantPlatformAuthorityService;
import identity.application.service.PlatformAdministratorsQuery;
import identity.application.service.RevokePlatformAuthorityService;
import identity.domain.exception.AccountNotFoundException;
import identity.domain.exception.InactiveAccountException;
import identity.domain.model.AccountId;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Administradores de plataforma (autorización (3) de Carlos, §3.2; matriz §1; ADR-038 §2.3). Solo un administrador de
 * plataforma; el resto, el mismo 403. Las reglas son las del dominio, sin reglas nuevas:
 * <ul>
 *   <li>{@code GET /api/v1/platform/administrators} → {@code 200 {items: [{accountId, status}]}}, sin email;</li>
 *   <li>{@code POST /api/v1/platform/administrators {accountId}} → {@code 201 {accountId, platformAuthority}}; ya la
 *   tiene → 409 {@code PlatformAuthorityAlreadyGranted}; cuenta inactiva → 409 {@code PlatformAuthorityTargetInactive};
 *   cuenta inexistente → 404 (solo lo ve la plataforma, como la organización inexistente);</li>
 *   <li>{@code POST /api/v1/platform/administrators/{accountId}/revoke} → {@code 200 {accountId}}; no la tiene → 409
 *   {@code PlatformAuthorityNotHeld}; es el último → 409 {@code LastPlatformAdministrator}; inexistente → 404.</li>
 * </ul>
 * Sin {@code Command-Id} (DH-34): el estado impide repetir (un reintento recibe 409). Revocar va por {@code POST
 * …/revoke} y no por {@code DELETE}, como retirar un responsable (DD-50) y dentro de los métodos de CORS (DD-57)
 * (`[DECISIÓN DELEGADA — pendiente de ratificar por Carlos]` DD-70).
 */
@RestController
public class PlatformAdministratorsController {

    public record GrantRequest(String accountId) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AuthorityResponse(String accountId, String platformAuthority) {}

    public record Administrator(String accountId, String status) {}

    public record Page(List<Administrator> items) {}

    private final PlatformAdministratorsQuery administrators;
    private final GrantPlatformAuthorityService grants;
    private final RevokePlatformAuthorityService revocations;

    public PlatformAdministratorsController(PlatformAdministratorsQuery administrators, GrantPlatformAuthorityService grants,
                                            RevokePlatformAuthorityService revocations) {
        this.administrators = administrators;
        this.grants = grants;
        this.revocations = revocations;
    }

    @GetMapping("/api/v1/platform/administrators")
    public ResponseEntity<Page> list(@CurrentActor AuthorizationPrincipal principal) {
        List<Administrator> items = administrators.administrators(principal).stream()
                .map(a -> new Administrator(a.accountId(), a.status())).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Page(items));
    }

    @PostMapping("/api/v1/platform/administrators")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthorityResponse grant(@CurrentActor AuthorizationPrincipal principal,
                                   @RequestBody(required = false) GrantRequest request) {
        // el servicio autoriza antes de leer la cuenta; un cuerpo vacío de quien no es de la plataforma también es 403
        administrators.authorize(principal);
        if (request == null || !StringUtils.hasText(request.accountId())) {
            throw new InvalidRequestFieldException("accountId");
        }
        try {
            grants.grantPlatformAuthority(principal, new AccountId(request.accountId()));
        } catch (AccountNotFoundException e) {
            throw new PlatformAuthorityTargetNotFoundException();
        } catch (InactiveAccountException e) {
            throw new PlatformAuthorityTargetInactiveException();
        }
        return new AuthorityResponse(request.accountId(), "ADMINISTRATOR");
    }

    @PostMapping("/api/v1/platform/administrators/{accountId}/revoke")
    public AuthorityResponse revoke(@CurrentActor AuthorizationPrincipal principal,
                                    @PathVariable("accountId") String accountId) {
        try {
            revocations.revokePlatformAuthority(principal, new AccountId(accountId));
        } catch (AccountNotFoundException e) {
            throw new PlatformAuthorityTargetNotFoundException();
        }
        return new AuthorityResponse(accountId, null);
    }
}
