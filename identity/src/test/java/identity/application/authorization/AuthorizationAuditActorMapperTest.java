package identity.application.authorization;

import com.traceability.contracts.authorization.AuthorizationPrincipal;
import com.traceability.contracts.authorization.PlatformAuthority;
import identity.domain.model.AuditActor;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class AuthorizationAuditActorMapperTest {

    private final AuthorizationAuditActorMapper mapper = new AuthorizationAuditActorMapper();

    @Test
    void toAuditActor_returnsAccountAuditActor() {
        AuthorizationPrincipal principal = new AuthorizationPrincipal(
                "acc-caller-123",
                null,
                Set.of(),
                PlatformAuthority.ADMINISTRATOR
        );

        AuditActor actor = mapper.toAuditActor(principal);

        assertInstanceOf(AuditActor.AccountAuditActor.class, actor);
        AuditActor.AccountAuditActor accountActor = (AuditActor.AccountAuditActor) actor;
        assertEquals("acc-caller-123", accountActor.accountId().value());
    }

    @Test
    void toAuditActor_nullPrincipal_throwsNullPointerException() {
        assertThrows(NullPointerException.class, () -> mapper.toAuditActor(null));
    }
}
