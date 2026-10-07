package identity.domain.exception;

/** Rol que no se puede invitar ni asignar con un cambio de rol: solo ADMINISTRATOR o EMPLOYEE (ADR-049 D5, D7). */
public class InvalidMemberRoleException extends RuntimeException {
    public InvalidMemberRoleException(String message) {
        super(message);
    }
}
