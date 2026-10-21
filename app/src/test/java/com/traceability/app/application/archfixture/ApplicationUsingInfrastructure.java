package com.traceability.app.application.archfixture;

import com.traceability.app.infrastructure.media.MediaObjectRepository;

/**
 * Violación deliberada para {@code MediaArchitectureTest}: una clase de {@code app.application} que depende de la
 * infraestructura de {@code app} (y, a través del repositorio, de Spring Data).
 */
public class ApplicationUsingInfrastructure {

    MediaObjectRepository repository;
}
