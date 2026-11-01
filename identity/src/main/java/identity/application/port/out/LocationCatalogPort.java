package identity.application.port.out;

import identity.domain.model.OrganizationLocation;

import java.util.List;
import java.util.Optional;

/**
 * [Plan] Catálogo de ubicaciones DIVIPOLA del DANE (ficha R-7 §6; P-R7-1 y P-R7-2). Lo implementa {@code app} con el
 * archivo que aportó una persona del equipo; {@code identity} solo lo consulta.
 */
public interface LocationCatalogPort {

    /** Si el municipio existe en el catálogo y es de ese departamento. */
    boolean contains(String departmentCode, String municipalityCode);

    /** Los nombres de una ubicación guardada, si sigue en el catálogo. */
    Optional<LocationNames> names(OrganizationLocation location);

    /** El catálogo completo, para {@code GET /public/locations}. */
    Catalog catalog();

    record LocationNames(String departmentName, String municipalityName) {}

    record Catalog(String catalogVersion, List<Department> departments) {}

    record Department(String code, String name, List<Municipality> municipalities) {}

    record Municipality(String code, String name) {}
}
