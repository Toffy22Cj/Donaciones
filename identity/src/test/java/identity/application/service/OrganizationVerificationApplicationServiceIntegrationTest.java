package identity.application.service;

import com.traceability.contracts.authorization.AuthorizationPrincipal;
import identity.application.authorization.AuthorizationAuditActorMapper;
import identity.application.authorization.PlatformAuthorizationPolicy;
import identity.application.port.out.AccountRepositoryPort;
import identity.application.port.out.AuditLogPort;
import identity.application.port.out.OrganizationRepositoryPort;
import identity.domain.exception.InsufficientPlatformAuthorityException;
import identity.domain.exception.InvalidInformationRequestMessageException;
import identity.domain.exception.InvalidVerificationTransitionException;
import identity.domain.exception.OrganizationNotFoundException;
import identity.domain.model.Account;
import identity.domain.model.AccountId;
import identity.domain.model.AccountStatus;
import identity.domain.model.AuditAction;
import identity.domain.model.AuditActor;
import identity.domain.model.Email;
import identity.domain.model.Organization;
import identity.domain.model.OrganizationId;
import identity.domain.model.OrganizationType;
import identity.domain.model.PasswordHash;
import identity.domain.model.PlatformAuthority;
import identity.domain.model.VerificationCommand;
import identity.domain.model.VerificationStatus;
import identity.infrastructure.persistence.mongo.BaseMongoIntegrationTest;
import identity.infrastructure.persistence.mongo.documents.AuditLogEntryDocument;
import identity.infrastructure.persistence.mongo.repositories.MongoAccountRepositoryAdapter;
import identity.infrastructure.persistence.mongo.repositories.MongoAuditLogAdapter;
import identity.infrastructure.persistence.mongo.repositories.MongoOrganizationRepositoryAdapter;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;

@DataMongoTest
@ContextConfiguration(classes = identity.infrastructure.persistence.mongo.IdentityTestApplication.class)
@Import({
        MongoAccountRepositoryAdapter.class,
        MongoOrganizationRepositoryAdapter.class,
        MongoAuditLogAdapter.class,
        MongoTransactionRetryHelper.class,
        PlatformAuthorizationPolicy.class,
        AuthorizationAuditActorMapper.class,
        CreateOrganizationService.class,
        VerifyOrganizationService.class,
        RejectOrganizationService.class,
        RequestOrganizationInformationService.class
})
@DirtiesContext
class OrganizationVerificationApplicationServiceIntegrationTest extends BaseMongoIntegrationTest {

    @MockitoSpyBean
    private AccountRepositoryPort accountRepository;

    @MockitoSpyBean
    private OrganizationRepositoryPort organizationRepository;

    @Autowired
    private MongoTransactionRetryHelper retryHelper;

    @Autowired
    private CreateOrganizationService createOrganizationService;

    @Autowired
    private VerifyOrganizationService verifyOrganizationService;

    @Autowired
    private RejectOrganizationService rejectOrganizationService;

    @Autowired
    private RequestOrganizationInformationService requestOrganizationInformationService;

    @Autowired
    private MongoTemplate mongoTemplate;

    private final AuditActor systemActor = new AuditActor.SystemAuditActor("test-runner");

    @BeforeEach
    void setUp() {
        reset(accountRepository, organizationRepository);
        mongoTemplate.dropCollection("accounts");
        mongoTemplate.dropCollection("organizations");
        mongoTemplate.dropCollection("identity_audit_log");
    }

    private Account seedCallerAccount(AccountId accountId, String email, AccountStatus status, PlatformAuthority authority) {
        Account account = Account.reconstitute(
                accountId,
                new Email(email),
                new PasswordHash("dummyHash"),
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

    private OrganizationId seedOrganizationWithRepresentative(String status, String message) {
        AccountId repId = AccountId.generate();
        seedCallerAccount(repId, "rep-" + repId.value() + "@example.com", AccountStatus.ACTIVE, null);

        Organization created = createOrganizationService.createOrganization(systemActor, OrganizationType.COMPANY, repId);
        OrganizationId organizationId = created.getOrganizationId();

        if (status != null && !VerificationStatus.PENDING_VERIFICATION.name().equals(status)) {
            Update update = new Update().set("verificationStatus", status);
            if (message != null) {
                update.set("verificationInformationRequest", message);
            } else {
                update.unset("verificationInformationRequest");
            }
            mongoTemplate.updateFirst(
                    Query.query(Criteria.where("_id").is(organizationId.value())),
                    update,
                    "organizations"
            );
        }
        return organizationId;
    }

    private void assertErrorInvariants(
            OrganizationId organizationId,
            String expectedStatus,
            String expectedMessage,
            long expectedAuditCount,
            int expectedRetryCount
    ) {
        Document actualDoc = mongoTemplate.findById(organizationId.value(), Document.class, "organizations");
        assertNotNull(actualDoc, "Organization document must exist in MongoDB");
        assertEquals(expectedStatus, actualDoc.getString("verificationStatus"), "Verification status must be unchanged");
        assertEquals(expectedMessage, actualDoc.getString("verificationInformationRequest"), "Verification message must be unchanged");

        long actualAuditCount = mongoTemplate.count(new Query(), "identity_audit_log");
        assertEquals(expectedAuditCount, actualAuditCount, "Audit log count must remain unchanged");
        assertEquals(expectedRetryCount, retryHelper.getRetryCount(), "Retry count must remain unchanged");
    }

    // =========================================================================
    // 1. Éxito: Las 6 transiciones válidas de §2.5
    // =========================================================================

    @Test
    void transition1_fromPendingVerification_toVerified_viaVerify() {
        AccountId callerId = AccountId.generate();
        seedCallerAccount(callerId, "admin1@example.com", AccountStatus.ACTIVE, PlatformAuthority.ADMINISTRATOR);
        AuthorizationPrincipal principal = createPrincipal(callerId, PlatformAuthority.ADMINISTRATOR);

        OrganizationId organizationId = seedOrganizationWithRepresentative("PENDING_VERIFICATION", null);
        long initialAudits = mongoTemplate.count(new Query(), "identity_audit_log");

        verifyOrganizationService.verifyOrganization(principal, organizationId);

        Document rawDoc = mongoTemplate.findById(organizationId.value(), Document.class, "organizations");
        assertNotNull(rawDoc);
        assertEquals("VERIFIED", rawDoc.getString("verificationStatus"));
        assertNull(rawDoc.getString("verificationInformationRequest"));

        List<Document> auditDocs = mongoTemplate.find(
                Query.query(Criteria.where("targetOrganizationId").is(organizationId.value())),
                Document.class,
                "identity_audit_log"
        );
        assertEquals(1, auditDocs.size() - initialAudits);

        Document auditDoc = auditDocs.get(auditDocs.size() - 1);
        assertEquals(AuditAction.ORGANIZATION_VERIFIED.name(), auditDoc.getString("action"));
        assertNull(auditDoc.getString("targetAccountId"));
        assertEquals(organizationId.value(), auditDoc.getString("targetOrganizationId"));

        Document actorDoc = auditDoc.get("actor", Document.class);
        assertNotNull(actorDoc);
        assertEquals("ACCOUNT", actorDoc.getString("type"));
        assertEquals(callerId.value(), actorDoc.getString("accountId"));

        Document summary = auditDoc.get("changeSummary", Document.class);
        assertNotNull(summary);
        assertEquals(3, summary.size());
        assertEquals("PENDING_VERIFICATION", summary.getString("previousStatus"));
        assertEquals("VERIFIED", summary.getString("newStatus"));
        assertFalse(summary.getBoolean("informationRequestPresent"));
    }

    @Test
    void transition2_fromPendingVerification_toRejected_viaReject() {
        AccountId callerId = AccountId.generate();
        seedCallerAccount(callerId, "admin2@example.com", AccountStatus.ACTIVE, PlatformAuthority.ADMINISTRATOR);
        AuthorizationPrincipal principal = createPrincipal(callerId, PlatformAuthority.ADMINISTRATOR);

        OrganizationId organizationId = seedOrganizationWithRepresentative("PENDING_VERIFICATION", null);

        rejectOrganizationService.rejectOrganization(principal, organizationId);

        Document rawDoc = mongoTemplate.findById(organizationId.value(), Document.class, "organizations");
        assertNotNull(rawDoc);
        assertEquals("REJECTED", rawDoc.getString("verificationStatus"));
        assertNull(rawDoc.getString("verificationInformationRequest"));

        List<Document> auditDocs = mongoTemplate.find(
                Query.query(Criteria.where("targetOrganizationId").is(organizationId.value())),
                Document.class,
                "identity_audit_log"
        );
        Document auditDoc = auditDocs.get(auditDocs.size() - 1);
        assertEquals(AuditAction.ORGANIZATION_REJECTED.name(), auditDoc.getString("action"));

        Document summary = auditDoc.get("changeSummary", Document.class);
        assertEquals(3, summary.size());
        assertEquals("PENDING_VERIFICATION", summary.getString("previousStatus"));
        assertEquals("REJECTED", summary.getString("newStatus"));
        assertFalse(summary.getBoolean("informationRequestPresent"));
    }

    @Test
    void transition3_fromPendingVerification_toNeedsMoreInformation_viaRequestInformation() {
        AccountId callerId = AccountId.generate();
        seedCallerAccount(callerId, "admin3@example.com", AccountStatus.ACTIVE, PlatformAuthority.ADMINISTRATOR);
        AuthorizationPrincipal principal = createPrincipal(callerId, PlatformAuthority.ADMINISTRATOR);

        OrganizationId organizationId = seedOrganizationWithRepresentative("PENDING_VERIFICATION", null);
        String secretMessage = "SECRETO-PII-FALTA-RUT-123456";

        requestOrganizationInformationService.requestOrganizationInformation(principal, organizationId, secretMessage);

        Document rawDoc = mongoTemplate.findById(organizationId.value(), Document.class, "organizations");
        assertNotNull(rawDoc);
        assertEquals("NEEDS_MORE_INFORMATION", rawDoc.getString("verificationStatus"));
        assertEquals(secretMessage, rawDoc.getString("verificationInformationRequest"));

        List<Document> auditDocs = mongoTemplate.find(
                Query.query(Criteria.where("targetOrganizationId").is(organizationId.value())),
                Document.class,
                "identity_audit_log"
        );
        Document auditDoc = auditDocs.get(auditDocs.size() - 1);
        assertEquals(AuditAction.ORGANIZATION_INFORMATION_REQUESTED.name(), auditDoc.getString("action"));

        Document summary = auditDoc.get("changeSummary", Document.class);
        assertEquals(3, summary.size());
        assertEquals("PENDING_VERIFICATION", summary.getString("previousStatus"));
        assertEquals("NEEDS_MORE_INFORMATION", summary.getString("newStatus"));
        assertTrue(summary.getBoolean("informationRequestPresent"));

        assertFalse(auditDoc.toJson().contains(secretMessage), "Audit log entry MUST NOT contain the message text");
    }

    @Test
    void transition4_fromNeedsMoreInformation_toVerified_viaVerify_clearsMessage() {
        AccountId callerId = AccountId.generate();
        seedCallerAccount(callerId, "admin4@example.com", AccountStatus.ACTIVE, PlatformAuthority.ADMINISTRATOR);
        AuthorizationPrincipal principal = createPrincipal(callerId, PlatformAuthority.ADMINISTRATOR);

        OrganizationId organizationId = seedOrganizationWithRepresentative("NEEDS_MORE_INFORMATION", "Falta estatuto");

        verifyOrganizationService.verifyOrganization(principal, organizationId);

        Document rawDoc = mongoTemplate.findById(organizationId.value(), Document.class, "organizations");
        assertNotNull(rawDoc);
        assertEquals("VERIFIED", rawDoc.getString("verificationStatus"));
        assertNull(rawDoc.getString("verificationInformationRequest"));

        List<Document> auditDocs = mongoTemplate.find(
                Query.query(Criteria.where("targetOrganizationId").is(organizationId.value())),
                Document.class,
                "identity_audit_log"
        );
        Document auditDoc = auditDocs.get(auditDocs.size() - 1);
        assertEquals(AuditAction.ORGANIZATION_VERIFIED.name(), auditDoc.getString("action"));

        Document summary = auditDoc.get("changeSummary", Document.class);
        assertEquals(3, summary.size());
        assertEquals("NEEDS_MORE_INFORMATION", summary.getString("previousStatus"));
        assertEquals("VERIFIED", summary.getString("newStatus"));
        assertFalse(summary.getBoolean("informationRequestPresent"));
    }

    @Test
    void transition5_fromNeedsMoreInformation_toRejected_viaReject_clearsMessage() {
        AccountId callerId = AccountId.generate();
        seedCallerAccount(callerId, "admin5@example.com", AccountStatus.ACTIVE, PlatformAuthority.ADMINISTRATOR);
        AuthorizationPrincipal principal = createPrincipal(callerId, PlatformAuthority.ADMINISTRATOR);

        OrganizationId organizationId = seedOrganizationWithRepresentative("NEEDS_MORE_INFORMATION", "Falta estatuto");

        rejectOrganizationService.rejectOrganization(principal, organizationId);

        Document rawDoc = mongoTemplate.findById(organizationId.value(), Document.class, "organizations");
        assertNotNull(rawDoc);
        assertEquals("REJECTED", rawDoc.getString("verificationStatus"));
        assertNull(rawDoc.getString("verificationInformationRequest"));

        List<Document> auditDocs = mongoTemplate.find(
                Query.query(Criteria.where("targetOrganizationId").is(organizationId.value())),
                Document.class,
                "identity_audit_log"
        );
        Document auditDoc = auditDocs.get(auditDocs.size() - 1);
        assertEquals(AuditAction.ORGANIZATION_REJECTED.name(), auditDoc.getString("action"));

        Document summary = auditDoc.get("changeSummary", Document.class);
        assertEquals(3, summary.size());
        assertEquals("NEEDS_MORE_INFORMATION", summary.getString("previousStatus"));
        assertEquals("REJECTED", summary.getString("newStatus"));
        assertFalse(summary.getBoolean("informationRequestPresent"));
    }

    @Test
    void transition6_fromNeedsMoreInformation_toNeedsMoreInformation_viaRequestInformation_replacesMessage() {
        AccountId callerId = AccountId.generate();
        seedCallerAccount(callerId, "admin6@example.com", AccountStatus.ACTIVE, PlatformAuthority.ADMINISTRATOR);
        AuthorizationPrincipal principal = createPrincipal(callerId, PlatformAuthority.ADMINISTRATOR);

        OrganizationId organizationId = seedOrganizationWithRepresentative("NEEDS_MORE_INFORMATION", "Primer requerimiento");
        String newSecretMessage = "SECRETO-PII-NUEVO-REQUERIMIENTO-789";

        requestOrganizationInformationService.requestOrganizationInformation(principal, organizationId, newSecretMessage);

        Document rawDoc = mongoTemplate.findById(organizationId.value(), Document.class, "organizations");
        assertNotNull(rawDoc);
        assertEquals("NEEDS_MORE_INFORMATION", rawDoc.getString("verificationStatus"));
        assertEquals(newSecretMessage, rawDoc.getString("verificationInformationRequest"));

        List<Document> auditDocs = mongoTemplate.find(
                Query.query(Criteria.where("targetOrganizationId").is(organizationId.value())),
                Document.class,
                "identity_audit_log"
        );
        Document auditDoc = auditDocs.get(auditDocs.size() - 1);
        assertEquals(AuditAction.ORGANIZATION_INFORMATION_REQUESTED.name(), auditDoc.getString("action"));

        Document summary = auditDoc.get("changeSummary", Document.class);
        assertEquals(3, summary.size());
        assertEquals("NEEDS_MORE_INFORMATION", summary.getString("previousStatus"));
        assertEquals("NEEDS_MORE_INFORMATION", summary.getString("newStatus"));
        assertTrue(summary.getBoolean("informationRequestPresent"));

        assertFalse(auditDoc.toJson().contains(newSecretMessage), "Audit log entry MUST NOT contain the message text");
    }

    // =========================================================================
    // 2. Transiciones inválidas: 6 casos (3 comandos × {VERIFIED, REJECTED})
    // =========================================================================

    @ParameterizedTest(name = "invalid transition: status={0}, command={1}")
    @MethodSource("invalidTransitions")
    void invalidTransitions_throwInvalidVerificationTransitionException_andPreserveInvariants(
            VerificationStatus currentStatus,
            VerificationCommand command
    ) {
        AccountId callerId = AccountId.generate();
        seedCallerAccount(callerId, "admin.invalid@example.com", AccountStatus.ACTIVE, PlatformAuthority.ADMINISTRATOR);
        AuthorizationPrincipal principal = createPrincipal(callerId, PlatformAuthority.ADMINISTRATOR);

        OrganizationId organizationId = seedOrganizationWithRepresentative(currentStatus.name(), null);
        long initialAudits = mongoTemplate.count(new Query(), "identity_audit_log");
        int initialRetries = retryHelper.getRetryCount();

        InvalidVerificationTransitionException ex = assertThrows(
                InvalidVerificationTransitionException.class,
                () -> {
                    switch (command) {
                        case VERIFY -> verifyOrganizationService.verifyOrganization(principal, organizationId);
                        case REJECT -> rejectOrganizationService.rejectOrganization(principal, organizationId);
                        case REQUEST_INFORMATION -> requestOrganizationInformationService.requestOrganizationInformation(
                                principal, organizationId, "Mensaje de prueba"
                        );
                    }
                }
        );

        assertEquals(currentStatus, ex.getCurrentStatus());
        assertEquals(command, ex.getCommand());

        assertErrorInvariants(organizationId, currentStatus.name(), null, initialAudits, initialRetries);
    }

    static Stream<Arguments> invalidTransitions() {
        return Stream.of(
                Arguments.of(VerificationStatus.VERIFIED, VerificationCommand.VERIFY),
                Arguments.of(VerificationStatus.VERIFIED, VerificationCommand.REJECT),
                Arguments.of(VerificationStatus.VERIFIED, VerificationCommand.REQUEST_INFORMATION),
                Arguments.of(VerificationStatus.REJECTED, VerificationCommand.VERIFY),
                Arguments.of(VerificationStatus.REJECTED, VerificationCommand.REJECT),
                Arguments.of(VerificationStatus.REJECTED, VerificationCommand.REQUEST_INFORMATION)
        );
    }

    // =========================================================================
    // 3. Mensaje inválido ("   " y 2001 caracteres)
    // =========================================================================

    @ParameterizedTest(name = "invalid message: {0}")
    @MethodSource("invalidMessages")
    void invalidMessage_throwsInvalidInformationRequestMessageException_andVerifiesNoInteractions(
            String caseName,
            String invalidMessage
    ) {
        AccountId callerId = AccountId.generate();
        seedCallerAccount(callerId, "admin.msg@example.com", AccountStatus.ACTIVE, PlatformAuthority.ADMINISTRATOR);
        AuthorizationPrincipal principal = createPrincipal(callerId, PlatformAuthority.ADMINISTRATOR);

        String previousMessage = "Mensaje previo legitimo";
        OrganizationId organizationId = seedOrganizationWithRepresentative("NEEDS_MORE_INFORMATION", previousMessage);

        reset(accountRepository, organizationRepository);
        long initialAudits = mongoTemplate.count(new Query(), "identity_audit_log");

        assertThrows(
                InvalidInformationRequestMessageException.class,
                () -> requestOrganizationInformationService.requestOrganizationInformation(principal, organizationId, invalidMessage)
        );

        verifyNoInteractions(accountRepository);
        verifyNoInteractions(organizationRepository);

        Document rawDoc = mongoTemplate.findById(organizationId.value(), Document.class, "organizations");
        assertNotNull(rawDoc);
        assertEquals("NEEDS_MORE_INFORMATION", rawDoc.getString("verificationStatus"));
        assertEquals(previousMessage, rawDoc.getString("verificationInformationRequest"), "Previous message must be retained");

        assertEquals(initialAudits, mongoTemplate.count(new Query(), "identity_audit_log"));
    }

    static Stream<Arguments> invalidMessages() {
        return Stream.of(
                Arguments.of("blank string", "   "),
                Arguments.of("2001 code points", ("SECRETO-PII-2001-".repeat(200)).substring(0, 2001))
        );
    }

    // =========================================================================
    // 4. Principal sin autoridad en los 3 comandos
    // =========================================================================

    @Test
    void principalWithoutAuthority_throwsInsufficientPlatformAuthorityException_andVerifiesNoInteractions() {
        AccountId callerId = AccountId.generate();
        seedCallerAccount(callerId, "user.noauth@example.com", AccountStatus.ACTIVE, null);
        AuthorizationPrincipal principalNoAuth = createPrincipal(callerId, null);

        OrganizationId organizationId = seedOrganizationWithRepresentative("PENDING_VERIFICATION", null);

        reset(accountRepository, organizationRepository);

        assertThrows(
                InsufficientPlatformAuthorityException.class,
                () -> verifyOrganizationService.verifyOrganization(principalNoAuth, organizationId)
        );
        verifyNoInteractions(accountRepository);
        verifyNoInteractions(organizationRepository);

        assertThrows(
                InsufficientPlatformAuthorityException.class,
                () -> rejectOrganizationService.rejectOrganization(principalNoAuth, organizationId)
        );
        verifyNoInteractions(accountRepository);
        verifyNoInteractions(organizationRepository);

        assertThrows(
                InsufficientPlatformAuthorityException.class,
                () -> requestOrganizationInformationService.requestOrganizationInformation(principalNoAuth, organizationId, "msg")
        );
        verifyNoInteractions(accountRepository);
        verifyNoInteractions(organizationRepository);
    }

    // =========================================================================
    // 5. D8e: Llamador INACTIVE, sin autoridad en BD, o inexistente en BD
    // =========================================================================

    @ParameterizedTest(name = "caller revalidation failure: callerType={0}, command={1}")
    @MethodSource("invalidCallersAndCommands")
    void callerRevalidationFailure_throwsInsufficientPlatformAuthorityException_andPreservesInvariants(
            String callerType,
            VerificationCommand command
    ) {
        AccountId callerId = AccountId.generate();
        if ("INACTIVE".equals(callerType)) {
            seedCallerAccount(callerId, "inactive@example.com", AccountStatus.INACTIVE, PlatformAuthority.ADMINISTRATOR);
        } else if ("NO_AUTHORITY_IN_DB".equals(callerType)) {
            seedCallerAccount(callerId, "noauthdb@example.com", AccountStatus.ACTIVE, null);
        } // "NON_EXISTENT_IN_DB" -> do not save to repository

        // Principal claims ADMINISTRATOR so it passes policy.authorize
        AuthorizationPrincipal principal = createPrincipal(callerId, PlatformAuthority.ADMINISTRATOR);

        OrganizationId organizationId = seedOrganizationWithRepresentative("PENDING_VERIFICATION", null);
        long initialAudits = mongoTemplate.count(new Query(), "identity_audit_log");
        int initialRetries = retryHelper.getRetryCount();

        assertThrows(
                InsufficientPlatformAuthorityException.class,
                () -> {
                    switch (command) {
                        case VERIFY -> verifyOrganizationService.verifyOrganization(principal, organizationId);
                        case REJECT -> rejectOrganizationService.rejectOrganization(principal, organizationId);
                        case REQUEST_INFORMATION -> requestOrganizationInformationService.requestOrganizationInformation(
                                principal, organizationId, "Mensaje"
                        );
                    }
                }
        );

        assertErrorInvariants(organizationId, "PENDING_VERIFICATION", null, initialAudits, initialRetries);
    }

    static Stream<Arguments> invalidCallersAndCommands() {
        return Stream.of(
                Arguments.of("INACTIVE", VerificationCommand.VERIFY),
                Arguments.of("INACTIVE", VerificationCommand.REJECT),
                Arguments.of("INACTIVE", VerificationCommand.REQUEST_INFORMATION),
                Arguments.of("NO_AUTHORITY_IN_DB", VerificationCommand.VERIFY),
                Arguments.of("NO_AUTHORITY_IN_DB", VerificationCommand.REJECT),
                Arguments.of("NO_AUTHORITY_IN_DB", VerificationCommand.REQUEST_INFORMATION),
                Arguments.of("NON_EXISTENT_IN_DB", VerificationCommand.VERIFY),
                Arguments.of("NON_EXISTENT_IN_DB", VerificationCommand.REJECT),
                Arguments.of("NON_EXISTENT_IN_DB", VerificationCommand.REQUEST_INFORMATION)
        );
    }

    // =========================================================================
    // 6. Organización inexistente
    // =========================================================================

    @Test
    void nonExistentOrganization_throwsOrganizationNotFoundException() {
        AccountId callerId = AccountId.generate();
        seedCallerAccount(callerId, "admin.missingorg@example.com", AccountStatus.ACTIVE, PlatformAuthority.ADMINISTRATOR);
        AuthorizationPrincipal principal = createPrincipal(callerId, PlatformAuthority.ADMINISTRATOR);

        OrganizationId nonExistentOrgId = OrganizationId.generate();
        long initialAudits = mongoTemplate.count(new Query(), "identity_audit_log");

        assertThrows(
                OrganizationNotFoundException.class,
                () -> verifyOrganizationService.verifyOrganization(principal, nonExistentOrgId)
        );

        assertThrows(
                OrganizationNotFoundException.class,
                () -> rejectOrganizationService.rejectOrganization(principal, nonExistentOrgId)
        );

        assertThrows(
                OrganizationNotFoundException.class,
                () -> requestOrganizationInformationService.requestOrganizationInformation(principal, nonExistentOrgId, "Mensaje")
        );

        assertNull(mongoTemplate.findById(nonExistentOrgId.value(), Document.class, "organizations"));
        assertEquals(initialAudits, mongoTemplate.count(new Query(), "identity_audit_log"));
    }
}
