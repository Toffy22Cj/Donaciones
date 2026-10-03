# Ejecución de la consolidación documental de Convocatoria (Fase 6)

**Fecha:** 2026-10-03. **Rama:** `develop`, HEAD `673eda9`. No se hizo commit ni push.

**Tipo:** consolidación documental limitada al perímetro de Convocatoria. No se modificó código, tests, contratos de otros bloques ni ADR de otros bloques. No se borró, movió ni renombró ningún archivo.

**Bases:**
- `auditoria-inventario-documental-convocatoria.md`: inventario, CD-01 a CD-14, GV-1 a GV-8;
- `auditoria-plan-consolidacion-documental-convocatoria.md`: plan, GC-1 a GC-10, DH-C-1 a DH-C-10.

**Decisiones resueltas por la instrucción de esta tarea.** La instrucción humana de la tarea fijó la estructura destino (`convocatoria-resumen.md` = índice; ADR-037 = normativa consolidada; ADR-043 separado; `implementation_plan.md` = implementación, contratos internos y modelo físico). Con ello quedaron resueltas tres decisiones del plan:
- **DH-C-2:** consolidar en el propio ADR-037;
- **DH-C-6:** el contrato interno y el modelo físico van a `implementation_plan.md`;
- **DH-C-9:** el resumen funciona como índice.

El resto de decisiones humanas siguen pendientes (§5).

---

## 1. Documentos modificados

Solo cinco, todos del perímetro de Convocatoria.

| Documento | Tipo de cambio |
|---|---|
| `ADR-037-convocatoria-ledger-assignment-donationintent.md` | Reestructurado como normativa consolidada: Enmienda 1 integrada, texto original conservado |
| `convocatoria-resumen.md` | Añadida la cabecera de función y el §0 (índice, mapa, estado documental); notas de trazabilidad en §2, §4, §6.1, §6.7, §6.8, §6.10–§6.14 y §6.20. Sin borrar texto |
| `implementation_plan.md` | Nota de consolidación tras la regla de trazabilidad; notas en §1.3, §3.5, §4.2, §4.4 y §16; §17 nuevo (anexo descriptivo). Sin borrar texto |
| `estado-fase6.md` | Notas solo en las partes de Convocatoria (§1, tras la tabla de §2, §3bis y §5). Sin borrar texto ni cambiar cifras |
| `fase-6-estructura-y-perimetro-convocatoria.md` | Notas solo dentro de §3 (perímetro de Convocatoria): §3, §3.1, §3.2, §3.4 (SUPERADO) y §3.5. §1–§2 y §4–§5, transversales a Fase 6, sin tocar |

**Archivo creado:** este informe.

## 2. Documentos conservados intactos

Las sumas de comprobación son idénticas antes y después de la consolidación (§8).
- **Enmiendas:**
  - `ADR-037-enmienda-1-convocatoria.md`;
  - `ADR-037-enmienda-2-convocatoria.md`.
- **ADR asociado:** `ADR-043-recuperacion-aplicacion-fondos-convocatoria.md`.
- **Auditorías y evidencia del bloque:**
  - `auditoria-c01-idempotencia-fondos.md`, `auditoria-c01-postcorreccion.md`, `auditoria-cierre-f1-f2.md`, `auditoria-flujo-confirmed-fondos-v3.md`;
  - `debate-cierre-confirmed-fondos.md`;
  - `auditoria-preimplementacion-cierre-convocatoria.md`, `auditoria-delimitacion-cierre-convocatoria.md`, `auditoria-cierre-final-convocatoria.md`;
  - `auditoria-herencia-fase5-convocatoria.md`, `auditoria-documental-convocatoria.md`, `auditoria-porcentaje-convocatoria-fase6.md`;
  - `auditoria-inventario-documental-convocatoria.md`, `auditoria-plan-consolidacion-documental-convocatoria.md`.
- **Documentos de otros bloques:** los 52 restantes de `Documentos/`, entre ellos:
  - `golden-path.md`, `api-contract-matrix.md`;
  - `ADR-038` a `ADR-042`, `ADR-029`, `ADR-032`;
  - `ia-resumen.md`, `identity-resumen.md`, `hallazgos-front-fase2.md`;
  - `plan-correccion-fase5-e-ia.md`, `estado-fase5.md`.

`golden-path.md`, `api-contract-matrix.md` y `Promt-maestro.md` ya tenían cambios sin commit **antes** de esta tarea. La consolidación no los tocó: su suma de comprobación no varió.

## 3. Contenido integrado en cada documento

### 3.1 ADR-037

| Contenido integrado | Destino en ADR-037 | Estado del contenido |
|---|---|---|
| Cabecera "Aprobado con Enmienda 1…" (texto que prescribe E1 §10.1). El estado original se conserva como "Estado original" | Cabecera | Aprobado |
| Lista de enmiendas con su estado real (E1 APROBADA e integrada; E2 BORRADOR y no integrada; ADR-043 Propuesto y separado) | Cabecera | — |
| Nota de numeración con la discrepancia (CD-02), sin cambiar título ni número | Cabecera | Pendiente (DH-C-3) |
| Convención de lectura (marcas [E1 §x], "Texto original", "BORRADOR, no normativa", "Contradicción registrada") | Cabecera | — |
| Objeto, regla de vigencia, N11, regla editorial (etiquetas) y lo que quedó fuera de alcance | §0 (nuevo) | Aprobado |
| Tabla "Decisiones que modifica" | §2 | Aprobado |
| Piezas adicionales del módulo (solicitud de cambio, registro de dinero no aceptable, registro de comandos) | §2 | Aprobado |
| Configuración (N1, tipos y medios, `onTargetReached`, invariante MONETARY, IN_KIND, sin excepción por donante) | §2.1 | Aprobado; P2 pendiente |
| Cambios de configuración (versionado, política, aprobador, N3, concurrencia) | §2.1 | Aprobado |
| Cierre manual y estado `CLOSED` | §2.1 | Aprobado; P7 pendiente |
| N2 (ledger solo con `MONETARY`), `REJECT_EXCESS`, dinero no aceptable, N4 | §2.2 | Aprobado; P1 pendiente |
| Estado y requisito de `STRICT` | §2.3 | Aprobado; integración pendiente |
| Operaciones de responsables, índice parcial restringido a `EMPLOYEE`, nombre `employeeRef` | §2.4 | Aprobado |
| Cardinalidad con `ADMINISTRATOR` y `RemoveResponsible`; nota sobre el respaldo inexistente (P6) | §2.5 | Aprobado; P6 pendiente |
| Campos de `DonationIntent` (N5), precondición (N6), tabla por medio (N7), N8, N9, unicidad de referencia, vencimiento, N10 | §2.6 | Aprobado; P5 pendiente |
| Idempotencia financiera (barrera `PENDING → CONFIRMED`, `commandId` determinista) | §2.6 | Aprobado, en conflicto con decisiones humanas posteriores (CD-03) |
| Relación del vencimiento con D1 | §2.6bis | Aprobado |
| Idempotencia de comandos de escritura del módulo y N12 | §2.7 (nuevo) | Aprobado |
| Consecuencias añadidas | §3 | Aprobado |
| Alternativas descartadas de la ronda D2–D4 | §4 | Aprobado |
| Cierre manual, asignación, autoasignación y autorización contextual | §5 | Aprobado; P6 pendiente |
| Identificadores de observabilidad añadidos | §6 | Aprobado |
| Estado vigente de las filas de riesgo (D2 resuelto, R4 resuelto, etc.) | §7.1 | Aprobado |
| P1–P7 y verificaciones técnicas abiertas | §7.2 | Pendientes |
| Dependencias ADR-032, ADR-029, API y `core`/ADR-040 | §7.3 | Dependencias |
| Compatibilidad y migración | §7.4 | Aprobado |
| Trazabilidad de la verificación del 30 sept | §8 | — |
| Relación con la Enmienda 2 y ADR-043 | §9 (nuevo, informativo) | **BORRADOR / Propuesto, no normativo** |
| Registro de la consolidación y anotaciones previas sustituidas (citadas literalmente) | §10 (nuevo) | — |

**No se integró en ADR-037:**
- la tabla de aprobación N1–N12 y la lista de aprobación (E1 §10.3);
- el historial de revisiones de la Enmienda 1 (E1 `:10-14`);
- la Enmienda 2 como norma;
- ADR-043.

Siguen en sus archivos originales, que ADR-037 referencia.

### 3.2 `convocatoria-resumen.md`

- **Cabecera "Función actual":** el documento pasa a ser documento de entrada. La cabecera original se conserva.
- **§0.1** Por dónde empezar.
- **§0.2** Mapa documental de los 22 documentos del bloque, con función, estado y autoridad.
- **§0.3** Dónde está cada decisión: tema → fuente canónica → estado.
- **§0.4** Decisiones humanas pendientes.
- **§0.5** Contradicciones abiertas.
- **§0.6** Referencias externas.
- **Notas "Formalizada en…":**
  - §6.7, §6.8, §6.10–§6.14 → Enmienda 1, integrada;
  - §6.20 → Enmienda 2 (BORRADOR) y ADR-043 (Propuesto).
- **Notas CD-05** en §2 y §4, sobre el respaldo del `REPRESENTATIVE`.
- **Nota CD-02** en §6.1, sobre la fuente inexistente de la renumeración y el renombrado `b417d3a`.

### 3.3 `implementation_plan.md`

- **Nota de consolidación tras la cabecera:**
  - rutas actuales de la base normativa;
  - base de la rev. 3 (Enmienda 2 en BORRADOR, ADR-043 Propuesto);
  - aprobación de la rev. 3 pendiente (DH-C-5).
- **Notas en otras secciones:**
  - §1.3: archivo `implementation-plan-paxfide-web.md` inexistente;
  - §3.5: `FUNDING_REJECTED` (BORRADOR) y grafía `EXPIRED_UNKNOWN`;
  - §4.2: nombres reales de colección;
  - §4.4: parámetros de reintento;
  - §16, criterio 7: §17 no sustituye la revisión humana.
- **§17 (nuevo, descriptivo, no normativo):**
  - §17.1: contrato interno A1–A12 y operaciones de sistema;
  - §17.2: modelo físico de las siete colecciones;
  - §17.3: transacciones y reintento.

### 3.4 `estado-fase6.md`

Notas en cuatro lugares; no se cambia ninguna cifra:
- **§1:** la línea "solo Blockchain" está desactualizada para Convocatoria.
- **Tras la tabla de §2:** situación documental actual de Convocatoria.
- **§3bis:**
  - G1–G3 pendientes de registro;
  - 194 tests en auditorías posteriores, no registrados como estado porque la consolidación no ejecutó tests (regla 2.4).
- **§5:** la barrera `APPLY_FUNDS` ya protege la aplicación; siguen T1, P8 y la idempotencia de `clearFundsGenesis` en `core`.

### 3.5 `fase-6-estructura-y-perimetro-convocatoria.md` (solo §3)

- **§3:** histórico, con N11.
- **§3.1:** el descubrimiento se confirmó después.
- **§3.2:** prevalece la Enmienda 1 §4.
- **§3.4:** **SUPERADO** por ADR-037 §2.3.
- **§3.5:** enmienda de ADR-032 no redactada; P6; `REGISTER_PHYSICAL_ASSET_FROM_DONATION` ya implementado.

## 4. Procedencia de cada integración

| Destino | Procedencia | Estado de la fuente |
|---|---|---|
| ADR-037 §0, §2–§8 (bloques [E1]) | `ADR-037-enmienda-1-convocatoria.md` §1–§10.2, copiado con sus etiquetas. Referencias adaptadas: "ADR-037 §X" → "§X"; "§X" de la enmienda → "E1 §X". Las viñetas largas se dividieron en subviñetas sin cambiar el contenido | APROBADA, 2026-09-30 (E1 `:3`) |
| ADR-037, cabecera "Estado consolidado" | E1 §10.1, [ESTADO] | APROBADA |
| ADR-037, notas CD-06 | `implementation_plan.md:65,67` | Plan aprobado (rev. 2) |
| ADR-037 §2.7, ampliación ID | `convocatoria-resumen.md` §6.15 | Decisión humana del 2026-10-01 |
| ADR-037 §9 | `ADR-037-enmienda-2-convocatoria.md`; `ADR-043-…`; `convocatoria-resumen.md` §6.20 | BORRADOR / Propuesto / decisiones humanas del 2026-10-02 |
| ADR-037, nota de numeración | E1 `:5-7`; `convocatoria-resumen.md` §6.1; `plan-correccion-fase5-e-ia.md:24`; `estado-fase5.md:33`; `git log` (`b417d3a`) | Discrepancia sin resolver |
| `convocatoria-resumen.md` §0 | Inventario y plan de consolidación (2026-10-03) | Auditorías (evidencia) |
| `implementation_plan.md` §17 | `auditoria-documental-convocatoria.md` Partes 3 y 5 y "BD HANDOFF". Se verificaron de nuevo contra el código, en solo lectura: nombres de colección, nombres de índice, clases de comando y constantes `MAX_ATTEMPTS = 6`, `BASE_MIN_MILLIS = 10`, `BASE_MAX_MILLIS = 50`, `MAX_BACKOFF_MILLIS = 500` (`ConvocatoriaTransactionRetryHelper.java:31-34`) | Auditoría (evidencia), más código |
| Notas de `estado-fase6.md` | ADR-037 consolidado; Enmienda 2 §6; auditorías del 2026-10-03 | — |
| Notas de `fase-6-…` §3 | ADR-037 §2.3 (`:80`, "debe marcarse como superado"); N11; E1 §4, §7.1; `convocatoria-resumen.md` §6.9.4 | APROBADA |

## 5. Decisiones que permanecen pendientes

La consolidación se detuvo antes de convertir cualquiera de estas en norma. Están registradas en `convocatoria-resumen.md` §0.4.

| ID | Decisión pendiente | Documentos afectados | Opciones encontradas | Por qué no se resolvió automáticamente |
|---|---|---|---|---|
| DH-C-1 | Aprobación del texto de la Enmienda 2 y de ADR-043 | ADR-037 §2.6, §2.7, §9; Enmienda 2; ADR-043; plan rev. 3 | Aprobar e integrar como [E2] / modificar / no aprobar | Exigen aprobación humana explícita (Enmienda 2 §9; ADR-043; regla 3.5). Integrar sería elevar un borrador a norma |
| DH-C-3 | Ratificar el catálogo 037–043 y dónde se registra | Título de ADR-037; `convocatoria-resumen.md` §6.1; E1 `:5-7` | Ratificar en §6.x / otra numeración | La fuente citada no existe; otros bloques dicen "pendiente" |
| DH-C-4 | ¿G1–G3 son decisiones humanas? | `estado-fase6.md:68`; resumen §6 | Registrar en §6.x / recalificar | Solo constan en un documento de estado; G3 no está en la Enmienda 1 |
| DH-C-5 | Aprobación de la rev. 3 del plan | `implementation_plan.md` | Aprobada / pendiente | La cabecera solo declara aprobada la rev. 2 |
| DH-C-7 | Cambios sin commit en `golden-path.md` y `api-contract-matrix.md` | Esos dos documentos | Conservar / revisión por sus dueños | Son de otros bloques |
| DH-C-8 | Grafía `EXPIRED-UNKNOWN` frente a `EXPIRED_UNKNOWN` | ADR-037 §2.6; plan §3.5 | Una u otra | La documentación y el código difieren |
| DH-C-10 | Versionar en Git los documentos de Convocatoria sin seguimiento | Enmiendas, ADR-043, plan, auditorías, este informe | Commit en `chore/` / esperar | Hoy no tienen copia histórica en Git. Esta tarea no hace commits |

## 6. Contradicciones que no se resolvieron

| ID | Contradicción | Cómo quedó |
|---|---|---|
| CD-02 | Numeración "aprobada" con fuente inexistente, frente a "tentativa" y "pendiente" | Señalada en la cabecera de ADR-037 y en el resumen §6.1. Título sin cambios |
| CD-03 | Barrera y semántica de `CONFIRMED`: E1 §5.3 y N9 (aprobadas) frente a F-1, F-2, D1 y D5 (decisiones humanas aprobadas, §6.20) y la Enmienda 2 (BORRADOR). El código sigue lo segundo | ADR-037 §2.6 conserva el texto aprobado de la Enmienda 1, con la contradicción señalada en su lugar y en §9. No se eligió entre ambos |
| CD-04 | Papel normativo del resumen | Nueva cabecera de función. La cabecera original y el alcance del encabezado de §6 se conservan |
| CD-05 | Respaldo del `REPRESENTATIVE` presentado como cerrado | Notas en el resumen §2 y §4, en `fase-6-…` §3.5 y en ADR-037 §2.5. En otros bloques, ver §7 |
| CD-06 | `ConvocatoriaReadPort` "ya existente" y `scanBasePackages` | Señalada en ADR-037 §2.3 y §2.6. Texto original conservado |
| CD-07 | Aprobación de la rev. 3 y base normativa del plan | Nota de consolidación en el plan. Aprobación pendiente (DH-C-5) |
| CD-08 | `estado-fase6.md` desactualizado para Convocatoria | Notas añadidas. No se cambiaron cifras sin ejecución (regla 2.4) |
| CD-10 | G1–G3 solo en un documento de estado | Pendiente (DH-C-4) |
| CD-13 | Grafía `EXPIRED-UNKNOWN` / `EXPIRED_UNKNOWN` | Señalada. Pendiente (DH-C-8) |
| CD-14 | Dictámenes de cierre opuestos entre auditorías | Constatada en el resumen §0.2. Ninguna auditoría modificada |

**Resueltas por la consolidación:**
- **CD-01:** la cabecera y §7 de ADR-037 ya reflejan la Enmienda 1; el texto original se conserva.
- **CD-11:** `fase-6-…` §3 queda marcado como superado o subordinado.

## 7. Referencias externas que requieren trabajo de otros bloques

No se ejecutaron.

| Documento afectado | Problema | Motivo | Dependencia con Convocatoria | Acción para el responsable del bloque |
|---|---|---|---|---|
| `api-contract-matrix.md` (API, Capa 5) | §2 dice "módulo `convocatoria` sin código". Una sola operación "asignar empleado". `donation-intents` "sin operación de dominio". `REPRESENTATIVE` como respaldo. Webhook "CERRADO E IMPLEMENTADO". Fila del webhook y lista de estados reescritas sin commit según la Enmienda 2 (BORRADOR) | El estado real está en ADR-037 §2.4, §2.6, §7.2 (P4) y `implementation_plan.md` §17. N11 subordina la matriz §2 | Contrato HTTP de las operaciones de Convocatoria | Reconciliar §2, §2b y §3 con ADR-037 consolidado y el plan §17; decidir sobre los cambios sin commit (DH-C-7); cerrar P4 |
| `golden-path.md` (Capa 6) | Paso 3 reescrito sin commit con el flujo de la Enmienda 2 (BORRADOR), presentado como vigente. §3 cita el respaldo del `REPRESENTATIVE` como existente | CD-03, CD-05 | Escenario de donación | Revisar el paso 3 cuando se decida DH-C-1; corregir §3 |
| `ADR-041` (API) | Numeración antigua "ADR-037/034/036" (`:3,4,108`). Estado público `FUNDING_REJECTED` y contrato de confirmación manual no definidos | CD-12; Enmienda 2 §6 | Contratos HTTP de Convocatoria | Corregir la numeración tras DH-C-3; enmienda de ADR-041 (P4, estados) |
| `ADR-040` (IA) | Numeración antigua "ADR-037/034/035" (`:3-4,68`). N10 (`confirmationSource`) no llega a los hechos de auditoría | CD-12; ADR-037 §7.3 | Hechos de auditoría de Convocatoria | Corregir la numeración; decidir N10 en `core`/ADR-040 |
| `ADR-032` (`core`) | La redacción normativa del respaldo del `REPRESENTATIVE` y P6 no existen | ADR-037 §5, §7.3 | Autorización contextual | Enmienda de ADR-032 |
| `ADR-029` (`core`) | `campaignRef` en `PhysicalAsset` | ADR-037 §7.3, §7.4 | Capacidad contextual, P2 | Enmienda de ADR-029 |
| `ADR-038` (Identidad) | Falta la implementación de producción de `OrganizationVerificationPort`; `INACTIVE` y `AccountNotFoundException` | `implementation_plan.md` §11 (X1, X2) | Bloquea `app → convocatoria` | Implementación en Identidad |
| `core` (sin documento de Convocatoria) | T1, P8, idempotencia de `clearFundsGenesis` | ADR-037 §7.1; Enmienda 2 §6 | Orquestador de fondos | Diseño e implementación en `core` |
| `hallazgos-front-fase2.md` (Frontend) | Usa "ADR-037" para la API y "ADR-033" para Convocatoria | Numeración antigua | Fuente de R4 | Actualizar referencias tras DH-C-3 |
| `plan-correccion-fase5-e-ia.md`, `estado-fase5.md` (Fase 5) | Renumeración "pendiente"; "ADR-037 = APIs/Frontend" | CD-02 | Numeración | Cerrar con la decisión DH-C-3 |
| `ADR-042` (`core`) | Nota de numeración: "ADR-038/039 solo en `develop` local" | Desactualizada | Ninguna directa | Revisar la nota |

## 8. Comprobación de que no se borró ningún documento

- **Archivos en `Documentos/`:**
  - 72 antes de esta tarea: 70 del inventario más `auditoria-inventario-documental-convocatoria.md` y `auditoria-plan-consolidacion-documental-convocatoria.md`;
  - 72 después de editar, antes de crear este informe;
  - 73 con este informe.
- **Sumas md5** tomadas antes de editar (`md5sum Documentos/*`) frente a las posteriores:
  - **67 coinciden**;
  - **5 difieren**, exactamente los cinco documentos de §1.
- **Enmiendas** (A, B): `ADR-037-enmienda-1-convocatoria.md` y `ADR-037-enmienda-2-convocatoria.md` existen y su md5 coincide.
- **Auditorías** (C): las 13 auditorías o el debate del bloque y las 4 de la Capa 5 existen y su md5 coincide.
- **Preservación de texto** (J). Cada línea no vacía de la versión anterior de los cinco documentos editados está presente en la versión nueva (comprobación por subcadena):
  - 0 líneas ausentes en `convocatoria-resumen.md`, `implementation_plan.md`, `estado-fase6.md` y `fase-6-…`;
  - en ADR-037, la única línea que no aparece literal es la cabecera `**Estado:** Aprobado — …`. Se conserva con el mismo texto bajo la etiqueta "**Estado original (antes de la Enmienda 1; se conserva como histórico):**".
  - Las tres anotaciones que una sesión anterior añadió sin commit se citan literalmente en ADR-037 §10.
  - Todas las líneas de la versión `HEAD` de ADR-037 también están presentes.

## 9. Comprobación de que no se modificó documentación fuera de Convocatoria

- **D. Perímetro:** de los 5 archivos modificados, 4 son de Convocatoria. `fase-6-…` es transversal a Fase 6 y solo se modificó en §3, el perímetro de Convocatoria; el diff son 10 líneas añadidas, todas dentro de §3.
- **Otros bloques:** los documentos de otros bloques tienen el md5 sin cambios, incluidos `golden-path.md`, `api-contract-matrix.md`, `ADR-038` a `ADR-042`, `ADR-029` y `ADR-032`.
- **Código:** no se modificó código ni tests. Solo se leyeron 4 archivos de `convocatoria/src/main` para comprobar nombres y constantes.
- **Git:** no se ejecutó `git add`, `commit` ni `push`.
- **E. Enmienda 1 en ADR-037:** la cobertura de términos de cada línea de la Enmienda 1 (§1–§10.2) dentro de ADR-037 es ≥ 0,93. Las únicas palabras ausentes son editoriales ("explicar", "modificado", "lleva", "apruebe"), en frases que hablaban de "esta enmienda" en futuro.
- **F. Borradores:** cada mención nueva a la Enmienda 2 o a ADR-043 en los documentos editados lleva "BORRADOR", "Propuesto" o "no normativa", o aparece en un contexto ya calificado así.
- **G. ADR-043:** sigue en su archivo, con el md5 sin cambios. ADR-037 solo lo referencia (§9).
- **H. Índice:** `convocatoria-resumen.md` §0.1–§0.3 apuntan a ADR-037, ADR-043, `implementation_plan.md` §17 y `estado-fase6.md`.
- **I. Referencias internas nuevas:** apuntan a secciones existentes:
  - ADR-037 §0, §2.7, §7.1–§7.4, §9 y §10;
  - resumen §0.1–§0.6;
  - plan §17–§17.3.

  Las únicas rutas inexistentes en el texto nuevo son citas marcadas expresamente como inexistentes (`ADR-042-frontend-web-paxfide-web.md`, `claude/front-fase2.md`, `implementation-plan-paxfide-web.md`) y este informe, que ya existe.

## 10. Estado final de la estructura documental

```text
CONVOCATORIA
│
├── convocatoria-resumen.md        índice / mapa / estado documental (§0)
│                                   + diseño conceptual histórico (§1–§5)
│                                   + registro de decisiones humanas (§6)
│
├── ADR-037                         normativa consolidada (original + Enmienda 1 integrada)
│                                   Enmienda 2 señalada como BORRADOR (§9); CD-03 registrada
│
├── ADR-043                         recuperación (Propuesto) — separado, intacto
│
├── implementation_plan.md          plan del primer corte
│                                   + §17 contrato interno y modelo físico (descriptivo)
│
├── estado-fase6.md                 estado de Convocatoria (fila 1 de §2, §3bis, §5), con notas
│
├── fase-6-estructura-…  §3         perímetro inicial — histórico, §3.4 SUPERADO
│
├── Enmiendas                       ADR-037-enmienda-1 (APROBADA), ADR-037-enmienda-2 (BORRADOR)
│                                   — fuentes originales intactas
│
└── Auditorías                      13 informes del bloque + este informe — evidencia intacta
```

**Para cerrar el orden documental** faltan las decisiones humanas de §5. En particular:
- **DH-C-1:** con la Enmienda 2 aprobada, se integraría en ADR-037 como [E2] con la misma convención y CD-03 quedaría resuelta;
- **DH-C-3:** numeración;
- **DH-C-10:** versionado en Git.

Y el trabajo de otros bloques de §7.
