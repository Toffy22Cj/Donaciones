package com.traceability.core.application.security;

import com.traceability.core.application.port.out.AssetAuthorizationPort;
import com.traceability.core.infrastructure.projection.mongo.documents.AssetIndexDocument;
import com.traceability.core.infrastructure.projection.mongo.repositories.AssetIndexRepository;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class AssetAuthorizationService implements AssetAuthorizationPort {

    private final AssetIndexRepository assetIndexRepository;

    public AssetAuthorizationService(AssetIndexRepository assetIndexRepository) {
        this.assetIndexRepository = assetIndexRepository;
    }

    @Override
    public boolean assetBelongsToFund(String assetId, String fundId) {
        if (assetId == null || fundId == null) {
            return false;
        }

        Optional<AssetIndexDocument> optionalIndex = assetIndexRepository.findById(assetId);
        if (optionalIndex.isEmpty()) {
            return false;
        }

        AssetIndexDocument index = optionalIndex.get();
        return fundId.equals(index.getProjectionId());
    }
}
