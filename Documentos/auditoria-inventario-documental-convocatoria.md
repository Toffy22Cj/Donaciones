# Auditoría documental 1 — Inventario y clasificación de Convocatoria (Fase 6)

**Tipo:** solo lectura. No se modificó, renombró, movió ni borró ningún documento existente. No se tocó código, tests ni `pom.xml`. No se renumeró ningún ADR. No se hizo commit ni push.
**Fecha:** 2026-10-03. **Rama:** `develop`, HEAD `673eda9`. Árbol de trabajo con cambios sin commit en `Documentos/` (seis documentos modificados y veintiuno sin versionar) y `convocatoria/` sin versionar (`git status` al inicio de la sesión).
**Perímetro:** Capa 1 de Fase 6, Convocatoria (`fase-6-estructura-y-perimetro-convocatoria.md` §2). Los demás bloques aparecen solo cuando existe una dependencia documental concreta.
**Naturaleza:** informe de auditoría. **No es documentación canónica** y no resuelve ninguna contradicción: las registra para una segunda fase.

**Método.**
- Se inventariaron los 70 archivos de `Documentos/`. De cada uno se leyó la cabecera y se buscaron menciones de "Convocatoria", "ADR-033" a "ADR-043" y rutas citadas.
- Se leyeron completos los diez documentos núcleo:
  - ADR-037, sus Enmiendas 1 y 2, y ADR-043;
  - `convocatoria-resumen.md`;
  - `fase-6-estructura-y-perimetro-convocatoria.md`;
  - `implementation_plan.md`;
  - `estado-fase6.md`;
  - `golden-path.md`;
  - `api-contract-matrix.md`.
- De las auditorías se leyeron la cabecera, la estructura y el dictamen. De los ADR de otros bloques, la cabecera y los pasajes que citan Convocatoria.
- Se usaron también `git log` y `git diff` sobre `Documentos/`, siempre en modo de solo lectura.

**Notación de evidencia.** `archivo:línea` significa que el dato se leyó en esa línea, en el estado actual del árbol de trabajo. `[git]` marca la evidencia sacada del historial de Git. `[no reverificado]` marca un dato tomado de otro informe sin comprobarlo de nuevo.

**Identificadores de este informe.** Para no chocar con los que ya existen (P1–P10, D1–D7, F-1/F-2, K-1/K-2, H-1–H-3, R1–R4, X1–X2…), este informe usa:
- **CD-nn** para las contradicciones documentales;
- **GV-n** para los grupos de duplicados o versiones.

---

## PASO 1 — Inventario

**Categorías de relación**, tal como se aplican aquí:
- **DIRECTO:** el perímetro principal del documento es Convocatoria (se comprobó leyendo el contenido, no el nombre).
- **DEPENDENCIA:** documento de otro bloque o transversal cuyo contenido condiciona a Convocatoria, o que consume una decisión de Convocatoria y la necesita para cerrar su propio contrato.
- **REFERENCIA:** cita o deriva de Convocatoria, o es fuente citada por ella, pero no la condiciona.
- **HISTÓRICO:** conserva contexto de una etapa anterior que todavía se cita desde Convocatoria.
- **OTRO BLOQUE:** pertenece a otro bloque y menciona Convocatoria solo de forma incidental.
- **NO RELACIONADO:** no la menciona, o solo de forma irrelevante.

### 1.1 Documentos con relación DIRECTA

| Archivo | Tipo | Relación | Estado aparente | Motivo |
|---|---|---|---|---|
| `ADR-037-convocatoria-ledger-assignment-donationintent.md` | ADR | DIRECTO | "Aprobado" (`:3`), con el título aún en "número tentativo" (`:1`) | ADR del bloque: Convocatoria, Ledger, Assignment, ResponsibleState y DonationIntent |
| `ADR-037-enmienda-1-convocatoria.md` | Enmienda de ADR | DIRECTO | "APROBADA — decisión humana explícita del 2026-09-30" (`:3`) | Modifica explícitamente ADR-037 (§2, tabla "Decisiones que modifica") |
| `ADR-037-enmienda-2-convocatoria.md` | Enmienda de ADR | DIRECTO | "BORRADOR … requiere aprobación humana explícita (§9)" (`:3`); casillas de §9 sin marcar (`:150-152`) | Modifica ADR-037 §2.6 y la Enmienda 1 §5.3 y N9 (`:38-45`) |
| `ADR-043-recuperacion-aplicacion-fondos-convocatoria.md` | ADR | DIRECTO | "Propuesto" (`:5`) | Recuperación de la aplicación de fondos; lo exige la Enmienda 2 (`:6`) |
| `convocatoria-resumen.md` | Resumen y registro de decisiones | DIRECTO | Cabecera: "Diseño pre-ADR … no es una decisión final" (`:3`); §6.7–§6.20 contienen decisiones humanas aprobadas | Registro de las decisiones del bloque (§6) |
| `fase-6-estructura-y-perimetro-convocatoria.md` | Snapshot de perímetro y estructura de fase | DIRECTO (§3); §1–§2 y §4–§5 son transversales a Fase 6 | "Documento de congelación previo a `plan-ejecucion-agentes-fase6.md`" (`:3`), archivo que no existe | Perímetro inicial de la Capa 1. Su §3.4 está corregido por ADR-037 §2.3 y la precedencia N11 lo subordina (Enmienda 1 `:24`) |
| `implementation_plan.md` | Plan | DIRECTO | "APROBADO (revisión 2)" (`:3`), con revisiones 2.1–3 posteriores (`:4`) | Plan del primer corte del módulo `convocatoria` |
| `estado-fase6.md` | Estado de fase | DIRECTO, pero **solo en parte**: §2 fila 1, §3bis y §5 primera viñeta. El resto es Blockchain, Identidad, IA y API | Sin estado formal; describe resultados de ejecución (`:4`) | Snapshot de implementación del primer corte y de C-01 (§3bis) |
| `auditoria-c01-idempotencia-fondos.md` | Auditoría | DIRECTO | "informe temporal de auditoría. No es documentación canónica" (`:4`) | C-01: doble aplicación de fondos |
| `auditoria-c01-postcorreccion.md` | Auditoría | DIRECTO | "informe temporal … No es documentación canónica" (`:4`) | C-01 después de la corrección; reauditoría contra DH-1–DH-5 |
| `auditoria-cierre-f1-f2.md` | Auditoría | DIRECTO | "informe temporal" (`:4`) | Cierre de F-1/F-2 |
| `auditoria-flujo-confirmed-fondos-v3.md` | Auditoría | DIRECTO | "informe temporal" (`:4`) | Flujo `CONFIRMED` → aplicación de fondos |
| `debate-cierre-confirmed-fondos.md` | Debate / análisis | DIRECTO | "informe temporal de análisis … no decide" (`:4`) | Opciones previas a D1–D7 |
| `auditoria-preimplementacion-cierre-convocatoria.md` | Auditoría | DIRECTO | "informe temporal" (`:3`) | Ataque adversarial a D1–D7 |
| `auditoria-delimitacion-cierre-convocatoria.md` | Auditoría | DIRECTO | "informe temporal" (`:3`) | Qué trabajo pertenece a Convocatoria |
| `auditoria-cierre-final-convocatoria.md` | Auditoría | DIRECTO | "informe temporal" (`:4`) | Cierre del bloque; dictamen NO CERRADO |
| `auditoria-herencia-fase5-convocatoria.md` | Auditoría | DIRECTO | "solo lectura", 2026-10-03 (`:3-4`) | Herencia de Fase 5 en Convocatoria |
| `auditoria-porcentaje-convocatoria-fase6.md` | Auditoría | DIRECTO | Sin cabecera de fecha, rama ni naturaleza | Avance del bloque; dictamen "CERRADO" |
| `auditoria-documental-convocatoria.md` | Auditoría | DIRECTO | "solo lectura", 2026-10-03 (`:3-4`) | Catálogo documental, contrato API, modelo de BD y obsolescencias del bloque |

### 1.2 Documentos de referencia principal que no pertenecen al bloque

| Archivo | Tipo | Relación | Estado aparente | Motivo |
|---|---|---|---|---|
| `api-contract-matrix.md` | Contrato (snapshot) | REFERENCIA (Capa 5) | "Snapshot contractual de Fase 6, no un ADR" (`:3`); título "(no congelado)" | Contiene §2, §2b y §3 sobre endpoints de Convocatoria, pero el documento es de toda la API. Ver Paso 8 |
| `golden-path.md` | Escenario de demo | REFERENCIA (Capa 6) | "Escenario conceptual cerrado. No es ejecutable hoy" (`:3`) | Los pasos 2 y 3 describen el flujo de Convocatoria; el diff sin commit añade la Enmienda 2 a ese paso 3 |
| `contract-wiring-review.md` | Revisión de cableado | REFERENCIA (Capa 5) | "(no congelado)" (`:1`) | Origen de P1/P2/P3 del cableado, que ADR-037 dice resolver (ADR-037 `:14-16`). Lo cita ADR-037 en "Complementa" (`:6`) |
| `hallazgos-front-fase2.md` | Hallazgos de frontend | REFERENCIA (Capa 5) | "documento de hallazgos, no ADR" (`:3`) | Origen del hallazgo R4 que resuelve la Enmienda 1 §3.5 |

### 1.3 Dependencias, referencias y otros bloques

| Archivo | Tipo | Relación | Estado aparente | Motivo |
|---|---|---|---|---|
| `ADR-038-identidad-platform-administrator-verificacion-organization.md` | ADR | DEPENDENCIA | "Aprobado" (`:3`); título "número tentativo" | Verificación de `Organization` (X1, `OrganizationVerificationPort`) y `IdentityPrincipalPort` (X2); bloquea `app → convocatoria` (Enmienda 2 §6) |
| `ADR-040-ia-convocatoria-audit-facts.md` | ADR | DEPENDENCIA (inversa: IA consume Convocatoria) | "Aprobado parcialmente" (`:3`) | `ConvocatoriaAuditFacts`; N10 y Enmienda 1 §7.4; C7 "Convocatoria sin ledger" (`:98`) |
| `ADR-041-api-frontend-contratos-http.md` | ADR | DEPENDENCIA | "Aprobado" (`:3`); título "número tentativo" | P4, §7-A.4 (idempotencia de creación de intención), estado público `FUNDING_REJECTED` (Enmienda 2 §6) |
| `ADR-032-autorizacion-comandos-core-matriz.md` | ADR | DEPENDENCIA | Fase 5 | Puerto contextual, P6, respaldo del `REPRESENTATIVE`; `:53` excluye a `REPRESENTATIVE` |
| `ADR-029-organization-physicalasset-donacion-especie.md` | ADR | DEPENDENCIA | Fase 5 | `campaignRef` en `PhysicalAsset` (Enmienda 1 §7.2, §9.1); P2 |
| `reglas-equipo-y-agentes.md` | Reglas de proceso | DEPENDENCIA (transversal) | Vigente | Reglas 2.1, 2.4, 2.6, 3.4 y 3.5, que citan la Enmienda 2, ADR-043 y el plan |
| `identity-resumen.md` | Resumen | DEPENDENCIA | "(no congelado)" | Precondición `Organization VERIFIED` (`:89`); `fullName` pendiente (`:154`) |
| `ia-resumen.md` | Resumen | REFERENCIA | "Diseño pre-ADR" (`:3`) | Diseña `ConvocatoriaAuditFacts` a partir de Convocatoria y el ledger (`:39-81`) |
| `documento-maestro-proyecto.md` | Documento maestro | REFERENCIA | Fase 1–5 | `Fund` por donante (§6.2, citado en `fase-6…` §3.3); `MongoTransactionManager` en `app` (citado en `convocatoria-resumen.md` §5) |
| `technical_documentation.md` | Documentación técnica | REFERENCIA | Fase 2 | Citado como evidencia del `MongoTransactionManager` (`convocatoria-resumen.md:85`) |
| `plan-correccion-fase5-e-ia.md` | Plan | HISTÓRICO | Colisión de numeración "⏳ PENDIENTE DE DECISIÓN HUMANA — sin renumerar" (`:24`) | Origen de la renumeración 033→037 y de la condición A3 sobre `STRICT` (`:146-158`) |
| `estado-fase5.md` | Estado de fase | HISTÓRICO | "Fase 5: CERRADA" (`:1`) | Colisiones 033–037 "PENDIENTES" (`:33`); catálogo de ADR de Fase 5 |
| `plan-api-fase6.md` | Plan | OTRO BLOQUE (Capa 5) | Sin cabecera de estado | Plan de la capa API. Menciona Convocatoria 34 veces, pero su perímetro es `api` (§2) |
| `revision-ready-api-fase6.md` | Auditoría | OTRO BLOQUE (Capa 5) | Dictamen "LISTO PARCIALMENTE" (`:4`) | Revisión de `plan-api-fase6.md` |
| `auditoria-api-convocatoria.md` | Auditoría | OTRO BLOQUE (Capa 5) | Sin fecha ni naturaleza | **El nombre dice "convocatoria", pero el perímetro es la API** (§1: controladores de `api`) |
| `auditoria-api-convocatoria-v2.md` | Auditoría | OTRO BLOQUE (Capa 5) | Sin fecha | Segunda versión; §7 "Auditoría del informe anterior" |
| `auditoria-api-convocatoria-v3.md` | Auditoría | OTRO BLOQUE (Capa 5) | Sin fecha; "Auditoría Documental Final API/Contracts" | Tercera versión. Dictamen "C — API documentalmente abierta" (`:151`) |
| `ADR-042-orquestacion-centralizada-reintentos-proyeccion.md` | ADR | OTRO BLOQUE (`core`, Fase 5) | Vigente | ADR-043 lo cita para declarar que su ámbito no cubre este caso (ADR-043 `:17`) |
| `ADR-033-contrato-asset-registration-saga.md` | ADR | OTRO BLOQUE (Fase 5) | "Aprobado parcialmente" (`estado-fase5.md:24`) | Precedente de orquestación (`fase-6…:138,155`). **No** es el ADR de Convocatoria |
| `ADR-026`, `ADR-031` | ADR | OTRO BLOQUE | Fase 4–5 | Precedentes citados (`CannotRemoveLastRoleException`; bloqueo de `REGISTER_PHYSICAL_ASSET_FROM_DONATION`) |
| `ADR-039-blockchain-merkle-producer-integrity-verification.md` | ADR | OTRO BLOQUE | "Aprobado" | Solo cita el orden ADR-037 → 038 (`:4`) |
| `blockchain-resumen.md` | Resumen | NO RELACIONADO | — | 0 menciones |
| `ADR-024-enmienda…`, `ADR-025`, `ADR-027`, `ADR-028`, `ADR-030`, `ADR-034`, `ADR-035`, `ADR-036` | ADR | NO RELACIONADO | — | 0 menciones de Convocatoria. `ADR-034`, `ADR-035` y `ADR-036` solo se cruzan con Convocatoria por la colisión de numeración (Paso 6) |
| `Promt-maestro.md` | Prompt (Fase 2) | NO RELACIONADO | Modificado sin commit | El diff solo añade el salto de línea final [git] |
| `architecture_diagrams.md`, `db_diagrama.md`, `developer_onboarding.md`, `estado-fase3.md`, `estado-fase4.md`, `hallazgo-framework-retry-projections.md`, `auditoria-preexistencias-fase5.md`, `plan-detallado-fase3.md`, `plan-ejecucion-agentes-fase2…5.md`, `Promt-Contexto.md`, `Promt-Documentacion.md`, `Promt-maestro-fase5.md` | Varios | NO RELACIONADO | — | 0 menciones de Convocatoria |

### 1.4 Documentos citados que no existen en el árbol

Se citan como fuente o destino, pero no existen en `Documentos/` ni en la raíz. No hay directorio `claude/`.

| Documento citado | Dónde se cita |
|---|---|
| `ADR-042-frontend-web-paxfide-web.md` | `ADR-037-enmienda-1:6`; `convocatoria-resumen.md:99` (fuente de la renumeración "aprobada el 2026-09-28") |
| `ADR-033-convocatoria-ledger-assignment-donationintent.md` | `ADR-037-enmienda-1:7`; `convocatoria-resumen.md:99`; `implementation_plan.md:5`. El archivo se renombró a `ADR-037-…` en `b417d3a` [git] |
| `claude/ADR-037-enmienda-1-convocatoria.md` | `implementation_plan.md:5` |
| `claude/front-fase2.md` / `front-fase2.md` / `front-fase1.md` | `ADR-037-enmienda-1:244,290`; `convocatoria-resumen.md:363`; `hallazgos-front-fase2.md:4-5` |
| `implementation-plan-paxfide-web.md` | `implementation_plan.md:49` |
| `plan-ejecucion-agentes-fase6.md` | `fase-6-estructura-y-perimetro-convocatoria.md:3,174` |

---

## PASO 2 — Clasificación de los documentos DIRECTOS

| Documento | Clase | Contenido normativo | Observación |
|---|---|---|---|
| ADR-037 | **A. ADR normativo** | Sí: decisiones §2–§7 | Contiene anotaciones *[Sustituido por la Enmienda 2 …]* en el propio texto normativo (`:5`, `:119`, `:127`) |
| Enmienda 1 | **B. Enmienda de ADR** | Sí: [DECISIÓN] y [DECISIÓN — NUEVA] N1–N12 | Contiene anotaciones *[SUSTITUIDO por la Enmienda 2 …]* (`:4`, `:195`, `:206`, `:332`) |
| Enmienda 2 | **B. Enmienda de ADR** | **Propuesto, no vigente según su propio texto** (`:3`, §9) | Declara una regla de precedencia sobre ADR-037 y la Enmienda 1 (`:15`) |
| ADR-043 | **A. ADR normativo** (propuesto) | Propuesto (`:5`) | Asociado a la Enmienda 2, no es enmienda |
| `convocatoria-resumen.md` | **F. Resumen**, con función de **registro de decisiones humanas** (§6.7–§6.20) | Mixto: §1–§5 son resumen conceptual; §6.7, §6.10–§6.15, §6.17–§6.20 son registros de decisiones; §6.9 es verificación de código; §6.16 es aclaración técnica | La cabecera (`:3`) contradice su uso como registro (CD-04) |
| `fase-6-estructura-y-perimetro-convocatoria.md` | **C. Snapshot / estado** (perímetro congelado) → candidato a **H** en lo sustituido | Se declara "No es un ADR" (`:4`) | §3.4 sustituido (ADR-037 §2.3); §3.2 y §3.5 subordinados por N11 |
| `implementation_plan.md` | **D. Plan** | Normativa de ejecución (plan aprobado, regla 3.4) | Ver CD-07 |
| `estado-fase6.md` | **C. Snapshot / estado** | No, aunque registra G1–G3 como "decisiones humanas" (`:68`) (CD-10) | Documento compartido por las cinco capas |
| Once auditorías y un debate (§1.1) | **G. Auditoría** (el debate: **I. Otro / análisis**) | No: siete se declaran "temporal" o "no canónica"; `auditoria-porcentaje-…` no declara naturaleza | Ver Paso 9 |

Para los documentos de referencia principal (§1.2):
- `api-contract-matrix.md`: **E. Contrato** (snapshot no congelado).
- `golden-path.md`: **E. Contrato** de escenario, derivado ("No introduce decisiones de dominio nuevas", `:141`).
- `contract-wiring-review.md`: **E. Contrato** (revisión).
- `hallazgos-front-fase2.md`: **I. Otro** (hallazgos).

---

## PASO 3 — Cadena ADR de Convocatoria

### 3.1 Tabla

| Documento | Relación con ADR-037 | Estado que declara | ¿Fuente normativa? | Observaciones |
|---|---|---|---|---|
| `ADR-037-…-donationintent.md` | Es el ADR | "Aprobado … Pendiente de implementación, de una única decisión de producto (D2, autoasignación)" (`:3`) | Sí | La Enmienda 1 §10.1 (`:298`) pide cambiar esta cabecera a "Aprobado con Enmienda 1…"; no se ha hecho. §7 sigue con D2 en 🔴 (`:173`) y "Solo D2 queda" (`:187`). Título "(número tentativo …)" |
| `ADR-037-enmienda-1-convocatoria.md` | Enmienda explícita (§2) | "APROBADA" el 2026-09-30, con N1–N12 (`:3`). §10.3.2: tres casillas marcadas y una sin marcar (apertura de enmiendas dependientes, `:346`) | Sí, según su texto | "Número histórico: ADR-033", "Número vigente: ADR-037" (`:5-6`), apoyado en un archivo inexistente |
| `ADR-037-enmienda-2-convocatoria.md` | Enmienda explícita de ADR-037 y de la Enmienda 1 (`:5`) | "BORRADOR"; decisiones humanas aprobadas el 2026-10-02, texto pendiente de aprobación (`:3`, §9) | **No determinable**: el propio documento exige aprobación para ser normativo | Sus fuentes son `convocatoria-resumen.md` §6.20 y seis auditorías "evidencia, no normativa" (`:7`) |
| `ADR-043-…` | ADR asociado a la Enmienda 2 (no la enmienda) | "Propuesto" (`:5`) | No todavía | Bloqueado en implementación por ADR-038, T1 y P8 (`:45`) |
| `convocatoria-resumen.md` §6.7–§6.20 | Fuente de las decisiones que formalizan las enmiendas (Enmienda 1 `:9`; Enmienda 2 `:7`) | Decisiones humanas aprobadas. Según `:91`, "no constituye enmienda normativa" hasta su enmienda | Mixto (CD-04) | §6.20 es la fuente de la Enmienda 2 y ADR-043; §6.19 y anteriores están marcadas como sustituidas (`:477`, `:495`, `:516`) |
| `fase-6-estructura-y-perimetro-convocatoria.md` | Documento corregido por ADR-037 (§2.3) y subordinado por N11 | "Documento de congelación" | No (se declara no-ADR, `:4`) | No lleva ninguna anotación de que §3.4 esté sustituido |
| `implementation_plan.md` | Implementa ADR-037, Enmienda 1 y (rev. 3) Enmienda 2 y ADR-043 | "APROBADO (revisión 2)"; rev. 3 sin declaración de aprobación (`:3-4`) | Sí, como plan de ejecución | Base normativa (`:5`) sin la Enmienda 2 y con rutas inexistentes |
| `estado-fase6.md` | Cita ADR-037, Enmienda 1 (aprobada), Enmienda 2 (BORRADOR) y ADR-043 (Propuesto) (`:16`, `:70`) | Snapshot | No | §2 fila 1 solo menciona la Enmienda 1 y la "rev. 2.2" del plan |
| `golden-path.md` | Paso 3 reescrito según la Enmienda 2 §3.1–§3.2 y ADR-043 (diff sin commit) | "Escenario conceptual cerrado" | No | Presenta el flujo de la Enmienda 2 sin indicar que está en borrador |
| `api-contract-matrix.md` | Fila del webhook reescrita según la Enmienda 2 §3 y ADR-043; lista de estados con `FUNDING_REJECTED` (diff sin commit) | Snapshot no congelado | No | Por N11, ADR-037 y la Enmienda 1 prevalecen sobre su §2 |
| `ADR-038`, `ADR-040`, `ADR-041` | Citan ADR-037 como dependencia o precedente | "Aprobado" / "Aprobado parcialmente" | Normativos en su bloque | ADR-040 y ADR-041 mezclan la numeración antigua (Paso 6) |
| `plan-correccion-fase5-e-ia.md`, `estado-fase5.md` | Llaman "ADR-033" a Convocatoria y "ADR-037" a APIs/Frontend | Renumeración "PENDIENTE" | No | Contradicen la Enmienda 1 `:6` y el árbol actual (CD-02) |
| `hallazgos-front-fase2.md` | Usa "ADR-037" para la API y "ADR-033" para Convocatoria | Hallazgos | No | Es la fuente de R4, que cita la Enmienda 1 §2 |
| Auditorías del 2026-10-02 y 03 | Reinterpretan o cuestionan ADR-037, la Enmienda 1 §5.3 y N9 | Temporales | No | `auditoria-flujo-confirmed-fondos-v3.md` §14: "CASO 3 — CONTRADICCIÓN DOCUMENTAL" entre la Enmienda 1 §5.3/N9 y F-1/F-2, antes de la Enmienda 2 |

### 3.2 Cadena reconstruida

```text
fase-6-estructura-y-perimetro-convocatoria.md  (perímetro, 18 sept; no-ADR)
   │  corregido por ADR-037 §2.3 (§3.4); subordinado por N11 (§3.2, §3.5)
   ▼
ADR-037 (número histórico ADR-033; renombrado en b417d3a, 2026-09-27)
   │  Estado: "Aprobado"; cabecera con D2 pendiente; título "tentativo"
   ▼
ADR-037 Enmienda 1 ── fuente: convocatoria-resumen.md §6.7–§6.14 (30 sept)
   │  Estado: APROBADA 2026-09-30 (N1–N12); documento actualizado 2026-10-01 (§6.15)
   │  decisiones posteriores sin enmienda: §6.15 (R1, ID, P7, R3), §6.16 (B-1)
   │  C-01: §6.17 → §6.18 → §6.19 (cada una sustituye a la anterior)
   ▼
ADR-037 Enmienda 2 ── fuente: convocatoria-resumen.md §6.20 (2 oct; F-1, F-2, D1–D7, P9)
   │  Estado: BORRADOR (texto no aprobado)
   ├──► ADR-043 (Propuesto) — recuperación (D2)
   ▼
Documentos derivados:
   implementation_plan.md rev. 3 · estado-fase6.md §3bis
   golden-path.md paso 3 · api-contract-matrix.md §3 (fila webhook)
   ADR-037 :5/:119/:127 y Enmienda 1 :4/:195/:206/:332 (anotaciones de sustitución)
```

### 3.3 Discrepancias de la cadena (registradas, no resueltas)

1. **Estado de la Enmienda 2.**
   - El documento se declara BORRADOR (`:3`).
   - Pero el texto normativo de ADR-037 y de la Enmienda 1 ya lleva anotaciones "Sustituido por la Enmienda 2".
   - Los documentos derivados (`implementation_plan.md` rev. 3, `golden-path.md`, `api-contract-matrix.md`, `estado-fase6.md`) describen su flujo como el vigente.
   - → CD-03.
2. **Estado de ADR-037.** La cabecera y §7 siguen describiendo D2 como pendiente, aunque la Enmienda 1 (aprobada) lo resuelve y ordena actualizar la cabecera (§10.1). → CD-01.
3. **Número de ADR-037.**
   - El título dice "tentativo".
   - La Enmienda 1 y el resumen dicen "renumeración aprobada el 2026-09-28" con una fuente inexistente.
   - `plan-correccion` y `estado-fase5` dicen "pendiente".
   - El renombrado físico es del 2026-09-27 [git], anterior a la fecha de aprobación citada.
   - → CD-02.
4. **Fuente de las decisiones de C-01.** Hay tres registros sucesivos (§6.17, §6.18 y §6.19), cada uno marcado como sustituido por el siguiente, y §6.20 sustituye a los tres "en lo que se contradigan" (`:516`). Esta cadena de sustitución es interna y explícita. Se registra como duplicación (GV-3), no como contradicción.

---

## PASO 4 — Documentos canónicos (solo candidatos)

### 4.1 Candidatos a fuente canónica

| Candidato | Para qué decisión | Evidencia | Por qué es solo CANDIDATO |
|---|---|---|---|
| **ADR-037** | Diseño base del bloque | Estado "Aprobado" (`:3`) | Cabecera y §7 desactualizadas (CD-01); número "tentativo" (CD-02) |
| **ADR-037 Enmienda 1** | D2, D3, D4, `CLOSE_ON_TARGET`, cierre manual, idempotencia, N1–N12 | "APROBADA" (`:3`), §10.3.2 | Numeración apoyada en una fuente inexistente (CD-02) |
| **ADR-037 Enmienda 2** — CANDIDATO condicionado | Confirmación y aplicación separadas, `APPLY_FUNDS`, `FUNDING_REJECTED` | Decisiones aprobadas (§1.1), texto en BORRADOR | Exige aprobación humana para ser normativa (`:3`, §9) |
| **ADR-043** — CANDIDATO condicionado | Recuperación automática | "Propuesto" (`:5`) | Exige aprobación humana (regla 3.5, `:5`) |
| **`convocatoria-resumen.md` §6.15, §6.16, §6.19 (DH-5) y §6.20** | Decisiones del primer corte y de C-01 que no tienen enmienda propia (R1, ID, P7 en el corte, R3, B-1, DH-5, P9) | §6.15 `:418-441`; §6.20 `:514-533` | La cabecera del documento niega su carácter de decisión (CD-04); según `:91`, una decisión humana no modifica un ADR sin enmienda |
| **`implementation_plan.md`** | Orden y alcance del primer corte | "APROBADO (revisión 2)" | La rev. 3 no declara aprobación; hay referencias rotas (CD-07) |

### 4.2 Candidatos a documento derivado

| Documento | Deriva de | Evidencia |
|---|---|---|
| `golden-path.md` | Resúmenes de Fase 6 y, en el paso 3, la Enmienda 2 y ADR-043 | `:141` "No introduce decisiones de dominio nuevas" |
| `api-contract-matrix.md` | `golden-path.md` y, en la fila del webhook, la Enmienda 2 | `:3` "derivada directamente de `golden-path.md`" |
| `estado-fase6.md` §3bis | Resultados de ejecución y `implementation_plan.md` §15 | `:4`, `:58` |
| `convocatoria-resumen.md` §1–§5 | Sesión de diseño conceptual del 18 sept | `:3`, `:83-85` |
| `ia-resumen.md` §3, `identity-resumen.md` §6 | Decisiones de Convocatoria | `ia-resumen.md:39-81`; `identity-resumen.md:139` |

### 4.3 Candidatos a histórico

| Documento | Motivo | Evidencia |
|---|---|---|
| `fase-6-estructura-y-perimetro-convocatoria.md` (§3.2, §3.4, §3.5) | Sustituido o subordinado por ADR-037 y N11; apunta a un plan que no existe | ADR-037 §2.3 (`:80`: "debe marcarse como superado"); Enmienda 1 `:24` |
| `convocatoria-resumen.md` §6.17 y §6.18 | Sustituidas por §6.19 y §6.20 | `:477`, `:495`, `:516` |
| `auditoria-c01-idempotencia-fondos.md` | Bug C-01 descrito sobre `applyFunds(campaignRef, amount)`; el estado actual (`estado-fase6.md:72`) describe otra operación | `:1-16`; `estado-fase6.md` §3bis |
| `auditoria-c01-postcorreccion.md`, `auditoria-cierre-f1-f2.md`, `auditoria-flujo-confirmed-fondos-v3.md`, `debate-cierre-confirmed-fondos.md`, `auditoria-preimplementacion-cierre-convocatoria.md` | Las preguntas que planteaban quedaron decididas en §6.20 y la Enmienda 2 | Dictámenes "BLOQUEADO / ABIERTO / CASO 3 / REQUIERE DECISIONES" anteriores a §6.20 |
| `auditoria-delimitacion-cierre-convocatoria.md` | Previa a la redacción de la Enmienda 2 y ADR-043, que la citan como evidencia | Enmienda 2 `:7` |
| `plan-correccion-fase5-e-ia.md` (colisión de numeración) | Describe un estado del catálogo que ya no coincide con el árbol | `:24-27` frente a `b417d3a` [git] |
| `estado-fase5.md` §1 (colisiones) | Mismo motivo | `:33-35` |
| `auditoria-api-convocatoria.md`, `-v2.md` | Superadas por la v3 (otro bloque) | La v2 §7 audita a la v1; la v3 se titula "Final" |

### 4.4 Candidatos a auditoría (no deben usarse como especificación)

Todos los documentos de clase G del Paso 2, además de:
- `revision-ready-api-fase6.md`;
- `auditoria-api-convocatoria*.md`;
- este mismo informe.

La Enmienda 2 ya fija este criterio para las seis auditorías que cita: "evidencia, no normativa" (`:7`).

---

## PASO 5 — Duplicados y versiones

| Grupo | Archivos | Relación | Posible documento vigente | Evidencia | Requiere decisión humana |
|---|---|---|---|---|---|
| **GV-1** Auditoría API | `auditoria-api-convocatoria.md`, `-v2.md`, `-v3.md` | Versiones sucesivas de la misma auditoría (otro bloque, Capa 5) | CANDIDATO: `-v3.md` | La v2 §7 "Auditoría del informe anterior"; la v3 se titula "Final". Ninguna lleva fecha en el contenido | Sí: si se conservan v1 y v2 como históricas. Decide el bloque API |
| **GV-2** Preparación de la API | `plan-api-fase6.md`, `revision-ready-api-fase6.md`, `auditoria-api-convocatoria-v3.md` | Tres documentos sobre el mismo inventario E1–E25 | No determinable | `revision-ready…:8`; v3 §4 | Sí (bloque API, no Convocatoria) |
| **GV-3** Decisiones C-01 | `convocatoria-resumen.md` §6.17, §6.18, §6.19, §6.20 | Registros sucesivos dentro del mismo documento | §6.20, y §6.19 en lo que §6.20 no contradice | `:477`, `:495`, `:516` | No para identificar el vigente; sí para decidir si se conservan dentro del documento |
| **GV-4** Cadena de auditorías del cierre de fondos | `auditoria-c01-idempotencia-fondos.md` → `auditoria-c01-postcorreccion.md` → `auditoria-cierre-f1-f2.md` → `auditoria-flujo-confirmed-fondos-v3.md` → `debate-cierre-confirmed-fondos.md` → `auditoria-preimplementacion-…` → `auditoria-delimitacion-…` → `auditoria-cierre-final-…` | Snapshots encadenados del mismo problema. La "v3" del nombre sugiere una v1 y una v2 que no existen como archivos | CANDIDATO: `auditoria-cierre-final-convocatoria.md` como último snapshot | Fechas 2026-10-02 en todas las cabeceras; la síntesis normativa está en §6.20 y la Enmienda 2 | Sí: tratamiento como históricos |
| **GV-5** Auditorías documentales del bloque | `auditoria-documental-convocatoria.md`, `auditoria-herencia-fase5-convocatoria.md`, **este informe** | Perímetros solapados (catálogo, numeración, obsolescencias). Las tres son del 2026-10-03 | No determinable: este informe es inventario; las otras dos tienen alcance distinto | `auditoria-documental…` Partes 1, 8 y 9; `auditoria-herencia…` §3 y §7 | Sí |
| **GV-6** Dictámenes de cierre del bloque | `auditoria-porcentaje-convocatoria-fase6.md` ("BLOQUE CONVOCATORIA: CERRADO", `:111-112`) frente a `auditoria-cierre-final-convocatoria.md` ("NO CERRADO", `:107`) | Dos auditorías con dictámenes opuestos sobre el mismo hecho | No determinable. La primera no tiene cabecera; la segunda condiciona el cierre a aprobar la Enmienda 2 y ADR-043 | Citas indicadas | Sí. Ninguna es normativa |
| **GV-7** Estado de Convocatoria repetido | `estado-fase6.md` §3bis, `implementation_plan.md` §1–§2, `convocatoria-resumen.md` §6.15–§6.20, `auditoria-porcentaje-…`, `auditoria-cierre-final-…` | Varios documentos describen el estado del bloque con cifras distintas: 168, 190 o 194 tests; rev. 2.2 o rev. 3 | No determinable | `estado-fase6.md:63,79`; `auditoria-cierre-final…` §5 (194); `auditoria-porcentaje…:121` (194) | Sí |
| **GV-8** Identificadores reutilizados | P1–P3 (`contract-wiring-review.md`) frente a P1–P7 (Enmienda 1 §8); D1 (ADR-037 §2.6bis) frente a D1–D7 (§6.20); D2 (ADR-037 §7, autoasignación) frente a D2 (§6.20, recuperación); F-1/F-2 (contradicciones en el plan rev. 2.5, `:4`) frente a F-1/F-2 (decisiones, §6.20); R4 (Enmienda 1 §3.5, de `hallazgos-front-fase2.md`) frente a R4 (`CLOSE_ON_TARGET + CLOSE`, plan §14.2) | Mismo nombre, significado distinto | — | Citas indicadas. §6.19 `:510` ya renombró una colisión (K-1/K-2) | Sí: política de nombres |

No hay ADR físicamente duplicados: un archivo por número entre 024 y 043.

---

## PASO 6 — Referencias a numeración antigua

**Catálogo actual del árbol de trabajo:**

| Número | ADR | Fase |
|---|---|---|
| 033 | Saga de registro de activos | 5 |
| 034 | Pending allocation | 5 |
| 035 | HumanActor | 5 |
| 036 | Reversión administrativa | 5 |
| 037 | Convocatoria | 6 |
| 038 | Identidad | 6 |
| 039 | Blockchain | 6 |
| 040 | IA | 6 |
| 041 | APIs/Frontend | 6 |
| 042 | Reintentos de proyección | 5 |
| 043 | Recuperación de fondos | 6 |

Los cinco ADR de Fase 6 se renombraron en `b417d3a` (2026-09-27) [git].

| Referencia encontrada | Archivo | Contexto | ¿Coincide con el catálogo actual? | Acción futura |
|---|---|---|---|---|
| "Número histórico: ADR-033" | `ADR-037-enmienda-1:5` | Etiquetada explícitamente como histórica | Sí, como dato histórico | MANTENER |
| `ADR-033-convocatoria-…donationintent.md` como nombre actual del archivo | `ADR-037-enmienda-1:7`; `convocatoria-resumen.md:99`; `implementation_plan.md:5` | "pendiente de renombrar" | No: el archivo es `ADR-037-…` | CORREGIR DOCUMENTACIÓN |
| Tabla de renumeración en `ADR-042-frontend-web-paxfide-web.md` | `ADR-037-enmienda-1:6`; `convocatoria-resumen.md:99` | Fuente de la aprobación del 2026-09-28 | No: el archivo no existe y 042 es reintentos de proyección | NO DETERMINADO (requiere decidir dónde queda registrada la numeración) |
| "ADR-033 §2.4/§2.5/§2.6" con el sentido de Convocatoria | `convocatoria-resumen.md:110,159` | Texto de §6 que `:99` manda leer como ADR-037 | No (033 es Fase 5), aclarado en el propio documento | HISTÓRICO |
| "(número tentativo — confirmar contra el catálogo real …)" | `ADR-037:1`, `ADR-038:1`, `ADR-039:1`, `ADR-040:1`, `ADR-041:1` | Título | En parte: los números coinciden con el árbol, pero la marca contradice la "renumeración aprobada" | CONFIRMAR |
| "ADR-037 (tentativo)" … "ADR-041 (tentativo)" | `estado-fase6.md:17-20` | Tabla de estado por capa | Números sí; marca "tentativo" | CONFIRMAR |
| "posterior a ADR-037/034/035"; "que en ADR-037/034/035" | `ADR-040:3-4,68` | Orden de redacción de Fase 6 | No: 034 y 035 son hoy Fase 5; Identidad y Blockchain son 038 y 039 | REVISAR CON OTRO BLOQUE |
| "ADR-037/034/036"; "ADR-037/034/035/036" | `ADR-041:3,4,108` | Dependencias heredadas | No (mismo motivo) | REVISAR CON OTRO BLOQUE |
| "ADR-037 §2.2/§2.5/§2.6/§2.7/§4/§7-A.4" con el sentido de la API | `hallazgos-front-fase2.md:5,97,106,129,157,452,472,516` | Numeración antigua (037 = APIs/Frontend) | No: hoy es ADR-041. Hoy "ADR-037" es Convocatoria | REVISAR CON OTRO BLOQUE |
| "ADR-033" (Convocatoria), "ADR-034" (Identidad), "ADR-036" (IA) | `hallazgos-front-fase2.md:149,151,319,522,550,552,557,558` | Numeración antigua de Fase 6 | No | REVISAR CON OTRO BLOQUE |
| "ADR-033 (Fase 5)" como precedente de saga | `fase-6-estructura-…:138,155` | Explícitamente Fase 5 | Sí | MANTENER |
| ADR-033 (saga), ADR-035 (HumanActor), ADR-036 (reversión) | `golden-path.md:64,112,114` | Fase 5 | Sí | MANTENER |
| "ADR-033 a ADR-036 de Fase 6"; "ADR-037 (APIs/Frontend)"; "ADR-033 (Convocatoria)"; "ADR-036 (IA)"; "hueco de ADR-033" | `plan-correccion-fase5-e-ia.md:3,11-27,85,153,211,245` | Plan de corrección anterior a la renumeración | No | HISTÓRICO |
| "ADR-039 (solo en `develop` local) … A7.1" | `plan-correccion-fase5-e-ia.md:130` | ADR de Fase 5 sin número publicado | No: 039 es hoy Blockchain | REVISAR CON OTRO BLOQUE |
| "Colisiones 033–037 PENDIENTES"; "ADR-038 SUPERSEDIDO (develop local)"; "ADR-037 de Fase 6" (= API) | `estado-fase5.md:33-35,417,536,548` | Estado de Fase 5 | No | HISTÓRICO |
| "ADR-038/ADR-039 existen solo en `develop` local" | `ADR-042:6` | Nota de numeración (Fase 5) | No: hoy 038 y 039 son ADR de Fase 6 publicados | REVISAR CON OTRO BLOQUE |
| "ADR-043 es el primer número libre tras ADR-042" | `ADR-043:7` | Nota de numeración | Sí | CONFIRMAR (depende de ratificar el catálogo) |
| Convocatoria 033→037, Identidad 034→038, Blockchain 035→039, IA 036→040, APIs 037→041 | `convocatoria-resumen.md:99` | Tabla de correspondencia | Sí con el árbol; su fuente no existe | CONFIRMAR |
| ADR-037 = Convocatoria; ADR-038 = Identidad | `ADR-038:4,11,35,82,120`; `ADR-039:4`; `ADR-037-enmienda-2`; `ADR-043`; `estado-fase6.md`; `implementation_plan.md` | Uso actual | Sí | MANTENER |
| "ADR-041 §7-A.4" | `convocatoria-resumen.md:427`; `implementation_plan.md:254` | Idempotencia de creación de `DonationIntent` | Sí: fila 4 de ADR-041 §7-A (`ADR-041:98`) | MANTENER |

---

## PASO 7 — Separación de perímetros

| Dependencia | Documento | Qué aporta a Convocatoria | ¿Pertenece al bloque Convocatoria? |
|---|---|---|---|
| Identity (verificación de organización) | `ADR-038`; `identity-resumen.md:89` | Fuente del dato `VERIFIED` (X1, `OrganizationVerificationPort`). Sin implementación de producción, `app → convocatoria` queda bloqueado (Enmienda 2 §6; `estado-fase6.md:81`) | DEPENDENCIA EXTERNA |
| Identity (principal y roles) | `ADR-038`; `implementation_plan.md` §11 (X2) | `IdentityPrincipalPort`. Huecos: cuentas `INACTIVE` y `AccountNotFoundException` | DEPENDENCIA EXTERNA |
| Core (génesis de `Fund`) | `ADR-037` §2.3; Enmienda 2 §3.2, §6 | `clearFundsGenesis`; T1 (camino sin reintento interno); P8 (mensaje de outbox) | DEPENDENCIA EXTERNA |
| Core (autorización contextual) | `ADR-032`; Enmienda 1 §7.1 | Puerto contextual; P6; respaldo del `REPRESENTATIVE` (no existe, `ADR-032:53`) | DEPENDENCIA EXTERNA |
| Core (`PhysicalAsset`) | `ADR-029`; Enmienda 1 §7.2, §9.1 | `campaignRef` en activos; P2 | DEPENDENCIA EXTERNA |
| Core (precedentes) | `ADR-033` (saga), `ADR-026`, `ADR-031`, `documento-maestro-proyecto.md` §6.2 | Patrón de orquestación; cardinalidad mínima; `Fund` por donante | NO (precedente heredado) |
| App (orquestador, scheduler) | Enmienda 2 §3.2, §5; `ADR-043`; plan §11 | Transacción única ledger + génesis + outbox; disparo inmediato; scheduler | DEPENDENCIA EXTERNA. La consulta de recuperables **sí** es de Convocatoria (Enmienda 2 §5; ADR-043 punto 2) |
| API (contratos HTTP) | `ADR-041` (§7-A.4, P4); `api-contract-matrix.md` §2, §2b, §3; `plan-api-fase6.md`; `revision-ready-api-fase6.md`; `auditoria-api-convocatoria*.md` | Rutas, cuerpos y respuestas de las operaciones de Convocatoria; estado público `FUNDING_REJECTED` | DEPENDENCIA EXTERNA (Capa 5). Las operaciones de dominio son de Convocatoria; su contrato HTTP no |
| API (webhook de pago) | Enmienda 1 §8 (P3); Enmienda 2 §6 | Confirmación de intenciones de pasarela | DEPENDENCIA EXTERNA |
| Frontend | `hallazgos-front-fase2.md` (R4); `front-fase2.md` (inexistente) | Origen del requisito de idempotencia de comandos (Enmienda 1 §3.5) | NO |
| IA | `ADR-040`; `ia-resumen.md` §3; Enmienda 1 §7.4 (N10) | Consume Convocatoria y el ledger; necesita `confirmationSource` | DEPENDENCIA EXTERNA (dirección inversa) |
| Blockchain | `ADR-039`; `estado-fase6.md` §3.2 | Solo comparte el precedente "orquestador en `app`" (`estado-fase6.md:36`) | NO |
| Dataset / Golden Path | `golden-path.md` pasos 2–3 | Escenario de demo que usa Convocatoria (`FLEXIBLE`, `GATEWAY`) | NO (Capa 6) |
| Proceso | `reglas-equipo-y-agentes.md` (2.1, 2.4, 2.6, 3.4, 3.5) | Exige ADR antes del código y evidencia de ejecución | NO (transversal) |
| Producto | Enmienda 1 §8 (P1, P5, P7); Enmienda 2 §4 (D4) | Dinero no aceptable, efectivo, ventana de fechas | SÍ (decisiones pendientes del propio bloque) |

---

## PASO 8 — `api-contract-matrix.md`

**Papel documental actual.**
- Se declara "Snapshot contractual de Fase 6, no un ADR" y "(no congelado)" (`:1-3`).
- Su "Nota de procedencia y vigencia" (`:123-125`) obliga a verificar contra el repositorio antes de considerar operativo un endpoint.
- Por la regla N11 (Enmienda 1 `:24`), ADR-037 y la Enmienda 1 prevalecen sobre su §2.
- **No se declara canónico.**

| Pregunta | Respuesta | Evidencia |
|---|---|---|
| 1. Parte de Convocatoria | §2 "Convocatoria" (crear, asignar empleado, lectura pública, descubrimiento); §2b "Convocatoria — lectura administrativa"; en §3, las filas `donation-intents`, `webhooks/payments` y el canal del `trackingCode`; en §5, la fila `GET /public/campaigns/{publicCode}/narrative` (IA sobre Convocatoria); la lista "Frontend Contract" (`:103-104`, `:106`) | `:27-34`, `:71-77`, `:40-42`, `:65`, `:99-110` |
| 2. Partes de otros bloques | §1 Platform (Identidad); `/auth/*` y `GET /account/donations` en §3 (Identidad/Core); §4 y §4b `PhysicalAsset` (Core); §5 Tracking (Fase 3); §6 Operaciones internas (Blockchain); reglas finales 1–8 (API) | `:18-25`, `:43-46`, `:48-57`, `:79-95`, `:59-69`, `:114-121` |
| 3. Partes heredadas | La matriz v1 "derivada directamente de `golden-path.md`" (`:3`); filas "YA EXISTE" de Fase 3; `POST /auth/register` (Fase 4) | `:3`, `:43`, `:63-64` |
| 4. Partes añadidas después | §2b y §4b ("nuevo, cerrado durante el Frontend Contract", `:71`, `:79`), ubicadas detrás de §6, fuera de orden. Diff sin commit: fila del webhook (Enmienda 2 §3; ADR-043) y lista de estados con `FUNDING_REJECTED` (`:41`, `:104`) [git] | Citas indicadas |
| 5. Versiones o snapshots | Un solo archivo ("v1" en el título). Historial: `7a51ecb` (2026-09-22) y `e3a0b25` [git], más el diff sin commit. No hay otra copia en `Documentos/` | [git] |
| 6. Fuente de cada contrato | Convocatoria §2 y §3 → `golden-path.md`, ADR-037 §2.6 y §5, ADR-041; fila webhook → Enmienda 2 §3 (BORRADOR) y ADR-043 (Propuesto); §2b → "Frontend Contract" (sin documento propio en el árbol); §1 → ADR-038; §4/§4b → ADR-029, ADR-032, `golden-path.md` §5; §5 narrativa → `ia-resumen.md`, ADR-040 | Citas en cada fila |

**Discrepancias de la matriz con documentos de Convocatoria.** Se registran, sin evaluar la corrección técnica de los endpoints. Ver CD-09.

---

## PASO 9 — Informes de auditoría

| Informe | Fecha | Perímetro | Resultado | ¿Documentación canónica? | ¿Parece histórico? |
|---|---|---|---|---|---|
| `auditoria-api-convocatoria.md` | Sin fecha en el contenido | API (Capa 5) | Inventario de endpoints; recomendación documental (§10) | No | Sí (superado por la v2 y la v3) |
| `auditoria-api-convocatoria-v2.md` | Sin fecha | API (Capa 5) | 2 A / 8 B / 4 C / 11 D; "no está listo" (`:5-10`) | No | Sí |
| `auditoria-api-convocatoria-v3.md` | Sin fecha | API (Capa 5) | "C — API documentalmente abierta" (`:151`) | No | No determinable (último de GV-1) |
| `revision-ready-api-fase6.md` | Sin fecha | API (Capa 5), revisión de `plan-api-fase6.md` | "LISTO PARCIALMENTE" (`:4`) | No | No determinable |
| `auditoria-c01-idempotencia-fondos.md` | 2026-10-02 | C-01 | "A — BUG CONFIRMADO" (`:10`) | No ("No es documentación canónica", `:4`) | Sí |
| `auditoria-c01-postcorreccion.md` | 2026-10-02 | C-01 después de la corrección; reauditoría DH-1–DH-5 | "BLOQUEADO POR DECISIÓN HUMANA" (§12) | No (`:4`) | Sí |
| `auditoria-cierre-f1-f2.md` | 2026-10-02 | F-1/F-2 | "ABIERTO — REQUIERE DECISIÓN HUMANA" (§13) | No (`:4`) | Sí |
| `auditoria-flujo-confirmed-fondos-v3.md` | 2026-10-02 | `CONFIRMED` → fondos | "CASO 3 — CONTRADICCIÓN DOCUMENTAL" (§14) | No (`:4`) | Sí |
| `debate-cierre-confirmed-fondos.md` | 2026-10-02 | Opciones de D1–D4 | "REQUIERE DECISIONES HUMANAS" (§7) | No (`:4`) | Sí |
| `auditoria-preimplementacion-cierre-convocatoria.md` | 2026-10-02 | D1–D7 | "BLOCKED — ARCHITECTURE GAP" (§4) | No (`:3`) | Sí |
| `auditoria-delimitacion-cierre-convocatoria.md` | 2026-10-02 | Trabajo propio frente a ajeno | Delimitación sin veredicto global; listas finales | No (`:3`) | Sí (fuente de la Enmienda 2, `:7`) |
| `auditoria-cierre-final-convocatoria.md` | 2026-10-02 | Cierre del bloque | "NO CERRADO": faltan la aprobación de la Enmienda 2 y la de ADR-043 (§6) | No (`:4`) | No determinable (último de GV-4) |
| `auditoria-herencia-fase5-convocatoria.md` | 2026-10-03 | Herencia de Fase 5 | "REQUIERE DECISIÓN HUMANA" (H-1, numeración) (§10) | No | No |
| `auditoria-porcentaje-convocatoria-fase6.md` | Sin fecha | Avance del bloque | "BLOQUE CONVOCATORIA: CERRADO" (`:111-112`) | No (sin cabecera de naturaleza) | No determinable; dictamen opuesto al de `auditoria-cierre-final-…` (GV-6) |
| `auditoria-documental-convocatoria.md` | 2026-10-03 | Catálogo, contrato API, BD, obsolescencias | "D. DOCUMENTACIÓN: no está lista para considerarse canónica" (Parte 10) | No | No |

Ningún informe de esta tabla se toma aquí como fuente normativa. Los datos que solo constan en ellos se marcan como [no reverificado] cuando se usan.

---

## PASO 10 — Dictamen

### A. Mapa documental actual

La documentación de Convocatoria se organiza en cinco capas superpuestas, sin una jerarquía declarada en un solo lugar.

1. **Normativa.**
   - ADR-037 y la Enmienda 1 (aprobada).
   - La Enmienda 2 (borrador) y ADR-043 (propuesto), ya citados como vigentes por los documentos derivados.
   - La única regla de precedencia escrita es N11 (Enmienda 1 `:24`): ADR-037 y la Enmienda 1 sobre `fase-6-…` y la matriz §2.
   - La Enmienda 2 declara su propia precedencia (`:15`) estando en borrador.
2. **Registro de decisiones.** `convocatoria-resumen.md` §6, que hace de libro de decisiones humanas (incluidas algunas sin enmienda: §6.15, §6.16, §6.20-P9) aunque su cabecera lo describe como "pre-ADR … no es una decisión final".
3. **Ejecución.** `implementation_plan.md`, aprobado en la revisión 2, con revisiones 2.1–3 acumuladas en un único párrafo de cabecera.
4. **Estado y derivados.**
   - `estado-fase6.md` (compartido por las cinco capas).
   - `golden-path.md` y `api-contract-matrix.md` (Capas 6 y 5), modificados sin commit para reflejar la Enmienda 2.
5. **Evidencia temporal.** Once auditorías y un debate del bloque, más tres auditorías y un plan de la Capa 5 cuyo nombre contiene "convocatoria" pero cuyo perímetro es la API. Ninguno está marcado como histórico en su nombre o en un índice.

A eso se suman documentos citados que no existen (seis rutas, §1.4) y una numeración de ADR coherente en el árbol pero sin registro verificable de la decisión que la fija (CD-02).

### B. Candidatos a documentación canónica

Solo candidatos, sin modificar nada:
1. `ADR-037-convocatoria-ledger-assignment-donationintent.md`: CANDIDATO.
2. `ADR-037-enmienda-1-convocatoria.md`: CANDIDATO.
3. `ADR-037-enmienda-2-convocatoria.md`: CANDIDATO condicionado a su aprobación.
4. `ADR-043-recuperacion-aplicacion-fondos-convocatoria.md`: CANDIDATO condicionado a su aprobación.
5. `convocatoria-resumen.md` §6.15, §6.16, §6.19 (DH-5) y §6.20, como registro de decisiones humanas: CANDIDATO, sujeto a CD-04.
6. `implementation_plan.md`, como plan de ejecución del primer corte: CANDIDATO, sujeto a CD-07.

### C. Documentación histórica / derivada / temporal

- **Derivada:**
  - `golden-path.md`;
  - `api-contract-matrix.md` (snapshot contractual, no ADR);
  - `estado-fase6.md` §3bis;
  - `convocatoria-resumen.md` §1–§5;
  - `ia-resumen.md` §3;
  - `identity-resumen.md` §6.
- **Candidatos a histórico:**
  - `fase-6-estructura-y-perimetro-convocatoria.md`, al menos §3.2, §3.4 y §3.5;
  - `convocatoria-resumen.md` §6.17 y §6.18;
  - `auditoria-c01-idempotencia-fondos.md`, `auditoria-c01-postcorreccion.md`, `auditoria-cierre-f1-f2.md`, `auditoria-flujo-confirmed-fondos-v3.md`, `debate-cierre-confirmed-fondos.md`, `auditoria-preimplementacion-cierre-convocatoria.md`, `auditoria-delimitacion-cierre-convocatoria.md`;
  - `plan-correccion-fase5-e-ia.md` y `estado-fase5.md`, en lo que se refiere a la numeración;
  - `auditoria-api-convocatoria.md` y `-v2.md` (otro bloque).
- **Temporales vigentes, no normativos:**
  - `auditoria-cierre-final-convocatoria.md`;
  - `auditoria-herencia-fase5-convocatoria.md`;
  - `auditoria-documental-convocatoria.md`;
  - `auditoria-porcentaje-convocatoria-fase6.md`;
  - este informe.
- **Otro bloque (Capa 5), no son de Convocatoria aunque su nombre lo sugiera:**
  - `auditoria-api-convocatoria*.md`;
  - `plan-api-fase6.md`;
  - `revision-ready-api-fase6.md`.

### D. Conflictos que necesitan una segunda fase

Contradicciones documentales reales. No se resuelve ninguna.

| ID | Contradicción | Documentos (evidencia) |
|---|---|---|
| **CD-01** | ADR-037 dice "Pendiente … de una única decisión de producto (D2)" y mantiene D2 en 🔴 y "Solo D2 queda". La Enmienda 1, aprobada, resuelve D2 y ordena sustituir esa cabecera | `ADR-037:3,173,187`; `ADR-037-enmienda-1:298,302` |
| **CD-02** | Numeración de ADR-037 a ADR-041:<br>• "aprobada el 2026-09-28", con una fuente inexistente;<br>• "número tentativo" en los títulos;<br>• "PENDIENTE de decisión humana — sin renumerar".<br>El renombrado físico es del 2026-09-27, anterior a la aprobación citada | `ADR-037-enmienda-1:5-7`; `convocatoria-resumen.md:99`; `ADR-037:1`, `ADR-038:1`, `ADR-040:1`, `ADR-041:1`; `plan-correccion-fase5-e-ia.md:24-27`; `estado-fase5.md:33`; `b417d3a` [git] |
| **CD-03** | La Enmienda 2 se declara BORRADOR y no normativa. Sin embargo:<br>• ADR-037 y la Enmienda 1 llevan en su texto anotaciones "Sustituido por la Enmienda 2";<br>• `implementation_plan.md` rev. 3, `golden-path.md`, `api-contract-matrix.md` y `estado-fase6.md` describen su flujo como el vigente;<br>• la propia Enmienda 2 declara precedencia sobre ADR-037.<br>Lo mismo ocurre con ADR-043 ("Propuesto"), citado como referencia de flujo | `ADR-037-enmienda-2:3,15,150-152`; `ADR-037:5,119,127`; `ADR-037-enmienda-1:4,195,206,332`; `implementation_plan.md:4,243`; `golden-path.md:30-40`; `api-contract-matrix.md:41,104`; `ADR-043:5` |
| **CD-04** | `convocatoria-resumen.md` se presenta como "Diseño pre-ADR … no es una decisión final". A la vez se declara "única fuente válida" (§6.19), "primer y único registro documental" (§6.17) y fuente de las enmiendas. Además, `:91` dice que sus decisiones "no constituyen enmienda normativa" | `convocatoria-resumen.md:3,91,458,495,516` |
| **CD-05** | Respaldo del `REPRESENTATIVE`:<br>• "Autorización de respaldo CERRADA" y "Enmienda ADR-032 … contenido ya decidido";<br>• frente a ADR-032, que excluye al `REPRESENTATIVE`, y la Enmienda 1, que constata que el respaldo no existe y deja P6 abierto;<br>• `golden-path.md` §3 y `api-contract-matrix.md:53` lo presentan como existente | `convocatoria-resumen.md:54,73,24-25`; `fase-6-…:98-108`; `ADR-032:53`; `ADR-037-enmienda-1:165,233-234`; `golden-path.md:92`; `api-contract-matrix.md:53` |
| **CD-06** | `ConvocatoriaReadPort` "ya existente" (ADR-037) frente a "No existe" (plan). `scanBasePackages = "com.traceability"` (ADR-037) frente a `{"com.traceability","identity"}` (plan, "corregido el 2026-10-01") | `ADR-037:76,106`; `implementation_plan.md:65,67` |
| **CD-07** | `implementation_plan.md`:<br>• el estado "APROBADO (revisión 2)" no cubre la rev. 3;<br>• el título y la base normativa dicen "ADR-037 + Enmienda 1", sin la Enmienda 2, que la rev. 3 implementa;<br>• la base normativa cita rutas inexistentes;<br>• §3.5 lista los estados sin `FUNDING_REJECTED` y dice "Sin estados nuevos", mientras §4.4 y §8 lo usan | `implementation_plan.md:1,3-5,130-131,179,262` |
| **CD-08** | `estado-fase6.md`:<br>• §1 dice "solo Blockchain tiene implementación real iniciada"; §2 y §3bis dicen "Primer corte implementado";<br>• §2 cita la "rev. 2.2" del plan y solo la Enmienda 1, frente a la rev. 3 y la Enmienda 2 de §3bis;<br>• §5 y §6 mantienen como "máxima severidad" la idempotencia de `clearFundsGenesis` sin reflejar la barrera `APPLY_FUNDS` de la Enmienda 2 | `estado-fase6.md:10,16,70-72,100,108`; `ADR-037-enmienda-2` §3.3 |
| **CD-09** | `api-contract-matrix.md` frente a la Enmienda 1 y ADR-037 (precedencia N11 declarada, sin reconciliar). Los puntos de choque son:<br>• "módulo `convocatoria` sin código todavía" (`:31`) frente a `estado-fase6.md` §3bis;<br>• una sola operación "asignar empleado" con "DISEÑO CERRADO" (`:32`) frente a las dos operaciones de la Enmienda 1 §4.1 y P4 (§7.3);<br>• "ninguna — solo prepara redirección a pasarela" en `donation-intents` (`:40`) frente a ADR-037 §2.6 (crea la `DonationIntent`) y la Enmienda 1 §5 (`BANK_TRANSFER`);<br>• "dominio subyacente CERRADO E IMPLEMENTADO" en el webhook (`:41`) frente a un orquestador no implementado (`estado-fase6.md:81,83`);<br>• "matriz ADR-021-D ya cerrada" (`:33`) frente a "Campos del read model público no definidos en ningún documento" (`implementation_plan.md:49`) | Citas indicadas |
| **CD-10** | Decisiones humanas G1–G3 (2026-10-01) registradas solo en un documento de estado. No constan en `convocatoria-resumen.md` §6, ni en `implementation_plan.md`, ni en las enmiendas. G3 ("una persona, como mucho una asignación activa por convocatoria") no figura en la Enmienda 1 §4.2, que regula la unicidad solo para `EMPLOYEE` entre convocatorias | `estado-fase6.md:68`; búsqueda de `G1`/`G2`/`G3`/`replacementActingRole` en `Documentos/` sin otros resultados normativos; `ADR-037-enmienda-1:149` |
| **CD-11** | `fase-6-estructura-…` no lleva ninguna marca de sustitución pese a que:<br>• ADR-037 §2.3 exige que "debe marcarse como superado" (§3.4);<br>• su §3.2 (sin autoasignación; responsable solo `EMPLOYEE`/`REPRESENTATIVE`) choca con la Enmienda 1 §4;<br>• su §3.1 ("listado público: No confirmado") choca con `convocatoria-resumen.md` §2 ("confirmado") y ADR-037 §7 ("necesidad … confirmada") | `fase-6-…:72,77-79,92-96`; `ADR-037:80,179`; `convocatoria-resumen.md:17`; `ADR-037-enmienda-1:24,157` |
| **CD-12** | Numeración mixta en ADR de otros bloques que citan Convocatoria: "ADR-037/034/035/036", donde 034–036 son hoy ADR de Fase 5 | `ADR-040:3-4,68`; `ADR-041:3-4,108` (decide el bloque dueño) |
| **CD-13** | Grafía `EXPIRED-UNKNOWN` en ADR-037, la matriz y el plan, frente a `EXPIRED_UNKNOWN` en el enum del código | `ADR-037:118`; `api-contract-matrix.md:104`; `implementation_plan.md:130`; `convocatoria/…/DonationIntentStatus.java:15` (leído solo para confirmar la grafía) |
| **CD-14** | Dictámenes opuestos sobre el cierre del bloque: "CERRADO" frente a "NO CERRADO". Ninguno es normativo; afecta a qué documento se cite como estado | `auditoria-porcentaje-convocatoria-fase6.md:111-112`; `auditoria-cierre-final-convocatoria.md:107-114` |

**No se registran como contradicciones** las sustituciones que ya están explícitas y anotadas en el propio texto:
- §6.17 → §6.18 → §6.19 → §6.20 en `convocatoria-resumen.md`;
- K-1/K-2;
- R2 subordinado a ADR-037 §2.5 (`implementation_plan.md:120`).

Se registran como duplicación (GV-3, GV-8).

---

## Resumen numérico

| Concepto | Cifra |
|---|---|
| Documentos revisados (inventario completo de `Documentos/`) | 70; diez de ellos leídos completos |
| Documentos DIRECTOS | 19 (8 núcleo + 11 auditorías o debate) |
| Candidatos a histórico | 9 documentos completos (7 auditorías o debate del bloque y 2 auditorías API) y 4 parciales (`fase-6-…` §3.2/§3.4/§3.5; resumen §6.17/§6.18; `plan-correccion-fase5-e-ia.md` y `estado-fase5.md` en lo relativo a la numeración) |
| Auditorías relacionadas | 15 (11 del bloque y 4 de la Capa 5) |
| Candidatos canónicos | 6 (2 condicionados a aprobación) |
| Grupos de duplicados o versiones | 8 (GV-1 a GV-8) |
| Contradicciones documentales | 14 (CD-01 a CD-14) |
| Referencias ADR potencialmente obsoletas | 20 filas en el Paso 6, de las que 15 son potencialmente obsoletas:<br>• 1 CORREGIR DOCUMENTACIÓN<br>• 4 CONFIRMAR<br>• 6 REVISAR CON OTRO BLOQUE<br>• 3 HISTÓRICO<br>• 1 NO DETERMINADO<br>Las otras 5 son MANTENER |
| Rutas citadas inexistentes | 6 |
| Dependencias externas | 9 (Paso 7: Identity ×2, Core ×3, App, API ×2, IA) |

---

*Archivo creado por esta auditoría: solo `Documentos/auditoria-inventario-documental-convocatoria.md`. No se modificó ningún otro archivo. No se hizo commit ni push.*
