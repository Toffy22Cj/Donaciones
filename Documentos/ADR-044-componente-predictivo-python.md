# ADR-044 — Componente predictivo de convocatorias en Python (offline, solo lectura)

> **Procedencia (2026-10-08):** texto base aportado por Carlos en la "Autorización de trabajo autónomo (4)" con la instrucción "añades esto al proyecto en documentos". Se transcribe sin cambios desde aquí hasta la sección "Enmiendas", que es lo único añadido. Hasta ahora el documento base no estaba en este repositorio (`ADR-044-enmienda-1-prediccion-en-backend.md` lo citaba como "número reservado").

**Status:** PROPUESTO (2026-10-06). Pasa a APROBADO cuando se cumplan las condiciones de §7. Aprobar este ADR **no** autoriza integrar nada con el backend, `paxfide-web` ni `paxfide-mobile`: cada integración requiere su propio contrato y plan aprobado (regla 3.4).
**Fecha:** 2026-10-06 (revisión 1)
**Número:** **044, propuesto** — siguiente libre tras ADR-043 en la numeración vigente. No queda asignado hasta aprobación humana explícita (condición 1 de §7).

**Relacionados:** ADR-037 (Convocatoria; `targetPolicy`, `DonationIntent`, `CampaignFundingLedger`), ADR-040 (IA; histórico ADR-036, que excluye predicción y scoring de `ConvocatoriaAuditFacts`), ADR-038 (Identidad; roles ADMINISTRATOR/REPRESENTATIVE), `reglas-equipo-y-agentes.md` §3.5.

**Origen de la obligación:** regla 3.5 — introduce una tecnología nueva (Python y su ecosistema de ML).

---

## 1. Context

- **Requisito académico (pre-fase 7):** modelo predictivo en Python, con gráficos, cuya salida JSON pueda consumir Flutter.
- **Pregunta problema (equipo, 6 oct 2026):** ¿es posible estimar, con un modelo entrenado sobre datos históricos de campañas, la probabilidad de que una convocatoria alcance su meta, para identificar a tiempo las campañas en riesgo, **sin que esa estimación se confunda con los hechos verificables** del sistema de trazabilidad?
- **ADR-040 §2.1** excluye explícitamente análisis predictivo y scoring de `ConvocatoriaAuditFacts` y del pipeline de narrativa. Esto **no prohíbe** la predicción, pero **sí prohíbe** alojarla en `ai` o mezclarla con hechos o narrativa.
- **No existen datos históricos reales** todavía. El Event Store real ancla hashes en testnet: cualquier dato inventado que entre ahí quedaría "certificado" e imposible de borrar.
- **Fase 6 en curso** (APIs y frontend). La Enmienda 1 de Convocatoria aún tiene P1–P7 abiertos, así que el esquema de persistencia no es estable.

---

## 2. Decision

### D1 — Ubicación y naturaleza

- Componente **externo, offline y de solo lectura**. Repositorio propio, por ejemplo `paxfide-predictor`, separado del backend Maven.
- No es un módulo del monolito ni un servicio en tiempo de ejecución. Spring no lo invoca.
- Su CI propio (pytest) **no** forma parte de la regla "`mvn test` en verde en `develop`".

### D2 — Aislamiento de datos (invariante)

- **Cero escrituras** en MongoDB, Event Store, colecciones de módulos, `MerkleBatch` o cualquier artefacto que se ancle.
- El dataset sintético se genera **solo como archivos planos** (CSV; Parquet opcional), con semilla fija y `manifest.json` que incluye versión, semilla, parámetros y SHA-256 de cada archivo.
- El dataset sintético declara `synthetic: true` en el manifest y en cada artefacto derivado.
- Cuando existan datos reales, Python los leerá **de un export o de un contrato de lectura dedicado**, nunca de las colecciones internas de cada módulo. Ese contrato **no** se define aquí.

### D3 — Pregunta y etiqueta (decididas por el equipo, 6 oct 2026)

- **Objetivo principal:** clasificación binaria, P(recaudado al cierre ≥ 100 % de la meta).
- **Complementario:** % final estimado de la meta (regresión).
- **Puntos de evaluación:** t ∈ {0.15, 0.25, 0.50} del tiempo total.
- **Recaudado** = suma de donaciones `CONFIRMED` que entraron al ledger. Los `FAILED`, `EXPIRED_UNKNOWN` y los rechazos por `STRICT` no suman.

### D4 — Metodología de evaluación

- **Partición por convocatoria** (`GroupShuffleSplit` + `GroupKFold` sobre `campaignRef`). Ninguna convocatoria aparece en train y test a la vez, y se verifica con `assert`.
- **Features solo con información ≤ t.** Los atributos estáticos se conocen al crear la convocatoria; las columnas `label_*` nunca entran como feature (`assert`).
- **Exclusiones declaradas:**
  - Snapshots con la meta ya alcanzada en t: etiqueta trivial que inflaría las métricas.
  - Snapshots de convocatorias `CLOSE_ON_TARGET` ya cerradas en t: no hay nada que predecir.
- **Métricas principales:** Brier score y calibración (ECE, curva de calibración), porque el producto muestra una probabilidad. Secundarias: log loss y ROC AUC. Accuracy solo como referencia.
- **Control de fuga obligatorio:** el AUC medio de 30 modelos entrenados con etiquetas permutadas debe estar en 0,5 ± 0,05.
- **Modelos:** baseline de ritmo (logística con solo `paceRatio`), regresión logística y `HistGradientBoosting`.

### D5 — Artefacto de predicción

- Es un artefacto **separado de los hechos**, con esta forma mínima: `kind: "ESTIMATE"`, `disclaimer`, `campaignRef`, `t`, `probabilityReachTarget`, `estimatedFinalPctOfTarget`, `modelVersion`, `datasetVersion`, `synthetic`.
- **Nunca** entra al Event Store, al hash, a un `MerkleBatch`, a `ConvocatoriaAuditFacts`, a `DonationAuditFacts` ni al prompt o la narrativa del LLM.

### D6 — Visibilidad (decidida por el equipo, 6 oct 2026)

- Solo ADMINISTRATOR o REPRESENTATIVE de la organización dueña de la convocatoria.
- Fuera de toda vista pública y de donantes. Esto evita el efecto de profecía autocumplida sobre los donantes.
- **El mecanismo de autorización no se define aquí.** Hoy no existe ninguna vía de exposición (ver §5).

### D7 — Dependencias

| Dependencia | Uso | Estado |
|---|---|---|
| Python 3.13 | Runtime | Decidido (versión verificada: 3.13.16) |
| numpy, pandas | Generación y features | Decidido |
| scikit-learn | Modelos y métricas | Decidido (1.9.1) |
| matplotlib | Gráficos | Decidido |
| joblib | Serialización de modelos | Decidido. **Solo cargar modelos propios**: `joblib.load` ejecuta código arbitrario |
| pyarrow | Parquet | Opcional |
| Servidor mock JSON para Flutter | Requisito académico | **No decidido** en este ADR |

Cualquier otra dependencia, por ejemplo XGBoost o un framework web, requiere enmienda.

---

## 3. Alternatives

| Alternativa | Por qué se descarta |
|---|---|
| Alojar la predicción en el módulo `ai` | ADR-040 §2.1 excluye predicción y scoring; mezclaría estimaciones con hechos auditables |
| Microservicio Python en línea llamado por Spring | Dependencia en tiempo de ejecución y fallos ambiguos sin necesidad demostrada |
| Exportar a ONNX/PMML y ejecutar en Java | Viable si la predicción pasa a producto; hoy añade complejidad sin beneficio |
| Sembrar Mongo con inserts directos desde Python | Salta invariantes (STRICT, CLOSE_ON_TARGET, ledger) y arriesga contaminar el Event Store anclado |
| Usar un mismo generador para el seed de demo y el dataset de entrenamiento | Mezcla dos destinos con riesgos distintos |
| Partición aleatoria por snapshot | La misma convocatoria aparecería en train y test: fuga de información |
| Evaluar solo con accuracy | No mide si la probabilidad mostrada es confiable |

---

## 4. Consequences

### Resultados verificados (ejecución del 6 oct 2026, `synthetic-v1`, semilla 20261006)

Dataset: 1500 convocatorias, 318 218 `DonationIntent`, 4381 snapshots. 119 snapshots se omiten por convocatoria ya cerrada en t. Tasa de éxito: 51,27 %. Dos ejecuciones con la misma semilla producen SHA-256 idénticos.

Partición: se excluyen 571 snapshots con la meta ya alcanzada. Quedan 3060 snapshots de train (1125 convocatorias) y 750 de test (282 convocatorias), con solapamiento 0.

| Modelo | CV-Brier | Test Brier | Log loss | ROC AUC | ECE |
|---|---|---|---|---|---|
| Baseline de ritmo | 0,2056 ± 0,0103 | 0,2122 | 0,6194 | 0,7577 | 0,1333 |
| Regresión logística | 0,0667 ± 0,0087 | **0,0652** | 0,2333 | 0,9707 | 0,0337 |
| HistGradientBoosting | 0,0650 ± 0,0079 | 0,0828 | 0,2950 | 0,9604 | 0,0545 |

- **Control de fuga:** AUC medio con etiquetas permutadas = 0,4928 (± 0,113). Pasa.
- **Regresión del % final:** MAE de 13,91 puntos porcentuales, frente a 112,04 de la extrapolación lineal ingenua. La extrapolación falla porque el pico de donaciones al inicio la sesga hacia arriba.

### Advertencia obligatoria para el informe académico

**Estas métricas no demuestran capacidad predictiva en el mundo real.** El generador es simple: un atractivo latente fijo, una curva de llegadas determinista y ruido Poisson. Con cientos de donaciones por convocatoria, el estado en t casi determina el resultado, y el modelo aprende las reglas del generador. Que la regresión logística supere a los árboles es coherente con un generador multiplicativo y suave. Con datos reales se espera un rendimiento menor, y hay un cambio de dominio que debe medirse.

### Positivas

- Cero acoplamiento con el monolito y cero riesgo para la cadena de integridad.
- Resultados reproducibles y verificables por hash.
- La separación entre estimación y hecho queda expresada en el propio artefacto (`kind: "ESTIMATE"`).

### Negativas y deuda aceptada

- No hay vía de exposición a la organización: hoy la predicción solo existe como archivo.
- El generador no modela shocks a mitad de campaña, estacionalidad ni dependencia entre campañas de la misma organización.
- Las features asumen que `DonationIntent` tendrá marca temporal por intent. Además, sigue sin verificarse en código que `Fund` lleve `campaignRef` (pendiente heredado de D4 de Convocatoria).

---

## 5. Pendientes registrados — NO decididos por este ADR

| ID | Tema | Efecto mientras siga abierto |
|---|---|---|
| P1 | Servidor mock JSON para Flutter (tecnología, endpoint, forma) | Requisito académico sin cubrir por este ADR |
| P2 | Vía de exposición real a ADMINISTRATOR/REPRESENTATIVE (contrato HTTP, autorización) | La predicción no es visible en producto |
| P3 | Contrato de lectura (export) para datos reales | Solo es posible entrenar con datos sintéticos |
| P4 | Fuente de datos públicos de crowdfunding y su licencia | Cambio de dominio sin medir |
| P5 | Seed de la BD de demo para front y APIs (debe pasar por comandos o APIs, nunca inserts directos) | Fuera de este componente; requiere decisión propia |
| P6 | Realismo del generador (shocks, estacionalidad) | Métricas optimistas |
| P7 | **Etiqueta mal definida para `STRICT`** (hallazgo del 6 oct). STRICT rechaza la donación que excedería la meta, así que el recaudo queda justo por debajo (mediana 99,8 %) y solo el 1,8 % de las convocatorias STRICT llega a ≥ 100 %, frente a 69 % en FLEXIBLE y 66 % en CLOSE_ON_TARGET. Hay que decidir qué significa "meta alcanzada" en STRICT (por ejemplo, ≥ 99 % o "cupo lleno") o excluir STRICT del modelo | Las métricas de §4 incluyen STRICT como negativos fáciles y están infladas; el mock de UI excluye STRICT |
| P8 | Superficies de visualización: el equipo pidió **web y Flutter** (6 oct). En Flutter contradice ADR-043 D1/D10 (el móvil es para donante y operador; lo administrativo va en web) | Requiere enmienda a ADR-043, o tratar la pantalla Flutter como demo académica fuera del árbol de rutas v1 |

---

## 6. Fuera de este ADR

- Cualquier cambio en `ai`, `convocatoria`, `core` o en los ADR-037/040.
- La UX de la predicción en `paxfide-web`.
- El reentrenamiento periódico y el monitoreo de deriva.

---

## 7. Condiciones de aprobación

| # | Condición | Estado |
|---|---|---|
| 1 | Número del ADR (044 propuesto) | PENDIENTE |
| 2 | D1–D7 aprobados por el equipo (D3 y D6 ya decididos el 6 oct 2026; falta confirmar su redacción) | PENDIENTE |
| 3 | Aceptar las exclusiones de D4 (snapshots con la meta ya alcanzada o la convocatoria cerrada) como decisión metodológica | PENDIENTE |
| 4 | Aceptar que P1 (servidor mock) queda fuera y se resuelve por enmienda o artefacto propio | PENDIENTE |

---

## Enmiendas

*Sección añadida el 2026-10-08 (encargo 6, P2). El texto base de arriba no cambia; cada enmienda dice qué sustituye.*

### Enmienda 1 — P3: el backend sirve la predicción, evaluada en Java

- **Documento:** `ADR-044-enmienda-1-prediccion-en-backend.md`. Decisiones DD-41 a DD-47, **ratificadas por Carlos el 2026-10-07**.
- **Qué cambia del base:**
  - **D1:** "Spring no lo invoca" sigue siendo cierto para el código Python. El modelo entrenado se exporta a JSON y la aplicación lo evalúa en Java (`CampaignPredictorModel`), con paridad de 1e-9 frente a scikit-learn.
  - **§4 Negativas, "No hay vía de exposición":** queda resuelto.
  - **§5 P2, vía de exposición:** resuelto con `GET /api/v1/organizations/{organizationId}/campaigns/{campaignRef}/prediction`, solo para `ADMINISTRATOR` o `REPRESENTATIVE` de la organización (D6), con `kind: "ESTIMATE"`, sin STRICT (P7) y solo en COP.
- **Cambio posterior (encargo 5, punto 3, Carlos, 2026-10-08):** fuera del rango de entrenamiento (t < 0,15 o t > 0,50) no se da ninguna cifra, sino el motivo explícito `OUTSIDE_TRAINED_RANGE` (#94). Esto sustituye el punto 1.6 de la Enmienda 1 ("se sirve la estimación con una segunda advertencia").

### Enmienda 2 — D1: el componente vive en `Donaciones/paxfide-predictor/`

**Estado:** APROBADA — Carlos, 2026-10-08 (encargo 6, P2: "Enmienda de ADR-044 D1: el componente vive en `Donaciones/paxfide-predictor/`").

- **Sustituye en D1:** "Repositorio propio, por ejemplo `paxfide-predictor`, separado del backend Maven" por "directorio `paxfide-predictor/` del repositorio `Donaciones`, **fuera del build de Maven**: no es un `<module>` del `pom.xml` raíz".
- **Se mantiene todo lo demás de D1:** componente offline y de solo lectura; Spring no invoca el código Python; su CI (pytest) no forma parte de la regla "`mvn test` en verde en `develop`" ni de `scripts/ci-local.sh`.
- **Reglas del directorio:**
  - `__pycache__/`, `*.pyc` y `paxfide-predictor/.venv/` están en `.gitignore`;
  - solo contiene datos **sintéticos** (`data/manifest.json` declara `synthetic: true`), el código de generación y entrenamiento y los artefactos exportados (`output/`);
  - **ningún archivo de datos personales**.
- **D2 no cambia:** el componente sigue sin escribir en MongoDB, el Event Store ni nada que se ancle.
