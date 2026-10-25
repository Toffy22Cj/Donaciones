package com.traceability.app.infrastructure.media;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * El proveedor de imágenes en memoria para los tests que suben fotos de verdad (ADR-051, Tarea 4: el golden path, el
 * semillero y la entrega por HTTP). Sustituye al S3 ficticio de los tests, que nadie escucha, como en
 * {@code OrganizationLogoHttpIntegrationTest}. La limpieza y la huella son las reales.
 */
@TestConfiguration
public class InMemoryMediaStorageConfig {

    @Bean
    @Primary
    InMemoryMediaStorageAdapter inMemoryMediaStorage() {
        return new InMemoryMediaStorageAdapter();
    }
}
