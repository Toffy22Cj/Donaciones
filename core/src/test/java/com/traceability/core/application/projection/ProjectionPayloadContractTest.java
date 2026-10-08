package com.traceability.core.application.projection;

import com.traceability.core.application.event.EventPayloadRegistry;
import com.traceability.core.domain.event.DomainEventPayload;
import com.traceability.core.infrastructure.projection.ProjectionEventHandler;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Contrato de versiones de las proyecciones (B-PROJ, plan-b-proj.md §3.1): cada manejador declara cada payload de
 * {@link EventPayloadRegistry} como tratado o ignorado, sin solapes y sin clases fuera del registro. Una versión nueva
 * de un evento (p. ej. la 3.0 de D-CAMPAIGN) hace fallar este test hasta que los manejadores la declaren.
 */
class ProjectionPayloadContractTest {

    static Stream<ProjectionEventHandler> handlers() {
        return Stream.of(
                mockConstructed(DonationProjectionHandler.class),
                mockConstructed(DonationAuditFactsHandler.class),
                mockConstructed(PendingAllocationProjectionHandler.class));
    }

    /** Las declaraciones no dependen de colaboradores: se instancia con dependencias simuladas. */
    private static ProjectionEventHandler mockConstructed(Class<? extends ProjectionEventHandler> type) {
        var constructor = type.getDeclaredConstructors()[0];
        Object[] args = Stream.of(constructor.getParameterTypes()).map(t -> (Object) mock(t)).toArray();
        try {
            constructor.setAccessible(true);
            return (ProjectionEventHandler) constructor.newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("handlers")
    void everyRegisteredPayloadIsDeclaredHandledOrIgnored(ProjectionEventHandler handler) {
        Set<Class<? extends DomainEventPayload>> registered = new HashSet<>(EventPayloadRegistry.registeredPayloads().values());
        Set<Class<? extends DomainEventPayload>> declared = new HashSet<>(handler.handledPayloads());
        declared.addAll(handler.ignoredPayloads());

        assertThat(declared).as(handler.getHandlerName() + ": payloads registrados sin declarar")
                .containsAll(registered);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("handlers")
    void noPayloadIsBothHandledAndIgnored(ProjectionEventHandler handler) {
        Set<Class<? extends DomainEventPayload>> overlap = new HashSet<>(handler.handledPayloads());
        overlap.retainAll(handler.ignoredPayloads());

        assertThat(overlap).as(handler.getHandlerName() + ": tratados e ignorados a la vez").isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("handlers")
    void nothingOutsideTheRegistryIsDeclared(ProjectionEventHandler handler) {
        Set<Class<? extends DomainEventPayload>> declared = new HashSet<>(handler.handledPayloads());
        declared.addAll(handler.ignoredPayloads());

        assertThat(EventPayloadRegistry.registeredPayloads().values())
                .as(handler.getHandlerName() + ": declaraciones fuera del registro")
                .containsAll(declared);
    }
}
