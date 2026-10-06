package identity.domain.exception;

public class PlatformAuthorityAlreadyGrantedException extends RuntimeException {
    public PlatformAuthorityAlreadyGrantedException(String message) {
        super(message);
    }
}
