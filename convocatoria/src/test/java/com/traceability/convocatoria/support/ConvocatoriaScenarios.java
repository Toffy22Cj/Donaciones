package com.traceability.convocatoria.support;

import com.traceability.convocatoria.application.command.CreateConvocatoriaCommand;
import com.traceability.convocatoria.application.service.ConvocatoriaLifecycleService;
import com.traceability.convocatoria.domain.model.ConvocatoriaConfiguration;
import com.traceability.convocatoria.domain.model.Visibility;

import java.time.Instant;
import java.util.UUID;

/** Atajos de escenario para los tests de casos de uso. */
public final class ConvocatoriaScenarios {

    // Posteriores a los relojes de los tests (octubre de 2026): crear con fechas pasadas se rechaza (deuda D-2 de la
    // ficha CV-01, plan B6-a)
    public static final Instant START = Instant.parse("2027-01-01T00:00:00Z");
    public static final Instant END = Instant.parse("2027-12-31T00:00:00Z");

    private ConvocatoriaScenarios() {
    }

    public static String createConvocatoria(ConvocatoriaLifecycleService service, String admin, String org,
                                            ConvocatoriaConfiguration configuration) {
        return service.createConvocatoria(new CreateConvocatoriaCommand(UUID.randomUUID().toString(), admin, org,
                "Campaña", null, Visibility.PUBLIC, START, END, configuration)).campaignRef();
    }
}
