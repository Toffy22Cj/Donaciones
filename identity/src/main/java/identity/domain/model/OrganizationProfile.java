package identity.domain.model;

import identity.domain.exception.InvalidEmailFormatException;
import identity.domain.exception.InvalidOrganizationProfileFieldException;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * [Plan] Perfil de la organización (ficha R-7 §3): lo público ({@code description}, {@code website}, {@code location},
 * {@code contactEmail}) y lo de verificación ({@code legalName}, {@code nit}, {@code specialTaxRegime},
 * {@code contactPhone}). Todo opcional; un campo nulo no está. {@code name} sigue en {@link Organization}.
 * <p>
 * Los textos se recortan y cuentan puntos de código (P-R7-7). La descripción es texto plano: se rechazan {@code <} y
 * {@code >} (sin HTML); el Markdown no se interpreta en ningún lado. Que {@code specialTaxRegime} solo valga para una
 * {@code FOUNDATION} lo comprueba {@link Organization}, que conoce el tipo.
 */
public record OrganizationProfile(String description, String website, OrganizationLocation location, String contactEmail,
                                  String legalName, Nit nit, Boolean specialTaxRegime, String contactPhone) {

    public static final OrganizationProfile EMPTY = new OrganizationProfile(null, null, null, null, null, null, null, null);
    public static final int DESCRIPTION_MAX_CODE_POINTS = 500;
    public static final int WEBSITE_MAX_LENGTH = 200;
    public static final int LEGAL_NAME_MAX_CODE_POINTS = 200;
    private static final Pattern PHONE = Pattern.compile("(3\\d{9}|60\\d{8})");

    /** Los campos tal como llegan; {@link #from} los valida. */
    public record Draft(String description, String website, String departmentCode, String municipalityCode,
                        String contactEmail, String legalName, String nit, String nitCheckDigit,
                        Boolean specialTaxRegime, String contactPhone) {}

    /** @throws InvalidOrganizationProfileFieldException con el primer campo que no cumple */
    public static OrganizationProfile from(Draft d) {
        String description = text(d.description(), "description", DESCRIPTION_MAX_CODE_POINTS);
        if (description != null && (description.indexOf('<') >= 0 || description.indexOf('>') >= 0)) {
            throw new InvalidOrganizationProfileFieldException("description");
        }
        OrganizationLocation location = null;
        if (d.departmentCode() != null || d.municipalityCode() != null) {
            location = new OrganizationLocation(d.departmentCode(), d.municipalityCode());
        }
        Nit nit = null;
        if (d.nit() != null || d.nitCheckDigit() != null) {
            if (d.nit() == null) {
                throw new InvalidOrganizationProfileFieldException("nit");
            }
            nit = Nit.of(d.nit(), d.nitCheckDigit());
        }
        return new OrganizationProfile(description, website(d.website()), location, email(d.contactEmail()),
                text(d.legalName(), "legalName", LEGAL_NAME_MAX_CODE_POINTS), nit, d.specialTaxRegime(),
                phone(d.contactPhone()));
    }

    /** Los nombres de los campos de la ficha que cambian de este perfil a {@code other}, en el orden de §3. */
    public List<String> changedFields(OrganizationProfile other) {
        List<String> changed = new ArrayList<>();
        if (!Objects.equals(description, other.description)) changed.add("description");
        if (!Objects.equals(website, other.website)) changed.add("website");
        if (!Objects.equals(location, other.location)) changed.add("location");
        if (!Objects.equals(contactEmail, other.contactEmail)) changed.add("contactEmail");
        if (!Objects.equals(legalName, other.legalName)) changed.add("legalName");
        if (!Objects.equals(nitNumber(), other.nitNumber())) changed.add("nit");
        if (!Objects.equals(nitCheckDigit(), other.nitCheckDigit())) changed.add("nitCheckDigit");
        if (!Objects.equals(specialTaxRegime, other.specialTaxRegime)) changed.add("specialTaxRegime");
        if (!Objects.equals(contactPhone, other.contactPhone)) changed.add("contactPhone");
        return changed;
    }

    public String nitNumber() {
        return nit == null ? null : nit.number();
    }

    public String nitCheckDigit() {
        return nit == null ? null : nit.checkDigit();
    }

    private static String text(String value, String field, int maxCodePoints) {
        if (value == null) {
            return null;
        }
        String stripped = value.strip();
        int codePoints = stripped.codePointCount(0, stripped.length());
        if (codePoints < 1 || codePoints > maxCodePoints) {
            throw new InvalidOrganizationProfileFieldException(field);
        }
        return stripped;
    }

    /** Una URL {@code https://} absoluta, con host, de hasta {@value #WEBSITE_MAX_LENGTH} caracteres. */
    private static String website(String value) {
        if (value == null) {
            return null;
        }
        String stripped = value.strip();
        try {
            URI uri = new URI(stripped);
            if (stripped.length() > WEBSITE_MAX_LENGTH || !"https".equals(uri.getScheme()) || uri.getHost() == null
                    || uri.getRawUserInfo() != null) {
                throw new InvalidOrganizationProfileFieldException("website");
            }
        } catch (URISyntaxException e) {
            throw new InvalidOrganizationProfileFieldException("website");
        }
        return stripped;
    }

    /** El mismo validador que el de las cuentas ({@link Email}), ya normalizado (P-R7-8). */
    private static String email(String value) {
        if (value == null) {
            return null;
        }
        try {
            return new Email(value).value();
        } catch (InvalidEmailFormatException e) {
            throw new InvalidOrganizationProfileFieldException("contactEmail");
        }
    }

    /** Teléfono colombiano: 10 dígitos que empiezan por 3 (celular) o por 60 (fijo). */
    private static String phone(String value) {
        if (value == null) {
            return null;
        }
        if (!PHONE.matcher(value).matches()) {
            throw new InvalidOrganizationProfileFieldException("contactPhone");
        }
        return value;
    }
}
