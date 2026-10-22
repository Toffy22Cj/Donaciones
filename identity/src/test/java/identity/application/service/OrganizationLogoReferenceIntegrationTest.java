package identity.application.service;

import com.traceability.contracts.authorization.AuthorizationPrincipal;
import com.traceability.contracts.authorization.AuthorizationRole;
import com.traceability.contracts.authorization.PlatformAuthority;
import identity.application.authorization.AuthorizationAuditActorMapper;
import identity.application.authorization.OrganizationMembersPolicy;
import identity.application.authorization.PlatformAuthorizationPolicy;
import identity.application.port.out.AccountRepositoryPort;
import identity.application.port.out.OrganizationRepositoryPort;
import identity.application.service.OrganizationLogoReferenceQuery.PublicOrganization;
import identity.domain.exception.OrganizationAccessDeniedException;
import identity.domain.model.Account;
import identity.domain.model.AccountId;
import identity.domain.model.AccountStatus;
import identity.domain.model.AuditActor;
import identity.domain.model.Email;
import identity.domain.model.Organization;
import identity.domain.model.OrganizationType;
import identity.domain.model.PasswordHash;
import identity.infrastructure.persistence.mongo.BaseMongoIntegrationTest;
import identity.infrastructure.persistence.mongo.repositories.MongoAccountRepositoryAdapter;
import identity.infrastructure.persistence.mongo.repositories.MongoAuditLogAdapter;
import identity.infrastructure.persistence.mongo.repositories.MongoOrganizationRepositoryAdapter;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

/**
 * ADR-051 F9 y F10; plan de la Tarea 2 §4.1, T2-16 y la parte de {@code identity} de T2-7 y T2-13: la referencia del
 * logo en {@code organizations}, su escritura en transacción con reintento y sus lecturas.
 */
@DataMongoTest
@ContextConfiguration(classes = identity.infrastructure.persistence.mongo.IdentityTestApplication.class)
@Import({
        MongoAccountRepositoryAdapter.class,
        MongoOrganizationRepositoryAdapter.class,
        MongoAuditLogAdapter.class,
        MongoTransactionRetryHelper.class,
        PlatformAuthorizationPolicy.class,
        OrganizationMembersPolicy.class,
        AuthorizationAuditActorMapper.class,
        CreateOrganizationService.class,
        OrganizationLogoReferenceService.class,
        OrganizationLogoReferenceQuery.class
})
@DirtiesContext
class OrganizationLogoReferenceIntegrationTest extends BaseMongoIntegrationTest {

    @MockitoSpyBean private OrganizationRepositoryPort organizations;
    @Autowired private AccountRepositoryPort accounts;
    @Autowired private CreateOrganizationService createOrganization;
    @Autowired private OrganizationLogoReferenceService references;
    @Autowired private OrganizationLogoReferenceQuery query;
    @Autowired private MongoTemplate mongoTemplate;

    private String organizationId;
    private String representative;

    @BeforeEach
    void setUp() {
        mongoTemplate.dropCollection("organizations");
        mongoTemplate.dropCollection("identity_audit_log");
        AccountId rep = AccountId.generate();
        accounts.save(Account.reconstitute(rep, new Email("rep-" + rep.value() + "@t2.test"), new PasswordHash("hash"),
                AccountStatus.ACTIVE, null, null));
        Organization o = createOrganization.createOrganization(new AuditActor.SystemAuditActor("t2-16"),
                OrganizationType.FOUNDATION, rep, "Fundación Logo");
        organizationId = o.getOrganizationId().value();
        representative = rep.value();
        reset(organizations); // solo cuenta lo que hace cada test
    }

    private AuthorizationPrincipal member(AuthorizationRole... roles) {
        return new AuthorizationPrincipal(representative, organizationId, Set.of(roles), null);
    }

    private Document rawDocument() {
        return mongoTemplate.getCollection("organizations").find(new Document("_id", organizationId)).first();
    }

    // ── T2-16 ──

    @Test
    void aDocumentWithoutTheField_isReadWithoutLogo() {
        assertThat(rawDocument().containsKey("logoObjectKey")).isFalse();

        assertThat(query.logoObjectKeyOf(organizationId)).isEmpty();
        assertThat(query.publicOrganization(organizationId)).isEqualTo(new PublicOrganization("Fundación Logo", false, false));
    }

    @Test
    void replacing_returnsThePreviousReference_andPersistsTheNewOne() {
        assertThat(references.replace(member(AuthorizationRole.REPRESENTATIVE), organizationId, "01KEYA")).isEmpty();
        assertThat(references.replace(member(AuthorizationRole.ADMINISTRATOR), organizationId, "01KEYB")).contains("01KEYA");

        assertThat(rawDocument().getString("logoObjectKey")).isEqualTo("01KEYB");
        assertThat(query.logoObjectKeyOf(organizationId)).contains("01KEYB");
        assertThat(query.publicOrganization(organizationId).hasLogo()).isTrue();
    }

    @Test
    void removing_returnsThePreviousReference_andWithoutLogoIsANoOpWithoutWriting() {
        assertThat(references.remove(member(AuthorizationRole.REPRESENTATIVE), organizationId)).isEmpty();
        verify(organizations, never()).save(any());

        references.replace(member(AuthorizationRole.REPRESENTATIVE), organizationId, "01KEYA");
        assertThat(references.remove(member(AuthorizationRole.REPRESENTATIVE), organizationId)).contains("01KEYA");
        assertThat(rawDocument().get("logoObjectKey")).isNull();
    }

    @Test
    void onlyTheRepresentativeOrAnAdministratorOfThatOrganization_changesTheLogo() {
        AuthorizationPrincipal employee = member(AuthorizationRole.EMPLOYEE);
        AuthorizationPrincipal otherOrganization = new AuthorizationPrincipal(representative, "other-org",
                Set.of(AuthorizationRole.ADMINISTRATOR), null);
        AuthorizationPrincipal platformAdmin = new AuthorizationPrincipal("acct-platform", null, Set.of(),
                PlatformAuthority.ADMINISTRATOR);

        for (AuthorizationPrincipal p : new AuthorizationPrincipal[]{employee, otherOrganization, platformAdmin}) {
            assertThatThrownBy(() -> references.requireLogoManager(p, organizationId))
                    .isInstanceOf(OrganizationAccessDeniedException.class);
            assertThatThrownBy(() -> references.replace(p, organizationId, "01KEYX"))
                    .isInstanceOf(OrganizationAccessDeniedException.class);
            assertThatThrownBy(() -> references.remove(p, organizationId))
                    .isInstanceOf(OrganizationAccessDeniedException.class);
        }
        assertThat(rawDocument().containsKey("logoObjectKey")).isFalse();
    }

    // ── T2-7 (lectura autenticada): miembro con cualquier rol o administrador de plataforma ──

    @Test
    void theAuthenticatedRead_isForAnyMemberOrAPlatformAdministrator() {
        references.replace(member(AuthorizationRole.REPRESENTATIVE), organizationId, "01KEYA");

        assertThat(query.logoObjectKeyForReader(member(AuthorizationRole.EMPLOYEE), organizationId)).contains("01KEYA");
        assertThat(query.logoObjectKeyForReader(new AuthorizationPrincipal("acct-platform", null, Set.of(),
                PlatformAuthority.ADMINISTRATOR), organizationId)).contains("01KEYA");
        assertThatThrownBy(() -> query.logoObjectKeyForReader(new AuthorizationPrincipal(representative, "other-org",
                Set.of(AuthorizationRole.ADMINISTRATOR), null), organizationId))
                .isInstanceOf(OrganizationAccessDeniedException.class);
        assertThatThrownBy(() -> query.logoObjectKeyForReader(new AuthorizationPrincipal("acct-x", null, Set.of(), null),
                organizationId)).isInstanceOf(OrganizationAccessDeniedException.class);
    }

    @Test
    void anUnknownOrganization_hasNoLogo_andNoPublicFacts() {
        assertThat(query.logoObjectKeyOf("no-such-org")).isEmpty();
        assertThat(query.publicOrganization("no-such-org")).isEqualTo(new PublicOrganization(null, false, false));
        assertThat(query.logoObjectKeyForReader(new AuthorizationPrincipal("acct-platform", null, Set.of(),
                PlatformAuthority.ADMINISTRATOR), "no-such-org")).isEmpty();
    }

    // ── T2-13 (parte de identity): la referencia anterior se lee dentro de la transacción ──

    @Test
    void twoSimultaneousReplacements_neverLoseAReference() throws Exception {
        CyclicBarrier bothRead = new CyclicBarrier(2);
        AtomicBoolean firstPass = new AtomicBoolean(true);
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            if (firstPass.get()) {
                try {
                    bothRead.await(5, TimeUnit.SECONDS);
                } finally {
                    firstPass.set(false);
                }
            }
            return result;
        }).when(organizations).findById(any());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Optional<String>> a = executor.submit(() ->
                    references.replace(member(AuthorizationRole.REPRESENTATIVE), organizationId, "01KEYA"));
            Future<Optional<String>> b = executor.submit(() ->
                    references.replace(member(AuthorizationRole.REPRESENTATIVE), organizationId, "01KEYB"));
            Optional<String> previousOfA = a.get(20, TimeUnit.SECONDS);
            Optional<String> previousOfB = b.get(20, TimeUnit.SECONDS);

            String finalKey = rawDocument().getString("logoObjectKey");
            assertThat(finalKey).isIn("01KEYA", "01KEYB");
            String loser = finalKey.equals("01KEYA") ? "01KEYB" : "01KEYA";
            // quien quedó ganó leyendo al otro como anterior: el archivo del otro se borra y nadie lo referencia
            assertThat(finalKey.equals("01KEYA") ? previousOfA : previousOfB).contains(loser);
        } finally {
            executor.shutdownNow();
        }
    }
}
