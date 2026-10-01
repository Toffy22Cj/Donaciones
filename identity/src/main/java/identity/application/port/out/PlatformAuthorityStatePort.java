package identity.application.port.out;

/**
 * Port for managing the singleton platform authority state document (ADR-038 §2.3).
 */
public interface PlatformAuthorityStatePort {

    /**
     * Checks if the singleton platform authority state document exists.
     *
     * @return true if the singleton document exists, false otherwise
     */
    boolean exists();

    /**
     * Atomically increments the active administrator count and version by 1.
     *
     * @throws identity.domain.exception.PlatformAuthorityStateMissingException if the document does not exist
     */
    void incrementAdministrators();

    /**
     * Atomically decrements the active administrator count by 1 and increments version by 1,
     * but only if the current active administrator count is strictly greater than 1.
     *
     * @return true if decremented successfully, false if count <= 1 or document not found
     */
    boolean decrementAdministratorsIfMoreThanOne();
}
