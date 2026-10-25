package com.traceability.contracts.authentication;

/**
 * Autenticación por email y contraseña (ADR-038 §2.7; plan B3 §2.1).
 */
public interface AuthenticateAccountPort {

    /**
     * Cambia la firma, no añade un método (ADR-050 Enmienda 1, E1-1 y, después, E3-1 de la parte 3): así no queda un
     * camino de login que ignore {@code credentialsNotBefore} ni las aceptaciones vigentes (D3).
     *
     * @param accepted las versiones que el cliente acepta en este login; {@link AcceptedLegalVersions#NONE} si ninguna
     * @return el {@code accountId} y el {@code credentialsNotBefore} de la cuenta autenticada, de la misma lectura
     * @throws AuthenticationFailedException si el email no existe, la contraseña no coincide o la cuenta está
     *         {@code INACTIVE}. Los tres casos son indistinguibles para el llamador (ID01-D1), y se comprueban
     *         <b>antes</b> que las aceptaciones (E3-2)
     * @throws AcceptanceRequiredException si la contraseña es correcta pero faltan versiones vigentes y
     *         {@code accepted} no trae exactamente esas (E3-2)
     */
    AuthenticatedAccount authenticate(String email, String password, AcceptedLegalVersions accepted);
}
