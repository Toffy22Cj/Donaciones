package com.traceability.convocatoria.application.service;

import com.traceability.convocatoria.application.audit.ConvocatoriaAuditAction;
import com.traceability.convocatoria.application.audit.ConvocatoriaAuditEntry;
import com.traceability.convocatoria.application.command.CloseConvocatoriaCommand;
import com.traceability.convocatoria.application.command.CreateConvocatoriaCommand;
import com.traceability.convocatoria.application.command.EditConfigurationCommand;
import com.traceability.convocatoria.application.command.EditConfigurationResult;
import com.traceability.convocatoria.domain.exception.ActorRoleNotAllowedException;
import com.traceability.convocatoria.domain.exception.CampaignAlreadyHasDonationsException;
import com.traceability.convocatoria.domain.exception.CampaignNotFoundException;
import com.traceability.convocatoria.domain.exception.ConfigurationChangeOnClosedCampaignException;
import com.traceability.convocatoria.domain.exception.ConfigurationVersionConflictException;
import com.traceability.convocatoria.domain.exception.MonetaryTermsChangeNotSupportedException;
import com.traceability.convocatoria.domain.model.Convocatoria;
import com.traceability.convocatoria.domain.model.ConvocatoriaConfiguration;
import com.traceability.convocatoria.domain.model.DonationIntent;
import com.traceability.convocatoria.domain.model.DonationType;
import com.traceability.convocatoria.domain.model.PaymentMethod;
import com.traceability.convocatoria.domain.model.TargetPolicy;
import com.traceability.convocatoria.domain.model.Visibility;
import com.traceability.convocatoria.infrastructure.persistence.mongo.document.CampaignFundingLedgerDocument;
import com.traceability.convocatoria.infrastructure.persistence.mongo.document.ConvocatoriaAuditLogDocument;
import com.traceability.convocatoria.support.AbstractConvocatoriaServiceIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** implementation_plan.md §5 (edición directa), §4.4, §7.1, §12.4, §13.1, §13.2; Enmienda §3.2; N2; G1. */
class EditConfigurationIntegrationTest extends AbstractConvocatoriaServiceIntegrationTest {

    @Autowired private ConvocatoriaLifecycleService service;

    private String create(ConvocatoriaConfiguration cfg) {
        return service.createConvocatoria(new CreateConvocatoriaCommand(newCommandId(), ADMIN, ORG, "t", null,
                Visibility.PUBLIC, CreateConvocatoriaIntegrationTest.START, CreateConvocatoriaIntegrationTest.END, cfg))
                .campaignRef();
    }

    private EditConfigurationCommand edit(String commandId, String campaignRef, long expected, ConvocatoriaConfiguration cfg) {
        return new EditConfigurationCommand(commandId, ADMIN, campaignRef, expected, cfg);
    }

    private static ConvocatoriaConfiguration gatewayOnly() {
        return monetary(TargetPolicy.FLEXIBLE, null, 1000L, PaymentMethod.GATEWAY);
    }

    private List<ConvocatoriaAuditAction> actions(String campaignRef) {
        List<ConvocatoriaAuditAction> result = new ArrayList<>();
        for (ConvocatoriaAuditEntry e : audit(campaignRef)) {
            result.add(e.action());
        }
        return result;
    }

    @Test
    void directEditCreatesNewVersionAndAudits() {
        String campaignRef = create(flexible());
        EditConfigurationResult result = service.editConfiguration(edit(newCommandId(), campaignRef, 1, gatewayOnly()));

        assertEquals(2L, result.configurationVersion());
        Convocatoria stored = convocatorias.findByCampaignRef(campaignRef).orElseThrow();
        assertEquals(2L, stored.getConfigurationVersion());
        assertEquals(Set.of(PaymentMethod.GATEWAY), stored.getConfiguration().acceptedPaymentMethods());
        assertEquals(List.of(ConvocatoriaAuditAction.CONVOCATORIA_CREATED, ConvocatoriaAuditAction.CONFIGURATION_EDITED),
                actions(campaignRef));
        assertEquals("2", audit(campaignRef).get(1).details().get("configurationVersion"));
    }

    @Test
    void addingMonetaryCreatesLedgerInSameTransition() {
        String campaignRef = create(inKindOnly());
        assertFalse(ledgers.findByCampaignRef(campaignRef).isPresent());
        ConvocatoriaConfiguration withMoney = new ConvocatoriaConfiguration(EnumSet.of(DonationType.IN_KIND,
                DonationType.MONETARY), Set.of(PaymentMethod.BANK_TRANSFER), "COP", 700L, TargetPolicy.STRICT, null);

        service.editConfiguration(edit(newCommandId(), campaignRef, 1, withMoney));

        assertEquals(700L, ledgers.findByCampaignRef(campaignRef).orElseThrow().targetAmount());
        assertEquals("COP", convocatorias.findByCampaignRef(campaignRef).orElseThrow().getConfiguration().currency());
    }

    @Test
    void removingMonetaryRemovesLedger() {
        String campaignRef = create(new ConvocatoriaConfiguration(EnumSet.of(DonationType.IN_KIND,
                DonationType.MONETARY), Set.of(PaymentMethod.GATEWAY), "COP", 700L, TargetPolicy.STRICT, null));
        assertTrue(ledgers.findByCampaignRef(campaignRef).isPresent());

        service.editConfiguration(edit(newCommandId(), campaignRef, 1, inKindOnly()));

        assertFalse(ledgers.findByCampaignRef(campaignRef).isPresent());
        Convocatoria stored = convocatorias.findByCampaignRef(campaignRef).orElseThrow();
        assertEquals(null, stored.getConfiguration().targetAmount());
        assertEquals(null, stored.getConfiguration().currency());
    }

    @Test
    void editIsRejectedOnceADonationIntentExists() {
        String campaignRef = create(flexible());
        Convocatoria c = convocatorias.findByCampaignRef(campaignRef).orElseThrow();
        donationIntents.insert(DonationIntent.create("i-1", "f-1", c, "donor", 10, "COP", PaymentMethod.GATEWAY, null));
        clearInvocations(convocatorias, auditLog);

        assertThrows(CampaignAlreadyHasDonationsException.class,
                () -> service.editConfiguration(edit(newCommandId(), campaignRef, 1, gatewayOnly())));

        verify(convocatorias, never()).updateConfigurationIfVersion(any(), anyLong());
        verify(auditLog, never()).append(any());
        assertEquals(1L, convocatorias.findByCampaignRef(campaignRef).orElseThrow().getConfigurationVersion());
        assertEquals(1, processedCommandCount());
    }

    @Test
    void closedConvocatoriaRejectsConfigurationChange() {
        String campaignRef = create(flexible());
        service.closeConvocatoria(new CloseConvocatoriaCommand(newCommandId(), ADMIN, campaignRef));
        clearInvocations(convocatorias, auditLog);

        assertThrows(ConfigurationChangeOnClosedCampaignException.class,
                () -> service.editConfiguration(edit(newCommandId(), campaignRef, 1, gatewayOnly())));
        verify(convocatorias, never()).updateConfigurationIfVersion(any(), anyLong());
        verify(auditLog, never()).append(any());
    }

    @Test
    void staleExpectedVersionIsAConflictNeverAnOverwrite() {
        String campaignRef = create(flexible());
        service.editConfiguration(edit(newCommandId(), campaignRef, 1, gatewayOnly()));

        assertThrows(ConfigurationVersionConflictException.class, () -> service.editConfiguration(
                edit(newCommandId(), campaignRef, 1, monetary(TargetPolicy.FLEXIBLE, null, 1000L, PaymentMethod.CASH))));

        Convocatoria stored = convocatorias.findByCampaignRef(campaignRef).orElseThrow();
        assertEquals(2L, stored.getConfigurationVersion());
        assertEquals(Set.of(PaymentMethod.GATEWAY), stored.getConfiguration().acceptedPaymentMethods());
        assertEquals(2, count(ConvocatoriaAuditLogDocument.COLLECTION));
    }

    @Test
    void monetaryTermsCannotBeEdited() {
        String campaignRef = create(flexible());
        clearInvocations(convocatorias);
        assertThrows(MonetaryTermsChangeNotSupportedException.class, () -> service.editConfiguration(
                edit(newCommandId(), campaignRef, 1, monetary(TargetPolicy.STRICT, null, 1000L, PaymentMethod.GATEWAY))));
        verify(convocatorias, never()).updateConfigurationIfVersion(any(), anyLong());
    }

    @Test
    void duplicateCommandIdReturnsOriginalVersionWithSingleEffect() {
        String campaignRef = create(flexible());
        String commandId = newCommandId();
        EditConfigurationResult first = service.editConfiguration(edit(commandId, campaignRef, 1, gatewayOnly()));
        EditConfigurationResult second = service.editConfiguration(edit(commandId, campaignRef, 1, gatewayOnly()));

        assertEquals(first, second);
        assertEquals(2L, convocatorias.findByCampaignRef(campaignRef).orElseThrow().getConfigurationVersion());
        assertEquals(2, count(ConvocatoriaAuditLogDocument.COLLECTION));
    }

    @Test
    void onlyAdministratorCanEdit() {
        String campaignRef = create(flexible());
        clearInvocations(convocatorias);
        assertThrows(ActorRoleNotAllowedException.class, () -> service.editConfiguration(
                new EditConfigurationCommand(newCommandId(), EMPLOYEE, campaignRef, 1, gatewayOnly())));
        assertThrows(ActorRoleNotAllowedException.class, () -> service.editConfiguration(
                new EditConfigurationCommand(newCommandId(), REPRESENTATIVE, campaignRef, 1, gatewayOnly())));
        verify(convocatorias, never()).updateConfigurationIfVersion(any(), anyLong());
    }

    @Test
    void unknownCampaignIsRejected() {
        assertThrows(CampaignNotFoundException.class,
                () -> service.editConfiguration(edit(newCommandId(), "missing", 1, gatewayOnly())));
    }

    @Test
    void forcedFailureRollsBackConfigurationLedgerAuditAndClaim() {
        String campaignRef = create(inKindOnly());
        doThrow(new IllegalStateException("forced ledger failure")).when(ledgers).insert(any());
        String commandId = newCommandId();
        ConvocatoriaConfiguration withMoney = new ConvocatoriaConfiguration(EnumSet.of(DonationType.IN_KIND,
                DonationType.MONETARY), Set.of(PaymentMethod.GATEWAY), "COP", 700L, TargetPolicy.STRICT, null);

        assertThrows(IllegalStateException.class,
                () -> service.editConfiguration(edit(commandId, campaignRef, 1, withMoney)));

        Convocatoria stored = convocatorias.findByCampaignRef(campaignRef).orElseThrow();
        assertEquals(1L, stored.getConfigurationVersion());
        assertEquals(EnumSet.of(DonationType.IN_KIND), stored.getConfiguration().acceptedDonationTypes());
        assertEquals(0, count(CampaignFundingLedgerDocument.COLLECTION));
        assertEquals(1, count(ConvocatoriaAuditLogDocument.COLLECTION));
        assertFalse(processedCommands.find(commandId).isPresent());
    }

    @Test
    void concurrentEditsOnSameVersionApplyOnlyOne() throws Exception {
        String campaignRef = create(flexible());
        CyclicBarrier barrier = new CyclicBarrier(2);
        List<Callable<EditConfigurationResult>> tasks = List.of(
                () -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return service.editConfiguration(edit(newCommandId(), campaignRef, 1, gatewayOnly()));
                },
                () -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return service.editConfiguration(edit(newCommandId(), campaignRef, 1,
                            monetary(TargetPolicy.FLEXIBLE, null, 1000L, PaymentMethod.BANK_TRANSFER)));
                });
        ExecutorService pool = Executors.newFixedThreadPool(2);
        int applied = 0;
        int conflicts = 0;
        try {
            for (Future<EditConfigurationResult> f : pool.invokeAll(tasks)) {
                try {
                    assertEquals(2L, f.get().configurationVersion());
                    applied++;
                } catch (ExecutionException e) {
                    assertInstanceOf(ConfigurationVersionConflictException.class, e.getCause());
                    conflicts++;
                }
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, applied);
        assertEquals(1, conflicts);
        assertEquals(2L, convocatorias.findByCampaignRef(campaignRef).orElseThrow().getConfigurationVersion());
        assertEquals(2, count(ConvocatoriaAuditLogDocument.COLLECTION));
    }
}
