package identity.domain.model;

import identity.domain.exception.InvalidOrganizationProfileFieldException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Ficha R-7 §3 y §9, test 5: las validaciones de cada campo del perfil, con el campo nombrado. */
class OrganizationProfileTest {

    static OrganizationProfile.Draft draft() {
        return new OrganizationProfile.Draft(null, null, null, null, null, null, null, null, null, null);
    }

    private static String fieldOf(OrganizationProfile.Draft d) {
        try {
            OrganizationProfile.from(d);
            return null;
        } catch (InvalidOrganizationProfileFieldException e) {
            return e.getField();
        }
    }

    @Test
    void allFieldsValid_areKept_trimmed_andTheEmailNormalized() {
        OrganizationProfile p = OrganizationProfile.from(new OrganizationProfile.Draft("  Ayudamos a niños  ",
                "https://fundacion.example.org/inicio", "05", "05001", "Contacto@Fundacion.Example.ORG",
                " Fundación Ejemplo S.A.S. ", "900000006", "9", Boolean.TRUE, "3001234567"));

        assertThat(p.description()).isEqualTo("Ayudamos a niños");
        assertThat(p.website()).isEqualTo("https://fundacion.example.org/inicio");
        assertThat(p.location()).isEqualTo(new OrganizationLocation("05", "05001"));
        assertThat(p.contactEmail()).isEqualTo("contacto@fundacion.example.org");
        assertThat(p.legalName()).isEqualTo("Fundación Ejemplo S.A.S.");
        assertThat(p.nit()).isEqualTo(Nit.of("900000006", "9"));
        assertThat(p.specialTaxRegime()).isTrue();
        assertThat(p.contactPhone()).isEqualTo("3001234567");
        assertThat(OrganizationProfile.from(draft())).isEqualTo(OrganizationProfile.EMPTY);
    }

    @Test
    void theWebsite_mustBeAnAbsoluteHttpsUrl_ofUpTo200Characters() {
        for (String bad : new String[] {"http://fundacion.example.org", "javascript:alert(1)", "fundacion.example.org",
                "https://", "https://" + "a".repeat(190) + ".org", "ftp://fundacion.example.org"}) {
            assertThat(fieldOf(new OrganizationProfile.Draft(null, bad, null, null, null, null, null, null, null, null)))
                    .as(bad).isEqualTo("website");
        }
    }

    @Test
    void theDescription_isOneTo500CodePoints_ofPlainText() {
        assertThat(fieldOf(new OrganizationProfile.Draft("a".repeat(501), null, null, null, null, null, null, null, null, null)))
                .isEqualTo("description");
        assertThat(fieldOf(new OrganizationProfile.Draft("   ", null, null, null, null, null, null, null, null, null)))
                .isEqualTo("description");
        assertThat(fieldOf(new OrganizationProfile.Draft("<b>hola</b>", null, null, null, null, null, null, null, null, null)))
                .as("sin HTML").isEqualTo("description");
        // 500 puntos de código fuera del plano básico son 1000 caracteres Java: se cuentan puntos de código
        assertThat(fieldOf(new OrganizationProfile.Draft("𝔸".repeat(500), null, null, null, null, null, null, null, null, null)))
                .isNull();
    }

    @Test
    void theLocation_needsBothCodes_withTheMunicipalityInsideItsDepartment() {
        assertThat(fieldOf(new OrganizationProfile.Draft(null, null, "05", null, null, null, null, null, null, null))).isEqualTo("location");
        assertThat(fieldOf(new OrganizationProfile.Draft(null, null, null, "05001", null, null, null, null, null, null))).isEqualTo("location");
        assertThat(fieldOf(new OrganizationProfile.Draft(null, null, "05", "11001", null, null, null, null, null, null)))
                .as("un municipio de otro departamento").isEqualTo("location");
        assertThat(fieldOf(new OrganizationProfile.Draft(null, null, "5", "05001", null, null, null, null, null, null))).isEqualTo("location");
    }

    @Test
    void thePhone_isTenDigitsStartingWith3or60() {
        assertThat(fieldOf(new OrganizationProfile.Draft(null, null, null, null, null, null, null, null, null, "6012345678"))).isNull();
        for (String bad : new String[] {"2001234567", "300123456", "30012345678", "+573001234567", "7012345678"}) {
            assertThat(fieldOf(new OrganizationProfile.Draft(null, null, null, null, null, null, null, null, null, bad)))
                    .as(bad).isEqualTo("contactPhone");
        }
    }

    @Test
    void theEmail_theLegalName_andTheNit_areValidated() {
        assertThat(fieldOf(new OrganizationProfile.Draft(null, null, null, null, "no-es-correo", null, null, null, null, null)))
                .isEqualTo("contactEmail");
        assertThat(fieldOf(new OrganizationProfile.Draft(null, null, null, null, null, "x".repeat(201), null, null, null, null)))
                .isEqualTo("legalName");
        assertThat(fieldOf(new OrganizationProfile.Draft(null, null, null, null, null, null, "900000006", null, null, null)))
                .as("NIT sin dígito").isEqualTo("nitCheckDigit");
        assertThat(fieldOf(new OrganizationProfile.Draft(null, null, null, null, null, null, null, "9", null, null)))
                .as("dígito sin NIT").isEqualTo("nit");
    }

    @Test
    void changedFields_namesOnlyTheFieldsThatChange() {
        OrganizationProfile a = OrganizationProfile.from(new OrganizationProfile.Draft("Uno", null, "05", "05001", null,
                "Razón", "900000006", "9", null, null));
        OrganizationProfile b = OrganizationProfile.from(new OrganizationProfile.Draft("Dos", null, "05", "05002", null,
                "Razón", "900000014", "8", null, null));

        assertThat(a.changedFields(b)).containsExactly("description", "location", "nit", "nitCheckDigit");
        assertThat(a.changedFields(a)).isEmpty();
    }
}
