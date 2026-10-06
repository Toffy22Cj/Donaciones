package identity.domain.exception;

import identity.domain.model.VerificationCommand;
import identity.domain.model.VerificationStatus;

/**
 * Thrown when an invalid verification transition is attempted on an Organization.
 *
 * References: ADR-038 §2.5, D8g.
 */
public class InvalidVerificationTransitionException extends RuntimeException {

    private final VerificationStatus currentStatus;
    private final VerificationCommand command;

    public InvalidVerificationTransitionException(VerificationStatus currentStatus, VerificationCommand command) {
        super("Invalid verification transition: cannot execute command [" + command + "] when organization status is [" + currentStatus + "]");
        this.currentStatus = currentStatus;
        this.command = command;
    }

    public VerificationStatus getCurrentStatus() {
        return currentStatus;
    }

    public VerificationCommand getCommand() {
        return command;
    }
}
