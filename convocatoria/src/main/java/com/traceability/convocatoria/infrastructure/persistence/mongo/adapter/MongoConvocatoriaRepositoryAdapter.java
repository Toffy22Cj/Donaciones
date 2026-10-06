package com.traceability.convocatoria.infrastructure.persistence.mongo.adapter;

import com.traceability.convocatoria.application.port.out.ConvocatoriaRepositoryPort;
import com.traceability.convocatoria.domain.model.Convocatoria;
import com.traceability.convocatoria.domain.model.ConvocatoriaConfiguration;
import com.traceability.convocatoria.domain.model.ConvocatoriaStatus;
import com.traceability.convocatoria.infrastructure.persistence.mongo.document.ConvocatoriaDocument;
import com.traceability.convocatoria.infrastructure.persistence.mongo.mapper.ConvocatoriaMapper;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Adaptador Mongo de {@link ConvocatoriaRepositoryPort} (implementation_plan.md §4.2–§4.4; Enmienda §3.2, §3.4).
 */
@Component
public class MongoConvocatoriaRepositoryAdapter implements ConvocatoriaRepositoryPort {

    private final MongoTemplate mongoTemplate;

    public MongoConvocatoriaRepositoryAdapter(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public void insert(Convocatoria convocatoria) {
        mongoTemplate.insert(ConvocatoriaMapper.toDocument(convocatoria));
    }

    @Override
    public Optional<Convocatoria> findByCampaignRef(String campaignRef) {
        return Optional.ofNullable(mongoTemplate.findById(campaignRef, ConvocatoriaDocument.class))
                .map(ConvocatoriaMapper::toDomain);
    }

    @Override
    public Optional<Convocatoria> findByPublicCode(String publicCode) {
        return Optional.ofNullable(mongoTemplate.findOne(Query.query(Criteria.where("publicCode").is(publicCode)),
                ConvocatoriaDocument.class)).map(ConvocatoriaMapper::toDomain);
    }

    @Override
    public boolean updateConfigurationIfVersion(Convocatoria convocatoria, long expectedVersion) {
        ConvocatoriaDocument values = new ConvocatoriaDocument();
        ConvocatoriaConfiguration cfg = convocatoria.getConfiguration();
        ConvocatoriaMapper.applyConfiguration(values, cfg);
        Query query = Query.query(Criteria.where("_id").is(convocatoria.getCampaignRef())
                .and("configurationVersion").is(expectedVersion)
                .and("status").is(ConvocatoriaStatus.OPEN.name()));
        Update update = new Update()
                .set("acceptedDonationTypes", values.acceptedDonationTypes)
                .set("acceptedPaymentMethods", values.acceptedPaymentMethods)
                .set("configurationVersion", convocatoria.getConfigurationVersion());
        setOrUnset(update, "currency", values.currency);
        setOrUnset(update, "targetAmount", values.targetAmount);
        setOrUnset(update, "targetPolicy", values.targetPolicy);
        setOrUnset(update, "onTargetReached", values.onTargetReached);
        return mongoTemplate.updateFirst(query, update, ConvocatoriaDocument.class).getMatchedCount() == 1;
    }

    @Override
    public boolean closeIfOpen(String campaignRef) {
        Query query = Query.query(Criteria.where("_id").is(campaignRef).and("status").is(ConvocatoriaStatus.OPEN.name()));
        Update update = new Update().set("status", ConvocatoriaStatus.CLOSED.name());
        return mongoTemplate.updateFirst(query, update, ConvocatoriaDocument.class).getMatchedCount() == 1;
    }

    private static void setOrUnset(Update update, String field, Object value) {
        if (value == null) {
            update.unset(field);
        } else {
            update.set(field, value);
        }
    }
}
