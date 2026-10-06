package identity.domain.exception;

public class BootstrapTargetAccountNotFoundException extends RuntimeException {

    public BootstrapTargetAccountNotFoundException() {
        super("Bootstrap target account not found");
    }

    public BootstrapTargetAccountNotFoundException(String message) {
        super(message);
    }
}
