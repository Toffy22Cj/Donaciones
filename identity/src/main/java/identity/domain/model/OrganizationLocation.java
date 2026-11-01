package identity.domain.model;

import identity.domain.exception.InvalidOrganizationProfileFieldException;

import java.util.regex.Pattern;

/**
 * [Plan] Ubicación de la organización con los códigos DIVIPOLA del DANE (ficha R-7 §3 y §6): 2 dígitos el departamento
 * y 5 el municipio, que empieza por el de su departamento. Se guardan los códigos; los nombres se resuelven al leer con
 * el catálogo. Que el municipio exista en el catálogo lo comprueba el caso de uso.
 */
public record OrganizationLocation(String departmentCode, String municipalityCode) {

    private static final Pattern DEPARTMENT = Pattern.compile("\\d{2}");
    private static final Pattern MUNICIPALITY = Pattern.compile("\\d{5}");

    public OrganizationLocation {
        if (departmentCode == null || municipalityCode == null || !DEPARTMENT.matcher(departmentCode).matches()
                || !MUNICIPALITY.matcher(municipalityCode).matches() || !municipalityCode.startsWith(departmentCode)) {
            throw new InvalidOrganizationProfileFieldException("location");
        }
    }
}
