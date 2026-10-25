package com.traceability.app.web.account;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.traceability.api.web.CurrentActor;
import com.traceability.api.web.InvalidRequestFieldException;
import com.traceability.contracts.authorization.AuthorizationPrincipal;
import identity.application.service.AccountProfileService;
import identity.application.service.AccountProfileService.AccountProfile;
import identity.domain.exception.InvalidProfileFieldException;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET} y {@code PUT /api/v1/account/profile} (ADR-050 D1; plan R-5). JWT obligatorio; solo la cuenta del JWT.
 * Responden {@code 200 {email, givenName?, familyName?}} con {@code no-store}. En el {@code PUT}, {@code null} o un
 * campo ausente borra; un campo vacío tras recortar o de más de 100 puntos de código → 400 con el campo nombrado (en el
 * log), y nada cambia. El nombre nunca va a un log ni a ninguna otra ruta.
 */
@RestController
public class AccountProfileController {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ProfileResponse(String email, String givenName, String familyName) {}

    public record ProfileRequest(String givenName, String familyName) {}

    private final AccountProfileService profiles;

    public AccountProfileController(AccountProfileService profiles) {
        this.profiles = profiles;
    }

    @GetMapping("/api/v1/account/profile")
    public ResponseEntity<ProfileResponse> read(@CurrentActor AuthorizationPrincipal principal) {
        return ok(profiles.read(principal));
    }

    @PutMapping("/api/v1/account/profile")
    public ResponseEntity<ProfileResponse> update(@CurrentActor AuthorizationPrincipal principal,
                                                  @RequestBody(required = false) ProfileRequest request) {
        ProfileRequest body = request == null ? new ProfileRequest(null, null) : request;
        try {
            return ok(profiles.update(principal, body.givenName(), body.familyName()));
        } catch (InvalidProfileFieldException e) {
            throw new InvalidRequestFieldException(e.getField());
        }
    }

    private static ResponseEntity<ProfileResponse> ok(AccountProfile profile) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new ProfileResponse(profile.email(), profile.givenName(), profile.familyName()));
    }
}
