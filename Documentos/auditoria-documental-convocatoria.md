# Auditoría documental y contractual — bloque Convocatoria (Fase 6)

**Tipo:** solo lectura. No se modificó código, tests, pom, configuración ni ningún documento existente. No se renumeró, renombró, movió ni borró nada.
**Fecha:** 2026-10-03. **Rama:** `develop`, HEAD `673eda9`. `convocatoria/` sigue sin versionar.
**Perímetro:** el bloque Convocatoria. Los demás bloques (Identidad, IA, Blockchain, `PhysicalAsset`, Tracking) solo aparecen cuando Convocatoria depende de ellos o ellos dependen de Convocatoria.

**Tests ejecutados en esta auditoría:** `mvn -o -q test -pl convocatoria` terminó con código 0. La suma de los informes de Surefire da `194 tests, 0 failures, 0 errors, 0 skipped`, repartidos en 20 clases.

**Evidencia:**
- **[A]** código leído;
- **[B]** test ejecutado ahora;
- **[D]** documento;
- **[E]** inferencia, marcada como tal.

---

## PARTE 1 — Catálogo documental

Las cabeceras se leyeron una por una. El nombre de un archivo no se toma como prueba de su autoridad.

| Archivo | Tipo | Autoridad | Vigencia | Relación con Convocatoria | Observaciones |
|---|---|---|---|---|---|
| `ADR-037-convocatoria-ledger-assignment-donationintent.md` | ADR | normativa | parcialmente vigente | ADR canónico del bloque | Cabecera: "Aprobado", pero el título conserva "(número tentativo …)". §2.6 (paso 3 y estados) quedó sustituido por la Enmienda 2 (borrador). La línea 118 dice `EXPIRED-UNKNOWN`; el código usa `EXPIRED_UNKNOWN` |
| `ADR-037-enmienda-1-convocatoria.md` | enmienda | normativa | parcialmente vigente | Enmienda 1 de ADR-037 | "APROBADA" el 2026-09-30, con N1–N12. Las líneas 6-7 citan un archivo inexistente y un nombre de archivo antiguo (Parte 8). La Enmienda 2 sustituye §5.3 y la frase de N9 |
| `ADR-037-enmienda-2-convocatoria.md` | enmienda | normativa **pendiente**: hoy es informativa | no determinable hasta su aprobación | Enmienda 2 de ADR-037 | "BORRADOR … requiere aprobación humana explícita (§9) para ser normativo". Las casillas de §9 están sin marcar |
| `ADR-043-recuperacion-aplicacion-fondos-convocatoria.md` | ADR | normativa **pendiente** | no determinable hasta su aprobación | ADR asociado a la Enmienda 2 (recuperación) | "Propuesto". Lo exige la regla 3.5 |
| `ADR-038-identidad-platform-administrator-verificacion-organization.md` | ADR | normativa (otro bloque) | vigente para su bloque | Dependencia: verificación de `Organization`, JWT y `HumanAccount` | "Aprobado", con el título en "(número tentativo …)" |
| `ADR-040-ia-convocatoria-audit-facts.md` | ADR | normativa (otro bloque) | parcialmente vigente | Consume Convocatoria (narrativa de campaña) | "Aprobado parcialmente". La línea 4 cita "ADR-037/034/035" con la numeración antigua de Fase 6 |
| `ADR-041-api-frontend-contratos-http.md` | ADR | normativa (capa API) | parcialmente vigente | Reglas HTTP y mapeo endpoint → hueco | "Aprobado", con el título en "(número tentativo …)". Las líneas 3, 4 y 108 usan la numeración antigua ("ADR-037/034/035/036"). §2.3 da por pendiente la idempotencia de la génesis |
| `api-contract-matrix.md` | contrato | contractual (el propio documento dice "Snapshot contractual … no congelado") | parcialmente vigente | Endpoints de Convocatoria (§2, §2b, §3) | La línea 31 dice "módulo `convocatoria` sin código todavía" (obsoleto). Faltan siete operaciones implementadas (Parte 3) |
| `convocatoria-resumen.md` | resumen | normativa para las decisiones de §6.x. Según su línea 91, una decisión humana es normativa solo cuando está registrada | parcialmente vigente | Registro de decisiones del bloque | La cabecera dice "Diseño pre-ADR … no es una decisión final", lo que choca con su papel de registro de decisiones (§6.7–§6.20). La línea 99 cita una fuente inexistente |
| `fase-6-estructura-y-perimetro-convocatoria.md` | plan | histórica (precedencia N11: ADR-037 y la Enmienda 1 prevalecen sobre él) | parcialmente vigente | Perímetro inicial | ADR-037 §2.3 sustituye su §3.4. Se presenta como "previo a `plan-ejecucion-agentes-fase6.md`", archivo que no existe |
| `implementation_plan.md` | plan | normativa de ejecución ("APROBADO (revisión 2)"; la revisión 3 añade la Enmienda 2) | vigente con referencias rotas | Plan del primer corte | La línea 5 cita `ADR-033-convocatoria-…` y `claude/ADR-037-enmienda-1-…`, rutas que no existen. §4.2 da nombres de colección "provisionales" (Parte 5) |
| `golden-path.md` | contrato (escenario) | informativa: "Escenario conceptual cerrado. No es ejecutable hoy" | vigente como escenario | Flujo de donación de extremo a extremo | Los ADR-033/035/036 que cita en las líneas 64, 112 y 114 son los de Fase 5 (uso correcto) |
| `estado-fase6.md` | estado | informativa (resultados de ejecución) | parcialmente vigente | §3bis describe Convocatoria | La línea 10 dice "solo Blockchain tiene implementación real". La línea 79 dice 190 tests; hoy son 194 [B]. No refleja el cambio de reintento |
| `contract-wiring-review.md` | contrato (revisión) | informativa | parcialmente vigente | Origen de P1/P2/P3 del cableado | Sus P1/P2/P3 son distintos de los P1–P7 de la Enmienda 1: mismo identificador con distinto significado (Parte 9) |
| `plan-api-fase6.md` | plan | informativa (sin cabecera de estado ni aprobación) | parcialmente vigente | Plan de la capa API | §1 describe bien el código actual: no hay controladores de Convocatoria y `api`/`app` no dependen de `convocatoria` |
| `revision-ready-api-fase6.md` | auditoría | informativa | snapshot | Revisión de `plan-api-fase6.md` | Dictamen "LISTO PARCIALMENTE". Tabla E1–E25 |
| `auditoria-api-convocatoria.md`, `-v2.md`, `-v3.md` | auditoría | informativa | snapshot; la v3 sustituye a las anteriores | Clasificación de endpoints | Tres versiones sucesivas de la misma auditoría |
| `auditoria-c01-idempotencia-fondos.md` | auditoría | informativa | histórica: el bug C-01 está corregido en el código | C-01 | Dice "Naturaleza: informe temporal" |
| `auditoria-c01-postcorreccion.md`, `auditoria-cierre-f1-f2.md`, `auditoria-flujo-confirmed-fondos-v3.md`, `debate-cierre-confirmed-fondos.md`, `auditoria-preimplementacion-cierre-convocatoria.md`, `auditoria-delimitacion-cierre-convocatoria.md` | auditoría / debate | informativa | histórica | Proceso de cierre de C-01 y de D1–D7 | Todos se declaran "temporal". Citan 180 tests, una cifra ya superada |
| `auditoria-cierre-final-convocatoria.md` | auditoría | informativa | snapshot vigente | Cierre del bloque | Dictamen NO CERRADO: solo bloquean las aprobaciones de la Enmienda 2 y ADR-043 |
| `auditoria-herencia-fase5-convocatoria.md` | auditoría | informativa | snapshot vigente | Herencia de Fase 5 | Del 2026-10-03 |
| `auditoria-porcentaje-convocatoria-fase6.md` | auditoría | informativa | no determinable | Avance del bloque | Sin cabecera de fecha, rama, autor ni naturaleza; sin versionar. No se usa como fuente aquí |
| `ADR-028` … `ADR-032`, `ADR-035` (Fase 5) | ADR | normativa (heredada) | vigente | Dependencias en `core` y `contracts` | Detalle en `auditoria-herencia-fase5-convocatoria.md` |
| `estado-fase5.md`, `plan-correccion-fase5-e-ia.md` | estado / plan | informativa | parcialmente vigente | Origen de la colisión de numeración | Siguen diciendo "PENDIENTE — sin renumerar" |

---

## PARTE 2 — El ADR de Convocatoria

### 2.1 ADR canónico y enmiendas

- **ADR canónico:** `ADR-037-convocatoria-ledger-assignment-donationintent.md`, con el número 037 [A: nombre en el árbol; D: Enmienda 1:5-6]. El número histórico era ADR-033 (Enmienda 1:5). El archivo se renombró en `b417d3a`.
- **Enmiendas de ADR-037:**
  1. `ADR-037-enmienda-1-convocatoria.md`: APROBADA.
  2. `ADR-037-enmienda-2-convocatoria.md`: BORRADOR.
- **ADR asociado, que no es enmienda:** ADR-043, "Propuesto", exigido por la Enmienda 2 (cabecera, línea 6).
- **No hay otras enmiendas** de ADR-037 en `Documentos/`.

### 2.2 Decisiones de ADR-037 original

| § | Decisión | Estado tras las enmiendas |
|---|---|---|
| 2.1 | `Convocatoria`: `publicCode` único, configuración, ventana | Ampliada por la Enmienda 1 §3 (configuración versionada, tipos y medios, cierre manual) |
| 2.2 | `CampaignFundingLedger` 1:1 con `campaignRef` | La Enmienda 1 §3.1 lo limita a "solo si acepta `MONETARY`" |
| 2.3 | Corrección de `STRICT` (sustituye `fase-6-…` §3.4) | La Enmienda 1 §6 lo cambia |
| 2.4 | `CampaignAssignment` | La Enmienda 1 §4 añade `ADMINISTRATOR` como responsable, dos operaciones y `actingRole` |
| 2.5 | `CampaignResponsibleState`, "≥1 responsable" | Vigente |
| 2.6 | `DonationIntent`: correlación por `paymentSessionId`; estados `PENDING/CONFIRMED/FAILED/EXPIRED-UNKNOWN`; paso 3 hacia `clearFundsGenesis` | La Enmienda 1 §5 añade campos, medios y barrera. **La Enmienda 2 (borrador) sustituye el paso 3, la barrera de §5.3 y la lista de estados (`+FUNDING_REJECTED`)** |
| 2.6bis | D1: el cierre no invalida intenciones ya aceptadas | Vigente |
| 5 | Autorización | La Enmienda 1 §4.4 y §7.1 añaden la autorización contextual. La Enmienda 2 §3.1 sustituye la frase de N9 |
| 6 | Observabilidad: audit log append-only | Vigente |

### 2.3 Qué cambia cada enmienda

- **Enmienda 1 (§2, tabla de las líneas 45-56):**
  - D2 / `CampaignAssignment`;
  - D3 (configuración de tipos);
  - D4 (medios y confirmación);
  - `CLOSE_ON_TARGET` → `onTargetReached`;
  - ledger condicionado a `MONETARY`;
  - cierre manual;
  - campos y barrera de `DonationIntent`;
  - autorización contextual;
  - idempotencia de los comandos de escritura (§3.5).
- **Enmienda 2 (§2, tabla de las líneas 38-47):**
  - barrera financiera, que pasa a ser `APPLY_FUNDS` por intención;
  - confirmación manual, que deja de ser la génesis y exige `ADMINISTRATOR`;
  - estados: añade `FUNDING_REJECTED`;
  - flujo del webhook en dos actos;
  - registro de comandos procesados con espacio de claves para los comandos de sistema;
  - recuperación (ADR-043).

### 2.4 Documentos que llaman ADR-037 a otra cosa o usan números contradictorios

| Documento | Referencia | Clasificación |
|---|---|---|
| `estado-fase5.md:33`; `plan-correccion-fase5-e-ia.md:31` | "ADR-037 (APIs/Frontend, Fase 6) está marcado como número tentativo" | **Histórica.** Hoy APIs/Frontend es ADR-041 |
| `ADR-041:3,4,108` | "ADR-037/034/036", "ADR-037/034/035/036" | **Contradictoria.** 034, 035 y 036 son aquí los números antiguos de Identidad, Blockchain e IA, pero en el catálogo vigente son ADR de Fase 5 |
| `ADR-040:4` | "posterior a ADR-037/034/035" | Mismo caso |
| `ADR-037-enmienda-1:6`; `convocatoria-resumen.md:99` | La renumeración se habría aprobado el 2026-09-28 según una tabla de `ADR-042-frontend-web-paxfide-web.md` | **Referencia a un documento inexistente** (no está en ninguna rama). 042 es el ADR de reintentos de proyección de Fase 5 |
| `ADR-037:1`, `ADR-038:1`, `ADR-040:1`, `ADR-041:1` | "número tentativo — confirmar contra el catálogo real" | Contradicen la renumeración "aprobada" |

### 2.5 ADR-042, ADR-043 y otros números

- **ADR-042** no pertenece a Convocatoria: es la orquestación de reintentos de proyección de `core` (A7.2, Fase 5). Las únicas menciones que lo atribuyen al frontend son las referencias rotas a `ADR-042-frontend-web-paxfide-web.md`.
- **ADR-043** sí pertenece a Convocatoria (recuperación de la aplicación de fondos), en estado Propuesto.
- **El código de `convocatoria` solo cita** ADR-037 (87 veces), ADR-038 (5), ADR-041 (2) y ADR-043 (4) [A]. No cita ningún número del rango en colisión.
- **Referencias vigentes:** ADR-037 con sus Enmiendas 1 y 2, y ADR-043.
- **Referencias históricas:** "ADR-033" como Convocatoria y "ADR-037" como APIs/Frontend.

---

## PARTE 3 — API contractual de Convocatoria

**Hechos de código que valen para todos los endpoints [A]:**
- No existe ningún controlador HTTP de Convocatoria. `api` solo tiene `PublicDonationController`, `PublicNarrativeController` y `PublicAssetHistoryController`.
- `api` depende de `core` y `contracts`; `app` depende de `core`, `crypto`, `ai`, `api` e `identity`. **Ninguno depende de `convocatoria`.**
- `contracts` no contiene ningún `ConvocatoriaReadPort`, `ConvocatoriaReadModel` ni `ConvocatoriaAdminReadModel`.
- `convocatoria` no tiene servicios de lectura. Solo hay puertos de repositorio internos y `DonationIntentService.resolvePublicCode` → `CampaignReference(campaignRef, organizationRef)`.
- **Ningún endpoint de Convocatoria está implementado en HTTP.** La columna "Implementación" se refiere al caso de uso de aplicación.
- **Prefijo de ruta:** el código existente usa `/api/v1/...`; la matriz escribe las rutas sin prefijo. Las fuentes revisadas no fijan el prefijo de los endpoints de Fase 6.
- **Autenticación:** el `actorAccountId` de los comandos debe salir del JWT (`sub`). JWT no está implementado (ADR-038, otro bloque).
- **Errores HTTP:** el mapeo de ADR-041 §2.5 (404/409) es una "propuesta … no confirmada por ninguna fuente". Las excepciones que cita existen en el código: `CampaignClosedException`, `CampaignFundingLimitExceededException`, `LastResponsibleRemovalWithoutReplacementException`, `EmployeeAlreadyAssignedException`.

### A. API propia de Convocatoria

| # | Método y ruta | Actor / auth / authz | Request (código) | Response | Errores relevantes (código) | Idempotencia | Caso de uso | Estado documental | Implementación | Fuente | Discrepancias |
|---|---|---|---|---|---|---|---|---|---|---|---|
| A1 | `POST /organizations/{id}/campaigns` | Administrador de la organización; JWT; `requireAdministratorOf` | `CreateConvocatoriaCommand(commandId, actorAccountId, organizationRef, title, description, visibility, startDate, endDate, configuration{acceptedDonationTypes, acceptedPaymentMethods, currency, targetAmount, targetPolicy, onTargetReached})` | Código: `{campaignRef, publicCode}`. Matriz: `{campaignRef, publicCode, status, ...}` | `OrganizationNotVerifiedException`, `ActorRoleNotAllowedException`, `ActorNotInCampaignOrganizationException`, `CampaignTitleRequired/TooLong`, `InvalidCampaignDateRange`, `IncompleteMonetaryConfiguration`, `EmptyAcceptedDonationTypes`, `InvalidTargetAmount`, `CommandIdReusedForDifferentCommand` | Sí, por `commandId` del cliente | `ConvocatoriaLifecycleService.createConvocatoria` | DISEÑO CERRADO (matriz §2) | IMPLEMENTADO (caso de uso); HTTP no | matriz §2; ADR-037 §2.1; Enmienda 1 §3 y §3.5 | Respuesta distinta. Body HTTP NO DEFINIDO EN LAS FUENTES REVISADAS. La matriz no exige `commandId` (Enmienda 1 §7.3 sí). La matriz dice "sin código" |
| A2 | `POST /campaigns/{campaignRef}/employees` | Administrador; JWT | `AssignEmployeeToCampaignCommand(commandId, actorAccountId, campaignRef, employeeRef)` | Código: `{assignmentId}`. Matriz: `{campaignRef, accountId, status}` | `EmployeeAlreadyAssignedException`, `ResponsibleAlreadyActiveInCampaignException`, `EmployeeSelfAssignmentNotAllowedException`, `InvalidResponsibleRecipientException`, `CampaignClosedException` | Sí (`commandId`) | `ResponsibleAssignmentService.assignEmployee` | DISEÑO CERRADO, **reabierto por P4** (Enmienda 1 §7.3) | IMPLEMENTADO (caso de uso) | matriz §2; Enmienda 1 §4.1 y §8 P4 | La respuesta no coincide. P4: rutas, cuerpos y respuestas pendientes |
| A3 | *(sin ruta)* designar administrador como responsable | Administrador; JWT | `DesignateAdministratorAsCampaignResponsibleCommand(commandId, actorAccountId, campaignRef, administratorRef)` | Código: `{assignmentId}` | `ResponsibleAlreadyActiveInCampaignException`, `InvalidResponsibleRecipientException` | Sí | `ResponsibleAssignmentService.designateAdministrator` | **PENDIENTE (P4)** | IMPLEMENTADO (caso de uso) | Enmienda 1 §4.1 | Ausente de la matriz |
| A4 | *(sin ruta)* retirar responsable (con o sin reemplazo) | Administrador; JWT | `RemoveResponsibleCommand(commandId, actorAccountId, campaignRef, responsibleRef, replacementRef, replacementActingRole)` | Código: `{removedAssignmentId, replacementAssignmentId}` | `LastResponsibleRemovalWithoutReplacementException`, `ReplacementActingRoleRequiredException`, `AssignmentAlreadyRemovedException`, `ResponsibleAssignmentNotFoundException` | Sí | `ResponsibleAssignmentService.removeResponsible` | **PENDIENTE (P4)** | IMPLEMENTADO (caso de uso) | ADR-037 §2.5; Enmienda 1 §4.2 | Ausente de la matriz |
| A5 | *(sin ruta)* editar configuración | Administrador; JWT | `EditConfigurationCommand(commandId, actorAccountId, campaignRef, expectedConfigurationVersion, newConfiguration)` | Código: `{campaignRef, configurationVersion}` | `ConfigurationVersionConflictException`, `ConfigurationChangeOnClosedCampaignException`, `MonetaryTermsChangeNotSupportedException`, `CampaignAlreadyHasDonationsException` | Sí; además, concurrencia optimista por versión | `ConvocatoriaLifecycleService.editConfiguration` | **PENDIENTE** (sin contrato HTTP) | IMPLEMENTADO (caso de uso) | Enmienda 1 §3.2 | Ausente de la matriz |
| A6 | *(sin ruta)* cerrar convocatoria | Administrador; JWT | `CloseConvocatoriaCommand(commandId, actorAccountId, campaignRef)` | Código: `{campaignRef}` | `CampaignAlreadyClosedException` | Sí | `ConvocatoriaLifecycleService.closeConvocatoria` | **PENDIENTE** (sin contrato HTTP) | IMPLEMENTADO (caso de uso) | Enmienda 1 §3.4 | Ausente de la matriz |
| A7 | `GET /public/campaigns/{publicCode}` | Público; sin auth | — | `ConvocatoriaReadModel`: **campos NO DEFINIDOS EN LAS FUENTES REVISADAS** | NO DEFINIDO | n/a | `ConvocatoriaReadPort.findPublicByCode` (no existe) | DISEÑO CERRADO (matriz) | **NO IMPLEMENTADO** (no hay puerto ni read model) | matriz §2 | La matriz remite a la "matriz ADR-021-D ya cerrada". ADR-021-D es el perímetro del tracking code de Fase 3 (`estado-fase3.md:19`) y no define campos de campaña |
| A8 | `GET /public/campaigns` | Público | `cursor, limit` | Lista paginada, NO DEFINIDA | — | n/a | `listPublicOpen` (no existe) | **PENDIENTE** | NO IMPLEMENTADO | matriz §2 | — |
| A9 | `GET /organizations/{organizationId}/campaigns` | Administrador; JWT; `OrganizationBoundaryPolicy` | Paginado (forma NO DEFINIDA) | `ConvocatoriaAdminReadModel{campaignRef, publicCode, title, status, visibility, targetAmount, targetPolicy, clearedAmount, responsables{accountId, fullName}, assignedEmployeeCount}` | NO DEFINIDO | n/a | Lectura compuesta (no existe) | CONTRATO DEFINIDO PERO NO IMPLEMENTADO | NO IMPLEMENTADO | matriz §2b | `fullName` viene de Identidad, así que la lectura es multimódulo y pasa por `app` (ADR-041 §2.8) [E]. La lista de campos no incluye `onTargetReached`, `currency` ni `acceptedPaymentMethods` |
| A10 | `POST /public/campaigns/{publicCode}/donation-intents` | Donante, anónimo o con JWT opcional | `CreateDonationIntentCommand(commandId, publicCode, donorRef, amount, currency, paymentMethod)` | Código: `{intentId, fundId}`. Matriz: `{paymentRedirectUrl / paymentSessionId}` | `CampaignClosedException`, `OrganizationNotVerifiedException`, `DonationTypeNotAcceptedException`, `PaymentMethodNotAcceptedException`, `DonationCurrencyMismatchException`, `InvalidDonationAmountException`, `CashDonationIntentNotSupportedException` (P5) | Sí (`commandId`). ADR-041 §7-A.4 lo da por pendiente en HTTP | `DonationIntentService.createDonationIntent` | **PENDIENTE** (matriz §3) | IMPLEMENTADO (caso de uso) para `GATEWAY`/`BANK_TRANSFER`; `CASH` se rechaza | ADR-037 §2.6 paso 1; Enmienda 1 §5.1 | **La matriz dice "ninguna [operación de dominio] — solo prepara redirección", pero ADR-037 §2.6 paso 1 y el código crean la `DonationIntent`.** `paymentSessionId` queda nulo hasta P3 [A: `DonationIntent.java:12-13`] |
| A11 | *(sin ruta)* confirmación manual (`BANK_TRANSFER`) | Administrador de la organización (D5); JWT | `ConfirmDonationIntentCommand(intentId, actorAccountId, reference)` | Código: `boolean` | `GatewayIntentManualConfirmationNotAllowedException`, `DonationIntentExpiredException`, `DonationIntentNotFoundException`, `IncompleteConfirmationException`, `ActorRoleNotAllowedException` | Transición condicional `PENDING → CONFIRMED`; sin `commandId` (Enmienda 2 §3.1) | `DonationIntentService.confirmDonationIntent` | **PENDIENTE** (sin contrato HTTP). `implementation_plan.md` §4.2 y §9.3 dicen que no es desplegable sin el contrato del índice de referencia de pago | IMPLEMENTADO (caso de uso) | Enmienda 1 §5.2; Enmienda 2 §3.1 (borrador) | Ausente de la matriz |
| A12 | *(sin ruta)* consulta del estado de una intención | Donante (credencial NO DEFINIDA) | — | Estados `PENDING/CONFIRMED/FAILED/EXPIRED_UNKNOWN/FUNDING_REJECTED` | — | n/a | No existe servicio de lectura | **PENDIENTE** (matriz, línea 104) | NO IMPLEMENTADO | matriz "Frontend Contract"; ADR-041 §2.4 | Los documentos escriben `EXPIRED-UNKNOWN`; el código, `EXPIRED_UNKNOWN`. Ningún código produce `FAILED` ni `EXPIRED_UNKNOWN` [A] |

### B. APIs externas que consume Convocatoria (todas sin HTTP)

| Elemento | Origen | Uso | Estado |
|---|---|---|---|
| `IdentityPrincipalPort.resolvePrincipal` | `contracts` (Fase 5) | Autorización de A1–A6 y A11 | IMPLEMENTADO (Identity) |
| `OrganizationVerificationPort.isVerified` | Puerto propio de `convocatoria` | A1, A10 | **Sin adaptador de producción** (ADR-038, otro bloque) |
| `POST /platform/organizations/{id}/verify` | Identidad | Precondición de A1 y A10 | DISEÑO CERRADO, no implementado (otro bloque) |
| `POST /auth/login` (JWT) | Identidad | Origen de `actorAccountId` | CONTRATO CERRADO, no implementado (otro bloque) |

### C. API de integración y orquestación

| Elemento | Detalle | Estado |
|---|---|---|
| `POST /webhooks/payments` | Firma del proveedor, sin JWT. Confirma la intención y, como acto posterior del sistema, aplica los fondos en una sola transacción: `APPLY_FUNDS` + ledger + `clearFundsGenesis` + outbox (matriz §3; Enmienda 2 §3; ADR-043). DTO NO DEFINIDO (depende del proveedor, P3) | CONTRATO CONCEPTUAL. La pieza de Convocatoria (`applyFundsForIntent`, `rejectFundingIfPermanentlyUnfundable`) está IMPLEMENTADA; el orquestador de `app` NO |
| Disparo inmediato y scheduler de respaldo (ADR-043) | Sin HTTP. Usa `findConfirmedPendingApplication(limit)` | Pieza de Convocatoria IMPLEMENTADA; `app` NO (bloqueado por GAP-1, T1 y P8) |
| Canal de entrega del `trackingCode` | La matriz lo da por PENDIENTE | PENDIENTE (otro bloque) |

### D. APIs fuera del perímetro que tocan datos de Convocatoria

| Endpoint | Relación | Estado |
|---|---|---|
| `GET /api/v1/donations/tracking` (credencial Bearer HMAC) | Expone `campaignRef` (`PublicDonationTrackingDTO`) desde la proyección de `core`. La intención aporta `fundId`, del que deriva el tracking | IMPLEMENTADO. La matriz §5 lo escribe como `GET /tracking/{trackingCode}`, que no coincide con el código |
| `GET /public/campaigns/{publicCode}/narrative` | IA (ADR-040) | CONTRATO DEFINIDO; bloqueado por C1 de ADR-040 |
| `POST /physical-assets/*`, `GET /physical-assets/{assetRef}` | Llevan `campaignRef` (ADR-029; Enmienda 1 §7.2) | Otro bloque |
| `GET /account/donations`, `POST /auth/register` | Identidad / Tracking | Otro bloque |

---

## API CONTRACT HANDOFF — CONVOCATORIA

Solo lo que necesita la persona responsable de API/Frontend. Los nombres de campo salen del código (comandos y resultados). Lo que no está en las fuentes se indica como NO DEFINIDO.

**Reglas comunes (ADR-041 y la matriz, reglas 1-8):**
- los controladores no llevan lógica;
- las escrituras delegan en los servicios de `convocatoria`;
- toda escritura de cliente lleva `commandId`, que la hace idempotente y devuelve el resultado original;
- JWT mínimo; el `AuthorizationPrincipal` se resuelve en cada petición;
- las operaciones que coordinan varios módulos pasan por `app`;
- el mapeo de errores 404/409 es solo una propuesta;
- la deduplicación HTTP propia está prohibida.

**Bloqueos estructurales previos:**
- ni `api` ni `app` dependen de `convocatoria`;
- no existe adaptador de producción de `OrganizationVerificationPort`;
- JWT no está implementado.

### 1. Convocatoria propia (escrituras)

| Operación | Ruta | Estado del contrato | Request | Response |
|---|---|---|---|---|
| Crear convocatoria | `POST /organizations/{id}/campaigns` | CONTRATO CONCEPTUAL (ruta y auth cerradas, body no) | Campos de `CreateConvocatoriaCommand` | Código: `{campaignRef, publicCode}`. Hay que reconciliarlo con la matriz |
| Asignar empleado | `POST /campaigns/{campaignRef}/employees` | PENDIENTE (P4) | `commandId, employeeRef` | `{assignmentId}` |
| Designar administrador | NO DEFINIDA | PENDIENTE (P4) | `commandId, administratorRef` | `{assignmentId}` |
| Retirar responsable | NO DEFINIDA | PENDIENTE | `commandId, responsibleRef, replacementRef?, replacementActingRole?` | `{removedAssignmentId, replacementAssignmentId}` |
| Editar configuración | NO DEFINIDA | PENDIENTE | `commandId, expectedConfigurationVersion, newConfiguration` | `{campaignRef, configurationVersion}` |
| Cerrar convocatoria | NO DEFINIDA | PENDIENTE | `commandId` | `{campaignRef}` |

### 2. Lecturas públicas

| Operación | Ruta | Estado |
|---|---|---|
| Detalle público | `GET /public/campaigns/{publicCode}` | Ruta cerrada; **campos NO DEFINIDOS**; read port inexistente |
| Descubrimiento | `GET /public/campaigns` | PENDIENTE |

Regla ya cerrada (`convocatoria-resumen.md:19`): una campaña `PRIVATE_LINK` se accede por `publicCode` pero nunca aparece en el descubrimiento.

### 3. Lecturas administrativas

| Operación | Ruta | Estado |
|---|---|---|
| Listado de la organización | `GET /organizations/{organizationId}/campaigns` | CONTRATO DEFINIDO PERO NO IMPLEMENTADO (campos en la matriz §2b; paginación NO DEFINIDA) |
| Detalle administrativo | — | La matriz §2b no lo añade deliberadamente |

### 4. Integraciones con donación y pagos

| Operación | Ruta | Estado |
|---|---|---|
| Crear intención | `POST /public/campaigns/{publicCode}/donation-intents` | PENDIENTE en HTTP. El caso de uso devuelve `{intentId, fundId}`. La respuesta de redirección depende del proveedor (P3) |
| Confirmación manual (transferencia) | NO DEFINIDA | PENDIENTE (Enmienda 2 en borrador; contrato del índice de referencia pendiente) |
| Estado de la intención | NO DEFINIDA | PENDIENTE. Hay cinco estados de dominio y `CONFIRMED` no significa fondos aplicados |
| Webhook de pago | `POST /webhooks/payments` | CONTRATO CONCEPTUAL (P3). Es orquestación de `app` |

### 5. APIs que no son de Convocatoria pero dependen de ella

- `GET /api/v1/donations/tracking`: expone `campaignRef`.
- `GET /public/campaigns/{publicCode}/narrative`: IA, ADR-040.
- Endpoints de `PhysicalAsset` que llevan `campaignRef`.
- `POST /platform/organizations/{id}/verify`: precondición de crear convocatorias e intenciones.

---

## PARTE 5 — MongoDB real de Convocatoria

**Mecanismo de índices [A]:**
- Los índices se declaran con `@Indexed` y `@CompoundIndex` en las clases de documento.
- En producción se crearían por `auto-index-creation: true` (`app/src/main/resources/application.yml:15`). Hoy no se crean, porque `app` no incluye `convocatoria`.
- En los tests, `ConvocatoriaTestIndexes` los resuelve y crea explícitamente para las siete clases.
- No existe ningún `MongoRepository`: todo pasa por `MongoTemplate`.

**Transacciones [A]:**
- Cada comando de cliente se ejecuta dentro de `IdempotentCommandExecutor.execute`, que usa `ConvocatoriaTransactionRetryHelper.executeWithRetry`: `TransactionTemplate` con 6 intentos y backoff exponencial.
- `confirmDonationIntent`, `applyFundsForIntent` y `rejectFundingIfPermanentlyUnfundable` llevan `@Transactional(SUPPORTS)`. Si hay una transacción externa se unen a ella sin reintento interno; si no la hay, usan el helper.

| Colección (real) | Clase | Campos (tipo) | `_id` | Índices | Estado / tiempo / auditoría |
|---|---|---|---|---|---|
| `convocatorias` | `ConvocatoriaDocument` | `organizationRef` S, `publicCode` S, `title` S, `description` S, `visibility` S, `startDate` I, `endDate` I, `status` S, `acceptedDonationTypes` Set<S>, `acceptedPaymentMethods` Set<S>, `currency` S, `targetAmount` Long, `targetPolicy` S, `onTargetReached` S, `configurationVersion` long | `campaignRef` | `uq_public_code` (`publicCode`, único) | `status` (`OPEN/CLOSED`); `configurationVersion` para concurrencia optimista; sin timestamps de creación |
| `campaign_assignments` | `CampaignAssignmentDocument` | `campaignRef` S, `employeeRef` S, `actingRole` S, `status` S, `assignedAt` I, `removedAt` I, `assignedBy` S, `selfAssigned` bool | `assignmentId` | `uq_active_employee_assignment`: `{employeeRef:1}`, único y parcial `{status:'ACTIVE', actingRole:'EMPLOYEE'}` | `status` (`ACTIVE/REMOVED`); `assignedAt`, `removedAt`, `assignedBy` |
| `campaign_responsible_state` | `CampaignResponsibleStateDocument` | `activeResponsibleCount` long | `campaignRef` | — | Contador (`upsert $inc`; decremento condicional `$gt:1`) |
| `campaign_funding_ledgers` | `CampaignFundingLedgerDocument` | `currency` S, `targetAmount` long, `targetPolicy` S, `onTargetReached` S, `clearedAmount` long | `campaignRef` | — | `clearedAmount` (`$inc`, incondicional o condicional `lte target-amount`). Se borra si se quita `MONETARY` (G1) |
| `donation_intents` | `DonationIntentDocument` | `fundId` S, `organizationRef` S, `campaignRef` S, `donorRef` S, `amount` long, `currency` S, `paymentMethod` S, `confirmationSource` S, `configurationVersion` long, `paymentSessionId` S, `providerEventId` S, `expiresAt` I, `status` S, `confirmedBy` S, `confirmedAt` I, `confirmationPaymentMethod` S, `confirmationReference` S | `intentId` | `uq_fund_id` (`fundId`, único); `uq_payment_session_id` (único, parcial `{paymentSessionId: {$type:'string'}}`) | `status` (5 valores); `confirmedBy` y `confirmedAt`; `expiresAt` solo para `BANK_TRANSFER` |
| `convocatoria_processed_commands` | `ProcessedCommandDocument` (cliente) y `Document` crudo (sistema) | `commandType` S, `result` Map<S,S>, `processedAt` I | Cliente: `commandId` (string). Sistema: `{commandType, commandId}` (subdocumento) | Solo `_id` | Reclamo con `findAndModify` + `upsert` + `setOnInsert`; `processedAt` |
| `convocatoria_audit_log` | `ConvocatoriaAuditLogDocument` | `action` S, `campaignRef` S, `actorRef` S, `targetRef` S, `selfAssigned` bool, `commandId` S, `occurredAt` I, `sequence` long, `details` Map<S,S> | `entryId` | — | Append-only (solo `insert`). `sequence` sale de un `AtomicLong` en memoria del proceso, sembrado con `currentTimeMillis*1000` |

S = String, I = Instant.

**Claves de idempotencia y unicidad:**
- Comandos de cliente: `_id = commandId`. Un `commandId` repetido con otro tipo produce `CommandIdReusedForDifferentCommandException`.
- `APPLY_FUNDS`: `_id = {commandType: "APPLY_FUNDS", commandId: intentId}`. No colisiona con un `_id` string (test `clientCommandIdEqualToTheIntentIdDoesNotCollideWithTheApplication`).
- `fundId` único: es el `fundId` de la génesis futura y la base del tracking.
- `paymentSessionId` único parcial (P3).

**Consultas relevantes:**
- `findByPublicCode`.
- `existsByCampaignRef` sobre `donation_intents`, sin índice en `campaignRef`.
- `findActiveByCampaignRefAndResponsible`.
- `findByCampaignRef` (asignaciones, ordenadas por `assignedAt`; audit log, ordenado por `sequence`).
- Agregación `findConfirmedPendingApplication`: `match status=CONFIRMED` → `sort _id` → `$lookup` en `convocatoria_processed_commands` por la clave de sistema → descarta las aplicadas → `$lookup` en `campaign_funding_ledgers` → excluye `CLOSE_ON_TARGET+CLOSE` → `limit`.

**Relaciones lógicas, sin claves foráneas físicas:**
- `campaignRef` une las cinco colecciones de dominio y el audit log.
- `organizationRef` apunta a una `Organization` de Identidad.
- `employeeRef`, `assignedBy`, `confirmedBy` y `actorRef` apuntan a cuentas de Identidad.
- `donorRef` es opaco.
- `fundId` apunta al futuro `Fund` de `core`.
- `intentId` es el `commandId` de `APPLY_FUNDS`.

**Coincidencia con el plan:** los nombres "provisionales" de `implementation_plan.md` §4.2 coinciden con el código en las cinco colecciones de dominio. El plan no nombra el registro de comandos ni el audit log; sus nombres reales son `convocatoria_processed_commands` y `convocatoria_audit_log`. El plan pide "`campaignRef` único" en `convocatorias`, `campaign_responsible_state` y `campaign_funding_ledgers`; el código lo cumple con el `_id`.

---

## BD HANDOFF — CONVOCATORIA

| Colección | Documento | Propósito | Clave principal | Índices | Unicidad | Quién escribe | Quién lee |
|---|---|---|---|---|---|---|---|
| `convocatorias` | `ConvocatoriaDocument` | Campaña y su configuración versionada | `_id = campaignRef` | `uq_public_code` | `campaignRef`, `publicCode` | `ConvocatoriaLifecycleService` (crear, editar, cerrar) | Servicios de `convocatoria`; futuras lecturas pública y administrativa |
| `campaign_assignments` | `CampaignAssignmentDocument` | Responsables (`EMPLOYEE` o `ADMINISTRATOR`) | `_id = assignmentId` | `uq_active_employee_assignment` (parcial) | Un `EMPLOYEE` activo en una sola campaña | `ResponsibleAssignmentService` | `ResponsibleAssignmentService`, autorización contextual |
| `campaign_responsible_state` | `CampaignResponsibleStateDocument` | Contador de "≥1 responsable" | `_id = campaignRef` | — | 1 por campaña | `ResponsibleAssignmentService` | Ídem |
| `campaign_funding_ledgers` | `CampaignFundingLedgerDocument` | Monto aplicado contra la meta | `_id = campaignRef` | — | 1 por campaña `MONETARY` | `ConvocatoriaLifecycleService` (alta y baja); `CampaignFundingLedgerService` (`$inc`) | `CampaignFundingLedgerService`; consulta recuperable; futura lectura administrativa (`clearedAmount`) |
| `donation_intents` | `DonationIntentDocument` | Intención de donación monetaria | `_id = intentId` | `uq_fund_id`, `uq_payment_session_id` (parcial) | `intentId`, `fundId`, `paymentSessionId` | `DonationIntentService` (crear, confirmar); `CampaignFundingLedgerService` (`FUNDING_REJECTED`) | Servicios de `convocatoria`; futuro orquestador y scheduler de `app` |
| `convocatoria_processed_commands` | `ProcessedCommandDocument` / `Document` | Idempotencia de comandos de cliente y barrera `APPLY_FUNDS` | `_id` string o `{commandType, commandId}` | Solo `_id` | Por clave | `IdempotentCommandExecutor`; `CampaignFundingLedgerService` | Ídem; consulta recuperable |
| `convocatoria_audit_log` | `ConvocatoriaAuditLogDocument` | Auditoría append-only | `_id = entryId` | — | `entryId` | Todos los servicios de escritura | `findByCampaignRef`; futuro `ConvocatoriaAuditFacts` (ADR-040) |

- **Datos propios:** las siete colecciones.
- **Datos de otros módulos que se guardan como referencias opacas:** `organizationRef` y cuentas (Identidad), `donorRef`, `fundId` (identificador de un `Fund` de `core` todavía no creado).
- **Lo que no debe modificar el compañero:**
  - ninguna colección directamente: `api` nunca recibe documentos crudos (ADR-041 §2.1);
  - los nombres de colección y de índice, porque el código los referencia (`ACTIVE_EMPLOYEE_INDEX` se usa para traducir `DuplicateKeyException`);
  - la forma del `_id` de los comandos de sistema;
  - `clearedAmount` y `status`, que solo se cambian por las operaciones condicionales del módulo.
- **Índices esenciales para invariantes:**
  - `uq_active_employee_assignment`: un `EMPLOYEE` en una sola campaña activa;
  - `uq_public_code`;
  - `uq_fund_id`;
  - `uq_payment_session_id`;
  - el `_id` del registro de comandos (idempotencia y barrera `APPLY_FUNDS`).
- **Índices de rendimiento que no existen:**
  - `donation_intents.status` y `donation_intents.campaignRef`: la consulta recuperable y `existsByCampaignRef` no tienen índice de apoyo [A];
  - `convocatoria_audit_log.campaignRef`.

  Su ausencia no rompe ninguna invariante. Es un hueco de rendimiento [E].
- **Participación en transacciones Mongo:** todas las escrituras de cliente (tabla de `implementation_plan.md` §4.4) más la aplicación y el rechazo de fondos. Requieren replica set.
- **Unicidad "un responsable activo por persona y campaña":** no tiene índice. Se comprueba con lectura previa (`ResponsibleAssignmentService:173`) dentro de la transacción. Ante dos designaciones simultáneas, ambas escriben el contador `campaign_responsible_state`, y el conflicto de escritura resultante serializa una de ellas [E: inferencia del mecanismo; esta auditoría no lo probó].

---

## PARTE 7 — Contrato frente a implementación

| Elemento | Documentado | Implementado | Testeado | Fuente | Estado |
|---|---|---|---|---|---|
| `Convocatoria` (crear, editar, cerrar) | ADR-037 §2.1; Enmienda 1 §3 | Sí | Sí: `CreateConvocatoria` 10, `EditConfiguration` 12, `CloseConvocatoria` 7, `ConvocatoriaTest` 24 | A, B | IMPLEMENTADO |
| `CampaignAssignment` (asignar, designar, retirar) | ADR-037 §2.4-2.5; Enmienda 1 §4 | Sí | Sí: 10 / 8 / 12, `CampaignAssignmentTest` 5 | A, B | IMPLEMENTADO |
| `DonationIntent` (crear) | ADR-037 §2.6; Enmienda 1 §5.1 | Sí; `CASH` rechazado (P5) | Sí: `CreateDonationIntent` 17, `DonationIntentTest` 10 | A, B | IMPLEMENTADO |
| `CampaignFundingLedger` | ADR-037 §2.2-2.3; Enmienda 1 §3.3 y §6 | Sí | Sí: `CampaignFundingLedgerTest` 4 + integración 31 | A, B | IMPLEMENTADO |
| Estados y transiciones de `DonationIntent` | ADR-037 §2.6 + Enmienda 2 §4 | `PENDING→CONFIRMED` y `CONFIRMED→FUNDING_REJECTED`. `FAILED` y `EXPIRED_UNKNOWN` existen en el enum sin ninguna transición | Parcial | A | PARCIAL: `FAILED` y `EXPIRED_UNKNOWN` dependen de P3 y de la expiración, fuera del corte |
| Confirmación manual | Enmienda 2 §3.1 (borrador) | Sí (administrador; rechaza pasarela) | Sí: `ConfirmDonationIntent` 10 | A, B | IMPLEMENTADO; normativa pendiente de aprobación |
| Rechazo (`FUNDING_REJECTED`) | Enmienda 2 §4 | Sí | Sí | A, B | IMPLEMENTADO; normativa pendiente |
| `APPLY_FUNDS` | Enmienda 2 §3.3; `convocatoria-resumen.md` §6.20 D1 | Sí | Sí (duplicado, concurrencia, colisión de claves) | A, B | IMPLEMENTADO; normativa pendiente |
| Reintento | `implementation_plan.md` §4.4 ("reintento acotado"); Enmienda 1 §6 | 6 intentos y backoff exponencial de 10 a 500 ms; sin reintento interno dentro de una transacción externa | Sí: `RetryHelper` 5 | A, B | IMPLEMENTADO. **No documentado en ningún canónico** (cifras y política) |
| Concurrencia | ADR-037 §2.5; Enmienda 1 §4.2; plan §13 | Sí | Sí (`IdempotentCommandExecutor` 5, carreras en el ledger) | A, B | IMPLEMENTADO |
| Consulta recuperable | ADR-043 (propuesto) | Sí (`findConfirmedPendingApplication`) | Sí | A, B | IMPLEMENTADO; el scheduler de `app` NO |
| Cierre | Enmienda 1 §3.4 | Sí | Sí | A, B | IMPLEMENTADO. Cierre automático `CLOSE_ON_TARGET+CLOSE` = R4 pendiente (`CloseOnTargetCloseNotSupportedException`) |
| APIs HTTP | matriz; ADR-041 | **No** | No | A | NO IMPLEMENTADO; contrato incompleto (Parte 3) |
| Persistencia | plan §4.2 | 7 colecciones, 4 índices declarados | Sí: `ConvocatoriaPersistenceIndexesIntegrationTest` 7, `MongoTransactionSmokeTest` 2 | A, B | IMPLEMENTADO; índices de producción sin crear mientras `app` no incluya el módulo |
| Frontera del módulo | plan §12.1 | ArchUnit prohíbe `core`, `identity` y `app` | Sí: `ConvocatoriaArchitectureTest` 2 | A, B | IMPLEMENTADO |
| Escenario de negocio | golden-path | Sí, dentro del módulo | `ConvocatoriaBusinessScenarioIntegrationTest` 5 | B | IMPLEMENTADO (sin génesis ni HTTP) |

---

## PARTE 8 — Documentación obsoleta

| Documento | Texto o tema obsoleto | Evidencia actual | Acción futura sugerida |
|---|---|---|---|
| `api-contract-matrix.md:31` | "módulo `convocatoria` sin código todavía" | Hay 20 clases de test y 194 tests [B] | Actualizar el estado de la fila |
| `api-contract-matrix.md` §2 y §3 | Respuestas de A1 y A2; "ninguna [operación de dominio]" en donation-intents; faltan A3–A6 y A11 | Comandos y resultados del código (Parte 3) | Reconciliar al cerrar P4 y el contrato HTTP |
| `api-contract-matrix.md:33` | "matriz ADR-021-D ya cerrada" como definición del read model público | ADR-021-D es el perímetro del tracking code (`estado-fase3.md:19`) | Definir los campos de `ConvocatoriaReadModel` o corregir la referencia |
| `api-contract-matrix.md` §5 | `GET /tracking/{trackingCode}` | `GET /api/v1/donations/tracking` con credencial Bearer [A] | Alinear las rutas (fuera del perímetro) |
| `estado-fase6.md:10` | "solo Blockchain tiene implementación real" | `estado-fase6.md:16` y §3bis | Coherencia interna |
| `estado-fase6.md:79` | "Tests run: 190" | 194 [B] | Actualizar desde un resultado de ejecución (regla 2.4) |
| `estado-fase6.md` §3bis | Sin el cambio de reintento (6 intentos, backoff exponencial) | `ConvocatoriaTransactionRetryHelper` [A] | Registrarlo |
| `estado-fase6.md:100`; `ADR-041` §2.3 y §7-B | "Idempotencia de `clearFundsGenesis` no verificada" como bloqueo del webhook | Vigente en parte: la aplicación ya tiene barrera `APPLY_FUNDS`; siguen T1, P8 y el espacio de claves de la génesis (Enmienda 2 §6) | Reformular según la Enmienda 2 cuando se apruebe |
| `ADR-037-enmienda-1:6`; `convocatoria-resumen.md:99` | Tabla de renumeración en `ADR-042-frontend-web-paxfide-web.md` | El archivo no existe en ninguna rama | Registrar la decisión de numeración en un documento existente (H-1 de la auditoría de herencia) |
| `ADR-037-enmienda-1:7`; `convocatoria-resumen.md:99`; `implementation_plan.md:5` | El archivo "aún se llama `ADR-033-convocatoria-…`" | El archivo es `ADR-037-…` (`b417d3a`) | Retirar la nota |
| `implementation_plan.md:5`; Enmienda 1 §7.3 y §9.3 | `claude/ADR-037-enmienda-1-…`, `claude/front-fase2.md` | Esas rutas no existen | Corregir las rutas |
| `ADR-037:1`, `ADR-038:1`, `ADR-040:1`, `ADR-041:1` | "número tentativo" | Catálogo físico sin duplicados | Decidir y retirar la marca (decisión humana) |
| `ADR-040:4`; `ADR-041:3,4,108` | "ADR-037/034/035/036" con la numeración antigua | Hoy 034–036 son ADR de Fase 5 | Sustituir por 038/039/040 si se ratifica la renumeración |
| `ADR-037:118`; `ADR-041:45,75`; `api-contract-matrix.md:104`; `contract-wiring-review.md:56`; `implementation_plan.md:130`; Enmienda 2:42 | `EXPIRED-UNKNOWN` | El enum es `EXPIRED_UNKNOWN` [A] | Unificar la grafía del contrato |
| `estado-fase5.md:33`; `plan-correccion-fase5-e-ia.md:28-32` | "Colisiones 033–037 PENDIENTES"; "ADR-037 = APIs/Frontend" | El catálogo físico ya está separado | Cerrar con referencia a la decisión registrada |
| `fase-6-estructura-y-perimetro-convocatoria.md` (cabecera) | "previo a `plan-ejecucion-agentes-fase6.md`" | El archivo no existe | Marcar como histórico |
| `convocatoria-resumen.md` (cabecera) | "Diseño pre-ADR … no es una decisión final" | §6.7–§6.20 contienen decisiones humanas aprobadas | Aclarar su estado normativo |
| Auditorías del 2026-10-02 | 180 tests; cláusulas ya cerradas | 194 [B]; Enmienda 2 | Marcarlas como históricas |

---

## PARTE 9 — Duplicados

| Hallazgo | Documentos | Tipo |
|---|---|---|
| Misma auditoría en tres versiones | `auditoria-api-convocatoria.md`, `-v2.md`, `-v3.md` | Duplicado sucesivo; la v3 es la última |
| Cadena de cierre de C-01 con contenido solapado | `auditoria-c01-idempotencia-fondos.md`, `auditoria-c01-postcorreccion.md`, `auditoria-cierre-f1-f2.md`, `auditoria-flujo-confirmed-fondos-v3.md`, `debate-cierre-confirmed-fondos.md`, `auditoria-preimplementacion-…`, `auditoria-delimitacion-…`, `auditoria-cierre-final-…` | Snapshots que deberían tratarse como históricos. La síntesis normativa ya está en `convocatoria-resumen.md` §6.20 y la Enmienda 2 |
| Decisiones de C-01 registradas tres veces | `convocatoria-resumen.md` §6.17, §6.18 y §6.19, con §6.20 como vigente | Duplicación interna. Las anteriores están marcadas como sustituidas o históricas (verificado en la sesión previa) |
| Revisiones de preparación de la API | `plan-api-fase6.md`, `revision-ready-api-fase6.md`, `auditoria-api-convocatoria-v3.md` | Tres documentos sobre el mismo inventario E1–E25 |
| Mezcla de normativa y auditoría | `convocatoria-resumen.md` (§6.9 "Verificación de código" junto a decisiones aprobadas); `estado-fase6.md` (estado y deuda) | Mezcla de autoridad |
| Identificadores reutilizados con distinto significado | P1/P2/P3 (`contract-wiring-review.md`) frente a P1–P7 (Enmienda 1 §8); D1 (ADR-037 §2.6bis) frente a D1–D7 (§6.20); R4 (Enmienda 1) frente a R4 (`hallazgos-front-fase2.md`, citado en Enmienda 1 §2) | Nombres ambiguos |
| ADR y enmiendas | No hay archivos de ADR duplicados en el árbol | Sin duplicado físico |
| Nombres de archivo ambiguos | `implementation_plan.md` (no dice que es de Convocatoria); `auditoria-porcentaje-convocatoria-fase6.md` (sin cabecera) | Ambigüedad |

---

## PARTE 10 — Dictamen

**A. IMPLEMENTACIÓN DE CONVOCATORIA: sí, implementada y testeada a nivel de módulo.**
- Evidencia: 194 tests, 0 fallos, 0 errores y 0 omitidos, re-ejecutados en esta auditoría [B].
- Cubre los casos de uso del primer corte, la barrera `APPLY_FUNDS`, `FUNDING_REJECTED`, el reintento, la concurrencia, la consulta recuperable y los índices.
- Lo que falta es externo al bloque y no cuenta como fallo de Convocatoria:
  - el orquestador y el scheduler de `app`;
  - la génesis (T1, P8, espacio de claves);
  - el adaptador de producción de `OrganizationVerificationPort`;
  - el webhook (P3).
- Dos matices dentro del bloque:
  - las transiciones a `FAILED` y `EXPIRED_UNKNOWN` no existen, por diseño del corte;
  - la parte de fondos se apoya en una Enmienda 2 y un ADR-043 que aún no están aprobados.

**B. CONTRATO API DE CONVOCATORIA: no está suficientemente definido para entregarlo como contrato cerrado.** Se puede entregar como handoff parcial. Huecos reales:
1. **P4:** rutas, cuerpos y respuestas de asignar, designar y retirar responsables.
2. Sin contrato HTTP para editar configuración, cerrar convocatoria, confirmar manualmente y consultar el estado de la intención.
3. Los campos de `ConvocatoriaReadModel` (lectura pública) no están definidos, y su referencia (ADR-021-D) no los contiene. El descubrimiento público está PENDIENTE.
4. Paginación del listado administrativo, y su composición multimódulo (`fullName`).
5. Cuerpos de request de todas las escrituras y prefijo de ruta (`/api/v1` o ninguno).
6. Mapeo de errores HTTP: solo hay una propuesta no confirmada.
7. Contradicción sobre `donation-intents`: la matriz dice "sin operación de dominio"; ADR-037 §2.6 y el código crean la intención. La respuesta difiere (`{intentId, fundId}` frente a redirección).
8. Las respuestas de A1 y A2 en la matriz no coinciden con el código.

P3 (webhook y proveedor) y JWT/Identidad son dependencias de otros bloques.

**C. MODELO BD DE CONVOCATORIA: sí, suficientemente identificado para entregarlo** (BD HANDOFF): siete colecciones con nombres reales, campos, claves, índices e invariantes. Huecos reales, ninguno bloquea la entrega:
1. En producción los índices dependen de `auto-index-creation` de `app`, que hoy no incluye el módulo. Falta fijar cómo se crearán al integrarlo.
2. La `sequence` del audit log es solo monótona dentro de cada proceso, no global [A].
3. No hay índices de apoyo para `donation_intents.status/campaignRef` ni para `convocatoria_audit_log.campaignRef` [E: rendimiento].

**D. DOCUMENTACIÓN: no está lista para considerarse canónica.**
- La Enmienda 2 y ADR-043 siguen sin aprobar.
- La numeración de ADR se apoya en una fuente inexistente.
- `api-contract-matrix.md` y `estado-fase6.md` contradicen el código actual (Parte 8).
- Hay referencias a rutas inexistentes, grafía inconsistente de `EXPIRED_UNKNOWN`, identificadores reutilizados con distinto significado y un volumen alto de auditorías temporales sin marcar como históricas (Parte 9).

El núcleo normativo es coherente con el código: ADR-037, la Enmienda 1 y `convocatoria-resumen.md` §6.20.

---

*Archivo creado por esta auditoría: solo este. No se modificó ningún otro archivo. No se hizo commit.*
