package com.traceability.convocatoria.support;

import java.time.Instant;

import com.traceability.contracts.authorization.AuthorizationRole;
import com.traceability.convocatoria.application.audit.ConvocatoriaAuditEntry;
import com.traceability.convocatoria.application.port.out.CampaignAssignmentRepositoryPort;
import com.traceability.convocatoria.application.port.out.CampaignFundingLedgerRepositoryPort;
import com.traceability.convocatoria.application.port.out.CampaignResponsibleStatePort;
import com.traceability.convocatoria.application.port.out.ConvocatoriaAuditLogPort;
import com.traceability.convocatoria.application.port.out.ConvocatoriaRepositoryPort;
import com.traceability.convocatoria.application.port.out.DonationIntentRepositoryPort;
import com.traceability.convocatoria.application.port.out.ProcessedCommandPort;
import com.traceability.convocatoria.domain.model.ConvocatoriaConfiguration;
import com.traceability.convocatoria.domain.model.DonationType;
import com.traceability.convocatoria.domain.model.OnTargetReached;
import com.traceability.convocatoria.domain.model.PaymentMethod;
import com.traceability.convocatoria.domain.model.TargetPolicy;
import com.traceability.convocatoria.infrastructure.persistence.mongo.document.ProcessedCommandDocument;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Base de los tests de casos de uso: MongoDB real (replica set), índices creados explícitamente, fakes de los
 * puertos externos (IdentityPrincipalPort, X1) y spies sobre los puertos de escritura para las aserciones
 * negativas obligatorias (implementation_plan.md §12.4).
 */
public abstract class AbstractConvocatoriaServiceIntegrationTest extends AbstractConvocatoriaMongoIntegrationTest {

    protected static final String ORG = "org-1";
    protected static final String OTHER_ORG = "org-2";
    protected static final String ADMIN = "admin-1";
    protected static final String ADMIN_2 = "admin-2";
    protected static final String ADMIN_EMPLOYEE = "admin-employee";
    protected static final String EMPLOYEE = "employee-1";
    protected static final String EMPLOYEE_2 = "employee-2";
    protected static final String EMPLOYEE_3 = "employee-3";
    protected static final String REPRESENTATIVE = "representative-1";
    protected static final String OTHER_ORG_ADMIN = "other-admin";
    protected static final String OTHER_ORG_EMPLOYEE = "other-employee";

    @Autowired protected MongoTemplate mongoTemplate;
    @Autowired protected FakeIdentityPrincipalPort identity;
    @Autowired protected FakeOrganizationVerificationPort organizationVerification;
    @Autowired protected MutableClock clock;

    @SpyBean protected ConvocatoriaRepositoryPort convocatorias;
    @SpyBean protected CampaignFundingLedgerRepositoryPort ledgers;
    @SpyBean protected CampaignAssignmentRepositoryPort assignments;
    @SpyBean protected CampaignResponsibleStatePort responsibleState;
    @SpyBean protected DonationIntentRepositoryPort donationIntents;
    @SpyBean protected ConvocatoriaAuditLogPort auditLog;
    @Autowired protected ProcessedCommandPort processedCommands;

    @BeforeEach
    void resetWorld() {
        ConvocatoriaTestIndexes.resetCollectionsAndIndexes(mongoTemplate);
        identity.clear();
        organizationVerification.clear();
        // Antes de las fechas de las convocatorias de los tests (ConvocatoriaScenarios.START = 2026-10-01): crear con
        // fechas pasadas se rechaza (deuda D-2 de la ficha CV-01). Cada test puede moverlo.
        clock.set(Instant.parse("2026-09-30T00:00:00Z"));
        identity.register(ADMIN, ORG, AuthorizationRole.ADMINISTRATOR);
        identity.register(ADMIN_2, ORG, AuthorizationRole.ADMINISTRATOR);
        identity.register(ADMIN_EMPLOYEE, ORG, AuthorizationRole.ADMINISTRATOR, AuthorizationRole.EMPLOYEE);
        identity.register(EMPLOYEE, ORG, AuthorizationRole.EMPLOYEE);
        identity.register(EMPLOYEE_2, ORG, AuthorizationRole.EMPLOYEE);
        identity.register(EMPLOYEE_3, ORG, AuthorizationRole.EMPLOYEE);
        identity.register(REPRESENTATIVE, ORG, AuthorizationRole.REPRESENTATIVE);
        identity.register(OTHER_ORG_ADMIN, OTHER_ORG, AuthorizationRole.ADMINISTRATOR);
        identity.register(OTHER_ORG_EMPLOYEE, OTHER_ORG, AuthorizationRole.EMPLOYEE);
    }

    protected static String newCommandId() {
        return UUID.randomUUID().toString();
    }

    protected static ConvocatoriaConfiguration monetary(TargetPolicy policy, OnTargetReached onTargetReached,
                                                        long target, PaymentMethod... methods) {
        return new ConvocatoriaConfiguration(EnumSet.of(DonationType.MONETARY), Set.of(methods), "COP", target,
                policy, onTargetReached);
    }

    protected static ConvocatoriaConfiguration flexible() {
        return monetary(TargetPolicy.FLEXIBLE, null, 1000L, PaymentMethod.GATEWAY, PaymentMethod.BANK_TRANSFER,
                PaymentMethod.CASH);
    }

    protected static ConvocatoriaConfiguration inKindOnly() {
        return new ConvocatoriaConfiguration(EnumSet.of(DonationType.IN_KIND), null, null, null, null, null);
    }

    protected long count(String collection) {
        return mongoTemplate.getCollection(collection).countDocuments();
    }

    protected long processedCommandCount() {
        return count(ProcessedCommandDocument.COLLECTION);
    }

    protected List<ConvocatoriaAuditEntry> audit(String campaignRef) {
        return auditLog.findByCampaignRef(campaignRef);
    }
}
