package identity.domain.exception;

/**
 * Thrown when reading an organization document from MongoDB that violates verification or domain invariants.
 *
 * References: ADR-038 §2.5, §2.6.
 */
public class CorruptOrganizationDocumentException extends RuntimeException {

    private final String organizationId;

    public CorruptOrganizationDocumentException(String organizationId, String message) {
        super("Corrupt organization document [" + organizationId + "]: " + message);
        this.organizationId = organizationId;
    }

    public CorruptOrganizationDocumentException(String organizationId, String message, Throwable cause) {
        super("Corrupt organization document [" + organizationId + "]: " + message, cause);
        this.organizationId = organizationId;
    }

    public String getOrganizationId() {
        return organizationId;
    }
}
