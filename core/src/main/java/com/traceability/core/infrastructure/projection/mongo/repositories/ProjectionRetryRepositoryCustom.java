package com.traceability.core.infrastructure.projection.mongo.repositories;

import com.traceability.core.infrastructure.projection.mongo.documents.ProjectionRetryDocument;

import java.util.Optional;

public interface ProjectionRetryRepositoryCustom {
    Optional<ProjectionRetryDocument> claimNextPendingRetry(int processingTimeoutMinutes);
}
