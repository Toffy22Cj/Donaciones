package identity.domain.exception;

/** [Plan] ADR-050 D9 (P-G-4): la cuenta ya está vinculada a otro Google (409 `AccountAlreadyLinkedToGoogle`). */
public class AccountAlreadyLinkedToGoogleException extends RuntimeException {
    public AccountAlreadyLinkedToGoogleException(String message) {
        super(message);
    }
}
