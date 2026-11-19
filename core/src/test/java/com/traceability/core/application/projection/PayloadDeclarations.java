package com.traceability.core.application.projection;

import com.traceability.core.domain.event.DomainEventPayload;
import com.traceability.core.infrastructure.projection.ProjectionEventHandler;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/** Lo que comprueba {@link ProjectionPayloadContractTest}, como función, para poder demostrar que falla. */
final class PayloadDeclarations {

    private PayloadDeclarations() {}

    /** Los payloads registrados que el manejador no declara ni como tratados ni como ignorados. */
    static Set<Class<? extends DomainEventPayload>> undeclared(ProjectionEventHandler handler,
            Collection<Class<? extends DomainEventPayload>> registered) {
        Set<Class<? extends DomainEventPayload>> declared = new HashSet<>(handler.handledPayloads());
        declared.addAll(handler.ignoredPayloads());
        Set<Class<? extends DomainEventPayload>> missing = new HashSet<>(registered);
        missing.removeAll(declared);
        return missing;
    }
}
