package com.traceability.app.config;

import com.traceability.app.infrastructure.location.DivipolaLocationCatalog;
import identity.application.port.out.LocationCatalogPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;

/**
 * [Plan] El catálogo DIVIPOLA (ficha R-7 §6, P-R7-1): se carga al arrancar desde el classpath, y sin el archivo la
 * aplicación no arranca (fail-fast).
 */
@Configuration
public class LocationCatalogConfig {

    @Bean
    public LocationCatalogPort locationCatalog() throws IOException {
        ClassPathResource resource = new ClassPathResource(DivipolaLocationCatalog.RESOURCE);
        if (!resource.exists()) {
            throw new IllegalStateException("Falta el catálogo DIVIPOLA en el classpath: " + DivipolaLocationCatalog.RESOURCE);
        }
        return DivipolaLocationCatalog.load(resource.getInputStream());
    }
}
