package identity.domain.exception;

/** [Plan] ADR-050 D9: el `idToken` no es válido (firma, `iss`, `aud`, `exp` o `email_verified`). Al iniciar sesión es el 401 de siempre; al vincular, 400 `GoogleIdTokenInvalid` (P-G-4). El mensaje nunca lleva el token. */
public class GoogleIdTokenInvalidException extends RuntimeException {
    public GoogleIdTokenInvalidException(String message) {
        super(message);
    }
}
