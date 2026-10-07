package com.traceability.convocatoria.infrastructure.persistence.mongo;

import com.traceability.convocatoria.application.port.out.CampaignAssignmentRepositoryPort;
import com.traceability.convocatoria.application.port.out.ConvocatoriaRepositoryPort;
import com.traceability.convocatoria.application.port.out.DonationIntentRepositoryPort;
import com.traceability.convocatoria.domain.exception.EmployeeAlreadyAssignedException;
import com.traceability.convocatoria.domain.model.CampaignAssignment;
import com.traceability.convocatoria.domain.model.Convocatoria;
import com.traceability.convocatoria.domain.model.ConvocatoriaConfiguration;
import com.traceability.convocatoria.domain.model.DonationIntent;
import com.traceability.convocatoria.domain.model.DonationType;
import com.traceability.convocatoria.domain.model.PaymentMethod;
import com.traceability.convocatoria.domain.model.TargetPolicy;
import com.traceability.convocatoria.domain.model.Visibility;
import com.traceability.convocatoria.infrastructure.persistence.mongo.document.CampaignAssignmentDocument;
import com.traceability.convocatoria.infrastructure.persistence.mongo.document.DonationIntentDocument;
import com.traceability.convocatoria.support.AbstractConvocatoriaMongoIntegrationTest;
import com.traceability.convocatoria.support.ConvocatoriaTestIndexes;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** implementation_plan.md §4.2: índices obligatorios creados y efectivos contra MongoDB real. */
class ConvocatoriaPersistenceIndexesIntegrationTest extends AbstractConvocatoriaMongoIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-10-01T10:00:00Z");

    @Autowired private MongoTemplate mongoTemplate;
    @Autowired private ConvocatoriaRepositoryPort convocatorias;
    @Autowired private CampaignAssignmentRepositoryPort assignments;
    @Autowired private DonationIntentRepositoryPort intents;

    @BeforeEach
    void setUp() {
        ConvocatoriaTestIndexes.resetCollectionsAndIndexes(mongoTemplate);
    }

    @Test
    void activeEmployeeIndexIsUniqueAndPartial() {
        Document index = findIndex(CampaignAssignmentDocument.COLLECTION, CampaignAssignmentDocument.ACTIVE_EMPLOYEE_INDEX);
        assertEquals(Boolean.TRUE, index.getBoolean("unique"));
        assertEquals(new Document("status", "ACTIVE").append("actingRole", "EMPLOYEE"),
                index.get("partialFilterExpression"));
    }

    @Test
    void sameEmployeeCannotHaveTwoActiveEmployeeAssignments() {
        assignments.insert(CampaignAssignment.assignEmployee("a-1", "camp-1", "emp", "admin", NOW));
        assertThrows(EmployeeAlreadyAssignedException.class,
                () -> assignments.insert(CampaignAssignment.assignEmployee("a-2", "camp-2", "emp", "admin", NOW)));
        assertEquals(1, mongoTemplate.getCollection(CampaignAssignmentDocument.COLLECTION).countDocuments());
    }

    @Test
    void removedEmployeeAssignmentDoesNotBlockANewOne() {
        assignments.insert(CampaignAssignment.assignEmployee("a-1", "camp-1", "emp", "admin", NOW));
        assertTrue(assignments.markRemovedIfActive("a-1", NOW.plusSeconds(1)));
        assertDoesNotThrow(() -> assignments.insert(CampaignAssignment.assignEmployee("a-2", "camp-2", "emp", "admin", NOW)));
    }

    @Test
    void administratorCanBeActiveResponsibleOfSeveralCampaigns() {
        assignments.insert(CampaignAssignment.designateAdministrator("a-1", "camp-1", "adm", "adm", NOW));
        assertDoesNotThrow(() -> assignments.insert(
                CampaignAssignment.designateAdministrator("a-2", "camp-2", "adm", "adm", NOW)));
    }

    @Test
    void publicCodeIsUnique() {
        convocatorias.insert(convocatoria("camp-1", "PUB"));
        assertThrows(DuplicateKeyException.class, () -> convocatorias.insert(convocatoria("camp-2", "PUB")));
    }

    @Test
    void campaignRefIsUnique() {
        convocatorias.insert(convocatoria("camp-1", "PUB-1"));
        assertThrows(DuplicateKeyException.class, () -> convocatorias.insert(convocatoria("camp-1", "PUB-2")));
    }

    @Test
    void fundIdIsUniqueAndPaymentSessionIdIndexIsPartial() {
        Document fundIndex = findIndex(DonationIntentDocument.COLLECTION, "uq_fund_id");
        assertEquals(Boolean.TRUE, fundIndex.getBoolean("unique"));
        Document sessionIndex = findIndex(DonationIntentDocument.COLLECTION, "uq_payment_session_id");
        assertEquals(Boolean.TRUE, sessionIndex.getBoolean("unique"));
        assertEquals(new Document("paymentSessionId", new Document("$type", "string")),
                sessionIndex.get("partialFilterExpression"));

        Convocatoria c = convocatoria("camp-1", "PUB-1");
        intents.insert(DonationIntent.create("i-1", "f-1", c, "d", 10, "COP", PaymentMethod.GATEWAY, null));
        // Dos intenciones sin paymentSessionId (P3) no colisionan en el índice parcial.
        assertDoesNotThrow(() -> intents.insert(DonationIntent.create("i-2", "f-2", c, "d", 10, "COP",
                PaymentMethod.GATEWAY, null)));
        assertThrows(DuplicateKeyException.class, () -> intents.insert(DonationIntent.create("i-3", "f-1", c, "d", 10,
                "COP", PaymentMethod.GATEWAY, null)));
    }

    private Document findIndex(String collection, String name) {
        for (Document index : mongoTemplate.getCollection(collection).listIndexes()) {
            if (name.equals(index.getString("name"))) {
                return index;
            }
        }
        throw new AssertionError("Index " + name + " not found in " + collection);
    }

    private static Convocatoria convocatoria(String campaignRef, String publicCode) {
        return Convocatoria.create(campaignRef, "org-1", publicCode, "t", null, Visibility.PUBLIC, NOW,
                NOW.plusSeconds(3600), new ConvocatoriaConfiguration(EnumSet.of(DonationType.MONETARY),
                        Set.of(PaymentMethod.GATEWAY), "COP", 1000L, TargetPolicy.FLEXIBLE, null));
    }
}
