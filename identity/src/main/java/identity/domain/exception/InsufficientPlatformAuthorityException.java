package identity.domain.exception;

public class InsufficientPlatformAuthorityException extends RuntimeException {
    public InsufficientPlatformAuthorityException(String message) {
        super(message);
    }
}
