package identity.domain.exception;

/** Ficha R-7, P5: el NIT ya pertenece a otra organización (409 `OrganizationNitAlreadyRegistered`). */
public class OrganizationNitAlreadyRegisteredException extends RuntimeException {
    public OrganizationNitAlreadyRegisteredException(String message) {
        super(message);
    }
}
