package com.traceability.convocatoria.infrastructure.persistence.mongo;

import com.traceability.convocatoria.application.query.SearchTextNormalizer;
import com.traceability.convocatoria.infrastructure.persistence.mongo.document.ConvocatoriaDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * Relleno de {@code titleSearch} en las convocatorias que no lo tienen (ficha R-1 §4; plan R-1 §2). Se ejecuta al
 * arrancar, antes de servir peticiones. Cada escritura es condicional ({@code _id} y {@code titleSearch} inexistente):
 * una convocatoria creada mientras corre ya llega con el campo y no se pisa. Idempotente: con todas rellenas, no
 * escribe nada. Solo registra cuántas rellenó.
 */
@Component
public class ConvocatoriaTitleSearchBackfill implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(ConvocatoriaTitleSearchBackfill.class);

    private final MongoTemplate mongoTemplate;

    public ConvocatoriaTitleSearchBackfill(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public void afterPropertiesSet() {
        long filled = backfill();
        if (filled > 0) {
            log.info("convocatoria.title-search.backfill filled={}", filled);
        }
    }

    /** @return cuántas convocatorias rellenó */
    public long backfill() {
        Query missing = Query.query(Criteria.where("titleSearch").exists(false));
        missing.fields().include("_id", "title");
        long filled = 0;
        for (ConvocatoriaDocument d : mongoTemplate.find(missing, ConvocatoriaDocument.class)) {
            Query stillMissing = Query.query(Criteria.where("_id").is(d.campaignRef).and("titleSearch").exists(false));
            Update update = new Update().set("titleSearch", SearchTextNormalizer.normalize(d.title));
            filled += mongoTemplate.updateFirst(stillMissing, update, ConvocatoriaDocument.class).getModifiedCount();
        }
        return filled;
    }
}
