package com.traceability.app.infrastructure.location;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import identity.application.port.out.LocationCatalogPort;
import identity.domain.model.OrganizationLocation;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * [Plan] Catálogo DIVIPOLA del DANE (ficha R-7 §6; P-R7-1 y P-R7-2), leído del classpath
 * ({@code divipola/divipola.json}) con la forma {@code {catalogVersion, departments: [{code, name, municipalities:
 * [{code, name}]}]}}. El archivo lo aportó una persona del equipo; el agente no lo descarga ni lo escribe. Sin él, o mal
 * formado, la aplicación no arranca (P-R7-1). Las entradas incluyen municipios, la isla y las áreas no municipalizadas
 * (nota del 2026-10-31 del plan R-7).
 */
public final class DivipolaLocationCatalog implements LocationCatalogPort {

    public static final String RESOURCE = "divipola/divipola.json";

    private final Catalog catalog;
    private final Map<String, String> departmentNames = new HashMap<>();
    private final Map<String, LocationNames> byMunicipality = new HashMap<>();

    private DivipolaLocationCatalog(Catalog catalog) {
        this.catalog = catalog;
        for (Department d : catalog.departments()) {
            departmentNames.put(d.code(), d.name());
            for (Municipality m : d.municipalities()) {
                byMunicipality.put(m.code(), new LocationNames(d.name(), m.name()));
            }
        }
    }

    /** @throws IllegalStateException si el archivo no tiene la forma esperada */
    public static DivipolaLocationCatalog load(InputStream json) {
        try (json) {
            JsonNode root = new ObjectMapper().readTree(json);
            String version = text(root, "catalogVersion");
            JsonNode departments = root.get("departments");
            if (departments == null || !departments.isArray() || departments.isEmpty()) {
                throw new IllegalStateException("Catálogo DIVIPOLA sin departamentos");
            }
            List<Department> list = new ArrayList<>();
            for (JsonNode d : departments) {
                String code = text(d, "code");
                List<Municipality> municipalities = new ArrayList<>();
                for (JsonNode m : d.path("municipalities")) {
                    String municipality = text(m, "code");
                    if (!municipality.startsWith(code)) {
                        throw new IllegalStateException("Catálogo DIVIPOLA: " + municipality + " no es de " + code);
                    }
                    municipalities.add(new Municipality(municipality, text(m, "name")));
                }
                list.add(new Department(code, text(d, "name"), List.copyOf(municipalities)));
            }
            return new DivipolaLocationCatalog(new Catalog(version, List.copyOf(list)));
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo leer el catálogo DIVIPOLA", e);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalStateException("Catálogo DIVIPOLA: falta " + field);
        }
        return value.asText();
    }

    @Override
    public boolean contains(String departmentCode, String municipalityCode) {
        return departmentNames.containsKey(departmentCode) && byMunicipality.containsKey(municipalityCode)
                && municipalityCode.startsWith(departmentCode);
    }

    @Override
    public Optional<LocationNames> names(OrganizationLocation location) {
        return Optional.ofNullable(byMunicipality.get(location.municipalityCode()));
    }

    @Override
    public Catalog catalog() {
        return catalog;
    }
}
