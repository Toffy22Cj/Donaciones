# ADR-044 — Enmienda 4: generador con mezcla de curvas de llegada y reentreno a `baseline-0.4.0`

## Status

**PROPUESTA — espera la aprobación de Manuel y de Carlos** (ADR-044 lo escribió Adonis y lo aprobó Carlos; Manuel aprobó la tarea el 2026-11-19, `decisiones-delegadas-2026-10.md`, «Firmas del 2026-11-19»). Redactada el 2026-11-20 por el agente. Plan 6 de `plan-tanda-2026-11-19.md`. **No autoriza código por sí sola:** el PR de código no se fusiona antes de que esta enmienda esté aprobada (regla 3.5). No toca `core` ni el contrato de la API.

## Por qué

La Enmienda 3 (APROBADA — Manuel, por delegación de Carlos, 2026-11-18) registró una limitación de `baseline-0.3.0`: **subestima la probabilidad de las convocatorias que recaudan de forma lineal**, porque el generador sintético con el que se entrenó reparte las donaciones **en U** (`generate_synthetic_dataset.py`, `_arrival_curve`: `1.6·exp(-x/0.12) + 0.35 + 1.1·exp(-(1-x)/0.08)`, normalizada a media 1). Al 25 % del tiempo, de 129 convocatorias que iban al ritmo lineal solo el 2,3 % alcanzó la meta en esos datos (Enm. 3, tabla 3). La Enmienda 3 dejó el cambio para «un plan aparte»; este es ese cambio.

## Decisión propuesta

- **E4-1. Mezcla de curvas.** El generador sortea, **por convocatoria**, una de tres curvas de llegada, todas normalizadas a media 1:
  - **en U** (la actual, sin cambios);
  - **lineal** (constante);
  - **concentrada al inicio** (decreciente; forma exacta en el plan de código y escrita en `manifest.json`).
  Proporciones: **U 40 %, lineal 40 %, concentrada al inicio 20 %** (Q-P-1; es una decisión de producto de Manuel y no hay datos reales que la respalden: la mezcla evita que una sola forma domine).
- **E4-2. Reproducibilidad.** `datasetVersion: synthetic-v2`; la misma semilla por defecto (`20261006`) y los mismos parámetros dan la **misma huella de `snapshots.csv`** en dos ejecuciones. El generador con la mezcla **desactivada** sigue dando la huella de la Enmienda 3 (`2d235ef5…`), de modo que `baseline-0.3.0` es reproducible.
- **E4-3. Modelo.** Reentreno a **`baseline-0.4.0`** con las mismas variables, los mismos hiperparámetros y las mismas guardas que 0.3.0 (rangos, STRICT, otra moneda, convocatoria cerrada). Solo cambia el conjunto de entrenamiento. Artefacto nuevo: `app/src/main/resources/predictor/campaign-predictor-baseline-0.4.0.json`. **El de 0.3.0 se conserva.**
- **E4-4. Cuál se usa.** `0.4.0` pasa a ser el modelo por defecto; `0.3.0` queda cargable por una propiedad de configuración (`traceability.prediction.model-resource`), **sin selector en la API** (Q-P-2 y Q-P-3).
- **E4-5. Paridad Python↔Java a 1e-9** para cada fila del archivo de paridad de 0.4.0 (`CampaignPredictorModelParityTest`, `TOLERANCE = 1e-9`), y la de variables (`CampaignFeatureBuilderParityTest`) intacta.
- **E4-6. Comparación honesta.** Métricas de 0.3.0 y de 0.4.0 **sobre el mismo conjunto de validación** (misma semilla de validación, huella registrada), con las cifras de 0.3.0 **recalculadas** allí, no copiadas, en `paxfide-predictor/RESULTADOS-baseline-0.4.0.md`; y las respuestas de `demo-historico` **antes y después** con el mismo escenario (`evidencia-fase6/paso2-escenario-historico-d4485a6-2026-11-16.md`).

## Qué no cambia

- **D2:** el componente sigue sin escribir en MongoDB, el Event Store ni nada que se ancle.
- **La advertencia «modelo entrenado con datos sintéticos»** (`synthetic: true`, Enmienda 1) **sigue siendo obligatoria**. Esta enmienda **no afirma** que 0.4.0 sea mejor para la realidad: no hay datos reales (ADR-044 §1). Puede mejorar el ritmo lineal y empeorar otro caso; por eso E4-6.
- La forma de la respuesta de la API de predicción: cambian los números y `modelVersion`.
- Sin dependencias nuevas: `numpy`, `pandas` y `scikit-learn` ya están en `paxfide-predictor/requirements.txt`.

## Riesgos

1. **Cambian los números que ve la gente** (web y móvil): se avisa en el PR y en la nota de la Enmienda 3.
2. **Comparación injusta** si los dos modelos se miden en conjuntos distintos: por eso E4-6 fija la semilla de validación.
3. **Mezcla arbitraria:** las proporciones son una elección, no un dato; se escriben en el manifiesto para poder cambiarlas con otra enmienda.

## Definición de hecho

Tests 1 a 8 del Plan 6 de `plan-tanda-2026-11-19.md` en verde con el rojo previo visible y las mutaciones muertas; paridad a 1e-9; tabla de métricas 0.3.0 frente a 0.4.0 con procedencia (comando, semilla, huella, entorno); respuestas de `demo-historico` antes y después; `scripts/ci-local.sh` completo con la salida de Surefire por módulo. Al fusionar el código se añade una nota fechada a la Enmienda 3 (la limitación se trata en 0.4.0), **sin reescribir** su texto.
