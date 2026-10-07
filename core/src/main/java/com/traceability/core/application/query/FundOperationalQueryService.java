package com.traceability.core.application.query;

import com.traceability.contracts.authorization.AuthorizationPrincipal;
import com.traceability.contracts.authorization.AuthorizationRole;
import com.traceability.contracts.authorization.IdentityPrincipalPort;
import com.traceability.core.application.authorization.InsufficientRoleException;
import com.traceability.core.application.authorization.OrganizationBoundaryPolicy;
import com.traceability.core.application.port.out.EventStorePort;
import com.traceability.core.application.port.out.FundDirectoryPort;
import com.traceability.core.application.port.out.FundOperationalReadPort;
import com.traceability.core.domain.event.DomainEvent;
import com.traceability.core.domain.event.HumanActor;
import com.traceability.core.domain.fund.Fund;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Lectura de los fondos de una organización (plan P1.1, DD-31). Del event store, como la lectura operacional de
 * activos (DD-13): la proyección de donaciones no guarda la organización. Autoriza antes de leer nada.
 */
@Service
public class FundOperationalQueryService implements FundOperationalReadPort {

    /** Una página, como el historial de la cuenta (DD-21). */
    static final int MAX_FUNDS = 200;

    private final FundDirectoryPort directory;
    private final EventStorePort eventStore;
    private final IdentityPrincipalPort identityPrincipalPort;
    private final OrganizationBoundaryPolicy organizationBoundaryPolicy;

    public FundOperationalQueryService(FundDirectoryPort directory, EventStorePort eventStore,
                                       IdentityPrincipalPort identityPrincipalPort,
                                       OrganizationBoundaryPolicy organizationBoundaryPolicy) {
        this.directory = directory;
        this.eventStore = eventStore;
        this.identityPrincipalPort = identityPrincipalPort;
        this.organizationBoundaryPolicy = organizationBoundaryPolicy;
    }

    @Override
    public List<FundView> listForOrganization(String organizationRef, HumanActor actor) {
        AuthorizationPrincipal principal = identityPrincipalPort.resolvePrincipal(actor.accountId());
        organizationBoundaryPolicy.assertBelongs(principal.organizationId(), organizationRef);
        if (principal.roles() == null || (!principal.roles().contains(AuthorizationRole.ADMINISTRATOR)
                && !principal.roles().contains(AuthorizationRole.EMPLOYEE))) {
            throw new InsufficientRoleException("Reading funds requires ADMINISTRATOR or EMPLOYEE");
        }
        return directory.findFundIdsByOrganization(organizationRef, MAX_FUNDS).stream().map(this::view).toList();
    }

    private FundView view(String fundId) {
        List<DomainEvent> events = eventStore.loadStream(fundId);
        Fund fund = Fund.rehydrate(fundId, events.stream().map(DomainEvent::payload).toList(), events.size());
        return new FundView(fundId, fund.getCampaignRef(), fund.getCurrency(), fund.getClearedAmount(),
                fund.getAvailableAmount(), fund.getAllocations().stream()
                .map(a -> new AllocationItem(a.allocationId(), a.amount(), a.status().name())).toList());
    }
}
