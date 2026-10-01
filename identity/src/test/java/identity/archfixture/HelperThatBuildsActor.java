package identity.archfixture;

import identity.domain.model.AccountId;
import identity.domain.model.AuditActor;

public class HelperThatBuildsActor {
    public void illegalBuild() {
        new AuditActor.AccountAuditActor(AccountId.generate());
    }
}
