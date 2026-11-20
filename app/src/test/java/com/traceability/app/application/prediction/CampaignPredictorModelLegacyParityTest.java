package com.traceability.app.application.prediction;

/** El artefacto de {@code baseline-0.3.0} se conserva (ADR-044 Enmienda 4, E4-3): su paridad con scikit-learn sigue en pie. */
class CampaignPredictorModelLegacyParityTest extends AbstractPredictorParityTest {
    @Override
    String version() {
        return "baseline-0.3.0";
    }
}
