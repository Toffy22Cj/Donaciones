# Resultados del componente predictivo — baseline-0.2.0 (sin STRICT)

Generado el 2026-10-07 con `train_baseline.py` (scikit-learn 1.9.1), dataset `synthetic-v1` (sintético). Las cifras son la salida literal de `output/metrics.json`.

**Para ADR-044.** El ADR todavía no está en ningún repositorio (el catálogo de Donaciones lo marca como "número reservado"). Esta tabla sustituye a la de §4, y el texto de D3 va abajo, listo para pegar.

## Cambio respecto a baseline-0.1.0

- **Se excluyen las convocatorias STRICT del modelo v1** (ADR-044 P7/D3): 1161 *snapshots* menos.
- **La versión 0.1.0 tenía las métricas infladas.** STRICT rechaza la donación que excedería la meta, así que casi nunca llega al 100 % por diseño: en el dataset, 7 de 388 convocatorias STRICT y 18 de 1.161 *snapshots* usados. Con `targetPolicy` como *feature*, cualquier modelo acertaba esos negativos triviales, y eso inflaba el Brier, el AUC y sobre todo la ventaja frente al baseline de ritmo, que no ve la política.
- **El generador no produce CLOSE_ON_TARGET con rechazo del exceso:** su CLOSE_ON_TARGET acepta la donación y cierra la convocatoria. No hay otra variante con el mismo problema.

## §4 — Métricas (test por convocatoria, 513 *snapshots* de 204 convocatorias no vistas)

| Modelo | CV-Brier (5 folds) | Brier test | Log loss | ROC AUC | ECE | Acierto al 0,5 |
|---|---|---|---|---|---|---|
| pace_baseline | 0.1145 ± 0.0057 | 0.1283 | 0.4160 | 0.8936 | 0.0544 | 0.821 |
| logistic_regression | 0.0789 ± 0.0076 | 0.0978 | 0.3413 | 0.9332 | 0.0430 | 0.875 |
| hist_gradient_boosting | 0.0899 ± 0.0094 | 0.1133 | 0.3878 | 0.9332 | 0.0878 | 0.840 |

**Mejor por Brier:** logistic_regression.

### Mejora sobre el baseline de ritmo (Brier del baseline − Brier del modelo; positivo = mejor)

Mismos 5 folds de `GroupKFold` para todos los modelos, así que la diferencia por fold está emparejada.

| Modelo | CV: media ± desv. | Por fold | Test |
|---|---|---|---|
| logistic_regression | 0.0356 ± 0.0079 | 0.0408, 0.0413, 0.0267, 0.0253, 0.0440 | 0.0305 |
| hist_gradient_boosting | 0.0246 ± 0.0067 | 0.0329, 0.0307, 0.0150, 0.0199, 0.0243 | 0.0150 |

La mejora es positiva en los 5 folds para los dos modelos: es estable, no un efecto del corte.

### Control de *leakage* por permutación

logistic_regression entrenada 30 veces con etiquetas permutadas; AUC medio en test real: AUC medio **0.5089** ± 0.1182 → superado (debe centrarse en 0,5).

### Regresión complementaria (% final de la meta)

HistGradientBoostingRegressor: MAE **19.24 puntos %**, frente a 97.48 de la extrapolación lineal.

### Comparación con la versión anterior (con STRICT; inflada)

| | baseline-0.1.0 (con STRICT) | baseline-0.2.0 (sin STRICT) |
|---|---|---|
| Brier test, regresión logística | 0,0652 | 0.0978 |
| ROC AUC, regresión logística | 0,9707 | 0.9332 |
| Brier test, baseline de ritmo | 0,2122 | 0.1283 |
| Mejora sobre el baseline (test) | 0,1470 | 0.0305 |
| MAE del regresor (puntos %) | 13,91 | 19.24 |

**Cómo presentarlo:** "El modelo mejora al baseline de ritmo en 0.030 de Brier en test (0.036 ± 0.008 en validación cruzada, positiva en los 5 folds), una vez eliminado el sesgo de STRICT. La cifra anterior (0,147) estaba inflada porque STRICT entraba como negativos triviales."

## Texto para ADR-044 D3

> **Alcance del modelo v1:** se excluyen las convocatorias `STRICT`. Rechazan la donación que excedería la meta, así que casi nunca alcanzan el 100 % por diseño: no son un caso a predecir. Dejarlas dentro añadía negativos triviales que inflaban todas las métricas (P7). El modelo no da predicción para una convocatoria `STRICT`. Las variantes `CLOSE_ON_TARGET` del dataset aceptan la donación y cierran la convocatoria, así que no tienen el mismo problema. Si se modelara `CLOSE_ON_TARGET` con rechazo del exceso, se excluiría por el mismo motivo.

## Artefactos regenerados

`output/metrics.json`, `model_*.joblib` (cuatro), `calibration.png`, `reliability_by_t.png`, `sample_predictions.json`, `chart_basic.png`, `chart_advanced.png` y `mock_predictions_response_v2.json`. `generate_mock_v2.py` aplica el mismo filtro, así que la partición por convocatoria coincide con la de `train_baseline.py`.
