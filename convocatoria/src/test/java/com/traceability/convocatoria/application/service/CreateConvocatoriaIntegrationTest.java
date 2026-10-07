package com.traceability.convocatoria.application.service;

import com.traceability.convocatoria.application.audit.ConvocatoriaAuditAction;
import com.traceability.convocatoria.application.audit.ConvocatoriaAuditEntry;
import com.traceability.convocatoria.application.command.CreateConvocatoriaCommand;
import com.traceability.convocatoria.application.command.CreateConvocatoriaResult;
import com.traceability.convocatoria.application.idempotency.CommandType;
import com.traceability.convocatoria.application.idempotency.ProcessedCommand;
import com.traceability.convocatoria.domain.exception.ActorNotInCampaignOrganizationException;
import com.traceability.convocatoria.domain.exception.ActorRoleNotAllowedException;
import com.traceability.convocatoria.domain.exception.CampaignTitleRequiredException;
import com.traceability.convocatoria.domain.exception.IncompleteMonetaryConfigurationException;
import com.traceability.convocatoria.domain.exception.OrganizationNotVerifiedException;
import com.traceability.convocatoria.domain.model.CampaignFundingLedger;
import com.traceability.convocatoria.domain.model.Convocatoria;
import com.traceability.convocatoria.domain.model.ConvocatoriaConfiguration;
import com.traceability.convocatoria.domain.model.ConvocatoriaStatus;
import com.traceability.convocatoria.domain.model.DonationType;
import com.traceability.convocatoria.domain.model.TargetPolicy;
import com.traceability.convocatoria.domain.model.Visibility;
import com.traceability.convocatoria.infrastructure.persistence.mongo.document.CampaignFundingLedgerDocument;
import com.traceability.convocatoria.infrastructure.persistence.mongo.document.ConvocatoriaAuditLogDocument;
import com.traceability.convocatoria.infrastructure.persistence.mongo.document.ConvocatoriaDocument;
import com.traceability.convocatoria.support.AbstractConvocatoriaServiceIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** implementation_plan.md §5 (crear convocatoria), §7.1, §12.3, §12.4, §13.1, §13.2. */
class CreateConvocatoriaIntegrationTest extends AbstractConvocatoriaServiceIntegrationTest {

    static final Instant START = Instant.parse("2026-10-01T00:00:00Z");
    static final Instant END = Instant.parse("2026-12-31T00:00:00Z");

    @Autowired private ConvocatoriaLifecycleService service;

    private CreateConvocatoriaCommand command(String commandId, String actor, ConvocatoriaConfiguration cfg) {
        return new CreateConvocatoriaCommand(commandId, actor, ORG, "Campaña", "desc", Visibility.PUBLIC, START, END, cfg);
    }

    @Test
    void createsMonetaryConvocatoriaWithLedgerAuditAndProcessedCommand() {
        String commandId = newCommandId();
        CreateConvocatoriaResult result = service.createConvocatoria(command(commandId, ADMIN, flexible()));

        Convocatoria stored = convocatorias.findByCampaignRef(result.campaignRef()).orElseThrow();
        assertEquals(result.publicCode(), stored.getPublicCode());
        assertEquals(10, stored.getPublicCode().length());
        assertEquals(ConvocatoriaStatus.OPEN, stored.getStatus());
        assertEquals(1L, stored.getConfigurationVersion());
        assertEquals(ORG, stored.getOrganizationRef());
        assertEquals("Campaña", stored.getTitle());
        assertEquals(START, stored.getStartDate());

        CampaignFundingLedger ledger = ledgers.findByCampaignRef(result.campaignRef()).orElseThrow();
        assertEquals(0L, ledger.clearedAmount());
        assertEquals(1000L, ledger.targetAmount());
        assertEquals(TargetPolicy.FLEXIBLE, ledger.targetPolicy());

        List<ConvocatoriaAuditEntry> entries = audit(result.campaignRef());
        assertEquals(1, entries.size());
        assertEquals(ConvocatoriaAuditAction.CONVOCATORIA_CREATED, entries.get(0).action());
        assertEquals(ADMIN, entries.get(0).actorRef());
        assertEquals(commandId, entries.get(0).commandId());

        ProcessedCommand processed = processedCommands.find(commandId).orElseThrow();
        assertEquals(CommandType.CREATE_CONVOCATORIA, processed.commandType());
        assertEquals(Map.of("campaignRef", result.campaignRef(), "publicCode", result.publicCode()), processed.result());
    }

    @Test
    void inKindOnlyConvocatoriaHasNoLedger() {
        CreateConvocatoriaResult result = service.createConvocatoria(command(newCommandId(), ADMIN, inKindOnly()));
        assertTrue(convocatorias.findByCampaignRef(result.campaignRef()).isPresent());
        assertFalse(ledgers.findByCampaignRef(result.campaignRef()).isPresent());
        verify(ledgers, never()).insert(any());
    }

    @Test
    void mixedConvocatoriaHasLedger() {
        ConvocatoriaConfiguration mixed = new ConvocatoriaConfiguration(EnumSet.of(DonationType.MONETARY,
                DonationType.IN_KIND), Set.of(com.traceability.convocatoria.domain.model.PaymentMethod.GATEWAY), "COP",
                500L, TargetPolicy.STRICT, null);
        CreateConvocatoriaResult result = service.createConvocatoria(command(newCommandId(), ADMIN, mixed));
        assertTrue(ledgers.findByCampaignRef(result.campaignRef()).isPresent());
    }

    @Test
    void duplicateCommandIdReturnsSameCampaignRefAndPublicCodeWithSingleEffect() {
        String commandId = newCommandId();
        CreateConvocatoriaResult first = service.createConvocatoria(command(commandId, ADMIN, flexible()));
        CreateConvocatoriaResult second = service.createConvocatoria(command(commandId, ADMIN, flexible()));

        assertEquals(first, second);
        assertEquals(1, count(ConvocatoriaDocument.COLLECTION));
        assertEquals(1, count(CampaignFundingLedgerDocument.COLLECTION));
        assertEquals(1, count(ConvocatoriaAuditLogDocument.COLLECTION));
        assertEquals(1, processedCommandCount());
    }

    @Test
    void invariantViolationWritesNothingAndLeavesNoClaim() {
        String commandId = newCommandId();
        CreateConvocatoriaCommand invalid = new CreateConvocatoriaCommand(commandId, ADMIN, ORG, " ", null,
                Visibility.PUBLIC, START, END, flexible());

        assertThrows(CampaignTitleRequiredException.class, () -> service.createConvocatoria(invalid));

        verify(convocatorias, never()).insert(any());
        verify(ledgers, never()).insert(any());
        verify(auditLog, never()).append(any());
        assertEquals(0, count(ConvocatoriaDocument.COLLECTION));
        assertEquals(0, processedCommandCount());

        // Reenviar el mismo commandId vuelve a ejecutarse (implementation_plan.md §12.3, reversión).
        CreateConvocatoriaResult result = service.createConvocatoria(command(commandId, ADMIN, flexible()));
        assertTrue(convocatorias.findByCampaignRef(result.campaignRef()).isPresent());
    }

    @Test
    void incompleteMonetaryConfigurationIsRejectedBeforeAnyWrite() {
        assertThrows(IncompleteMonetaryConfigurationException.class, () -> service.createConvocatoria(command(
                newCommandId(), ADMIN, new ConvocatoriaConfiguration(EnumSet.of(DonationType.MONETARY), Set.of(),
                        "COP", 1000L, TargetPolicy.FLEXIBLE, null))));
        verify(convocatorias, never()).insert(any());
        assertEquals(0, processedCommandCount());
    }

    @Test
    void unverifiedOrganizationIsRejected() {
        organizationVerification.markUnverified(ORG);
        assertThrows(OrganizationNotVerifiedException.class,
                () -> service.createConvocatoria(command(newCommandId(), ADMIN, flexible())));
        verify(convocatorias, never()).insert(any());
        verify(auditLog, never()).append(any());
        assertEquals(0, processedCommandCount());
    }

    @Test
    void onlyAdministratorOfTheOrganizationCanCreate() {
        assertThrows(ActorRoleNotAllowedException.class,
                () -> service.createConvocatoria(command(newCommandId(), EMPLOYEE, flexible())));
        assertThrows(ActorRoleNotAllowedException.class,
                () -> service.createConvocatoria(command(newCommandId(), REPRESENTATIVE, flexible())));
        assertThrows(ActorNotInCampaignOrganizationException.class,
                () -> service.createConvocatoria(command(newCommandId(), OTHER_ORG_ADMIN, flexible())));
        verify(convocatorias, never()).insert(any());
        assertEquals(0, processedCommandCount());
        assertEquals(0, count(ConvocatoriaAuditLogDocument.COLLECTION));
    }

    @Test
    void forcedFailureInsideTransactionRollsBackEverythingIncludingClaim() {
        doThrow(new IllegalStateException("forced audit failure")).when(auditLog).append(any());
        String commandId = newCommandId();

        assertThrows(IllegalStateException.class,
                () -> service.createConvocatoria(command(commandId, ADMIN, flexible())));

        assertEquals(0, count(ConvocatoriaDocument.COLLECTION));
        assertEquals(0, count(CampaignFundingLedgerDocument.COLLECTION));
        assertEquals(0, count(ConvocatoriaAuditLogDocument.COLLECTION));
        assertEquals(0, processedCommandCount());
    }

    @Test
    void concurrentSameCommandIdCreatesOnceAndBothReceiveOriginalResult() throws Exception {
        for (int round = 0; round < 3; round++) {
            String commandId = newCommandId();
            CyclicBarrier barrier = new CyclicBarrier(2);
            Callable<CreateConvocatoriaResult> task = () -> {
                barrier.await(10, TimeUnit.SECONDS);
                return service.createConvocatoria(command(commandId, ADMIN, flexible()));
            };
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                List<Future<CreateConvocatoriaResult>> futures = pool.invokeAll(List.of(task, task));
                assertEquals(futures.get(0).get(), futures.get(1).get());
            } finally {
                pool.shutdownNow();
            }
        }
        assertEquals(3, count(ConvocatoriaDocument.COLLECTION));
        assertEquals(3, count(CampaignFundingLedgerDocument.COLLECTION));
        assertEquals(3, count(ConvocatoriaAuditLogDocument.COLLECTION));
        assertEquals(3, processedCommandCount());
    }
}
