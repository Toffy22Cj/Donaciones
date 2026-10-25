package com.traceability.core.support;

import com.traceability.contracts.media.DeliveryEvidencePort;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Stub compartido de {@link DeliveryEvidencePort} para los tests de {@code core} (ADR-051, Tarea 4; plan T4 §3). El
 * puerto es una dependencia obligatoria de {@code PhysicalAssetCommandService}: los contextos que escanean
 * {@code com.traceability.core} recogen este {@code @Component}, y los tests unitarios lo instancian a mano. Solo existe
 * en las fuentes de test de {@code core}. Acepta cualquier vínculo y registra cada llamada.
 */
@Component
public class StubDeliveryEvidencePort implements DeliveryEvidencePort {

    public record Binding(String organizationId, String evidenceRef, String assetId) {}

    private final List<Binding> bindings = new CopyOnWriteArrayList<>();

    @Override
    public void bindToAsset(String organizationId, String evidenceRef, String assetId) {
        bindings.add(new Binding(organizationId, evidenceRef, assetId));
    }

    public List<Binding> bindings() {
        return new ArrayList<>(bindings);
    }

    public void reset() {
        bindings.clear();
    }
}
