package identity.application.authorization;

import com.traceability.contracts.authorization.AuthorizationPrincipal;
import identity.domain.model.AccountId;
import identity.domain.model.AuditActor;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Maps an authenticated {@link AuthorizationPrincipal} to an {@link AuditActor.AccountAuditActor}
 * for auditable actions (ADR-038 §2.2, §2.3).
 */
@Component
public class AuthorizationAuditActorMapper {

    public AuditActor toAuditActor(AuthorizationPrincipal principal) {
        Objects.requireNonNull(principal, "principal must not be null");
        return new AuditActor.AccountAuditActor(new AccountId(principal.accountId()));
    }
}
