package identity.application.service;

import com.traceability.contracts.authorization.AuthorizationPrincipal;
import identity.application.authorization.AuthorizationAuditActorMapper;
import identity.application.authorization.PlatformAuthorizationPolicy;
import identity.application.port.out.AccountRepositoryPort;
import identity.application.port.out.AuditLogPort;
import identity.application.port.out.PlatformAuthorityStatePort;
import identity.domain.exception.AccountNotFoundException;
import identity.domain.exception.InactiveAccountException;
import identity.domain.exception.InsufficientPlatformAuthorityException;
import identity.domain.exception.LastPlatformAdministratorException;
import identity.domain.exception.PlatformAdministratorDeactivationException;
import identity.domain.exception.PlatformAuthorityAlreadyGrantedException;
import identity.domain.exception.PlatformAuthorityInvariantViolationException;
import identity.domain.exception.PlatformAuthorityNotHeldException;
import identity.domain.exception.PlatformAuthorityStateMissingException;
import identity.domain.model.Account;
import identity.domain.model.AccountId;
import identity.domain.model.AccountStatus;
import identity.domain.model.AuditAction;
import identity.domain.model.AuditActor;
import identity.domain.model.Email;
import identity.domain.model.PasswordHash;
import identity.domain.model.PlatformAuthority;
import identity.infrastructure.persistence.mongo.BaseMongoIntegrationTest;
import identity.infrastructure.persistence.mongo.documents.AccountDocument;
import identity.infrastructure.persistence.mongo.documents.AuditLogEntryDocument;
import identity.infrastructure.persistence.mongo.documents.PlatformAuthorityStateDocument;
import identity.infrastructure.persistence.mongo.repositories.MongoAccountRepositoryAdapter;
import identity.infrastructure.persistence.mongo.repositories.MongoAuditLogAdapter;
import identity.infrastructure.persistence.mongo.repositories.MongoPlatformAuthorityStateAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

@DataMongoTest
@ContextConfiguration(classes = identity.infrastructure.persistence.mongo.IdentityTestApplication.class)
@Import({
        MongoAccountRepositoryAdapter.class,
        MongoPlatformAuthorityStateAdapter.class,
        MongoAuditLogAdapter.class,
        MongoTransactionRetryHelper.class,
        PlatformAuthorizationPolicy.class,
        AuthorizationAuditActorMapper.class,
        GrantPlatformAuthorityService.class,
        RevokePlatformAuthorityService.class,
        DeactivateAccountService.class
})
@org.springframework.test.annotation.DirtiesContext
class PlatformAuthorityApplicationServiceIntegrationTest extends BaseMongoIntegrationTest {

    @MockitoSpyBean
    private AccountRepositoryPort accountRepository;

    @MockitoSpyBean
    private PlatformAuthorityStatePort platformAuthorityStatePort;

    @Autowired
    private MongoTransactionRetryHelper retryHelper;

    @Autowired
    private GrantPlatformAuthorityService grantPlatformAuthorityService;

    @Autowired
    private RevokePlatformAuthorityService revokePlatformAuthorityService;

    @Autowired
    private DeactivateAccountService deactivateAccountService;

    @Autowired
    private MongoTemplate mongoTemplate;

    @BeforeEach
    void setUp() {
        reset(accountRepository, platformAuthorityStatePort);
        mongoTemplate.dropCollection("accounts");
        mongoTemplate.dropCollection("identity_audit_log");
        mongoTemplate.dropCollection("platform_authority_state");
    }

    private void seedPlatformAuthorityState(long count, long version) {
        PlatformAuthorityStateDocument doc = new PlatformAuthorityStateDocument(
                MongoPlatformAuthorityStateAdapter.SINGLETON_ID,
                count,
                version
        );
        mongoTemplate.save(doc, MongoPlatformAuthorityStateAdapter.COLLECTION_NAME);
    }

    private Account seedAccount(AccountId accountId, String email, AccountStatus status, PlatformAuthority authority) {
        Account account = Account.reconstitute(
                accountId,
                new Email(email),
                new PasswordHash("hashed-password"),
                status,
                null,
                authority
        );
        accountRepository.save(account);
        return account;
    }

    private AuthorizationPrincipal createPrincipal(AccountId accountId, PlatformAuthority authority) {
        return new AuthorizationPrincipal(
                accountId.value(),
                null,
                Set.of(),
                authority != null ? com.traceability.contracts.authorization.PlatformAuthority.ADMINISTRATOR : null
        );
    }

    private PlatformAuthorityStateDocument getPlatformAuthorityState() {
        return mongoTemplate.findById(
                MongoPlatformAuthorityStateAdapter.SINGLETON_ID,
                PlatformAuthorityStateDocument.class,
                MongoPlatformAuthorityStateAdapter.COLLECTION_NAME
        );
    }

    private long getAuditLogCount() {
        return mongoTemplate.count(new Query(), "identity_audit_log");
    }

    /**
     * Asserts the 5 mandatory negative invariants for error tests:
     * 1. Target account intact
     * 2. activeAdministratorCount intact
     * 3. version intact
     * 4. Zero new audit entries
     * 5. retryCount unchanged
     */
    private void assertErrorInvariants(
            AccountId targetAccountId,
            Account expectedTargetAccount,
            Long expectedAdminCount,
            Long expectedVersion,
            long expectedAuditCount,
            int expectedRetryCount
    ) {
        // 1. Target account intact
        AccountDocument actualTargetDoc = mongoTemplate.findById(
                targetAccountId.value(),
                AccountDocument.class,
                "accounts"
        );
        if (expectedTargetAccount == null) {
            assertNull(actualTargetDoc, "Account should not exist in MongoDB");
        } else {
            assertNotNull(actualTargetDoc, "Account should exist in MongoDB");
            assertEquals(expectedTargetAccount.getStatus().name(), actualTargetDoc.getStatus(), "Account status must be intact");
            String expectedAuth = expectedTargetAccount.getPlatformAuthority() != null ? expectedTargetAccount.getPlatformAuthority().name() : null;
            assertEquals(expectedAuth, actualTargetDoc.getPlatformAuthority(), "Account authority must be intact");
            assertEquals(expectedTargetAccount.getEmail().value(), actualTargetDoc.getEmail(), "Account email must be intact");
            String expectedOrg = expectedTargetAccount.getOrganizationId() != null ? expectedTargetAccount.getOrganizationId().value() : null;
            assertEquals(expectedOrg, actualTargetDoc.getOrganizationId(), "Account org must be intact");
        }

        // 2 & 3. activeAdministratorCount and version intact
        PlatformAuthorityStateDocument state = getPlatformAuthorityState();
        if (expectedAdminCount == null) {
            assertNull(state, "Platform authority state should not exist");
        } else {
            assertNotNull(state, "Platform authority state should exist");
            assertEquals(expectedAdminCount.longValue(), state.getActiveAdministratorCount(), "activeAdministratorCount must be intact");
            assertEquals(expectedVersion.longValue(), state.getVersion(), "version must be intact");
        }

        // 4. Zero new audit entries
        assertEquals(expectedAuditCount, getAuditLogCount(), "Audit log entry count must be unchanged");

        // 5. retryCount unchanged
        assertEquals(expectedRetryCount, retryHelper.getRetryCount(), "retryCount must remain unchanged");
    }

    // =========================================================================
    // 4.2 Integración — Caminos felices
    // =========================================================================

    @Test
    void grantPlatformAuthority_success() {
        AccountId callerId = AccountId.generate();
        seedAccount(callerId, "caller@example.com", AccountStatus.ACTIVE, PlatformAuthority.ADMINISTRATOR);
        AuthorizationPrincipal callerPrincipal = createPrincipal(callerId, PlatformAuthority.ADMINISTRATOR);

        AccountId targetId = AccountId.generate();
        seedAccount(targetId, "target@example.com", AccountStatus.ACTIVE, null);

        seedPlatformAuthorityState(1, 1);

        grantPlatformAuthorityService.grantPlatformAuthority(callerPrincipal, targetId);

        // State updated (+1, +1)
        PlatformAuthorityStateDocument state = getPlatformAuthorityState();
        assertNotNull(state);
        assertEquals(2, state.getActiveAdministratorCount());
        assertEquals(2, state.getVersion());

        // Account updated
        Account targetInDb = accountRepository.findById(targetId);
        assertEquals(PlatformAuthority.ADMINISTRATOR, targetInDb.getPlatformAuthority());

        // Audit log recorded with caller as actor, NOT target
        List<AuditLogEntryDocument> auditLogs = mongoTemplate.findAll(AuditLogEntryDocument.class, "identity_audit_log");
        assertEquals(1, auditLogs.size());
        AuditLogEntryDocument entry = auditLogs.get(0);
        assertEquals(AuditAction.PLATFORM_AUTHORITY_GRANTED, entry.getAction());
        assertEquals(targetId.value(), entry.getTargetAccountId());
        assertNotNull(entry.getActor(), "Actor subdocument must not be null");
        assertEquals("ACCOUNT", entry.getActor().getType());
        assertEquals(callerId.value(), entry.getActor().getAccountId(), "Actor must be caller principal account");
        assertNotEquals(targetId.value(), entry.getActor().getAccountId(), "Actor must NOT be target account");
        assertNull(entry.getActorAccountId(), "Legacy actorAccountId must be null for post-cutover entries");
    }

    @Test
    void revokePlatformAuthority_success() {
        AccountId callerId = AccountId.generate();
        seedAccount(callerId, "caller@example.com", AccountStatus.ACTIVE, PlatformAuthority.ADMINISTRATOR);
        AuthorizationPrincipal callerPrincipal = createPrincipal(callerId, PlatformAuthority.ADMINISTRATOR);

        AccountId targetId = AccountId.generate();
        seedAccount(targetId, "target@example.com", AccountStatus.ACTIVE, PlatformAuthority.ADMINISTRATOR);

        seedPlatformAuthorityState(2, 1);

        revokePlatformAuthorityService.revokePlatformAuthority(callerPrincipal, targetId);

        // State updated (-1, +1)
        PlatformAuthorityStateDocument state = getPlatformAuthorityState();
        assertNotNull(state);
        assertEquals(1, state.getActiveAdministratorCount());
        assertEquals(2, state.getVersion());

        // Account updated
        Account targetInDb = accountRepository.findById(targetId);
        assertNull(targetInDb.getPlatformAuthority());

        // Audit log recorded
        List<AuditLogEntryDocument> auditLogs = mongoTemplate.findAll(AuditLogEntryDocument.class, "identity_audit_log");
        assertEquals(1, auditLogs.size());
        AuditLogEntryDocument entry = auditLogs.get(0);
        assertEquals(AuditAction.PLATFORM_AUTHORITY_REVOKED, entry.getAction());
        assertEquals(targetId.value(), entry.getTargetAccountId());
        assertNotNull(entry.getActor(), "Actor subdocument must not be null");
        assertEquals("ACCOUNT", entry.getActor().getType());
        assertEquals(callerId.value(), entry.getActor().getAccountId());
        assertNotEquals(targetId.value(), entry.getActor().getAccountId());
        assertNull(entry.getActorAccountId(), "Legacy actorAccountId must be null for post-cutover entries");
    }

    @Test
    void revokePlatformAuthority_selfRevocation_whenAnotherAdminExists_succeeds() {
        AccountId callerId = AccountId.generate();
        seedAccount(callerId, "caller@example.com", AccountStatus.ACTIVE, PlatformAuthority.ADMINISTRATOR);
        AuthorizationPrincipal callerPrincipal = createPrincipal(callerId, PlatformAuthority.ADMINISTRATOR);

        AccountId anotherAdminId = AccountId.generate();
        seedAccount(anotherAdminId, "other@example.com", AccountStatus.ACTIVE, PlatformAuthority.ADMINISTRATOR);

        seedPlatformAuthorityState(2, 5);

        // Self-revocation
        revokePlatformAuthorityService.revokePlatformAuthority(callerPrincipal, callerId);

        PlatformAuthorityStateDocument state = getPlatformAuthorityState();
        assertNotNull(state);
        assertEquals(1, state.getActiveAdministratorCount());
        assertEquals(6, state.getVersion());

        Account callerInDb = accountRepository.findById(callerId);
        assertNull(callerInDb.getPlatformAuthority());

        Account otherInDb = accountRepository.findById(anotherAdminId);
        assertEquals(PlatformAuthority.ADMINISTRATOR, otherInDb.getPlatformAuthority());
    }

    // =========================================================================
    // 4.3 Integración — Ramas de excepción del ADR
    // =========================================================================

    @Test
    void grantPlatformAuthority_missingState_throwsStateMissingException_andAccountIntact() {
        AccountId callerId = AccountId.generate();
        seedAccount(callerId, "caller@example.com", AccountStatus.ACTIVE, PlatformAuthority.ADMINISTRATOR);
        AuthorizationPrincipal callerPrincipal = createPrincipal(callerId, PlatformAuthority.ADMINISTRATOR);

        AccountId targetId = AccountId.generate();
        Account target = seedAccount(targetId, "target@example.com", AccountStatus.ACTIVE, null);

        int initialRetry = retryHelper.getRetryCount();
        long initialAudit = getAuditLogCount();

        assertThrows(PlatformAuthorityStateMissingException.class,
                () -> grantPlatformAuthorityService.grantPlatformAuthority(callerPrincipal, targetId));

        assertErrorInvariants(targetId, target, null, null, initialAudit, initialRetry);
    }

    @Test
    void grantPlatformAuthority_targetNotFound_throwsAccountNotFoundException_andStateIntact() {
        AccountId callerId = AccountId.generate();
        seedAccount(callerId, "caller@example.com", AccountStatus.ACTIVE, PlatformAuthority.ADMINISTRATOR);
        AuthorizationPrincipal callerPrincipal = createPrincipal(callerId, PlatformAuthority.ADMINISTRATOR);

        AccountId nonExistentTargetId = AccountId.generate();
        seedPlatformAuthorityState(1, 1);

        int initialRetry = retryHelper.getRetryCount();
        long initialAudit = getAuditLogCount();

        assertThrows(AccountNotFoundException.class,
                () -> grantPlatformAuthorityService.grantPlatformAuthority(callerPrincipal, nonExistentTargetId));

        assertErrorInvariants(nonExistentTargetId, null, 1L, 1L, initialAudit, initialRetry);
    }

    @Test
    void grantPlatformAuthority_targetInactive_throwsInactiveAccountException_andStateIntact() {
        AccountId callerId = AccountId.generate();
        seedAccount(callerId, "caller@example.com", AccountStatus.ACTIVE, PlatformAuthority.ADMINISTRATOR);
        AuthorizationPrincipal callerPrincipal = createPrincipal(callerId, PlatformAuthority.ADMINISTRATOR);

        AccountId targetId = AccountId.generate();
        Account target = seedAccount(targetId, "target@example.com", AccountStatus.INACTIVE, null);
        seedPlatformAuthorityState(1, 1);

        int initialRetry = retryHelper.getRetryCount();
        long initialAudit = getAuditLogCount();

        assertThrows(InactiveAccountException.class,
                () -> grantPlatformAuthorityService.grantPlatformAuthority(callerPrincipal, targetId));

        assertErrorInvariants(targetId, target, 1L, 1L, initialAudit, initialRetry);
    }

    @Test
    void grantPlatformAuthority_alreadyGranted_throwsAlreadyGrantedException_andStateIntact() {
        AccountId callerId = AccountId.generate();
        seedAccount(callerId, "caller@example.com", AccountStatus.ACTIVE, PlatformAuthority.ADMINISTRATOR);
        AuthorizationPrincipal callerPrincipal = createPrincipal(callerId, PlatformAuthority.ADMINISTRATOR);

        AccountId targetId = AccountId.generate();
        Account target = seedAccount(targetId, "target@example.com", AccountStatus.ACTIVE, PlatformAuthority.ADMINISTRATOR);
        seedPlatformAuthorityState(2, 1);

        int initialRetry = retryHelper.getRetryCount();
        long initialAudit = getAuditLogCount();

        assertThrows(PlatformAuthorityAlreadyGrantedException.class,
                () -> grantPlatformAuthorityService.grantPlatformAuthority(callerPrincipal, targetId));

        assertErrorInvariants(targetId, target, 2L, 1L, initialAudit, initialRetry);
    }

    @Test
    void revokePlatformAuthority_missingState_throwsStateMissingException_andAccountIntact() {
        AccountId callerId = AccountId.generate();
        seedAccount(callerId, "caller@example.com", AccountStatus.ACTIVE, PlatformAuthority.ADMINISTRATOR);
        AuthorizationPrincipal callerPrincipal = createPrincipal(callerId, PlatformAuthority.ADMINISTRATOR);

        AccountId targetId = AccountId.generate();
        Account target = seedAccount(targetId, "target@example.com", AccountStatus.ACTIVE, PlatformAuthority.ADMINISTRATOR);

        int initialRetry = retryHelper.getRetryCount();
        long initialAudit = getAuditLogCount();

        assertThrows(PlatformAuthorityStateMissingException.class,
                () -> revokePlatformAuthorityService.revokePlatformAuthority(callerPrincipal, targetId));

        assertErrorInvariants(targetId, target, null, null, initialAudit, initialRetry);
    }

    @Test
    void revokePlatformAuthority_targetNotFound_throwsAccountNotFoundException_andStateIntact() {
        AccountId callerId = AccountId.generate();
        seedAccount(callerId, "caller@example.com", AccountStatus.ACTIVE, PlatformAuthority.ADMINISTRATOR);
        AuthorizationPrincipal callerPrincipal = createPrincipal(callerId, PlatformAuthority.ADMINISTRATOR);

        AccountId nonExistentTargetId = AccountId.generate();
        seedPlatformAuthorityState(2, 1);

        int initialRetry = retryHelper.getRetryCount();
        long initialAudit = getAuditLogCount();

        assertThrows(AccountNotFoundException.class,
                () -> revokePlatformAuthorityService.revokePlatformAuthority(callerPrincipal, nonExistentTargetId));

        assertErrorInvariants(nonExistentTargetId, null, 2L, 1L, initialAudit, initialRetry);
    }

    @Test
    void revokePlatformAuthority_targetNotHeld_throwsNotHeldException_andStateIntact() {
        AccountId callerId = AccountId.generate();
        seedAccount(callerId, "caller@example.com", AccountStatus.ACTIVE, PlatformAuthority.ADMINISTRATOR);
        AuthorizationPrincipal callerPrincipal = createPrincipal(callerId, PlatformAuthority.ADMINISTRATOR);

        AccountId targetId = AccountId.generate();
        Account target = seedAccount(targetId, "target@example.com", AccountStatus.ACTIVE, null);
        seedPlatformAuthorityState(1, 1);

        int initialRetry = retryHelper.getRetryCount();
        long initialAudit = getAuditLogCount();

        assertThrows(PlatformAuthorityNotHeldException.class,
                () -> revokePlatformAuthorityService.revokePlatformAuthority(callerPrincipal, targetId));

        assertErrorInvariants(targetId, target, 1L, 1L, initialAudit, initialRetry);
    }

    @Test
    void revokePlatformAuthority_lastAdmin_throwsLastPlatformAdministratorException_andStateIntact() {
        AccountId callerId = AccountId.generate();
        Account caller = seedAccount(callerId, "caller@example.com", AccountStatus.ACTIVE, PlatformAuthority.ADMINISTRATOR);
        AuthorizationPrincipal callerPrincipal = createPrincipal(callerId, PlatformAuthority.ADMINISTRATOR);

        seedPlatformAuthorityState(1, 1);

        int initialRetry = retryHelper.getRetryCount();
        long initialAudit = getAuditLogCount();

        assertThrows(LastPlatformAdministratorException.class,
                () -> revokePlatformAuthorityService.revokePlatformAuthority(callerPrincipal, callerId));

        assertErrorInvariants(callerId, caller, 1L, 1L, initialAudit, initialRetry);
    }

    // =========================================================================
    // 4.4 Integración — Evaluación de política antes de lecturas
    // =========================================================================

    @Test
    void grantPlatformAuthority_unauthorizedPrincipal_throwsBeforeAnyRead() {
        AccountId nonAdminPrincipalId = AccountId.generate();
        AuthorizationPrincipal nonAdminPrincipal = createPrincipal(nonAdminPrincipalId, null);

        AccountId targetId = AccountId.generate();
        Account target = seedAccount(targetId, "target@example.com", AccountStatus.ACTIVE, null);
        seedPlatformAuthorityState(1, 1);

        int initialRetry = retryHelper.getRetryCount();
        long initialAudit = getAuditLogCount();

        assertThrows(InsufficientPlatformAuthorityException.class,
                () -> grantPlatformAuthorityService.grantPlatformAuthority(nonAdminPrincipal, targetId));

        // Verify policy rejected before any database read
        verify(accountRepository, never()).findById(any());
        verify(platformAuthorityStatePort, never()).exists();

        // Invariants asserted
        assertErrorInvariants(targetId, target, 1L, 1L, initialAudit, initialRetry);
    }

    @Test
    void revokePlatformAuthority_unauthorizedPrincipal_throwsBeforeAnyRead() {
        AccountId nonAdminPrincipalId = AccountId.generate();
        AuthorizationPrincipal nonAdminPrincipal = createPrincipal(nonAdminPrincipalId, null);

        AccountId targetId = AccountId.generate();
        Account target = seedAccount(targetId, "target@example.com", AccountStatus.ACTIVE, PlatformAuthority.ADMINISTRATOR);
        seedPlatformAuthorityState(2, 1);

        int initialRetry = retryHelper.getRetryCount();
        long initialAudit = getAuditLogCount();

        assertThrows(InsufficientPlatformAuthorityException.class,
                () -> revokePlatformAuthorityService.revokePlatformAuthority(nonAdminPrincipal, targetId));

        // Verify policy rejected before any database read
        verify(accountRepository, never()).findById(any());
        verify(platformAuthorityStatePort, never()).exists();

        // Invariants asserted
        assertErrorInvariants(targetId, target, 2L, 1L, initialAudit, initialRetry);
    }

    // =========================================================================
    // 4.5 Integración — Revalidación D6e dentro de la transacción
    // =========================================================================

    @Test
    void grantPlatformAuthority_principalActorInactiveInDb_throwsInsufficientAuthority() {
        AccountId callerId = AccountId.generate();
        // DB says INACTIVE even though token says ADMINISTRATOR
        seedAccount(callerId, "caller@example.com", AccountStatus.INACTIVE, PlatformAuthority.ADMINISTRATOR);
        AuthorizationPrincipal callerPrincipal = createPrincipal(callerId, PlatformAuthority.ADMINISTRATOR);

        AccountId targetId = AccountId.generate();
        Account target = seedAccount(targetId, "target@example.com", AccountStatus.ACTIVE, null);
        seedPlatformAuthorityState(1, 1);

        int initialRetry = retryHelper.getRetryCount();
        long initialAudit = getAuditLogCount();

        assertThrows(InsufficientPlatformAuthorityException.class,
                () -> grantPlatformAuthorityService.grantPlatformAuthority(callerPrincipal, targetId));

        assertErrorInvariants(targetId, target, 1L, 1L, initialAudit, initialRetry);
    }

    @Test
    void grantPlatformAuthority_principalActorLacksAuthorityInDb_throwsInsufficientAuthority() {
        AccountId callerId = AccountId.generate();
        // DB says authority null even though token says ADMINISTRATOR
        seedAccount(callerId, "caller@example.com", AccountStatus.ACTIVE, null);
        AuthorizationPrincipal callerPrincipal = createPrincipal(callerId, PlatformAuthority.ADMINISTRATOR);

        AccountId targetId = AccountId.generate();
        Account target = seedAccount(targetId, "target@example.com", AccountStatus.ACTIVE, null);
        seedPlatformAuthorityState(1, 1);

        int initialRetry = retryHelper.getRetryCount();
        long initialAudit = getAuditLogCount();

        assertThrows(InsufficientPlatformAuthorityException.class,
                () -> grantPlatformAuthorityService.grantPlatformAuthority(callerPrincipal, targetId));

        assertErrorInvariants(targetId, target, 1L, 1L, initialAudit, initialRetry);
    }

    @Test
    void grantPlatformAuthority_principalActorNotFoundInDb_throwsInsufficientAuthority() {
        AccountId nonExistentCallerId = AccountId.generate();
        // DB does not have caller account
        AuthorizationPrincipal callerPrincipal = createPrincipal(nonExistentCallerId, PlatformAuthority.ADMINISTRATOR);

        AccountId targetId = AccountId.generate();
        Account target = seedAccount(targetId, "target@example.com", AccountStatus.ACTIVE, null);
        seedPlatformAuthorityState(1, 1);

        int initialRetry = retryHelper.getRetryCount();
        long initialAudit = getAuditLogCount();

        assertThrows(InsufficientPlatformAuthorityException.class,
                () -> grantPlatformAuthorityService.grantPlatformAuthority(callerPrincipal, targetId));

        assertErrorInvariants(targetId, target, 1L, 1L, initialAudit, initialRetry);
    }

    @Test
    void revokePlatformAuthority_principalActorInactiveInDb_throwsInsufficientAuthority() {
        AccountId callerId = AccountId.generate();
        seedAccount(callerId, "caller@example.com", AccountStatus.INACTIVE, PlatformAuthority.ADMINISTRATOR);
        AuthorizationPrincipal callerPrincipal = createPrincipal(callerId, PlatformAuthority.ADMINISTRATOR);

        AccountId targetId = AccountId.generate();
        Account target = seedAccount(targetId, "target@example.com", AccountStatus.ACTIVE, PlatformAuthority.ADMINISTRATOR);
        seedPlatformAuthorityState(2, 1);

        int initialRetry = retryHelper.getRetryCount();
        long initialAudit = getAuditLogCount();

        assertThrows(InsufficientPlatformAuthorityException.class,
                () -> revokePlatformAuthorityService.revokePlatformAuthority(callerPrincipal, targetId));

        assertErrorInvariants(targetId, target, 2L, 1L, initialAudit, initialRetry);
    }

    @Test
    void revokePlatformAuthority_principalActorLacksAuthorityInDb_throwsInsufficientAuthority() {
        AccountId callerId = AccountId.generate();
        seedAccount(callerId, "caller@example.com", AccountStatus.ACTIVE, null);
        AuthorizationPrincipal callerPrincipal = createPrincipal(callerId, PlatformAuthority.ADMINISTRATOR);

        AccountId targetId = AccountId.generate();
        Account target = seedAccount(targetId, "target@example.com", AccountStatus.ACTIVE, PlatformAuthority.ADMINISTRATOR);
        seedPlatformAuthorityState(2, 1);

        int initialRetry = retryHelper.getRetryCount();
        long initialAudit = getAuditLogCount();

        assertThrows(InsufficientPlatformAuthorityException.class,
                () -> revokePlatformAuthorityService.revokePlatformAuthority(callerPrincipal, targetId));

        assertErrorInvariants(targetId, target, 2L, 1L, initialAudit, initialRetry);
    }

    @Test
    void revokePlatformAuthority_principalActorNotFoundInDb_throwsInsufficientAuthority() {
        AccountId nonExistentCallerId = AccountId.generate();
        AuthorizationPrincipal callerPrincipal = createPrincipal(nonExistentCallerId, PlatformAuthority.ADMINISTRATOR);

        AccountId targetId = AccountId.generate();
        Account target = seedAccount(targetId, "target@example.com", AccountStatus.ACTIVE, PlatformAuthority.ADMINISTRATOR);
        seedPlatformAuthorityState(2, 1);

        int initialRetry = retryHelper.getRetryCount();
        long initialAudit = getAuditLogCount();

        assertThrows(InsufficientPlatformAuthorityException.class,
                () -> revokePlatformAuthorityService.revokePlatformAuthority(callerPrincipal, targetId));

        assertErrorInvariants(targetId, target, 2L, 1L, initialAudit, initialRetry);
    }

    // =========================================================================
    // 4.6 Integración — Enmienda ADR-026 con contador singleton
    // =========================================================================

    @Test
    void deactivateAccount_onPlatformAdministrator_preservesCounter_andProducesNoAudit() {
        AccountId adminId = AccountId.generate();
        Account admin = seedAccount(adminId, "admin@example.com", AccountStatus.ACTIVE, PlatformAuthority.ADMINISTRATOR);
        seedPlatformAuthorityState(1, 1);

        int initialRetry = retryHelper.getRetryCount();
        long initialAudit = getAuditLogCount();

        AuditActor actor = new AuditActor.AccountAuditActor(adminId);

        assertThrows(PlatformAdministratorDeactivationException.class,
                () -> deactivateAccountService.deactivateAccount(actor, adminId));

        assertErrorInvariants(adminId, admin, 1L, 1L, initialAudit, initialRetry);
    }

    @Test
    void revokeThenDeactivateAccount_succeeds_andDecrementsCounterOnce() {
        AccountId callerId = AccountId.generate();
        seedAccount(callerId, "caller@example.com", AccountStatus.ACTIVE, PlatformAuthority.ADMINISTRATOR);
        AuthorizationPrincipal callerPrincipal = createPrincipal(callerId, PlatformAuthority.ADMINISTRATOR);

        AccountId targetId = AccountId.generate();
        seedAccount(targetId, "target@example.com", AccountStatus.ACTIVE, PlatformAuthority.ADMINISTRATOR);

        seedPlatformAuthorityState(2, 1);

        // 1. Revoke platform authority
        revokePlatformAuthorityService.revokePlatformAuthority(callerPrincipal, targetId);

        PlatformAuthorityStateDocument afterRevoke = getPlatformAuthorityState();
        assertNotNull(afterRevoke);
        assertEquals(1, afterRevoke.getActiveAdministratorCount());
        assertEquals(2, afterRevoke.getVersion());

        Account targetAfterRevoke = accountRepository.findById(targetId);
        assertNull(targetAfterRevoke.getPlatformAuthority());
        assertEquals(AccountStatus.ACTIVE, targetAfterRevoke.getStatus());

        // 2. Deactivate the account (now that it is not platform administrator)
        AuditActor actor = new AuditActor.AccountAuditActor(callerId);
        deactivateAccountService.deactivateAccount(actor, targetId);

        Account targetAfterDeactivate = accountRepository.findById(targetId);
        assertEquals(AccountStatus.INACTIVE, targetAfterDeactivate.getStatus());

        // Counter decremented exactly once by revoke, untouched by deactivate
        PlatformAuthorityStateDocument afterDeactivate = getPlatformAuthorityState();
        assertNotNull(afterDeactivate);
        assertEquals(1, afterDeactivate.getActiveAdministratorCount());
        assertEquals(2, afterDeactivate.getVersion());
    }

    // =========================================================================
    // Añadido (2) del usuario: Fallo de escritura condicional con rollback
    // =========================================================================

    @Test
    void grantPlatformAuthority_conditionalWriteMatchesNothing_throwsInvariantViolation_andRollsBackEverything() {
        AccountId callerId = AccountId.generate();
        seedAccount(callerId, "caller@example.com", AccountStatus.ACTIVE, PlatformAuthority.ADMINISTRATOR);
        AuthorizationPrincipal callerPrincipal = createPrincipal(callerId, PlatformAuthority.ADMINISTRATOR);

        AccountId targetId = AccountId.generate();
        Account target = seedAccount(targetId, "target@example.com", AccountStatus.ACTIVE, null);

        seedPlatformAuthorityState(1, 1);

        // Force conditional write to simulate 0 matched documents
        doReturn(false).when(accountRepository).grantPlatformAuthorityIfAbsent(targetId);

        int initialRetry = retryHelper.getRetryCount();
        long initialAudit = getAuditLogCount();

        assertThrows(PlatformAuthorityInvariantViolationException.class,
                () -> grantPlatformAuthorityService.grantPlatformAuthority(callerPrincipal, targetId));

        // 5 Invariants affirmed
        assertErrorInvariants(targetId, target, 1L, 1L, initialAudit, initialRetry);

        // Explicitly assert account in DB has no authority
        Account targetInDb = accountRepository.findById(targetId);
        assertNull(targetInDb.getPlatformAuthority(), "Account must remain without authority");
    }
}
