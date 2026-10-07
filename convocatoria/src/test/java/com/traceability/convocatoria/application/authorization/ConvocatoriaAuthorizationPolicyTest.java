package com.traceability.convocatoria.application.authorization;

import com.traceability.contracts.authorization.AuthorizationRole;
import com.traceability.convocatoria.domain.exception.ActorNotInCampaignOrganizationException;
import com.traceability.convocatoria.domain.exception.ActorRoleNotAllowedException;
import com.traceability.convocatoria.domain.exception.InvalidResponsibleRecipientException;
import com.traceability.convocatoria.domain.exception.OrganizationNotVerifiedException;
import com.traceability.convocatoria.domain.model.OrganizationVerification;
import com.traceability.convocatoria.support.FakeIdentityPrincipalPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.traceability.contracts.authorization.AuthorizationRole.ADMINISTRATOR;
import static com.traceability.contracts.authorization.AuthorizationRole.EMPLOYEE;
import static com.traceability.contracts.authorization.AuthorizationRole.REPRESENTATIVE;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * implementation_plan.md §6: un caso positivo y uno negativo por fila, incluidos {@code REPRESENTATIVE},
 * {@code EMPLOYEE} y actor de otra organización rechazados. Todas las operaciones administrativas del módulo
 * (crear, editar, asignar, designar, retirar, cerrar) exigen la misma regla: {@code ADMINISTRATOR} de la organización.
 * Crear {@code DonationIntent} no pasa por esta política (ADR-037 §5).
 */
class ConvocatoriaAuthorizationPolicyTest {

    private static final String ORG = "org-1";
    private final FakeIdentityPrincipalPort identity = new FakeIdentityPrincipalPort();
    private final ConvocatoriaAuthorizationPolicy policy = new ConvocatoriaAuthorizationPolicy(identity);

    @BeforeEach
    void setUp() {
        identity.register("admin", ORG, ADMINISTRATOR);
        identity.register("admin-employee", ORG, ADMINISTRATOR, EMPLOYEE);
        identity.register("employee", ORG, EMPLOYEE);
        identity.register("representative", ORG, REPRESENTATIVE);
        identity.register("other-admin", "org-2", ADMINISTRATOR);
        identity.register("no-org", null);
    }

    @Test
    void administratorOfTheOrganizationIsAuthorized() {
        ConvocatoriaActor actor = policy.requireAdministratorOf("admin", ORG);
        assertEquals("admin", actor.accountId());
        assertEquals(ORG, actor.organizationRef());
        assertDoesNotThrow(() -> policy.requireAdministratorOf("admin-employee", ORG));
    }

    @Test
    void employeeIsRejected() {
        assertThrows(ActorRoleNotAllowedException.class, () -> policy.requireAdministratorOf("employee", ORG));
    }

    @Test
    void representativeIsRejected() {
        assertThrows(ActorRoleNotAllowedException.class, () -> policy.requireAdministratorOf("representative", ORG));
    }

    @Test
    void administratorOfAnotherOrganizationIsRejected() {
        assertThrows(ActorNotInCampaignOrganizationException.class,
                () -> policy.requireAdministratorOf("other-admin", ORG));
        assertThrows(ActorNotInCampaignOrganizationException.class, () -> policy.requireAdministratorOf("no-org", ORG));
    }

    @Test
    void employeeRecipientMustBeEmployeeOfTheOrganization() {
        assertDoesNotThrow(() -> policy.requireRecipient("employee", ORG, EMPLOYEE));
        assertDoesNotThrow(() -> policy.requireRecipient("admin-employee", ORG, EMPLOYEE));
        assertThrows(InvalidResponsibleRecipientException.class, () -> policy.requireRecipient("admin", ORG, EMPLOYEE));
        assertThrows(InvalidResponsibleRecipientException.class,
                () -> policy.requireRecipient("representative", ORG, EMPLOYEE));
    }

    @Test
    void administratorRecipientMustBeAdministratorOfTheOrganization() {
        assertDoesNotThrow(() -> policy.requireRecipient("admin", ORG, ADMINISTRATOR));
        assertThrows(InvalidResponsibleRecipientException.class,
                () -> policy.requireRecipient("employee", ORG, ADMINISTRATOR));
        assertThrows(InvalidResponsibleRecipientException.class,
                () -> policy.requireRecipient("other-admin", ORG, ADMINISTRATOR));
    }

    @Test
    void portExceptionsPropagateWithoutBeingCaughtOrRenamed() {
        FakeIdentityPrincipalPort.UnknownAccountException thrown = assertThrows(
                FakeIdentityPrincipalPort.UnknownAccountException.class,
                () -> policy.requireRecipient("ghost", ORG, AuthorizationRole.EMPLOYEE));
        assertEquals("Unknown account ghost", thrown.getMessage());
        assertThrows(FakeIdentityPrincipalPort.UnknownAccountException.class,
                () -> policy.requireAdministratorOf("ghost", ORG));
    }

    @Test
    void organizationVerificationPrecondition() {
        assertDoesNotThrow(() -> OrganizationVerification.requireVerified(ORG, true));
        OrganizationNotVerifiedException e = assertThrows(OrganizationNotVerifiedException.class,
                () -> OrganizationVerification.requireVerified(ORG, false));
        assertSame(OrganizationNotVerifiedException.class, e.getClass());
    }
}
