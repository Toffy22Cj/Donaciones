package identity.application.service;

import com.traceability.contracts.authorization.AuthorizationPrincipal;
import identity.application.authorization.AuthorizationAuditActorMapper;
import identity.application.authorization.PlatformAuthorizationPolicy;
import identity.application.port.out.AccountRepositoryPort;
import identity.application.port.out.AuditLogPort;
import identity.application.port.out.PlatformAuthorityStatePort;
import identity.domain.exception.BootstrapTargetAccountNotFoundException;
import identity.domain.exception.InactiveAccountException;
import identity.domain.exception.InvalidEmailFormatException;
import identity.domain.exception.PlatformAlreadyBootstrappedException;
import identity.domain.exception.PlatformAuthorityInconsistentStateException;
import identity.domain.exception.PlatformAuthorityInvariantViolationException;
import identity.domain.model.Account;
import identity.domain.model.AccountId;
import identity.domain.model.AccountStatus;
import identity.domain.model.AuditAction;
import identity.domain.model.AuditActor;
import identity.domain.model.AuditLogEntry;
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
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DataMongoTest
@ContextConfiguration(classes = identity.infrastructure.persistence.mongo.IdentityTestApplication.class)
@Import({
        MongoAccountRepositoryAdapter.class,
        MongoPlatformAuthorityStateAdapter.class,
        MongoAuditLogAdapter.class,
        MongoTransactionRetryHelper.class,
        BootstrapPlatformAuthorityService.class,
        GrantPlatformAuthorityService.class,
        PlatformAuthorizationPolicy.class,
        AuthorizationAuditActorMapper.class
})
@DirtiesContext
class BootstrapPlatformAuthorityServiceIntegrationTest extends BaseMongoIntegrationTest {

    @MockitoSpyBean
    private AccountRepositoryPort accountRepository;

    @MockitoSpyBean
    private PlatformAuthorityStatePort platformAuthorityStatePort;

    @MockitoSpyBean
    private AuditLogPort auditLogPort;

    @MockitoSpyBean
    private GrantPlatformAuthorityService grantPlatformAuthorityService;

    @Autowired
    private MongoTransactionRetryHelper retryHelper;

    @Autowired
    private BootstrapPlatformAuthorityService bootstrapService;

    @Autowired
    private MongoTemplate mongoTemplate;

    @BeforeEach
    void setUp() {
        reset(accountRepository, platformAuthorityStatePort, auditLogPort, grantPlatformAuthorityService);
        mongoTemplate.dropCollection("accounts");
        mongoTemplate.dropCollection("identity_audit_log");
        mongoTemplate.dropCollection("platform_authority_state");
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

    private PlatformAuthorityStateDocument getPlatformAuthorityState() {
        return mongoTemplate.findById(
                MongoPlatformAuthorityStateAdapter.SINGLETON_ID,
                PlatformAuthorityStateDocument.class,
                MongoPlatformAuthorityStateAdapter.COLLECTION_NAME
        );
    }

    private long getAuditLogCount() {
        return mongoTemplate.count(new org.springframework.data.mongodb.core.query.Query(), "identity_audit_log");
    }

    private void assertNegativeInvariants(
            AccountId targetAccountId,
            Account expectedTargetAccount,
            Long expectedAdminCount,
            Long expectedVersion,
            long expectedAuditCount,
            int expectedRetryCount
    ) {
        // 1. Account state
        if (targetAccountId != null) {
            AccountDocument doc = mongoTemplate.findById(targetAccountId.value(), AccountDocument.class, "accounts");
            if (expectedTargetAccount == null) {
                assertNull(doc, "Account should not exist in MongoDB");
            } else {
                assertNotNull(doc, "Account should exist in MongoDB");
                assertEquals(expectedTargetAccount.getStatus().name(), doc.getStatus());
                String expectedAuth = expectedTargetAccount.getPlatformAuthority() != null ? expectedTargetAccount.getPlatformAuthority().name() : null;
                assertEquals(expectedAuth, doc.getPlatformAuthority());
            }
        }

        // 2 & 3. PlatformAuthorityState
        PlatformAuthorityStateDocument state = getPlatformAuthorityState();
        if (expectedAdminCount == null) {
            assertNull(state, "Platform authority state should not exist");
        } else {
            assertNotNull(state, "Platform authority state should exist");
            assertEquals(expectedAdminCount.longValue(), state.getActiveAdministratorCount());
            assertEquals(expectedVersion.longValue(), state.getVersion());
        }

        // 4. Zero new audit entries
        assertEquals(expectedAuditCount, getAuditLogCount(), "Audit count must remain unchanged");

        // 5. retryCount unchanged
        assertEquals(expectedRetryCount, retryHelper.getRetryCount(), "Retry count must remain unchanged");
    }

    // =========================================================================
    // Caso 1: Éxito
    // =========================================================================
    @Test
    void bootstrap_success() {
        AccountId targetId = AccountId.generate();
        seedAccount(targetId, "admin@example.com", AccountStatus.ACTIVE, null);

        int initialRetry = retryHelper.getRetryCount();

        AccountId resultId = bootstrapService.bootstrap("admin@example.com");

        assertEquals(targetId, resultId);

        // 1. Singleton with count=1, version=1
        PlatformAuthorityStateDocument state = getPlatformAuthorityState();
        assertNotNull(state);
        assertEquals(1L, state.getActiveAdministratorCount());
        assertEquals(1L, state.getVersion());

        // 2. Account is ADMINISTRATOR
        Account accountInDb = accountRepository.findById(targetId);
        assertEquals(PlatformAuthority.ADMINISTRATOR, accountInDb.getPlatformAuthority());

        // 3. Exactly 1 audit entry BOOTSTRAP_PLATFORM_AUTHORITY with SystemAuditActor("platform-bootstrap") and targetAccountId
        List<AuditLogEntryDocument> auditLogs = mongoTemplate.findAll(AuditLogEntryDocument.class, "identity_audit_log");
        assertEquals(1, auditLogs.size());
        AuditLogEntryDocument entry = auditLogs.get(0);
        assertEquals(AuditAction.BOOTSTRAP_PLATFORM_AUTHORITY, entry.getAction());
        assertEquals(targetId.value(), entry.getTargetAccountId());
        assertNotNull(entry.getActor());
        assertEquals("SYSTEM", entry.getActor().getType());
        assertEquals(BootstrapPlatformAuthorityService.PROCESS_ID, entry.getActor().getProcessId());
        assertNull(entry.getActor().getAccountId());

        // 4. Retry delta == 0
        assertEquals(0, retryHelper.getRetryCount() - initialRetry);

        // 5. GrantPlatformAuthorityService never invoked (ADR-038 §2.4)
        verify(grantPlatformAuthorityService, never()).grantPlatformAuthority(any(AuthorizationPrincipal.class), any(AccountId.class));
    }

    // =========================================================================
    // Caso 2: Segunda ejecución -> PlatformAlreadyBootstrappedException
    // =========================================================================
    @Test
    void bootstrap_alreadyBootstrapped_throwsPlatformAlreadyBootstrappedException() {
        AccountId targetId1 = AccountId.generate();
        seedAccount(targetId1, "admin1@example.com", AccountStatus.ACTIVE, null);
        bootstrapService.bootstrap("admin1@example.com");

        AccountId targetId2 = AccountId.generate();
        Account target2 = seedAccount(targetId2, "admin2@example.com", AccountStatus.ACTIVE, null);

        int initialRetry = retryHelper.getRetryCount();
        long initialAudit = getAuditLogCount();

        assertThrows(PlatformAlreadyBootstrappedException.class,
                () -> bootstrapService.bootstrap("admin2@example.com"));

        assertNegativeInvariants(targetId2, target2, 1L, 1L, initialAudit, initialRetry);
    }

    // =========================================================================
    // Caso 3: Otra cuenta ya administradora y sin singleton -> PlatformAuthorityInconsistentStateException
    // =========================================================================
    @Test
    void bootstrap_otherAccountAlreadyAdminWithoutSingleton_throwsInconsistentStateException() {
        AccountId existingAdminId = AccountId.generate();
        Account existingAdmin = seedAccount(existingAdminId, "existing@example.com", AccountStatus.ACTIVE, PlatformAuthority.ADMINISTRATOR);

        AccountId targetId = AccountId.generate();
        Account target = seedAccount(targetId, "target@example.com", AccountStatus.ACTIVE, null);

        int initialRetry = retryHelper.getRetryCount();
        long initialAudit = getAuditLogCount();

        assertThrows(PlatformAuthorityInconsistentStateException.class,
                () -> bootstrapService.bootstrap("target@example.com"));

        assertNegativeInvariants(targetId, target, null, null, initialAudit, initialRetry);
        // Existing admin remains untouched
        Account existingInDb = accountRepository.findById(existingAdminId);
        assertEquals(PlatformAuthority.ADMINISTRATOR, existingInDb.getPlatformAuthority());
    }

    // =========================================================================
    // Caso 4: Cuenta destino ya administradora y no hay singleton -> PlatformAuthorityInconsistentStateException
    // =========================================================================
    @Test
    void bootstrap_targetAccountAlreadyAdminWithoutSingleton_throwsInconsistentStateException() {
        AccountId targetId = AccountId.generate();
        Account target = seedAccount(targetId, "target@example.com", AccountStatus.ACTIVE, PlatformAuthority.ADMINISTRATOR);

        int initialRetry = retryHelper.getRetryCount();
        long initialAudit = getAuditLogCount();

        assertThrows(PlatformAuthorityInconsistentStateException.class,
                () -> bootstrapService.bootstrap("target@example.com"));

        assertNegativeInvariants(targetId, target, null, null, initialAudit, initialRetry);
    }

    // =========================================================================
    // Caso 5: Cuenta inexistente -> BootstrapTargetAccountNotFoundException
    // =========================================================================
    @Test
    void bootstrap_targetAccountNotFound_throwsBootstrapTargetAccountNotFoundException() {
        int initialRetry = retryHelper.getRetryCount();
        long initialAudit = getAuditLogCount();

        assertThrows(BootstrapTargetAccountNotFoundException.class,
                () -> bootstrapService.bootstrap("nonexistent@example.com"));

        assertNegativeInvariants(null, null, null, null, initialAudit, initialRetry);
    }

    // =========================================================================
    // Caso 6: Cuenta INACTIVE -> InactiveAccountException
    // =========================================================================
    @Test
    void bootstrap_targetAccountInactive_throwsInactiveAccountException() {
        AccountId targetId = AccountId.generate();
        Account target = seedAccount(targetId, "inactive@example.com", AccountStatus.INACTIVE, null);

        int initialRetry = retryHelper.getRetryCount();
        long initialAudit = getAuditLogCount();

        assertThrows(InactiveAccountException.class,
                () -> bootstrapService.bootstrap("inactive@example.com"));

        assertNegativeInvariants(targetId, target, null, null, initialAudit, initialRetry);
    }

    // =========================================================================
    // Caso 7: Email null, "" y "no-es-email" -> InvalidEmailFormatException (3 casos)
    // =========================================================================
    @Test
    void bootstrap_invalidEmail_null_throwsInvalidEmailFormatException_andNoMongoInteraction() {
        int initialRetry = retryHelper.getRetryCount();
        long initialAudit = getAuditLogCount();

        assertThrows(InvalidEmailFormatException.class, () -> bootstrapService.bootstrap(null));

        verifyNoInteractions(platformAuthorityStatePort, accountRepository);
        assertNegativeInvariants(null, null, null, null, initialAudit, initialRetry);
    }

    @Test
    void bootstrap_invalidEmail_blank_throwsInvalidEmailFormatException_andNoMongoInteraction() {
        int initialRetry = retryHelper.getRetryCount();
        long initialAudit = getAuditLogCount();

        assertThrows(InvalidEmailFormatException.class, () -> bootstrapService.bootstrap(""));

        verifyNoInteractions(platformAuthorityStatePort, accountRepository);
        assertNegativeInvariants(null, null, null, null, initialAudit, initialRetry);
    }

    @Test
    void bootstrap_invalidEmail_malformed_throwsInvalidEmailFormatException_andNoMongoInteraction() {
        int initialRetry = retryHelper.getRetryCount();
        long initialAudit = getAuditLogCount();

        assertThrows(InvalidEmailFormatException.class, () -> bootstrapService.bootstrap("no-es-email"));

        verifyNoInteractions(platformAuthorityStatePort, accountRepository);
        assertNegativeInvariants(null, null, null, null, initialAudit, initialRetry);
    }

    // =========================================================================
    // Caso 8: Rollback real (AuditLogPort lanza excepción después de escrituras)
    // =========================================================================
    @Test
    void bootstrap_auditFailure_rollsBackSingletonAndAccountAuthority() {
        AccountId targetId = AccountId.generate();
        seedAccount(targetId, "admin@example.com", AccountStatus.ACTIVE, null);

        doThrow(new NamedAuditFailureException("Simulated audit write failure"))
                .when(auditLogPort).record(any(AuditLogEntry.class));

        int initialRetry = retryHelper.getRetryCount();

        assertThrows(NamedAuditFailureException.class,
                () -> bootstrapService.bootstrap("admin@example.com"));

        // Rollback asserted: singleton does NOT exist, account has NO authority, 0 audits
        assertNull(getPlatformAuthorityState(), "Platform authority state must be rolled back");
        Account targetInDb = accountRepository.findById(targetId);
        assertNull(targetInDb.getPlatformAuthority(), "Account platform authority must be rolled back");
        assertEquals(0L, getAuditLogCount(), "Audit log must be empty");
        assertEquals(initialRetry, retryHelper.getRetryCount());
    }

    private static class NamedAuditFailureException extends RuntimeException {
        NamedAuditFailureException(String message) {
            super(message);
        }
    }

    // =========================================================================
    // Caso 9: grantPlatformAuthorityIfAbsent stubbeado a false
    // =========================================================================
    @Test
    void bootstrap_conditionalWriteMatchesNothing_throwsInvariantViolation_andRollsBack() {
        AccountId targetId = AccountId.generate();
        seedAccount(targetId, "admin@example.com", AccountStatus.ACTIVE, null);

        doReturn(false).when(accountRepository).grantPlatformAuthorityIfAbsent(targetId);

        int initialRetry = retryHelper.getRetryCount();

        assertThrows(PlatformAuthorityInvariantViolationException.class,
                () -> bootstrapService.bootstrap("admin@example.com"));

        assertNull(getPlatformAuthorityState(), "Platform authority state must not exist after rollback");
        Account targetInDb = accountRepository.findById(targetId);
        assertNull(targetInDb.getPlatformAuthority(), "Account must remain without authority");
        assertEquals(0L, getAuditLogCount(), "Audit log must remain empty");
        assertEquals(initialRetry, retryHelper.getRetryCount());
    }
}
