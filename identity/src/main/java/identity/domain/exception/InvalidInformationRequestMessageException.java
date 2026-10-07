package identity.domain.exception;

/**
 * Thrown when an InformationRequestMessage violates validation constraints.
 *
 * References: ADR-038 §2.6.
 */
public class InvalidInformationRequestMessageException extends RuntimeException {

    public InvalidInformationRequestMessageException(String message) {
        super(message);
    }
}
