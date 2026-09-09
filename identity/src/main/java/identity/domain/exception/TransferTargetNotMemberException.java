package identity.domain.exception;

public class TransferTargetNotMemberException extends RuntimeException {
    public TransferTargetNotMemberException(String message) {
        super(message);
    }
}
