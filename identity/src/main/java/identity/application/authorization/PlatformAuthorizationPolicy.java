package identity.application.authorization;

import com.traceability.contracts.authorization.AuthorizationPrincipal;
import com.traceability.contracts.authorization.PlatformAuthority;
import identity.domain.exception.InsufficientPlatformAuthorityException;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Evaluates platform-level authorization policies before executing platform commands (ADR-038 §2.7).
 */
@Component
public class PlatformAuthorizationPolicy {

    public void authorize(AuthorizationPrincipal principal, PlatformCommandType command) {
        Objects.requireNonNull(principal, "principal must not be null");
        Objects.requireNonNull(command, "command must not be null");

        boolean authorized = switch (command) {
            case VERIFY_ORGANIZATION,
                 REJECT_ORGANIZATION,
                 REQUEST_ORGANIZATION_INFORMATION,
                 GRANT_PLATFORM_AUTHORITY,
                 REVOKE_PLATFORM_AUTHORITY ->
                    principal.platformAuthority() == PlatformAuthority.ADMINISTRATOR;
        };

        if (!authorized) {
            throw new InsufficientPlatformAuthorityException(
                    "Principal lacks platform administrator authority for command: " + command);
        }
    }
}
