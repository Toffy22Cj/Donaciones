package com.traceability.core.infrastructure.projection.mongo;

import com.traceability.core.application.port.out.AssetHistoryReadModel;
import com.traceability.core.application.port.out.AssetTransitionReadModel;
import com.traceability.core.infrastructure.projection.mongo.documents.AssetHistoryProjectionDocument;
import com.traceability.core.infrastructure.projection.mongo.documents.AssetHistoryProjectionDocument.AssetTransition;
import com.traceability.core.infrastructure.projection.mongo.repositories.AssetHistoryProjectionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Ficha R-6, G-3 (plan del Bloque C, versión 1, §2.2 y P-R6C-2): el historial lleva la {@code sequence} de cada
 * transición, que la proyección ya guarda, para cruzar cada paso con su evento en el servidor. Es interna: no sale
 * por HTTP (DD-76).
 */
@ExtendWith(MockitoExtension.class)
class MongoAssetHistoryReadAdapterTest {

    @Mock
    private AssetHistoryProjectionRepository repository;

    @InjectMocks
    private MongoAssetHistoryReadAdapter adapter;

    @Test
    void getHistory_carriesTheSequenceOfEachTransition() {
        AssetHistoryProjectionDocument doc = new AssetHistoryProjectionDocument();
        doc.setAssetId("asset-1");
        doc.setTransitions(List.of(
                AssetTransition.builder().eventType("ASSET_REGISTERED").status("REGISTERED")
                        .timestamp("2026-10-01T10:00:00Z").sequence(1).build(),
                AssetTransition.builder().eventType("ASSET_DISPATCHED").status("DISPATCHED")
                        .timestamp("2026-10-02T10:00:00Z").sequence(3).build()));
        when(repository.findById("asset-1")).thenReturn(Optional.of(doc));

        AssetHistoryReadModel model = adapter.getHistory("asset-1").orElseThrow();

        assertThat(model.transitions()).extracting(AssetTransitionReadModel::sequence).containsExactly(1L, 3L);
        assertThat(model.transitions()).extracting(AssetTransitionReadModel::eventType)
                .containsExactly("ASSET_REGISTERED", "ASSET_DISPATCHED");
    }
}
