package identity.domain.exception;

/** [Plan] Ficha R-7 §3: un campo del perfil de la organización no tiene la forma pedida. Lleva el nombre del campo, nunca el valor; la respuesta es el 400 de siempre. */
public class InvalidOrganizationProfileFieldException extends RuntimeException {

    private final String field;

    public InvalidOrganizationProfileFieldException(String field) {
        super("Invalid organization profile field: " + field);
        this.field = field;
    }

    public String getField() {
        return field;
    }
}
