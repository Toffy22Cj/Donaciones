package identity.domain.exception;

public class CannotRemoveLastRoleException extends RuntimeException {
    public CannotRemoveLastRoleException(String message) {
        super(message);
    }
}
