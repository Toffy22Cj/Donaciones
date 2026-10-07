package identity.application.service;

import com.traceability.contracts.authorization.AuthorizationPrincipal;
import identity.application.authorization.PlatformAuthorizationPolicy;
import identity.application.authorization.PlatformCommandType;
import identity.application.port.out.AccountRepositoryPort;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/**
 * Administradores de plataforma (autorización (3) de Carlos, §3.2; matriz §1): {@code accountId} y estado, <b>sin
 * email</b> (como DD-55). Solo otro administrador de plataforma. Una página con tope; lectura sin transacción.
 */
@Service
public class PlatformAdministratorsQuery {

    public static final int LIMIT = 100;

    public record AdministratorView(String accountId, String status) {}

    private final AccountRepositoryPort accounts;
    private final PlatformAuthorizationPolicy policy;

    public PlatformAdministratorsQuery(AccountRepositoryPort accounts, PlatformAuthorizationPolicy policy) {
        this.accounts = accounts;
        this.policy = policy;
    }

    /** Solo un administrador de plataforma; quien llama lo usa antes de interpretar la entrada (403 antes que 400). */
    public void authorize(AuthorizationPrincipal principal) {
        Objects.requireNonNull(principal, "principal must not be null");
        policy.authorize(principal, PlatformCommandType.READ_PLATFORM_ADMINISTRATORS);
    }

    public List<AdministratorView> administrators(AuthorizationPrincipal principal) {
        authorize(principal);
        return accounts.findPlatformAdministrators(LIMIT).stream()
                .map(a -> new AdministratorView(a.getAccountId().value(), a.getStatus().name())).toList();
    }
}
