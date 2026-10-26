package com.traceability.core.application.port.out;

import java.util.List;

/** Fondos de una organización, por su génesis en el event store (plan P1.1, DD-31). */
public interface FundDirectoryPort {

    /** Ids de los fondos cuya génesis lleva esa organización, en orden de creación, como mucho {@code limit}. */
    List<String> findFundIdsByOrganization(String organizationRef, int limit);

    /**
     * Enmienda 3 de la ficha R-6 (P-E2-2; autorizado por Manuel, reglas-equipo §3.5): los mismos fondos, de la génesis
     * más reciente a la más antigua (en un empate, {@code streamId} descendente), como mucho {@code limit}. Solo lo usa
     * G-8; el método de arriba no cambia.
     */
    List<String> findLatestFundIdsByOrganization(String organizationRef, int limit);
}
