package identity.domain.exception;

public class IdentityTransactionOutcomeUnknownException extends RuntimeException {
    public IdentityTransactionOutcomeUnknownException(String message) {
        super(message);
    }

    public IdentityTransactionOutcomeUnknownException(String message, Throwable cause) {
        super(message, cause);
    }
}
