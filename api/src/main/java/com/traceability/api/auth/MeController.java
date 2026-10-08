package com.traceability.api.auth;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.traceability.api.web.CurrentActor;
import com.traceability.contracts.authorization.AuthorizationPrincipal;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * N1, {@code GET /api/v1/me} (ficha N1 congelada, Q-N1-1 a Q-N1-4; P2.1 de la segunda autorización): exactamente
 * {@code accountId}, {@code organizationId}, {@code roles} y {@code platformAuthority}. Sin email, nombre ni estado.
 * {@code organizationId} y {@code platformAuthority} se omiten si son nulos; {@code roles} siempre está ({@code []} sin
 * organización). {@code Cache-Control: no-store}. Sin JWT válido, o con la cuenta {@code INACTIVE}, el filtro da 401.
 * Sirve para <b>representar</b> la interfaz: la autorización sigue en cada petición.
 */
@RestController
public class MeController {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MeResponse(String accountId, String organizationId,
                             @JsonInclude(JsonInclude.Include.ALWAYS) List<String> roles, String platformAuthority) {}

    @GetMapping("/api/v1/me")
    public ResponseEntity<MeResponse> me(@CurrentActor AuthorizationPrincipal principal) {
        List<String> roles = principal.roles() == null ? List.of()
                : principal.roles().stream().map(Enum::name).sorted().toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new MeResponse(principal.accountId(),
                principal.organizationId(), roles,
                principal.platformAuthority() == null ? null : principal.platformAuthority().name()));
    }
}
