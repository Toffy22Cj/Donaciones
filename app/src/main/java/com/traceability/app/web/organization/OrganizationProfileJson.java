package com.traceability.app.web.organization;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.traceability.api.web.InvalidRequestFieldException;
import identity.domain.exception.InvalidOrganizationProfileFieldException;
import identity.domain.model.OrganizationProfile;

/**
 * [Plan] La forma JSON del perfil de la organización (ficha R-7 §3): los campos van planos en el cuerpo y en las
 * respuestas, y la ubicación como {@code {departmentCode, municipalityCode}}. Un campo inválido es el 400 de siempre,
 * con el campo en el log ({@link InvalidRequestFieldException}).
 */
public final class OrganizationProfileJson {

    private OrganizationProfileJson() {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record LocationCodes(String departmentCode, String municipalityCode) {}

    /** El perfil completo de una respuesta; los ausentes no salen. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ProfileFields(String description, String website, LocationCodes location, String contactEmail,
                                String legalName, String nit, String nitCheckDigit, Boolean specialTaxRegime,
                                String contactPhone) {

        public static ProfileFields of(OrganizationProfile p) {
            return new ProfileFields(p.description(), p.website(),
                    p.location() == null ? null
                            : new LocationCodes(p.location().departmentCode(), p.location().municipalityCode()),
                    p.contactEmail(), p.legalName(), p.nitNumber(), p.nitCheckDigit(), p.specialTaxRegime(),
                    p.contactPhone());
        }
    }

    /** Valida los campos del cuerpo; un campo inválido → 400 con el campo nombrado. */
    public static OrganizationProfile profile(String description, String website, LocationCodes location,
                                              String contactEmail, String legalName, String nit, String nitCheckDigit,
                                              Boolean specialTaxRegime, String contactPhone) {
        try {
            return OrganizationProfile.from(new OrganizationProfile.Draft(description, website,
                    location == null ? null : location.departmentCode(),
                    location == null ? null : location.municipalityCode(), contactEmail, legalName, nit, nitCheckDigit,
                    specialTaxRegime, contactPhone));
        } catch (InvalidOrganizationProfileFieldException e) {
            throw new InvalidRequestFieldException(e.getField());
        }
    }
}
