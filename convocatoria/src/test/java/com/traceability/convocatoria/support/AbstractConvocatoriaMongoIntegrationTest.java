package com.traceability.convocatoria.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base de los tests de integración del módulo: MongoDB real en modo replica set
 * (las transacciones multi-documento lo exigen; implementation_plan.md §4.4, §12.3).
 * Un único contenedor por JVM, compartido por todas las clases de test.
 */
@SpringBootTest(classes = ConvocatoriaTestApplication.class)
public abstract class AbstractConvocatoriaMongoIntegrationTest {

    protected static final MongoDBContainer MONGO =
            new MongoDBContainer(DockerImageName.parse("mongo:6.0"));

    static {
        MONGO.start();
    }

    @DynamicPropertySource
    static void mongoProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", MONGO::getReplicaSetUrl);
    }
}
