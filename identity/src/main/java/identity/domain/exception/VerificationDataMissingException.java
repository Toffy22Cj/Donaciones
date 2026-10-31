package identity.domain.exception;

/** Ficha R-7, P2 (b): no se verifica una organización sin razón social o sin NIT (409 `VerificationDataMissing`). */
public class VerificationDataMissingException extends RuntimeException {
    public VerificationDataMissingException(String message) {
        super(message);
    }
}
