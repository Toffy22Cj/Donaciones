package com.traceability.contracts.authentication;

/**
 * Autenticación por email y contraseña (ADR-038 §2.7; plan B3 §2.1).
 */
public interface AuthenticateAccountPort {

    /**
     * Cambia la firma, no añade un método (ADR-050 Enmienda 1, E1-1): así no queda un camino de login que ignore
     * {@code credentialsNotBefore}.
     *
     * @return el {@code accountId} y el {@code credentialsNotBefore} de la cuenta autenticada, de la misma lectura
     * @throws AuthenticationFailedException si el email no existe, la contraseña no coincide o la cuenta está
     *         {@code INACTIVE}. Los tres casos son indistinguibles para el llamador (ID01-D1).
     */
    AuthenticatedAccount authenticate(String email, String password);
}
