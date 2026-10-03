# Auditoría de delimitación — Cierre del bloque Convocatoria (Fase 6)

**Fecha:** 2026-10-02. **Rama:** `develop`, HEAD `673eda9`; `convocatoria/` sin versionar. **Naturaleza:** informe temporal, solo lectura. No rediseña ni recomienda arquitectura nueva: delimita qué trabajo pertenece a Convocatoria.
**Decisiones cerradas:** F-1, F-2 y D1–D7 (confirmadas el 2026-10-02).

Taxonomía de evidencia: **A** código · **B** test (reproducido ahora) · **C** git · **D** documental · **E** inferencia · **F** contradicción · **G** no determinable.
Categorías: **CONVOCATORIA** · **DEPENDENCIA EXTERNA** · **DOCUMENTACIÓN** · **DECISIÓN HUMANA** · **NO DETERMINABLE**.

**Tests reproducidos ahora (B):** `mvn -o test -pl convocatoria` → `Tests run: 180, Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS`. No se añadió ningún test ni se creó ninguna sonda nueva.

---

## A. `processed_commands` como barrera (D1)

| Hecho | Evidencia |
|---|---|
| Colección `convocatoria_processed_commands`: `_id = commandId`, `commandType`, `result` (`Map<String,String>`), `processedAt` | A (`ProcessedCommandDocument`) |
| Reclamo atómico: `findAndModify(_id) + setOnInsert + upsert`, que devuelve el documento previo. Si hay `DuplicateKeyException`, lanza `CommandClaimCollisionException` (reintentable) | A (`MongoProcessedCommandAdapter.claim:35-47`) |
| `IdempotentCommandExecutor`: reclamar → si existía, devuelve el resultado guardado (N12) o rechaza si es de otro tipo (I1) → ejecutar → guardar el resultado. Todo dentro de `executeWithRetry` | A (`IdempotentCommandExecutor:33-50`) |
| Concurrencia real con el mismo `commandId`: un solo efecto, y ambos reciben el resultado original. Si la acción falla por regla de dominio, no queda reclamo | B (`concurrentSameCommandIdHasSingleEffectAndBothGetOriginalResult`, `duplicateReturnsOriginalResultWithoutRepeatingEffect`, `domainFailureLeavesNoClaimAndResendExecutesAgain`) |
| La Enmienda §3.5 fija un "alcance mínimo" de comandos de escritura, no una lista cerrada. La §2.1 descarta "un mecanismo de idempotencia propio de Convocatoria, distinto del de `core`" | D |

**¿Puede reutilizarse sin introducir una segunda semántica de idempotencia?** Sí en lo esencial: la semántica de "reclamar una clave única en la misma transacción y devolver el resultado original ante un duplicado" es exactamente la que necesita la barrera (E sobre A y B). Hay **tres condiciones** que el código actual no cumple:

| # | Condición | Evidencia | Categoría |
|---|---|---|---|
| A-1 | **Variante sin reintento interno.** `IdempotentCommandExecutor` siempre pasa por `executeWithRetry`, que no detecta si ya hay una transacción activa. Dentro de la transacción del orquestador, reintentar está prohibido (Enmienda §6). Hace falta una variante que se una a la transacción externa y propague el conflicto | A; D | CONVOCATORIA |
| A-2 | **Espacio de claves.** El `commandId` lo aporta el cliente (`CreateDonationIntentCommand`, operación pública "sin política de roles") y la creación devuelve `intentId` y `fundId` (A). El registro es un único espacio de claves con la regla I1 (otro tipo → `CommandIdReusedForDifferentCommandException`). Si la clave de la barrera se puede derivar del `intentId` o del `fundId`, **quien conozca ese identificador puede ocuparla antes** con un comando de otro tipo, y la aplicación de esa intención fallaría siempre por I1 (E sobre A). La clave del sistema debe quedar fuera del alcance de las claves de cliente | A, E | CONVOCATORIA (la forma de separarlas, DECISIÓN HUMANA si exige cambiar la validación de `commandId`) |
| A-3 | **Tipo nuevo de "comando del sistema".** Hace falta un `CommandType` nuevo para una operación que no inicia un cliente. Es compatible con el "alcance mínimo" de la Enmienda §3.5, pero hay que declararlo en la Enmienda 2 | D | DOCUMENTACIÓN |

**Bloqueo:** ninguno que rompa una regla vigente, si se cumplen A-1, A-2 y A-3.

---

## B. Transacción ledger + génesis (ADR-037 §2.2, §2.3)

| Pieza | Situación | Evidencia | Categoría |
|---|---|---|---|
| `MongoTransactionManager` común | Existe en `app` (`TraceabilityInfrastructureConfig`) | A; D (ADR-037 §2.3) | — |
| Participación de `convocatoria` | El incremento del ledger se une a la transacción externa y la marca para rollback (`@Transactional(SUPPORTS)`) | A, B | Existe |
| Participación de `core` | `appendAndOutbox` es `@Transactional` (`REQUIRED`) | A | Existe |
| Génesis sin reintento interno | `clearFundsGenesis` reintenta dentro (`CommandRetryTemplate`) y, al agotarse, descarta la causa transitoria (T1) | A; D (plan §8) | **DEPENDENCIA EXTERNA (`core`)** |
| Reclamo sin reintento interno | A-1 | A | CONVOCATORIA |
| Mensaje de outbox de la génesis | `clearFundsGenesis` pasa `List.of()` (`FundCommandService:94`). ADR-037 §2.3 exige un "mensaje de Outbox" en la transacción, sin definir cuál | A, D | **NO DETERMINABLE** → DOCUMENTACIÓN / DECISIÓN de `core` |
| Actor de la génesis | `SystemActor` existe y `core` no le comprueba roles | A | Existe (D6) |
| Composición | No hay orquestador en `app` | A | DEPENDENCIA EXTERNA (`app`) |

**¿Pueden formar la transacción que exige ADR-037?** El mecanismo existe (A). Faltan:
- en `core`, el camino sin reintento (T1);
- en `convocatoria`, A-1;
- la composición en `app`;
- la definición del mensaje de outbox (G).

---

## C. `app`: dónde puede vivir el disparo inmediato

| Hecho | Evidencia | Categoría |
|---|---|---|
| `app` no depende de `convocatoria` (`app/pom.xml`: 0 referencias). Su capa de servicios solo tiene `IntegrityVerificationUseCase` | A | — |
| **No existe ninguna implementación de producción de `OrganizationVerificationPort`** (solo `FakeOrganizationVerificationPort` en los tests). `identity` no tiene el dato de verificación. El plan §11 dice que sin ella el contexto de `app` no arranca con `convocatoria` (`ApplicationContextLoadTest`) | A, D | **DEPENDENCIA EXTERNA (ADR-038 / `identity` + adaptador en `app`)** |
| `IdentityPrincipalPort` tiene implementación en `identity` | A (plan §11: `IdentityPrincipalPortImpl`) | Existe |
| `convocatoria` no puede llamar a `app` y no tiene eventos: **el disparo inmediato solo puede salir de quien invoca la confirmación, desde `app`** | A, B (`ConvocatoriaArchitectureTest`) | DEPENDENCIA EXTERNA (`app`) |
| No hay endpoint ni contrato HTTP de confirmación manual: `api` solo tiene controladores públicos de lectura, y ni `api-contract-matrix` ni ADR-041 lo definen | A, D | DEPENDENCIA EXTERNA (`api`/`app`) + DOCUMENTACIÓN (ADR-041) |

---

## D. Scheduler de respaldo

| Precedente | Qué aporta | Reutilizable | Evidencia |
|---|---|---|---|
| `@EnableScheduling` en `TraceabilityApplication` | Infraestructura de scheduling de Spring ya activa en `app` | Sí, como infraestructura | A |
| `BlockchainAnchorProducer` (`app`) | `@Scheduled` + lotes con límite configurable + recuperación de trabajo atascado + transiciones condicionales ("benign no-op" con varias instancias) | **El patrón, no el código** (es específico de lotes Merkle) | A, C (`7a51ecb`, `793d4b8`) |
| `OutboxSagaCoordinator`, `ProjectionRetryScheduler` (`core`) | Sondeo y reintento de mensajes y proyecciones de `core` | No: dominio y módulo distintos | A |
| ADR-042 | Política de reintentos de proyecciones | No: su ámbito son las proyecciones de `core` | D |

**Diseño específico pendiente** (todo en `app`, salvo la consulta):
- qué consulta (pieza de `convocatoria`);
- tamaño de lote e intervalo;
- qué hacer con los rechazos permanentes (se apoya en el estado terminal de F);
- registro y observabilidad.

Lo regula el ADR propio de D2 (regla 3.5). **Categoría:** la consulta es CONVOCATORIA; el scheduler es DEPENDENCIA EXTERNA (`app`); el ADR es DOCUMENTACIÓN.

---

## E. Autorización

| Hecho | Evidencia | Categoría |
|---|---|---|
| `confirmDonationIntent()` no autoriza: `confirmedBy` es texto libre | A (`ConfirmDonationIntentCommand`, `DonationIntentService:112-128`) | CONVOCATORIA (falta) |
| `ConvocatoriaAuthorizationPolicy.requireAdministratorOf(accountId, org)` comprueba la organización (`ActorNotInCampaignOrganizationException`) y el rol `ADMINISTRATOR` (`ActorRoleNotAllowedException`) | A; B (`administratorOfTheOrganizationIsAuthorized`, `employeeIsRejected`, `representativeIsRejected`, `administratorOfAnotherOrganizationIsRejected`) | Reutilizable |
| Origen de la intención: `paymentMethod` (`GATEWAY`/`BANK_TRANSFER`/`CASH`) y `confirmationSource` (`PAYMENT_PROVIDER`/`ORGANIZATION`) | A | Reutilizable: no cambia `contracts` |
| Para aplicar la política, `confirmedBy` tiene que pasar a ser un `accountId` resoluble. Es un cambio en el comando interno de `convocatoria` | A | CONVOCATORIA |
| `IdentityPrincipalPort` no filtra cuentas `INACTIVE` | D (plan §11, X2) | DEPENDENCIA EXTERNA (ADR-038 / `identity`) |
| Con D5, ninguna intención `GATEWAY` llega a `CONFIRMED` en este corte (su camino es el webhook, P3) | A, D | DEPENDENCIA EXTERNA (P3) |
| Los tests actuales confirman intenciones `GATEWAY` con `confirmedBy = "provider"` | A, B | CONVOCATORIA (habrá que reescribirlos) |

---

## F. Estado terminal: convenciones y excepciones

**Convenciones existentes (A, enums de todos los módulos):**

| Enum | Valores |
|---|---|
| `DonationIntentStatus` | `PENDING`, `CONFIRMED`, `FAILED`, `EXPIRED_UNKNOWN` |
| `FundStatus` | `PLEDGED`, `CLEARED`, `FAILED`, `REFUNDED`, `FULLY_REFUNDED` |
| `AllocationStatus` | `REQUESTED`, `CONFIRMED`, `REVERSED` |
| `OutboxStatus` | `PENDING`, `QUARANTINED`, `COMPLETED` |
| `AnchorStatus` | `COLLECTING`, `PENDING`, `SUBMITTING`, `SUBMITTED`, `ANCHORED`, `STUCK`, `FAILED`, `ANCHOR_MISMATCH` |
| `ConvocatoriaStatus` / `AssignmentStatus` / `AccountStatus` | `OPEN`/`CLOSED`; `ACTIVE`/`REMOVED`; `ACTIVE`/`INACTIVE` |

- **Forma:** participio o adjetivo en `UPPER_SNAKE`. Los estados negativos o terminales son de dos tipos: participio simple (`FAILED`, `REVERSED`, `QUARANTINED`, `CLOSED`, `REMOVED`) o compuesto que califica el ámbito (`EXPIRED_UNKNOWN`, `ANCHOR_MISMATCH`).
- **`REJECTED` no existe en ningún módulo.**
- **`FAILED` no sirve:** significa "el sistema sabe que [el pago] falló" (ADR-041:75), y sus transiciones son del webhook (Javadoc de `DonationIntentStatus`, P3) (D).
- **Vocabulario documental de este caso:** "rechazado por STRICT/CLOSE_ON_TARGET + REJECT_EXCESS" y "dinero no aceptable" (Enmienda §3.3, D); `OnTargetReached.REJECT_EXCESS` (A).

**Nombre compatible:**
- Un participio derivado de "rechazar" respeta la convención y el vocabulario (D, A).
- Sin calificar (`REJECTED`) sería ambiguo frente al rechazo de un pago. Con calificación del ámbito, sigue el patrón de `EXPIRED_UNKNOWN` y `ANCHOR_MISMATCH` (E).
- **La cadena exacta: NO DETERMINABLE → DECISIÓN HUMANA.**

**Excepciones al aplicar los fondos de una intención `CONFIRMED`:**

| Excepción | Origen | Clase propuesta por la evidencia | Evidencia |
|---|---|---|---|
| `CampaignFundingLimitExceededException` | ledger (STRICT, REJECT_EXCESS) | **Permanente** → estado terminal | A: meta inmutable (`MonetaryTermsChangeNotSupportedException`) y el ledger no resta |
| `CloseOnTargetCloseNotSupportedException` | `CampaignFundingLedger.isCapacityLimited` | Permanente **solo mientras R4 siga sin implementar**. Al implementarse R4, la misma intención sería aplicable, y el estado terminal es irreversible | A; D (R4) → **NO DETERMINABLE / DECISIÓN HUMANA** |
| `CampaignNotFoundException` | ledger inexistente | No debería ocurrir: con intenciones existentes no se puede borrar el ledger (H1) | A (`ConvocatoriaLifecycleService:111-119`) → anomalía, **NO DETERMINABLE** |
| `InvalidFundingAmountException`, `InvalidFundGenesisException` (`core`) | importe ≤ 0, `organizationRef` nulo | Imposibles por construcción (lo valida `DonationIntent.create`) | A → anomalía |
| `DonationIntentNotFoundException` | intención inexistente | Anomalía | A |
| `CommandIdReusedForDifferentCommandException` | ocupación previa de la clave (A-2) | Ni de negocio ni transitoria | A, E → **NO DETERMINABLE** (se evita con A-2) |
| `TransientTransactionError` / `WriteConflict`, `CommandClaimCollisionException` | MongoDB / reclamo | **Transitoria** → reintentar la transacción completa | A (`ConvocatoriaTransactionRetryHelper.isRetryable`); D (Enmienda §6) |
| `ConcurrencyConflictException` / `ConcurrencyRetryExhaustedException` (`core`) | génesis | Transitoria, pero hoy oculta su causa (T1) | A; D (plan §8) |
| `DonationIntentExpiredException`, `IncompleteConfirmationException` | confirmación | No aplican a una intención ya `CONFIRMED` | A |

---

## G. Documentos obsoletos o contradictorios con D1–D7

| Documento | Motivo | Categoría |
|---|---|---|
| Enmienda 1 §5.3 (líneas 204–207) | La barrera deja de ser `PENDING → CONFIRMED` en la transacción del efecto (D1) | DOCUMENTACIÓN (Enmienda 2) |
| Enmienda 1 §5.2 N9 (línea 194) | "Se ejecuta como `CLEAR_FUNDS_AS_GENESIS`" queda obsoleto (F-2, D5) | DOCUMENTACIÓN (Enmienda 2) |
| Enmienda 1 §3.5 | Uso del registro para una operación del sistema; espacio de claves (A-2, A-3) | DOCUMENTACIÓN (Enmienda 2) |
| Enmienda 1 §3.3 / §8 P1 | Alcance: P1 fuera; no se habilita dinero real | DOCUMENTACIÓN (Enmienda 2) |
| ADR-037 §2.6 (lista de estados; paso 3) | Estado terminal nuevo; dos actos | DOCUMENTACIÓN (Enmienda 2) |
| ADR-041 (líneas 45, 75) y `api-contract-matrix.md:104` | Estado público nuevo; no existe el contrato de confirmación manual | DOCUMENTACIÓN |
| ADR nuevo de recuperación | Exigido por la regla 3.5 (D2, D7) | DOCUMENTACIÓN |
| `convocatoria-resumen.md` | Falta registrar F-1, F-2 y D1–D7. En §6.19, DH-3 (línea 501) da por hecho el acto combinado, y "F-1/F-2" (líneas 511–512) chocan con los nombres de las decisiones. La línea 167 y H-D4.6 (línea 178) pasan a ser históricas | DOCUMENTACIÓN |
| `implementation_plan.md` | §4.4 (línea 178), §7.2 (líneas 242–243), §8 (línea 261), §9.2 (línea 297, cita §6.17), §11, §13.3, §14.1; referencias de las revisiones 2.3–2.5 a §6.17 y §6.18 | DOCUMENTACIÓN |
| `estado-fase6.md` §3bis (líneas 70–79) | Describe la operación combinada; cita §6.18; colisión de nombres | DOCUMENTACIÓN (tras la ejecución) |
| `golden-path.md:30-33` | Webhook → génesis + ledger en un paso | DOCUMENTACIÓN |
| Javadocs: `CampaignFundingLedgerService`, `ConfirmDonationIntentCommand` ("La invocará el orquestador"), `CommandType` | Describen la operación combinada | CONVOCATORIA (junto con el código) |
| ADR-032 | `ADMINISTRATOR` ya está autorizado: no cambia | No tocar |
| Cuerpo de ADR-037, `fase-6-estructura…`, `contract-wiring-review.md`, informes de auditoría | Se enmiendan aparte, están superados (N11), son compatibles o son evidencia histórica | No tocar |

---

## Matriz 1 — Trabajo → módulo responsable

| Trabajo | Módulo | Categoría | Evidencia | Condición de cierre |
|---|---|---|---|---|
| Autorización `ADMINISTRATOR` en `confirmDonationIntent` (`confirmedBy` → `accountId` + `requireAdministratorOf`) | `convocatoria` | CONVOCATORIA | A (E) | Tests negativos sin cambio de estado |
| Rechazo de intenciones de pasarela en el camino manual, con excepción nombrada | `convocatoria` | CONVOCATORIA | A (E) | Test negativo |
| Aplicación desde `CONFIRMED` (redefinir `applyFundsForIntent`): reclamo de barrera → incremento del ledger; se une a la transacción externa | `convocatoria` | CONVOCATORIA | A (A, B) | Tests secuencial, concurrente, tras reinicio y en transacción externa |
| Variante sin reintento interno del reclamo (A-1) | `convocatoria` | CONVOCATORIA | A | Test dentro de una transacción externa |
| Espacio de claves del sistema separado del de los clientes (A-2) | `convocatoria` | CONVOCATORIA (+ DECISIÓN HUMANA sobre la forma) | A, E | Test: una clave de cliente no puede ocupar la de la barrera |
| Estado terminal y su escritura condicionada (`CONFIRMED` → terminal, en otra transacción, que comprueba la barrera) | `convocatoria` | CONVOCATORIA (nombre: DECISIÓN HUMANA) | A, E | Tests: permanente → terminal; carrera con una aplicación → no terminal |
| Consulta de intenciones `CONFIRMED` sin barrera y no terminales | `convocatoria` | CONVOCATORIA | A (F) | Test tras reinicio |
| Eliminar el camino "confirmar + aplicar" (F-1) y reescribir sus tests | `convocatoria` | CONVOCATORIA | A, B | Ninguna operación hace las dos cosas (A) |
| Orquestador transaccional (barrera + ledger + génesis + outbox) | `app` | DEPENDENCIA EXTERNA | A, D | — |
| Disparo inmediato tras la confirmación | `app` (+ `api`) | DEPENDENCIA EXTERNA | A (C) | — |
| Scheduler de respaldo | `app` | DEPENDENCIA EXTERNA | A (D) | — |
| Dependencia `app → convocatoria` | `app` | DEPENDENCIA EXTERNA | A, D | — |
| Adaptador de producción de `OrganizationVerificationPort` | `identity` + `app` (ADR-038) | DEPENDENCIA EXTERNA | A | — |
| Filtrado de cuentas `INACTIVE` | `identity` (ADR-038) | DEPENDENCIA EXTERNA | D | — |
| Camino sin reintento de `clearFundsGenesis` (T1) | `core` | DEPENDENCIA EXTERNA | A, D | — |
| Mensaje de outbox de la génesis | `core` (+ ADR-037) | NO DETERMINABLE | A, D | — |
| Webhook y confirmación de pasarela | `app`/`api` (P3) | DEPENDENCIA EXTERNA | D | — |
| Registro de dinero no aceptable (P1) | `convocatoria` (N4), **fuera de este corte** | DEPENDENCIA EXTERNA (decisión de producto) | D | Declarado fuera de alcance |
| Contrato HTTP de confirmación manual | ADR-041 | DOCUMENTACIÓN | A, D | — |

## Matriz 2 — Decisión → evidencia actual

| Decisión | Evidencia actual | Estado |
|---|---|---|
| D1 | Registro reutilizable y probado en concurrencia (A, B). Faltan A-1 y A-2 (A, E) | Implementable en `convocatoria` con condiciones |
| D2 | Scheduling activo en `app` y patrón en `BlockchainAnchorProducer` (A, C). `app` no puede componer `convocatoria` (A) | Consulta: `convocatoria`. Resto: bloqueado fuera |
| D3 | Sin estado equivalente; convenciones identificadas (A). `CampaignFundingLimitExceededException` permanente (A); `CloseOnTargetClose…` y anomalías, G | Implementable, una vez fijados el nombre y la clasificación |
| D4 | Plan §1.3 y §14.1; Enmienda §3.3 (D) | Fuera de corte; condición: no habilitar dinero real |
| D5 | `requireAdministratorOf` + tests (A, B); origen registrado (A); `INACTIVE` (D) | Implementable en `convocatoria` |
| D6 | `SystemActor` sin comprobación de roles (A); sin orquestador (A) | Fuera de `convocatoria` |
| D7 | Regla 3.5; `convocatoria-resumen.md:91` (D) | Requisito previo al código |

## Matriz 3 — Dependencia externa → bloqueo que produce

| Dependencia | Módulo | Bloquea | ¿Bloquea el cierre de Convocatoria? |
|---|---|---|---|
| Adaptador de `OrganizationVerificationPort` (ADR-038) | `identity`/`app` | `app → convocatoria`, orquestador, disparo, scheduler | No (bloquea el golden path) |
| T1 (génesis sin reintento) | `core` | Transacción única del orquestador | No |
| Mensaje de outbox de la génesis (G) | `core` | Cumplimiento literal de ADR-037 §2.3 | No |
| Orquestador, disparo inmediato, scheduler | `app` | Aplicación efectiva y recuperación automática | No |
| Webhook (P3) | `app`/`api` | Confirmación de intenciones `GATEWAY` | No |
| Filtrado de `INACTIVE` (ADR-038) | `identity` | Garantía plena de "`ADMINISTRATOR` autorizado" | No (riesgo declarado) |
| P1 | producto / `convocatoria` (N4) | Habilitar dinero real con rechazo permanente | No (fuera de corte por D4) |
| Contrato HTTP de confirmación (ADR-041) | `api` | Exponer la confirmación manual | No |

## Matriz 4 — Documento que debe cambiar → motivo

Ver la sección G. **Antes de tocar código:** Enmienda 2 de ADR-037 (con el nombre del estado y la clasificación de excepciones) y su registro en `convocatoria-resumen.md` (regla 3.5; `convocatoria-resumen.md:91`). **Puede ir en paralelo o después:** el ADR de recuperación (gobierna código de `app`), ADR-041, `api-contract-matrix` y `golden-path.md`. **Tras la ejecución:** `estado-fase6.md` y `implementation_plan.md`.

## Matriz 5 — Condiciones verificables de cierre del bloque Convocatoria

| # | Condición | Cómo se verifica |
|---|---|---|
| 1 | Enmienda 2 aprobada y registrada antes del código, incluidos el nombre del estado terminal y la clasificación de excepciones | D: documentos con estado APROBADA y fecha anterior a los cambios de código |
| 2 | Ninguna operación de `convocatoria` confirma y aplica a la vez | A: lectura de `CampaignFundingLedgerService` y `DonationIntentService` |
| 3 | La confirmación exige `ADMINISTRATOR` de la organización y rechaza las intenciones de pasarela | B: tests positivos y negativos, con aserción de estado sin cambios |
| 4 | La aplicación parte de `CONFIRMED` y usa el registro de comandos procesados con clave del sistema que un cliente no puede ocupar | A + B: test de ocupación previa |
| 5 | Una sola aplicación por intención: en secuencia, en concurrencia real (`CyclicBarrier`, MongoDB en replica set), tras reinicio y con llamadores distintos | B |
| 6 | Dentro de una transacción externa, sin reintento interno; un llamador que captura la excepción no puede confirmar la transacción | B |
| 7 | Rechazo permanente → estado terminal en otra transacción condicionada. Un fallo transitorio no lleva al estado terminal. Una carrera con una aplicación no lleva a marcar el terminal por error | B |
| 8 | La consulta devuelve las intenciones `CONFIRMED` sin barrera ni estado terminal, también tras un reinicio | B |
| 9 | `ConvocatoriaArchitectureTest` en verde: `convocatoria` sigue sin depender de `core`, `app` ni `identity` | B |
| 10 | `mvn test -pl convocatoria` y el reactor completo en verde, con la salida literal de Surefire | B |
| 11 | `convocatoria-resumen.md`, `implementation_plan.md` y `estado-fase6.md` actualizados con lo ejecutado; colisión de nombres F-1/F-2 resuelta | D |
| 12 | Dependencias externas de la Matriz 3 declaradas como pendientes con su módulo, sin implementación parcial en `convocatoria` | D + A |
| 13 | `convocatoria/` versionado en una rama `feat/` con PR (regla 3.1) | C |

---

## SE PUEDE IMPLEMENTAR EN CONVOCATORIA

1. Autorización `ADMINISTRATOR` en `confirmDonationIntent()`: `confirmedBy` pasa a ser un `accountId` resuelto con `ConvocatoriaAuthorizationPolicy.requireAdministratorOf`.
2. Rechazo, con excepción nombrada, de las intenciones `GATEWAY` / `PAYMENT_PROVIDER` en el camino manual.
3. Redefinición de `applyFundsForIntent`: parte de `CONFIRMED`, reclama la barrera en `convocatoria_processed_commands` con un `CommandType` nuevo y una clave del sistema que un cliente no puede ocupar, y después incrementa el ledger. Se une a la transacción externa, marcándola para rollback.
4. Variante sin reintento interno del reclamo idempotente para uso dentro de una transacción externa.
5. Estado terminal de rechazo (nombre a decidir) y su escritura condicionada, solo para las excepciones clasificadas como permanentes.
6. Consulta de intenciones `CONFIRMED` sin aplicar y no terminales.
7. Eliminación del camino "confirmar + aplicar", con la reescritura de sus tests y Javadocs.

## NO PERTENECE A CONVOCATORIA

1. Orquestador transaccional, disparo inmediato y scheduler de respaldo (`app`).
2. Dependencia `app → convocatoria` (`app`).
3. Adaptador de producción de `OrganizationVerificationPort` y filtrado de cuentas `INACTIVE` (`identity`/`app`, ADR-038).
4. Camino sin reintento de `clearFundsGenesis` (T1) y mensaje de outbox de la génesis (`core`).
5. Webhook y confirmación de pasarela (P3; `app`/`api`).
6. Contrato y endpoint HTTP de la confirmación manual (ADR-041; `api`/`app`).
7. ADR de recuperación automática (gobierna código de `app`).

## BLOQUEA EL GOLDEN PATH PERO NO DEBE ABSORBERLO CONVOCATORIA

1. ADR-038: sin el adaptador de verificación de organización, `app` no puede componer `convocatoria`.
2. T1 en `core`: sin él no existe la transacción única ledger + `Fund` + outbox.
3. Definición del mensaje de outbox de la génesis (ADR-037 §2.3; G).
4. P3: sin webhook no hay confirmación de pasarela ni dinero real.
5. P1: sin registro de dinero no aceptable no se puede habilitar dinero real con rechazos permanentes (D4).
6. Contrato HTTP de confirmación manual (ADR-041).
