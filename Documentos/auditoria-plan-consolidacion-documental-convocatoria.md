# Auditoría documental 2 — Plan de consolidación de la documentación de Convocatoria (Fase 6)

**Tipo:** solo lectura y planificación. No se modificó, renombró, movió ni borró ningún documento. No se tocó código, tests, contratos ni ADR. No se creó ningún ADR ni contrato. No se hizo commit ni push.

**Fecha:** 2026-10-03. **Rama:** `develop`, HEAD `673eda9`.

**Perímetro:** solo la documentación cuyo contenido pertenece a Convocatoria (Capa 1 de Fase 6).
- Los documentos de otros bloques aparecen únicamente para explicar una referencia, y no se propone cambiarlos.
- El inventario de partida es `auditoria-inventario-documental-convocatoria.md`, que sirve de base para:
  - los 19 documentos DIRECTOS;
  - los grupos GV-1 a GV-8;
  - las contradicciones CD-01 a CD-14.

**Naturaleza:** informe de auditoría. No es documentación canónica. **Todo lo que aquí se llama "propuesta" requiere aprobación humana antes de ejecutarse.** Esto lo exigen:
- la regla 2.1 de `reglas-equipo-y-agentes.md`: no crear documentos ni "siguiente fase" sin aprobación, y detenerse ante contradicciones;
- la regla de la propia serie: `convocatoria-resumen.md:91` exige una enmienda para que una decisión modifique un ADR.

**Identificadores nuevos de este informe:**
- **GC-n:** grupo de consolidación.
- **DH-C-n:** decisión humana previa a consolidar.

No coinciden con identificadores existentes.

---

## 0. Qué significa "consolidar" según la propia documentación del proyecto

No existe en `Documentos/` ninguna política de archivado: no hay carpeta de históricos, ni regla de mover documentos, ni regla de borrado. Este informe no la inventa. La consolidación que se propone se apoya solo en precedentes escritos.

| Precedente | Qué establece | Evidencia |
|---|---|---|
| P-A | Una enmienda es un archivo propio que **se inserta** en el ADR original y le cambia el estado; el archivo de la enmienda se conserva | `ADR-024-enmienda-narrativa-endpoint.md:3` ("Insertar esta sección en `ADR-024-…`, cambiando el estado … a 'Approved with amendment'"); el estado consolidado se ve en `documento-maestro-proyecto.md:162` |
| P-B | La propia Enmienda 1 enumera, como [ESTADO], los cambios que deben hacerse **dentro** de ADR-037 | `ADR-037-enmienda-1:294-314` (§10.1 y §10.2) |
| P-C | El texto sustituido se conserva en el lugar, marcado, en vez de borrarse | `ADR-037:127` ("*[Sustituido por la Enmienda 2 …]* Texto original: …"); `convocatoria-resumen.md:470` ("Se conserva el texto original como registro"), `:477` |
| P-D | Las correcciones se registran explícitamente, sin editar la historia en silencio | `convocatoria-resumen.md:85` |
| P-E | Un documento sustituido se marca como superado, no se elimina | `ADR-037:80` ("`fase-6-…` §3.4 debe marcarse como superado"); `estado-fase5.md:34` (un ADR "SUPERSEDIDO" que se conserva) |
| P-F | Las correcciones documentales de Convocatoria se hacen en una rama `chore/` después de la enmienda | `convocatoria-resumen.md:210,303`; `reglas-equipo-y-agentes.md` §3.1 |
| P-G | Las decisiones humanas se registran en `convocatoria-resumen.md` §6.x antes de cambiar nada (precedente de §6.17, DH-5) | `convocatoria-resumen.md:458,466`; `implementation_plan.md:4` |
| P-H | Las auditorías son evidencia, no normativa | `ADR-037-enmienda-2:7`; cabeceras de las auditorías ("No es documentación canónica") |

**Definición que se propone, derivada de P-A a P-H.** Consolidar es:
1. incorporar al texto de ADR-037 las decisiones **aprobadas** de sus enmiendas, conservando el texto sustituido marcado en su lugar (P-A, P-B, P-C);
2. conservar íntegros los archivos de las enmiendas como fuente y trazabilidad (P-A);
3. dejar en un único documento el índice de qué es vigente y qué es histórico, sin editar las auditorías (P-E, P-H);
4. hacer todo en una rama `chore/` documental, después de las decisiones humanas previas (P-F, P-G).

**Ningún archivo desaparece. Ningún texto se pierde.**

---

## 1. Tipos documentales

Se usa la tipología pedida. Un mismo documento puede contener varios tipos; en ese caso se separa por secciones.

| Tipo | Documentos de Convocatoria (o sección) |
|---|---|
| **A. Normativa** | ADR-037; Enmienda 1; Enmienda 2 (pendiente de aprobación); ADR-043 (propuesto); `convocatoria-resumen.md` §6.7, §6.10–§6.15, §6.19 (DH-5) y §6.20 (registro de decisiones humanas) |
| **B. Operativa** | `estado-fase6.md` (fila 1 de §2, §3bis, primera viñeta de §5); `implementation_plan.md` §2 (estado de partida del código) |
| **C. Histórica** | `convocatoria-resumen.md` §1–§5, §6.2–§6.6, §6.9, §6.17, §6.18; `fase-6-estructura-y-perimetro-convocatoria.md` §3; texto sustituido dentro de ADR-037 y de la Enmienda 1 |
| **D. Auditoría** | Las once auditorías y el debate del bloque; `auditoria-inventario-documental-convocatoria.md`; este informe |
| **E. Contrato** | Sin documento propio de Convocatoria. Su contrato HTTP vive en documentos de la Capa 5 (`api-contract-matrix.md` §2, §2b y §3; ADR-041). El contrato de aplicación real (comandos, resultados, errores) y el modelo Mongo real solo están descritos en una auditoría (`auditoria-documental-convocatoria.md`, Partes 3 y 5) |
| **F. Plan** | `implementation_plan.md` |

**Hallazgo de tipo.** Dos informaciones que deberían ser de tipo E o F existen hoy **solo dentro de una auditoría** (tipo D):
- el contrato de aplicación real de Convocatoria;
- el modelo MongoDB real (nombres reales de las siete colecciones, índices, `_id` de los comandos de sistema, política de reintento con seis intentos y backoff).

Además, `implementation_plan.md` §16 (criterio 7: "Resumen de decisiones de implementación entregado para revisión humana: nombres de excepciones, nombres provisionales de clases y colecciones, firmas, parámetro de vencimiento") no tiene ningún documento de entrega en `Documentos/`. Ver GC-6 y GC-7.

---

## 2. Grupos de consolidación

Los grupos salen de la evidencia del inventario (GV-1 a GV-8 y CD-01 a CD-14) y de la tabla de decisiones del §4.

### GC-1 — ADR-037 y sus enmiendas

**Documentos relacionados:**
- `ADR-037-convocatoria-ledger-assignment-donationintent.md`;
- `ADR-037-enmienda-1-convocatoria.md`;
- `ADR-037-enmienda-2-convocatoria.md`.

Repiten sus decisiones: `convocatoria-resumen.md` §2, §6.7–§6.20; `implementation_plan.md` §1, §3–§10; `fase-6-…` §3; `estado-fase6.md` §3bis.

Auditorías relacionadas: toda la cadena GV-4.

**Información única:**
- **ADR-037:**
  - justificación de §2.3 con código citado;
  - D1 (§2.6bis), con el Camino B descartado;
  - razonamiento de "`fundId` estable" (§2.6bis);
  - razonamiento de "el `REPRESENTATIVE` no cuenta" (§2.5);
  - alternativas descartadas (§4);
  - seis identificadores de observabilidad (§6);
  - tabla de riesgos (§7) y autocorrección del descubrimiento (§7, `:185`);
  - trazabilidad de verificación (§8).
- **Enmienda 1:**
  - tabla "Decisiones que modifica" (§2);
  - alternativas descartadas de la ronda D2–D4 (§2.1);
  - etiquetas editoriales y regla N11 (§1);
  - P1–P7 con columnas "ya fijado / falta decidir" (§8);
  - compatibilidad y migración (§9);
  - dependencias (§7);
  - aprobación N1–N12 (§10.3);
  - historial de revisiones (`:10-14`).
- **Enmienda 2:**
  - tabla F-1…D7 (§1.1);
  - diagrama del flujo definitivo (§3);
  - razonamiento del `_id` como subdocumento (§3.3);
  - tabla de excepciones frente a efecto sobre la intención (§4);
  - P8, P9 y P10 (§7);
  - tabla de dependencias (§6);
  - lista de aprobación (§9).

**Información duplicada:**
- Las decisiones de la Enmienda 1 vuelven a describirse como tablas en `convocatoria-resumen.md` §6.7–§6.14 (origen) y como especificación en `implementation_plan.md` §3–§10.
- Las de la Enmienda 2, en `convocatoria-resumen.md` §6.20, `implementation_plan.md` §4.4, §7.2 y §8, y `estado-fase6.md` §3bis.

**Decisiones vigentes:**
- ADR-037, salvo lo modificado;
- la Enmienda 1 (APROBADA, `:3`), salvo §5.3 y la frase de N9, que sustituye la Enmienda 2.

**Pendiente de aprobación (no vigente según su propio texto):** la Enmienda 2 (BORRADOR, `:3`). Sus decisiones de origen sí están aprobadas (`convocatoria-resumen.md` §6.20, `:516`).

**Decisiones históricas:**
- ADR-037 §2.6, paso 3 original (ya marcado así en `:127`);
- D2 "sin autoasignación" (ADR-037 §5, `:157`, §7, `:173`);
- "ledger 1:1" (§2.2);
- Enmienda 1 §5.3 (barrera `PENDING → CONFIRMED`) y la frase de N9.

**Propuesta de consolidación (no ejecutada):**
1. **Primero, la Enmienda 1 (aprobada).** Incorporar a ADR-037 los cambios [ESTADO] que la propia Enmienda 1 prescribe en §10.1 y §10.2:
   - cabecera;
   - §3 Consecuencias;
   - §5 Autorización;
   - §7 Riesgos (D2 resuelto, R4 resuelto, P1–P7);
   - §8 Trazabilidad.

   Además, en cada sección modificada (§2.1, §2.2, §2.4, §2.5, §2.6, §5), insertar el texto vigente con la marca "*[Enmienda 1 §x]*" y conservar el texto sustituido marcado "*Texto original:*" (P-B, P-C). Esto cierra CD-01.
2. **Después, la Enmienda 2, solo si se aprueba (DH-C-1).** Mismo procedimiento en §2.6 (estados y flujo) y en la referencia a la barrera. Si no se aprueba, las anotaciones "Sustituido por la Enmienda 2" que hoy tiene ADR-037 (`:5`, `:119`, `:127`) deben revisarse, porque presentan como sustitución vigente un texto en borrador (CD-03).
3. **Conservar** los dos archivos de enmienda **íntegros**, como fuente y trazabilidad (P-A). Contienen información que no debe volcarse en el ADR: etiquetas editoriales, tablas de aprobación, historial de revisiones y alternativas de la ronda. ADR-037 los referencia en una sección "Historial de enmiendas" (ya existe una línea "Enmendado por", `:5`).
4. **No** incorporar a ADR-037:
   - evidencia de auditorías;
   - resultados de tests;
   - planes de tareas;
   - el debate de C-01.

   Solo se citan como trazabilidad (requisito de no convertir el ADR en "basurero").

**Alternativa sujeta a decisión (DH-C-2):** en lugar de editar ADR-037 en el lugar, crear un "texto consolidado" separado. Este informe **no la recomienda**, por tres motivos:
- crearía un documento nuevo (regla 2.1);
- crearía una tercera versión de las mismas decisiones (aumenta GV-7);
- el precedente P-A edita el ADR original.

La decisión es humana.

### GC-2 — Recuperación y aplicación de fondos (ADR-043)

**Documentos:**
- `ADR-043-recuperacion-aplicacion-fondos-convocatoria.md`;
- Enmienda 2 §3.2, §4 y §5;
- `convocatoria-resumen.md` §6.20 (D2, D3, P9);
- `implementation_plan.md` §4.4, §8 y §11 (fila del orquestador);
- `estado-fase6.md:74,81`.

**Información única de ADR-043:**
- alternativas descartadas (outbox propio, disparador externo, consulta bajo demanda, bloqueo distribuido);
- deuda del coste de la consulta entre colecciones;
- precedente `BlockchainAnchorProducer`;
- resultado por intención (punto 4).

**Duplicada:** el mecanismo (disparo inmediato, scheduler y consulta) se repite en la Enmienda 2 §5, el resumen §6.20 D2 y el plan §8 y §11.

**Propuesta:** ADR-043 **se mantiene como documento independiente**.
- La Enmienda 2 dice que lo exige la regla 3.5 (`ADR-037-enmienda-2:6`; `reglas-equipo-y-agentes.md` §3.5: un mecanismo de recuperación nuevo requiere su propio ADR).
- No se fusiona con ADR-037: ADR-037 solo lo referencia.
- Su estado "Propuesto" depende de DH-C-1.

### GC-3 — Registro de decisiones humanas

**Documentos:**
- `convocatoria-resumen.md` §6;
- `estado-fase6.md:68` (G1–G3);
- `implementation_plan.md` (cabecera de revisiones `:4`, §14.2).

**Información única:**
- **§6.1:** vocabulario ("responsable" frente a `REPRESENTATIVE`, `IN_KIND`, `GATEWAY`…) y la tabla de renumeración.
- **§6.2–§6.6:** opciones, conflictos C-D2.x, huecos H-D3.x y H-D4.x, y orden de trabajo.
- **§6.9:** verificación de código del 30 sept, incluida la lista de defectos documentales de §6.9.4.
- **§6.15:** R1, ID, P7 y R3, que acotan el corte y "no reescriben la enmienda".
- **§6.16:** B-1.
- **§6.17–§6.19:** cadena C-01 y K-1/K-2.
- **§6.20:** F-1…D7 y P9.
- **G1–G3:** solo en `estado-fase6.md:68` (CD-10).

**Duplicada:**
- §6.7, §6.10–§6.14, ya formalizadas en la Enmienda 1;
- §6.20, en la Enmienda 2;
- §6.17, §6.18 y §6.19, entre sí (GV-3).

**Propuesta:**
1. `convocatoria-resumen.md` §6 **se mantiene como registro cronológico de decisiones humanas** (P-G). No se fusiona con ADR-037, porque contiene razonamiento, opciones y verificaciones que no son normativos.
2. Las secciones ya formalizadas en una enmienda se conservan íntegras. Basta una marca de "formalizada en Enmienda N §x", del mismo estilo que `:167` y `:178`.
3. **G1–G3:** si se confirma que fueron decisiones humanas (DH-C-4), registrarlas en una sección nueva §6.x del resumen (P-G), sin borrarlas de `estado-fase6.md`. El inventario observó además que G3 no figura en la Enmienda 1 §4.2.
4. **R1, ID, P7 (corte), R3 y B-1 (§6.15–§6.16):** se mantienen en el resumen y en el plan. No se elevan a ADR salvo decisión humana, porque §6.15 declara que acotan el primer corte y "no reescriben la enmienda" (`:420`).

### GC-4 — Perímetro y resumen conceptual

**Documentos:**
- `fase-6-estructura-y-perimetro-convocatoria.md` §3;
- `convocatoria-resumen.md` §1–§5;
- ADR-037 §1.

**Información única:**
- **`fase-6-…`:**
  - §3.1, tabla de casos de uso del MVP con su estado de confirmación;
  - acuerdos de la reunión del 18 sept (simultaneidad descartada, 24/7);
  - §3.3, hallazgo "`Fund` por donante";
  - §3.4, decisión STRICT original (histórica);
  - §3.6, sin read model nuevo;
  - §3.9, lista de fuera de alcance;
  - §3.10, dependencias.

  **§1–§2 y §4–§5 son transversales a Fase 6, no de Convocatoria.**
- **`convocatoria-resumen.md`:**
  - §2, panel de descubrimiento con sus dos casos de uso;
  - `visibility`, `status` y ventana como tres conceptos distintos (`:40`);
  - §4, restricción de paginación del listado público (`:77`): no aparece en ningún ADR ni en el plan;
  - §5, nota de corrección.

**Duplicada:** casi todo §3 de `fase-6-…` y §2 del resumen fueron absorbidos por ADR-037 y la Enmienda 1.

**Propuesta:**
1. **Ninguno se fusiona.** Ambos se conservan como contexto histórico (tipo C).
2. En `fase-6-…`, **solo en §3** (lo único de Convocatoria), aplicar la marca de superación que ADR-037 §2.3 ya ordena (`ADR-037:80`, P-E) y la de subordinación N11 en §3.2 y §3.5 (CD-11). §1–§2 y §4–§5 no se tocan, porque son de toda Fase 6.
3. En el resumen §2–§4, marcar las frases sustituidas, por ejemplo "No hay autoasignación" frente a la Enmienda 1 §4.3 o "Autorización de respaldo CERRADA" frente a P6 (CD-05). Se usa el estilo de nota ya presente (`:24`, `:28`).
4. **Información que debe rescatarse en lo normativo:** la restricción de paginación del listado público (resumen `:53`, `:77`) es un requisito que no está en ningún ADR. Su destino natural es el contrato del endpoint (ADR-041 / matriz, Capa 5). No es tarea de Convocatoria editarlos: se comunica como dependencia (§3, GC-8).

### GC-5 — Plan del primer corte

**Documento:** `implementation_plan.md`.

**Información única:**
- estado de partida del código el 30 sept (§2);
- diseño de X1, X2, I1, H1 y T1;
- reglas de transacciones (§4.4);
- orden de tareas (§15);
- tests exigidos (§12–§13);
- criterios de aceptación (§16);
- historial de revisiones 2–3 (cabecera).

**Duplicada:** las decisiones que el plan cita de las enmiendas y del resumen.

**Propuesta:** se mantiene **independiente**, como plan (tipo F), sin fusionarse con el ADR. Correcciones internas (CD-07):
- cabecera de aprobación de la rev. 3 (DH-C-5);
- base normativa (añadir la Enmienda 2 y corregir rutas inexistentes);
- §3.5 (lista de estados sin `FUNDING_REJECTED`);
- extraer el párrafo de revisiones de la cabecera a una sección "Historial de revisiones", sin cambiar su texto.

### GC-6 — Contrato de aplicación y API de Convocatoria

**Documentos:**
- `api-contract-matrix.md` §2, §2b y §3 (Capa 5);
- ADR-041 (Capa 5);
- `auditoria-documental-convocatoria.md` Parte 3 y "API CONTRACT HANDOFF";
- `implementation_plan.md` §5 y §6.

Fuera de perímetro: `plan-api-fase6.md`, `revision-ready-api-fase6.md`, `auditoria-api-convocatoria*.md`.

**Información única:**
- **Solo en la auditoría documental:**
  - firmas reales de los comandos y resultados A1–A12;
  - excepciones reales por operación;
  - operaciones sin ruta (A3–A6, A11, A12).
- **Solo en la matriz:**
  - rutas y modelo `ConvocatoriaAdminReadModel` (§2b).

**Duplicada:** la lista de operaciones (ADR-037 §5; plan §5–§6; matriz; auditoría).

**Propuesta:**
1. La **ruta HTTP, el cuerpo y la respuesta** pertenecen a la Capa 5 (ADR-041; Enmienda 1 §7.3, P4). Convocatoria **no** consolida ni edita la matriz ni ADR-041. Las discrepancias de CD-09 se entregan como dependencia al bloque API (§3, GC-8).
2. El **contrato de aplicación** (operaciones, comandos, resultados, excepciones) sí es de Convocatoria y hoy solo existe en una auditoría. Destino propuesto: el "resumen de decisiones de implementación" que el plan ya exige (§16, criterio 7), entregado como sección del plan (§16) o anexo del plan. Así no se crea un documento ni un contrato nuevos sin aprobación (DH-C-6).
3. La auditoría documental se conserva intacta como evidencia de origen.

### GC-7 — Modelo MongoDB de Convocatoria

**Documentos:**
- `implementation_plan.md` §4.2 (nombres "provisionales");
- `auditoria-documental-convocatoria.md` Parte 5 y "BD HANDOFF" (nombres y campos reales);
- índices normativos en ADR-037 §2.4, Enmienda 1 §4.2 y Enmienda 2 §3.3 (`_id` de sistema).

**Información única de la auditoría:**
- nombres reales `convocatoria_processed_commands` y `convocatoria_audit_log`;
- campos por colección;
- nombres de índices (`uq_public_code`, `uq_active_employee_assignment`, `uq_fund_id`, `uq_payment_session_id`);
- reintento de 6 intentos con backoff de 10–500 ms;
- `sequence` del audit log monótona solo por proceso;
- índices de rendimiento ausentes;
- la consulta recuperable como agregación con `$lookup`.

**Propuesta:**
- Los **índices que protegen invariantes** siguen en el ADR (ya están: ADR-037 §2.4, Enmienda 1 §4.2, Enmienda 2 §3.3).
- El **modelo físico real** pasa al plan §4.2 (sustituyendo "provisional" por el nombre real y conservando la mención de que fue provisional) o al mismo anexo de §16 de GC-6 (DH-C-6).
- La política de reintento con sus cifras no está en ningún canónico (auditoría documental, Parte 7). Su destino es el plan §4.4, donde ya figura "reintento acotado".
- La auditoría se conserva intacta.

### GC-8 — Documentos de otros bloques que repiten o derivan decisiones de Convocatoria (no se consolidan)

**Documentos:**
- `golden-path.md`, pasos 2–3 (Capa 6);
- `api-contract-matrix.md` (Capa 5);
- `ADR-040`, `ADR-041`;
- `ia-resumen.md` §3;
- `identity-resumen.md`;
- `hallazgos-front-fase2.md`;
- `plan-correccion-fase5-e-ia.md`, `estado-fase5.md`.

**Hecho relevante:** `golden-path.md` y `api-contract-matrix.md` tienen **cambios sin commit** que introducen el flujo de la Enmienda 2 (`git diff`).

**Propuesta:** Convocatoria **no edita** estos documentos. Se entrega a cada bloque dueño la lista de discrepancias (CD-05, CD-09, CD-12 y referencias del Paso 6 del inventario).

Los cambios sin commit en `golden-path.md` y `api-contract-matrix.md` se presentan al humano para que decida (DH-C-7):
- si forman parte del trabajo de Convocatoria;
- o si los revisan los dueños de las Capas 5 y 6.

### GC-9 — Estado operativo

**Documentos:**
- `estado-fase6.md` (fila 1 de §2, §3bis, primera viñeta de §5);
- `auditoria-cierre-final-convocatoria.md` §5;
- `auditoria-porcentaje-convocatoria-fase6.md`.

**Información única de `estado-fase6.md`:**
- salidas literales de Surefire (línea base, 168, 190);
- incidencia `WT_PANIC`;
- lista de pruebas de mutación;
- bloqueos.

**Duplicada:** el estado del bloque se describe también en el plan, el resumen y dos auditorías, con cifras distintas (GV-7).

**Propuesta:**
- `estado-fase6.md` es el **único** documento operativo de estado (regla 2.4: se actualiza solo con resultados de ejecución).
- Corregir solo sus partes de Convocatoria (CD-08):
  - §1 dice "solo Blockchain";
  - §2 fila 1 cita la rev. 2.2 y no la Enmienda 2;
  - §5 cita la severidad de `clearFundsGenesis` sin la barrera.

  Se hace con una nueva ejecución real y **sin borrar las cifras anteriores**, añadiéndolas como histórico de §3bis.
- Las auditorías de estado se conservan como evidencia.

### GC-10 — Auditorías y evidencia del bloque

**Documentos:**
- **Cadena GV-4:** `auditoria-c01-idempotencia-fondos.md`, `auditoria-c01-postcorreccion.md`, `auditoria-cierre-f1-f2.md`, `auditoria-flujo-confirmed-fondos-v3.md`, `debate-cierre-confirmed-fondos.md`, `auditoria-preimplementacion-cierre-convocatoria.md`, `auditoria-delimitacion-cierre-convocatoria.md`, `auditoria-cierre-final-convocatoria.md`.
- **GV-5 y GV-6:** `auditoria-herencia-fase5-convocatoria.md`, `auditoria-documental-convocatoria.md`, `auditoria-porcentaje-convocatoria-fase6.md`, `auditoria-inventario-documental-convocatoria.md` y este informe.

**Propuesta:** **se conservan intactas** (P-H). No se fusionan entre sí ni con normativa, y no se editan para marcarlas como históricas. Su estado (histórica o vigente) se declara desde el índice (§5) y desde las secciones de trazabilidad de ADR-037 y la Enmienda 2, que ya las citan (`ADR-037-enmienda-2:7`).

---

## 3. Matriz documento a documento

Destinos posibles: **CONSOLIDAR** (su contenido normativo vigente se incorpora a otro documento y el archivo se conserva), **CONSERVAR COMO HISTÓRICO**, **CONSERVAR COMO EVIDENCIA** o **MANTENER INDEPENDIENTE**.

| Grupo documental | Documento | Tipo | Información única | Información duplicada | Decisión relacionada | Destino propuesto | Motivo |
|---|---|---|---|---|---|---|---|
| GC-1 | ADR-037 | A | Justificaciones §2.3, §2.5, §2.6bis; alternativas §4; observabilidad §6; riesgos §7; trazabilidad §8 | Repetido por el resumen §2, `fase-6-…` §3 y el plan | Todo el modelo de Convocatoria | **MANTENER INDEPENDIENTE como documento canónico** y receptor de la consolidación | P-A, P-B |
| GC-1 | Enmienda 1 | A | Etiquetas, N11, alternativas §2.1, P1–P7 (§8), migración §9, N1–N12, historial de revisiones | Decisiones repetidas en el resumen §6.7–§6.14 y en el plan | D2, D3, D4, `CLOSE_ON_TARGET`, cierre, idempotencia | **CONSOLIDAR** en ADR-037 (decisiones) y **conservar el archivo íntegro** | P-A, P-B (§10.1) |
| GC-1 | Enmienda 2 | A (borrador) | Flujo en dos actos, `_id` de sistema, tabla de excepciones, P8–P10, dependencias | Repetida en el resumen §6.20, el plan rev. 3, `estado-fase6.md` §3bis, el golden path y la matriz | F-1, F-2, D1, D3, D5, D6 | **CONSOLIDAR** en ADR-037 **solo tras su aprobación** (DH-C-1); conservar el archivo íntegro | `:3`, §9 |
| GC-2 | ADR-043 | A (propuesto) | Alternativas, deuda de coste, multiinstancia, resultado por intención | Mecanismo repetido en la Enmienda 2 §5, el resumen §6.20 D2 y el plan §8 y §11 | D2 (recuperación), P9 | **MANTENER INDEPENDIENTE** | Regla 3.5; Enmienda 2 `:6` |
| GC-3, GC-4 | `convocatoria-resumen.md` | §6.x: A (registro); §1–§5, §6.2–§6.6, §6.9, §6.17–§6.18: C | Vocabulario §6.1, razonamiento y opciones, verificación §6.9, R1/ID/P7/R3 (§6.15), B-1, cadena C-01, paginación pública (§4) | §6.7–§6.14 → Enmienda 1; §6.20 → Enmienda 2; §6.17–§6.19 entre sí | Todas | **MANTENER INDEPENDIENTE** como registro de decisiones e índice (propuesta §5). Secciones ya formalizadas: marcar, no mover | P-G; `:4` (su propósito declarado es ver "de un vistazo qué quedó decidido") |
| GC-4 | `fase-6-estructura-y-perimetro-convocatoria.md` | C (§3); §1–§2 y §4–§5 transversales | Casos de uso del MVP (§3.1), acuerdos del 18 sept, hallazgo "`Fund` por donante", STRICT original, fuera de alcance | §3.2 y §3.5 → Enmienda 1; §3.4 → ADR-037 §2.3 | Perímetro, STRICT, responsables | **CONSERVAR COMO HISTÓRICO** (§3 marcado como superado o subordinado); §1–§2 y §4–§5 sin tocar | `ADR-037:80`; N11 |
| GC-5 | `implementation_plan.md` | F (§2: B) | Estado de partida, X1, X2, I1, H1, T1, §4.4, tareas, tests, DoD, historial | Decisiones de las enmiendas y el resumen | Ejecución del primer corte | **MANTENER INDEPENDIENTE**; receptor del contrato de aplicación y del modelo Mongo real (§16 / §4.2) | Regla 3.4; §16 criterio 7 |
| GC-9 | `estado-fase6.md` (solo Convocatoria) | B | Evidencia Surefire, `WT_PANIC`, mutaciones, G1–G3, bloqueos | Estado repetido en el plan, el resumen y las auditorías | Estado del corte | **MANTENER INDEPENDIENTE**; corregir solo la parte de Convocatoria sin borrar cifras | Regla 2.4 |
| GC-10 | `auditoria-c01-idempotencia-fondos.md` | D | Reproducción del bug C-01 original | — | C-01 | **CONSERVAR COMO EVIDENCIA** (histórica) | P-H |
| GC-10 | `auditoria-c01-postcorreccion.md` | D | R-1…R-3, sondas, reauditoría DH | — | DH-1–DH-5 | **CONSERVAR COMO EVIDENCIA** (histórica) | Fuente de §6.17 (`:458`) |
| GC-10 | `auditoria-cierre-f1-f2.md` | D | Matriz documento → regla → código; X-1…X-4 | — | F-1, F-2 | **CONSERVAR COMO EVIDENCIA** (histórica) | Fuente de la Enmienda 2 (`:7`) |
| GC-10 | `auditoria-flujo-confirmed-fondos-v3.md` | D | CASO 3; matrices Q1–Q3 | — | F-1, F-2 | **CONSERVAR COMO EVIDENCIA** (histórica) | Fuente de la Enmienda 2 |
| GC-10 | `debate-cierre-confirmed-fondos.md` | D (análisis) | Opciones y ataques adversariales de D1–D4 | — | D1–D4 | **CONSERVAR COMO HISTÓRICO** (explica cómo se llegó a D1–D7) | Fuente de la Enmienda 2 |
| GC-10 | `auditoria-preimplementacion-cierre-convocatoria.md` | D | GAP-1…GAP-4 | — | D1–D7 | **CONSERVAR COMO EVIDENCIA** (histórica) | Fuente de la Enmienda 2 |
| GC-10 | `auditoria-delimitacion-cierre-convocatoria.md` | D | Convenciones de nombre del estado terminal (§F); matriz trabajo → módulo | — | D3 (`FUNDING_REJECTED`), frontera | **CONSERVAR COMO EVIDENCIA** (histórica) | Fuente de la Enmienda 2 |
| GC-10, GC-9 | `auditoria-cierre-final-convocatoria.md` | D | Criterios de cierre verificados | Estado (GV-7) | Cierre | **CONSERVAR COMO EVIDENCIA** (último snapshot de GV-4) | P-H |
| GC-10 | `auditoria-herencia-fase5-convocatoria.md` | D | Mapa Fase 5 → Convocatoria; H-1…H-3 | Numeración (con el inventario) | Numeración, herencia | **CONSERVAR COMO EVIDENCIA** | P-H |
| GC-6, GC-7, GC-10 | `auditoria-documental-convocatoria.md` | D | **Único lugar** con el contrato de aplicación real, el modelo Mongo real y las cifras de reintento | Catálogo (con el inventario) | API, Mongo, reintento | **CONSERVAR COMO EVIDENCIA**; su contenido técnico se **copia** (no se mueve) al plan (GC-6, GC-7) | Información de tipo E/F atrapada en tipo D |
| GC-9, GC-10 | `auditoria-porcentaje-convocatoria-fase6.md` | D | Porcentajes de avance | Estado; dictamen opuesto (GV-6) | Cierre | **CONSERVAR COMO EVIDENCIA** sin fecha ni naturaleza declaradas (constatarlo en el índice) | P-H |
| GC-10 | `auditoria-inventario-documental-convocatoria.md` | D | Inventario, CD-01…CD-14 | Con la auditoría documental (GV-5) | — | **CONSERVAR COMO EVIDENCIA** | P-H |
| GC-10 | Este informe | D | Plan de consolidación | — | — | **CONSERVAR COMO EVIDENCIA** | P-H |
| GC-8 | `golden-path.md`, `api-contract-matrix.md`, `ADR-040`, `ADR-041`, `ia-resumen.md`, `identity-resumen.md`, `hallazgos-front-fase2.md`, `plan-correccion-fase5-e-ia.md`, `estado-fase5.md` | Otro bloque | — | Repiten o derivan decisiones de Convocatoria | Varias | **MANTENER INDEPENDIENTE**; fuera de esta consolidación | Perímetro |

---

## 4. Matriz de decisiones

| Decisión de Convocatoria | Documento actual (fuente) | Documentos que la repiten | Fuente que debería quedar como canónica | ¿Necesita consolidación? |
|---|---|---|---|---|
| Modelo de convocatoria (`status` persistido, `publicCode`, visibilidad, configuración versionada, `acceptedDonationTypes` y `acceptedPaymentMethods`, `onTargetReached`) | ADR-037 §2.1; Enmienda 1 §3.1–§3.2 | Resumen §2, §6.3–§6.5, §6.8; `fase-6-…` §3.2; plan §3.1 | ADR-037 consolidado | **Sí**: incorporar la Enmienda 1 §3 a §2.1 (P-B) |
| Datos mínimos (R1: `title`, fechas, `currency`) | Resumen §6.15 | Plan §3.1 | Resumen §6.15 (decisión del corte); plan §3.1 (especificación) | No: se mantiene donde está ("no reescribe la enmienda", `:420`) |
| Responsables (`actingRole`, dos operaciones, autoasignación del administrador, contador) | ADR-037 §2.4–§2.5; Enmienda 1 §4 | Resumen §2, §6.2, §6.7, §6.12; `fase-6-…` §3.2; plan §3.2–§3.3, §6 | ADR-037 consolidado | **Sí** (cierra CD-01 y CD-11) |
| G1–G3 (ledger al quitar `MONETARY`; `replacementActingRole`; una asignación activa por persona y convocatoria) | `estado-fase6.md:68` (documento de estado) | Ninguno | Resumen §6.x nuevo, si se confirma (DH-C-4) | **Sí**: registrar (CD-10) |
| Cierre manual | Enmienda 1 §3.4 | Resumen §6.14; plan §10 | ADR-037 consolidado | **Sí** (Enmienda 1 §10.1) |
| `DonationIntent` (campos, `paymentMethod`, `confirmationSource`, versión, vencimiento, precondiciones) | ADR-037 §2.6; Enmienda 1 §5.1–§5.2 | Resumen §6.4, §6.11; plan §3.5, §9 | ADR-037 consolidado | **Sí** |
| Estados de `DonationIntent` (`PENDING/CONFIRMED/FAILED/EXPIRED-UNKNOWN` + `FUNDING_REJECTED`) | ADR-037 §2.6 (`:118-119`); Enmienda 2 §4 | Resumen §6.20 D3; plan §3.5 (sin `FUNDING_REJECTED`), §4.4; matriz `:104`; `estado-fase6.md:73` | ADR-037 consolidado tras DH-C-1 | **Sí**, además de CD-07 y la grafía de CD-13 (DH-C-8) |
| Semántica de `CONFIRMED` (no significa fondos aplicados) | Enmienda 2 §3.1; resumen §6.20 F-1/F-2 | Resumen §6.17–§6.19 (históricas); plan §7.2, §9.2; golden path; matriz | ADR-037 consolidado tras DH-C-1 | **Sí** |
| Confirmación manual (solo `ADMINISTRATOR`, nunca pasarela, `confirmedBy`) | Enmienda 2 §3.1 (D5) | Resumen §6.20; plan §9.2; Enmienda 1 N9 (sustituida) | ADR-037 consolidado tras DH-C-1 | **Sí** |
| Autorización (crear, asignar, cerrar, intención pública, webhook por firma) | ADR-037 §5; Enmienda 1 §3.4, §4 | Plan §6; matriz §2 | ADR-037 §5 consolidado | **Sí** (Enmienda 1 §10.1 añade el cierre) |
| Respaldo del `REPRESENTATIVE` / P6 | Enmienda 1 §4.4, §7.1 (P6 pendiente; dueño: ADR-032) | Resumen §2 y §4 ("CERRADA"); `fase-6-…` §3.5; golden path §3; matriz `:53` | Enmienda 1 §8 (P6) hasta que se enmiende ADR-032 | **Sí**: marcar las frases contradictorias del resumen y de `fase-6-…` (CD-05). Las de otros bloques, en GC-8 |
| Pasarela / webhook (correlación por `paymentSessionId`, `providerEventId`) | ADR-037 §2.6 pasos 1–2; Enmienda 1 §5.3 (P3) | Resumen §6.12; plan §1.3; matriz §3; golden path | ADR-037 consolidado; contrato HTTP en Capa 5 | Parcial: P3 sigue pendiente |
| D1 (el cierre no invalida intenciones ya aceptadas) | ADR-037 §2.6bis | Enmienda 1 §3.4, §5.4; resumen §6.14; plan §10 | ADR-037 §2.6bis | No (ya canónica, sin contradicciones) |
| `CampaignFundingLedger` (escritura condicional, existe solo con `MONETARY`, `REJECT_EXCESS` completo) | ADR-037 §2.2; Enmienda 1 §3.1, §3.3 | Resumen §6.5, §6.11, §6.14; plan §3.4, §8 | ADR-037 consolidado | **Sí** (N2 modifica "1:1") |
| Transacción `STRICT` (ledger + génesis + outbox en `app`; reintento que envuelve la transacción completa; T1) | ADR-037 §2.3; Enmienda 1 §6 | Resumen §2, §5, §6.9.5, §6.15; plan §8; `fase-6-…` §3.4 (histórico) | ADR-037 consolidado | **Sí** |
| `APPLY_FUNDS` (barrera por intención, `_id = {commandType, commandId}`) | Enmienda 2 §3.3; resumen §6.20 D1 | Plan §4.2, §7.2, §8; `estado-fase6.md:72`; golden path | ADR-037 consolidado tras DH-C-1 | **Sí** |
| Idempotencia de comandos de cliente (`commandId`, resultado original N12, I1) | Enmienda 1 §3.5; plan §7.1 (I1) | Resumen §6.13 | ADR-037 consolidado (N12); plan §7.1 (I1, implementación) | **Sí** |
| Idempotencia de creación de intención (ID) | Resumen §6.15 | Plan §7.3 | Resumen §6.15 + plan §7.3 | No |
| `FUNDING_REJECTED` | Enmienda 2 §4; resumen §6.20 D3 | Plan §4.4, §8; `estado-fase6.md:73`; golden path; matriz | ADR-037 consolidado tras DH-C-1 (P10 pendiente) | **Sí** |
| Recuperación (disparo inmediato, scheduler, consulta, P9) | ADR-043; Enmienda 2 §5 | Resumen §6.20 D2, P9; plan §8, §11; `estado-fase6.md:74,81` | **ADR-043** (independiente) | No fusionar: solo referenciar desde ADR-037 |
| Dinero no aceptable (P1) | Enmienda 1 §3.3 (N4, P1); Enmienda 2 §4 (D4) | Resumen §6.4, §6.11; plan §14.1 | ADR-037 consolidado (pendiente P1) | **Sí** |
| APIs de Convocatoria (HTTP) | `api-contract-matrix.md` §2, §2b, §3 (Capa 5); ADR-041 | Plan §1.3; auditoría documental Parte 3 | Capa 5 (ADR-041 / matriz) | **No en este bloque**; entregar CD-09 al bloque API |
| Contrato de aplicación (comandos, resultados, excepciones) | **Solo** `auditoria-documental-convocatoria.md` Parte 3 | — | Plan §16 / anexo (DH-C-6) | **Sí**: hoy está atrapado en una auditoría |
| MongoDB (colecciones, índices, `_id`, reintento) | Índices: ADR-037 §2.4, Enmienda 1 §4.2, Enmienda 2 §3.3. Modelo real: **solo** la auditoría documental Parte 5 | Plan §4.2 (provisional) | Índices: ADR-037 consolidado. Modelo físico: plan §4.2 / §16 | **Sí** |
| Dependencias externas (ADR-038/X1, T1, P8, P3, ADR-041/P4, ADR-032/P6, ADR-029, ADR-040/N10) | Enmienda 1 §7; Enmienda 2 §6; plan §11 | `estado-fase6.md:81-83`; auditorías | Enmienda 1 §7 + Enmienda 2 §6 → ADR-037 §3 (Enmienda 1 §10.1); plan §11 (operativo) | **Sí** |
| Golden Path (paso 3) | `golden-path.md` (Capa 6) | Matriz | `golden-path.md` (Capa 6) | No en este bloque (GC-8, DH-C-7) |
| Numeración de los ADR de Fase 6 | Enmienda 1 `:5-7`; resumen §6.1 (fuente inexistente) | `plan-correccion-…`, `estado-fase5.md` (otro bloque) | Nueva sección §6.x del resumen, si el humano lo decide (DH-C-3) | **Sí** (CD-02) |
| B-1 (dirección `app → convocatoria`) | Resumen §6.16 | Plan §2, §11, §15 | Resumen §6.16 + plan §11 | No |
| P2–P7 (pendientes) | Enmienda 1 §8 | Resumen §6.6; plan §14.1 | Enmienda 1 §8 → ADR-037 §7 (§10.1) | **Sí** (incorporar como filas de §7) |

---

## 5. Matriz de conservación

"Se elimina" no es una opción. Ningún archivo se borra, se mueve ni se renombra.

| Documento | ¿Se conserva? | ¿Se consolida? | ¿Dónde queda su información? | ¿Qué parte debe permanecer histórica? |
|---|---|---|---|---|
| ADR-037 | Sí | Es el receptor | En sí mismo, con las enmiendas aprobadas incorporadas | El texto original sustituido, marcado en su lugar (P-C) |
| Enmienda 1 | Sí, íntegra | Sus decisiones, en ADR-037 | ADR-037 (decisiones) + su propio archivo (trazabilidad, N1–N12, P1–P7, historial) | Todo el archivo, como registro de la enmienda |
| Enmienda 2 | Sí, íntegra | Solo tras su aprobación | ADR-037 + su propio archivo | Todo el archivo |
| ADR-043 | Sí | No | En sí mismo | — |
| `convocatoria-resumen.md` | Sí | No (es registro); se marcan las secciones ya formalizadas | En sí mismo, como registro de decisiones e índice | §1–§5, §6.2–§6.6, §6.9, §6.17, §6.18 (y §6.19 en lo que sustituye §6.20) |
| `fase-6-estructura-y-perimetro-convocatoria.md` | Sí | No | En sí mismo; §3 marcado | §3 completo como perímetro original; §3.4 explícitamente superado |
| `implementation_plan.md` | Sí | Recibe el contrato de aplicación y el modelo físico (copia desde la auditoría) | En sí mismo | Historial de revisiones 2–2.5; §2 como estado del 30 sept |
| `estado-fase6.md` (Convocatoria) | Sí | No | En sí mismo | Cifras anteriores (168, 190) como histórico de §3bis |
| `auditoria-c01-idempotencia-fondos.md` | Sí, intacta | No | En sí misma | Toda |
| `auditoria-c01-postcorreccion.md` | Sí, intacta | No | En sí misma | Toda |
| `auditoria-cierre-f1-f2.md` | Sí, intacta | No | En sí misma | Toda |
| `auditoria-flujo-confirmed-fondos-v3.md` | Sí, intacta | No | En sí misma | Toda |
| `debate-cierre-confirmed-fondos.md` | Sí, intacto | No | En sí mismo | Todo |
| `auditoria-preimplementacion-cierre-convocatoria.md` | Sí, intacta | No | En sí misma | Toda |
| `auditoria-delimitacion-cierre-convocatoria.md` | Sí, intacta | No | En sí misma | Toda |
| `auditoria-cierre-final-convocatoria.md` | Sí, intacta | No | En sí misma | Toda (último snapshot de cierre) |
| `auditoria-herencia-fase5-convocatoria.md` | Sí, intacta | No | En sí misma | Toda |
| `auditoria-documental-convocatoria.md` | Sí, intacta | Su contenido técnico se **copia** al plan | En sí misma + plan §4.2 / §16 | Toda (la copia no sustituye al original) |
| `auditoria-porcentaje-convocatoria-fase6.md` | Sí, intacta | No | En sí misma | Toda |
| `auditoria-inventario-documental-convocatoria.md` | Sí, intacta | No | En sí misma | Toda |
| Este informe | Sí, intacto | No | En sí mismo | Todo |
| Documentos de otros bloques (GC-8) | Sí, sin cambios por Convocatoria | No | En sí mismos | Lo que decida cada bloque |

---

## 6. Decisiones humanas previas a cualquier consolidación

Según la regla 2.1 y P-G, ninguna se resuelve en este informe.

| ID | Decisión | Por qué bloquea | Evidencia |
|---|---|---|---|
| DH-C-1 | ¿Se aprueba el texto de la Enmienda 2 y ADR-043? | Sin aprobación no pueden incorporarse a ADR-037, y las anotaciones "Sustituido por la Enmienda 2" ya presentes quedan sin base | CD-03; `ADR-037-enmienda-2` §9; `ADR-043:5` |
| DH-C-2 | ¿Consolidación en el lugar (editar ADR-037, recomendada por P-A y P-B) o texto consolidado separado? | Define el destino de GC-1 | §0 |
| DH-C-3 | ¿Se ratifica el catálogo 037–043 y dónde se registra esa decisión? | La Enmienda 1 y el resumen citan una fuente inexistente; los títulos dicen "tentativo" | CD-02 |
| DH-C-4 | ¿G1, G2 y G3 fueron decisiones humanas? ¿Se registran en el resumen §6.x? | Hoy solo constan en un documento de estado | CD-10 |
| DH-C-5 | ¿La rev. 3 de `implementation_plan.md` está aprobada? | La cabecera solo declara aprobada la rev. 2 | CD-07 |
| DH-C-6 | ¿Destino del contrato de aplicación y del modelo físico Mongo: plan §16 (resumen de decisiones de implementación) y §4.2, o un documento nuevo? | Hoy solo existen en una auditoría; crear un documento requiere aprobación | GC-6, GC-7; plan §16 criterio 7 |
| DH-C-7 | ¿Los cambios sin commit en `golden-path.md` y `api-contract-matrix.md` se consideran parte del trabajo de Convocatoria o los revisan los dueños de las Capas 5 y 6? | Son documentos de otros bloques | `git diff`; GC-8 |
| DH-C-8 | ¿Grafía canónica `EXPIRED-UNKNOWN` o `EXPIRED_UNKNOWN`? | La documentación y el enum del código difieren | CD-13 |
| DH-C-9 | ¿El encabezado de `convocatoria-resumen.md` pasa de "pre-ADR … no es una decisión final" a "registro de decisiones e índice del bloque"? | Define su papel (CD-04) y si puede servir de índice | `convocatoria-resumen.md:3-4,91` |
| DH-C-10 | ¿Se versionan en Git los documentos de Convocatoria hoy sin seguimiento antes de consolidar? | Las Enmiendas 1 y 2, ADR-043, el plan y todas las auditorías del bloque **no tienen commit** (`git status`): hoy no existe copia histórica de ellos. Consolidar sobre archivos sin versionar impediría comprobar después que no se perdió nada | `git status` al inicio de la sesión |

---

## 7. Cierre

### 1. Documentos principales de Convocatoria (núcleo propuesto)

| Función | Documento |
|---|---|
| Decisión arquitectónica consolidada | `ADR-037-convocatoria-ledger-assignment-donationintent.md` |
| Fuente de cada enmienda (trazabilidad) | `ADR-037-enmienda-1-convocatoria.md`, `ADR-037-enmienda-2-convocatoria.md` |
| Recuperación (ADR propio) | `ADR-043-recuperacion-aplicacion-fondos-convocatoria.md` |
| Registro de decisiones humanas e índice | `convocatoria-resumen.md` (sujeto a DH-C-9) |
| Plan, contrato de aplicación y modelo físico | `implementation_plan.md` (sujeto a DH-C-5 y DH-C-6) |
| Estado operativo | `estado-fase6.md` (solo sus secciones de Convocatoria) |

### 2. Grupos que deben consolidarse

Derivados de la evidencia (§2):
1. **GC-1:** ADR-037 + Enmienda 1 (ya aprobada); + Enmienda 2 tras DH-C-1.
2. **GC-3:** registro de decisiones. G1–G3 al resumen §6.x; marcar las secciones ya formalizadas.
3. **GC-6 y GC-7:** contrato de aplicación y modelo Mongo. Copiar desde la auditoría documental al plan (§16, §4.2, §4.4).
4. **GC-4:** perímetro histórico. Marcas de superación en `fase-6-…` §3 y en el resumen §2–§4.
5. **GC-5 y GC-9:** plan y estado. Correcciones internas (CD-07, CD-08) sin borrar el historial.

No se consolidan:
- **GC-2:** ADR-043 independiente.
- **GC-8:** otros bloques.
- **GC-10:** auditorías.

### 3. Documentos que deben conservarse intactos

- Las once auditorías y el debate del bloque:
  - `auditoria-c01-idempotencia-fondos.md`;
  - `auditoria-c01-postcorreccion.md`;
  - `auditoria-cierre-f1-f2.md`;
  - `auditoria-flujo-confirmed-fondos-v3.md`;
  - `debate-cierre-confirmed-fondos.md`;
  - `auditoria-preimplementacion-cierre-convocatoria.md`;
  - `auditoria-delimitacion-cierre-convocatoria.md`;
  - `auditoria-cierre-final-convocatoria.md`;
  - `auditoria-herencia-fase5-convocatoria.md`;
  - `auditoria-documental-convocatoria.md`;
  - `auditoria-porcentaje-convocatoria-fase6.md`.
- `auditoria-inventario-documental-convocatoria.md` y este informe.
- Los archivos de la Enmienda 1 y la Enmienda 2: íntegros, aunque su contenido se incorpore a ADR-037.
- Todos los documentos de otros bloques (GC-8).

### 4. Información que no puede perderse

1. El texto original de ADR-037 en cada punto sustituido. Ya hay un precedente: `:127`.
2. Las alternativas descartadas de ADR-037 §4, de la Enmienda 1 §2.1 y de ADR-043.
3. Los razonamientos de:
   - D1 / `fundId` estable (ADR-037 §2.6bis);
   - "el `REPRESENTATIVE` no cuenta" (§2.5);
   - la corrección de STRICT (§2.3, resumen §5).
4. La aprobación N1–N12, con su origen (Enmienda 1 §10.3), y el historial de revisiones de la Enmienda 1 y del plan.
5. P1–P10, con lo "ya fijado" y lo que "falta decidir" (Enmienda 1 §8; Enmienda 2 §7).
6. La compatibilidad y migración (Enmienda 1 §9).
7. El vocabulario de §6.1 del resumen ("responsable de la convocatoria", `IN_KIND`, `GATEWAY`/`BANK_TRANSFER`/`CASH`).
8. La verificación de código del 30 sept (resumen §6.9), incluida la lista de defectos documentales de §6.9.4.
9. La cadena C-01:
   - §6.17, §6.18 y §6.19;
   - K-1/K-2;
   - DH-5, sobre la "decisión humana" no registrada que citaba el Javadoc.
10. F-1, F-2, D1–D7 y P9 (resumen §6.20).
11. R1, ID, P7 (corte), R3 y B-1 (resumen §6.15–§6.16).
12. G1–G3 (`estado-fase6.md:68`).
13. La restricción de paginación y no exposición de datos privados del listado público (resumen `:53`, `:77`).
14. Los acuerdos de la reunión del 18 sept y la tabla de casos de uso del MVP (`fase-6-…` §3.1–§3.2).
15. El contrato de aplicación real A1–A12 y el modelo Mongo real (auditoría documental, Partes 3 y 5).
16. Las cifras de reintento (6 intentos, backoff de 10–500 ms) y la advertencia sobre la `sequence` del audit log.
17. Las evidencias de Surefire (168, 190 y 194) con su fecha y contexto, y la incidencia `WT_PANIC` (`estado-fase6.md` §3bis).
18. Las pruebas de mutación listadas (`estado-fase6.md:75`).
19. Las convenciones de nombre que justifican `FUNDING_REJECTED` (auditoría de delimitación §F).
20. GAP-1 a GAP-4 (auditoría de preimplementación §4).
21. Las contradicciones registradas (CD-01 a CD-14) y H-1 a H-3, hasta que se resuelvan.

### 5. Propuesta de estructura documental final de Convocatoria (solo propuesta)

Sin mover ni renombrar archivos:

```text
convocatoria-resumen.md                   ← PUNTO DE ENTRADA (DH-C-9)
  ├── §0 (nuevo) Índice del bloque: tabla documento → función → estado
  │        (canónico / enmienda-fuente / plan / estado / histórico / evidencia)
  ├── §1–§5 Diseño conceptual del 18 sept (histórico, marcado)
  └── §6 Registro cronológico de decisiones humanas (§6.1 … §6.20, §6.x nuevas)

ADR-037-convocatoria-ledger-assignment-donationintent.md   ← NORMATIVA CONSOLIDADA
  ├── decisión original, con texto sustituido marcado en su lugar
  ├── Enmienda 1 incorporada (según su §10.1–§10.2)
  ├── Enmienda 2 incorporada (solo tras DH-C-1)
  ├── §7 riesgos y pendientes P1–P10
  └── Historial de enmiendas → enlaces a los dos archivos de enmienda

ADR-037-enmienda-1-convocatoria.md        ← fuente íntegra (trazabilidad)
ADR-037-enmienda-2-convocatoria.md        ← fuente íntegra (trazabilidad)
ADR-043-recuperacion-aplicacion-fondos-convocatoria.md  ← ADR propio

implementation_plan.md                    ← PLAN
  ├── §4.2 modelo físico real (copiado de la auditoría documental)
  ├── §4.4 transacciones y política de reintento
  ├── §16 resumen de decisiones de implementación (contrato de aplicación A1–A12)
  └── Historial de revisiones

estado-fase6.md (§2 fila 1, §3bis, §5)    ← ESTADO OPERATIVO

fase-6-estructura-y-perimetro-convocatoria.md §3   ← HISTÓRICO marcado

auditoria-*.md, debate-*.md               ← EVIDENCIA intacta, clasificada en el índice
```

El índice sustituye a cualquier marca dentro de las auditorías: así se conservan intactas.

### 6. Orden recomendado de ejecución

Cada paso se hace solo tras la aprobación correspondiente, en una rama `chore/` (P-F), con una sola responsabilidad por paso.

| Orden | Paso | Depende de | Documento tocado |
|---|---|---|---|
| 0 | Versionar el estado actual de la documentación de Convocatoria, para tener una línea base comprobable | DH-C-10 | Ninguno (Git) |
| 1 | Registrar las decisiones previas (DH-C-3 numeración, DH-C-4 G1–G3, DH-C-8 grafía, DH-C-9 papel del resumen) en nuevas §6.x del resumen | Respuestas humanas | `convocatoria-resumen.md` |
| 2 | Consolidar la Enmienda 1 en ADR-037 (§10.1–§10.2 de la propia enmienda; texto sustituido conservado) | DH-C-2, DH-C-3 | `ADR-037-…` |
| 3 | Aprobar o no la Enmienda 2 y ADR-043. Si se aprueban: actualizar sus cabeceras e incorporar la Enmienda 2 a ADR-037. Si no: revisar las anotaciones de sustitución ya presentes | DH-C-1 | Enmienda 2, ADR-043, ADR-037 |
| 4 | Marcar en el resumen las secciones formalizadas y las frases superadas, y añadir el índice §0 | Pasos 1–3 | `convocatoria-resumen.md` |
| 5 | Marcar `fase-6-…` §3 como superado o subordinado (solo §3) | Paso 2 | `fase-6-estructura-y-perimetro-convocatoria.md` |
| 6 | Plan: cabecera de la rev. 3, base normativa, §3.5, historial de revisiones; copiar el modelo físico, el reintento y el contrato de aplicación desde la auditoría documental | DH-C-5, DH-C-6, paso 3 | `implementation_plan.md` |
| 7 | Estado: corregir solo las partes de Convocatoria con una nueva ejecución real, sin borrar las cifras anteriores | Regla 2.4 | `estado-fase6.md` |
| 8 | Entregar a los bloques dueños la lista de discrepancias (CD-05, CD-09, CD-12, Paso 6 del inventario) y la decisión DH-C-7 | — | Ninguno de Convocatoria |
| 9 | Auditoría de verificación de la consolidación (solo lectura) contra los criterios del §7 | Pasos 1–8 | Informe nuevo, si se aprueba |

### 7. Criterios para considerar Convocatoria documentalmente ordenada

Todos se pueden comprobar con lectura, `grep`, `git` o una suma de comprobación.

1. **Nada se perdió.**
   - Los 21 archivos del §5 siguen existiendo con el mismo nombre y ruta.
   - Las auditorías, el debate y los archivos de enmienda tienen la misma suma de comprobación que en la línea base del paso 0. Las enmiendas solo pueden variar en la cabecera de estado, si DH-C-1 lo decide.
2. **Una fuente por decisión.** Cada fila de la matriz del §4 tiene exactamente una fuente canónica, y los demás documentos que la repiten la citan o están marcados como históricos o formalizados.
3. **ADR-037 al día.**
   - La cabecera ya no dice "única decisión de producto (D2)".
   - §7 contiene P1–P7 (y P8–P10 si se aprueba la Enmienda 2).
   - Cada sustitución conserva "Texto original".
4. **Coherencia con el estado de aprobación.**
   - Ningún documento de Convocatoria presenta como vigente el contenido de un documento en BORRADOR o Propuesto.
   - O bien la Enmienda 2 y ADR-043 figuran como aprobados.
5. **Sin rutas rotas.** `grep` sobre los documentos de Convocatoria no encuentra:
   - `ADR-033-convocatoria-`;
   - `claude/`;
   - `ADR-042-frontend-web-paxfide-web.md`;
   - `implementation-plan-paxfide-web.md`;
   - `plan-ejecucion-agentes-fase6.md`.

   Si alguna se conserva como texto histórico, debe estar marcada como tal.
6. **Numeración registrada.**
   - Existe una sección del resumen que registra la decisión sobre el catálogo 037–043.
   - Los títulos de ADR-037 y ADR-043 son coherentes con ella.
7. **Decisiones registradas.** G1–G3 constan en el resumen §6.x (o se registra que no fueron decisiones humanas). Ningún documento de estado es la única fuente de una decisión.
8. **Contrato y modelo fuera de las auditorías.** El contrato de aplicación (A1–A12) y el modelo Mongo real constan en el plan (o en el destino de DH-C-6). La auditoría documental deja de ser su única fuente.
9. **Índice completo.** El índice del punto de entrada lista los 19 documentos DIRECTOS del inventario, este informe y el inventario, cada uno con su función y estado.
10. **Perímetro respetado.** Ningún documento de otro bloque (GC-8) tiene cambios atribuibles a la consolidación de Convocatoria (`git log` de la rama `chore/`).
11. **Contradicciones cerradas.** CD-01, CD-02, CD-03, CD-04, CD-07, CD-08, CD-10, CD-11 y CD-13 están cada una resuelta o registrada como pendiente con su dueño. CD-05, CD-09 y CD-12 están entregadas al bloque dueño.
12. **Persona nueva.** Desde el punto de entrada, una lectura de ADR-037, ADR-043, el plan y la fila de Convocatoria de `estado-fase6.md` basta para conocer las decisiones vigentes, los pendientes y el estado, sin abrir ninguna auditoría.

---

*Archivo creado por esta auditoría: solo `Documentos/auditoria-plan-consolidacion-documental-convocatoria.md`. No se modificó ningún otro archivo. No se hizo commit ni push.*
