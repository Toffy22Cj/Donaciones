package identity.archfixture;

import identity.domain.model.AuditActor;

public class HelperThatBuildsSystemActor {
    public void illegalBuild() {
        new AuditActor.SystemAuditActor("illegal-process");
    }
}
