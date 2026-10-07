package com.traceability.convocatoria.application.query;

import java.util.Optional;

/** Lectura pública de una convocatoria (CV-07; ADR-037 CD-06; plan B6-a §2.3). Sin un modelo de lectura nuevo. */
public interface ConvocatoriaReadPort {

    /** Vacío si no existe o si el código no tiene la forma de uno emitido (en ese caso ni se consulta). */
    Optional<PublicCampaignView> findPublicByCode(String publicCode);
}
