# Plan P3 — predictor de convocatorias en el backend

**Estado:** **EN EJECUCIÓN** bajo la segunda autorización de trabajo autónomo de Carlos (2026-10-07), P3. Decisiones DD-41 a DD-47, `[DECISIÓN DELEGADA — pendiente de ratificar por Carlos]`. Norma propuesta: `ADR-044-enmienda-1-prediccion-en-backend.md` (BORRADOR).

## Orden (instrucción de Carlos: "sin paridad no hay endpoint")
1. Exportar a JSON la regresión logística y el regresor del % final (`scripts/predictor/export_baseline_json.py`, scikit-learn 1.9.1, la versión del entrenamiento).
2. **Test de paridad del modelo** (`CampaignPredictorModelParityTest`, 1e-9) y **de las variables** (`CampaignFeatureBuilderParityTest`, 1e-9 relativo).
3. Solo después: lectura en `convocatoria` (`CampaignPredictionDataQuery`), caso de uso y ruta en `app`.

## Tests
- Paridad (arriba).
- `CampaignPredictionUseCaseTest`: estimación con datos y reloj fijo; motivos sin estimación; autorización.
- `CampaignPredictionHttpIntegrationTest`: sobre, `no-store`, STRICT, `IN_KIND`, el mismo 403 para empleado, otra organización, convocatoria ajena o inexistente, 401 sin JWT, y que leer no escribe en ninguna colección.

**Mutaciones:** umbral `<` en vez de `<=`; NaN al lado contrario; sin estandarizar; el `PENDING` como fallo; sin comprobar rol; STRICT con estimación; aceptar otra organización.
