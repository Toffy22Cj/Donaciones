package com.traceability.contracts.authentication;

/**
 * ADR-050 Enmienda 2, E2-3 (Q1 (a)): la contraseña es correcta y la cuenta está activa, pero su correo no está
 * verificado. Se lanza <b>después</b> de comprobar la contraseña y el estado, y antes de las aceptaciones (D3): una
 * contraseña incorrecta sigue siendo el 401 de siempre, sin revelar si el correo está verificado. Sin datos.
 */
public class EmailNotVerifiedException extends RuntimeException {

    public EmailNotVerifiedException() {
        super("The email of the account is not verified");
    }
}
