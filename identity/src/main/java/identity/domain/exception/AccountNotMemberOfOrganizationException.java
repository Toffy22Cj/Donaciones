package identity.domain.exception;

public class AccountNotMemberOfOrganizationException extends RuntimeException {
    public AccountNotMemberOfOrganizationException(String message) {
        super(message);
    }
}
