package identity.application.service;

import com.traceability.contracts.authorization.AuthorizationPrincipal;
import com.traceability.contracts.authorization.AuthorizationRole;
import identity.application.port.out.AccountRepositoryPort;
import identity.application.port.out.AuditLogPort;
import identity.application.port.out.OrganizationRepositoryPort;
import identity.domain.exception.LegalVersionNotCurrentException;
import identity.domain.model.Account;
import identity.domain.model.AuditAction;
import identity.domain.model.AuditActor;
import identity.domain.model.AuditLogEntry;
import identity.domain.model.Email;
import identity.domain.model.LegalAcceptance;
import identity.domain.model.LegalVersions;
import identity.domain.model.Organization;
import identity.domain.model.OrganizationTermsAcceptance;
import identity.domain.model.OrganizationType;
import identity.infrastructure.persistence.mongo.BaseMongoIntegrationTest;
import identity.infrastructure.persistence.mongo.repositories.MongoAccountRepositoryAdapter;
import identity.infrastructure.persistence.mongo.repositories.MongoAuditLogAdapter;
import identity.infrastructure.persistence.mongo.repositories.MongoOrganizationRepositoryAdapter;
import identity.infrastructure.security.BCryptPasswordHasherAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

/**
 * ADR-050 D1, D2 y D10 (plan R-5, primera parte), en Mongo real: tests 20 (versión y fecha de cada aceptación; la
 * organización guarda la suya con la cuenta) y 22 ({@code ACCOUNT_CREATED} sin el correo), y {@code PROFILE_UPDATED} sin
 * valores.
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
        CreateOrganizationService.class,
        AccountProfileService.class,
        identity.application.authorization.AuthorizationAuditActorMapper.class,
        LegalAcceptanceAndProfileIntegrationTest.TestBeans.class
})
class LegalAcceptanceAndProfileIntegrationTest extends BaseMongoIntegrationTest {

    static final Instant NOW = Instant.parse("2026-10-25T12:00:00Z");
    static final String CURRENT = "v1-2026-10";

    @TestConfiguration
    static class TestBeans {
        @Bean Clock clock() { return Clock.fixed(NOW, ZoneOffset.UTC); }
        @Bean LegalVersions legalVersions() { return new LegalVersions(CURRENT, CURRENT, CURRENT); }
    }

    @Autowired private CreateAccountService accounts;
    @Autowired private CreateOrganizationService organizations;
    @Autowired private AccountProfileService profiles;
    @Autowired private OrganizationRepositoryPort organizationRepository;
    @MockitoSpyBean private AccountRepositoryPort accountRepository;
    @MockitoSpyBean private AuditLogPort auditLog;
    @Autowired private org.springframework.data.mongodb.core.MongoTemplate mongoTemplate;

    @BeforeEach
    void setUp() {
        reset(accountRepository, auditLog);
    }

    private static Email newEmail() {
        return new Email("r5-" + UUID.randomUUID() + "@example.org");
    }

    private static AuthorizationPrincipal principalOf(Account account) {
        return new AuthorizationPrincipal(account.getAccountId().value(), null, Set.of(), null);
    }

    private AuditLogEntry lastAudit(AuditAction action) {
        ArgumentCaptor<AuditLogEntry> captor = ArgumentCaptor.forClass(AuditLogEntry.class);
        verify(auditLog, org.mockito.Mockito.atLeastOnce()).record(captor.capture());
        return captor.getAllValues().stream().filter(e -> e.action() == action).reduce((a, b) -> b).orElseThrow();
    }

    // ── 20 ──

    @Test
    void registration_storesTheVersionAndDateOfEachAcceptance() {
        Account created = accounts.createAccount(newEmail(), "Password123!long", CURRENT, CURRENT);

        Account stored = accountRepository.findById(created.getAccountId());
        assertThat(stored.getTermsAcceptance()).isEqualTo(new LegalAcceptance(CURRENT, NOW));
        assertThat(stored.getDataPolicyAcceptance()).isEqualTo(new LegalAcceptance(CURRENT, NOW));
    }

    @Test
    void registration_withAMissingOrOldVersion_namesTheField_andCreatesNothing() {
        Email email = newEmail();

        assertThatThrownBy(() -> accounts.createAccount(email, "Password123!long", null, CURRENT))
                .isInstanceOf(LegalVersionNotCurrentException.class)
                .satisfies(e -> assertThat(((LegalVersionNotCurrentException) e).getField()).isEqualTo("acceptedTermsVersion"));
        assertThatThrownBy(() -> accounts.createAccount(email, "Password123!long", CURRENT, "v0-2026-01"))
                .isInstanceOf(LegalVersionNotCurrentException.class)
                .satisfies(e -> assertThat(((LegalVersionNotCurrentException) e).getField())
                        .isEqualTo("acceptedDataPolicyVersion"));

        assertThat(accountRepository.findByEmail(email)).isEmpty();
        verify(accountRepository, never()).save(any());
        verify(auditLog, never()).record(any());
    }

    @Test
    void organization_storesTheVersionOfItsTermsWithTheAccountThatAcceptedThem() {
        Account representative = accounts.createAccount(newEmail(), "Password123!long", CURRENT, CURRENT);

        Organization created = organizations.createOrganization(new AuditActor.AccountAuditActor(representative.getAccountId()),
                OrganizationType.FOUNDATION, representative.getAccountId(), "Fundación R-5", CURRENT);

        Organization stored = organizationRepository.findById(created.getOrganizationId());
        assertThat(stored.getOrganizationTermsAcceptance())
                .isEqualTo(new OrganizationTermsAcceptance(CURRENT, representative.getAccountId(), NOW));
    }

    @Test
    void organization_withAMissingOrOldVersion_isRejected_andNotCreated() {
        Account representative = accounts.createAccount(newEmail(), "Password123!long", CURRENT, CURRENT);
        reset(accountRepository);

        assertThatThrownBy(() -> organizations.createOrganization(
                new AuditActor.AccountAuditActor(representative.getAccountId()), OrganizationType.FOUNDATION,
                representative.getAccountId(), "Otra", "v0-2026-01"))
                .isInstanceOf(LegalVersionNotCurrentException.class)
                .satisfies(e -> assertThat(((LegalVersionNotCurrentException) e).getField())
                        .isEqualTo("acceptedOrganizationTermsVersion"));

        assertThat(accountRepository.findById(representative.getAccountId()).getOrganizationId()).isNull();
    }

    // ── 22 ──

    @Test
    void accountCreated_doesNotCarryTheEmail() {
        Email email = newEmail();

        accounts.createAccount(email, "Password123!long", CURRENT, CURRENT);
        accounts.createAccount(newEmail(), "Password123!long");

        ArgumentCaptor<AuditLogEntry> captor = ArgumentCaptor.forClass(AuditLogEntry.class);
        verify(auditLog, org.mockito.Mockito.times(2)).record(captor.capture());
        for (AuditLogEntry entry : captor.getAllValues()) {
            assertThat(entry.action()).isEqualTo(AuditAction.ACCOUNT_CREATED);
            assertThat(entry.changeSummary()).isEqualTo(Map.of("selfRegistration", true));
            assertThat(entry.toString()).doesNotContain(email.value());
        }
    }

    @Test
    void accountCreated_theOldEntriesWithTheEmail_areNotRewritten() {
        // ADR-050 D10 y ADR-025: solo las entradas nuevas dejan de llevar el correo; las antiguas se quedan como estaban
        String oldEmail = "antigua-" + UUID.randomUUID() + "@r5.test";
        String auditId = "old-" + UUID.randomUUID();
        org.bson.Document old = new org.bson.Document("_id", auditId)
                .append("occurredAt", java.util.Date.from(Instant.parse("2026-09-01T10:00:00Z")))
                .append("actor", new org.bson.Document("type", "SYSTEM").append("processId", "legacy"))
                .append("action", "ACCOUNT_CREATED")
                .append("changeSummary", new org.bson.Document("email", oldEmail));
        mongoTemplate.insert(old, "identity_audit_log");

        accounts.createAccount(newEmail(), "Password123!long", CURRENT, CURRENT);

        org.bson.Document after = mongoTemplate.findById(auditId, org.bson.Document.class, "identity_audit_log");
        assertThat(after).isNotNull();
        assertThat(after.get("changeSummary", org.bson.Document.class).getString("email")).isEqualTo(oldEmail);
        assertThat(after.toJson()).isEqualTo(old.toJson());
    }

    // ── D1: perfil ──

    @Test
    void profileUpdate_auditsOnlyTheNamesOfTheChangedFields_andNothingWhenNothingChanges() {
        Account account = accounts.createAccount(newEmail(), "Password123!long", CURRENT, CURRENT);
        reset(auditLog);

        var profile = profiles.update(principalOf(account), "Ana", "Pérez");

        assertThat(profile.givenName()).isEqualTo("Ana");
        AuditLogEntry entry = lastAudit(AuditAction.PROFILE_UPDATED);
        assertThat(entry.changeSummary()).isEqualTo(Map.of("fields", List.of("givenName", "familyName")));
        assertThat(entry.toString()).doesNotContain("Ana").doesNotContain("Pérez");
        assertThat(entry.actor()).isEqualTo(new AuditActor.AccountAuditActor(account.getAccountId()));

        reset(auditLog);
        profiles.update(principalOf(account), "Ana", "Pérez");
        verify(auditLog, never()).record(any());

        Account stored = accountRepository.findById(account.getAccountId());
        assertThat(stored.getGivenName()).isEqualTo("Ana");
        assertThat(stored.getFamilyName()).isEqualTo("Pérez");
        assertThat(profiles.read(principalOf(account)).familyName()).isEqualTo("Pérez");
    }

    @Test
    void anAccountWithoutRoles_canStillReadItsProfile() {
        Account account = accounts.createAccount(newEmail(), "Password123!long");
        AuthorizationPrincipal principal = new AuthorizationPrincipal(account.getAccountId().value(), null,
                Set.of(AuthorizationRole.EMPLOYEE), null);

        assertThat(profiles.read(principal).givenName()).isNull();
    }
}
