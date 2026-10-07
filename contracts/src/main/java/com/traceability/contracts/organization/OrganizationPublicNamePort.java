package com.traceability.contracts.organization;

import java.util.Optional;

/**
 * Nombre público de una organización, para el detalle público de una convocatoria (CV-07; Q7 de D-API: "un puerto
 * mínimo de identity que devuelve solo el nombre de la organización"; plan B6-a, Q-B6A-1 (a), Carlos, 2026-10-07).
 * Devuelve solo el nombre: ni miembros, ni estado de verificación, ni ningún otro dato de la organización.
 */
public interface OrganizationPublicNamePort {

    /** Vacío si la organización no existe o no tiene nombre (el nombre es opcional, DD-04). */
    Optional<String> findPublicName(String organizationId);
}
