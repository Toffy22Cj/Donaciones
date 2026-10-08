package com.traceability.core.application.projection;

import com.traceability.core.domain.event.DomainEventPayload;
import com.traceability.core.domain.fund.payloads.FundsRefundedPayload;
import com.traceability.core.infrastructure.persistence.mongo.TraceabilityEventDocument;
import com.traceability.core.infrastructure.projection.ProjectionEventHandler;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** B-PROJ: un payload no declarado deja log de error y contador por manejador; nunca en silencio, nunca lanza. */
class UndeclaredPayloadMonitorTest {

    private static ProjectionEventHandler handler(Set<Class<? extends DomainEventPayload>> handled) {
        return new ProjectionEventHandler() {
            @Override public void handleEvent(TraceabilityEventDocument eventDoc) { }
            @Override public String getHandlerName() { return "H"; }
            @Override public Set<Class<? extends DomainEventPayload>> handledPayloads() { return handled; }
        };
    }

    private static final FundsRefundedPayload REFUND = new FundsRefundedPayload("r-1", 10L, false, "reason");

    @Test
    void undeclaredPayload_isCountedPerHandler() {
        UndeclaredPayloadMonitor monitor = new UndeclaredPayloadMonitor();

        assertThat(monitor.checkDeclared(handler(Set.of()), REFUND)).isFalse();
        assertThat(monitor.checkDeclared(handler(Set.of()), REFUND)).isFalse();

        assertThat(monitor.total()).isEqualTo(2);
        assertThat(monitor.byHandler()).isEqualTo(Map.of("H", 2L));
    }

    @Test
    void declaredPayload_isNotCounted() {
        UndeclaredPayloadMonitor monitor = new UndeclaredPayloadMonitor();

        assertThat(monitor.checkDeclared(handler(Set.of(FundsRefundedPayload.class)), REFUND)).isTrue();

        assertThat(monitor.total()).isZero();
    }
}
