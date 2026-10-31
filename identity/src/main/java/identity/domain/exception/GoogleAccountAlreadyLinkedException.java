package identity.domain.exception;

/** [Plan] ADR-050 D9 (P-G-4): ese Google ya está vinculado a otra cuenta (409 `GoogleAccountAlreadyLinked`). */
public class GoogleAccountAlreadyLinkedException extends RuntimeException {
    public GoogleAccountAlreadyLinkedException(String message) {
        super(message);
    }
}
