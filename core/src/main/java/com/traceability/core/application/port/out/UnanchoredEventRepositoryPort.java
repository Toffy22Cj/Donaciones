package com.traceability.core.application.port.out;

import com.traceability.contracts.SequenceRange;

import java.util.List;
import java.util.Map;

public interface UnanchoredEventRepositoryPort {
    /**
     * Claims unanchored events (merkleBatchId is null) by setting their merkleBatchId to the provided batchId.
     * Orders the events by streamId ASC and sequence ASC before claiming to guarantee a stable coverage generation.
     * @param batchId the ID of the batch claiming the events
     * @param maxStreams the maximum number of distinct streams to include (optional bound)
     * @param maxEventsPerBatch the maximum number of events to claim in total
     * @return A map of streamId to SequenceRange that represents the coverage claimed in this transaction
     */
    Map<String, SequenceRange> claimOrphansAndAssignBatch(String batchId, int maxStreams, int maxEventsPerBatch);

    /**
     * Retrieves the event hashes for the given coverage map.
     * The returned list MUST be strictly ordered by streamId ASC, sequence ASC.
     * @param coverage the coverage map containing streamIds and their sequence ranges
     * @return ordered list of event hashes
     */
    List<String> getEventHashesByCoverage(Map<String, SequenceRange> coverage);
}
