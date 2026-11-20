package com.traceability.app.application.prediction;

/** Paridad Java ↔ Python del modelo vigente, {@code baseline-0.4.0} (ADR-044 Enmienda 4, PROPUESTA). */
class CampaignPredictorModelParityTest extends AbstractPredictorParityTest {
    @Override
    String version() {
        return "baseline-0.4.0";
    }
}
