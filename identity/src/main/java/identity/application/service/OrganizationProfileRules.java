package identity.application.service;

import identity.application.port.out.LocationCatalogPort;
import identity.domain.exception.InvalidOrganizationProfileFieldException;
import identity.domain.model.OrganizationProfile;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * [Plan] Lo que el perfil de la organización comprueba fuera del dominio (ficha R-7 §3 y §6): que la ubicación esté en
 * el catálogo DIVIPOLA. El catálogo es opcional en el contexto para que los tests de {@code identity} que no usan
 * ubicaciones no tengan que declararlo; en {@code app} siempre existe y falla al arrancar si falta el archivo (P-R7-1).
 */
@Component
public class OrganizationProfileRules {

    private final ObjectProvider<LocationCatalogPort> catalog;

    public OrganizationProfileRules(ObjectProvider<LocationCatalogPort> catalog) {
        this.catalog = catalog;
    }

    /** @throws InvalidOrganizationProfileFieldException ({@code location}) si la ubicación no está en el catálogo */
    public void requireKnownLocation(OrganizationProfile profile) {
        if (profile.location() == null) {
            return;
        }
        LocationCatalogPort locations = catalog.getIfAvailable();
        if (locations == null) {
            throw new IllegalStateException("No hay catálogo de ubicaciones (LocationCatalogPort)");
        }
        if (!locations.contains(profile.location().departmentCode(), profile.location().municipalityCode())) {
            throw new InvalidOrganizationProfileFieldException("location");
        }
    }
}
