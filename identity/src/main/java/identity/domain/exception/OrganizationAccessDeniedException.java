package identity.domain.exception;

/**
 * Lectura de una organización por quien no es {@code ADMINISTRATOR} ni {@code REPRESENTATIVE} de ella, o de una
 * organización inexistente (P2.7, ID-12). Hacia fuera, el mismo 403 que cualquier otro acceso denegado (DD-01).
 */
public class OrganizationAccessDeniedException extends RuntimeException {
    public OrganizationAccessDeniedException(String message) {
        super(message);
    }
}
