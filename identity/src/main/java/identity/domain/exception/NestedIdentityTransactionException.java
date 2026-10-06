package identity.domain.exception;

public class NestedIdentityTransactionException extends RuntimeException {
    public NestedIdentityTransactionException(String message) {
        super(message);
    }

    public NestedIdentityTransactionException(String message, Throwable cause) {
        super(message, cause);
    }
}
