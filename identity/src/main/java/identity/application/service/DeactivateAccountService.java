package identity.application.service;

import com.github.f4b6a3.ulid.UlidCreator;
import identity.application.port.out.AccountRepositoryPort;
import identity.application.port.out.AuditLogPort;
import identity.domain.model.Account;
import identity.domain.model.AccountId;
import identity.domain.model.AuditAction;
import identity.domain.model.AuditActor;
import identity.domain.model.AuditLogEntry;
import com.traceability.contracts.authorization.AuthorizationPrincipal;
import com.traceability.contracts.campaign.CampaignResponsibilityPort;
import identity.application.authorization.AuthorizationAuditActorMapper;
import identity.application.port.out.OrganizationRepositoryPort;
import identity.application.port.out.PasswordHasherPort;
import identity.domain.exception.ActiveCampaignResponsibleException;
import identity.domain.exception.CurrentPasswordIncorrectException;
import identity.domain.exception.PlatformAdministratorDeactivationException;
import identity.domain.exception.RepresentativeTransferRequiredException;
import identity.domain.model.Organization;
import identity.domain.model.Role;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Collections;
import java.util.Objects;

@Service
public class DeactivateAccountService {

    private final AccountRepositoryPort accountRepository;
    private final AuditLogPort auditLogPort;
    private final MongoTransactionRetryHelper retryHelper;
    private final PasswordHasherPort passwordHasher;
    private final OrganizationRepositoryPort organizations;
    private final CampaignResponsibilityPort responsibilities;
    private final AuthorizationAuditActorMapper auditActors;
    private final Clock clock;

    public DeactivateAccountService(AccountRepositoryPort accountRepository, 
                                    AuditLogPort auditLogPort,
                                    MongoTransactionRetryHelper retryHelper,
                                    PasswordHasherPort passwordHasher,
                                    OrganizationRepositoryPort organizations,
                                    CampaignResponsibilityPort responsibilities,
                                    AuthorizationAuditActorMapper auditActors,
                                    ObjectProvider<Clock> clock) {
        this.accountRepository = accountRepository;
        this.auditLogPort = auditLogPort;
        this.retryHelper = retryHelper;
        this.passwordHasher = passwordHasher;
        this.organizations = organizations;
        this.responsibilities = responsibilities;
        this.auditActors = auditActors;
        this.clock = clock.getIfAvailable(Clock::systemUTC);
    }

    /**
     * ADR-050 D6 y Q3 (a) (plan D6 §4.1): la propia cuenta, con la contraseña actual. En una transacción y en este orden
     * (P-D6-2):
     * <ol>
     *   <li>contraseña actual incorrecta → {@link CurrentPasswordIncorrectException}, 400 y nunca 401 (P-D6-1);</li>
     *   <li>administrador de plataforma → {@link PlatformAdministratorDeactivationException} (409);</li>
     *   <li>{@code REPRESENTATIVE} de su organización → {@link RepresentativeTransferRequiredException} (409);</li>
     *   <li>responsable activo de una convocatoria ({@code CampaignResponsibilityPort}) → {@link
     *       ActiveCampaignResponsibleException} (409), con la ventana de concurrencia aceptada en ADR-049 D9.</li>
     * </ol>
     * Si pasa, {@code deactivate(now)} fija {@code credentialsNotBefore} (D4) y se audita {@code ACCOUNT_DEACTIVATED}
     * con el actor del principal. La organización y la membresía no cambian (D6). En cualquier rechazo, nada cambia.
     */
    public void deactivateOwnAccount(AuthorizationPrincipal principal, String currentPlainPassword) {
        AuditActor self = auditActors.toAuditActor(principal);
        AccountId accountId = new AccountId(principal.accountId());

        retryHelper.executeWithRetry(() -> {
            Account account = accountRepository.findById(accountId);
            if (currentPlainPassword == null || !passwordHasher.matches(currentPlainPassword, account.getPasswordHash())) {
                throw new CurrentPasswordIncorrectException("Current password does not match");
            }
            if (account.getPlatformAuthority() != null) {
                throw new PlatformAdministratorDeactivationException(
                        "Cannot deactivate account with platform authority: " + accountId.value());
            }
            if (account.getOrganizationId() != null) {
                Organization organization = organizations.findById(account.getOrganizationId());
                boolean representative = organization.membershipOf(accountId)
                        .map(m -> m.hasRole(Role.REPRESENTATIVE)).orElse(false);
                if (representative) {
                    throw new RepresentativeTransferRequiredException(
                            "The representative must transfer the role before deactivating the account");
                }
                if (!responsibilities.activeActingRoles(account.getOrganizationId().value(), accountId.value()).isEmpty()) {
                    throw new ActiveCampaignResponsibleException("Account is an active campaign responsible");
                }
            }
            Instant now = clock.instant();
            if (account.deactivate(now)) {
                accountRepository.save(account);
                auditLogPort.record(AuditLogEntry.record(UlidCreator.getUlid().toString(), now, self,
                        account.getAccountId(), null, AuditAction.ACCOUNT_DEACTIVATED, Collections.emptyMap()));
            }
        });
    }

    public void deactivateAccount(AuditActor actor, AccountId accountId) {
        Objects.requireNonNull(actor, "actor must not be null");

        retryHelper.executeWithRetry(() -> {
            Account account = accountRepository.findById(accountId);
            Instant now = clock.instant();
            
            // ADR-050 D4 y D6: desactivar revoca los tokens anteriores
            boolean mutated = account.deactivate(now);
            
            if (mutated) {
                accountRepository.save(account);

                AuditLogEntry auditLog = AuditLogEntry.record(
                        UlidCreator.getUlid().toString(),
                        now,
                        actor,
                        account.getAccountId(),
                        null,
                        AuditAction.ACCOUNT_DEACTIVATED,
                        Collections.emptyMap()
                );
                auditLogPort.record(auditLog);
            }
        });
    }
}
