package com.traceability.convocatoria.application.idempotency;

import com.traceability.convocatoria.application.port.out.ProcessedCommandPort;
import com.traceability.convocatoria.domain.exception.CampaignAlreadyClosedException;
import com.traceability.convocatoria.domain.exception.CommandIdReusedForDifferentCommandException;
import com.traceability.convocatoria.infrastructure.persistence.mongo.document.ProcessedCommandDocument;
import com.traceability.convocatoria.support.AbstractConvocatoriaMongoIntegrationTest;
import com.traceability.convocatoria.support.ConvocatoriaTestIndexes;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Registro de comandos procesados del módulo (Enmienda §3.5, N12; implementation_plan.md §7.1, §12.3, §13.1).
 * El "efecto" es una inserción real en una colección dentro de la misma transacción.
 */
class IdempotentCommandExecutorIntegrationTest extends AbstractConvocatoriaMongoIntegrationTest {

    private static final String EFFECTS = "convocatoria_test_effects";

    @Autowired private IdempotentCommandExecutor executor;
    @Autowired private ProcessedCommandPort processedCommands;
    @Autowired private MongoTemplate mongoTemplate;

    @BeforeEach
    void setUp() {
        ConvocatoriaTestIndexes.resetCollectionsAndIndexes(mongoTemplate);
        mongoTemplate.dropCollection(EFFECTS);
        mongoTemplate.createCollection(EFFECTS);
    }

    private Map<String, String> effect(String id) {
        mongoTemplate.insert(new Document("_id", id), EFFECTS);
        return Map.of("ref", id);
    }

    private long effects() {
        return mongoTemplate.getCollection(EFFECTS).countDocuments();
    }

    @Test
    void firstExecutionStoresTypeAndResult() {
        String commandId = UUID.randomUUID().toString();
        Map<String, String> result = executor.execute(commandId, CommandType.CLOSE_CONVOCATORIA, () -> effect("e-1"));

        assertEquals(Map.of("ref", "e-1"), result);
        ProcessedCommand stored = processedCommands.find(commandId).orElseThrow();
        assertEquals(CommandType.CLOSE_CONVOCATORIA, stored.commandType());
        assertEquals(Map.of("ref", "e-1"), stored.result());
    }

    @Test
    void duplicateReturnsOriginalResultWithoutRepeatingEffect() {
        String commandId = UUID.randomUUID().toString();
        AtomicInteger runs = new AtomicInteger();
        Map<String, String> first = executor.execute(commandId, CommandType.CLOSE_CONVOCATORIA, () -> {
            runs.incrementAndGet();
            return effect("e-1");
        });
        Map<String, String> second = executor.execute(commandId, CommandType.CLOSE_CONVOCATORIA, () -> {
            runs.incrementAndGet();
            return effect("e-2");
        });

        assertEquals(first, second);
        assertEquals(1, runs.get());
        assertEquals(1, effects());
    }

    @Test
    void commandIdReusedByAnotherCommandTypeIsRejectedAndOriginalUnchanged() {
        String commandId = UUID.randomUUID().toString();
        executor.execute(commandId, CommandType.CLOSE_CONVOCATORIA, () -> effect("e-1"));

        AtomicInteger runs = new AtomicInteger();
        assertThrows(CommandIdReusedForDifferentCommandException.class,
                () -> executor.execute(commandId, CommandType.CREATE_CONVOCATORIA, () -> {
                    runs.incrementAndGet();
                    return effect("e-2");
                }));

        assertEquals(0, runs.get());
        assertEquals(1, effects());
        ProcessedCommand stored = processedCommands.find(commandId).orElseThrow();
        assertEquals(CommandType.CLOSE_CONVOCATORIA, stored.commandType());
        assertEquals(Map.of("ref", "e-1"), stored.result());
    }

    @Test
    void domainFailureLeavesNoClaimAndResendExecutesAgain() {
        String commandId = UUID.randomUUID().toString();
        AtomicInteger attempts = new AtomicInteger();

        assertThrows(CampaignAlreadyClosedException.class,
                () -> executor.execute(commandId, CommandType.CLOSE_CONVOCATORIA, () -> {
                    attempts.incrementAndGet();
                    effect("partial");
                    throw new CampaignAlreadyClosedException("forced");
                }));

        assertEquals(1, attempts.get(), "domain exceptions are never retried");
        assertFalse(processedCommands.find(commandId).isPresent());
        assertEquals(0, effects(), "the partial effect was rolled back with the claim");
        assertEquals(0, mongoTemplate.getCollection(ProcessedCommandDocument.COLLECTION).countDocuments());

        Map<String, String> result = executor.execute(commandId, CommandType.CLOSE_CONVOCATORIA, () -> effect("e-1"));
        assertEquals(Map.of("ref", "e-1"), result);
        assertEquals(1, effects());
    }

    @Test
    void concurrentSameCommandIdHasSingleEffectAndBothGetOriginalResult() throws Exception {
        for (int round = 0; round < 5; round++) {
            String commandId = UUID.randomUUID().toString();
            CyclicBarrier barrier = new CyclicBarrier(2);
            AtomicInteger runs = new AtomicInteger();
            Callable<Map<String, String>> task = () -> {
                barrier.await(10, TimeUnit.SECONDS);
                return executor.execute(commandId, CommandType.CREATE_CONVOCATORIA, () -> {
                    int n = runs.incrementAndGet();
                    return effect(commandId + "-" + n);
                });
            };
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                List<Future<Map<String, String>>> futures = pool.invokeAll(List.of(task, task));
                Map<String, String> a = futures.get(0).get();
                Map<String, String> b = futures.get(1).get();
                assertEquals(a, b);
                assertTrue(a.get("ref").startsWith(commandId));
            } finally {
                pool.shutdownNow();
            }
            assertEquals(1, mongoTemplate.getCollection(EFFECTS)
                    .countDocuments(new Document("_id", new Document("$regex", "^" + commandId))));
        }
        assertEquals(5, effects());
    }
}
