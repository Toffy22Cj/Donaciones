package com.traceability.core.application.event;

import com.traceability.core.domain.event.DomainEventPayload;
import com.traceability.core.domain.fund.payloads.*;
import com.traceability.core.domain.physicalasset.payloads.*;

import java.util.HashMap;
import java.util.Map;

/**
 * Registry mapping event type strings to their concrete DomainEventPayload classes.
 * Required for polymorphic deserialization when reloading events from the Event Store.
 */
public class EventPayloadRegistry {
    private static final Map<String, Class<? extends DomainEventPayload>> registry = new HashMap<>();

    static {
        // PhysicalAsset Events
        registry.put("ASSET_REGISTERED", AssetRegisteredPayload.class);
        registry.put("ASSET_DISPATCHED", AssetDispatchedPayload.class);
        registry.put("ASSET_RECEIVED", AssetReceivedPayload.class);
        registry.put("ASSET_CUSTODY_TRANSFERRED", AssetCustodyTransferredPayload.class);
        registry.put("ASSET_SPLIT", AssetSplitPayload.class);
        registry.put("ASSET_DEPLETED", AssetDepletedPayload.class);
        registry.put("ASSET_SPLIT_COMPENSATED", AssetSplitCompensatedPayload.class);
        registry.put("ASSET_DELIVERED", AssetDeliveredPayload.class);

        // Fund Events
        registry.put("FUND_REGISTERED", FundRegisteredPayload.class);
        registry.put("FUNDS_CLEARED", FundsClearedPayload.class);
        registry.put("ALLOCATION_REQUESTED", AllocationRequestedPayload.class);
        registry.put("ALLOCATION_CONFIRMED", AllocationConfirmedPayload.class);
        registry.put("ALLOCATION_REVERSED", AllocationReversedPayload.class);
        registry.put("FUNDS_REFUNDED", FundsRefundedPayload.class);
    }

    /**
     * Gets the concrete payload class for a given event type.
     */
    public static Class<? extends DomainEventPayload> getClassForType(String eventType) {
        Class<? extends DomainEventPayload> clazz = registry.get(eventType);
        if (clazz == null) {
            throw new IllegalArgumentException("Unknown eventType: " + eventType);
        }
        return clazz;
    }
}
