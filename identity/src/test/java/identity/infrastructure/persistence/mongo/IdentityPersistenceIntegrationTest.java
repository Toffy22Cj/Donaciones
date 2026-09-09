package identity.infrastructure.persistence.mongo;


import identity.domain.model.Account;
import identity.domain.model.AccountId;
import identity.domain.model.AuditAction;
import identity.domain.model.AuditLogEntry;
import identity.domain.model.Email;
import identity.domain.model.Organization;
import identity.domain.model.OrganizationId;
import identity.domain.model.OrganizationType;
import identity.domain.model.PasswordHash;
import identity.domain.model.Role;
import identity.infrastructure.persistence.mongo.repositories.MongoAccountRepositoryAdapter;
import identity.infrastructure.persistence.mongo.repositories.MongoAuditLogAdapter;
import identity.infrastructure.persistence.mongo.repositories.MongoOrganizationRepositoryAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DataMongoTest
@Import({MongoAccountRepositoryAdapter.class, MongoOrganizationRepositoryAdapter.class, MongoAuditLogAdapter.class})
class IdentityPersistenceIntegrationTest extends BaseMongoIntegrationTest {

    @Autowired
    private MongoAccountRepositoryAdapter accountRepository;

    @Autowired
    private MongoOrganizationRepositoryAdapter organizationRepository;

    @Autowired
    private MongoAuditLogAdapter auditLogRepository;

    @Test
    void testAccountRoundTrip() {
        Email email = new Email("roundtrip@example.com");
        PasswordHash hash = new PasswordHash("myhash");
        Account account = Account.createAccount(email, hash);

        accountRepository.save(account);

        Account retrieved = accountRepository.findById(account.getAccountId());

        assertEquals(account.getAccountId(), retrieved.getAccountId());
        assertEquals(email, retrieved.getEmail());
        assertEquals(hash, retrieved.getPasswordHash());
        assertEquals(account.getStatus(), retrieved.getStatus());
        assertNull(retrieved.getOrganizationId());
    }

    @Test
    void testAccountUniqueEmailConstraint() {
        Email email = new Email("duplicate@example.com");
        Account account1 = Account.createAccount(email, new PasswordHash("hash1"));
        accountRepository.save(account1);

        Account account2 = Account.createAccount(email, new PasswordHash("hash2"));
        
        assertThrows(DuplicateKeyException.class, () -> {
            accountRepository.save(account2);
        });
    }

    @Test
    void testOrganizationRoundTripAndRoleSerialization() {
        AccountId repId = AccountId.generate();
        Organization org = Organization.createOrganization(OrganizationType.COMPANY, repId);
        
        AccountId empId = AccountId.generate();
        org.addEmployee(empId);
        org.assignAdministrator(empId);

        organizationRepository.save(org);

        Organization retrieved = organizationRepository.findById(org.getOrganizationId());

        assertEquals(org.getOrganizationId(), retrieved.getOrganizationId());
        assertEquals(OrganizationType.COMPANY, retrieved.getType());
        assertEquals(2, retrieved.getMembers().size());

        assertTrue(retrieved.getMembers().stream().anyMatch(m -> m.getAccountId().equals(repId) && m.hasRole(Role.REPRESENTATIVE)));
        assertTrue(retrieved.getMembers().stream().anyMatch(m -> m.getAccountId().equals(empId) && m.hasRole(Role.EMPLOYEE) && m.hasRole(Role.ADMINISTRATOR)));
    }

    @Autowired
    private identity.infrastructure.persistence.mongo.repositories.spring.SpringDataAuditLogRepository springDataAuditLogRepository;

    @Test
    void testAuditLogRoundTrip() {
        AccountId actorId = AccountId.generate();
        OrganizationId targetOrgId = OrganizationId.generate();
        
        // Use truncated instant because MongoDB driver serializes Instant to date which truncates to milliseconds
        Instant occurredAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        
        AuditLogEntry entry = new AuditLogEntry(
            "audit-123",
            occurredAt,
            actorId,
            null,
            targetOrgId,
            AuditAction.EMPLOYEE_ADDED,
            Map.of("role", "EMPLOYEE")
        );

        auditLogRepository.record(entry);

        Optional<identity.infrastructure.persistence.mongo.documents.AuditLogEntryDocument> documentOptional = springDataAuditLogRepository.findById(entry.auditId());
        assertTrue(documentOptional.isPresent(), "AuditLogEntryDocument should be persisted in MongoDB");
        
        identity.infrastructure.persistence.mongo.documents.AuditLogEntryDocument document = documentOptional.get();
        assertEquals(entry.auditId(), document.getAuditId());
        assertEquals(entry.occurredAt(), document.getOccurredAt());
        assertEquals(entry.actorAccountId().value(), document.getActorAccountId());
        assertNull(document.getTargetAccountId());
        assertEquals(entry.targetOrganizationId().value(), document.getTargetOrganizationId());
        assertEquals(entry.action(), document.getAction());
        assertEquals(entry.changeSummary(), document.getChangeSummary());
    }
}
