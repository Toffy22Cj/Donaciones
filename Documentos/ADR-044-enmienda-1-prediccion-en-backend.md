# ADR-044 — Enmienda 1: el backend sirve la predicción, evaluada en Java

**Estado:** **BORRADOR** — `[DECISIÓN DELEGADA — pendiente de ratificar por Carlos]` (DD-41 a DD-47). Redactado bajo la segunda autorización de trabajo autónomo de Carlos (2026-10-07), P3, que pidió "un borrador de enmienda a ADR-044".
**Enmienda a:** ADR-044 (componente predictivo en Python, PROPUESTO). **El documento base no está en este repositorio** (`documento-maestro-proyecto.md`: "número reservado"); esta enmienda se redacta contra lo que de él se cita aquí y contra los artefactos del predictor (`paxfide-predictor`, rama `feat/predictive-dataset-baseline`, `baseline-0.2.0`).
**Sustituye, si se ratifica:** la respuesta Q-v2-9 de `propuesta-apis-fase6.md` §6 (mock server fuera de `com.traceability`). La sustitución la pidió Carlos en la segunda autorización: "endpoint de solo lectura … el Java calcula la predicción con los datos reales de la convocatoria".
**No cambia:** el entrenamiento, el dataset ni el código del predictor, que siguen en Python y fuera de la aplicación (ADR-044 D1). El predictor no se mueve de repositorio (instrucción de Carlos).

---

## 1. Decisión

1. **Entrenamiento offline en Python; inferencia en Java.** El modelo entrenado se exporta a JSON (`scripts/predictor/export_baseline_json.py`) y el backend lo evalúa sin runtime de Python ni dependencias nuevas (`CampaignPredictorModel`).
2. **Modelos servidos** (`baseline-0.2.0`, sin STRICT):
   - **Probabilidad de alcanzar la meta**: la regresión logística, el mejor modelo por Brier en `metrics.json` (test 0.0978 frente a 0.1133 de HistGradientBoosting y 0.1283 del baseline de ritmo).
   - **% final estimado**: el `HistGradientBoostingRegressor` (MAE de test 19.24 puntos).
3. **Paridad obligatoria antes del endpoint.** Dos tests con tolerancia 1e-9:
   - modelo: 303 vectores fijos (300 filas del dataset y 3 casos límite) con la salida de scikit-learn 1.9.1. Diferencia máxima medida: 2.2e-16 (clasificador) y 0.0 (regresor);
   - variables: 172 instantáneas de 25 convocatorias sintéticas, calculadas por `build_snapshot` del generador y por `CampaignFeatureBuilder`. Diferencia máxima: 8.9e-16.
4. **Ruta de solo lectura** `GET /api/v1/organizations/{organizationId}/campaigns/{campaignRef}/prediction`, para `ADMINISTRATOR` o `REPRESENTATIVE` de esa organización, comprobado en el servidor. Cualquier otro caso da el mismo 403 (DD-01). Nunca escribe en el event store, en las proyecciones ni en `convocatoria`.
5. **La respuesta es siempre una estimación**: `kind: "ESTIMATE"`, `modelVersion` y `warning: "modelo entrenado con datos sintéticos"`. Sin estimación, `available: false` con el motivo:

| Motivo | Cuándo |
|---|---|
| `STRICT_POLICY_EXCLUDED` | Política STRICT: el modelo no se entrenó con ella (P7/D3) |
| `NO_MONETARY_TARGET` | La convocatoria no acepta `MONETARY` |
| `NOT_STARTED` / `CAMPAIGN_ENDED` | Antes del inicio; `CLOSED` o pasado el fin |
| `TARGET_ALREADY_REACHED` | Ya recaudó la meta: el entrenamiento excluye esas instantáneas |
| `TOO_MANY_INTENTS` | Más de 10 000 intenciones leídas (tope de la lectura) |
| `UNSUPPORTED_CURRENCY` | Moneda distinta de COP (Carlos, 2026-10-07) |

6. **Fuera del rango de entrenamiento** (el modelo vio el 15 %, 25 % y 50 % del tiempo): se sirve la estimación con una segunda advertencia, no se oculta.

## 2. Variables con datos reales (DD-45, DD-46)

| Variable | Fuente real |
|---|---|
| `logTargetAmount` | `ln(targetAmount / 10^exponente)`: la meta en unidades enteras de la moneda (ISO 4217; COP = 2) |
| `durationDays`, `pctTimeElapsed` | `startDate`, `endDate` y el reloj del servidor |
| `orgPriorCampaigns` | convocatorias de la misma organización con `startDate` anterior |
| `paymentMethodsEnabled` | número de `acceptedPaymentMethods` |
| resto | intenciones de la convocatoria: `CONFIRMED` (en su `confirmedAt`) suma; `FUNDING_REJECTED` es intento sin fallo ni recaudo (como `rejectedByPolicy`); `FAILED` y `EXPIRED_UNKNOWN` son fallos; `PENDING` aún no cuenta |

## 3. Riesgos que esta enmienda no resuelve

- **Datos sintéticos.** El modelo nunca vio una convocatoria real; la advertencia es obligatoria y la UI debe separarlo de los hechos verificables (Q-v2-9).
- **Moneda.** El dataset está en COP: una convocatoria en otra moneda no tiene estimación (`UNSUPPORTED_CURRENCY`, Carlos, 2026-10-07).
- **Unidades (H-P3-1, corregido el 2026-10-07):** el backend guarda los importes en unidades mínimas (Q-CV01-3; COP, exponente 2) y el dataset sintético está en pesos. Las razones no cambian, pero `logTargetAmount` se calculaba sobre céntimos (ln(100) ≈ 4,6 de más). Ahora se calcula sobre unidades enteras con el exponente ISO 4217, y la paridad de variables se prueba alimentando Java con céntimos.
- **Reentrenar** exige volver a exportar y a pasar la paridad; el nombre del recurso lleva la versión del modelo.
- **El script de exportación** vive en `scripts/predictor/` de este repositorio porque lee los artefactos sin cambiarlos. Si Carlos prefiere que viva junto al predictor, se mueve sin tocar el backend.
