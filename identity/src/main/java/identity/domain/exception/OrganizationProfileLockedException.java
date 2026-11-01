package identity.domain.exception;

/** Ficha R-7, P1 (b): una organización `VERIFIED` no cambia `name`, `legalName`, `nit` ni `nitCheckDigit` (409 `OrganizationProfileLocked`). */
public class OrganizationProfileLockedException extends RuntimeException {
    public OrganizationProfileLockedException(String message) {
        super(message);
    }
}
