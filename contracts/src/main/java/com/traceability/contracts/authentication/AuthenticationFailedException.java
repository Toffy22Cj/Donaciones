package com.traceability.contracts.authentication;

/**
 * Fallo de autenticación, único para todos los motivos (plan B3 §2.1): ni el tipo ni el mensaje revelan si el email
 * existe, si la contraseña es incorrecta o si la cuenta está desactivada.
 */
public class AuthenticationFailedException extends RuntimeException {

    public AuthenticationFailedException() {
        super("Authentication failed");
    }
}
