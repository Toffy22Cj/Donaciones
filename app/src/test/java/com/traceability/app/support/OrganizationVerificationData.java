package com.traceability.app.support;

import identity.domain.model.Nit;
import identity.domain.model.OrganizationId;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.concurrent.ThreadLocalRandom;

/**
 * [Plan] Ficha R-7, P2 (b) y P-R7-4: la plataforma ya no verifica una organización sin razón social y NIT. Los tests
 * que verifican una organización le ponen antes estos datos, con un NIT <b>sintético</b> (P-R7-5): 9 dígitos al azar y
 * su dígito de verificación bien calculado, distinto en cada llamada para no chocar con el índice único (P5).
 */
public final class OrganizationVerificationData {

    private OrganizationVerificationData() {
    }

    public static void prepare(MongoTemplate mongoTemplate, OrganizationId organizationId) {
        prepare(mongoTemplate, organizationId.value());
    }

    public static void prepare(MongoTemplate mongoTemplate, String organizationId) {
        String nit = syntheticNit();
        mongoTemplate.getCollection("organizations").updateOne(new Document("_id", organizationId),
                new Document("$set", new Document("legalName", "Organización de prueba (sintético)")
                        .append("nit", nit).append("nitCheckDigit", String.valueOf(Nit.checkDigitOf(nit)))));
    }

    /** Un NIT sintético de 9 dígitos que empieza por 9. */
    public static String syntheticNit() {
        return "9" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000));
    }
}
