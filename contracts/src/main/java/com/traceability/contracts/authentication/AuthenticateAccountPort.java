package com.traceability.contracts.authentication;

/**
 * Autenticación por email y contraseña (ADR-038 §2.7; plan B3 §2.1).
 */
public interface AuthenticateAccountPort {

    /**
     * @return el {@code accountId} de la cuenta autenticada
     * @throws AuthenticationFailedException si el email no existe, la contraseña no coincide o la cuenta está
     *         {@code INACTIVE}. Los tres casos son indistinguibles para el llamador (ID01-D1).
     */
    String authenticate(String email, String password);
}
