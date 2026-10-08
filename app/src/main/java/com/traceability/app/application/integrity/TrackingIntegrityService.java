package com.traceability.app.application.integrity;

import com.traceability.core.application.port.out.DonationReadPort;
import com.traceability.core.application.port.out.EventBatchMembershipPort;
import com.traceability.core.application.port.out.EventBatchMembershipPort.EventMembership;
import com.traceability.core.application.port.out.LogisticsReadItem;
import com.traceability.crypto.application.port.in.IntegrityVerificationPort;
import com.traceability.crypto.application.port.out.MerkleBatchRepositoryPort;
import com.traceability.crypto.domain.AnchorStatus;
import com.traceability.crypto.domain.InconclusiveReason;
import com.traceability.crypto.domain.MerkleBatch;
import com.traceability.crypto.domain.StreamIdentity;
import com.traceability.crypto.domain.VerificationResult;
import com.traceability.crypto.domain.exception.LegacyBatchLeafHashesUnavailableException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Integridad de una donación para su donante (encargo 6, P4; ficha {@code ficha-p4-integridad-en-seguimiento.md}).
 * Solo lectura. Por cada lote de Merkle con eventos de la donación (su fondo y los activos de su proyección): el estado
 * del anclaje, la raíz, la transacción, la red y el resultado de {@code verifyBatch} con su motivo.
 * <p>
 * Nunca devuelve nada de otras donaciones: ni el {@code batchId}, ni la cobertura, ni las secuencias afectadas ni la
 * raíz recalculada; {@code eventsOfThisDonation} y {@code affectsThisDonation} solo miran los streams propios. La
 * verificación de un lote anclado se guarda en caché ({@code traceability.integrity.verification-cache-ttl}, 5 min por
 * defecto, hasta {@value #MAX_CACHED_BATCHES} lotes) porque recalcula el árbol entero
 * ({@code [DECISIÓN DELEGADA — pendiente de ratificar]} DD-76).
 */
@Service
public class TrackingIntegrityService {

    /** Tope de eventos de una donación que se leen; una donación real tiene unas decenas. */
    static final int MAX_EVENTS = 10_000;
    static final int MAX_CACHED_BATCHES = 1_000;
    static final Duration DEFAULT_CACHE_TTL = Duration.ofMinutes(5);

    public enum Result { MATCH, MISMATCH, INCONCLUSIVE }

    public enum Reason {
        NOT_ANCHORED("El lote aún no está anclado en la cadena"),
        LEGACY_BATCH("Lote antiguo sin las hojas guardadas: no se puede recalcular"),
        ROOT_MISMATCH("La raíz recalculada desde los eventos no coincide con la anclada"),
        LEAF_COUNT_CHANGED("Cambió el número de eventos del lote"),
        CANONICAL_FORM_UNKNOWN("Hay eventos antiguos con una forma canónica que no se puede determinar"),
        VERIFICATION_INCONCLUSIVE("La verificación no fue concluyente");

        public final String text;

        Reason(String text) {
            this.text = text;
        }
    }

    /** {@code affectsThisDonation}: solo con {@code MISMATCH} atribuible; {@code null} si no se puede saber. */
    public record Verification(Result result, Reason reason, Boolean affectsThisDonation) {}

    public record BatchIntegrity(String anchorStatus, String merkleRoot, String transactionHash, String network,
                                 Instant anchoredAt, Long confirmedBlockNumber, int eventsOfThisDonation,
                                 Verification verification) {}

    public record DonationIntegrity(List<BatchIntegrity> batches, int unanchoredEvents, Instant checkedAt) {}

    private record Cached(VerificationResult result, RuntimeException legacy, Instant expiresAt) {}

    private final DonationReadPort donations;
    private final EventBatchMembershipPort membership;
    private final MerkleBatchRepositoryPort batches;
    private final IntegrityVerificationPort verification;
    private final Clock clock;
    private final Duration cacheTtl;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    @Autowired
    public TrackingIntegrityService(DonationReadPort donations, EventBatchMembershipPort membership,
                                    MerkleBatchRepositoryPort batches, IntegrityVerificationPort verification,
                                    ObjectProvider<Clock> clock,
                                    @Value("${traceability.integrity.verification-cache-ttl:PT5M}") Duration cacheTtl) {
        this.donations = donations;
        this.membership = membership;
        this.batches = batches;
        this.verification = verification;
        this.clock = clock.getIfAvailable(Clock::systemUTC);
        this.cacheTtl = cacheTtl;
    }

    TrackingIntegrityService(DonationReadPort donations, EventBatchMembershipPort membership,
                             MerkleBatchRepositoryPort batches, IntegrityVerificationPort verification,
                             ObjectProvider<Clock> clock) {
        this(donations, membership, batches, verification, clock, DEFAULT_CACHE_TTL);
    }

    public DonationIntegrity integrityOf(String fundId) {
        Instant now = clock.instant();
        Set<String> streams = new LinkedHashSet<>();
        streams.add(fundId);
        donations.findByFundId(fundId).ifPresent(model -> model.logistics().stream()
                .map(LogisticsReadItem::assetId).forEach(streams::add));

        int unanchored = 0;
        Map<String, Integer> ownEventsByBatch = new LinkedHashMap<>();
        for (EventMembership e : membership.findMembership(streams, MAX_EVENTS)) {
            if (e.merkleBatchId() == null) {
                unanchored++;
            } else {
                ownEventsByBatch.merge(e.merkleBatchId(), 1, Integer::sum);
            }
        }

        List<MerkleBatch> found = new ArrayList<>();
        for (String batchId : ownEventsByBatch.keySet()) {
            batches.findByBatchId(batchId).ifPresent(found::add);
        }
        found.sort(Comparator.comparing(MerkleBatch::createdAt, Comparator.nullsLast(Comparator.naturalOrder())));
        List<BatchIntegrity> result = new ArrayList<>();
        for (MerkleBatch b : found) {
            result.add(new BatchIntegrity(b.status().name(), b.merkleRoot(), b.transactionHash(), b.network(),
                    b.anchoredAt(), b.confirmedBlockNumber(), ownEventsByBatch.get(b.batchId()),
                    verify(b, streams, now)));
        }
        return new DonationIntegrity(List.copyOf(result), unanchored, now);
    }

    private Verification verify(MerkleBatch batch, Set<String> ownStreams, Instant now) {
        if (batch.status() != AnchorStatus.ANCHORED) {
            return new Verification(Result.INCONCLUSIVE, Reason.NOT_ANCHORED, null);
        }
        Cached cached = cache.get(batch.batchId());
        if (cached == null || !now.isBefore(cached.expiresAt())) {
            cached = compute(batch.batchId(), now);
        }
        if (cached.legacy() != null) {
            return new Verification(Result.INCONCLUSIVE, Reason.LEGACY_BATCH, null);
        }
        VerificationResult r = cached.result();
        return switch (r.status()) {
            case MATCH -> new Verification(Result.MATCH, null, null);
            case INCONCLUSIVE -> new Verification(Result.INCONCLUSIVE,
                    r.inconclusiveReason() == InconclusiveReason.CANONICAL_FORM_UNKNOWN
                            ? Reason.CANONICAL_FORM_UNKNOWN : Reason.VERIFICATION_INCONCLUSIVE, null);
            case MISMATCH -> r.recomputedRoot() == null || !r.diagnosisComplete()
                    ? new Verification(Result.MISMATCH, Reason.LEAF_COUNT_CHANGED, null)
                    : new Verification(Result.MISMATCH, Reason.ROOT_MISMATCH, r.affectedSequences().stream()
                            .map(StreamIdentity::streamId).anyMatch(ownStreams::contains));
        };
    }

    private Cached compute(String batchId, Instant now) {
        Cached computed;
        try {
            computed = new Cached(verification.verifyBatch(batchId), null, now.plus(cacheTtl));
        } catch (LegacyBatchLeafHashesUnavailableException legacy) {
            computed = new Cached(null, legacy, now.plus(cacheTtl));
        }
        if (cache.size() >= MAX_CACHED_BATCHES) {
            cache.clear();
        }
        if (!cacheTtl.isZero()) {
            cache.put(batchId, computed);
        }
        return computed;
    }
}
