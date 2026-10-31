package com.traceability.convocatoria.infrastructure.persistence.mongo.adapter;

import com.traceability.convocatoria.application.port.out.ConvocatoriaRepositoryPort;
import com.traceability.convocatoria.application.query.PublicCampaignDiscoveryQuery;
import com.traceability.convocatoria.domain.model.Convocatoria;
import com.traceability.convocatoria.domain.model.ConvocatoriaConfiguration;
import com.traceability.convocatoria.domain.model.ConvocatoriaStatus;
import com.traceability.convocatoria.infrastructure.persistence.mongo.document.ConvocatoriaDocument;
import com.traceability.convocatoria.infrastructure.persistence.mongo.mapper.ConvocatoriaMapper;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.regex.Pattern;

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

    @Override
    public Optional<String> replaceCoverObjectKey(String campaignRef, String coverObjectKey) {
        Update update = new Update();
        setOrUnset(update, "coverObjectKey", coverObjectKey);
        // devuelve el documento de antes: su coverObjectKey es la portada anterior
        ConvocatoriaDocument before = mongoTemplate.findAndModify(Query.query(Criteria.where("_id").is(campaignRef)),
                update, ConvocatoriaDocument.class);
        return Optional.ofNullable(before).map(d -> d.coverObjectKey);
    }

    private static void setOrUnset(Update update, String field, Object value) {
        if (value == null) {
            update.unset(field);
        } else {
            update.set(field, value);
        }
    }

    @Override
    public java.util.List<Convocatoria> findByOrganizationRef(String organizationRef, int limit) {
        Query query = Query.query(Criteria.where("organizationRef").is(organizationRef))
                .with(org.springframework.data.domain.Sort.by("_id")).limit(limit);
        return mongoTemplate.find(query, ConvocatoriaDocument.class).stream().map(ConvocatoriaMapper::toDomain).toList();
    }

    /**
     * Ficha R-1 §3 y §5: siempre {@code PUBLIC} y {@code OPEN}; {@code titleSearch} por subcadena literal
     * ({@code Pattern.quote}: ningún carácter de {@code q} es un operador); el tipo, si lo hay, dentro de
     * {@code acceptedDonationTypes}; y la continuación estricta del orden, con {@code publicCode} como desempate.
     */
    @Override
    public java.util.List<Convocatoria> findPublicOpenPage(PublicCampaignDiscoveryQuery.Criteria discovery, int limit) {
        Criteria criteria = Criteria.where("visibility").is("PUBLIC").and("status").is("OPEN");
        if (discovery.textQuery() != null) {
            criteria = criteria.and("titleSearch").regex(Pattern.compile(Pattern.quote(discovery.textQuery())));
        }
        if (discovery.donationType() != null) {
            criteria = criteria.and("acceptedDonationTypes").is(discovery.donationType().name());
        }
        PublicCampaignDiscoveryQuery.Position after = discovery.after();
        Sort order = switch (discovery.sort()) {
            case DEFAULT -> {
                if (after != null) {
                    criteria = criteria.and("publicCode").gt(after.lastPublicCode());
                }
                yield Sort.by("publicCode");
            }
            case ENDING_SOON -> {
                if (after != null) {
                    criteria = criteria.orOperator(Criteria.where("endDate").gt(after.lastSortValue()),
                            Criteria.where("endDate").is(after.lastSortValue()).and("publicCode").gt(after.lastPublicCode()));
                }
                yield Sort.by(Sort.Order.asc("endDate"), Sort.Order.asc("publicCode"));
            }
            case RECENT -> {
                if (after != null) {
                    criteria = criteria.orOperator(Criteria.where("startDate").lt(after.lastSortValue()),
                            Criteria.where("startDate").is(after.lastSortValue()).and("publicCode").gt(after.lastPublicCode()));
                }
                yield Sort.by(Sort.Order.desc("startDate"), Sort.Order.asc("publicCode"));
            }
        };
        Query query = Query.query(criteria).with(order).limit(limit);
        return mongoTemplate.find(query, ConvocatoriaDocument.class).stream().map(ConvocatoriaMapper::toDomain).toList();
    }

    @Override
    public long countByOrganizationStartedBefore(String organizationRef, java.time.Instant before) {
        return mongoTemplate.count(Query.query(Criteria.where("organizationRef").is(organizationRef)
                .and("startDate").lt(before)), ConvocatoriaDocument.class);
    }
}
