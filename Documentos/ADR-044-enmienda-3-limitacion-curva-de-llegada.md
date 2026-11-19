# ADR-044 — Enmienda 3: limitación conocida — el modelo reproduce la curva de llegada del generador sintético

## Status

**APROBADA — Manuel, por delegación de Carlos, 2026-11-18** (acordado en conversación con Carlos; registro en `decisiones-delegadas-2026-10.md`, sección «Firmas por delegación (2026-11-18)»). ADR-044 lo escribió Adonis y lo aprobó Carlos; esta enmienda **no cambia ninguna decisión**: documenta una limitación. Redactada el 2026-11-19 por Manuel, con asistencia de Claude, por su decisión del 2026-11-18: «enmienda corta de ADR-044, "limitación conocida", con tu tabla de cifras y la consecuencia. Sin cambiar modelo ni generador».

**No autoriza código ni cambia `baseline-0.3.0`.** Todo lo que sigue se midió sobre el repositorio en el estado que dice §Procedencia, y se puede repetir con los comandos del final.

## Qué se encontró

El modelo `baseline-0.3.0` (regresión logística para la probabilidad de alcanzar la meta) **reproduce la curva de llegada de donaciones del generador sintético con el que se entrenó**, no una propiedad de las campañas reales. Ese generador reparte las donaciones con una **curva en U** (muchas al principio y al final, pocas en medio; `_arrival_curve`, `paxfide-predictor/generate_synthetic_dataset.py:50-54`). Consecuencia: en los datos de entrenamiento, una convocatoria que recauda **al ritmo lineal** (ritmo ≈ 1) casi nunca alcanza la meta, y el modelo lo aprendió.

**Ritmo** = `pctRaised / pctTimeElapsed` (la variable `paceRatio`): 1,0 es ir justo al ritmo lineal.

### 1. La curva de llegada del generador

Fracción de las donaciones esperadas que ya han llegado, para una convocatoria de 60 días (`_arrival_curve(60)`, acumulada y normalizada):

| Tiempo transcurrido | 15 % | 25 % | 40 % | 50 % |
|---|---|---|---|---|
| Donaciones ya llegadas | 31 % | 41 % | 52 % | 58 % |

### 2. Lo que ve el modelo al entrenar

Mediana del ritmo en el entrenamiento, por corte: **2,61** (15 %), **1,99** (25 %) y **1,26** (50 %). Las medias del propio modelo exportado (`classifier.scalerMean` en `app/src/main/resources/predictor/campaign-predictor-baseline-0.3.0.json`) son `pctTimeElapsed` 0,28, `pctRaised` 0,489 y `paceRatio` 2,061: el modelo se entrenó con convocatorias que ya llevan casi la mitad de la meta cuando solo ha pasado el 28 % del tiempo.

### 3. Frecuencia real de alcanzar la meta, según el ritmo (datos sintéticos de entrenamiento)

Cada celda es `n` instantáneas y el porcentaje de ellas cuya convocatoria **sí alcanzó** la meta. Solo instantáneas con la meta aún sin alcanzar (`alreadyReachedAtT == 0`, el mismo filtro del entrenamiento: `train_baseline.py:134`). Con ritmo > 3 y > 2 al 50 % no hay casos suficientes y la tabla los deja vacíos.

| Ritmo | al 15 % del tiempo | al 25 % del tiempo | al 50 % del tiempo |
|---|---|---|---|
| ≤ 0,9 | 103 · 3,9 % | 146 · 2,1 % | 300 · 1,0 % |
| 0,9 – 1,2 | 87 · 3,4 % | **129 · 2,3 %** | 212 · 22,6 % |
| 1,2 – 1,5 | 114 · 12,3 % | 140 · 16,4 % | 209 · 62,2 % |
| 1,5 – 2 | 177 · 23,2 % | 242 · 46,3 % | 382 · 50,0 % |
| 2 – 3 | 339 · 57,8 % | 351 · 72,4 % | — |
| > 3 | 587 · 71,2 % | 292 · 59,6 % | — |

Lectura: al 25 % del tiempo, de 129 convocatorias que iban al ritmo lineal (0,9–1,2), solo el **2,3 %** alcanzó la meta. Con el mismo 35–55 % recaudado: al 25 % del tiempo alcanza la meta el 47,9 % (n = 384) y al 50 %, el 9,5 % (n = 273).

### 4. Qué hace el modelo en la demo local

Respuestas reales de `GET /api/v1/organizations/{organizationId}/campaigns/{campaignRef}/prediction` de la demo, de `ADMINISTRATOR`, sobre el backend local con `dev,demo-seed,demo-historico` (commit `d4485a6`, sembrada el 2026-11-16T07:54Z; fichero `Documentos/evidencia-fase6/paso2-escenario-historico-d4485a6-2026-11-16.md`). «Ritmo» aquí es el cociente de las columnas «Recaudado» y «Transcurrido» de ese fichero, a título orientativo:

| Convocatoria «[Demo]» | Tiempo | Recaudado | Ritmo | `probabilityReachTarget` |
|---|---|---|---|---|
| Inicio del rango | 17 % | 12 % | 0,71 | 0,0045 |
| **Cuarenta por ciento** | 40 % | 45 % | 1,13 | **0,069** |
| Un cuarto | 25 % | 45 % | 1,80 | 0,4327 |
| Cerca del límite | 47 % | 80 % | 1,70 | 0,9691 |

Una convocatoria que va un poco por encima del ritmo lineal («Cuarenta por ciento», 45 % al 40 % del tiempo) recibe un 6,9 %: es coherente con la tabla 3, no un error de cálculo.

## Procedencia (para repetirlo)

| Dato | Valor |
|---|---|
| Repositorio | `Toffy22Cj/Donaciones`, `develop` en `bcaa5dc` (2026-11-19) |
| Generador | `paxfide-predictor/generate_synthetic_dataset.py` (sha256 `aa1cd349f1b85960…`; último cambio `f2e84fa`) |
| Parámetros | los de por defecto: semilla `20261006`, 1 500 convocatorias, `datasetVersion: synthetic-v1`, `synthetic: true` (`data/manifest.json`) |
| Resultado | 4 381 instantáneas; **3 810** sin la meta ya alcanzada, de 1 407 convocatorias (las que usa esta enmienda) |
| Huella de `snapshots.csv` | `2d235ef5504720b4370567c33ee29a5ad326c2f3cb035a562a65e48abea0e67a` |
| Entorno | Python 3.13.18, numpy 2.5.5, pandas 3.1.1, scikit-learn 1.9.1 |
| Repetición | la misma huella en dos ejecuciones independientes (2026-11-15 y 2026-11-19) |

```bash
cd paxfide-predictor && python generate_synthetic_dataset.py --out /ruta/data   # verificar la huella de snapshots.csv
python analisis_ritmo.py /ruta/data                                            # el script de abajo
```

```python
# analisis_ritmo.py — ritmo frente a «alcanza la meta» (la tabla 3 y los datos de §2)
import sys, pandas as pd
d = pd.read_csv(sys.argv[1] + "/snapshots.csv")
d = d[d.alreadyReachedAtT == 0]
bins = [0, 0.9, 1.2, 1.5, 2.0, 3.0, 99]
d["ritmo"] = pd.cut(d.paceRatio, bins, labels=["<=0,9", "0,9-1,2", "1,2-1,5", "1,5-2", "2-3", ">3"])
for t, g in d.groupby("t"):
    print(t, len(g), round(g.paceRatio.median(), 2))
    print(g.groupby("ritmo", observed=True).label_reachedTarget.agg(["count", "mean"]).round(3))
```

## Decisión

1. **Se registra como limitación conocida** de `baseline-0.3.0`: **subestima la probabilidad de las convocatorias que recaudan de forma lineal**, porque se entrenó con datos sintéticos que llegan en U.
2. **No cambia el modelo ni el generador.** La advertencia «modelo entrenado con datos sintéticos» (Enmienda 1) sigue siendo obligatoria y es la que avisa de esto al usuario; no se añade texto nuevo a la interfaz en este corte.
3. **Qué no se sabe:** si las convocatorias reales de PaxFide siguen una curva en U, una lineal u otra. No hay datos reales (ADR-044 §1). Esta enmienda **no afirma** que el modelo sea malo para la realidad, solo que su comportamiento ante el ritmo lineal viene del generador.

## Siguiente paso — no autorizado todavía

Después de las fotos (decisión de Manuel, 2026-11-18): un **plan aparte** para un generador con mezcla de curvas (en U, lineal y concentrada al inicio), reentrenar a `baseline-0.4.0` y repetir la paridad Python↔Java con tolerancia 1e-9. **La enmienda de ese cambio se redacta cuando Manuel la pida**; hasta entonces, nada de esto se hace.
