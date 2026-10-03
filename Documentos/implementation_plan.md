# implementation_plan.md — Convocatoria (ADR-037 + Enmienda 1)

**Estado:** APROBADO (revisión 2) — aprobación humana explícita del 2026-10-01 como plan de implementación del primer corte. Autoriza implementar siguiendo el orden y las condiciones de §15, con P1–P7, R3–R4 y las dependencias externas abiertas; ninguna decisión nueva puede inventarse durante la implementación (regla 2.1).
**Fecha:** 2026-09-30. **Revisión 3 (2026-10-02, cierre del flujo de fondos, `convocatoria-resumen.md` §6.20; ADR-037 Enmienda 2; ADR-043):** confirmar y aplicar fondos son actos separados; la aplicación consume una intención `CONFIRMED` con barrera por comando de sistema `APPLY_FUNDS`; estado terminal `FUNDING_REJECTED`; confirmación manual solo por `ADMINISTRATOR` y nunca para pasarela; recuperación automática (ADR-043). Sustituye lo que las revisiones 2.3–2.5 atribuían a §6.17–§6.19 y las contradicciones abiertas (renombradas K-1/K-2, resueltas). **Revisión 2.5 (2026-10-02, solo trazabilidad, sin cambio de comportamiento):** la fuente de C-01 pasa a ser `convocatoria-resumen.md` §6.19 (DH-1–DH-5 vigentes), que sustituye §6.17 y §6.18 y registra las contradicciones abiertas F-1 (aplicación "posterior" frente a Enmienda §5.3) y F-2 (N9 frente a la confirmación independiente). Lo que la rev. 2.4 atribuía a §6.18 se lee contra §6.19. **Revisión 2.4 (2026-10-02, cierre de C-01, `convocatoria-resumen.md` §6.18):** la aplicación de fondos de una intención es `applyFundsForIntent` (§8): barrera + ledger, uniéndose a la transacción del orquestador si existe. La confirmación independiente es válida y no implica fondos aplicados. Un fallo de aplicación se propaga y no deja confirmación aunque el llamador capture la excepción. C01-A y C01-B (rev. 2.3) quedan superados. **Revisión 2.3 (2026-10-02, decisión humana C-01, sin cambio de alcance):** incorpora DH-1–DH-5 (`convocatoria-resumen.md` §6.17): `CONFIRMED` no significa fondos aplicados; un rechazo del ledger no deja la intención confirmada; el fallo se propaga al orquestador. Los puntos abiertos C01-A y C01-B de §6.17 bloquean los cambios de código de C-01 hasta que se decidan (§7.2). **Revisión 2.1 (2026-10-01, corrección de coherencia, sin cambio de alcance):** B-1 — aclaración técnica derivada de la verificación del grafo Maven, no decisión de dominio: la Tarea 1 no añade `app → convocatoria` en este corte (contradecía §11); la dependencia `app → convocatoria` se añadirá cuando exista composición que la necesite (§11). **Revisión 2.2 (2026-10-01, solo redacción de B-1, sin cambio de alcance):** precisa que la restricción permanente es la dirección `convocatoria → app/core/identity`, no `app → convocatoria`; X2 — se explicita el comportamiento ante excepciones propias del puerto. **Revisión 2:** 2026-10-01 — incorpora las decisiones humanas R1, R3 (alcance), ID y P7 en el primer corte (`convocatoria-resumen.md` §6.15), R2 resuelto por ADR-037 §2.5, y el diseño verificado en código para X1, X2, I1 y T1 (revisión técnica del 30 sept–1 oct, solo lectura). Revisión de autoría propia: no sustituye una revisión independiente.
**Base normativa:** ADR-037 original (archivo `ADR-033-convocatoria-ledger-assignment-donationintent.md`), `claude/ADR-037-enmienda-1-convocatoria.md` (aprobada por decisión humana explícita el 2026-09-30, con N1–N12), `convocatoria-resumen.md` §6.
**Regla de trazabilidad:** cada tarea cita la decisión que implementa. Ante discrepancia entre este plan y esos documentos, prevalecen ellos y el implementador se detiene y lo reporta. Por la regla de precedencia N11, ADR-037 y su enmienda prevalecen sobre `fase-6-estructura-y-perimetro-convocatoria.md` y `api-contract-matrix.md` §2.
**Nota de la consolidación documental (2026-10-03).** Esta nota no cambia el alcance ni la aprobación del plan.
- **Rutas de la base normativa:**
  - El ADR-037 original es hoy `ADR-037-convocatoria-ledger-assignment-donationintent.md` (renombrado desde `ADR-033-…` en el commit `b417d3a`). Desde el 2026-10-03 contiene la Enmienda 1 integrada (lectura consolidada).
  - La Enmienda 1 está en `Documentos/ADR-037-enmienda-1-convocatoria.md`. La ruta `claude/…` de la línea anterior no existe en el repositorio.
- **Base de la revisión 3:**
  - la Enmienda 2 (`ADR-037-enmienda-2-convocatoria.md`), en estado **BORRADOR**;
  - ADR-043, en estado **Propuesto**, documento de otro bloque citado como dependencia externa (`convocatoria-resumen.md` §6.21);
  - las decisiones humanas de `convocatoria-resumen.md` §6.20.
- **Aprobación de la revisión 3:** la cabecera solo declara aprobada la revisión 2. Si la revisión 3 está aprobada es una decisión humana pendiente (`convocatoria-resumen.md` §0.4, DH-C-5).
- **Contrato interno y modelo físico:** el contrato interno de aplicación y el modelo físico MongoDB implementados en el primer corte se describen en §17.
- **Índice del bloque:** `convocatoria-resumen.md` §0.

**Convención de este documento:**
- **NUEVO**: no existe en el código; se crea. Los nombres marcados "provisional" pueden ajustarse durante la implementación, pero el cambio se reporta en el resumen de decisiones (§16), igual que en los planes de Fase 2 y Fase 4.
- **EXISTE**: verificado en código por los informes del 2026-09-30 (§2). Se reverifica en la Tarea 0.
- **DETENERSE**: el implementador no decide; vuelve a revisión humana.

---

## 1. Objetivo y alcance

### 1.1 Objetivo

Implementar el módulo `convocatoria` (primer corte) según ADR-037 + Enmienda 1: dominio, persistencia, autorización, idempotencia y casos de uso de `Convocatoria`, `CampaignAssignment`, `CampaignResponsibleState`, `CampaignFundingLedger` y `DonationIntent`, sin integración con otros módulos más allá de `contracts`.

### 1.2 Dentro del primer corte

| Pieza | Fuente |
|---|---|
| Módulo Maven `convocatoria` (`convocatoria → contracts` únicamente) | ADR-037 §2 |
| `Convocatoria`: configuración versionada, invariantes, `OPEN`/`CLOSED`, cierre manual | ADR-037 §2.1; Enmienda §3.1, §3.2, §3.4 |
| Edición directa de configuración antes de la primera donación | Enmienda §3.2 |
| `CampaignAssignment` con `actingRole`; `AssignEmployeeToCampaign`; `DesignateAdministratorAsCampaignResponsible`; `RemoveResponsible` | ADR-037 §2.4–§2.5; Enmienda §4 |
| `CampaignResponsibleState` (contador) | ADR-037 §2.5 |
| `CampaignFundingLedger`: creación según `MONETARY` y escritura condicional por política (sin orquestador) | ADR-037 §2.2; Enmienda §3.1, §3.3 |
| `DonationIntent` para `GATEWAY` y `BANK_TRANSFER`: creación, vencimiento, transición condicional `PENDING → CONFIRMED` | ADR-037 §2.6; Enmienda §5 |
| Idempotencia de los comandos de escritura del módulo (registro propio) | Enmienda §3.5 (B2, N12) |
| Autorización propia del módulo (`ConvocatoriaAuthorizationPolicy`) | ADR-037 §4, §5; Enmienda §3.4, §4 |
| Resolución interna `publicCode → campaignRef + organizationRef` | ADR-037 §2.6 |

### 1.3 Fuera del primer corte (no se implementa; ver §11 y §14)

| Pieza | Motivo |
|---|---|
| Registro de dinero no aceptable | P1 abierto; sin mecanismo de resolución tendría un estado sin salida (regla 2.6) |
| Flujo de efectivo (`CASH`) | P5 abierto |
| Puerto contextual hacia `core` | ADR-032 + P6 |
| Orquestador de `STRICT` en `app` y efecto de confirmación sobre `Fund` | Integración; requisito transaccional de Enmienda §6 |
| Adaptador del webhook | P3 |
| Endpoints HTTP | P4 / ADR-041 |
| `campaignRef` en `PhysicalAsset` | ADR-029 |
| Validación de `IN_KIND` en el Camino B | P2 |
| Distinción pública/IA de la fuente de confirmación | `core`/ADR-040 (Enmienda §7.4) |
| `ConvocatoriaReadModel` público y `ConvocatoriaAdminReadModel` | Campos del read model público no definidos en ningún documento (ver `implementation-plan-paxfide-web.md` §0); vista admin depende de un puerto de miembros de Identity (`contract-wiring-review.md` P2) |
| Descubrimiento público (`listPublicOpen`) | Contrato pendiente (`api-contract-matrix.md` §2) |
| Solicitud de cambio de configuración con aprobación (N3) | Fuera del primer corte por decisión humana del 2026-10-01 (R3, `convocatoria-resumen.md` §6.15). Quién aprueba ya está decidido (Enmienda §3.2); quedan abiertos quién solicita, quién rechaza y la retirada por el solicitante |
| Edición de datos descriptivos (`title`, `description`, `visibility`, fechas) tras la creación | Ninguna operación está definida en ADR-037 ni en la enmienda; no se inventa. En este corte se fijan al crear |

*Nota de la consolidación (2026-10-03):* `implementation-plan-paxfide-web.md`, citado en la tabla anterior, no existe en `Documentos/` ni en el repositorio. La referencia se conserva como estaba.

---

## 2. Estado de partida del código real

**Fuente:** informes de solo lectura sobre el repositorio en rama `develop`, HEAD `673eda92fcb18626788804a3a48b41730e6784fb`, registrados en `convocatoria-resumen.md` §6.9. Esta sesión no tiene acceso directo al repositorio; tests no ejecutados. La Tarea 0 reverifica todo contra el HEAD vigente al empezar.

| Hecho | Estado |
|---|---|
| Módulos del reactor: `contracts`, `core`, `crypto`, `ai`, `api`, `identity`, `app` (7) | EXISTE |
| Módulo `convocatoria` | No existe |
| `contracts` contiene `IdentityPrincipalPort`, `AuthorizationPrincipal`, `AuditFactsPort`, `NarrativeReadPort`, `HashPort` | EXISTE |
| `ConvocatoriaReadPort` | **No existe** (ADR-037 §2.6 lo da por "ya existente"; el código lo desmiente) |
| `MongoTransactionManager` declarado en `app` (`TraceabilityInfrastructureConfig.java:14`) | EXISTE |
| `@SpringBootApplication(scanBasePackages = {"com.traceability", "identity"})` y `@EnableMongoRepositories(basePackages = {"com.traceability", "identity"})` en `app` (`TraceabilityApplication.java:9-10`) | EXISTE (corregido el 2026-10-01: ADR-037 §2.3 citaba solo `com.traceability`). `app` es el módulo de composición: depende de los módulos que ensambla y no hay autoconfiguración que descubra módulos fuera de su classpath. Un paquete `com.traceability.convocatoria` entrará en el escaneo cuando exista `app → convocatoria`; en el primer corte no se añade (B-1, §11, §15) |
| `Organization` (`identity/.../Organization.java:16-25`): solo `organizationId`, `type`, `members` | EXISTE; **no hay `verificationStatus`** en ningún módulo (X1) |
| `IdentityPrincipalPortImpl.resolvePrincipal` (`:30-58`) resuelve cualquier cuenta (organización + roles de su membresía). No filtra `AccountStatus.INACTIVE` y una cuenta inexistente lanza `identity.domain.exception.AccountNotFoundException` (`MongoAccountRepositoryAdapter.java:29`) | EXISTE (X2) |
| `CommandRetryTemplate` (`core`, `:14-36`) reintenta `ConcurrencyConflictException` y al agotarse lanza `ConcurrencyRetryExhaustedException` sin la causa (`:24`, `:35`); `FundCommandService.clearFundsGenesis` (`:83-96`) lo usa alrededor de `appendAndOutbox` | EXISTE (T1, §8) |
| Índices declarados por anotación; `auto-index-creation: true` en `app/src/main/resources/application.yml:15` | EXISTE |
| Sin `@Version` en ningún módulo; sin `Clock` inyectado en producción (`Instant.now()` directo) | EXISTE |
| Invariantes de dominio de `core` lanzan `IllegalArgumentException` (`Fund.java:118`) | EXISTE; **no se imita**: Convocatoria usa excepciones nombradas (regla 2.6) |
| `TransactionalEventPublisher.appendAndOutbox` con `@Transactional` sin atributos → `Propagation.REQUIRED` (`TransactionalEventPublisher.java:25`) | EXISTE; verificado por lectura, no por ejecución |
| Registro de comandos procesados de `core`: `processed_commands`, `tryClaim(commandId)` por findAndModify con upsert | EXISTE (en `core`; no se reutiliza su código) |
| Reintento transaccional de `identity`: `MongoTransactionRetryHelper` con `TransactionTemplate` programático, verificado con concurrencia real (`CyclicBarrier`) | EXISTE (en `identity`; según `documento-maestro-proyecto.md`, Tarea 4.9) |
| Test de arquitectura ArchUnit en `core` (`ArchitectureTest.java`) | EXISTE |
| `clearFundsGenesis` sin tests de reintento/duplicado | EXISTE; idempotencia **NO CONFIRMADA** en ejecución |

---

## 3. Cambios de dominio (`convocatoria.domain`, todo NUEVO)

Paquete raíz provisional: `com.traceability.convocatoria`, con subpaquetes `domain`, `application`, `infrastructure`, siguiendo la convención de `core` (a confirmar en la Tarea 0 contra `identity`). El dominio no importa Spring ni MongoDB (regla ArchUnit, §12.1).

### 3.1 `Convocatoria` — NUEVO

- **Contrato:** ADR-037 §2.1; Enmienda §3.1, §3.2, §3.4.
- **Campos decididos:** `campaignRef`; `organizationRef` (referencia opaca); `publicCode` (único); `status ∈ {OPEN, CLOSED}` (persistido); `visibility ∈ {PUBLIC, PRIVATE_LINK}` (ADR-037 §2.1; resumen §2); `acceptedDonationTypes ⊆ {MONETARY, IN_KIND}`; `acceptedPaymentMethods ⊆ {GATEWAY, BANK_TRANSFER, CASH}`; `targetAmount`, `targetPolicy ∈ {FLEXIBLE, STRICT, CLOSE_ON_TARGET}`, `onTargetReached ∈ {CLOSE, REJECT_EXCESS, ACCEPT_EXCESS}` (solo con `CLOSE_ON_TARGET`); versión de configuración.
- **Campos de R1 (decisión humana 2026-10-01, resumen §6.15):**
  - `title`: obligatorio, texto no vacío (longitud máxima: implementación, reportada en §16).
  - `description`: opcional.
  - `startDate`, `endDate`: obligatorias, instantes UTC, `startDate < endDate`. **Describen la convocatoria y no limitan la creación de `DonationIntent` en este corte** (P7 sigue abierto, §9.1). No cambian `status` (no hay cierre automático por fecha, Enmienda §3.4).
  - `currency`: obligatoria si `MONETARY ∈ acceptedDonationTypes`; ausente si la convocatoria es solo `IN_KIND`.
- `title`, `description`, `visibility` y las fechas **no** forman parte de la configuración versionada (Enmienda §3.2 enumera `acceptedDonationTypes` y `acceptedPaymentMethods`; meta y política en §3.1). En este corte se fijan al crear: no hay operación para editarlos (§1.3).
- **Invariantes (rechazo con excepción nombrada):**
  - `title` no vacío; `startDate < endDate` (R1).
  - `currency` presente si y solo si `MONETARY ∈ acceptedDonationTypes`; añadir `MONETARY` incluye `currency` en la misma transición de versión (R1).
  - `acceptedDonationTypes` no vacío (N1).
  - Si `MONETARY ∈ acceptedDonationTypes`: `acceptedPaymentMethods` no vacío y `targetAmount`/`targetPolicy` definidos. Comprobado en creación y en todo cambio (§6.8.4).
  - Si la convocatoria es solo `IN_KIND`: sin `targetAmount`/`targetPolicy` (N2).
  - `onTargetReached` presente si y solo si `targetPolicy = CLOSE_ON_TARGET`.
  - Añadir `MONETARY` es una sola transición de versión que incluye meta, política y medios de pago.
  - `CLOSED` no admite cambios de configuración.
  - Meta y política no cambian después de la primera donación; **no se implementa operación de cambio de meta/política** (no está definida; resumen §2).
- **Transiciones:** `OPEN → CLOSED` por cierre manual (§10). Ninguna transición `CLOSED → OPEN`.
- **Test que lo demuestra:** `ConvocatoriaTest` (NUEVO, unitario): un caso por invariante, positivo y negativo.

### 3.2 `CampaignAssignment` — NUEVO

- **Contrato:** ADR-037 §2.4; Enmienda §4.1–§4.3.
- **Campos:** identificador de asignación; `campaignRef`; responsable (campo `employeeRef` heredado del índice; representa a `EMPLOYEE` o `ADMINISTRATOR`, Enmienda §4.2); `actingRole ∈ {EMPLOYEE, ADMINISTRATOR}`; `status ∈ {ACTIVE, REMOVED}`; fechas de asignación y retiro; actor que asigna; marca de autoasignación (para el audit log).
- **Reglas:** cada asignación es una inserción; `REMOVED` es histórico y no se reactiva.
- **Test:** `CampaignAssignmentTest` (NUEVO, unitario).

### 3.3 `CampaignResponsibleState` — NUEVO

- **Contrato:** ADR-037 §2.5; Enmienda §4.2.
- **Comportamiento:** contador `activeResponsibleCount` por `campaignRef`; cuenta `EMPLOYEE` y `ADMINISTRATOR`; el respaldo del `REPRESENTATIVE` no cuenta. Mecanismo de contención de escritura, nunca read model.
- **Creación (R2, resuelto por ADR-037 §2.5):** el contador se inicializa en la primera asignación (`upsert:true`), así que una convocatoria recién creada tiene 0 responsables hasta su primera asignación; la protección "nunca 0" se aplica al **retiro**. El texto "nunca 0 responsables" de `convocatoria-resumen.md` §2 es conceptual (18 sept) y queda subordinado al ADR normativo.

### 3.4 `CampaignFundingLedger` — NUEVO

- **Contrato:** ADR-037 §2.2; Enmienda §3.1 (N2), §3.3.
- **Comportamiento:** existe solo si la convocatoria acepta `MONETARY`; se crea en la misma transacción que la convocatoria o que la transición de versión que añade `MONETARY`. Ver §8.

### 3.5 `DonationIntent` — NUEVO

- **Contrato:** ADR-037 §2.6, §2.6bis; Enmienda §5 (N5–N10).
- **Campos:** `intentId`; `fundId` (generado una sola vez al crear, inmutable); `organizationRef`, `campaignRef`, `donorRef`, `amount`, `currency`; `paymentMethod`; `confirmationSource` (distinto de `paymentMethod`); versión de configuración leída al crear; `paymentSessionId` (solo `GATEWAY`; asignado cuando exista la integración P3, nulo hasta entonces); `providerEventId` (solo `GATEWAY`); fecha de expiración (solo `BANK_TRANSFER`); `status ∈ {PENDING, CONFIRMED, FAILED, EXPIRED-UNKNOWN}`; datos de confirmación (quién, cuándo, medio, referencia/evidencia; N8).
- **Sin estados nuevos:** el vencimiento de `BANK_TRANSFER` se evalúa contra la fecha de expiración; no se añade un estado `EXPIRED` (no está decidido).
- *Nota de la consolidación (2026-10-03):* la revisión 3 añadió el estado terminal `FUNDING_REJECTED` (§4.4, §8). Su fuente es la decisión humana D3 (`convocatoria-resumen.md` §6.20) y la Enmienda 2 §4, que está en **BORRADOR**. La lista de estados de arriba es la de la revisión 2 y se conserva. El enum del código usa `EXPIRED_UNKNOWN` (grafía pendiente: DH-C-8).
- **Test:** `DonationIntentTest` (NUEVO, unitario).

### 3.6 Excepciones

Excepciones ya nombradas en ADR-037/ADR-041 (§2.5): `CampaignNotFoundException`, `CampaignFundingLimitExceededException`, `EmployeeAlreadyAssignedException`, `LastResponsibleRemovalWithoutReplacementException`, `CampaignClosedException` (rechazo de nuevas intenciones con `status ≠ OPEN`). Toda otra condición de fallo lleva excepción nombrada NUEVA (regla 2.6), incluidas las invariantes de R1 (rango de fechas, título vacío, moneda ausente o distinta) y el reuso de un `commandId` con otro tipo de comando (§7.1); los nombres los elige el implementador y los reporta en §16. **No se imita** el uso de `IllegalArgumentException` para invariantes que hace `core` (`Fund.java:118`): contradice la regla 2.6. `DomainInvariantViolationException` vive en `core` y no es accesible desde este módulo. `PaymentCorrelationNotFoundException` y `PaymentEventMismatchException` pertenecen al webhook (P3) y no se crean en este corte.

---

## 4. Persistencia, esquema y eventos

### 4.1 Naturaleza

CRUD + audit log append-only, **no Event Sourcing** (ADR-037 §2). No hay eventos de dominio versionados, ni upcasters, ni migración: todas las colecciones son nuevas (Enmienda §9.2).

### 4.2 Colecciones e índices — NUEVO (nombres de colección provisionales)

| Colección (provisional) | Índices obligatorios | Fuente |
|---|---|---|
| convocatorias | `publicCode` único; `campaignRef` único | ADR-037 §2.1 |
| campaign_assignments | `{ employeeRef: 1 } UNIQUE WHERE status = "ACTIVE" AND actingRole = "EMPLOYEE"` | Enmienda §4.2 |
| campaign_responsible_state | `campaignRef` único | ADR-037 §2.5 |
| campaign_funding_ledgers | `campaignRef` único (1:1 solo con `MONETARY`) | ADR-037 §2.2; N2 |
| donation_intents | `intentId` único; `fundId` único; `paymentSessionId` único **parcial** (solo si existe) | ADR-037 §2.6; Enmienda §5.1 |
| registro de comandos procesados del módulo | Comandos de cliente: `_id = commandId`; guarda además el tipo de comando y el resultado original en el mismo documento. Comandos de sistema (`APPLY_FUNDS`): `_id = {commandType, commandId}` en la misma colección, espacio de claves separado (Enmienda 2 §3.3) | Enmienda §3.5; I1 (§7.1); Enmienda 2 §3.3 |
| audit log del módulo | — (append-only) | ADR-037 §6 |

- Índice de referencia de pago (Enmienda §5.2): **no se crea en este corte**. Su alcance se fija en el contrato de transferencia (REQUISITO de la enmienda). Mientras no exista, la confirmación manual de `BANK_TRANSFER` no es desplegable (§9.3).
- Índice de `providerEventId`: fuera de corte (P3).
- *Nota de la consolidación (2026-10-03):* los nombres reales de colecciones e índices del código están en §17.2:
  - las cinco colecciones de dominio coinciden con esta tabla;
  - el registro de comandos es `convocatoria_processed_commands`;
  - el audit log es `convocatoria_audit_log`.
- **Creación de índices:** comprobar en la Tarea 0 cómo los crea hoy el proyecto (`auto-index-creation: true` en `app`; en tests de `core` no se crea, según §6.9.3 del resumen). Los tests de `convocatoria` deben crear explícitamente los índices de los que dependen sus invariantes; un test de unicidad sin índice no prueba nada (precedente: `ProcessedCommandIdempotencyIntegrationTest.java:106`).

### 4.3 Adaptadores — NUEVO

Puertos de salida en `convocatoria.application` y adaptadores Mongo en `convocatoria.infrastructure`, con documentos y mappers manuales (mismo patrón que `identity`, Tarea 4.6). El dominio no lleva anotaciones de persistencia.

### 4.4 Transacciones del módulo

| Transacción | Escrituras que la componen | Fuente |
|---|---|---|
| Crear convocatoria | convocatoria + ledger (si `MONETARY`) + audit log + registro de comando | ADR-037 §2.1; N2; §3.5 |
| Editar configuración | convocatoria (condicional sobre la versión esperada) + ledger (si se añade `MONETARY`) + audit log + registro de comando | Enmienda §3.2 |
| Asignar / designar | asignación + contador (`upsert` en la primera) + audit log + registro de comando | ADR-037 §2.5 |
| Retirar sin reemplazo | asignación → `REMOVED` + contador `$gt:1` + audit log + registro de comando | ADR-037 §2.5 |
| Retirar con reemplazo | asignación → `REMOVED` + nueva asignación (contador sin cambio) + audit log + registro de comando | ADR-037 §2.5 |
| Cerrar | convocatoria `OPEN → CLOSED` (condicional) + audit log + registro de comando | Enmienda §3.4 |
| Crear intención | intención + audit log + registro de comando | ADR-037 §2.6; ID (§7.3) |
| Confirmar intención (manual) | autorización `ADMINISTRATOR` + intención `PENDING → CONFIRMED` condicional; sin ledger ni registro de comandos | Enmienda 2 §3.1 |
| Aplicar fondos de una intención `CONFIRMED` | reclamo `(APPLY_FUNDS, intentId)` + ledger (§8), con el resultado original en el reclamo; en la transacción del orquestador si existe (que añade génesis y outbox, fuera de corte) | Enmienda 2 §3.2, §3.3 |
| Marcar `FUNDING_REJECTED` | `CONFIRMED → FUNDING_REJECTED` condicional, solo si el rechazo es permanente y la barrera no está reclamada | Enmienda 2 §4 |

- `MongoTransactionManager` lo aporta `app` en producción. Los tests del módulo declaran su propia configuración de test con `MongoTransactionManager` y Testcontainers en modo replica set (las transacciones de MongoDB lo exigen), siguiendo la configuración de test existente de `core` (verificar en la Tarea 0).
- **Reintento ante `TransientTransactionError`:** NUEVO en el módulo, reimplementando el patrón de `MongoTransactionRetryHelper` de `identity` (`TransactionTemplate` programático, reintento acotado, sin reintentar excepciones de dominio). No se importa `identity` ni se mueve el helper a otro módulo (eso sería una decisión de arquitectura fuera de alcance). No es un mecanismo nuevo: es el ya aprobado en `convocatoria-resumen.md` §2.
- *Nota de la consolidación (2026-10-03):* los parámetros reales del reintento implementado (6 intentos, espera exponencial con tope de 500 ms, sin reintento interno dentro de una transacción externa) se describen en §17.3.

---

## 5. Casos de uso y comandos (`convocatoria.application`, todo NUEVO)

Todos los comandos de escritura reciben `commandId` (§7). Las firmas exactas son propuestas no verificadas contra código (Enmienda §10.2) y se reportan en §16.

| Caso de uso | Comportamiento | Errores principales | Test |
|---|---|---|---|
| Crear convocatoria | Valida invariantes de §3.1; genera `campaignRef` y `publicCode` únicos; crea ledger si `MONETARY` | Invariante de configuración; organización no verificada (X1); actor no autorizado | `CreateConvocatoriaIntegrationTest` |
| Editar configuración (directa) | Solo si no existe ninguna `DonationIntent` para la convocatoria (H1); incrementa versión; escritura condicional sobre la versión esperada | Ya existen donaciones; conflicto de versión; `CLOSED` | `EditConfigurationIntegrationTest` |
| `AssignEmployeeToCampaign` | Inserta asignación `actingRole = EMPLOYEE`; incrementa contador | `EmployeeAlreadyAssignedException`; destinatario no es `EMPLOYEE` de la organización (X2); autoasignación | `AssignEmployeeIntegrationTest` |
| `DesignateAdministratorAsCampaignResponsible` | Inserta asignación `actingRole = ADMINISTRATOR`; sin límite de convocatorias; autoasignación permitida y marcada | Destinatario no es `ADMINISTRATOR` (X2) | `DesignateAdministratorIntegrationTest` |
| `RemoveResponsible(campaignRef, responsibleRef, replacementRef?)` | Una sola operación; con reemplazo aplica las reglas de la operación del tipo del reemplazo | `LastResponsibleRemovalWithoutReplacementException` | `RemoveResponsibleIntegrationTest` |
| Cerrar convocatoria | `OPEN → CLOSED` (§10) | Ya `CLOSED` (excepción nombrada NUEVA) | `CloseConvocatoriaIntegrationTest` |
| Crear `DonationIntent` | §9; con `commandId` (§7.3) | `CampaignClosedException`; tipo o medio no aceptado; moneda distinta de la de la convocatoria (R1); `CASH` no soportado en este corte | `CreateDonationIntentIntegrationTest` |
| Resolver `publicCode` | Devuelve `campaignRef` y `organizationRef` para uso interno del caso anterior | `CampaignNotFoundException` | dentro del test anterior |

**"Primera donación" en este corte (H1):** equivale a "existe alguna `DonationIntent` de la convocatoria". Hoy ningún activo puede asociarse a una convocatoria (ADR-029 pendiente) y el efectivo está excluido (P5). Cuando lleguen ADR-029 o P5, esta comprobación debe ampliarse; queda registrado como dependencia (§11).

---

## 6. Autorización

**NUEVO:** `ConvocatoriaAuthorizationPolicy` en `convocatoria.application`, sobre `IdentityPrincipalPort` / `AuthorizationPrincipal` de `contracts` (EXISTE). No reutiliza `RoleAuthorizationPolicy` ni `OrganizationBoundaryPolicy` de `core` (ADR-037 §4). El módulo usa su propia representación de actor derivada de `AuthorizationPrincipal`; nunca `core.domain.event.ActorRef` (ADR-037 §6).

| Operación | Quién | Fuente |
|---|---|---|
| Crear convocatoria | `ADMINISTRATOR` de la organización, organización `VERIFIED` | ADR-037 §5 |
| Editar configuración (directa) | `ADMINISTRATOR` | Enmienda §3.2 |
| `AssignEmployeeToCampaign` | `ADMINISTRATOR`; destinatario `EMPLOYEE` de la misma organización; no autoasignación | Enmienda §4.1, §4.3 |
| `DesignateAdministratorAsCampaignResponsible` | `ADMINISTRATOR`; destinatario `ADMINISTRATOR` de la misma organización; autoasignación permitida y auditada | Enmienda §4.1, §4.3 |
| `RemoveResponsible` | `ADMINISTRATOR` | ADR-037 §5 |
| Cerrar convocatoria | `ADMINISTRATOR` | Enmienda §3.4 |
| Crear `DonationIntent` | Pública u opcional JWT; sin política de roles | ADR-037 §5 |

- En todas: el actor pertenece a la organización de la convocatoria.
- El respaldo del `REPRESENTATIVE` **no aplica** a ninguna de estas operaciones (ADR-037 §5).
- **Test:** `ConvocatoriaAuthorizationPolicyTest` (NUEVO): un caso positivo y uno negativo por fila, incluidos `REPRESENTATIVE` rechazado, `EMPLOYEE` rechazado y actor de otra organización rechazado.

---

## 7. Idempotencia

### 7.1 Comandos de escritura del módulo (Enmienda §3.5, B2, N12)

- **Alcance:** crear convocatoria, editar configuración, `AssignEmployeeToCampaign`, `DesignateAdministratorAsCampaignResponsible`, `RemoveResponsible`, cerrar convocatoria y crear `DonationIntent` (ampliación de B2, decisión ID del 2026-10-01, §7.3). La solicitud de cambio (N3) queda fuera de corte.
- **Mecanismo:** registro de comandos procesados **propio del módulo** (NUEVO). Mismo patrón que `core` (reclamo atómico por `commandId`), sin importar su código ni su colección.
- **Dentro de la misma transacción** que la escritura de dominio y el audit log. Si la transacción se revierte (fallo técnico o excepción de dominio), el reclamo se revierte con ella: solo quedan registradas las ejecuciones con éxito.
- **Resultado original (N12):** el registro guarda, junto al `commandId`, la referencia al resultado. Crear convocatoria guarda `campaignRef` y `publicCode`; los demás comandos guardan la referencia del efecto registrado (identificador de asignación, `campaignRef` cerrado, versión de configuración resultante). Ante un duplicado, se devuelve ese resultado sin repetir el efecto.
- **Orden dentro de la transacción:** reclamar `commandId` → si ya existía, devolver el resultado guardado sin ejecutar → si no, ejecutar y guardar el resultado en el mismo documento del reclamo antes del commit. Nunca queda visible un reclamo sin resultado.
- **Duplicados concurrentes:** dos ejecuciones simultáneas del mismo `commandId` colisionan en el reclamo (conflicto de escritura o, tras el commit de la primera, `DuplicateKey`). Ambos casos se tratan como **reintento de la transacción completa + lectura del resultado guardado**, nunca como error de dominio. No se copia el mapeo de `core` (`MongoProcessedCommandAdapter.java:30-40`), que convierte la colisión en `ConcurrencyConflictException`.
- **I1 — tipo de comando:** el registro guarda el tipo de comando. Un `commandId` ya usado por un comando de **otro tipo** se rechaza con excepción nombrada en lugar de devolver el resultado de otra operación. No compara el contenido del cuerpo (Enmienda §3.5 [ESTADO] se respeta); compara la operación.
- **No se compara el contenido** del comando duplicado con el original (Enmienda §3.5 [ESTADO]). El implementador no añade esa comprobación.

### 7.2 Idempotencia financiera (Enmienda §5.3)

- La transición `DonationIntent PENDING → CONFIRMED` es **condicional e idempotente** (escritura condicionada a `status = PENDING`; para `BANK_TRANSFER`, además, a no haber vencido). Devuelve si la transición se aplicó o no; nunca lanza el efecto dos veces.
- *(Sustituido por la Enmienda 2 §3.3: esta transición ya no es la barrera del efecto financiero; la barrera es el reclamo `APPLY_FUNDS` de la aplicación posterior, ver el punto siguiente.)*
- El `commandId` con que el orquestador invocará `clearFundsGenesis` se **deriva de forma determinista de la intención**; nunca se genera en cada entrega del webhook. La derivación vive con el orquestador (fuera de corte); este corte garantiza que `intentId` y `fundId` sean estables e inmutables.
- **Flujo vigente (Enmienda 2 §3; resumen §6.20):** la transición `PENDING → CONFIRMED` confirma la intención y **ya no es la barrera financiera**. La aplicación posterior consume una intención `CONFIRMED`; su barrera es el reclamo del comando de sistema `APPLY_FUNDS` (`commandId = intentId`), en espacio de claves separado de los comandos de cliente. `CONFIRMED` no significa fondos aplicados.
- *Registros anteriores (§6.17–§6.19, revisiones 2.3–2.5) superados por la Enmienda 2.*

### 7.3 Creación de `DonationIntent`

**Decisión humana del 2026-10-01 (ID, resumen §6.15):** la creación de `DonationIntent` es idempotente con el mismo mecanismo del módulo (§7.1).
- Clave: `commandId` obligatorio en el comando de creación.
- Persistencia: registro de comandos procesados del módulo, en la misma transacción que la intención y el audit log.
- Resultado original devuelto ante duplicado: `intentId` y `fundId`.
- Si la primera transacción se revirtió, no queda reclamo y el reenvío se ejecuta como nuevo.
- No existe clave de negocio natural (un donante puede donar legítimamente dos veces el mismo importe a la misma convocatoria); `paymentSessionId` es posterior (P3) y conserva su índice parcial.
- El contrato HTTP (cómo viaja el `commandId`) sigue siendo de ADR-041 §7-A.4; este corte solo fija el caso de uso.

---

## 8. Ledger y reglas monetarias

- **Contrato:** ADR-037 §2.2; Enmienda §3.1, §3.3.
- **Creación:** con la convocatoria si acepta `MONETARY`, o en la transición que añade `MONETARY`; misma transacción.
- **Operación de aplicación de fondos** (`applyFundsForIntent(intentId)`, invocada por el sistema: orquestador / recuperación de `app`, ADR-043): exige `CONFIRMED`; reclama la barrera `(APPLY_FUNDS, intentId)` **antes** del incremento; incrementa por política; guarda el resultado original. Se une a la transacción externa sin reintento interno y la marca para rollback; sin transacción activa abre la suya con reintento. Un rechazo permanente se marca después con `FUNDING_REJECTED` (Enmienda 2 §4). La composición con `Fund` y el outbox sigue siendo del orquestador:

| Política | Escritura condicional | Fuente |
|---|---|---|
| `FLEXIBLE` | `updateOne({campaignRef}, {$inc})` | ADR-037 §2.2 |
| `STRICT` | `updateOne({campaignRef, clearedAmount ≤ targetAmount − amount}, {$inc})` | ADR-037 §2.2 |
| `CLOSE_ON_TARGET` + `REJECT_EXCESS` | Igual que `STRICT`: rechaza completa la donación que superaría la meta | Enmienda §3.3 |
| `CLOSE_ON_TARGET` + `ACCEPT_EXCESS` | Sin condición de capacidad | Enmienda §3.1 |
| `CLOSE_ON_TARGET` + `CLOSE` | **DETENERSE** → R4 (§14.2) | — |

- `status` **no** forma parte del filtro (D1).
- `matchedCount == 0` sin conflicto transitorio → `CampaignNotFoundException` o `CampaignFundingLimitExceededException`, clasificados de forma determinista.
- El ledger no se usa como read model para contar donaciones.
- **Requisito transaccional del orquestador (fuera de corte, registrado aquí por exigencia de la enmienda §6):**
  - `TransactionalEventPublisher.appendAndOutbox` usa `@Transactional` con `Propagation.REQUIRED` (verificado por lectura de código).
  - El reintento de `STRICT` debe **envolver la transacción completa del orquestador**, no vivir dentro de ella, porque `clearFundsGenesis` tiene su propio bucle de reintentos y con `REQUIRED` un conflicto revierte toda la transacción externa.
  - **T1 (verificado por lectura, 2026-10-01):** dentro del orquestador, `clearFundsGenesis` **no puede** pasar por `CommandRetryTemplate`: reintentaría sobre una transacción que MongoDB ya abortó y, al agotarse, `ConcurrencyRetryExhaustedException` descarta la causa (`CommandRetryTemplate.java:24,35`), con lo que el reintento externo no reconocería el `TransientTransactionError`. Requiere un camino sin reintento interno en `core`. Contradice la frase "`CommandRetryTemplate` … reutilizado" de `convocatoria-resumen.md` §2. Dependencia de `core`/`app`; no es trabajo de este módulo.
  - **No verificado en ejecución.** Ese punto no se considera cerrado hasta que exista un test con Testcontainers que lo demuestre (ver §13.3).
- **Tests de este corte:** `CampaignFundingLedgerIntegrationTest` (NUEVO): una aplicación por política; `REJECT_EXCESS` con meta 1.000, acumulado 900 y donación de 200 → rechazada completa y acumulado sin cambio; ledger inexistente → `CampaignNotFoundException`; convocatoria solo `IN_KIND` → sin ledger.

---

## 9. DonationIntent y configuración

### 9.1 Creación (Enmienda §5.1)

- **Precondiciones:** `Convocatoria.status = OPEN`; organización `VERIFIED` (X1, puerto propio, §11); `MONETARY` aceptado y `paymentMethod` aceptado en la versión vigente; `paymentMethod ∈ {GATEWAY, BANK_TRANSFER}` en este corte; `currency` de la intención igual a la de la convocatoria (R1).
- `CASH`: rechazado con excepción nombrada que cite P5. El implementador **no** decide si el efectivo crea intención.
- **Fechas (decisión humana del 2026-10-01, resumen §6.15):** en este corte la ventana de fechas **no** se comprueba; la regla vigente es solo `status = OPEN` (Enmienda §3.4). P7 sigue abierto como regla futura.
- Registra la versión de configuración leída (prueba, no revalidación posterior; §6.8.3).
- `confirmationSource`: `PAYMENT_PROVIDER` para `GATEWAY`, `ORGANIZATION` para `BANK_TRANSFER` (N5, N7).
- `BANK_TRANSFER`: fija la fecha de expiración al crear; la duración es un parámetro de configuración de implementación (NUEVO), no una constante de dominio.
- Un cambio de configuración posterior no invalida intenciones creadas antes (§6.7). El cierre posterior tampoco (D1).

### 9.2 Transición `PENDING → CONFIRMED`

Ver §7.2. Solo `ADMINISTRATOR` de la organización (`ConvocatoriaAuthorizationPolicy.requireAdministratorOf`); `confirmedBy` es el actor autorizado; una intención de pasarela se rechaza (Enmienda 2 §3.1). Registra quién, cuándo, medio y referencia/evidencia (N8). Para `BANK_TRANSFER` vencida: no se aplica (rechazo con excepción nombrada). `CONFIRMED` no afirma que los fondos se hayan aplicado.

### 9.3 Lo que este corte no hace

- No invoca `clearFundsGenesis` ni toca `Fund` (orquestador de `app`, fuera de corte; Enmienda 2 §3.2, §6). La confirmación nunca toca el ledger.
- No crea el índice de unicidad de la referencia de pago (alcance pendiente del contrato de transferencia). Por eso la confirmación manual de `BANK_TRANSFER` no es desplegable todavía.
- No asigna `paymentSessionId` ni procesa `providerEventId` (P3).
- No decide qué pasa con el dinero de una transferencia llegada tras vencer (P1).

### 9.4 Tests

`CreateDonationIntentIntegrationTest` y `ConfirmDonationIntentIntegrationTest` (NUEVOS): precondiciones una a una; versión registrada; vencimiento; `CASH` rechazado; transición aplicada una sola vez ante dos confirmaciones (secuenciales y concurrentes), con aserción negativa de que la segunda no modifica nada (regla 2.5).

---

## 10. Cierre de convocatoria

- **Contrato:** Enmienda §3.4 (decisión humana §6.14).
- **Comportamiento:** operación explícita `OPEN → CLOSED`, escritura condicional sobre `status = OPEN`; exclusiva de `ADMINISTRATOR`; comando de escritura con idempotencia (§7.1).
- Cerrar una convocatoria ya `CLOSED` con un `commandId` distinto: rechazo con excepción nombrada NUEVA (la transición solo existe desde `OPEN`).
- Tras el cierre: no se crean nuevas intenciones (`CampaignClosedException`); no se admiten cambios de configuración; las intenciones existentes conservan su validez (D1).
- **Distinto de:** cierre por `CLOSE_ON_TARGET` + `CLOSE` (lo ejecutaría el orquestador; R4); vencimiento de una intención `BANK_TRANSFER`.
- No hay cierre automático por fecha ni reapertura.
- **Test:** `CloseConvocatoriaIntegrationTest` (NUEVO): cierre; duplicado con mismo `commandId` devuelve el resultado original; segundo cierre con otro `commandId` rechazado; intención nueva rechazada tras el cierre; intención creada antes sigue `PENDING` y confirmable.

---

## 11. Integraciones y dependencias externas

| Dependencia | Qué necesita Convocatoria | Qué NO implementa este plan | Estado |
|---|---|---|---|
| Dirección de dependencias Maven (B-1, aclaración técnica) | `convocatoria → contracts` es su **única** dependencia directa de proyecto. `convocatoria` **nunca** depende de `app`, `core` ni `identity` (restricción permanente; ArchUnit cubre `core` e `identity`, y Maven impide `app` porque `app` no está en su classpath) | — | Vigente |
| Composición en `app` (`app → convocatoria`) | **No se añade en el primer corte**: sin orquestador ni implementación real de `OrganizationVerificationPort`, el contexto completo de `app` no arranca con los servicios de `convocatoria` | El orquestador de `STRICT` y los adaptadores que requieran composición (p. ej. el de `OrganizationVerificationPort`, ADR-038) | **Se añadirá y será necesaria** cuando se implemente esa composición/orquestación (compilar y ejecutar la integración). No contradice la arquitectura: la dirección es `app → convocatoria`, igual que `app` ya depende de `core`, `identity`, `crypto`, `ai` y `api` |
| `IdentityPrincipalPort` / `AuthorizationPrincipal` (`contracts`) | Resolver actor, organización y roles | Nada en `identity` | EXISTE |
| X1 — verificación de la organización | Comprobar `VERIFIED` al crear convocatoria e intención | El dato y su fuente (ADR-038); el adaptador en `app` | **Verificado: el dato no existe** (`Organization.java:16-25`). Diseño: puerto de salida **propio de `convocatoria`** (no se amplía `AuthorizationPrincipal`); la precondición vive en el dominio; tests con un fake. Dependencia de ejecución: sin implementación real del puerto, crear convocatoria/intención no es usable de punta a punta hasta ADR-038 |
| X2 — rol del destinatario de una asignación | Comprobar que el destinatario es `EMPLOYEE`/`ADMINISTRATOR` de la organización | Puerto de miembros de Identity | **Verificado:** `IdentityPrincipalPort.resolvePrincipal(destinatario)` sirve sin cambiar el contrato (`IdentityPrincipalPortImpl.java:30-58`). Dos huecos de Identity, comunicados como dependencia de ADR-038: no filtra `INACTIVE`; una cuenta inexistente lanza `AccountNotFoundException` de `identity`, no capturable desde `convocatoria` por tipo (necesita una excepción en `contracts`). **Comportamiento en este corte (sin decisión nueva):** si el principal resuelto no pertenece a la organización de la convocatoria o no tiene el rol exigido, `convocatoria` rechaza con su excepción nombrada de destinatario no válido. Lo que el puerto lance por sí mismo (cuenta inexistente; cuenta inactiva en la rama ADR-038) se **propaga sin capturar ni renombrar**: el contrato de `IdentityPrincipalPort` no declara excepciones y `convocatoria` no puede nombrarlas sin importar `identity`. En los tests del módulo el puerto es un fake, así que este caso no es alcanzable en el primer corte. Se resuelve en ADR-038 (excepción en `contracts`) |
| ADR-032 + P6 | Implementar el puerto contextual | El puerto y su adaptador | Fuera de corte |
| ADR-029 — `campaignRef` en `PhysicalAsset` | Nada en este corte. Dependencia exacta: Camino A, heredado del `Fund`; Camino B, recibido en el comando; split, heredado del padre; inmutable tras el registro; nunca inferido mediante proyecciones. Cuando exista, la comprobación de "primera donación" (H1) debe considerar activos en especie. | Nada de `core` | Fuera de corte |
| Orquestador de la aplicación de fondos y recuperación (`app`) | Que la barrera y el ledger sean invocables dentro de una transacción externa (hecho), y una consulta de intenciones recuperables (hecho) | El orquestador, el disparo inmediato y el scheduler (ADR-043) | Fuera de corte; bloqueado por ADR-038 (`OrganizationVerificationPort`) y por T1/P8 en `core` (Enmienda 2 §6) |
| `clearFundsGenesis` (`core`) | — | Su idempotencia (pendiente de `core`) | Fuera de corte |
| ADR-041 | Contratos HTTP (P4), `commandId` en los cuerpos (Enmienda §7.3), transporte del `commandId` de creación de intención (§7-A.4; la idempotencia del caso de uso ya está decidida, §7.3) | Endpoints | Fuera de corte |
| `core` (T1) | Camino sin reintento interno de `clearFundsGenesis` para el orquestador, conservando la causa transitoria | El cambio en `core` | Fuera de corte; condición previa del orquestador (§8) |
| `core`/ADR-040 (N10) | Conservar `paymentMethod` y `confirmationSource` en la intención | Vista de seguimiento e IA | Fuera de corte |

---

## 12. Tests requeridos

### 12.1 Arquitectura — NUEVO

`ConvocatoriaArchitectureTest` (ArchUnit): `convocatoria` no depende de `core` ni de `identity`; `convocatoria.domain` no importa `org.springframework.*` ni `com.mongodb.*` (mismo criterio que `core`).

### 12.2 Unitarios de dominio

`ConvocatoriaTest`, `CampaignAssignmentTest`, `DonationIntentTest`, `ConvocatoriaAuthorizationPolicyTest` (§3, §6).

### 12.3 Integración (Testcontainers MongoDB real)

Los listados en §5, §8, §9.4 y §10, más:
- Idempotencia (§7.1), un test por comando: dos envíos con el mismo `commandId` → un solo efecto (un solo documento, un solo incremento del contador, una sola entrada de audit log) y el mismo resultado devuelto; para crear convocatoria, el mismo `campaignRef` y `publicCode`.
- Reversión: un comando que falla por regla de dominio no deja registro en el registro de comandos procesados; reenviar el mismo `commandId` vuelve a ejecutarse.
- Audit log: orden y número exacto de entradas por escenario (precedente: Tarea 4.10 de Fase 4), incluida la marca de autoasignación.

### 12.4 Aserciones negativas obligatorias (regla 2.5)

En todas las ramas excluyentes: rechazo por invariante → `verify(..., never())` sobre el adaptador de escritura y comprobación de que el contador y el audit log no cambiaron; duplicado → ninguna escritura de dominio nueva.

---

## 13. Tests de concurrencia y transacciones

### 13.1 Dentro de este corte (obligatorios, con concurrencia real forzada, p. ej. `CyclicBarrier`, como en la Tarea 4.9)

| Escenario | Resultado esperado |
|---|---|
| Mismo `EMPLOYEE` asignado a dos convocatorias a la vez | Una asignación activa; la otra `EmployeeAlreadyAssignedException` |
| Mismo `ADMINISTRATOR` designado en dos convocatorias a la vez | Ambas activas |
| Dos retiros concurrentes que dejarían la convocatoria sin responsable | Uno aplicado; el otro `LastResponsibleRemovalWithoutReplacementException`; contador ≥ 1 |
| Dos ediciones de configuración concurrentes sobre la misma versión | Una aplicada; la otra conflicto de versión, sin sobrescritura |
| Dos aplicaciones de fondos `STRICT` que juntas superan la meta | Solo una aplicada; acumulado ≤ meta |
| Dos confirmaciones concurrentes de la misma intención | Transición aplicada una sola vez |
| Dos aplicaciones concurrentes de la misma intención `CONFIRMED` | Un solo incremento y un solo reclamo |
| Aplicación y marca de `FUNDING_REJECTED` en carrera | Nunca aplicada y rechazada a la vez |
| Mismo `commandId` enviado en paralelo (crear convocatoria y crear intención) | Un solo efecto; ambos reciben el resultado original |
| Mismo `commandId` usado por dos tipos de comando distintos | El segundo se rechaza con excepción nombrada; el primero no cambia (I1) |
| Reintento ante `TransientTransactionError` | Se reintenta y no duplica efectos; una excepción de dominio no se reintenta (a lo sumo un intento) |

### 13.2 Reversión

Un fallo forzado dentro de cada transacción de §4.4 revierte todas sus escrituras, incluido el reclamo del `commandId`.

### 13.3 Fuera de este corte (registrado como condición de cierre del orquestador)

Test con Testcontainers del orquestador de la aplicación de fondos (ADR-043) que demuestre que el reintento envuelve la transacción completa y que la génesis (`appendAndOutbox`, `REQUIRED`) y el outbox se revierten junto con el ledger y el reclamo de la barrera. Hasta que exista y pase, el requisito de Enmienda §6 sigue **no verificado**.

---

## 14. Pendientes y cómo afectan al plan

### 14.1 P1–P7 (no se cierra ninguno)

| Pendiente | Estado | ¿Bloquea qué? | Qué NO debe decidir el implementador |
|---|---|---|---|
| P1 — Dinero no aceptable (H-D4.3) | Abierto | Intenciones `FUNDING_REJECTED` (su dinero queda sin registro: no se habilita dinero real sin P1, Enmienda 2 §4); Registro de dinero no aceptable; rechazo en el orquestador; transferencia llegada tras vencer | El mecanismo de resolución (devolución, conciliación u otro), los datos financieros o de PII que se guardan, ni ningún estado de ese registro |
| P2 — Validación de `IN_KIND` | Abierto | Registro en especie del Camino B | Dónde se valida ni un puerto nuevo hacia `core` |
| P3 — Webhook y proveedor | Abierto | Adaptador del webhook; asignación de `paymentSessionId`; índice de `providerEventId` | Proveedor, contrato del webhook, alcance del índice |
| P4 — Contrato HTTP de asignación | Abierto | Endpoints de asignación | Rutas, cuerpos y respuestas |
| P5 — Efectivo (`CASH`) | Abierto | Cualquier flujo de efectivo | Si `CASH` crea `DonationIntent`, dónde se valida, qué versión de configuración se asocia |
| P6 — Respaldo del `REPRESENTATIVE` | Abierto | Adaptador del puerto contextual | La condición de activación del respaldo |
| P7 — Ventana de fechas | Abierto (en el primer corte las fechas no limitan, decisión del 2026-10-01) | Cualquier restricción por fechas | Comprobar fechas al crear intenciones o en el descubrimiento |

### 14.2 Puntos fuera de P1–P7: estado tras la revisión del 2026-10-01

| ID | Punto | Estado | Fuente |
|---|---|---|---|
| R1 | Datos mínimos de `Convocatoria` | **Cerrado** (decisión humana): §3.1 | Resumen §6.15 |
| R2 | Convocatoria con 0 responsables al crear | **Cerrado** por ADR-037 §2.5: §3.3 | ADR-037 §2.5 |
| R3 | Solicitud de cambio de configuración | **Cerrado como fuera del primer corte** (decisión humana). Quién aprueba ya está decidido (Enmienda §3.2: otro `ADMINISTRATOR` o el `REPRESENTATIVE`, distinto del solicitante; sin aprobador válido, el cambio se bloquea) y el conflicto de versión también (§3.2). Quedan abiertos: quién solicita, quién rechaza, retirada por el solicitante, cancelación al cerrar | Resumen §6.15 |
| R4 | `CLOSE_ON_TARGET` + `CLOSE` | Abierto; integración posterior con el orquestador. Rama `CLOSE` fuera de corte | — |
| X1 | Verificación de organización | Diseño cerrado (§11); dato pendiente de ADR-038 | Código verificado |
| X2 | Rol del destinatario | Diseño cerrado (§11); dos huecos para ADR-038 | Código verificado |
| ID | Idempotencia de creación de intención | **Cerrado** (decisión humana): §7.3 | Resumen §6.15 |
| I1 | `commandId` reutilizado entre tipos de comando | Decisión de implementación: §7.1 | — |
| T1 | Reintento dentro del orquestador | Dependencia de `core`/`app`: §8 | Código verificado |

---

## 15. Orden exacto de implementación

Ramas propuestas (no se abren hasta la aprobación de cada bloque). Cada tarea termina con `mvn test -pl convocatoria` completo y su resumen literal de Surefire.

| Tarea | Rama | Contenido | Depende de | Condición |
|---|---|---|---|---|
| 0 | — (solo lectura) | Reverificar §2 contra el HEAD vigente (incluida la rama de ADR-038, si ya está integrada); configuración de test transaccional de `core`; si `@CompoundIndex` admite `partialFilter` en la versión de Spring Data del proyecto (si no, índices parciales vía `IndexOperations`); si existe CI con lista explícita de módulos | — | Una discrepancia con §2 → reportar antes de seguir |
| 1 | `feat/convocatoria-module-scaffolding` | Módulo Maven (`pom.xml` raíz y `convocatoria/pom.xml`), `ConvocatoriaArchitectureTest`, configuración de test con Testcontainers replica set. **En este corte no se añade `app → convocatoria`** (aclaración técnica B-1, §11): `app` escanea `com.traceability` y su `ApplicationContextLoadTest` arrancaría los servicios de `convocatoria` sin implementación de `OrganizationVerificationPort`, que este corte no construye. No es una prohibición permanente: ver §11, fila "Composición en `app`" | 0 | Reactor completo en verde; `app/**` sin cambios |
| 2 | `feat/convocatoria-domain` | Dominio puro de §3 (con R1) y excepciones nombradas; tests unitarios | 1 | — |
| 3 | `feat/convocatoria-persistence` | Puertos, documentos, mappers, adaptadores, índices; registro de comandos procesados (con tipo y resultado, §7.1); audit log; helper de reintento | 2 | — |
| 4 | `feat/convocatoria-authorization` | `ConvocatoriaAuthorizationPolicy`; puerto propio de verificación de organización (X1); validación del destinatario con `resolvePrincipal` (X2) | 2 | — |
| 5 | `feat/convocatoria-campaign-lifecycle` | Crear convocatoria (con ledger), edición directa, cierre; idempotencia | 3, 4 | — |
| 6 | `feat/convocatoria-assignment` | Asignar, designar, retirar; contador; concurrencia | 3, 4 | — |
| 7 | `feat/convocatoria-donation-intent` | Resolución de `publicCode`, creación de intención idempotente (§7.3), transición `PENDING → CONFIRMED` | 5 | — |
| 8 | `feat/convocatoria-ledger` | Aplicación de fondos por política (sin rama `CLOSE`) | 7 | Rama `CLOSE` solo con R4 decidido |
| 9 | `feat/convocatoria-integration-tests` | Escenarios completos de negocio, audit log exacto, concurrencia de §13.1, reactor completo | 5–8 | — |
| — | `feat/convocatoria-config-change-request` | Solicitud de cambio (N3) | — | **Fuera del primer corte** (R3, §14.2) |

Cambio de orden respecto de la revisión 1: el ledger pasa detrás de las intenciones (antes no tenía quien lo invocara dentro del corte) y la solicitud de cambio sale del corte. La numeración de tareas cambia en consecuencia.

---

## 16. Criterios de aceptación / Definition of Done

Por tarea:
1. Cada clase pública cita en su Javadoc la decisión de ADR-037 o de la Enmienda que implementa (regla 2.1).
2. Ningún campo, comando, estado o excepción fuera de este plan. Si falta algo, se reporta; no se añade.
3. Output literal de Surefire de `mvn test -pl convocatoria` completo (`Tests run: X, Failures: Y, Errors: Z`). Nada de `-DskipTests`.
4. Tests de concurrencia con concurrencia real forzada, no simulada, donde §13 lo exige.
5. Aserciones negativas en ramas excluyentes (§12.4).
6. Ningún TODO, mock permanente ni placeholder sin marcar con su razón y el pendiente que lo bloquea (P1–P7, R3–R4, X1–X2, T1). El fake del puerto de verificación de organización (X1) es de test, no de producción.
7. Resumen de decisiones de implementación entregado para revisión humana: nombres de excepciones, nombres provisionales de clases y colecciones, firmas, parámetro de vencimiento de `BANK_TRANSFER`.
   *Nota de la consolidación (2026-10-03):* §17 recoge, como descripción del código, las firmas, las excepciones, las colecciones y el reintento implementados. **No sustituye la revisión humana** que exige este criterio.

Para el corte completo:
8. `mvn clean test` del reactor completo (8 módulos) en verde desde la raíz, con output literal. La regla 3.2 menciona "cuatro módulos"; el criterio aplicable es el reactor completo.
9. `estado-fase6.md` actualizado solo con lo que la ejecución de esa sesión demuestre (regla 2.4).
10. Nada fuera de corte (§1.3) aparece implementado.

---

## 17. Primer corte implementado: contrato interno de aplicación y modelo físico (anexo descriptivo)

*Sección añadida por la consolidación documental del 2026-10-03.*

**Naturaleza:** descriptiva, no normativa.
- Describe lo que el código del primer corte implementa. **No introduce ni aprueba ninguna decisión.**
- Donde difiera de ADR-037 consolidado, prevalece el ADR y la diferencia se reporta (regla 2.1).
- No sustituye la revisión humana del criterio 7 de §16.

**Procedencia:**
- Copiado de `auditoria-documental-convocatoria.md` (2026-10-03): Parte 3 (API contractual, tabla A), Parte 5 (MongoDB real) y "BD HANDOFF". Esa auditoría se conserva intacta como evidencia.
- La consolidación comprobó de nuevo contra el código, en solo lectura:
  - los siete nombres de colección;
  - los nombres de índice `uq_public_code`, `uq_fund_id`, `uq_payment_session_id` y `uq_active_employee_assignment`;
  - los nombres de las clases de comando;
  - los parámetros de reintento.
- El resto se copia sin reverificar.

### 17.0 Estructura real del módulo y dependencias

*Descriptivo. Verificado en `convocatoria/pom.xml` y `convocatoria/src/main/java` el 2026-10-03.*

**Dependencias del `pom.xml`:**
- Proyecto: solo `contracts` (B-1; ADR-037 §2).
- Librerías: `spring-boot-starter`, `spring-boot-starter-data-mongodb`.
- Test: `spring-boot-starter-test`, Testcontainers (`junit-jupiter`, `mongodb`) y `archunit-junit5`.

`ConvocatoriaArchitectureTest` prohíbe depender de `core`, `identity` y `app`.

**Paquetes (raíz `com.traceability.convocatoria`):**

| Paquete | Contenido |
|---|---|
| `domain.model` | `Convocatoria`, `ConvocatoriaConfiguration`, `CampaignAssignment`, `CampaignResponsibleState`, `CampaignFundingLedger`, `DonationIntent`, `OrganizationVerification` |
| `domain.model` (enums) | `ConvocatoriaStatus {OPEN, CLOSED}`; `Visibility {PUBLIC, PRIVATE_LINK}`; `TargetPolicy {FLEXIBLE, STRICT, CLOSE_ON_TARGET}`; `OnTargetReached {CLOSE, REJECT_EXCESS, ACCEPT_EXCESS}`; `DonationType {MONETARY, IN_KIND}`; `PaymentMethod {GATEWAY, BANK_TRANSFER, CASH}`; `ConfirmationSource {PAYMENT_PROVIDER, ORGANIZATION}`; `ActingRole {EMPLOYEE, ADMINISTRATOR}`; `AssignmentStatus {ACTIVE, REMOVED}`; `DonationIntentStatus` (§17.4) |
| `domain.exception` | 42 excepciones nombradas, raíz `ConvocatoriaDomainException` (regla 2.6) |
| `application.command` | Comandos y resultados (§17.1) |
| `application.service` | `ConvocatoriaLifecycleService`, `ResponsibleAssignmentService`, `DonationIntentService`, `CampaignFundingLedgerService`, `ConvocatoriaTransactionRetryHelper` |
| `application.authorization` | `ConvocatoriaAuthorizationPolicy`, `ConvocatoriaActor` (sobre `IdentityPrincipalPort` de `contracts`) |
| `application.idempotency` | `IdempotentCommandExecutor`, `ProcessedCommand`, `CommandClaimCollisionException`, `CommandType` |
| `application.audit` | `ConvocatoriaAuditEntry`, `ConvocatoriaAuditAction` |
| `application.port.out` | `ConvocatoriaRepositoryPort`, `CampaignAssignmentRepositoryPort`, `CampaignResponsibleStatePort`, `CampaignFundingLedgerRepositoryPort`, `DonationIntentRepositoryPort`, `ProcessedCommandPort`, `ConvocatoriaAuditLogPort`, `OrganizationVerificationPort` |
| `infrastructure.persistence.mongo.{document,mapper,adapter}` | Siete documentos, sus mappers manuales y siete adaptadores `Mongo*` sobre `MongoTemplate` |

**`CommandType`:**
- Comandos de cliente: `CREATE_CONVOCATORIA`, `EDIT_CONFIGURATION`, `ASSIGN_EMPLOYEE_TO_CAMPAIGN`, `DESIGNATE_ADMINISTRATOR_AS_CAMPAIGN_RESPONSIBLE`, `REMOVE_RESPONSIBLE`, `CLOSE_CONVOCATORIA`, `CREATE_DONATION_INTENT`.
- Comando de sistema: `APPLY_FUNDS` (`isSystem() = true`).

**`OrganizationVerificationPort.isVerified(organizationRef)`:** es un puerto propio del módulo (X1). En el módulo solo existe un fake de test; la implementación de producción depende de ADR-038.

**Audit log (`ConvocatoriaAuditAction`):** registra `CONVOCATORIA_CREATED`, `CONFIGURATION_EDITED`, `EMPLOYEE_ASSIGNED`, `ADMINISTRATOR_DESIGNATED`, `RESPONSIBLE_REMOVED`, `CONVOCATORIA_CLOSED` y `DONATION_INTENT_CREATED`.
- **Sin entrada de audit log:** la confirmación de intenciones, la aplicación de fondos y la marca de `FUNDING_REJECTED`.
- Trazabilidad de `FUNDING_REJECTED`: pendiente P10 (Enmienda 2 §7, BORRADOR).
- Trazabilidad de la confirmación y de la aplicación: NO DEFINIDA EN LA DOCUMENTACIÓN ACTUAL más allá de los campos `confirmedBy`/`confirmedAt` de la intención y del reclamo `APPLY_FUNDS`.

**Dependencias externas:** ver §11. No se repiten aquí.

### 17.1 Contrato interno de aplicación (operaciones del módulo)

- **Sin HTTP:** ninguna operación tiene controlador HTTP. El contrato HTTP pertenece a la Capa 5 (ADR-041, `api-contract-matrix.md`; P4) y no se define aquí.
- **Autenticación:** el `actorAccountId` de los comandos deberá salir del JWT (`sub`). JWT pertenece a ADR-038.

| # | Operación | Comando (código) | Resultado (código) | Excepciones relevantes (código) | Caso de uso | Idempotencia | Fuente normativa |
|---|---|---|---|---|---|---|---|
| A1 | Crear convocatoria | `CreateConvocatoriaCommand(commandId, actorAccountId, organizationRef, title, description, visibility, startDate, endDate, configuration{acceptedDonationTypes, acceptedPaymentMethods, currency, targetAmount, targetPolicy, onTargetReached})` | `{campaignRef, publicCode}` | `OrganizationNotVerifiedException`, `ActorRoleNotAllowedException`, `ActorNotInCampaignOrganizationException`, `CampaignTitleRequired/TooLong`, `InvalidCampaignDateRange`, `IncompleteMonetaryConfiguration`, `EmptyAcceptedDonationTypes`, `InvalidTargetAmount`, `CommandIdReusedForDifferentCommand` | `ConvocatoriaLifecycleService.createConvocatoria` | `commandId` del cliente | ADR-037 §2.1, §2.7; §6.15 R1 |
| A2 | Asignar empleado | `AssignEmployeeToCampaignCommand(commandId, actorAccountId, campaignRef, employeeRef)` | `{assignmentId}` | `EmployeeAlreadyAssignedException`, `ResponsibleAlreadyActiveInCampaignException`, `EmployeeSelfAssignmentNotAllowedException`, `InvalidResponsibleRecipientException`, `CampaignClosedException` | `ResponsibleAssignmentService.assignEmployee` | Sí | ADR-037 §2.4, §5 |
| A3 | Designar administrador como responsable | `DesignateAdministratorAsCampaignResponsibleCommand(commandId, actorAccountId, campaignRef, administratorRef)` | `{assignmentId}` | `ResponsibleAlreadyActiveInCampaignException`, `InvalidResponsibleRecipientException` | `ResponsibleAssignmentService.designateAdministrator` | Sí | ADR-037 §2.4, §5 |
| A4 | Retirar responsable (con o sin reemplazo) | `RemoveResponsibleCommand(commandId, actorAccountId, campaignRef, responsibleRef, replacementRef, replacementActingRole)` | `{removedAssignmentId, replacementAssignmentId}` | `LastResponsibleRemovalWithoutReplacementException`, `ReplacementActingRoleRequiredException`, `AssignmentAlreadyRemovedException`, `ResponsibleAssignmentNotFoundException` | `ResponsibleAssignmentService.removeResponsible` | Sí | ADR-037 §2.5; G2 (pendiente de registro, `convocatoria-resumen.md` §0.4) |
| A5 | Editar configuración | `EditConfigurationCommand(commandId, actorAccountId, campaignRef, expectedConfigurationVersion, newConfiguration)` | `{campaignRef, configurationVersion}` | `ConfigurationVersionConflictException`, `ConfigurationChangeOnClosedCampaignException`, `MonetaryTermsChangeNotSupportedException`, `CampaignAlreadyHasDonationsException` | `ConvocatoriaLifecycleService.editConfiguration` | Sí, además de la concurrencia optimista por versión | ADR-037 §2.1 |
| A6 | Cerrar convocatoria | `CloseConvocatoriaCommand(commandId, actorAccountId, campaignRef)` | `{campaignRef}` | `CampaignAlreadyClosedException` | `ConvocatoriaLifecycleService.closeConvocatoria` | Sí | ADR-037 §2.1, §5 |
| A7 | Detalle público | — (no existe `ConvocatoriaReadPort`) | Campos de `ConvocatoriaReadModel` **NO DEFINIDOS** | NO DEFINIDO | No implementado | n/a | §1.3 (fuera de corte) |
| A8 | Descubrimiento público | — | NO DEFINIDO | — | No implementado | n/a | §1.3 |
| A9 | Listado administrativo | — | `ConvocatoriaAdminReadModel` (campos en `api-contract-matrix.md` §2b) | NO DEFINIDO | No implementado; lectura multimódulo (`fullName` de Identidad) | n/a | §1.3 |
| A10 | Crear `DonationIntent` | `CreateDonationIntentCommand(commandId, publicCode, donorRef, amount, currency, paymentMethod)` | `{intentId, fundId}` | `CampaignClosedException`, `OrganizationNotVerifiedException`, `DonationTypeNotAcceptedException`, `PaymentMethodNotAcceptedException`, `DonationCurrencyMismatchException`, `InvalidDonationAmountException`, `CashDonationIntentNotSupportedException` (P5) | `DonationIntentService.createDonationIntent` | Sí (`commandId`; decisión ID) | ADR-037 §2.6; §6.15 ID |
| A11 | Confirmación manual (`BANK_TRANSFER`) | `ConfirmDonationIntentCommand(intentId, actorAccountId, reference)` | `boolean` | `GatewayIntentManualConfirmationNotAllowedException`, `DonationIntentExpiredException`, `DonationIntentNotFoundException`, `IncompleteConfirmationException`, `ActorRoleNotAllowedException` | `DonationIntentService.confirmDonationIntent` | Transición condicional `PENDING → CONFIRMED`; sin `commandId` | ADR-037 §2.6 (E1 §5.2); la autorización y el rechazo de pasarela siguen D5 (§6.20) y la Enmienda 2 §3.1, en **BORRADOR** (CD-03) |
| A12 | Consulta del estado de una intención | — | — | — | No implementado | n/a | Pendiente |

**Operaciones de sistema** (sin actor humano; invocadas por el futuro orquestador y el scheduler de `app`, que no existen):
- `CampaignFundingLedgerService.applyFundsForIntent(intentId)` → `ApplyFundsResult` (`appliedNow = false` ante un duplicado);
- `rejectFundingIfPermanentlyUnfundable`;
- `findConfirmedPendingApplication(limit)`.

Fuente: decisiones humanas de §6.20 (D1, D2, D3, P9) y Enmienda 2 §3–§5 (**BORRADOR**). El mecanismo de `app` que las invocará se describe en ADR-043 (**Propuesto**), documento de otro bloque (`convocatoria-resumen.md` §6.21).

### 17.2 Modelo físico MongoDB

**Mecanismo:**
- Índices declarados con `@Indexed`/`@CompoundIndex`.
- En producción los crearía `auto-index-creation: true` de `app`. Hoy no se crean, porque `app` no incluye `convocatoria` (B-1).
- En los tests los crea `ConvocatoriaTestIndexes`.
- No hay `MongoRepository`: todo pasa por `MongoTemplate`.

| Colección (real) | `_id` | Índices | Propósito y notas |
|---|---|---|---|
| `convocatorias` | `campaignRef` | `uq_public_code` (`publicCode`, único) | Campaña y configuración versionada (`configurationVersion`, concurrencia optimista). `status` `OPEN/CLOSED`. Sin timestamps de creación |
| `campaign_assignments` | `assignmentId` | `uq_active_employee_assignment`: `{employeeRef:1}`, único y parcial `{status:'ACTIVE', actingRole:'EMPLOYEE'}` | Responsables (`EMPLOYEE` o `ADMINISTRATOR`). `assignedAt`, `removedAt`, `assignedBy`, `selfAssigned` |
| `campaign_responsible_state` | `campaignRef` | — | Contador de "≥1 responsable" (`upsert $inc`; decremento condicional `$gt:1`) |
| `campaign_funding_ledgers` | `campaignRef` | — | `clearedAmount` (`$inc`, condicional según la política). Existe solo con `MONETARY` (N2). Se borra si se quita `MONETARY` en la edición directa (G1, pendiente de registro) |
| `donation_intents` | `intentId` | `uq_fund_id` (`fundId`, único); `uq_payment_session_id` (único, parcial `{paymentSessionId: {$type:'string'}}`) | `status` con cinco valores en el código (incluido `FUNDING_REJECTED`, BORRADOR); `confirmedBy`, `confirmedAt`; `expiresAt` solo para `BANK_TRANSFER` |
| `convocatoria_processed_commands` | Cliente: `commandId` (string). Sistema: `{commandType, commandId}` (subdocumento) | Solo `_id` | Idempotencia de comandos de cliente (resultado original guardado, I1) y barrera `APPLY_FUNDS` (`commandId = intentId`). Reclamo con `findAndModify` + `upsert` + `setOnInsert` |
| `convocatoria_audit_log` | `entryId` | — | Append-only. `sequence` sale de un contador en memoria del proceso: es monótona dentro de cada proceso, no global |

**Campos por colección** (clases `*Document`, verificado en el código el 2026-10-03; S = String, I = Instant):

| Colección | Campos |
|---|---|
| `convocatorias` | `_id = campaignRef`; `organizationRef` S; `publicCode` S; `title` S; `description` S; `visibility` S; `startDate` I; `endDate` I; `status` S; `acceptedDonationTypes` Set<S>; `acceptedPaymentMethods` Set<S>; `currency` S; `targetAmount` Long (nulo si solo `IN_KIND`); `targetPolicy` S; `onTargetReached` S; `configurationVersion` long |
| `campaign_assignments` | `_id = assignmentId`; `campaignRef` S; `employeeRef` S (responsable, `EMPLOYEE` o `ADMINISTRATOR`); `actingRole` S; `status` S; `assignedAt` I; `removedAt` I; `assignedBy` S; `selfAssigned` boolean |
| `campaign_responsible_state` | `_id = campaignRef`; `activeResponsibleCount` long |
| `campaign_funding_ledgers` | `_id = campaignRef`; `currency` S; `targetAmount` long; `targetPolicy` S; `onTargetReached` S; `clearedAmount` long |
| `donation_intents` | `_id = intentId`; `fundId` S; `organizationRef` S; `campaignRef` S; `donorRef` S; `amount` long; `currency` S; `paymentMethod` S; `confirmationSource` S; `configurationVersion` long; `paymentSessionId` S; `providerEventId` S; `expiresAt` I; `status` S; `confirmedBy` S; `confirmedAt` I; `confirmationPaymentMethod` S; `confirmationReference` S |
| `convocatoria_processed_commands` | `_id` (string `commandId` o subdocumento `{commandType, commandId}`); `commandType` S; `result` Map<S,S>; `processedAt` I |
| `convocatoria_audit_log` | `_id = entryId`; `action` S; `campaignRef` S; `actorRef` S; `targetRef` S; `selfAssigned` boolean; `commandId` S; `occurredAt` I; `sequence` long; `details` Map<S,S> |

**Índices declarados en el código.** Son los únicos, además de los `_id`:
- `uq_public_code`;
- `uq_active_employee_assignment`;
- `uq_fund_id`;
- `uq_payment_session_id`.

No existe índice de referencia de pago (§4.2: su alcance depende del contrato de transferencia) ni de `providerEventId` (P3).

**Relaciones lógicas** (sin claves foráneas físicas):
- `campaignRef` une las colecciones de dominio y el audit log;
- `organizationRef` y las cuentas (`employeeRef`, `assignedBy`, `confirmedBy`, `actorRef`) apuntan a Identidad;
- `donorRef` es opaco;
- `fundId` apunta al futuro `Fund` de `core`.

**Índices de rendimiento ausentes** (no rompen ninguna invariante): `donation_intents.status`, `donation_intents.campaignRef` y `convocatoria_audit_log.campaignRef`.

**"Un responsable activo por persona y convocatoria" (G3):**
- no tiene índice;
- se comprueba con una lectura previa dentro de la transacción;
- según la auditoría de origen, la serialización ante dos designaciones simultáneas es una inferencia, no una prueba.

### 17.3 Transacciones y reintento

- **Comandos de cliente:**
  - Cada uno se ejecuta dentro de `IdempotentCommandExecutor.execute`, sobre `ConvocatoriaTransactionRetryHelper`.
  - `TransactionTemplate` con **6 intentos totales** (`MAX_ATTEMPTS = 6`).
  - Espera entre intentos con jitter en [`10·2^(n-1)`, `50·2^(n-1)`] ms y tope de **500 ms** (`BASE_MIN_MILLIS = 10`, `BASE_MAX_MILLIS = 50`, `MAX_BACKOFF_MILLIS = 500`; verificado en el código el 2026-10-03).
  - Solo se reintentan fallos transitorios; las excepciones de dominio no se reintentan.
- **Operaciones de sistema** (`confirmDonationIntent`, `applyFundsForIntent`, `rejectFundingIfPermanentlyUnfundable`): llevan `@Transactional(SUPPORTS)`. Si hay una transacción externa se unen a ella **sin reintento interno** y la marcan para rollback. Si no la hay, usan el helper.
- Esta política concreta (cifras incluidas) no figura en ningún documento normativo. §4.4 solo dice "reintento acotado". Se registra aquí como descripción del código.

### 17.4 Estados y transiciones de `DonationIntent` implementados

*Descriptivo. Verificado en el código el 2026-10-03:*
- `DonationIntent.java:78,100-106`;
- `MongoDonationIntentRepositoryAdapter.java:55-77`;
- `CampaignFundingLedgerService.java:112-128`.

Fuente normativa: ADR-037 §2.6. `FUNDING_REJECTED` procede de la decisión humana D3 (`convocatoria-resumen.md` §6.20) y de la Enmienda 2 §4, en **BORRADOR**.

| Transición | Quién la produce | Condición atómica (escritura condicional) | Notas |
|---|---|---|---|
| (creación) → `PENDING` | `DonationIntentService.createDonationIntent` | Inserción. Idempotente por `commandId` (§7.3) | `expiresAt` solo para `BANK_TRANSFER` |
| `PENDING → CONFIRMED` | `DonationIntentService.confirmDonationIntent` (`confirmIfPending`) | `_id` + `status = PENDING` + (`expiresAt` ausente o `expiresAt > confirmedAt`) | Registra `confirmedBy`, `confirmedAt`, `confirmationPaymentMethod` y `confirmationReference`. No toca ledger, registro de comandos ni `Fund` |
| `CONFIRMED` → fondos aplicados (sin cambio de estado) | `CampaignFundingLedgerService.applyFundsForIntent` | Exige `CONFIRMED`; reclamo `(APPLY_FUNDS, intentId)` antes del incremento del ledger | La intención aplicada **sigue `CONFIRMED`**; la marca de aplicación es el reclamo (ADR-043, documento externo de otro bloque, "Consecuencias") |
| `CONFIRMED → FUNDING_REJECTED` | `CampaignFundingLedgerService.rejectFundingIfPermanentlyUnfundable` (`markFundingRejectedIfConfirmed`) | El servicio comprueba `CONFIRMED`, ausencia de reclamo `APPLY_FUNDS` y capacidad insuficiente permanente; el adaptador escribe condicionado a `status = CONFIRMED` | `CLOSE_ON_TARGET + CLOSE` no es rechazo permanente mientras R4 esté pendiente |
| `FAILED`, `EXPIRED_UNKNOWN` | Ninguna | — | Existen en el enum sin ninguna transición que los produzca: dependen de P3 (webhook), fuera del corte |

### 17.5 Consultas relevantes del módulo

*Descriptivo. Procedencia: `auditoria-documental-convocatoria.md` Parte 5; consulta de recuperación verificada en `MongoDonationIntentRepositoryAdapter.java:80-114`.*

- **Consultas simples:**
  - `findByPublicCode`: resolución interna de `publicCode`.
  - `existsByCampaignRef` sobre `donation_intents`: comprobación H1 de "primera donación" (§5). Sin índice de apoyo en `campaignRef`.
  - `findActiveByCampaignRefAndResponsible`: unicidad G3 por lectura previa.
  - `findByCampaignRef`: asignaciones ordenadas por `assignedAt` y audit log ordenado por `sequence`.
- **`findConfirmedPendingApplication(limit)`.** Es la consulta de recuperación: la parte que corresponde a `convocatoria` según la decisión D2 (`convocatoria-resumen.md` §6.20). El mecanismo que la usa se describe en ADR-043 (Propuesto), documento externo de otro bloque. Es una agregación sobre `donation_intents` con estas etapas:
  1. `match status = CONFIRMED`;
  2. `sort _id`;
  3. `$lookup` en `convocatoria_processed_commands` por la clave de sistema `{commandType: APPLY_FUNDS, commandId: intentId}`;
  4. descarta las que ya tienen reclamo;
  5. `$lookup` en `campaign_funding_ledgers`;
  6. excluye `CLOSE_ON_TARGET + CLOSE` (P9, opción a);
  7. `limit`.

  Cruza dos colecciones y su coste crece con el histórico (ADR-043, documento externo de otro bloque, "Consecuencias"). No hay índice de apoyo en `donation_intents.status`.
