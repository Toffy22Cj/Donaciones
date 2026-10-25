package com.traceability.app.application.integrity;

import com.traceability.contracts.TrackingAnchorStatus;
import com.traceability.contracts.authorization.AuthorizationPrincipal;
import com.traceability.contracts.authorization.AuthorizationRole;
import com.traceability.core.application.authorization.InsufficientRoleException;
import com.traceability.core.application.authorization.OrganizationBoundaryPolicy;
import com.traceability.core.application.port.out.AssetDirectoryPort;
import com.traceability.core.application.port.out.EventBatchCountPort;
import com.traceability.core.application.port.out.EventBatchCountPort.BatchEventCount;
import com.traceability.core.application.port.out.FundDirectoryPort;
import com.traceability.crypto.application.port.out.MerkleBatchRepositoryPort;
import com.traceability.crypto.domain.MerkleBatch;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Integridad de una organización (ficha R-6, §6, G-7; plan D, §2.1). Solo lectura. Los streams son los de sus fondos y
 * sus activos, por la génesis (directorios, con un límite de {@value #DIRECTORY_LIMIT} cada uno y {@code truncated} si
 * se alcanza, C-5); los recuentos, de una agregación por lote sin tope (P-R6D-3), y cada lote se clasifica con la tabla
 * de G-3 del Bloque C ({@link AssetAnchorStatusProducer#trackingStatusOf}, P-R6D-5). Un evento cuyo lote no está
 * guardado cuenta como sin anclar, como {@code PENDING} en G-3 (H-C-2).
 * <p>
 * Solo {@code ADMINISTRATOR} o {@code REPRESENTATIVE} de la organización, como la predicción (DD-43); el resto, el
 * mismo 403, antes de leer nada. Nunca {@code batchId}, {@code merkleRoot}, {@code transactionHash} ni nada de otras
 * organizaciones: solo se cuentan los streams propios. Un {@code findByBatchId} por lote distinto (P-R6D-7).
 */
@Service
public class OrganizationIntegrityQuery {

    static final int DIRECTORY_LIMIT = 10_000;

    public record OrganizationIntegrity(long unanchoredEvents, long inPendingBatches, long underReviewEvents,
                                        Instant lastAnchoredAt, boolean truncated, Instant checkedAt) {}

    private final FundDirectoryPort funds;
    private final AssetDirectoryPort assets;
    private final EventBatchCountPort counts;
    private final MerkleBatchRepositoryPort batches;
    private final OrganizationBoundaryPolicy boundary;
    private final Clock clock;

    public OrganizationIntegrityQuery(FundDirectoryPort funds, AssetDirectoryPort assets, EventBatchCountPort counts,
                                      MerkleBatchRepositoryPort batches, OrganizationBoundaryPolicy boundary,
                                      ObjectProvider<Clock> clock) {
        this.funds = funds;
        this.assets = assets;
        this.counts = counts;
        this.batches = batches;
        this.boundary = boundary;
        this.clock = clock.getIfAvailable(Clock::systemUTC);
    }

    public OrganizationIntegrity integrityOf(AuthorizationPrincipal principal, String organizationId) {
        authorize(principal, organizationId);
        Instant now = clock.instant();

        List<String> fundIds = funds.findFundIdsByOrganization(organizationId, DIRECTORY_LIMIT + 1);
        List<String> assetIds = assets.findAssetIdsByOrganization(organizationId, DIRECTORY_LIMIT + 1);
        boolean truncated = fundIds.size() > DIRECTORY_LIMIT || assetIds.size() > DIRECTORY_LIMIT;
        List<String> streams = new ArrayList<>(fundIds.subList(0, Math.min(fundIds.size(), DIRECTORY_LIMIT)));
        streams.addAll(assetIds.subList(0, Math.min(assetIds.size(), DIRECTORY_LIMIT)));

        long unanchored = 0;
        long inPending = 0;
        long underReview = 0;
        Instant lastAnchoredAt = null;
        for (BatchEventCount count : counts.countByBatch(streams)) {
            Optional<MerkleBatch> batch = count.merkleBatchId() == null
                    ? Optional.empty() : batches.findByBatchId(count.merkleBatchId());
            TrackingAnchorStatus status = batch.map(b -> AssetAnchorStatusProducer.trackingStatusOf(b.status()))
                    .orElse(TrackingAnchorStatus.PENDING);
            switch (status) {
                case PENDING -> unanchored += count.events();
                case IN_BATCH -> inPending += count.events();
                case UNDER_REVIEW -> underReview += count.events();
                case ANCHORED -> {
                    Instant anchoredAt = batch.get().anchoredAt();
                    if (anchoredAt != null && (lastAnchoredAt == null || anchoredAt.isAfter(lastAnchoredAt))) {
                        lastAnchoredAt = anchoredAt;
                    }
                }
            }
        }
        return new OrganizationIntegrity(unanchored, inPending, underReview, lastAnchoredAt, truncated, now);
    }

    /** Frontera de organización y {@code ADMINISTRATOR} o {@code REPRESENTATIVE}; el resto, el mismo 403 (DD-01). */
    private void authorize(AuthorizationPrincipal principal, String organizationId) {
        boundary.assertBelongs(principal.organizationId(), organizationId);
        if (principal.roles() == null || !(principal.roles().contains(AuthorizationRole.ADMINISTRATOR)
                || principal.roles().contains(AuthorizationRole.REPRESENTATIVE))) {
            throw new InsufficientRoleException("Organization integrity requires ADMINISTRATOR or REPRESENTATIVE");
        }
    }
}
