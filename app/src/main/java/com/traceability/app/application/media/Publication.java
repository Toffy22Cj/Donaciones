package com.traceability.app.application.media;

import java.util.List;

/** El historial de decisiones de una copia, en orden, y el estado que dejó la última (E2-5). No incluye bytes. */
public record Publication(boolean published, List<PublicationDecision> decisions) {}
