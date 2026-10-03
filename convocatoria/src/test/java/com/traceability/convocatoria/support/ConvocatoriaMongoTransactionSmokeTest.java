package com.traceability.convocatoria.support;

import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tarea 1 (implementation_plan.md §15): demuestra que la configuración de test del módulo
 * ejecuta transacciones reales (replica set): un commit persiste y un rollback no deja nada.
 */
class ConvocatoriaMongoTransactionSmokeTest extends AbstractConvocatoriaMongoIntegrationTest {

    private static final String COLLECTION = "convocatoria_tx_smoke";

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        mongoTemplate.dropCollection(COLLECTION);
        mongoTemplate.createCollection(COLLECTION);
    }

    @Test
    void committedTransactionPersists() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                mongoTemplate.insert(new Document("_id", "committed"), COLLECTION));

        assertEquals(1, mongoTemplate.getCollection(COLLECTION).countDocuments());
    }

    @Test
    void rolledBackTransactionLeavesNothing() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        assertThrows(IllegalStateException.class, () -> tx.executeWithoutResult(status -> {
            mongoTemplate.insert(new Document("_id", "rolled-back"), COLLECTION);
            throw new IllegalStateException("forced rollback");
        }));

        assertEquals(0, mongoTemplate.getCollection(COLLECTION).countDocuments());
    }
}
