package com.traceability.convocatoria.application.query;

import com.traceability.convocatoria.application.audit.ConvocatoriaAuditAction;
import com.traceability.convocatoria.application.audit.ConvocatoriaAuditEntry;
import com.traceability.convocatoria.application.command.CloseConvocatoriaCommand;
import com.traceability.convocatoria.application.port.out.ConvocatoriaAuditLogPort;
import com.traceability.convocatoria.application.query.OrganizationCampaignClosuresQuery.CampaignClosure;
import com.traceability.convocatoria.application.service.ConvocatoriaLifecycleService;
import com.traceability.convocatoria.support.AbstractConvocatoriaServiceIntegrationTest;
import com.traceability.convocatoria.support.ConvocatoriaScenarios;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Ficha R-6, G-8, {@code CAMPAIGN_CLOSED} (plan D, P-R6D-2 (a), limitada a las convocatorias cerradas): de las
 * convocatorias de la organización, solo las {@code CLOSED}, con la fecha de cierre de {@link CampaignTimelineQuery}.
 * Solo se lee el registro de auditoría de las cerradas. Ningún puerto de {@code convocatoria} cambia.
 */
class OrganizationCampaignClosuresQueryIntegrationTest extends AbstractConvocatoriaServiceIntegrationTest {

    @Autowired private ConvocatoriaLifecycleService lifecycle;
    @Autowired private OrganizationCampaignClosuresQuery query;
    @SpyBean private ConvocatoriaAuditLogPort auditLog;

    private Instant closedAtOf(String campaignRef) {
        return auditLog.findByCampaignRef(campaignRef).stream()
                .filter(e -> e.action() == ConvocatoriaAuditAction.CONVOCATORIA_CLOSED)
                .map(ConvocatoriaAuditEntry::occurredAt).findFirst().orElseThrow();
    }

    @Test
    void onlyTheClosedCampaignsOfTheOrganization_withTheirClosingDate() {
        String open = ConvocatoriaScenarios.createConvocatoria(lifecycle, ADMIN, ORG, flexible());
        String closed = ConvocatoriaScenarios.createConvocatoria(lifecycle, ADMIN, ORG, flexible());
        lifecycle.closeConvocatoria(new CloseConvocatoriaCommand(newCommandId(), ADMIN, closed));
        String otherClosed = ConvocatoriaScenarios.createConvocatoria(lifecycle, OTHER_ORG_ADMIN, OTHER_ORG, flexible());
        lifecycle.closeConvocatoria(new CloseConvocatoriaCommand(newCommandId(), OTHER_ORG_ADMIN, otherClosed));
        Instant expected = closedAtOf(closed);
        org.mockito.Mockito.clearInvocations(auditLog);

        assertThat(query.closedCampaigns(ORG)).containsExactly(new CampaignClosure(closed, expected));

        // Solo el registro de la cerrada: ni el de la abierta ni el de la otra organización
        verify(auditLog, times(1)).findByCampaignRef(closed);
        verify(auditLog, never()).findByCampaignRef(open);
        verify(auditLog, never()).findByCampaignRef(otherClosed);
    }

    @Test
    void anOrganizationWithoutClosedCampaigns_givesNothing_andReadsNoAuditLog() {
        ConvocatoriaScenarios.createConvocatoria(lifecycle, ADMIN, ORG, flexible());
        org.mockito.Mockito.clearInvocations(auditLog);

        assertThat(query.closedCampaigns(ORG)).isEmpty();
        verify(auditLog, never()).findByCampaignRef(anyString());
    }
}
