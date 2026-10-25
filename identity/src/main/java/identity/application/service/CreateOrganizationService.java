package identity.application.service;

import com.github.f4b6a3.ulid.UlidCreator;
import identity.application.port.out.AccountRepositoryPort;
import identity.application.port.out.AuditLogPort;
import identity.application.port.out.OrganizationRepositoryPort;
import identity.domain.exception.AccountAlreadyBelongsToOrganizationException;
import identity.domain.model.Account;
import identity.domain.model.AccountId;
import identity.domain.model.AuditAction;
import identity.domain.model.AuditActor;
import identity.domain.model.AuditLogEntry;
import identity.domain.model.Organization;
import identity.domain.model.OrganizationType;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

@Service
public class CreateOrganizationService {

    private final AccountRepositoryPort accountRepository;
    private final OrganizationRepositoryPort organizationRepository;
    private final AuditLogPort auditLogPort;
    private final MongoTransactionRetryHelper retryHelper;
    private final ObjectProvider<identity.domain.model.LegalVersions> legalVersions;
    private final java.time.Clock clock;

    public CreateOrganizationService(AccountRepositoryPort accountRepository, 
                                     OrganizationRepositoryPort organizationRepository, 
                                     AuditLogPort auditLogPort,
                                     MongoTransactionRetryHelper retryHelper,
                                     ObjectProvider<identity.domain.model.LegalVersions> legalVersions,
                                     ObjectProvider<java.time.Clock> clock) {
        this.accountRepository = accountRepository;
        this.organizationRepository = organizationRepository;
        this.auditLogPort = auditLogPort;
        this.retryHelper = retryHelper;
        this.legalVersions = legalVersions;
        this.clock = clock.getIfAvailable(java.time.Clock::systemUTC);
    }

    /**
     * Alta por HTTP (ADR-050 D2): exige la versión vigente de los términos de organización. Ausente o distinta →
     * {@link identity.domain.exception.LegalVersionNotCurrentException} ({@code acceptedOrganizationTermsVersion}), sin
     * organización. Se guarda con la cuenta que la aceptó (el representante inicial) y la fecha.
     */
    public Organization createOrganization(AuditActor actor, OrganizationType type, AccountId initialRepresentativeAccountId,
                                           String name, String acceptedOrganizationTermsVersion) {
        if (!legalVersions.getObject().organizationTerms().equals(acceptedOrganizationTermsVersion)) {
            throw new identity.domain.exception.LegalVersionNotCurrentException("acceptedOrganizationTermsVersion");
        }
        return create(actor, type, initialRepresentativeAccountId, name, organization -> organization
                .acceptOrganizationTerms(acceptedOrganizationTermsVersion, initialRepresentativeAccountId, clock.instant()));
    }

    public Organization createOrganization(AuditActor actor, OrganizationType type, AccountId initialRepresentativeAccountId) {
        return createOrganization(actor, type, initialRepresentativeAccountId, null);
    }

    /** Con nombre público opcional (plan B6-a, Q-B6A-1; el nombre lo lee CV-07 por {@code OrganizationPublicNamePort}). */
    public Organization createOrganization(AuditActor actor, OrganizationType type, AccountId initialRepresentativeAccountId,
                                           String name) {
        return create(actor, type, initialRepresentativeAccountId, name, organization -> {});
    }

    private Organization create(AuditActor actor, OrganizationType type, AccountId initialRepresentativeAccountId,
                                String name, java.util.function.Consumer<Organization> beforeSave) {
        Objects.requireNonNull(actor, "actor must not be null");

        return retryHelper.executeWithRetry(() -> {
            Account account = accountRepository.findById(initialRepresentativeAccountId);

            if (account.getOrganizationId() != null) {
                throw new AccountAlreadyBelongsToOrganizationException("Account already belongs to an organization");
            }

            Organization organization = Organization.createOrganization(type, initialRepresentativeAccountId, name);
            beforeSave.accept(organization);
            account.joinOrganization(organization.getOrganizationId());

            organizationRepository.save(organization);
            accountRepository.save(account);

            AuditLogEntry auditLog = AuditLogEntry.record(
                    UlidCreator.getUlid().toString(),
                    Instant.now(),
                    actor,
                    initialRepresentativeAccountId,
                    organization.getOrganizationId(),
                    AuditAction.ORGANIZATION_CREATED,
                    Map.of("type", type.name())
            );
            auditLogPort.record(auditLog);

            return organization;
        });
    }
}
