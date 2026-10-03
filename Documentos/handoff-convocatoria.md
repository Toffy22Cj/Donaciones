# Handoff — Convocatoria (Fase 6, Capa 1)

**Propósito:** entregar el bloque Convocatoria a otra persona. Resume qué existe, qué se puede usar y qué falta, y separa lo que pertenece a Convocatoria de lo que pertenece a otros bloques.

**Fecha:** 2026-10-03. **Rama:** `develop`, HEAD `673eda9`. El módulo `convocatoria/` y casi toda su documentación **no tienen commit** (ver §13).

**Autoridad:**
- No es normativo: no introduce ninguna decisión.
- La normativa es `ADR-037-convocatoria-ledger-assignment-donationintent.md`.
- El detalle técnico está en `implementation_plan.md` §17.
- El índice de todo el bloque está en `convocatoria-resumen.md` §0.
- Si este documento difiere de esas fuentes, prevalecen ellas.

---

## 1. Estado de implementación

| Pieza | Estado | Fuente |
|---|---|---|
| Módulo Maven `convocatoria` (`convocatoria → contracts` únicamente) | IMPLEMENTADO | `implementation_plan.md` §17.0 |
| Crear, editar configuración (directa) y cerrar convocatoria | IMPLEMENTADO (caso de uso) | ADR-037 §2.1; plan §17.1 |
| Asignar empleado, designar administrador, retirar responsable | IMPLEMENTADO (caso de uso) | ADR-037 §2.4, §2.5, §5 |
| Crear `DonationIntent` (`GATEWAY`, `BANK_TRANSFER`; `CASH` rechazado por P5) | IMPLEMENTADO (caso de uso) | ADR-037 §2.6 |
| Confirmación manual `PENDING → CONFIRMED` | IMPLEMENTADO. La semántica aplicada (solo `ADMINISTRATOR`, nunca pasarela, sin efecto financiero) viene de decisiones humanas con la Enmienda 2 en **BORRADOR** | ADR-037 §2.6 (CD-03); plan §17.4 |
| Aplicación de fondos con barrera `APPLY_FUNDS`; `FUNDING_REJECTED`; consulta de recuperables | IMPLEMENTADO en `convocatoria`. Especificación en **BORRADOR** (Enmienda 2). El mecanismo de recuperación de `app` se describe en ADR-043 (**Propuesto**), documento de otro bloque | plan §17.1, §17.4, §17.5 |
| Idempotencia por `commandId` de los comandos de cliente | IMPLEMENTADO | ADR-037 §2.7 |
| Solicitud de cambio de configuración (R3), registro de dinero no aceptable (P1), efectivo (P5), cierre `CLOSE_ON_TARGET + CLOSE` (R4) | **NO IMPLEMENTADO**, fuera del primer corte | ADR-037 §7.2; plan §1.3, §14 |
| Lecturas pública y administrativa (`ConvocatoriaReadPort`, read models) | **NO IMPLEMENTADO** | plan §1.3 |
| Orquestador de fondos, disparo inmediato y scheduler (`app`) | **NO IMPLEMENTADO**; pertenece a `app` | ADR-043 (externo, otro bloque); plan §11 |
| Endpoints HTTP | **NO IMPLEMENTADO**; pertenecen a `api` | §8 |

## 2. Tests actuales

`mvn -o test -pl convocatoria`, ejecutado el 2026-10-03, output literal de Surefire:

```
Tests run: 194, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

- Incluyen tests unitarios, de integración con MongoDB real (Testcontainers, replica set), de concurrencia y la regla de arquitectura `ConvocatoriaArchitectureTest`.
- No se ejecutó el reactor completo en esta sesión.
- Fuente del estado: `estado-fase6.md` §3bis.

## 3. Colecciones MongoDB reales

Siete colecciones, todas propias de Convocatoria (detalle: `implementation_plan.md` §17.2):

| Colección | `_id` | Propósito |
|---|---|---|
| `convocatorias` | `campaignRef` | Campaña y configuración versionada |
| `campaign_assignments` | `assignmentId` | Responsables (`EMPLOYEE` o `ADMINISTRATOR`) |
| `campaign_responsible_state` | `campaignRef` | Contador de "≥1 responsable" |
| `campaign_funding_ledgers` | `campaignRef` | Monto aplicado contra la meta (solo con `MONETARY`) |
| `donation_intents` | `intentId` | Intenciones de donación monetaria |
| `convocatoria_processed_commands` | `commandId` (cliente) o `{commandType, commandId}` (sistema) | Idempotencia y barrera `APPLY_FUNDS` |
| `convocatoria_audit_log` | `entryId` | Audit log append-only |

**Acceso:** ningún otro módulo debe leer ni escribir estas colecciones directamente. `api` nunca recibe documentos crudos (ADR-041 §2.1).

## 4. Campos principales

La lista completa está en `implementation_plan.md` §17.2. Los campos más relevantes para quien consuma el módulo:
- **`convocatorias`:**
  - identidad: `organizationRef`, `publicCode`;
  - descripción: `title`, `description`, `visibility`, `startDate`, `endDate`;
  - estado: `status`;
  - configuración: `acceptedDonationTypes`, `acceptedPaymentMethods`, `currency`, `targetAmount`, `targetPolicy`, `onTargetReached`, `configurationVersion`.
- **`donation_intents`:**
  - referencias: `fundId`, `organizationRef`, `campaignRef`, `donorRef`;
  - importe y medio: `amount`, `currency`, `paymentMethod`, `confirmationSource`, `configurationVersion`;
  - pasarela y vencimiento: `paymentSessionId`, `providerEventId`, `expiresAt`;
  - estado: `status`;
  - confirmación: `confirmedBy`, `confirmedAt`, `confirmationPaymentMethod`, `confirmationReference`.
- **`campaign_funding_ledgers`:** `clearedAmount`, más la política copiada de la convocatoria.
- **`campaign_assignments`:** `employeeRef` (el responsable, sea `EMPLOYEE` o `ADMINISTRATOR`), `actingRole`, `status`, `assignedAt`, `removedAt`, `assignedBy`, `selfAssigned`.

## 5. Índices existentes

Declarados en el código: `implementation_plan.md` §17.2.

| Índice | Colección | Definición | Protege |
|---|---|---|---|
| `uq_public_code` | `convocatorias` | `publicCode` único | Unicidad del código público |
| `uq_active_employee_assignment` | `campaign_assignments` | `{employeeRef: 1}` único, parcial `{status: 'ACTIVE', actingRole: 'EMPLOYEE'}` | Un `EMPLOYEE` en una sola convocatoria activa (ADR-037 §2.4) |
| `uq_fund_id` | `donation_intents` | `fundId` único | Un `Fund` por intención |
| `uq_payment_session_id` | `donation_intents` | `paymentSessionId` único, parcial (solo si es string) | Correlación de pasarela |
| `_id` | `convocatoria_processed_commands` | — | Idempotencia y barrera `APPLY_FUNDS` |

- **Creación de los índices:**
  - En producción los crearía `auto-index-creation: true` de `app`.
  - Hoy no se crean porque `app` no depende de `convocatoria` (B-1).
  - Cómo se crearán al integrarlo: **NO DEFINIDO EN LA DOCUMENTACIÓN ACTUAL**.
- **Índices que no existen:**
  - de referencia de pago: depende del contrato de transferencia;
  - de `providerEventId`: depende de P3;
  - de rendimiento: `donation_intents.status`, `donation_intents.campaignRef`, `convocatoria_audit_log.campaignRef`.

## 6. Estados y transiciones

| Entidad | Estados | Transiciones implementadas |
|---|---|---|
| `Convocatoria` | `OPEN`, `CLOSED` | `OPEN → CLOSED` por cierre manual (`ADMINISTRATOR`). Sin reapertura. Sin cierre automático por fecha. El cierre por `CLOSE_ON_TARGET + CLOSE` no está implementado (R4) |
| `CampaignAssignment` | `ACTIVE`, `REMOVED` | `ACTIVE → REMOVED`. `REMOVED` no se reactiva |
| `DonationIntent` | `PENDING`, `CONFIRMED`, `FAILED`, `EXPIRED_UNKNOWN`, `FUNDING_REJECTED` | Creación → `PENDING`; `PENDING → CONFIRMED`; `CONFIRMED → FUNDING_REJECTED`. `FAILED` y `EXPIRED_UNKNOWN` existen sin transición (dependen de P3) |

- Una intención aplicada **sigue `CONFIRMED`**: la marca de aplicación es el reclamo `APPLY_FUNDS`.
- **`CONFIRMED` no significa fondos aplicados** (decisión humana F-1, `convocatoria-resumen.md` §6.20). Esa semántica todavía está en BORRADOR en la Enmienda 2 y en conflicto con la Enmienda 1 §5.3 (CD-03).

Detalle: `implementation_plan.md` §17.4.

## 7. Contrato interno (pertenece a Convocatoria)

Operaciones Java de los servicios de aplicación del módulo. Firmas, resultados y excepciones completas en `implementation_plan.md` §17.1.

| Servicio | Operación | Resultado |
|---|---|---|
| `ConvocatoriaLifecycleService` | `createConvocatoria(CreateConvocatoriaCommand)` | `CreateConvocatoriaResult {campaignRef, publicCode}` |
| | `editConfiguration(EditConfigurationCommand)` | `EditConfigurationResult {campaignRef, configurationVersion}` |
| | `closeConvocatoria(CloseConvocatoriaCommand)` | `CloseConvocatoriaResult {campaignRef}` |
| `ResponsibleAssignmentService` | `assignEmployee(AssignEmployeeToCampaignCommand)` | `AssignmentResult {assignmentId}` |
| | `designateAdministrator(DesignateAdministratorAsCampaignResponsibleCommand)` | `AssignmentResult {assignmentId}` |
| | `removeResponsible(RemoveResponsibleCommand)` | `RemoveResponsibleResult {removedAssignmentId, replacementAssignmentId}` |
| `DonationIntentService` | `resolvePublicCode(publicCode)` | `CampaignReference {campaignRef, organizationRef}` |
| | `createDonationIntent(CreateDonationIntentCommand)` | `CreateDonationIntentResult {intentId, fundId}` |
| | `confirmDonationIntent(ConfirmDonationIntentCommand)` | `boolean` |
| `CampaignFundingLedgerService` (operaciones de sistema) | `applyFundsForIntent(intentId)` | `ApplyFundsResult` (`appliedNow = false` ante un duplicado) |
| | `rejectFundingIfPermanentlyUnfundable(intentId)` | `boolean` |
| | `findConfirmedPendingApplication(limit)` | `List<DonationIntent>` |

**Reglas comunes:**
- Todo comando de cliente lleva `commandId`. Un duplicado devuelve el resultado original (N12), y un `commandId` reutilizado con otro tipo de comando se rechaza (I1).
- Las excepciones son nombradas: 42 clases en `domain.exception`.
- Reintento autónomo: 6 intentos con espera exponencial y tope de 500 ms.
- Dentro de una transacción externa no hay reintento interno; la operación marca la transacción para rollback (plan §17.3).

## 8. APIs HTTP realmente definidas

**Pertenencia:** el contrato HTTP **no pertenece a Convocatoria**. Pertenece a la Capa 5 (`api`), con su normativa en ADR-041 y su snapshot en `api-contract-matrix.md` (no congelado, no ADR). Este handoff solo lo lee y no lo cierra. No existe ningún controlador HTTP de Convocatoria.

Por la regla N11 (ADR-037 §0), ADR-037 y la Enmienda 1 prevalecen sobre `api-contract-matrix.md` §2 cuando se contradicen.

**Errores:** ADR-041 §2.5 propone 404 para "no encontrado" y 409 para conflictos de invariante (`CampaignClosedException`, `CampaignFundingLimitExceededException`, `LastResponsibleRemovalWithoutReplacementException`, `EmployeeAlreadyAssignedException`), pero lo declara "propuesta de este review, no confirmada por ninguna fuente". Por eso, en la tabla, los errores HTTP figuran como NO DEFINIDOS.

| Operación | Método y ruta | Request | Response | Errores HTTP | Autorización | Estado contractual |
|---|---|---|---|---|---|---|
| Crear convocatoria | `POST /organizations/{id}/campaigns` (matriz §2) | Cuerpo: NO DEFINIDO EN LA DOCUMENTACIÓN ACTUAL. Debe incluir `commandId` (ADR-037 §7.3); cómo viaja: NO DEFINIDO | Matriz: `{campaignRef, publicCode, status, ...}`. Caso de uso: `{campaignRef, publicCode}`. **Discrepancia sin reconciliar** | NO DEFINIDO EN LA DOCUMENTACIÓN ACTUAL | JWT + `ADMINISTRATOR` de la organización (matriz §2; ADR-037 §5) | Ruta y autorización fijadas ("DISEÑO CERRADO" en la matriz = forma HTTP, ADR-041 §2.1). Request y response **no cerrados** |
| Asignar empleado | `POST /campaigns/{campaignRef}/employees` (matriz §2) | NO DEFINIDO EN LA DOCUMENTACIÓN ACTUAL | Matriz: `{campaignRef, accountId, status}`. Caso de uso: `{assignmentId}`. Discrepancia | NO DEFINIDO EN LA DOCUMENTACIÓN ACTUAL | JWT + `ADMINISTRATOR` | **PENDIENTE (P4)**: la Enmienda 1 §7.3 exige desdoblarla en dos operaciones; la matriz no lo refleja |
| Designar administrador | NO DEFINIDO EN LA DOCUMENTACIÓN ACTUAL | NO DEFINIDO | NO DEFINIDO | NO DEFINIDO | `ADMINISTRATOR` (ADR-037 §5; dominio) | **PENDIENTE (P4)** |
| Retirar responsable | NO DEFINIDO EN LA DOCUMENTACIÓN ACTUAL | NO DEFINIDO | NO DEFINIDO | NO DEFINIDO | `ADMINISTRATOR` (dominio) | **PENDIENTE** |
| Editar configuración | NO DEFINIDO EN LA DOCUMENTACIÓN ACTUAL | NO DEFINIDO | NO DEFINIDO | NO DEFINIDO | `ADMINISTRATOR` (dominio) | **PENDIENTE** |
| Cerrar convocatoria | NO DEFINIDO EN LA DOCUMENTACIÓN ACTUAL | NO DEFINIDO | NO DEFINIDO | NO DEFINIDO | `ADMINISTRATOR` (dominio) | **PENDIENTE** |
| Detalle público | `GET /public/campaigns/{publicCode}` (matriz §2) | — | `ConvocatoriaReadModel`: campos NO DEFINIDOS EN LA DOCUMENTACIÓN ACTUAL. La matriz remite a "matriz ADR-021-D", que no define campos de campaña | NO DEFINIDO | Pública | Ruta fijada; response **no cerrada**; read port inexistente |
| Descubrimiento público | `GET /public/campaigns` (matriz §2) | `cursor`, `limit` (matriz) | Lista paginada: NO DEFINIDA | NO DEFINIDO | Pública | **PENDIENTE** (matriz). Paginación obligatoria como requisito (`convocatoria-resumen.md` §4) |
| Listado administrativo | `GET /organizations/{organizationId}/campaigns` (matriz §2b) | Paginación: NO DEFINIDA | `ConvocatoriaAdminReadModel {campaignRef, publicCode, title, status, visibility, targetAmount, targetPolicy, clearedAmount, responsables{accountId, fullName}, assignedEmployeeCount}` (matriz §2b) | NO DEFINIDO | JWT + `ADMINISTRATOR` + `OrganizationBoundaryPolicy` | "CONTRATO DEFINIDO" en la matriz para la forma de respuesta. Requiere composición en `app` (`fullName` viene de Identidad). Sin implementación |
| Crear intención de donación | `POST /public/campaigns/{publicCode}/donation-intents` (matriz §3) | NO DEFINIDO EN LA DOCUMENTACIÓN ACTUAL. Transporte del `commandId`: ADR-041 §7-A, fila 4, pendiente | Matriz: `{paymentRedirectUrl / paymentSessionId}`. Caso de uso: `{intentId, fundId}`. Discrepancia; la redirección depende del proveedor (P3) | NO DEFINIDO | Pública u opcional JWT | **PENDIENTE** (matriz) |
| Confirmación manual (`BANK_TRANSFER`) | NO DEFINIDO EN LA DOCUMENTACIÓN ACTUAL | NO DEFINIDO | NO DEFINIDO | NO DEFINIDO | `ADMINISTRATOR` de la organización (D5, §6.20; Enmienda 2 §3.1 en BORRADOR) | **PENDIENTE**. La Enmienda 2 §6 lo declara dependencia de ADR-041. Además no es desplegable sin el índice de referencia de pago (plan §9.3) |
| Consulta del estado de una intención | NO DEFINIDO EN LA DOCUMENTACIÓN ACTUAL | — | Estados de §6. "`CONFIRMED` no significa fondos aplicados" (matriz, Frontend Contract) | NO DEFINIDO | NO DEFINIDO | **PENDIENTE** |
| Webhook de pago | `POST /webhooks/payments` (matriz §3) | DTO del proveedor: NO DEFINIDO (P3) | `200 OK` al proveedor (matriz) | NO DEFINIDO | Firma del proveedor, nunca JWT | "CONTRATO CONCEPTUAL". La matriz dice "dominio subyacente CERRADO E IMPLEMENTADO", lo que no es exacto: el orquestador no existe. Es orquestación de `app`, **no de Convocatoria** |

**APIs que no son de Convocatoria pero usan sus datos:**
- `GET /public/campaigns/{publicCode}/narrative` (IA, ADR-040);
- tracking (`campaignRef` en `PublicDonationTrackingDTO`);
- endpoints de `PhysicalAsset` con `campaignRef` (ADR-029);
- `POST /platform/organizations/{id}/verify` (Identidad; precondición de crear convocatorias e intenciones).

## 9. APIs todavía no cerradas

Son todas las filas de §8. Ninguna operación HTTP de Convocatoria tiene hoy definidos a la vez request, response y errores.

Lo más avanzado:
- la ruta y la autorización de crear convocatoria;
- la forma de respuesta del listado administrativo.

Las decisiones que faltan pertenecen a la Capa 5 (ADR-041 y la matriz) o dependen de P3 y P4.

## 10. Dependencias externas

| Dependencia | Bloque | Qué bloquea |
|---|---|---|
| Implementación de producción de `OrganizationVerificationPort` (ADR-038) | Identidad / `app` | Que `app` pueda depender de `convocatoria`; crear convocatorias e intenciones de punta a punta |
| Huecos de `IdentityPrincipalPort` (`INACTIVE`, `AccountNotFoundException` cruzando la frontera) | Identidad (ADR-038) | Validación del destinatario de asignaciones (X2) |
| T1 (`clearFundsGenesis` sin reintento interno), P8 (outbox de la génesis), idempotencia de `clearFundsGenesis` | `core` | Orquestador de fondos |
| Orquestador, disparo inmediato y scheduler | `app`; descrito en ADR-043 (Propuesto), documento de otro bloque | Aplicación real de fondos y recuperación |
| P3: webhook y proveedor de pago | `app` / `api` | Confirmación de intenciones de pasarela; `FAILED` y `EXPIRED_UNKNOWN` |
| P4 y contratos HTTP | `api` (ADR-041) | Toda la exposición HTTP |
| ADR-032 (puerto contextual, P6) | `core` | Capacidad contextual del administrador responsable; respaldo del `REPRESENTATIVE` |
| ADR-029 (`campaignRef` en `PhysicalAsset`) | `core` | Donaciones en especie ligadas a convocatoria; P2 |
| ADR-040 (N10, `confirmationSource`) | IA / `core` | Distinción proveedor/organización en vista pública e IA |
| P1 (registro de dinero no aceptable) | Producto / Convocatoria | Habilitar dinero real con rechazos (D4) |

## 11. Qué puede consumir otro módulo actualmente

- **En ejecución, nada todavía.** Ningún módulo depende de `convocatoria`:
  - `api` y `app` no lo tienen en su `pom.xml`;
  - `contracts` no contiene ningún puerto de Convocatoria.
- **A nivel de código**, cuando exista la dependencia `app → convocatoria` (B-1, `convocatoria-resumen.md` §6.16), `app` podrá:
  - componer los servicios de §7 como Spring beans;
  - proporcionar la implementación de producción de `OrganizationVerificationPort`;
  - orquestar `applyFundsForIntent`, `rejectFundingIfPermanentlyUnfundable` y `findConfirmedPendingApplication` en su transacción. El mecanismo se describe en ADR-043, documento externo de otro bloque.
- **`api`** consumirá Convocatoria a través de lo que `app` componga, o de puertos de lectura que hoy no existen. Nunca por las colecciones.

## 12. Qué queda bloqueado por otros bloques

- **Composición en `app`:** bloqueada por ADR-038 (`OrganizationVerificationPort`).
- **Aplicación real de fondos (ledger + génesis + outbox en una transacción):** bloqueada por T1 y P8 (`core`) y por el orquestador (`app`).
- **Confirmación de pasarela:** bloqueada por P3.
- **Exposición HTTP:** bloqueada por ADR-041, P4 y los contratos de §8.
- **Capacidad contextual sobre activos:** bloqueada por ADR-032/P6 y ADR-029.

**Pendiente dentro de Convocatoria** (no bloqueado por otros bloques, pero sin decisión):
- P1, P5, P7;
- R3, R4;
- P10, en BORRADOR.

## 13. Decisiones humanas y estado documental

Ver `convocatoria-resumen.md` §0.4 y §0.5. En resumen:
- **Aprobación de la Enmienda 2 (DH-C-1).** Su §9 también exige aprobar ADR-043, que es de otro bloque (`convocatoria-resumen.md` §6.21). Hoy la semántica implementada de confirmación y aplicación descansa en decisiones humanas aprobadas (§6.20), pero no en una enmienda aprobada (CD-03).
- **Numeración de ADR (DH-C-3).**
- **G1–G3 (DH-C-4).**
- **Revisión 3 del plan (DH-C-5).**
- **Grafía `EXPIRED_UNKNOWN` (DH-C-8).**
- **Versionado en Git (DH-C-10).** El módulo `convocatoria/` y la documentación del bloque no tienen commit.
