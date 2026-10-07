package com.traceability.core.infrastructure.projection.mongo;

import com.traceability.core.application.port.out.AssetHistoryReadModel;
import com.traceability.core.application.port.out.AssetHistoryReadPort;
import com.traceability.core.application.port.out.AssetTransitionReadModel;
import com.traceability.core.infrastructure.projection.mongo.documents.AssetHistoryProjectionDocument;
import com.traceability.core.infrastructure.projection.mongo.repositories.AssetHistoryProjectionRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;

@Component
public class MongoAssetHistoryReadAdapter implements AssetHistoryReadPort {

    private final AssetHistoryProjectionRepository repository;

    public MongoAssetHistoryReadAdapter(AssetHistoryProjectionRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<AssetHistoryReadModel> getHistory(String assetId) {
        return repository.findById(assetId)
                .map(doc -> new AssetHistoryReadModel(
                        doc.getAssetId(),
                        doc.getTransitions().stream()
                                .map(t -> new AssetTransitionReadModel(
                                        t.getEventType(),
                                        t.getStatus(),
                                        t.getCustodian(),
                                        t.getLocation(),
                                        t.getTimestamp() != null ? Instant.parse(t.getTimestamp()) : null
                                ))
                                .toList()
                ));
    }
}
