package com.traceability.app.infrastructure.location;

import com.traceability.contracts.location.MunicipalityCatalogPort;
import com.traceability.contracts.location.MunicipalityEntry;
import identity.application.port.out.LocationCatalogPort;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * [Plan] {@link MunicipalityCatalogPort} sobre el catálogo DIVIPOLA que ya carga la aplicación al arrancar (ficha R-7
 * §6; enmienda 2 de ADR-029, D3): los nombres van tal cual el catálogo, con su {@code catalogVersion}. Un código que no
 * está, o {@code null}, da {@code Optional.empty()}.
 */
public final class DivipolaMunicipalityCatalogAdapter implements MunicipalityCatalogPort {

    private final Map<String, MunicipalityEntry> byCode = new HashMap<>();

    public DivipolaMunicipalityCatalogAdapter(LocationCatalogPort.Catalog catalog) {
        for (LocationCatalogPort.Department d : catalog.departments()) {
            for (LocationCatalogPort.Municipality m : d.municipalities()) {
                byCode.put(m.code(), new MunicipalityEntry(m.code(), m.name(), d.code(), d.name(), catalog.catalogVersion()));
            }
        }
    }

    @Override
    public Optional<MunicipalityEntry> find(String municipalityCode) {
        return municipalityCode == null ? Optional.empty() : Optional.ofNullable(byCode.get(municipalityCode));
    }
}
