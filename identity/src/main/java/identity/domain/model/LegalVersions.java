package identity.domain.model;

/**
 * Versiones vigentes de los textos legales (ADR-050 D2; plan R-5, P-R5-3). Las construye {@code app} desde la
 * configuración ({@code traceability.legal.*}) y las entrega como bean: {@code identity} no lee la configuración.
 */
public record LegalVersions(String terms, String dataPolicy, String organizationTerms) {
    public LegalVersions {
        requireText(terms, "terms");
        requireText(dataPolicy, "dataPolicy");
        requireText(organizationTerms, "organizationTerms");
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Legal version '" + name + "' must not be blank");
        }
    }
}
