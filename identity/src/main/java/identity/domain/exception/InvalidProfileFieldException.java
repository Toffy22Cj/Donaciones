package identity.domain.exception;

/** ADR-050 D1: un campo del perfil vacío tras recortar o de más de 100 puntos de código. Nunca lleva el valor. */
public class InvalidProfileFieldException extends RuntimeException {
    private final String field;

    public InvalidProfileFieldException(String field) {
        super("Invalid profile field: " + field);
        this.field = field;
    }

    public String getField() {
        return field;
    }
}
