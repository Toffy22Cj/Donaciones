# Resultados de `baseline-0.4.0` frente a `baseline-0.3.0` (ADR-044 Enmienda 4, PROPUESTA)

**Datos sintéticos.** Nada de esto mide la realidad de PaxFide: no hay datos reales (ADR-044 §1). La advertencia «modelo entrenado con datos sintéticos» sigue siendo obligatoria.

## Qué cambió
El generador sortea, por convocatoria, una curva de llegada: **en U 40 %, lineal 40 %, concentrada al inicio 20 %** (`generate_synthetic_dataset.py --curve-mix`, `synthetic-v2`). El modelo (regresión logística + regresor del % final) es el mismo, con las mismas variables y guardas; solo cambian los datos de entrenamiento.

## Procedencia (todo se repite con estos comandos)
| Qué | Valor |
|---|---|
| Commit del código | el de la rama `feat/predictor-baseline-0-4-0` (ver el PR) |
| Entorno | Python 3.13.18, numpy 2.5.5, pandas 3.1.1, scikit-learn 1.9.1 (el de la Enmienda 3) |
| Entrenamiento de 0.3.0 | `generate_synthetic_dataset.py --curve-mix u=1` (semilla 20261006, 1 500 convocatorias): huella de `snapshots.csv` `2d235ef5504720b4370567c33ee29a5ad326c2f3cb035a562a65e48abea0e67a`, la de la Enmienda 3. **El artefacto reentrenado es idéntico byte a byte al `campaign-predictor-baseline-0.3.0.json` del repositorio** (`cmp`), de modo que el entorno reproduce el modelo anterior |
| Entrenamiento de 0.4.0 | `generate_synthetic_dataset.py` (mezcla por defecto, semilla 20261006, 1 500): huella de `snapshots.csv` `67dde78aa3ecef2d42f12e8a21dff9a4bcba507527bdc23be99c8e4960d2ec5f`; `train_baseline.py` y `scripts/predictor/export_baseline_json.py` |
| Validación | **otra semilla** (20261107), mezcla por defecto, 1500 convocatorias; `snapshots.csv` `c3d2e2cf993282726882e2b47b890c7a4029651a9cb68f7b8df35e6db3da2b5f`; 2669 instantáneas tras quitar la meta ya alcanzada y STRICT. Ninguno de los dos modelos la vio |
| Comando | `python compare_models.py --old <modelos 0.3.0> --new <modelos 0.4.0> --out <dir>` |

## Métricas sobre el MISMO conjunto de validación (recalculadas aquí, ninguna copiada)
| Subconjunto | n | Brier (menor es mejor) 0.3.0 → 0.4.0 | AUC 0.3.0 → 0.4.0 | ECE 0.3.0 → 0.4.0 |
|---|---|---|---|---|
| **Todo el conjunto** | 2669 | 0.1716 → **0.1347** | 0.8772 → 0.8873 | 0.131 → 0.0249 |
| Convocatorias con llegada **en U** | 1062 | 0.0793 → **0.1024** | 0.9587 → 0.9405 | 0.0172 → 0.0609 |
| Convocatorias con llegada **lineal** | 1187 | 0.2521 → **0.1453** | 0.9109 → 0.9145 | 0.2885 → 0.1461 |
| Convocatorias con llegada **concentrada al inicio** | 420 | 0.1777 → **0.1866** | 0.9572 → 0.9624 | 0.2153 → 0.2433 |
| Corte t = 0,15 | 1031 | 0.1999 → **0.1461** | 0.8566 → 0.8608 | 0.1737 → 0.0404 |
| Corte t = 0,25 | 925 | 0.1771 → **0.138** | 0.8737 → 0.8808 | 0.1419 → 0.0476 |
| Corte t = 0,50 | 713 | 0.1235 → **0.1141** | 0.9136 → 0.9165 | 0.0717 → 0.02 |

**El caso de la Enmienda 3** (ritmo lineal 0,9–1,2 al 25 % del tiempo, `n = 131`): frecuencia observada de alcanzar la meta **0.3664**; probabilidad media que predice 0.3.0 **0.0744**; la de 0.4.0 **0.3257**.

## Lectura honesta
- **Mejora** el conjunto entero y, sobre todo, las convocatorias de llegada **lineal**, que era la limitación de la Enmienda 3 (Brier 0.2521 → 0.1453), y la calibración global (ECE 0.131 → 0.0249).
- **Empeora** las convocatorias de llegada **en U** (Brier 0.0793 → 0.1024) y, algo, las **concentradas al inicio** (Brier 0.1777 → 0.1866): el modelo **no sabe qué forma tiene la convocatoria** (no hay una variable que la distinga), así que promedia las tres. Con 0.3.0 pasaba lo contrario: acertaba en U y fallaba en lineal.
- **Que el conjunto de validación venga de la misma mezcla favorece a 0.4.0 en el total**; por eso se separa por curva. Si en la realidad dominara la llegada en U, 0.3.0 sería mejor. Esa es una pregunta sobre los datos reales, que no existen.

## Las respuestas de `demo-historico`, antes y después (mismo escenario, misma base, mismo instante)
Ambas ejecuciones corren contra la **misma base** sembrada una vez (`dev,demo-seed,demo-historico`), con el backend arrancado dos veces: la primera con `TRACEABILITY_PREDICTION_MODEL_RESOURCE=/predictor/campaign-predictor-baseline-0.3.0.json` (los números **coinciden con la evidencia anterior** `paso2-escenario-historico-d4485a6-2026-11-16.md`: 0,9691/1,4121, 0,069/0,8203, 0,4327/1,1827 y 0,0045/0,3618) y la segunda con el modelo por defecto. Las respuestas literales: `evidencia-fase6/p6-escenario-historico-baseline-0.3.0-*.md` y `…-0.4.0-*.md`.

| Convocatoria | Transcurrido | Recaudado / meta | `baseline-0.3.0` | `baseline-0.4.0` |
|---|---|---|---|---|
| [Demo] Cerca del límite | 47.0% | 80.0% | P=0.9691 · final=1.4121 | P=0.874 · final=1.5695 |
| [Demo] Después del rango | 65.0% | 80.0% | `OUTSIDE_TRAINED_RANGE` | `OUTSIDE_TRAINED_RANGE` |
| [Demo] Cuarenta por ciento | 40.0% | 45.0% | P=0.069 · final=0.8203 | P=0.2068 · final=0.89 |
| [Demo] Un cuarto | 25.0% | 45.0% | P=0.4327 · final=1.1827 | P=0.6463 · final=1.3727 |
| [Demo] Política estricta | 30.0% | 20.0% | `STRICT_POLICY_EXCLUDED` | `STRICT_POLICY_EXCLUDED` |
| [Demo] Meta alcanzada | 30.0% | 105.0% | `TARGET_ALREADY_REACHED` | `TARGET_ALREADY_REACHED` |
| [Demo] En dólares | 30.0% | 25.0% | `UNSUPPORTED_CURRENCY` | `UNSUPPORTED_CURRENCY` |
| [Demo] Inicio del rango | 17.0% | 12.0% | P=0.0045 · final=0.3618 | P=0.1637 · final=0.8257 |
| [Demo] Cerrada antes de tiempo | 20.0% | 4.0% | `CAMPAIGN_ENDED` | `CAMPAIGN_ENDED` |
| [Demo] Antes del rango | 7.0% | 3.0% | `OUTSIDE_TRAINED_RANGE` | `OUTSIDE_TRAINED_RANGE` |
| Abrigo para el invierno | 0.0% | 5.0% | `OUTSIDE_TRAINED_RANGE` | `OUTSIDE_TRAINED_RANGE` |
| Kits de higiene (enlace privado) | 0.0% | 6.7% | `OUTSIDE_TRAINED_RANGE` | `OUTSIDE_TRAINED_RANGE` |
| Útiles escolares 2026 | 0.0% | 6.0% | `CAMPAIGN_ENDED` | `CAMPAIGN_ENDED` |


`P` = `probabilityReachTarget`, `final` = `estimatedFinalPctOfTarget`. Los casos que no dan cifra (fuera de rango, STRICT, otra moneda, meta alcanzada, cerrada) **no cambian**.
