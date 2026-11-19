package com.traceability.core.application.projection;

import com.traceability.core.application.event.EventPayloadRegistry;
import com.traceability.core.domain.event.DomainEventPayload;
import com.traceability.core.infrastructure.projection.ProjectionEventHandler;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * ADR-051 Enmienda 2, Tarea 2, Q-T2-4 (b): el contrato de declaración de {@link ProjectionPayloadContractTest} <b>rompe
 * el CI</b> cuando se registra un payload nuevo que un manejador no declara, también en {@code DonationAuditFactsHandler},
 * cuyo conjunto «ignorado» se calculaba como «el registro menos lo tratado» y por eso nunca fallaba.
 */
class ProjectionPayloadContractDemonstrationTest {

    /** Un payload «nuevo»: registrado, pero que ningún manejador conoce. */
    record BrandNewPayload(String value) implements DomainEventPayload {}

    private static ProjectionEventHandler constructed(Class<? extends ProjectionEventHandler> type) {
        var constructor = type.getDeclaredConstructors()[0];
        Object[] args = java.util.stream.Stream.of(constructor.getParameterTypes()).map(t -> (Object) mock(t)).toArray();
        try {
            constructor.setAccessible(true);
            return (ProjectionEventHandler) constructor.newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<ProjectionEventHandler> handlers() {
        return List.of(constructed(DonationProjectionHandler.class), constructed(DonationAuditFactsHandler.class),
                constructed(PendingAllocationProjectionHandler.class));
    }

    // el registro de hoy está completamente declarado por cada manejador (el contrato pasa)
    @Test
    void today_everyHandlerDeclaresEveryRegisteredPayload() {
        Set<Class<? extends DomainEventPayload>> registered = new HashSet<>(EventPayloadRegistry.registeredPayloads().values());
        for (ProjectionEventHandler handler : handlers()) {
            assertThat(PayloadDeclarations.undeclared(handler, registered)).as(handler.getHandlerName()).isEmpty();
        }
    }

    // y con un payload nuevo registrado, el contrato lo señala en CADA manejador, incluido el de auditoría
    @Test
    void aNewlyRegisteredPayload_isReportedUndeclared_inEveryHandler_includingTheAuditOne() {
        List<Class<? extends DomainEventPayload>> registered = new ArrayList<>(EventPayloadRegistry.registeredPayloads().values());
        registered.add(BrandNewPayload.class);
        for (ProjectionEventHandler handler : handlers()) {
            assertThat(PayloadDeclarations.undeclared(handler, registered)).as(handler.getHandlerName())
                    .containsExactly(BrandNewPayload.class);
        }
    }

    // el conjunto «ignorado» del manejador de auditoría es una lista explícita: no se vuelve a derivar del registro
    @Test
    void theAuditHandler_doesNotDeriveItsIgnoredSetFromTheRegistry() {
        List<String> methods = Arrays.stream(DonationAuditFactsHandler.class.getDeclaredMethods()).map(Method::getName).toList();
        assertThat(methods).doesNotContain("ignoredFromRegistry");
    }
}
