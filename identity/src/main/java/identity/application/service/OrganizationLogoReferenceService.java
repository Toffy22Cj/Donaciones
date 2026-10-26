package identity.application.service;

import com.traceability.contracts.authorization.AuthorizationPrincipal;
import identity.application.authorization.OrganizationMembersPolicy;
import identity.application.port.out.OrganizationRepositoryPort;
import identity.domain.exception.OrganizationAccessDeniedException;
import identity.domain.exception.OrganizationNotFoundException;
import identity.domain.model.Organization;
import identity.domain.model.OrganizationId;
import identity.application.authorization.PlatformAuthorizationPolicy;
import identity.application.authorization.PlatformCommandType;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * La referencia del logo en {@link Organization} (ADR-051 F9 y F10; plan de la Tarea 2 §4.1). Solo
 * {@code REPRESENTATIVE} o {@code ADMINISTRATOR} de esa organización (F8, {@link OrganizationMembersPolicy}); el resto,
 * el mismo 403 (DD-01). Cambiar la referencia es "el cambio confirmado" de F10: se hace en transacción con reintento y
 * devuelve la referencia anterior <b>leída dentro de la transacción</b>, para que dos reemplazos simultáneos nunca
 * dejen una referencia apuntando a un archivo borrado. Sin auditoría nueva (P-T2-5).
 */
@Service
public class OrganizationLogoReferenceService {

    private final OrganizationMembersPolicy policy;
    private final OrganizationRepositoryPort organizations;
    private final MongoTransactionRetryHelper retryHelper;
    private final PlatformAuthorizationPolicy platformPolicy;

    public OrganizationLogoReferenceService(OrganizationMembersPolicy policy, OrganizationRepositoryPort organizations,
                                            MongoTransactionRetryHelper retryHelper, PlatformAuthorizationPolicy platformPolicy) {
        this.policy = policy;
        this.organizations = organizations;
        this.retryHelper = retryHelper;
        this.platformPolicy = platformPolicy;
    }

    /** Paso 1 de §4.2: el permiso, antes de leer ni guardar ningún byte. */
    public void requireLogoManager(AuthorizationPrincipal principal, String organizationId) {
        policy.requireManager(principal, organizationId);
    }

    /** @return la referencia anterior, si la había */
    public Optional<String> replace(AuthorizationPrincipal principal, String organizationId, String logoObjectKey) {
        requireLogoManager(principal, organizationId);
        return retryHelper.executeWithRetry(() -> {
            Organization organization = load(organizationId);
            Optional<String> previous = organization.replaceLogo(logoObjectKey);
            organizations.save(organization);
            return previous;
        });
    }

    /** @return la referencia anterior; vacío si no había logo, sin escribir nada (P-T2-4) */
    public Optional<String> remove(AuthorizationPrincipal principal, String organizationId) {
        requireLogoManager(principal, organizationId);
        return retryHelper.executeWithRetry(() -> {
            Organization organization = load(organizationId);
            Optional<String> previous = organization.removeLogo();
            previous.ifPresent(ignored -> organizations.save(organization));
            return previous;
        });
    }

    /**
     * ADR-051 F7 (Tarea 5): la plataforma retira un logo que incumple la regla. Solo el administrador de plataforma
     * ({@code REMOVE_MEDIA}); quita la referencia como {@link #remove}, sin el permiso de gestor del logo.
     *
     * @return la referencia anterior; vacío si no había logo, sin escribir nada
     */
    public Optional<String> removeAsPlatform(AuthorizationPrincipal principal, String organizationId) {
        platformPolicy.authorize(principal, PlatformCommandType.REMOVE_MEDIA);
        return retryHelper.executeWithRetry(() -> {
            Organization organization = load(organizationId);
            Optional<String> previous = organization.removeLogo();
            previous.ifPresent(ignored -> organizations.save(organization));
            return previous;
        });
    }

    private Organization load(String organizationId) {
        try {
            return organizations.findById(new OrganizationId(organizationId));
        } catch (OrganizationNotFoundException e) {
            throw new OrganizationAccessDeniedException("Organization not writable");
        }
    }
}
