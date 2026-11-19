package com.traceability.core.application.projection;

import com.traceability.core.domain.event.DomainEventPayload;
import com.traceability.core.domain.fund.payloads.FundsRefundedPayload;
import com.traceability.core.infrastructure.persistence.mongo.TraceabilityEventDocument;
import com.traceability.core.infrastructure.projection.ProjectionEventHandler;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

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

    // ADR-051 Enmienda 2, Tarea 2, Q-T2-4 (b): el aviso lleva campos estructurados y nunca el payload
    @Test
    void theStructuredWarning_carriesEventTypeSchemaVersionStreamIdAndEventId_neverThePayload() {
        Logger logger = (Logger) LoggerFactory.getLogger(UndeclaredPayloadMonitor.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            UndeclaredPayloadMonitor monitor = new UndeclaredPayloadMonitor();
            TraceabilityEventDocument event = new TraceabilityEventDocument();
            event.setEventId("evt-123");
            event.setStreamId("stream-9");
            event.setEventType("FUNDS_REFUNDED");
            event.setSchemaVersion("7.0");
            event.setPayload(Map.of("reason", "SECRET-PAYLOAD-VALUE"));

            boolean declared = monitor.checkDeclared(handler(Set.of()), REFUND, event);

            assertThat(declared).isFalse();
            assertThat(appender.list).hasSize(1);
            ILoggingEvent line = appender.list.get(0);
            assertThat(line.getLevel()).isEqualTo(Level.ERROR);
            String text = line.getFormattedMessage();
            assertThat(text).contains("handler=H", "eventType=FUNDS_REFUNDED", "schemaVersion=7.0", "streamId=stream-9",
                    "eventId=evt-123", "payloadClass=" + REFUND.getClass().getName());
            // aserción negativa: ni el payload ni sus valores
            assertThat(text).doesNotContain("SECRET-PAYLOAD-VALUE").doesNotContain("reason");
            assertThat(monitor.byHandler()).isEqualTo(Map.of("H", 1L));
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void aDeclaredPayload_withTheEvent_isNotLoggedNorCounted() {
        UndeclaredPayloadMonitor monitor = new UndeclaredPayloadMonitor();
        TraceabilityEventDocument event = new TraceabilityEventDocument();
        event.setEventId("e");

        assertThat(monitor.checkDeclared(handler(Set.of(FundsRefundedPayload.class)), REFUND, event)).isTrue();
        assertThat(monitor.total()).isZero();
    }
}
