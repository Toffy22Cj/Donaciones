package com.traceability.convocatoria.support;

import com.traceability.convocatoria.infrastructure.persistence.mongo.document.CampaignAssignmentDocument;
import com.traceability.convocatoria.infrastructure.persistence.mongo.document.CampaignFundingLedgerDocument;
import com.traceability.convocatoria.infrastructure.persistence.mongo.document.CampaignResponsibleStateDocument;
import com.traceability.convocatoria.infrastructure.persistence.mongo.document.ConvocatoriaAuditLogDocument;
import com.traceability.convocatoria.infrastructure.persistence.mongo.document.ConvocatoriaDocument;
import com.traceability.convocatoria.infrastructure.persistence.mongo.document.DonationIntentDocument;
import com.traceability.convocatoria.infrastructure.persistence.mongo.document.ProcessedCommandDocument;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.data.mongodb.core.index.IndexResolver;
import org.springframework.data.mongodb.core.index.MongoPersistentEntityIndexResolver;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;

import java.util.List;

/**
 * Los tests crean explícitamente los índices de los que dependen sus invariantes (implementation_plan.md §4.2):
 * se resuelven desde las anotaciones de los documentos de producción, así el test prueba esas anotaciones.
 * Colecciones e índices se crean una vez por JVM (no se crean colecciones dentro de transacciones); entre tests solo
 * se vacían. Recrearlas en cada test agotaba los descriptores de fichero de {@code mongod} en el contenedor
 * (WiredTiger "Too many open files" → WT_PANIC, verificado en el log del contenedor durante la Tarea 9).
 */
public final class ConvocatoriaTestIndexes {

    static final List<Class<?>> DOCUMENTS = List.of(
            ConvocatoriaDocument.class,
            CampaignAssignmentDocument.class,
            CampaignResponsibleStateDocument.class,
            CampaignFundingLedgerDocument.class,
            DonationIntentDocument.class,
            ProcessedCommandDocument.class,
            ConvocatoriaAuditLogDocument.class);

    private ConvocatoriaTestIndexes() {
    }

    private static boolean initialized;

    public static synchronized void resetCollectionsAndIndexes(MongoTemplate mongoTemplate) {
        if (initialized) {
            for (Class<?> type : DOCUMENTS) {
                mongoTemplate.getCollection(mongoTemplate.getCollectionName(type)).deleteMany(new org.bson.Document());
            }
            return;
        }
        IndexResolver resolver = new MongoPersistentEntityIndexResolver(
                (MongoMappingContext) mongoTemplate.getConverter().getMappingContext());
        for (Class<?> type : DOCUMENTS) {
            mongoTemplate.dropCollection(type);
            mongoTemplate.createCollection(type);
            IndexOperations ops = mongoTemplate.indexOps(type);
            resolver.resolveIndexFor(type).forEach(ops::ensureIndex);
        }
        initialized = true;
    }
}
