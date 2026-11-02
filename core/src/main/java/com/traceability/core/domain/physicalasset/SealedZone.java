package com.traceability.core.domain.physicalasset;

/**
 * [Plan] La zona pública sellada en un evento de {@code PhysicalAsset} (enmienda 2 de ADR-029, D1): el código DIVIPOLA
 * del municipio, el texto {@code "MUNICIPIO, DEPARTAMENTO"} con los nombres tal cual del catálogo de ese día (si los dos
 * nombres son iguales, como en Bogotá, uno solo; P5 de la ficha) y la versión del catálogo.
 */
public record SealedZone(String municipalityCode, String zoneName, String catalogVersion) {

    public SealedZone {
        if (municipalityCode == null || zoneName == null || catalogVersion == null) {
            throw new IllegalArgumentException("A sealed zone needs its code, its name and its catalog version");
        }
    }

    public static SealedZone of(String municipalityCode, String municipalityName, String departmentName,
                                String catalogVersion) {
        String zoneName = municipalityName.equals(departmentName) ? municipalityName
                : municipalityName + ", " + departmentName;
        return new SealedZone(municipalityCode, zoneName, catalogVersion);
    }

    /** La zona de los tres campos de un payload; {@code null} si el evento no la tiene (E-1). */
    static SealedZone fromFields(String municipalityCode, String zoneName, String catalogVersion) {
        return municipalityCode == null ? null : new SealedZone(municipalityCode, zoneName, catalogVersion);
    }
}
