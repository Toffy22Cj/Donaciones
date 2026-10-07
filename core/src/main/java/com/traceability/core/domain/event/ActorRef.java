package com.traceability.core.domain.event;

public sealed interface ActorRef permits SystemActor, ExternalActor, HumanActor {
}
