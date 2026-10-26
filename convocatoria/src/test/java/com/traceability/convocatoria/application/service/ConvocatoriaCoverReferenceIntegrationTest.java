package com.traceability.convocatoria.application.service;

import com.traceability.convocatoria.application.command.CreateConvocatoriaCommand;
import com.traceability.convocatoria.application.query.ConvocatoriaReadPort;
import com.traceability.convocatoria.domain.exception.ActorNotInCampaignOrganizationException;
import com.traceability.convocatoria.domain.exception.ActorRoleNotAllowedException;
import com.traceability.convocatoria.domain.exception.CampaignNotFoundException;
import com.traceability.convocatoria.domain.model.Visibility;
import com.traceability.convocatoria.infrastructure.persistence.mongo.document.ConvocatoriaDocument;
import com.traceability.convocatoria.support.AbstractConvocatoriaServiceIntegrationTest;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-051, Tarea 3 (plan de cierre §T3; P-T3-1 y P-T3-2), en Mongo real: la referencia de la portada en la
 * convocatoria. Solo el {@code ADMINISTRATOR} de su organización la cambia (C1 (a)); reemplazar devuelve la anterior;
 * quitar sin portada no cambia nada; un documento sin el campo se lee sin portada. La referencia viaja en las vistas
 * públicas para {@code app}, sin salir en ninguna respuesta.
 */
class ConvocatoriaCoverReferenceIntegrationTest extends AbstractConvocatoriaServiceIntegrationTest {

    @Autowired private ConvocatoriaLifecycleService lifecycle;
    @Autowired private ConvocatoriaCoverReferenceService covers;
    @Autowired private ConvocatoriaReadPort reads;

    private record Created(String campaignRef, String publicCode) {}

    private Created create() {
        var r = lifecycle.createConvocatoria(new CreateConvocatoriaCommand(newCommandId(), ADMIN, ORG, "t", null,
                Visibility.PUBLIC, CreateConvocatoriaIntegrationTest.START, CreateConvocatoriaIntegrationTest.END, flexible()));
        return new Created(r.campaignRef(), r.publicCode());
    }

    private String storedKey(String campaignRef) {
        Document d = mongoTemplate.getCollection(ConvocatoriaDocument.COLLECTION).find(new Document("_id", campaignRef)).first();
        return d == null ? null : d.getString("coverObjectKey");
    }

    @Test
    void replace_returnsThePrevious_andTheViewsCarryTheReference() {
        Created c = create();

        assertEquals(Optional.empty(), covers.replace(ADMIN, ORG, c.campaignRef(), "key-1"));
        assertEquals(Optional.of("key-1"), covers.replace(ADMIN_2, ORG, c.campaignRef(), "key-2"));

        assertEquals("key-2", storedKey(c.campaignRef()));
        assertEquals("key-2", reads.findPublicByCode(c.publicCode()).orElseThrow().coverObjectKey());
    }

    @Test
    void remove_returnsThePrevious_andWithoutCoverChangesNothing() {
        Created c = create();
        covers.replace(ADMIN, ORG, c.campaignRef(), "key-1");

        assertEquals(Optional.of("key-1"), covers.remove(ADMIN, ORG, c.campaignRef()));
        assertNull(storedKey(c.campaignRef()));
        assertEquals(Optional.empty(), covers.remove(ADMIN, ORG, c.campaignRef()));
    }

    // C1 (a): solo ADMINISTRATOR; otra organización o un campaignRef ajeno o inexistente → las excepciones del 403
    @Test
    void onlyTheAdministratorOfTheOrganization_changesIt() {
        Created c = create();

        assertThrows(ActorRoleNotAllowedException.class, () -> covers.replace(REPRESENTATIVE, ORG, c.campaignRef(), "k"));
        assertThrows(ActorRoleNotAllowedException.class, () -> covers.replace(EMPLOYEE, ORG, c.campaignRef(), "k"));
        assertThrows(ActorNotInCampaignOrganizationException.class,
                () -> covers.replace(OTHER_ORG_ADMIN, ORG, c.campaignRef(), "k"));
        // la ruta dice otra organización que la de la convocatoria: el mismo 403, sin oráculo de existencia
        assertThrows(CampaignNotFoundException.class, () -> covers.replace(OTHER_ORG_ADMIN, OTHER_ORG, c.campaignRef(), "k"));
        assertThrows(CampaignNotFoundException.class, () -> covers.replace(ADMIN, ORG, "no-such-campaign", "k"));
        assertThrows(ActorRoleNotAllowedException.class, () -> covers.remove(REPRESENTATIVE, ORG, c.campaignRef()));
        assertNull(storedKey(c.campaignRef()));
    }

    // T3-8: un documento sin el campo se lee sin portada
    @Test
    void aDocumentWithoutTheField_isReadWithoutCover() {
        Created c = create();

        assertNull(storedKey(c.campaignRef()));
        assertNull(reads.findPublicByCode(c.publicCode()).orElseThrow().coverObjectKey());
        assertTrue(convocatorias.findByCampaignRef(c.campaignRef()).orElseThrow().getCoverObjectKey() == null);
    }
}
