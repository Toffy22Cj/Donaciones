package identity.infrastructure.persistence.mongo;


import identity.domain.model.Account;
import identity.domain.model.AccountId;
import identity.domain.model.AuditAction;
import identity.domain.model.AuditActor;
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
import java.util.UUID;

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
    void testAccountRoundTrip_withPlatformAdministrator() {
        AccountId accountId = AccountId.generate();
        Email email = new Email("platform.admin@example.com");
        PasswordHash hash = new PasswordHash("myhash");
        Account account = Account.reconstitute(
                accountId,
                email,
                hash,
                identity.domain.model.AccountStatus.ACTIVE,
                null,
                identity.domain.model.PlatformAuthority.ADMINISTRATOR
        );

        accountRepository.save(account);

        org.bson.Document rawDoc = mongoTemplate.findOne(
                new org.springframework.data.mongodb.core.query.Query(
                        org.springframework.data.mongodb.core.query.Criteria.where("_id").is(accountId.value())),
                org.bson.Document.class,
                "accounts"
        );
        assertNotNull(rawDoc);
        assertEquals("ADMINISTRATOR", rawDoc.getString("platformAuthority"));

        Account retrieved = accountRepository.findById(accountId);
        assertNotNull(retrieved);
        assertEquals(accountId, retrieved.getAccountId());
        assertEquals(email, retrieved.getEmail());
        assertEquals(identity.domain.model.PlatformAuthority.ADMINISTRATOR, retrieved.getPlatformAuthority());
    }

    @Test
    void testAccount_legacyDocumentWithoutPlatformAuthority_readsAsNull() {
        AccountId accountId = AccountId.generate();
        org.bson.Document rawDoc = new org.bson.Document("_id", accountId.value())
                .append("email", "legacy.account@example.com")
                .append("passwordHash", "legacyhash")
                .append("status", "ACTIVE");
        mongoTemplate.insert(rawDoc, "accounts");

        Account retrieved = accountRepository.findById(accountId);
        assertNotNull(retrieved);
        assertEquals(accountId, retrieved.getAccountId());
        assertNull(retrieved.getPlatformAuthority());
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

    @Autowired
    private org.springframework.data.mongodb.core.MongoTemplate mongoTemplate;

    @Test
    void testAuditLogRoundTrip() {
        AccountId actorId = AccountId.generate();
        OrganizationId targetOrgId = OrganizationId.generate();
        
        // Use truncated instant because MongoDB driver serializes Instant to date which truncates to milliseconds
        Instant occurredAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        
        AuditLogEntry entry = AuditLogEntry.record(
            "audit-123",
            occurredAt,
            new AuditActor.AccountAuditActor(actorId),
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
        assertNotNull(document.getActor());
        assertEquals("ACCOUNT", document.getActor().getType());
        assertEquals(actorId.value(), document.getActor().getAccountId());
        assertNull(document.getActorAccountId(), "actorAccountId must be null on post-cutover documents");
        assertNull(document.getTargetAccountId());
        assertEquals(entry.targetOrganizationId().value(), document.getTargetOrganizationId());
        assertEquals(entry.action(), document.getAction());
        assertEquals(entry.changeSummary(), document.getChangeSummary());
    }

    @Test
    void legacyDocument_withOnlyActorAccountId_readsAsPreCutover() {
        String auditId = UUID.randomUUID().toString();
        AccountId legacyActor = AccountId.generate();
        AccountId target = AccountId.generate();

        org.bson.Document raw = new org.bson.Document("_id", auditId)
                .append("occurredAt", java.util.Date.from(Instant.now()))
                .append("actorAccountId", legacyActor.value())
                .append("targetAccountId", target.value())
                .append("action", "ACCOUNT_DEACTIVATED")
                .append("changeSummary", Map.of());
        mongoTemplate.insert(raw, "identity_audit_log");

        identity.infrastructure.persistence.mongo.documents.AuditLogEntryDocument doc = springDataAuditLogRepository.findById(auditId).orElseThrow();
        AuditLogEntry entry = identity.infrastructure.persistence.mongo.mappers.AuditLogEntryMapper.toDomain(doc);

        assertEquals(identity.domain.model.AuditRegime.PRE_CUTOVER, entry.regime());
        assertNull(entry.actor(), "Legacy document must never produce a non-null actor");
        assertEquals(legacyActor, entry.legacyRecordedActorAccountId());
    }

    @Test
    void postCutoverDocument_withActor_readsCorrectly() {
        // 1. Caso ACCOUNT
        String auditId1 = UUID.randomUUID().toString();
        AccountId accountActorId = AccountId.generate();
        org.bson.Document rawAccount = new org.bson.Document("_id", auditId1)
                .append("occurredAt", java.util.Date.from(Instant.now()))
                .append("actor", new org.bson.Document("type", "ACCOUNT").append("accountId", accountActorId.value()))
                .append("targetAccountId", AccountId.generate().value())
                .append("action", "ACCOUNT_CREATED")
                .append("changeSummary", Map.of("selfRegistration", true));
        mongoTemplate.insert(rawAccount, "identity_audit_log");

        identity.infrastructure.persistence.mongo.documents.AuditLogEntryDocument doc1 = springDataAuditLogRepository.findById(auditId1).orElseThrow();
        AuditLogEntry entry1 = identity.infrastructure.persistence.mongo.mappers.AuditLogEntryMapper.toDomain(doc1);
        assertEquals(identity.domain.model.AuditRegime.POST_CUTOVER, entry1.regime());
        assertEquals(new AuditActor.AccountAuditActor(accountActorId), entry1.actor());
        assertNull(entry1.legacyRecordedActorAccountId());

        // 2. Caso SYSTEM
        String auditId2 = UUID.randomUUID().toString();
        org.bson.Document rawSystem = new org.bson.Document("_id", auditId2)
                .append("occurredAt", java.util.Date.from(Instant.now()))
                .append("actor", new org.bson.Document("type", "SYSTEM").append("processId", "test-process"))
                .append("targetAccountId", AccountId.generate().value())
                .append("action", "ACCOUNT_CREATED")
                .append("changeSummary", Map.of());
        mongoTemplate.insert(rawSystem, "identity_audit_log");

        identity.infrastructure.persistence.mongo.documents.AuditLogEntryDocument doc2 = springDataAuditLogRepository.findById(auditId2).orElseThrow();
        AuditLogEntry entry2 = identity.infrastructure.persistence.mongo.mappers.AuditLogEntryMapper.toDomain(doc2);
        assertEquals(identity.domain.model.AuditRegime.POST_CUTOVER, entry2.regime());
        assertEquals(new AuditActor.SystemAuditActor("test-process"), entry2.actor());
        assertNull(entry2.legacyRecordedActorAccountId());
    }

    @Test
    void recordedPostCutoverDocument_doesNotContainActorAccountId_andContainsActor() {
        AccountId actorId = AccountId.generate();
        AuditLogEntry entry = AuditLogEntry.record(
                com.github.f4b6a3.ulid.UlidCreator.getUlid().toString(),
                Instant.now().truncatedTo(ChronoUnit.MILLIS),
                new AuditActor.AccountAuditActor(actorId),
                AccountId.generate(),
                null,
                AuditAction.ACCOUNT_DEACTIVATED,
                Map.of("reason", "test")
        );

        auditLogRepository.record(entry);

        org.bson.Document rawDoc = mongoTemplate.findOne(
                new org.springframework.data.mongodb.core.query.Query(
                        org.springframework.data.mongodb.core.query.Criteria.where("_id").is(entry.auditId())),
                org.bson.Document.class,
                "identity_audit_log"
        );

        assertNotNull(rawDoc);
        assertFalse(rawDoc.containsKey("actorAccountId"), "Raw MongoDB document must NOT contain actorAccountId");
        assertTrue(rawDoc.containsKey("actor"), "Raw MongoDB document must contain actor");
        org.bson.Document actorDoc = rawDoc.get("actor", org.bson.Document.class);
        assertEquals("ACCOUNT", actorDoc.getString("type"));
        assertEquals(actorId.value(), actorDoc.getString("accountId"));
    }

    @Test
    void fullRoundTrip_forBothActorVariants() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        // AccountAuditActor round-trip
        AuditLogEntry accountEntry = AuditLogEntry.record(
                com.github.f4b6a3.ulid.UlidCreator.getUlid().toString(),
                now,
                new AuditActor.AccountAuditActor(AccountId.generate()),
                AccountId.generate(),
                null,
                AuditAction.CREDENTIALS_CHANGED,
                Map.of("strKey", "value", "boolKey", true)
        );
        auditLogRepository.record(accountEntry);
        identity.infrastructure.persistence.mongo.documents.AuditLogEntryDocument reloadedDoc1 = springDataAuditLogRepository.findById(accountEntry.auditId()).orElseThrow();
        AuditLogEntry reloaded1 = identity.infrastructure.persistence.mongo.mappers.AuditLogEntryMapper.toDomain(reloadedDoc1);
        assertEquals(accountEntry, reloaded1);
        assertEquals(identity.domain.model.AuditRegime.POST_CUTOVER, reloaded1.regime());

        // SystemAuditActor round-trip
        AuditLogEntry systemEntry = AuditLogEntry.record(
                com.github.f4b6a3.ulid.UlidCreator.getUlid().toString(),
                now,
                new AuditActor.SystemAuditActor("test-process"),
                AccountId.generate(),
                null,
                AuditAction.ACCOUNT_DEACTIVATED,
                Map.of("strKey", "systemAction", "boolKey", false)
        );
        auditLogRepository.record(systemEntry);
        identity.infrastructure.persistence.mongo.documents.AuditLogEntryDocument reloadedDoc2 = springDataAuditLogRepository.findById(systemEntry.auditId()).orElseThrow();
        AuditLogEntry reloaded2 = identity.infrastructure.persistence.mongo.mappers.AuditLogEntryMapper.toDomain(reloadedDoc2);
        assertEquals(systemEntry, reloaded2);
        assertEquals(identity.domain.model.AuditRegime.POST_CUTOVER, reloaded2.regime());
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "corrupt document: {0}")
    @org.junit.jupiter.params.provider.MethodSource("corruptDocumentPayloads")
    void corruptDocument_throwsCorruptAuditLogEntryException(String caseName, org.bson.Document payload) {
        String auditId = UUID.randomUUID().toString();
        payload.append("_id", auditId);
        payload.putIfAbsent("occurredAt", java.util.Date.from(Instant.now()));
        payload.putIfAbsent("action", "ACCOUNT_DEACTIVATED");
        payload.putIfAbsent("changeSummary", Map.of());

        mongoTemplate.insert(payload, "identity_audit_log");

        identity.infrastructure.persistence.mongo.documents.AuditLogEntryDocument doc = springDataAuditLogRepository.findById(auditId).orElseThrow();

        identity.domain.exception.CorruptAuditLogEntryException ex = assertThrows(
                identity.domain.exception.CorruptAuditLogEntryException.class,
                () -> identity.infrastructure.persistence.mongo.mappers.AuditLogEntryMapper.toDomain(doc),
                "Corrupt document [" + caseName + "] must throw CorruptAuditLogEntryException"
        );

        assertTrue(ex.getMessage().contains(auditId), "Exception message must include auditId");
    }

    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> corruptDocumentPayloads() {
        return java.util.stream.Stream.of(
                org.junit.jupiter.params.provider.Arguments.of("no actor nor actorAccountId", new org.bson.Document()),
                org.junit.jupiter.params.provider.Arguments.of("both actor and actorAccountId", new org.bson.Document()
                        .append("actorAccountId", AccountId.generate().value())
                        .append("actor", new org.bson.Document("type", "ACCOUNT").append("accountId", AccountId.generate().value()))),
                org.junit.jupiter.params.provider.Arguments.of("actor empty object and actorAccountId present", new org.bson.Document()
                        .append("actorAccountId", AccountId.generate().value())
                        .append("actor", new org.bson.Document())),
                org.junit.jupiter.params.provider.Arguments.of("actor empty object only", new org.bson.Document()
                        .append("actor", new org.bson.Document())),
                org.junit.jupiter.params.provider.Arguments.of("unknown actor type", new org.bson.Document()
                        .append("actor", new org.bson.Document("type", "UNKNOWN_TYPE"))),
                org.junit.jupiter.params.provider.Arguments.of("ACCOUNT without accountId", new org.bson.Document()
                        .append("actor", new org.bson.Document("type", "ACCOUNT"))),
                org.junit.jupiter.params.provider.Arguments.of("SYSTEM without processId", new org.bson.Document()
                        .append("actor", new org.bson.Document("type", "SYSTEM")))
        );
    }

    @Test
    void record_legacyEntry_throwsLegacyAuditLogEntryWriteException_andLeavesNoDocument() {
        String auditId = "legacy-write-test-" + UUID.randomUUID();
        AuditLogEntry legacyEntry = AuditLogEntry.legacy(
                auditId,
                Instant.now().truncatedTo(ChronoUnit.MILLIS),
                AccountId.generate(),
                AccountId.generate(),
                null,
                AuditAction.ACCOUNT_DEACTIVATED,
                Map.of()
        );

        assertThrows(identity.domain.exception.LegacyAuditLogEntryWriteException.class,
                () -> auditLogRepository.record(legacyEntry));

        org.bson.Document rawDoc = mongoTemplate.findOne(
                new org.springframework.data.mongodb.core.query.Query(
                        org.springframework.data.mongodb.core.query.Criteria.where("_id").is(auditId)),
                org.bson.Document.class,
                "identity_audit_log"
        );
        assertNull(rawDoc, "No document must be persisted in identity_audit_log when writing legacy entry");
    }
}
