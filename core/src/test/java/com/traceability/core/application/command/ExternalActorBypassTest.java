package com.traceability.core.application.command;

import com.traceability.core.application.authorization.OrganizationBoundaryPolicy;
import com.traceability.core.application.authorization.RoleAuthorizationPolicy;
import com.traceability.core.application.port.out.EventStorePort;
import com.traceability.core.application.port.out.ProcessedCommandRepositoryPort;
import com.traceability.core.application.service.TransactionalEventPublisher;
import com.traceability.core.domain.event.ExternalActor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class ExternalActorBypassTest {

    private PhysicalAssetCommandService service;

    @BeforeEach
    void setUp() {
        CommandRetryTemplate retryTemplate = new CommandRetryTemplate();
        ProcessedCommandRepositoryPort processedCommandRepository = Mockito.mock(ProcessedCommandRepositoryPort.class);
        EventStorePort eventStore = Mockito.mock(EventStorePort.class);
        TransactionalEventPublisher eventPublisher = Mockito.mock(TransactionalEventPublisher.class);
        RoleAuthorizationPolicy roleAuthorizationPolicy = Mockito.mock(RoleAuthorizationPolicy.class);
        OrganizationBoundaryPolicy organizationBoundaryPolicy = Mockito.mock(OrganizationBoundaryPolicy.class);

        service = new PhysicalAssetCommandService(
                retryTemplate,
                processedCommandRepository,
                eventStore,
                eventPublisher,
                roleAuthorizationPolicy,
                organizationBoundaryPolicy
        );
    }

    @Test
    @DisplayName("ExternalActor should bypass authorization policies (ADR-032/D6) - Synthetic Test")
    void externalActorBypassSyntheticTest() {
        ExternalActor externalActor = new ExternalActor("Source-System-A", "event-id-123");
        
        assertDoesNotThrow(() -> {
            service.registerPhysicalAsset(
                    UUID.randomUUID().toString(),
                    "org-1",
                    "LAPTOP",
                    BigDecimal.TEN,
                    "UNIDADES",
                    "custodian-1",
                    "location-1",
                    null,
                    null,
                    externalActor
            );
        }, "ExternalActor should bypass authorization checks without throwing exceptions");
    }
}
