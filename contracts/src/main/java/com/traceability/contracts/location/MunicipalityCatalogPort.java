package com.traceability.contracts.location;

import java.util.Optional;

/**
 * [Plan] Catálogo de municipios DIVIPOLA del DANE, de solo lectura (enmienda 2 de ADR-029, D3). Lo consulta
 * {@code core} para validar el código de municipio de cada paso con ubicación y sellar su zona, y lo implementa
 * {@code app} sobre el mismo catálogo que ya carga al arrancar (ficha R-7 §6).
 */
public interface MunicipalityCatalogPort {

    /** El municipio con ese código de 5 dígitos, si está en el catálogo; vacío si no existe o es {@code null}. */
    Optional<MunicipalityEntry> find(String municipalityCode);
}
