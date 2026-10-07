package com.traceability.core.application.query;

import com.traceability.contracts.authorization.AuthorizationPrincipal;
import com.traceability.contracts.authorization.AuthorizationRole;
import com.traceability.contracts.authorization.IdentityPrincipalPort;
import com.traceability.core.application.authorization.InsufficientRoleException;
import com.traceability.core.application.authorization.OrganizationBoundaryPolicy;
import com.traceability.core.application.exception.PhysicalAssetNotFoundException;
import com.traceability.core.application.port.out.EventStorePort;
import com.traceability.core.application.port.out.PhysicalAssetOperationalReadPort;
import com.traceability.core.application.port.out.PhysicalAssetOperationalView;
import com.traceability.core.application.port.out.SplitResolutionReadPort;
import com.traceability.core.application.saga.SplitResolutionStatus;
import com.traceability.core.domain.event.DomainEvent;
import com.traceability.core.domain.event.HumanActor;
import com.traceability.core.domain.physicalasset.PhysicalAsset;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Lecturas de activos para un empleado (plan B6-c §2.1, DD-13). Rehidrata del event store, como
 * {@link SplitResolutionQueryService}: los activos del Camino B no están en ninguna proyección. Exige {@code EMPLOYEE}
 * y la frontera de organización (matriz §4b), comprobadas después de cargar el activo porque la organización sale de
 * él; un activo inexistente es {@link PhysicalAssetNotFoundException}, que hacia fuera responde como uno ajeno (DD-12).
 */
@Service
public class PhysicalAssetOperationalQueryService implements PhysicalAssetOperationalReadPort {

    private final EventStorePort eventStore;
    private final IdentityPrincipalPort identityPrincipalPort;
    private final OrganizationBoundaryPolicy organizationBoundaryPolicy;
    private final SplitResolutionReadPort splitResolutions;

    public PhysicalAssetOperationalQueryService(EventStorePort eventStore, IdentityPrincipalPort identityPrincipalPort,
                                                OrganizationBoundaryPolicy organizationBoundaryPolicy,
                                                SplitResolutionReadPort splitResolutions) {
        this.eventStore = eventStore;
        this.identityPrincipalPort = identityPrincipalPort;
        this.organizationBoundaryPolicy = organizationBoundaryPolicy;
        this.splitResolutions = splitResolutions;
    }

    @Override
    public PhysicalAssetOperationalView findOperationalView(String assetId, HumanActor actor) {
        PhysicalAsset asset = loadAuthorized(assetId, actor);
        return new PhysicalAssetOperationalView(assetId, asset.getLifecycleStatus().name(), asset.getCustodianRef(),
                asset.getCurrentLocation(), asset.getQuantity(), asset.getUnitOfMeasure(), asset.getCampaignRef());
    }

    @Override
    public Optional<SplitResolutionStatus> findSplitStatus(String parentAssetId, String childAssetId, HumanActor actor) {
        loadAuthorized(parentAssetId, actor);
        return splitResolutions.findStatus(parentAssetId, childAssetId);
    }

    private PhysicalAsset loadAuthorized(String assetId, HumanActor actor) {
        List<DomainEvent> events = eventStore.loadStream(assetId);
        if (events.isEmpty()) {
            throw new PhysicalAssetNotFoundException(assetId);
        }
        PhysicalAsset asset = PhysicalAsset.rehydrate(assetId, events.stream().map(DomainEvent::payload).toList(),
                events.size());
        AuthorizationPrincipal principal = identityPrincipalPort.resolvePrincipal(actor.accountId());
        organizationBoundaryPolicy.assertBelongs(principal.organizationId(), asset.getOrganizationRef());
        if (principal.roles() == null || !principal.roles().contains(AuthorizationRole.EMPLOYEE)) {
            throw new InsufficientRoleException("Reading a PhysicalAsset requires " + AuthorizationRole.EMPLOYEE);
        }
        return asset;
    }
}
