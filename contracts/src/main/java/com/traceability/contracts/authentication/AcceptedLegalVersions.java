package com.traceability.contracts.authentication;

/**
 * [Enmienda] Versiones de los textos legales que el cliente dice aceptar en el login (ADR-050 Enmienda 1, parte 3,
 * E3-1). Los dos campos son anulables: un login sin versiones las manda nulas ({@link #NONE}).
 */
public record AcceptedLegalVersions(String termsVersion, String dataPolicyVersion) {

    /** Un login que no acepta nada. */
    public static final AcceptedLegalVersions NONE = new AcceptedLegalVersions(null, null);
}
