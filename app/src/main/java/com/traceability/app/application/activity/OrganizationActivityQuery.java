package com.traceability.app.application.activity;

import com.traceability.contracts.authorization.AuthorizationPrincipal;
import com.traceability.contracts.authorization.AuthorizationRole;
import com.traceability.convocatoria.application.query.OrganizationCampaignClosuresQuery;
import com.traceability.convocatoria.application.query.OrganizationCampaignClosuresQuery.CampaignClosure;
import com.traceability.core.application.authorization.InsufficientRoleException;
import com.traceability.core.application.authorization.OrganizationBoundaryPolicy;
import com.traceability.core.application.port.out.ActivityEventsPort;
import com.traceability.core.application.port.out.ActivityEventsPort.ActivityEvent;
import com.traceability.core.application.port.out.AssetDirectoryPort;
import com.traceability.core.application.port.out.EventBatchCountPort;
import com.traceability.core.application.port.out.EventBatchCountPort.BatchEventCount;
import com.traceability.core.application.port.out.FundDirectoryPort;
import com.traceability.core.application.service.LocationReferenceService;
import com.traceability.core.domain.event.DomainEventPayload;
import com.traceability.core.domain.physicalasset.payloads.AssetDeliveredPayload;
import com.traceability.core.domain.physicalasset.payloads.AssetReceivedPayload;
import com.traceability.core.domain.physicalasset.payloads.AssetRegisteredPayload;
import com.traceability.core.domain.physicalasset.payloads.AssetRegisteredV2Payload;
import com.traceability.core.domain.physicalasset.payloads.AssetRegisteredV3Payload;
import com.traceability.core.domain.physicalasset.payloads.AssetSplitPayload;
import com.traceability.core.domain.physicalasset.payloads.AssetSplitV2Payload;
import com.traceability.core.domain.physicalasset.payloads.AssetSplitV3Payload;
import com.traceability.crypto.application.port.out.MerkleBatchRepositoryPort;
import com.traceability.crypto.domain.AnchorStatus;
import com.traceability.crypto.domain.MerkleBatch;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Actividad reciente de una organización (ficha R-6, §6, G-8; plan D, §2.2, con P-R6D-1 (b) y P-R6D-2 (a)). Solo
 * lectura. Mezcla tres fuentes con el orden total de C-7, {@code (instante de listado descendente, tipo de fuente, id
 * estable)}, y devuelve {@value #PAGE_SIZE} por página: se piden {@code PAGE_SIZE + 1} a cada fuente desde la clave del
 * cursor, se mezclan y se cortan. La clave guarda los tres componentes, así que un empate de instantes entre fuentes no
 * repite ni salta ítems.
 * <ol>
 *   <li>Eventos del Event Store de los streams de la organización (sus fondos y sus activos, por la génesis): los
 *   tipos de D-R6-9; instante = {@code recordedAt}, id = {@code eventId}.</li>
 *   <li>{@code CAMPAIGN_CLOSED}: sus convocatorias {@code CLOSED}, con {@code closedAt}; id = {@code campaignRef}.</li>
 *   <li>{@code BATCH_ANCHORED}: los lotes {@code ANCHORED} con eventos propios, de la misma agregación que G-7; instante
 *   = {@code anchoredAt}; id = {@code batchId}, que solo viaja dentro del cursor cifrado. Nunca se lee
 *   {@code coverage}.</li>
 * </ol>
 * Los instantes se comparan como el texto ISO de {@code Instant.toString()}, que es como el Event Store guarda
 * {@code recordedAt} y como lo ordena Mongo (hallazgo H-D-2). Nunca el actor, {@code donorRef}, {@code beneficiaryRef},
 * custodios, ubicaciones exactas (solo {@code locationZone}) ni importes. {@code ADMINISTRATOR}, {@code REPRESENTATIVE}
 * y {@code EMPLOYEE} de la organización (D-R6-8); el resto, el mismo 403, antes de leer nada.
 */
@Service
public class OrganizationActivityQuery {

    public static final int PAGE_SIZE = 20;
    static final int DIRECTORY_LIMIT = 10_000;
    /** D-R6-9: los eventos del Event Store que se listan. */
    static final Set<String> EVENT_TYPES = Set.of("ASSET_REGISTERED", "ASSET_DISPATCHED", "ASSET_RECEIVED",
            "ASSET_DELIVERED", "ASSET_SPLIT", "FUNDS_CLEARED");

    /** Orden de las fuentes en un empate de instantes. */
    public static final int SOURCE_EVENT = 0;
    public static final int SOURCE_CAMPAIGN = 1;
    public static final int SOURCE_BATCH = 2;

    /** Clave del orden total: {@code instant} es el texto ISO del instante de listado. */
    public record ActivityKey(String instant, int source, String id) {}

    /** {@code kind}: {@code ASSET}, {@code CAMPAIGN} o {@code FUND}. */
    public record Subject(String kind, String ref) {}

    /** {@code subject} falta en {@code BATCH_ANCHORED}, que lleva {@code eventCount} (P-R6D-1 (b)). */
    public record ActivityItem(String type, Instant occurredAt, Subject subject, Long eventCount, BigDecimal quantity,
                               String unitOfMeasure, String locationZone) {}

    /**
     * {@code next} es nulo en la última página. {@code truncated}: la organización tiene más de
     * {@value #DIRECTORY_LIMIT} fondos o activos y solo se leyeron los primeros de cada directorio (Enmienda 2 de la
     * ficha R-6, con el criterio de G-7).
     */
    public record ActivityPage(List<ActivityItem> items, ActivityKey next, boolean truncated) {}

    private record Keyed(ActivityKey key, ActivityItem item) {}

    static final Comparator<ActivityKey> ORDER = Comparator.comparing(ActivityKey::instant, Comparator.reverseOrder())
            .thenComparingInt(ActivityKey::source)
            .thenComparing(ActivityKey::id);

    private final FundDirectoryPort funds;
    private final AssetDirectoryPort assets;
    private final ActivityEventsPort events;
    private final EventBatchCountPort counts;
    private final MerkleBatchRepositoryPort batches;
    private final OrganizationCampaignClosuresQuery closures;
    private final LocationReferenceService locations;
    private final OrganizationBoundaryPolicy boundary;

    public OrganizationActivityQuery(FundDirectoryPort funds, AssetDirectoryPort assets, ActivityEventsPort events,
                                     EventBatchCountPort counts, MerkleBatchRepositoryPort batches,
                                     OrganizationCampaignClosuresQuery closures, LocationReferenceService locations,
                                     OrganizationBoundaryPolicy boundary) {
        this.funds = funds;
        this.assets = assets;
        this.events = events;
        this.counts = counts;
        this.batches = batches;
        this.closures = closures;
        this.locations = locations;
        this.boundary = boundary;
    }

    /** Frontera de organización y uno de los tres roles; el resto, el mismo 403 (DD-01). */
    public void authorize(AuthorizationPrincipal principal, String organizationId) {
        boundary.assertBelongs(principal.organizationId(), organizationId);
        if (principal.roles() == null || !(principal.roles().contains(AuthorizationRole.ADMINISTRATOR)
                || principal.roles().contains(AuthorizationRole.REPRESENTATIVE)
                || principal.roles().contains(AuthorizationRole.EMPLOYEE))) {
            throw new InsufficientRoleException("Organization activity requires ADMINISTRATOR, REPRESENTATIVE or EMPLOYEE");
        }
    }

    /** @param after la clave del último ítem de la página anterior; {@code null} para la primera */
    public ActivityPage page(AuthorizationPrincipal principal, String organizationId, ActivityKey after) {
        authorize(principal, organizationId);
        // Enmienda 2 de la ficha R-6: el criterio de G-7 (OrganizationIntegrityQuery), uno más para saber si hay más
        List<String> fundIds = funds.findFundIdsByOrganization(organizationId, DIRECTORY_LIMIT + 1);
        List<String> assetIds = assets.findAssetIdsByOrganization(organizationId, DIRECTORY_LIMIT + 1);
        boolean truncated = fundIds.size() > DIRECTORY_LIMIT || assetIds.size() > DIRECTORY_LIMIT;
        List<String> streams = new ArrayList<>(fundIds.subList(0, Math.min(fundIds.size(), DIRECTORY_LIMIT)));
        streams.addAll(assetIds.subList(0, Math.min(assetIds.size(), DIRECTORY_LIMIT)));
        int wanted = PAGE_SIZE + 1;

        List<Keyed> merged = new ArrayList<>();
        merged.addAll(eventItems(streams, after, wanted));
        merged.addAll(closureItems(organizationId, after, wanted));
        merged.addAll(batchItems(streams, after, wanted));
        merged.sort(Comparator.comparing(Keyed::key, ORDER));

        List<Keyed> page = merged.subList(0, Math.min(PAGE_SIZE, merged.size()));
        ActivityKey next = merged.size() > PAGE_SIZE ? page.get(PAGE_SIZE - 1).key() : null;
        return new ActivityPage(page.stream().map(Keyed::item).toList(), next, truncated);
    }

    private List<Keyed> eventItems(List<String> streams, ActivityKey after, int wanted) {
        String before = after == null ? null : after.instant();
        // Si el cursor es de esta fuente, siguen los empates con un id mayor; si es de otra posterior, solo lo anterior
        String afterId = after != null && after.source() == SOURCE_EVENT ? after.id() : null;
        return events.findPage(streams, EVENT_TYPES, before, afterId, wanted).stream()
                .map(e -> new Keyed(new ActivityKey(e.recordedAt(), SOURCE_EVENT, e.eventId()), item(e)))
                .toList();
    }

    private List<Keyed> closureItems(String organizationId, ActivityKey after, int wanted) {
        List<Keyed> items = new ArrayList<>();
        for (CampaignClosure c : closures.closedCampaigns(organizationId)) {
            ActivityKey key = new ActivityKey(c.closedAt().toString(), SOURCE_CAMPAIGN, c.campaignRef());
            if (after == null || ORDER.compare(key, after) > 0) {
                items.add(new Keyed(key, new ActivityItem("CAMPAIGN_CLOSED", c.closedAt(),
                        new Subject("CAMPAIGN", c.campaignRef()), null, null, null, null)));
            }
        }
        return firstInOrder(items, wanted);
    }

    private List<Keyed> batchItems(List<String> streams, ActivityKey after, int wanted) {
        List<Keyed> items = new ArrayList<>();
        for (BatchEventCount count : counts.countByBatch(streams)) {
            if (count.merkleBatchId() == null) {
                continue;
            }
            Optional<MerkleBatch> batch = batches.findByBatchId(count.merkleBatchId());
            if (batch.isEmpty() || batch.get().status() != AnchorStatus.ANCHORED || batch.get().anchoredAt() == null) {
                continue;
            }
            Instant anchoredAt = batch.get().anchoredAt();
            ActivityKey key = new ActivityKey(anchoredAt.toString(), SOURCE_BATCH, count.merkleBatchId());
            if (after == null || ORDER.compare(key, after) > 0) {
                items.add(new Keyed(key, new ActivityItem("BATCH_ANCHORED", anchoredAt, null, count.events(),
                        null, null, null)));
            }
        }
        return firstInOrder(items, wanted);
    }

    private static List<Keyed> firstInOrder(List<Keyed> items, int wanted) {
        return items.stream().sorted(Comparator.comparing(Keyed::key, ORDER)).limit(wanted).toList();
    }

    /**
     * Cantidad, unidad y zona del payload, con las mismas ubicaciones que el historial del seguimiento
     * ({@code DonationProjectionHandler}: registro → {@code currentLocation}, recepción → {@code facilityLocation},
     * entrega → {@code locationRef}; despacho y división, sin zona). Un {@code FUNDS_CLEARED} solo lleva su fondo.
     */
    private ActivityItem item(ActivityEvent e) {
        DomainEventPayload p = e.payload();
        if ("FUNDS_CLEARED".equals(e.eventType())) {
            return new ActivityItem(e.eventType(), e.occurredAt(), new Subject("FUND", e.streamId()), null, null, null, null);
        }
        BigDecimal quantity = null;
        String unit = null;
        String location = null;
        if (p instanceof AssetRegisteredV3Payload r) {
            quantity = r.quantity();
            unit = r.unitOfMeasure();
            location = r.currentLocation();
        } else if (p instanceof AssetRegisteredV2Payload r) {
            quantity = r.quantity();
            unit = r.unitOfMeasure();
            location = r.currentLocation();
        } else if (p instanceof AssetRegisteredPayload r) {
            quantity = r.quantity();
            unit = r.unitOfMeasure();
            location = r.currentLocation();
        } else if (p instanceof AssetSplitV3Payload s) {
            quantity = s.extractedQuantity();
            unit = s.unitOfMeasure();
        } else if (p instanceof AssetSplitV2Payload s) {
            quantity = s.extractedQuantity();
            unit = s.unitOfMeasure();
        } else if (p instanceof AssetSplitPayload s) {
            quantity = s.extractedQuantity();
            unit = s.unitOfMeasure();
        } else if (p instanceof AssetReceivedPayload r) {
            location = r.facilityLocation();
        } else if (p instanceof AssetDeliveredPayload d) {
            location = d.locationRef();
        }
        String zone = location == null ? null : locations.resolveZone(location).orElse(null);
        return new ActivityItem(e.eventType(), e.occurredAt(), new Subject("ASSET", e.streamId()), null, quantity, unit, zone);
    }
}
