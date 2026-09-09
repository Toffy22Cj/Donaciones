package identity.application.service;

import identity.application.port.out.AuditLogPort;
import identity.domain.model.Account;
import identity.domain.model.AuditAction;
import identity.domain.model.AuditLogEntry;
import identity.domain.model.Email;
import identity.domain.model.Organization;
import identity.domain.model.OrganizationType;
import identity.domain.model.PasswordHash;
import identity.infrastructure.persistence.mongo.BaseMongoIntegrationTest;
import identity.infrastructure.persistence.mongo.IdentityTestApplication;
import identity.infrastructure.persistence.mongo.repositories.MongoAccountRepositoryAdapter;
import identity.infrastructure.persistence.mongo.repositories.MongoAuditLogAdapter;
import identity.infrastructure.persistence.mongo.repositories.MongoOrganizationRepositoryAdapter;
import identity.infrastructure.security.BCryptPasswordHasherAdapter;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@DataMongoTest
@ContextConfiguration(classes = IdentityTestApplication.class)
@Import({
        MongoAccountRepositoryAdapter.class,
        MongoAuditLogAdapter.class,
        MongoOrganizationRepositoryAdapter.class,
        BCryptPasswordHasherAdapter.class,
        MongoTransactionRetryHelper.class,
        CreateAccountService.class,
        CreateOrganizationService.class,
        AddEmployeeService.class,
        AssignAdministratorService.class,
        TransferRepresentativeAndRemoveService.class,
        RemoveMemberFromOrganizationService.class
})
class IdentityEndToEndIntegrationTest extends BaseMongoIntegrationTest {

    @Autowired
    private CreateAccountService createAccountService;

    @Autowired
    private CreateOrganizationService createOrganizationService;

    @Autowired
    private AddEmployeeService addEmployeeService;

    @Autowired
    private AssignAdministratorService assignAdministratorService;

    @Autowired
    private TransferRepresentativeAndRemoveService transferRepresentativeAndRemoveService;

    @Autowired
    private RemoveMemberFromOrganizationService removeMemberFromOrganizationService;

    @MockitoSpyBean
    private AuditLogPort auditLogPort;

    @Test
    void endToEnd_identityLifecycle_auditLogExactVerification() {
        // 1. Create Accounts
        Account rep = createAccountService.createAccount(new Email("rep@test.com"), "Password123!");
        Account emp1 = createAccountService.createAccount(new Email("emp1@test.com"), "Password123!");
        Account emp2 = createAccountService.createAccount(new Email("emp2@test.com"), "Password123!");
        Account emp3 = createAccountService.createAccount(new Email("emp3@test.com"), "Password123!");

        // 2. Create Organization with Representative
        Organization org = createOrganizationService.createOrganization(OrganizationType.COMPANY, rep.getAccountId());

        // 3. Add 3 Employees
        addEmployeeService.addEmployee(org.getOrganizationId(), emp1.getAccountId());
        addEmployeeService.addEmployee(org.getOrganizationId(), emp2.getAccountId());
        addEmployeeService.addEmployee(org.getOrganizationId(), emp3.getAccountId());

        // 4. Assign Administrator to emp1
        assignAdministratorService.assignAdministrator(org.getOrganizationId(), emp1.getAccountId());

        // 5. Try to assign Administrator to emp1 again (should be no-op, no new audit log)
        assignAdministratorService.assignAdministrator(org.getOrganizationId(), emp1.getAccountId());

        // 6. Transfer Representative from rep to emp1
        // (Since rep only has the REPRESENTATIVE role, this operation will completely remove rep's Membership from the Organization)
        transferRepresentativeAndRemoveService.transferRepresentativeAndRemove(org.getOrganizationId(), rep.getAccountId(), emp1.getAccountId());

        // 7. Remove emp2 from the organization
        removeMemberFromOrganizationService.removeMemberFromOrganization(org.getOrganizationId(), emp2.getAccountId());

        // 8. Verify the exact Audit Log sequence
        ArgumentCaptor<AuditLogEntry> captor = ArgumentCaptor.forClass(AuditLogEntry.class);
        // We expect exactly 11 Audit Log entries:
        // 4x ACCOUNT_CREATED
        // 1x ORGANIZATION_CREATED
        // 3x EMPLOYEE_ADDED
        // 1x ADMINISTRATOR_ASSIGNED
        // 0x ADMINISTRATOR_ASSIGNED (no-op skipped)
        // 1x REPRESENTATIVE_TRANSFERRED
        // 1x MEMBER_REMOVED
        verify(auditLogPort, times(11)).record(captor.capture());

        List<AuditLogEntry> logs = captor.getAllValues();
        assertEquals(11, logs.size());
        assertEquals(AuditAction.ACCOUNT_CREATED, logs.get(0).action());
        assertEquals(AuditAction.ACCOUNT_CREATED, logs.get(1).action());
        assertEquals(AuditAction.ACCOUNT_CREATED, logs.get(2).action());
        assertEquals(AuditAction.ACCOUNT_CREATED, logs.get(3).action());
        assertEquals(AuditAction.ORGANIZATION_CREATED, logs.get(4).action());
        assertEquals(AuditAction.EMPLOYEE_ADDED, logs.get(5).action());
        assertEquals(AuditAction.EMPLOYEE_ADDED, logs.get(6).action());
        assertEquals(AuditAction.EMPLOYEE_ADDED, logs.get(7).action());
        assertEquals(AuditAction.ADMINISTRATOR_ASSIGNED, logs.get(8).action());
        assertEquals(AuditAction.REPRESENTATIVE_TRANSFERRED, logs.get(9).action());
        assertEquals(AuditAction.MEMBER_REMOVED, logs.get(10).action());
    }
}
