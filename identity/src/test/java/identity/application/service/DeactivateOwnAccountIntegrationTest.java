package identity.application.service;

import com.traceability.contracts.authorization.AuthorizationPrincipal;
import identity.application.port.out.AccountRepositoryPort;
import identity.domain.exception.ActiveCampaignResponsibleException;
import identity.domain.exception.CurrentPasswordIncorrectException;
import identity.domain.exception.PlatformAdministratorDeactivationException;
import identity.domain.exception.RepresentativeTransferRequiredException;
import identity.domain.model.Account;
import identity.domain.model.AccountId;
import identity.domain.model.AccountStatus;
import identity.domain.model.AuditAction;
import identity.domain.model.AuditActor;
import identity.domain.model.Email;
import identity.domain.model.OrganizationId;
import identity.domain.model.OrganizationType;
import identity.infrastructure.persistence.mongo.BaseMongoIntegrationTest;
import identity.infrastructure.persistence.mongo.documents.AuditLogEntryDocument;
import identity.infrastructure.persistence.mongo.repositories.MongoAccountRepositoryAdapter;
import identity.infrastructure.persistence.mongo.repositories.MongoAuditLogAdapter;
import identity.infrastructure.persistence.mongo.repositories.MongoOrganizationRepositoryAdapter;
import identity.infrastructure.security.BCryptPasswordHasherAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.test.context.ContextConfiguration;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ADR-050 D6 y Q3 (a) (plan D6 §4.1 y §6), en Mongo real con {@link StubCampaignResponsibilityPort} en lugar de
 * {@code convocatoria}: {@link DeactivateAccountService#deactivateOwnAccount}. Test 24 de ADR-050 (las tres
 * excepciones dejan la cuenta {@code ACTIVE} y sin auditoría), la contraseña incorrecta y el éxito.
 */
@DataMongoTest
@ContextConfiguration(classes = identity.infrastructure.persistence.mongo.IdentityTestApplication.class)
@Import({
        MongoAccountRepositoryAdapter.class,
        MongoAuditLogAdapter.class,
        MongoOrganizationRepositoryAdapter.class,
        BCryptPasswordHasherAdapter.class,
        MongoTransactionRetryHelper.class,
        CreateAccountService.class,
        CreateOrganizationService.class,
        AddEmployeeService.class,
        DeactivateAccountService.class,
        identity.application.authorization.AuthorizationAuditActorMapper.class,
        StubCampaignResponsibilityPort.class
})
class DeactivateOwnAccountIntegrationTest extends BaseMongoIntegrationTest {

    private static final String PASSWORD = "Pass123!Pass123!";
    private static final AuditActor SETUP = new AuditActor.SystemAuditActor("d6-identity-tests");

    @Autowired private CreateAccountService accounts;
    @Autowired private CreateOrganizationService organizations;
    @Autowired private AddEmployeeService employees;
    @Autowired private DeactivateAccountService deactivation;
    @Autowired private AccountRepositoryPort accountRepository;
    @Autowired private StubCampaignResponsibilityPort responsibilities;
    @Autowired private MongoTemplate mongoTemplate;

    @BeforeEach
    void reset() {
        responsibilities.reset();
    }

    private AccountId newAccount() {
        return accounts.createAccount(new Email(UUID.randomUUID() + "@d6.test"), PASSWORD).getAccountId();
    }

    private OrganizationId organizationOf(AccountId representative) {
        return organizations.createOrganization(SETUP, OrganizationType.FOUNDATION, representative, "Fundación D6")
                .getOrganizationId();
    }

    private static AuthorizationPrincipal principal(AccountId id) {
        // el servicio solo usa accountId: los roles y la autoridad los vuelve a leer de la cuenta
        return new AuthorizationPrincipal(id.value(), null, Set.of(), null);
    }

    private List<AuditLogEntryDocument> deactivationAudits(AccountId id) {
        return mongoTemplate.find(Query.query(Criteria.where("targetAccountId").is(id.value())
                .and("action").is(AuditAction.ACCOUNT_DEACTIVATED.name())), AuditLogEntryDocument.class);
    }

    /** La cuenta sigue ACTIVE, con el mismo credentialsNotBefore, y no hay auditoría de la desactivación. */
    private void nothingChanged(AccountId id, Instant notBeforeBefore) {
        Account after = accountRepository.findById(id);
        assertThat(after.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(after.getCredentialsNotBefore()).isEqualTo(notBeforeBefore);
        assertThat(deactivationAudits(id)).isEmpty();
    }

    private void refused(AccountId id, Class<? extends Throwable> expected) {
        Instant notBefore = accountRepository.findById(id).getCredentialsNotBefore();
        assertThatThrownBy(() -> deactivation.deactivateOwnAccount(principal(id), PASSWORD)).isInstanceOf(expected);
        nothingChanged(id, notBefore);
    }

    // ── 24 ──

    @Test
    void test24_thePlatformAdministrator_isRefused() {
        AccountId id = newAccount();
        mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(id.value())),
                Update.update("platformAuthority", "ADMINISTRATOR"), "accounts");

        refused(id, PlatformAdministratorDeactivationException.class);
    }

    @Test
    void test24_aPlatformAdministratorWhoIsAlsoRepresentative_getsThePlatformRefusalFirst() {
        AccountId id = newAccount();
        organizationOf(id);
        mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(id.value())),
                Update.update("platformAuthority", "ADMINISTRATOR"), "accounts");

        // P-D6-2: administrador antes que representante
        refused(id, PlatformAdministratorDeactivationException.class);
    }

    @Test
    void test24_theRepresentative_isRefused() {
        AccountId id = newAccount();
        organizationOf(id);

        refused(id, RepresentativeTransferRequiredException.class);
    }

    @Test
    void test24_anActiveCampaignResponsible_isRefused() {
        OrganizationId org = organizationOf(newAccount());
        AccountId id = newAccount();
        employees.addEmployee(SETUP, org, id);
        responsibilities.markActiveResponsible(org.value(), id.value(), "EMPLOYEE");

        refused(id, ActiveCampaignResponsibleException.class);
    }

    // ── contraseña ──

    @Test
    void aWrongCurrentPassword_isRefusedBeforeAnyOtherCheck() {
        AccountId id = newAccount();
        organizationOf(id); // representante: la contraseña va antes (P-D6-2)
        Instant notBefore = accountRepository.findById(id).getCredentialsNotBefore();

        assertThatThrownBy(() -> deactivation.deactivateOwnAccount(principal(id), "Wrong-Pass-123!"))
                .isInstanceOf(CurrentPasswordIncorrectException.class);
        assertThatThrownBy(() -> deactivation.deactivateOwnAccount(principal(id), null))
                .isInstanceOf(CurrentPasswordIncorrectException.class);
        nothingChanged(id, notBefore);
    }

    // ── éxito ──

    @Test
    void anEmployee_isDeactivated_withCredentialsNotBefore_theMembershipIntact_andTheAuditOfTheAccount() {
        OrganizationId org = organizationOf(newAccount());
        AccountId id = newAccount();
        employees.addEmployee(SETUP, org, id);
        Instant before = Instant.now();

        deactivation.deactivateOwnAccount(principal(id), PASSWORD);

        Account after = accountRepository.findById(id);
        assertThat(after.getStatus()).isEqualTo(AccountStatus.INACTIVE);
        assertThat(after.getCredentialsNotBefore()).isAfter(before);
        // D6: ni la cuenta ni la organización pierden la membresía
        assertThat(after.getOrganizationId()).isEqualTo(org);
        assertThat(mongoTemplate.findById(org.value(), org.bson.Document.class, "organizations").toJson())
                .contains(id.value());
        List<AuditLogEntryDocument> audits = deactivationAudits(id);
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0).getActor().getType()).isEqualTo("ACCOUNT");
        assertThat(audits.get(0).getActor().getAccountId()).isEqualTo(id.value());
    }

    @Test
    void anAccountWithoutOrganization_isDeactivated() {
        AccountId id = newAccount();

        deactivation.deactivateOwnAccount(principal(id), PASSWORD);

        assertThat(accountRepository.findById(id).getStatus()).isEqualTo(AccountStatus.INACTIVE);
        assertThat(deactivationAudits(id)).hasSize(1);
    }
}
