package com.traceability.core.support;

import com.traceability.contracts.location.MunicipalityCatalogPort;
import com.traceability.contracts.location.MunicipalityEntry;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * [Plan] Stub de {@link MunicipalityCatalogPort} para los tests de {@code core} (plan de la zona en cada paso, §4), como
 * {@link StubCampaignInKindEligibilityPort}: los contextos que escanean {@code com.traceability.core} lo recogen, y los
 * tests unitarios lo instancian a mano. Solo existe en las fuentes de test de {@code core}.
 * <p>
 * Por defecto conoce {@code 05001} MEDELLÍN y {@code 05002} ABEJORRAL (ANTIOQUIA), {@code 08001} BARRANQUILLA
 * (ATLÁNTICO) y {@code 11001} BOGOTÁ, D.C., con la versión {@value #VERSION}. Registra cada consulta.
 */
@Component
public class StubMunicipalityCatalogPort implements MunicipalityCatalogPort {

    public static final String VERSION = "DIVIPOLA-TEST";

    private final Map<String, MunicipalityEntry> entries = new ConcurrentHashMap<>();
    private final List<String> queries = new CopyOnWriteArrayList<>();

    public StubMunicipalityCatalogPort() {
        reset();
    }

    public void reset() {
        entries.clear();
        queries.clear();
        put("05001", "MEDELLÍN", "05", "ANTIOQUIA");
        put("05002", "ABEJORRAL", "05", "ANTIOQUIA");
        put("08001", "BARRANQUILLA", "08", "ATLÁNTICO");
        put("11001", "BOGOTÁ, D.C.", "11", "BOGOTÁ, D.C.");
    }

    /** Cambia el nombre de un municipio, como si el catálogo cambiara de versión (test 12). */
    public void rename(String municipalityCode, String municipalityName, String catalogVersion) {
        MunicipalityEntry e = entries.get(municipalityCode);
        entries.put(municipalityCode, new MunicipalityEntry(municipalityCode, municipalityName, e.departmentCode(),
                e.departmentName(), catalogVersion));
    }

    public List<String> queries() {
        return List.copyOf(queries);
    }

    private void put(String code, String name, String departmentCode, String departmentName) {
        entries.put(code, new MunicipalityEntry(code, name, departmentCode, departmentName, VERSION));
    }

    @Override
    public Optional<MunicipalityEntry> find(String municipalityCode) {
        queries.add(String.valueOf(municipalityCode));
        return Optional.ofNullable(municipalityCode == null ? null : entries.get(municipalityCode));
    }
}
