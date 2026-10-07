package com.traceability.core.infrastructure.persistence.mongo.config;

import com.traceability.core.domain.event.ActorRef;
import com.traceability.core.domain.event.ExternalActor;
import com.traceability.core.domain.event.HumanActor;
import com.traceability.core.domain.event.SystemActor;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MongoConfigHumanActorTest {

    private final MongoConfig.ActorRefWriteConverter writeConverter = new MongoConfig.ActorRefWriteConverter();
    private final MongoConfig.ActorRefReadConverter readConverter = new MongoConfig.ActorRefReadConverter();

    @Test
    void writeHumanActor_returnsCorrectDocument() {
        HumanActor humanActor = new HumanActor("acc-test-1");
        Document document = writeConverter.convert(humanActor);

        assertEquals("HumanActor", document.getString("_class"));
        assertEquals("acc-test-1", document.getString("accountId"));
    }

    @Test
    void readHumanActor_returnsCorrectObject() {
        Document document = new Document();
        document.put("_class", "HumanActor");
        document.put("accountId", "acc-test-1");

        ActorRef actorRef = readConverter.convert(document);

        assertTrue(actorRef instanceof HumanActor);
        HumanActor humanActor = (HumanActor) actorRef;
        assertEquals("acc-test-1", humanActor.accountId());
    }

    @Test
    void readRetrocompatibility_systemActor_returnsCorrectObject() {
        Document document = new Document();
        document.put("_class", "SystemActor");
        document.put("policyName", "test-policy");

        ActorRef actorRef = readConverter.convert(document);

        assertTrue(actorRef instanceof SystemActor);
        SystemActor systemActor = (SystemActor) actorRef;
        assertEquals("test-policy", systemActor.policyName());
    }

    @Test
    void readRetrocompatibility_externalActor_returnsCorrectObject() {
        Document document = new Document();
        document.put("_class", "ExternalActor");
        document.put("sourceSystem", "sys-1");
        document.put("externalEventId", "evt-1");

        ActorRef actorRef = readConverter.convert(document);

        assertTrue(actorRef instanceof ExternalActor);
        ExternalActor externalActor = (ExternalActor) actorRef;
        assertEquals("sys-1", externalActor.sourceSystem());
        assertEquals("evt-1", externalActor.externalEventId());
    }

    @Test
    void readUnknownActor_returnsNullAsFallback() {
        Document document = new Document();
        document.put("_class", "UnknownClass");
        
        ActorRef actorRef = readConverter.convert(document);
        
        assertNull(actorRef);
    }

    /*
     * Nota sobre el test de escritura "Unknown Write":
     * La rama defensiva del ActorRefWriteConverter para un tipo no reconocido lanza UnknownActorRefTypeException.
     * Esta aserción se verifica por inspección del código y queda documentada como no-ejercitable en un test normal,
     * dado que ActorRef mantiene exclusivamente las variantes selladas actuales (permits SystemActor, ExternalActor, HumanActor).
     * Por el diseño sealed interface, el compilador impide instanciar o inyectar un ActorRef desconocido 
     * sin incurrir en manipulaciones de bytecode o reflection insegura, técnicas prohibidas por las políticas.
     * Si en el futuro aparece una nueva variante no reconocida por el converter, deberá añadirse la prueba
     * ejecutable correspondiente antes de cerrar ese cambio.
     */
}
