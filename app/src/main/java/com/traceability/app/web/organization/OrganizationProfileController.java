package com.traceability.app.web.organization;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.traceability.api.web.CurrentActor;
import com.traceability.api.web.InvalidRequestFieldException;
import com.traceability.contracts.authorization.AuthorizationPrincipal;
import identity.application.service.UpdateOrganizationProfileService;
import identity.domain.exception.InvalidOrganizationProfileFieldException;
import identity.domain.model.Organization;
import identity.domain.model.OrganizationProfile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * [Plan] {@code PUT /api/v1/organizations/{organizationId}/profile} (ficha R-7 §4), con JWT y sin {@code Command-Id}
 * (DH-34). {@code REPRESENTATIVE} o {@code ADMINISTRATOR}, como el logo; el resto, el mismo 403. Lleva todos los campos
 * de §3: un opcional ausente o {@code null} se borra, como en {@code PUT /account/profile}; {@code name} es obligatorio.
 * Responde {@code 200} con el perfil completo y {@code no-store}. Un campo inválido → 400 con el campo; P1 → 409
 * {@code OrganizationProfileLocked}; P5 → 409 {@code OrganizationNitAlreadyRegistered}.
 */
@RestController
public class OrganizationProfileController {

    public record ProfileRequest(String name, String description, String website,
                                 OrganizationProfileJson.LocationCodes location, String contactEmail, String legalName,
                                 String nit, String nitCheckDigit, Boolean specialTaxRegime, String contactPhone) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ProfileResponse(String name, @JsonUnwrapped OrganizationProfileJson.ProfileFields profile) {}

    private final UpdateOrganizationProfileService profiles;

    public OrganizationProfileController(UpdateOrganizationProfileService profiles) {
        this.profiles = profiles;
    }

    @PutMapping("/api/v1/organizations/{organizationId}/profile")
    public ResponseEntity<ProfileResponse> update(@CurrentActor AuthorizationPrincipal principal,
                                                  @PathVariable("organizationId") String organizationId,
                                                  @RequestBody(required = false) ProfileRequest request) {
        ProfileRequest body = request == null
                ? new ProfileRequest(null, null, null, null, null, null, null, null, null, null) : request;
        Organization updated;
        try {
            OrganizationProfile profile = OrganizationProfileJson.profile(body.description(), body.website(),
                    body.location(), body.contactEmail(), body.legalName(), body.nit(), body.nitCheckDigit(),
                    body.specialTaxRegime(), body.contactPhone());
            updated = profiles.updateProfile(principal, organizationId, body.name(), profile);
        } catch (InvalidOrganizationProfileFieldException e) {
            throw new InvalidRequestFieldException(e.getField());
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new ProfileResponse(updated.getName(),
                OrganizationProfileJson.ProfileFields.of(updated.getProfile())));
    }
}
