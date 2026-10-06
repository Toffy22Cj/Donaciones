package com.traceability.core.application.command;

import com.traceability.contracts.authorization.AuthorizationPrincipal;
import com.traceability.contracts.authorization.AuthorizationRole;
import com.traceability.contracts.authorization.IdentityPrincipalPort;
import com.traceability.core.application.authorization.CommandType;
import com.traceability.core.application.authorization.CrossOrganizationAccessException;
import com.traceability.core.application.authorization.InsufficientRoleException;
import com.traceability.core.application.authorization.OrganizationBoundaryPolicy;
import com.traceability.core.application.authorization.RoleAuthorizationPolicy;
import com.traceability.core.application.port.out.EventStorePort;
import com.traceability.core.application.port.out.ProcessedCommandRepositoryPort;
import com.traceability.core.application.service.TransactionalEventPublisher;
import com.traceability.core.domain.event.HumanActor;
import com.traceability.core.domain.physicalasset.PhysicalAsset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PhysicalAssetCommandServiceAuthorizationTest {

    @Mock private CommandRetryTemplate retryTemplate;
    @Mock private ProcessedCommandRepositoryPort processedCommandRepository;
    @Mock private EventStorePort eventStore;
    @Mock private TransactionalEventPublisher eventPublisher;
    @Mock private IdentityPrincipalPort identityPrincipalPort;

    private RoleAuthorizationPolicy roleAuthorizationPolicy = new RoleAuthorizationPolicy();
    private OrganizationBoundaryPolicy organizationBoundaryPolicy = new OrganizationBoundaryPolicy();

    private PhysicalAssetCommandService service;

    @BeforeEach
    void setUp() {
        when(retryTemplate.execute(any())).thenAnswer((Answer<Object>) invocation -> {
            Supplier<Object> supplier = invocation.getArgument(0);
            return supplier.get();
        });

        service = new PhysicalAssetCommandService(
            retryTemplate,
            processedCommandRepository,
            eventStore,
            eventPublisher,
            roleAuthorizationPolicy,
            organizationBoundaryPolicy,
            identityPrincipalPort
        );
    }

    private PhysicalAsset mockAsset(String assetId, String orgId) {
        PhysicalAsset asset = PhysicalAsset.create(
                assetId, "MEDICINE", BigDecimal.valueOf(100), "BOX", "WH-1", "CUST-1", null, assetId, "ALLOC-1", "SRC-1", orgId, "DON", "DON-REF"
        );
        asset.dispatch("carrier");
        asset.receive("fac", "rec");
        when(eventStore.loadStream(assetId)).thenReturn(asset.getUncommittedEvents());
        return asset;
    }

    // --- deliverAsset ---

    @Test
    void deliverAsset_positive() {
        String assetId = UUID.randomUUID().toString();
        String orgId = "ORG-1";
        mockAsset(assetId, orgId);

        HumanActor actor = new HumanActor("user1");
        AuthorizationPrincipal principal = new AuthorizationPrincipal("user1", orgId, Set.of(AuthorizationRole.EMPLOYEE), null);
        when(identityPrincipalPort.resolvePrincipal("user1")).thenReturn(principal);

        assertDoesNotThrow(() -> {
            service.deliverAsset("cmd-1", assetId, "cust-2", "ben-1", "loc-2", "evid", Instant.now(), actor);
        });

        verify(eventPublisher).appendAndOutbox(eq(assetId), eq("PhysicalAsset"), any(Long.class), any(), eq(actor), any(), eq("cmd-1"));
    }

    @Test
    void deliverAsset_negative_insufficientRole() {
        String assetId = UUID.randomUUID().toString();
        String orgId = "ORG-1";
        mockAsset(assetId, orgId);

        HumanActor actor = new HumanActor("user1");
        // Needs EMPLOYEE, give REPRESENTATIVE
        AuthorizationPrincipal principal = new AuthorizationPrincipal("user1", orgId, Set.of(AuthorizationRole.REPRESENTATIVE), null);
        when(identityPrincipalPort.resolvePrincipal("user1")).thenReturn(principal);

        assertThatThrownBy(() -> {
            service.deliverAsset("cmd-1", assetId, "cust-2", "ben-1", "loc-2", "evid", Instant.now(), actor);
        }).isInstanceOf(InsufficientRoleException.class);

        verify(eventPublisher, never()).appendAndOutbox(any(), any(), any(Long.class), any(), any(), any(), any());
    }

    @Test
    void deliverAsset_negative_crossOrg() {
        String assetId = UUID.randomUUID().toString();
        String orgId = "ORG-1";
        mockAsset(assetId, orgId);

        HumanActor actor = new HumanActor("user1");
        AuthorizationPrincipal principal = new AuthorizationPrincipal("user1", "DIFFERENT-ORG", Set.of(AuthorizationRole.EMPLOYEE), null);
        when(identityPrincipalPort.resolvePrincipal("user1")).thenReturn(principal);

        assertThatThrownBy(() -> {
            service.deliverAsset("cmd-1", assetId, "cust-2", "ben-1", "loc-2", "evid", Instant.now(), actor);
        }).isInstanceOf(CrossOrganizationAccessException.class);

        verify(eventPublisher, never()).appendAndOutbox(any(), any(), any(Long.class), any(), any(), any(), any());
    }
}
