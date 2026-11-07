package com.traceability.contracts.location;

/**
 * [Plan] Una entrada del catálogo DIVIPOLA (enmienda 2 de ADR-029, D3): el municipio y su departamento, con los nombres
 * tal cual los publica el catálogo, y la versión del catálogo que los dio.
 */
public record MunicipalityEntry(String municipalityCode, String municipalityName, String departmentCode,
                                String departmentName, String catalogVersion) {}
