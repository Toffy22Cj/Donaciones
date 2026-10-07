package identity.domain.exception;

public class AccountAlreadyBelongsToOrganizationException extends RuntimeException {
    public AccountAlreadyBelongsToOrganizationException(String message) {
        super(message);
    }
}
