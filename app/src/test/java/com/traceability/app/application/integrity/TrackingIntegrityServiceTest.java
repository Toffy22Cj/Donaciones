package com.traceability.app.application.integrity;

import com.traceability.app.application.integrity.TrackingIntegrityService.BatchIntegrity;
import com.traceability.app.application.integrity.TrackingIntegrityService.DonationIntegrity;
import com.traceability.app.application.integrity.TrackingIntegrityService.Reason;
import com.traceability.app.application.integrity.TrackingIntegrityService.Result;
import com.traceability.contracts.SequenceRange;
import com.traceability.core.application.port.out.DonationReadModel;
import com.traceability.core.application.port.out.DonationReadPort;
import com.traceability.core.application.port.out.EventBatchMembershipPort;
import com.traceability.core.application.port.out.EventBatchMembershipPort.EventMembership;
import com.traceability.core.application.port.out.LogisticsReadItem;
import com.traceability.crypto.application.port.in.IntegrityVerificationPort;
import com.traceability.crypto.application.port.out.MerkleBatchRepositoryPort;
import com.traceability.crypto.domain.AnchorStatus;
import com.traceability.crypto.domain.MerkleBatch;
import com.traceability.crypto.domain.StreamIdentity;
import com.traceability.crypto.domain.VerificationResult;
import com.traceability.crypto.domain.VerificationStatus;
import com.traceability.crypto.domain.exception.LegacyBatchLeafHashesUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Encargo 6, P4: la integridad de una donación en el seguimiento. Solo lotes con eventos de la donación (su fondo y
 * sus activos), el resultado de {@code verifyBatch} con su motivo, nada de otras donaciones y la caché del resultado.
 */
class TrackingIntegrityServiceTest {

    static final Instant T0 = Instant.parse("2026-10-21T15:00:00Z");

    static class MovableClock extends Clock {
        Instant now = T0;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    final DonationReadPort donations = mock(DonationReadPort.class);
    final EventBatchMembershipPort membership = mock(EventBatchMembershipPort.class);
    final MerkleBatchRepositoryPort batches = mock(MerkleBatchRepositoryPort.class);
    final IntegrityVerificationPort verification = mock(IntegrityVerificationPort.class);
    final MovableClock clock = new MovableClock();
    TrackingIntegrityService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ObjectProvider<Clock> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable(any())).thenReturn(clock);
        service = new TrackingIntegrityService(donations, membership, batches, verification, provider);
        when(donations.findByFundId("FUND-1")).thenReturn(Optional.of(new DonationReadModel("FUND-1", "COP", "CAMP-1",
                100, 100, 0, 0, 0, "ACTIVE", List.of(new LogisticsReadItem("ASSET-1", "RECEIVED", "kit", "unit",
                java.math.BigDecimal.ONE, "zona", null)))));
    }

    static MerkleBatch batch(String id, AnchorStatus status, Instant createdAt, Map<String, SequenceRange> coverage) {
        return new MerkleBatch(id, coverage, "root-" + id, List.of("h1", "h2"), createdAt, status, "ganache-local",
                "0xcontract", 7L, status == AnchorStatus.ANCHORED ? "0xtx-" + id : null, createdAt,
                status == AnchorStatus.ANCHORED ? createdAt.plusSeconds(60) : null,
                status == AnchorStatus.ANCHORED ? 42L : null, null);
    }

    /** B-OLD: fondo seq 1 (anclado). B-NEW: fondo seq 2 y activo seq 1, con eventos de OTRA donación (enviado). */
    void twoBatches() {
        when(membership.findMembership(any(), anyInt())).thenReturn(List.of(
                new EventMembership("FUND-1", 1, "B-OLD"),
                new EventMembership("FUND-1", 2, "B-NEW"),
                new EventMembership("FUND-1", 3, null),
                new EventMembership("ASSET-1", 1, "B-NEW")));
        when(batches.findByBatchId("B-OLD")).thenReturn(Optional.of(batch("B-OLD", AnchorStatus.ANCHORED, T0.minusSeconds(600),
                Map.of("FUND-1", new SequenceRange(1, 1), "OTHER-FUND", new SequenceRange(1, 4)))));
        when(batches.findByBatchId("B-NEW")).thenReturn(Optional.of(batch("B-NEW", AnchorStatus.SUBMITTED, T0.minusSeconds(60),
                Map.of("FUND-1", new SequenceRange(2, 2), "ASSET-1", new SequenceRange(1, 1), "OTHER-FUND", new SequenceRange(5, 5)))));
    }

    @Test
    void onlyTheBatchesWithEventsOfThisDonation_withTheirAnchorData_andAMatch() {
        twoBatches();
        when(verification.verifyBatch("B-OLD")).thenReturn(new VerificationResult(VerificationStatus.MATCH, "root-B-OLD",
                "root-B-OLD", List.of(), true));

        DonationIntegrity d = service.integrityOf("FUND-1");

        verify(membership).findMembership(org.mockito.ArgumentMatchers.argThat((Collection<String> s) ->
                s.size() == 2 && s.containsAll(List.of("FUND-1", "ASSET-1"))), anyInt());
        assertThat(d.checkedAt()).isEqualTo(T0);
        assertThat(d.unanchoredEvents()).isEqualTo(1);
        assertThat(d.batches()).hasSize(2);
        BatchIntegrity old = d.batches().get(0);
        assertThat(old.anchorStatus()).isEqualTo("ANCHORED");
        assertThat(old.merkleRoot()).isEqualTo("root-B-OLD");
        assertThat(old.transactionHash()).isEqualTo("0xtx-B-OLD");
        assertThat(old.network()).isEqualTo("ganache-local");
        assertThat(old.confirmedBlockNumber()).isEqualTo(42L);
        assertThat(old.anchoredAt()).isEqualTo(T0.minusSeconds(540));
        assertThat(old.eventsOfThisDonation()).as("solo los propios, no los 4 de la otra donación").isEqualTo(1);
        assertThat(old.verification().result()).isEqualTo(Result.MATCH);
        assertThat(old.verification().reason()).isNull();

        BatchIntegrity pending = d.batches().get(1);
        assertThat(pending.anchorStatus()).isEqualTo("SUBMITTED");
        assertThat(pending.eventsOfThisDonation()).isEqualTo(2);
        assertThat(pending.verification().result()).isEqualTo(Result.INCONCLUSIVE);
        assertThat(pending.verification().reason()).isEqualTo(Reason.NOT_ANCHORED);
        verify(verification, never()).verifyBatch("B-NEW");
    }

    @Test
    void aMismatch_saysWhetherItTouchesThisDonation_withoutNamingOtherStreams() {
        twoBatches();
        when(verification.verifyBatch("B-OLD")).thenReturn(new VerificationResult(VerificationStatus.MISMATCH, "other",
                "root-B-OLD", List.of(new StreamIdentity("OTHER-FUND", 3)), true));

        BatchIntegrity old = service.integrityOf("FUND-1").batches().get(0);

        assertThat(old.verification().result()).isEqualTo(Result.MISMATCH);
        assertThat(old.verification().reason()).isEqualTo(Reason.ROOT_MISMATCH);
        assertThat(old.verification().affectsThisDonation()).isFalse();
        assertThat(old.toString()).doesNotContain("OTHER-FUND").doesNotContain("B-OLD\"").doesNotContain("other,");
    }

    @Test
    void aMismatchOnItsOwnEvent_affectsThisDonation() {
        twoBatches();
        when(verification.verifyBatch("B-OLD")).thenReturn(new VerificationResult(VerificationStatus.MISMATCH, "other",
                "root-B-OLD", List.of(new StreamIdentity("FUND-1", 1)), true));

        assertThat(service.integrityOf("FUND-1").batches().get(0).verification().affectsThisDonation()).isTrue();
    }

    @Test
    void aChangedNumberOfEvents_isAMismatchThatCannotBeAttributed() {
        twoBatches();
        when(verification.verifyBatch("B-OLD")).thenReturn(new VerificationResult(VerificationStatus.MISMATCH, null,
                "root-B-OLD", List.of(), false));

        BatchIntegrity old = service.integrityOf("FUND-1").batches().get(0);

        assertThat(old.verification().reason()).isEqualTo(Reason.LEAF_COUNT_CHANGED);
        assertThat(old.verification().affectsThisDonation()).isNull();
    }

    @Test
    void aLegacyBatchWithoutLeaves_isInconclusive() {
        twoBatches();
        when(verification.verifyBatch("B-OLD")).thenThrow(new LegacyBatchLeafHashesUnavailableException("legacy"));

        BatchIntegrity old = service.integrityOf("FUND-1").batches().get(0);

        assertThat(old.verification().result()).isEqualTo(Result.INCONCLUSIVE);
        assertThat(old.verification().reason()).isEqualTo(Reason.LEGACY_BATCH);
    }

    @Test
    void theVerificationIsCachedFiveMinutes_perBatch() {
        twoBatches();
        when(verification.verifyBatch("B-OLD")).thenReturn(new VerificationResult(VerificationStatus.MATCH, "root-B-OLD",
                "root-B-OLD", List.of(), true));

        service.integrityOf("FUND-1");
        clock.now = T0.plus(Duration.ofMinutes(4));
        service.integrityOf("FUND-1");
        verify(verification, times(1)).verifyBatch("B-OLD");

        clock.now = T0.plus(Duration.ofMinutes(5)).plusSeconds(1);
        service.integrityOf("FUND-1");
        verify(verification, times(2)).verifyBatch("B-OLD");
    }

    @Test
    void withoutAProjection_onlyTheFundStreamIsChecked_andNoEventsMeansNoBatches() {
        when(donations.findByFundId("FUND-2")).thenReturn(Optional.empty());
        when(membership.findMembership(any(), anyInt())).thenReturn(List.of());

        DonationIntegrity d = service.integrityOf("FUND-2");

        verify(membership).findMembership(org.mockito.ArgumentMatchers.argThat((Collection<String> s) ->
                s.size() == 1 && s.contains("FUND-2")), anyInt());
        assertThat(d.batches()).isEmpty();
        assertThat(d.unanchoredEvents()).isZero();
        verify(batches, never()).findByBatchId(anyString());
    }

    @Test
    void anUnknownCanonicalForm_isInconclusiveWithItsOwnReason() {
        twoBatches();
        when(verification.verifyBatch("B-OLD")).thenReturn(new VerificationResult(VerificationStatus.INCONCLUSIVE,
                "root-B-OLD", "root-B-OLD", List.of(new StreamIdentity("FUND-1", 1)), true,
                com.traceability.crypto.domain.InconclusiveReason.CANONICAL_FORM_UNKNOWN));

        BatchIntegrity old = service.integrityOf("FUND-1").batches().get(0);

        assertThat(old.verification().result()).isEqualTo(Result.INCONCLUSIVE);
        assertThat(old.verification().reason()).isEqualTo(Reason.CANONICAL_FORM_UNKNOWN);
    }
}
