package com.traceability.app.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexResolver;
import org.springframework.data.mongodb.core.index.MongoPersistentEntityIndexResolver;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;
import org.springframework.data.mongodb.core.mapping.MongoPersistentEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * H-IDX-1 (Carlos, 2026-10-08): al arrancar, y antes de que el servidor web acepte tráfico, crea cada colección
 * {@code @Document} de todos los módulos y cada índice que el código declara ({@code @Indexed}, {@code @CompoundIndex},
 * incluidos los únicos y los parciales), con el mismo resolutor que Spring Data. Sin esto, los índices de los documentos
 * sin repositorio de Spring Data solo se creaban en su primer uso (y nunca en los tests de {@code app}): el único
 * {@code (streamId, sequence)} del event store, el único parcial de {@code campaign_assignments}, los de
 * {@code donation_intents}, etc. Si un índice no se puede crear (por ejemplo, datos que violan un único), la aplicación
 * no arranca. {@code ensureIndex} es idempotente: con el índice ya creado, no cambia nada.
 */
@Component
public class MongoIndexInitializer implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(MongoIndexInitializer.class);
    static final String[] BASE_PACKAGES = {"com.traceability", "identity"};

    private final MongoTemplate mongoTemplate;
    private final MongoMappingContext mappingContext;

    public MongoIndexInitializer(MongoTemplate mongoTemplate, MongoMappingContext mappingContext) {
        this.mongoTemplate = mongoTemplate;
        this.mappingContext = mappingContext;
    }

    @Override
    public void afterPropertiesSet() {
        IndexResolver resolver = new MongoPersistentEntityIndexResolver(mappingContext);
        int indexes = 0;
        List<Class<?>> documents = documentClasses();
        for (Class<?> type : documents) {
            MongoPersistentEntity<?> entity = mappingContext.getRequiredPersistentEntity(type);
            String collection = entity.getCollection();
            if (!mongoTemplate.collectionExists(collection)) {
                mongoTemplate.createCollection(collection);
            }
            for (var definition : resolver.resolveIndexFor(type)) {
                mongoTemplate.indexOps(collection).ensureIndex(definition);
                indexes++;
            }
        }
        log.info("Índices de MongoDB asegurados al arrancar: {} colecciones, {} índices declarados",
                documents.size(), indexes);
    }

    /** Todas las clases {@code @Document} de los módulos, en orden estable. */
    static List<Class<?>> documentClasses() {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Document.class));
        List<Class<?>> types = new ArrayList<>();
        for (String base : BASE_PACKAGES) {
            for (BeanDefinition candidate : scanner.findCandidateComponents(base)) {
                types.add(ClassUtils.resolveClassName(candidate.getBeanClassName(),
                        MongoIndexInitializer.class.getClassLoader()));
            }
        }
        types.sort(Comparator.comparing(Class::getName));
        return types;
    }
}
