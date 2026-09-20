package com.traceability.core.infrastructure.projection.mongo.documents;

import com.traceability.core.domain.fund.AllocationStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document(collection = "pending_allocations")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PendingAllocationDocument {
    @Id
    private String allocationId;
    private String fundId;
    private long requestedAmount;
    private Instant requestedAt;
    private AllocationStatus status;
}
