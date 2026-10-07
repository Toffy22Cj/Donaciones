package com.traceability.core.domain.event;

public record ExternalActor(String sourceSystem, String externalEventId) implements ActorRef {
}
