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
import com.traceability.core.domain.event.DomainEvent;
import com.traceability.core.domain.event.HumanActor;
import com.traceability.core.domain.fund.Fund;
import com.traceability.core.domain.fund.OrganizationRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;

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
class FundCommandServiceAuthorizationTest {

    @Mock private CommandRetryTemplate retryTemplate;
    @Mock private ProcessedCommandRepositoryPort processedCommandRepository;
    @Mock private EventStorePort eventStore;
    @Mock private TransactionalEventPublisher eventPublisher;
    @Mock private IdentityPrincipalPort identityPrincipalPort;

    private RoleAuthorizationPolicy roleAuthorizationPolicy = new RoleAuthorizationPolicy();
    private OrganizationBoundaryPolicy organizationBoundaryPolicy = new OrganizationBoundaryPolicy();

    private FundCommandService service;

    @BeforeEach
    void setUp() {
        when(retryTemplate.execute(any())).thenAnswer((Answer<Object>) invocation -> {
            Supplier<Object> supplier = invocation.getArgument(0);
            return supplier.get();
        });

        service = new FundCommandService(
            retryTemplate,
            processedCommandRepository,
            eventStore,
            eventPublisher,
            roleAuthorizationPolicy,
            organizationBoundaryPolicy,
            identityPrincipalPort
        );
    }

    private Fund createFundWithRequest(String fundId, String orgId) {
        Fund f = Fund.clearFundsGenesis(fundId, new OrganizationRef(orgId), 1000L, "src", "USD", "CAMP", "DON");
        f.requestAllocation("alloc-1", 100L);
        return f;
    }

    // --- requestAllocation ---

    @Test
    void requestAllocation_positive() {
        String fundId = UUID.randomUUID().toString();
        String orgId = "ORG-1";
        Fund f = Fund.clearFundsGenesis(fundId, new OrganizationRef(orgId), 1000L, "src", "USD", "CAMP", "DON");
        when(eventStore.loadStream(fundId)).thenReturn(f.getUncommittedEvents());

        HumanActor actor = new HumanActor("user1");
        AuthorizationPrincipal principal = new AuthorizationPrincipal("user1", orgId, Set.of(AuthorizationRole.ADMINISTRATOR), null);
        when(identityPrincipalPort.resolvePrincipal("user1")).thenReturn(principal);
        // el publicador real devuelve true cuando escribe; un mock devolvería false (= reclamo ajeno, plan P1.1)
        when(eventPublisher.appendAndOutbox(any(), any(), any(Long.class), any(), any(), any(), any(), any())).thenReturn(true);

        assertDoesNotThrow(() -> {
            service.requestAllocation("cmd-1", fundId, "alloc-1", 100L, actor);
        });

        // Plan P1.1: el reclamo guarda el resultado REQUEST_ALLOCATION:fundId:allocationId
        verify(eventPublisher).appendAndOutbox(eq(fundId), eq("Fund"), any(Long.class), any(), eq(actor), any(), eq("cmd-1"),
                eq("REQUEST_ALLOCATION:" + fundId + ":alloc-1"));
    }

    @Test
    void requestAllocation_negative_insufficientRole() {
        String fundId = UUID.randomUUID().toString();
        String orgId = "ORG-1";
        Fund f = Fund.clearFundsGenesis(fundId, new OrganizationRef(orgId), 1000L, "src", "USD", "CAMP", "DON");
        when(eventStore.loadStream(fundId)).thenReturn(f.getUncommittedEvents());

        HumanActor actor = new HumanActor("user1");
        AuthorizationPrincipal principal = new AuthorizationPrincipal("user1", orgId, Set.of(AuthorizationRole.EMPLOYEE), null);
        when(identityPrincipalPort.resolvePrincipal("user1")).thenReturn(principal);

        assertThatThrownBy(() -> {
            service.requestAllocation("cmd-1", fundId, "alloc-1", 100L, actor);
        }).isInstanceOf(InsufficientRoleException.class);

        verify(eventPublisher, never()).appendAndOutbox(any(), any(), any(Long.class), any(), any(), any(), any());
        verify(eventPublisher, never()).appendAndOutbox(any(), any(), any(Long.class), any(), any(), any(), any(), any());
    }

    // --- confirmAllocation ---

    @Test
    void confirmAllocation_positive() {
        String fundId = UUID.randomUUID().toString();
        String orgId = "ORG-1";
        Fund f = createFundWithRequest(fundId, orgId);
        when(eventStore.loadStream(fundId)).thenReturn(f.getUncommittedEvents());

        HumanActor actor = new HumanActor("user1");
        AuthorizationPrincipal principal = new AuthorizationPrincipal("user1", orgId, Set.of(AuthorizationRole.ADMINISTRATOR), null);
        when(identityPrincipalPort.resolvePrincipal("user1")).thenReturn(principal);

        assertDoesNotThrow(() -> {
            service.confirmAllocation("cmd-1", fundId, "alloc-1", actor);
        });

        verify(eventPublisher).appendAndOutbox(eq(fundId), eq("Fund"), any(Long.class), any(), eq(actor), any(), eq("cmd-1"));
    }

    @Test
    void confirmAllocation_negative_crossOrg() {
        String fundId = UUID.randomUUID().toString();
        String orgId = "ORG-1";
        Fund f = createFundWithRequest(fundId, orgId);
        when(eventStore.loadStream(fundId)).thenReturn(f.getUncommittedEvents());

        HumanActor actor = new HumanActor("user1");
        AuthorizationPrincipal principal = new AuthorizationPrincipal("user1", "DIFFERENT-ORG", Set.of(AuthorizationRole.ADMINISTRATOR), null);
        when(identityPrincipalPort.resolvePrincipal("user1")).thenReturn(principal);

        assertThatThrownBy(() -> {
            service.confirmAllocation("cmd-1", fundId, "alloc-1", actor);
        }).isInstanceOf(CrossOrganizationAccessException.class);

        verify(eventPublisher, never()).appendAndOutbox(any(), any(), any(Long.class), any(), any(), any(), any());
    }

    // --- reverseAllocation (Internal Saga Compensation) ---

    @Test
    void reverseAllocation_systemActor_noAuthorizationException() {
        String fundId = UUID.randomUUID().toString();
        String orgId = "ORG-1";
        Fund f = createFundWithRequest(fundId, orgId);
        when(eventStore.loadStream(fundId)).thenReturn(f.getUncommittedEvents());

        com.traceability.core.domain.event.SystemActor actor = new com.traceability.core.domain.event.SystemActor("AssetRegisteredSagaPolicy");

        // IdentityPrincipalPort should not even be called for SystemActor, 
        // and authorize() should bypass without throwing.

        assertDoesNotThrow(() -> {
            service.reverseAllocation("cmd-1", fundId, "alloc-1", "reason", actor);
        });

        verify(eventPublisher).appendAndOutbox(eq(fundId), eq("Fund"), any(Long.class), any(), eq(actor), any(), eq("cmd-1"));
    }
}
