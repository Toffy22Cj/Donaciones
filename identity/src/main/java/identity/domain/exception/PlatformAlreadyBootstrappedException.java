package identity.domain.exception;

public class PlatformAlreadyBootstrappedException extends RuntimeException {

    public PlatformAlreadyBootstrappedException(String message) {
        super(message);
    }

    public PlatformAlreadyBootstrappedException(String message, Throwable cause) {
        super(message, cause);
    }
}
