package identity.domain.exception;

/** Ficha R-7 §5: `resubmit` solo desde `NEEDS_MORE_INFORMATION` (409 `VerificationResubmitNotAllowed`). */
public class VerificationResubmitNotAllowedException extends RuntimeException {
    public VerificationResubmitNotAllowedException(String message) {
        super(message);
    }
}
