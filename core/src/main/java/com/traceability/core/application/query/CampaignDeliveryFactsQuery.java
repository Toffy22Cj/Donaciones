package com.traceability.core.application.query;

import com.traceability.core.application.port.out.CampaignAssetDirectoryPort;
import com.traceability.core.application.port.out.EventStorePort;
import com.traceability.core.domain.event.DomainEvent;
import com.traceability.core.domain.physicalasset.AssetLifecycleStatus;
import com.traceability.core.domain.physicalasset.PhysicalAsset;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Hechos de entrega de una convocatoria (ADR-040 §2.1; plan B5, DD-33): {@code unitsDelivered} = suma de la cantidad
 * de los activos {@code DELIVERED}, y {@code distinctRecipients} = número de {@code beneficiaryRef} distintos de esos
 * mismos. Rehidrata del event store, la fuente de verdad. Nunca devuelve ids de activos ni beneficiarios.
 */
@Service
public class CampaignDeliveryFactsQuery {

    public record DeliveryFacts(BigDecimal unitsDelivered, long distinctRecipients, Instant readAt) {}

    private final CampaignAssetDirectoryPort directory;
    private final EventStorePort eventStore;
    private final Clock clock;

    public CampaignDeliveryFactsQuery(CampaignAssetDirectoryPort directory, EventStorePort eventStore,
                                      ObjectProvider<Clock> clock) {
        this.directory = directory;
        this.eventStore = eventStore;
        this.clock = clock.getIfAvailable(Clock::systemUTC);
    }

    public DeliveryFacts deliveriesOf(String campaignRef) {
        Instant readAt = clock.instant();
        BigDecimal units = BigDecimal.ZERO;
        Set<String> recipients = new HashSet<>();
        for (String assetId : directory.findAssetIdsByCampaign(campaignRef)) {
            List<DomainEvent> events = eventStore.loadStream(assetId);
            PhysicalAsset asset = PhysicalAsset.rehydrate(assetId, events.stream().map(DomainEvent::payload).toList(),
                    events.size());
            if (asset.getLifecycleStatus() == AssetLifecycleStatus.DELIVERED && campaignRef.equals(asset.getCampaignRef())) {
                units = units.add(asset.getQuantity());
                if (asset.getFinalBeneficiaryRef() != null) {
                    recipients.add(asset.getFinalBeneficiaryRef());
                }
            }
        }
        return new DeliveryFacts(units, recipients.size(), readAt);
    }
}
