package identity.domain.exception;

public class IdentityConcurrentModificationException extends RuntimeException {
    public IdentityConcurrentModificationException(String message) {
        super(message);
    }

    public IdentityConcurrentModificationException(String message, Throwable cause) {
        super(message, cause);
    }
}
