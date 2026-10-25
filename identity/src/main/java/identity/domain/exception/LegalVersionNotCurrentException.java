package identity.domain.exception;

/**
 * ADR-050 D2: una versión aceptada que falta o no es la vigente. {@code field} es el nombre del campo de la petición
 * ({@code acceptedTermsVersion}, …), para el 400 con el campo nombrado.
 */
public class LegalVersionNotCurrentException extends RuntimeException {
    private final String field;

    public LegalVersionNotCurrentException(String field) {
        super("Accepted legal version is missing or not current: " + field);
        this.field = field;
    }

    public String getField() {
        return field;
    }
}
