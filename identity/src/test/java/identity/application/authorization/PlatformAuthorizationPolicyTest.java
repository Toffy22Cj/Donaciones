package identity.application.authorization;

import com.traceability.contracts.authorization.AuthorizationPrincipal;
import com.traceability.contracts.authorization.PlatformAuthority;
import identity.domain.exception.InsufficientPlatformAuthorityException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PlatformAuthorizationPolicyTest {

    private final PlatformAuthorizationPolicy policy = new PlatformAuthorizationPolicy();

    @ParameterizedTest
    @EnumSource(PlatformCommandType.class)
    void authorize_withAdministrator_succeedsForAllCommands(PlatformCommandType command) {
        AuthorizationPrincipal adminPrincipal = new AuthorizationPrincipal(
                "acc-admin-123",
                null,
                Set.of(),
                PlatformAuthority.ADMINISTRATOR
        );

        assertDoesNotThrow(() -> policy.authorize(adminPrincipal, command));
    }

    @ParameterizedTest
    @EnumSource(PlatformCommandType.class)
    void authorize_withoutAdministrator_throwsInsufficientPlatformAuthorityException(PlatformCommandType command) {
        AuthorizationPrincipal nonAdminPrincipal = new AuthorizationPrincipal(
                "acc-user-123",
                null,
                Set.of(),
                null
        );

        assertThrows(InsufficientPlatformAuthorityException.class,
                () -> policy.authorize(nonAdminPrincipal, command));
    }

    @Test
    void authorize_nullPrincipal_throwsNullPointerException() {
        assertThrows(NullPointerException.class,
                () -> policy.authorize(null, PlatformCommandType.GRANT_PLATFORM_AUTHORITY));
    }

    @Test
    void authorize_nullCommand_throwsNullPointerException() {
        AuthorizationPrincipal adminPrincipal = new AuthorizationPrincipal(
                "acc-admin-123",
                null,
                Set.of(),
                PlatformAuthority.ADMINISTRATOR
        );

        assertThrows(NullPointerException.class,
                () -> policy.authorize(adminPrincipal, null));
    }
}
