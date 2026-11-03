package identity.application.archfixture;

import identity.domain.model.Organization;

/**
 * Fixture de ArchUnit (plan de preverificación, test f): una consulta que decide la verificación. Solo existe para
 * comprobar que la regla {@code queries_must_not_decide_the_verification} muerde. No es código de producción.
 */
public class ArchfixtureDecidingQuery {

    public void decide(Organization organization) {
        if (organization.missingForVerification().isEmpty()) {
            organization.verify();
        } else {
            organization.reject();
        }
    }
}
