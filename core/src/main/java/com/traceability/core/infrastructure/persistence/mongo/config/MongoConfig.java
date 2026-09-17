package com.traceability.core.infrastructure.persistence.mongo.config;

import com.traceability.core.domain.event.ActorRef;
import com.traceability.core.domain.event.ExternalActor;
import com.traceability.core.domain.event.SystemActor;
import org.bson.Document;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;
import org.springframework.data.convert.WritingConverter;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions;

import java.util.Arrays;

@Configuration
public class MongoConfig {

    @Bean
    public MongoCustomConversions customConversions() {
        return new MongoCustomConversions(Arrays.asList(
                new ActorRefWriteConverter(),
                new ActorRefReadConverter()
        ));
    }

    @WritingConverter
    public static class ActorRefWriteConverter implements Converter<ActorRef, Document> {
        @Override
        public Document convert(ActorRef source) {
            Document doc = new Document();
            if (source instanceof SystemActor sys) {
                doc.put("_class", "SystemActor");
                doc.put("policyName", sys.policyName());
            } else if (source instanceof ExternalActor ext) {
                doc.put("_class", "ExternalActor");
                doc.put("sourceSystem", ext.sourceSystem());
                doc.put("externalEventId", ext.externalEventId());
            }
            return doc;
        }
    }

    @ReadingConverter
    public static class ActorRefReadConverter implements Converter<Document, ActorRef> {
        @Override
        public ActorRef convert(Document source) {
            String type = source.getString("_class");
            if ("SystemActor".equals(type)) {
                return new SystemActor(source.getString("policyName"));
            } else if ("ExternalActor".equals(type)) {
                return new ExternalActor(
                        source.getString("sourceSystem"),
                        source.getString("externalEventId")
                );
            }
            // fallback if it's somehow missing or different
            return null;
        }
    }
}
