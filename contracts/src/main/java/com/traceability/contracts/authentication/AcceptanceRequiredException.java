package com.traceability.contracts.authentication;

/**
 * [Enmienda] La contraseña es correcta, pero la cuenta tiene versiones legales pendientes y el login no trae
 * exactamente las vigentes (ADR-050 D3 y Enmienda 1, parte 3, E3-2). Lleva solo las pendientes; la otra, nula. Nunca
 * se lanza antes de validar la contraseña y el estado: una contraseña incorrecta sigue siendo el 401 de siempre.
 */
public class AcceptanceRequiredException extends RuntimeException {

    private final String pendingTermsVersion;
    private final String pendingDataPolicyVersion;

    public AcceptanceRequiredException(String pendingTermsVersion, String pendingDataPolicyVersion) {
        super("Acceptance of the current legal versions is required");
        this.pendingTermsVersion = pendingTermsVersion;
        this.pendingDataPolicyVersion = pendingDataPolicyVersion;
    }

    public String pendingTermsVersion() {
        return pendingTermsVersion;
    }

    public String pendingDataPolicyVersion() {
        return pendingDataPolicyVersion;
    }
}
