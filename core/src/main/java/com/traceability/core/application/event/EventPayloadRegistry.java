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
    public record EventKey(String eventType, String schemaVersion) {}
    
    private static final Map<EventKey, Class<? extends DomainEventPayload>> registry = new HashMap<>();
    private static final Map<Class<? extends DomainEventPayload>, String> versionByClass = new HashMap<>();

    private static void register(String eventType, String schemaVersion, Class<? extends DomainEventPayload> clazz) {
        registry.put(new EventKey(eventType, schemaVersion), clazz);
        versionByClass.put(clazz, schemaVersion);
    }

    static {
        // PhysicalAsset Events
        register("ASSET_REGISTERED", "1.0", AssetRegisteredPayload.class);
        register("ASSET_DISPATCHED", "1.0", AssetDispatchedPayload.class);
        register("ASSET_RECEIVED", "1.0", AssetReceivedPayload.class);
        register("ASSET_CUSTODY_TRANSFERRED", "1.0", AssetCustodyTransferredPayload.class);
        register("ASSET_SPLIT", "1.0", AssetSplitPayload.class);
        register("ASSET_DEPLETED", "1.0", AssetDepletedPayload.class);
        register("ASSET_SPLIT_COMPENSATED", "1.0", AssetSplitCompensatedPayload.class);
        register("ASSET_DELIVERED", "1.0", AssetDeliveredPayload.class);

        // Fund Events
        register("FUND_REGISTERED", "1.0", FundRegisteredPayload.class);
        register("FUND_REGISTERED", "2.0", FundRegisteredV2Payload.class);
        register("FUNDS_CLEARED", "1.0", FundsClearedPayload.class);
        register("FUNDS_CLEARED", "2.0", FundsClearedV2Payload.class);
        register("ALLOCATION_REQUESTED", "1.0", AllocationRequestedPayload.class);
        register("ALLOCATION_CONFIRMED", "1.0", AllocationConfirmedPayload.class);
        register("ALLOCATION_REVERSED", "1.0", AllocationReversedPayload.class);
        register("FUNDS_REFUNDED", "1.0", FundsRefundedPayload.class);
    }

    /**
     * Gets the concrete payload class for a given event type and schema version.
     */
    public static Class<? extends DomainEventPayload> getClassForType(String eventType, String schemaVersion) {
        Class<? extends DomainEventPayload> clazz = registry.get(new EventKey(eventType, schemaVersion));
        if (clazz == null) {
            throw new IllegalArgumentException("Unknown eventType: " + eventType + " with schemaVersion: " + schemaVersion);
        }
        return clazz;
    }
    
    /**
     * Gets the schema version associated with the given payload class.
     */
    public static String getSchemaVersionForClass(Class<? extends DomainEventPayload> clazz) {
        String version = versionByClass.get(clazz);
        if (version == null) {
            throw new IllegalArgumentException("Unknown payload class: " + clazz.getName());
        }
        return version;
    }
}
