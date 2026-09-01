package com.traceability.core.domain.physicalasset.payloads;

import com.traceability.core.domain.event.DomainEventPayload;

/**
 * Payload for ASSET_CUSTODY_TRANSFERRED event.
 * Ref: ADR-003
 */
public record AssetCustodyTransferredPayload(
    String previousCustodianRef,
    String newCustodianRef
) implements DomainEventPayload {}
