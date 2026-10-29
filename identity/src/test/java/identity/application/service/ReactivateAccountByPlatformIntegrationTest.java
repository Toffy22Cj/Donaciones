package identity.application.service;

import com.traceability.contracts.authorization.AuthorizationPrincipal;
import com.traceability.contracts.authorization.AuthorizationRole;
import com.traceability.contracts.authorization.PlatformAuthority;
import identity.application.port.out.AccountRepositoryPort;
import identity.domain.exception.AccountNotFoundException;
import identity.domain.exception.InsufficientPlatformAuthorityException;
import identity.domain.model.Account;
import identity.domain.model.AccountId;
import identity.domain.model.AccountStatus;
import identity.domain.model.AuditAction;
import identity.domain.model.AuditActor;
import identity.domain.model.Email;
import identity.infrastructure.persistence.mongo.BaseMongoIntegrationTest;
import identity.infrastructure.persistence.mongo.documents.AuditLogEntryDocument;
import identity.infrastructure.persistence.mongo.repositories.MongoAccountRepositoryAdapter;
import identity.infrastructure.persistence.mongo.repositories.MongoAuditLogAdapter;
import identity.infrastructure.persistence.mongo.repositories.MongoOrganizationRepositoryAdapter;
import identity.infrastructure.security.BCryptPasswordHasherAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.ContextConfiguration;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ADR-050 D7 y test 26 (plan de cierre, §D7; P-D7-1 a 3), en Mongo real:
 * {@link ReactivateAccountService#reactivateAccount(AuthorizationPrincipal, AccountId)}. Solo el administrador de
 * plataforma reactiva, y autoriza antes de leer la cuenta; el actor de la auditoría es él; una cuenta ya activa no se
 * audita otra vez; reactivar no toca {@code credentialsNotBefore} (D4).
 */
@DataMongoTest
@ContextConfiguration(classes = identity.infrastructure.persistence.mongo.IdentityTestApplication.class)
@Import({
        EmailVerificationTestBeans.class, // ADR-050 Enmienda 2: el servicio de verificación y los dos puertos de correo
        MongoAccountRepositoryAdapter.class,
        MongoAuditLogAdapter.class,
        MongoOrganizationRepositoryAdapter.class,
        BCryptPasswordHasherAdapter.class,
        MongoTransactionRetryHelper.class,
        CreateAccountService.class,
        DeactivateAccountService.class,
        ReactivateAccountService.class,
        identity.application.authorization.PlatformAuthorizationPolicy.class,
        identity.application.authorization.AuthorizationAuditActorMapper.class,
        StubCampaignResponsibilityPort.class
})
class ReactivateAccountByPlatformIntegrationTest extends BaseMongoIntegrationTest {

    private static final String PASSWORD = "Pass123!Pass123!";
    private static final AuditActor SETUP = new AuditActor.SystemAuditActor("d7-identity-tests");

    @Autowired private CreateAccountService accounts;
    @Autowired private DeactivateAccountService deactivations;
    @Autowired private ReactivateAccountService reactivations;
    @Autowired private AccountRepositoryPort accountRepository;
    @Autowired private MongoTemplate mongoTemplate;

    private AccountId inactiveAccount() {
        AccountId id = accounts.createAccount(new Email(UUID.randomUUID() + "@d7.test"), PASSWORD).getAccountId();
        deactivations.deactivateAccount(SETUP, id);
        return id;
    }

    private static AuthorizationPrincipal platform() {
        return new AuthorizationPrincipal(AccountId.generate().value(), null, Set.of(), PlatformAuthority.ADMINISTRATOR);
    }

    private List<AuditLogEntryDocument> reactivationAudits(AccountId id) {
        return mongoTemplate.find(Query.query(Criteria.where("targetAccountId").is(id.value())
                .and("action").is(AuditAction.ACCOUNT_REACTIVATED.name())), AuditLogEntryDocument.class);
    }

    @Test
    void d7_1_thePlatformAdministrator_reactivates_andIsTheActorOfTheAudit() {
        AccountId id = inactiveAccount();
        AuthorizationPrincipal admin = platform();

        reactivations.reactivateAccount(admin, id);

        assertThat(accountRepository.findById(id).getStatus()).isEqualTo(AccountStatus.ACTIVE);
        List<AuditLogEntryDocument> audits = reactivationAudits(id);
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0).getActor().getType()).isEqualTo("ACCOUNT");
        assertThat(audits.get(0).getActor().getAccountId()).isEqualTo(admin.accountId());
    }

    // test 26: cualquier otro recibe el 403 uniforme (aquí, la excepción que lo produce) y nada cambia
    @Test
    void d7_2_test26_anyoneElse_isRefused_andTheAccountStaysInactiveWithoutAudit() {
        AccountId id = inactiveAccount();
        List<AuthorizationPrincipal> others = List.of(
                new AuthorizationPrincipal(AccountId.generate().value(), "org-1", Set.of(AuthorizationRole.ADMINISTRATOR), null),
                new AuthorizationPrincipal(AccountId.generate().value(), "org-1", Set.of(AuthorizationRole.REPRESENTATIVE), null),
                new AuthorizationPrincipal(AccountId.generate().value(), "org-1", Set.of(AuthorizationRole.EMPLOYEE), null),
                new AuthorizationPrincipal(AccountId.generate().value(), null, Set.of(), null),
                // la propia cuenta tampoco se reactiva a sí misma (D7: "solo el administrador de plataforma")
                new AuthorizationPrincipal(id.value(), null, Set.of(), null));

        for (AuthorizationPrincipal other : others) {
            assertThatThrownBy(() -> reactivations.reactivateAccount(other, id))
                    .isInstanceOf(InsufficientPlatformAuthorityException.class);
        }

        assertThat(accountRepository.findById(id).getStatus()).isEqualTo(AccountStatus.INACTIVE);
        assertThat(reactivationAudits(id)).isEmpty();
    }

    // autoriza antes de leer la cuenta: con una cuenta inexistente, el rechazo es el mismo y no un "no existe"
    @Test
    void d7_3_theRefusalComesBeforeReadingTheAccount() {
        AuthorizationPrincipal outsider = new AuthorizationPrincipal(AccountId.generate().value(), null, Set.of(), null);

        assertThatThrownBy(() -> reactivations.reactivateAccount(outsider, AccountId.generate()))
                .isInstanceOf(InsufficientPlatformAuthorityException.class);
    }

    // D4: reactivar no toca credentialsNotBefore, así que los tokens anteriores a la desactivación siguen rechazados
    @Test
    void d7_4_reactivating_keepsTheCredentialsNotBeforeOfTheDeactivation() {
        AccountId id = inactiveAccount();
        Instant notBefore = accountRepository.findById(id).getCredentialsNotBefore();
        assertThat(notBefore).isNotNull();

        reactivations.reactivateAccount(platform(), id);

        Account after = accountRepository.findById(id);
        assertThat(after.getCredentialsNotBefore()).isEqualTo(notBefore);
        assertThat(after.acceptsTokenIssuedAt(notBefore.minusSeconds(1))).isFalse();
    }

    // P-D7-3: una cuenta ya activa no cambia ni se audita otra vez
    @Test
    void d7_6_anAlreadyActiveAccount_isANoOp_withoutANewAudit() {
        AccountId id = inactiveAccount();
        reactivations.reactivateAccount(platform(), id);

        reactivations.reactivateAccount(platform(), id);

        assertThat(accountRepository.findById(id).getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(reactivationAudits(id)).hasSize(1);
    }

    // P-D7-2: inexistente, pedida por la plataforma → AccountNotFoundException (la ruta la traduce a 404)
    @Test
    void d7_7_anUnknownAccount_askedByThePlatform_isNotFound() {
        assertThatThrownBy(() -> reactivations.reactivateAccount(platform(), AccountId.generate()))
                .isInstanceOf(AccountNotFoundException.class);
    }
}
