# ADR-037 — Enmienda 1 — Convocatoria, Ledger, Assignment y DonationIntent

**Estado:** APROBADA — decisión humana explícita del 2026-09-30, con N1–N12 (§10.3.1). Estado del documento actualizado el 2026-10-01 (`convocatoria-resumen.md` §6.15).
**Enmendado por:** `ADR-037-enmienda-2-convocatoria.md` (BORRADOR, 2026-10-02): confirmación y aplicación de fondos de `DonationIntent` como actos separados, barrera por comando de sistema `APPLY_FUNDS` y estado `FUNDING_REJECTED`. Sustituye §5.3 y la frase de N9 "se ejecuta como `CLEAR_FUNDS_AS_GENESIS`" de la Enmienda 1, y el paso 3 y la lista de estados de ADR-037 §2.6.
**Número histórico:** ADR-033.
**Número vigente:** ADR-037 (renumeración de Fase 6 aprobada el 2026-09-28; ver tabla en `ADR-042-frontend-web-paxfide-web.md`).
**ADR que enmienda:** ADR-037 original, archivo `ADR-033-convocatoria-ledger-assignment-donationintent.md` (pendiente de renombrar según `plan-correccion-fase5-e-ia.md`). En este documento, "ADR-037 §X" se refiere a las secciones de ese archivo.
**Fecha:** 2026-09-30.
**Fuentes:** `convocatoria-resumen.md` §6.7, §6.8, §6.10, §6.11, §6.12, §6.13 y §6.14 (decisiones humanas aprobadas el 2026-09-30), §6.3 y §6.4 (reglas propuestas pendientes de aprobación en esta enmienda) y §6.9 (verificación de código en rama `develop`, HEAD `673eda92fcb18626788804a3a48b41730e6784fb`, solo lectura, tests no ejecutados).
**Revisiones:**
- 2026-09-30 (1): revisión de consistencia contra §6: etiquetas corregidas (§10.3), aclaración del nombre de campo del índice (§4.2), pendientes P5 y P6 añadidos (§8).
- 2026-09-30 (2): gate de implementación: regla de precedencia documental (§1, N11) e idempotencia de los comandos de escritura de Convocatoria (§3.5, decisión B2 y N12).
- 2026-09-30 (3): auditoría de cierre: creación del ledger (§3.1, N2), resultado guardado en el registro de idempotencia (§3.5, N12), dependencia de N10 declarada (§7.4), cierre manual (§3.4) y pendiente P7 (§8).
- 2026-10-01 (4): solo estado: BORRADOR → APROBADA (§10.3.2). Sin cambios de contenido normativo. Las decisiones del primer corte de implementación (R1, ID, P7 en el primer corte, R3 fuera del corte) se registran en `convocatoria-resumen.md` §6.15 y en `implementation_plan.md`, no en esta enmienda.

---

## 1. Objeto de la enmienda

Incorporar a ADR-037 las decisiones de la ronda D2/D3/D4/`CLOSE_ON_TARGET` del 29–30 sept 2026 y declarar lo que corresponde a otros ADRs. Esta enmienda no es un ADR nuevo y no vuelve a explicar el diseño.

**Regla de vigencia: todo lo que no sea modificado explícitamente por esta enmienda permanece vigente sin cambios respecto del ADR-037 original.**

**Regla de precedencia — [DECISIÓN — NUEVA] (N11):** donde se contradigan, ADR-037 y esta enmienda prevalecen sobre `fase-6-estructura-y-perimetro-convocatoria.md` (en particular §3.2, §3.4 y §3.5) y sobre `api-contract-matrix.md` §2. Esos documentos no se reescriben aquí; su corrección es el `chore/` documental de `convocatoria-resumen.md` §6.9.4.

**Regla editorial.** Cada cambio lleva una sola etiqueta:

| Etiqueta | Significado |
|---|---|
| **[DECISIÓN]** | Decisión humana ya aprobada el 2026-09-30 (`convocatoria-resumen.md` §6.7, §6.8, §6.10, §6.11, §6.12, §6.13, §6.14). Normativa cuando esta enmienda se apruebe. |
| **[DECISIÓN — NUEVA]** | Regla que §6 dejó como propuesta pendiente de aprobación, o decisión que nace en esta enmienda. **No está aprobada todavía**: se aprueba por primera vez al aprobar esta enmienda. Lista completa en §10.3.1. |
| **[REQUISITO]** | Requisito de diseño o implementación derivado de una decisión. No introduce decisión de producto nueva. |
| **[PENDIENTE]** | Decisión o integración no tomada. Ninguna implementación puede resolverla por su cuenta: vuelve a revisión humana. |
| **[ESTADO]** | Actualización del estado de un riesgo, verificación o supuesto de ADR-037. No cambia el diseño. |
| **[DEPENDENCIA]** | Cambio que pertenece a otro ADR. Se declara aquí; no se resuelve aquí. |

Ningún **[PENDIENTE]** de §8 se convierte en decisión implícita.

**Fuera de alcance de esta enmienda** (decidido, se ejecuta después): correcciones documentales de `convocatoria-resumen.md` §6.9.4 (`chore/`) y el índice mal ubicado en `ProcessedCommandIdempotencyIntegrationTest` (`fix/` con test de regresión).

---

## 2. Decisiones que modifica

| Tema | Qué decía ADR-037 | Qué cambia | Sección |
|---|---|---|---|
| D2 / `CampaignAssignment` | D2 abierto; responsable = `EMPLOYEE` asignado; sin autoasignación | `ADMINISTRATOR` puede ser responsable sin `EMPLOYEE`; dos operaciones separadas; `actingRole`; D2 resuelto | §4 |
| D3 / configuración de tipos | No existía | `acceptedDonationTypes`, configuración versionada, política de cambios con aprobación | §3 |
| D4 / medios y confirmación | Solo pasarela (webhook) | `acceptedPaymentMethods`; transferencia y efectivo con confirmación de la organización; vencimiento; referencia única | §3, §5 |
| `CLOSE_ON_TARGET` | Tres opciones de excedente, nombre pendiente | `onTargetReached`; `REJECT_EXCESS` rechaza la donación completa; dinero no aceptable fuera del ledger | §3 |
| Ledger | Un ledger por `campaignRef` (1:1) | Solo si la convocatoria acepta `MONETARY` | §3.1 |
| Cierre manual | Sin operación ni autorización definidas | Operación explícita `OPEN → CLOSED`, exclusiva de `ADMINISTRATOR` | §3.4 |
| `DonationIntent` | Correlación por `paymentSessionId` para pasarela | Campos nuevos; barrera de idempotencia; `commandId` determinista | §5 |
| Autorización contextual | Respaldo del `REPRESENTATIVE` descrito como decidido | Capacidad contextual del administrador responsable; puerto nuevo; respaldo inexistente en código | §4, §7 |
| Idempotencia de comandos de escritura | No definida (solo para la creación de `DonationIntent`; hallazgo R4 de `hallazgos-front-fase2.md`) | `commandId` + registro de comandos procesados, mismo patrón que `core` | §3.5 |

### 2.1 Alternativas descartadas en esta ronda — [DECISIÓN]

Se añaden a ADR-037 §4:

- **Exigir que el administrador responsable tenga también `EMPLOYEE`:** forzaba dos roles permanentes para una sola función y requería una operación nueva en `identity`.
- **Excepción de tipo de donación por donante:** convierte la configuración en sugerencia. La excepción va sobre la configuración (solicitud de cambio), no sobre la donación.
- **Inferir la convocatoria de un activo recorriendo proyecciones:** las proyecciones no son fuente de verdad y la cadena puede terminar en `null`.
- **Aceptación parcial de una donación con `REJECT_EXCESS`:** la donación es indivisible.
- **Autoaprobación de cambios de configuración:** autoasignación ≠ autoaprobación.
- **Comando genérico de asignación con `actingRole`:** las dos operaciones tienen invariantes y autorización distintos.
- **Ampliar el puerto contextual para validar `IN_KIND`:** mezclaría autorización con configuración.
- **Mecanismo de idempotencia propio de Convocatoria, distinto del de `core`:** crearía una segunda semántica de idempotencia en el sistema sin necesidad.
- **Cierre automático por fecha en esta enmienda:** no está decidido en ADR-037 y mezclaría el cierre manual con la ventana temporal, que son mecanismos distintos.

---

## 3. Cambios sobre `Convocatoria`

Modifica ADR-037 §2.1, §2.2 y §5.

### 3.1 Configuración

- **[DECISIÓN]** `acceptedDonationTypes`: subconjunto de `{MONETARY, IN_KIND}` (solo dinero, solo especie o ambos), elegido al crear.
- **[DECISIÓN — NUEVA]** `acceptedDonationTypes` nunca está vacío, ni al crear ni tras un cambio; para dejar de recibir se cierra la convocatoria.
- **[DECISIÓN]** `acceptedPaymentMethods`: conjunto de `{GATEWAY, BANK_TRANSFER, CASH}`; obligatorio y no vacío solo si `MONETARY ∈ acceptedDonationTypes`. Tipo de donación y medio de pago son configuraciones distintas.
- **[DECISIÓN]** `onTargetReached ∈ {CLOSE, REJECT_EXCESS, ACCEPT_EXCESS}` cuando `targetPolicy = CLOSE_ON_TARGET`. La semántica es la decisión; el nombre exacto del campo/enum es de implementación.
- **[DECISIÓN]** Invariante, comprobado en todo estado, incluida la creación: si `MONETARY ∈ acceptedDonationTypes`, entonces `acceptedPaymentMethods` no está vacío y `targetAmount`/`targetPolicy` están definidos. Añadir `MONETARY` es una sola transición de versión.
- **[DECISIÓN — NUEVA]** Una convocatoria solo `IN_KIND` no tiene `targetAmount`/`targetPolicy` ni `CampaignFundingLedger`. **Modifica ADR-037 §2.2** ("un ledger por `campaignRef`, relación 1:1"): el ledger existe solo si la convocatoria acepta `MONETARY`, y se crea en la misma transacción que la creación de la convocatoria o que la transición de versión que añade `MONETARY`. Motivo: la escritura condicional de ADR-037 §2.2 no crea el documento; sin ledger, la aplicación de fondos terminaría en `CampaignNotFoundException`.
- **[DECISIÓN]** La aceptación de `IN_KIND` se valida contra la configuración vigente. Es regla de negocio, no de autorización, y no forma parte del puerto contextual (§4.4). Ubicación: **[PENDIENTE] P2** (§8).
- **[DECISIÓN]** No existe excepción por donante: una donación de un tipo no aceptado nunca se admite individualmente.

### 3.2 Cambios de configuración

- **[DECISIÓN]** La configuración es versionada con efecto prospectivo: cada cambio aprobado produce una versión nueva y rige solo hacia adelante.
- **[DECISIÓN]** Política idéntica para `acceptedDonationTypes` y `acceptedPaymentMethods`:
  - Antes de la primera donación (sin `DonationIntent`, `Fund` ni activo en especie asociado): edición directa por `ADMINISTRATOR`, auditada.
  - Después: solo mediante solicitud de cambio, aprobada por otro `ADMINISTRATOR` o por el `REPRESENTATIVE`, siempre distinto del solicitante. Si no existe ningún aprobador válido, el cambio se bloquea. PaxFide nunca aprueba.
  - Ningún cambio modifica retrospectivamente donaciones registradas ni `DonationIntent` creadas antes de la aprobación: conservan la validez que tenían al crearse.
  - Una convocatoria `CLOSED` no admite cambios de configuración.
- **[DECISIÓN — NUEVA]** La solicitud de cambio de configuración vive en el módulo `convocatoria`, como CRUD + audit log (mismo criterio que el resto de piezas de ADR-037 §2). Nombre, campos y colección son de implementación.
- **[REQUISITO]** Concurrencia:
  - Cambio contra cambio: escritura condicional sobre la versión esperada; si cambió, conflicto, nunca sobrescritura silenciosa. La solicitud guarda la versión sobre la que se pidió; si al aprobarla la configuración ya avanzó, la aprobación falla.
  - Cambio contra creación de `DonationIntent`: sin conflicto; la intención registra la versión que leyó (§5).

### 3.3 Meta, ledger y dinero no aceptable

- **[DECISIÓN]** `REJECT_EXCESS` rechaza **completa** la donación que haría superar la meta; no existe aceptación parcial. Ejemplo: meta 1.000, acumulado 900, donación de 200 → se rechazan los 200.
- **[DECISIÓN]** El dinero recibido que el dominio no puede aceptar (rechazado por `STRICT`/`CLOSE_ON_TARGET + REJECT_EXCESS`, o transferencia llegada tras vencer su intención) **no entra al `Fund` ni al `CampaignFundingLedger`**, no se convierte en donación aceptada y queda en un registro de dinero no aceptable, fuera de ambos, con resolución manual trazable (estado, motivo, actor, momento, evidencia).
- **[DECISIÓN — NUEVA]** El registro de dinero no aceptable vive en el módulo `convocatoria`, como CRUD + audit log, separado de `DonationIntent` y de `Fund`.
- **[PENDIENTE] P1** Mecanismo definitivo de resolución de ese dinero (§8). No se implementa ninguna transición a "devuelto".
- Sin cambios en el mecanismo de escritura condicional de ADR-037 §2.2.

### 3.4 Estado `CLOSED` y cierre manual

Modifica ADR-037 §5 (autorización) y precisa §2.1.

- **[DECISIÓN]** Cierre manual: operación explícita que cambia `status` de `OPEN` a `CLOSED`. La ejecuta exclusivamente un `ADMINISTRATOR` de la organización (mismo criterio que `CreateConvocatoria`, ADR-037 §5). El respaldo del `REPRESENTATIVE` no aplica (ADR-037 §5, sin cambios).
- **[DECISIÓN]** Una convocatoria `CLOSED` no admite nuevas `DonationIntent` (precondición `status = OPEN` de ADR-037 §2.6, sin cambios).
- **[DECISIÓN]** Las intenciones creadas mientras la convocatoria estaba `OPEN` no se invalidan por el cierre (D1, ADR-037 §2.6bis, sin cambios).
- **[DECISIÓN]** Una convocatoria `CLOSED` no admite cambios de configuración.
- **[DECISIÓN]** La reapertura (`CLOSED → OPEN`) sigue fuera del contrato actual (ADR-037 §7, sin cambios).
- **[DECISIÓN]** No se introduce cierre automático por fecha. El cierre al alcanzar la meta con `targetPolicy = CLOSE_ON_TARGET` y `onTargetReached = CLOSE` (ADR-037 §2.2) es un mecanismo distinto y no cambia.
- **[REQUISITO]** El cierre manual es un comando de escritura sujeto a §3.5 (idempotencia).
- **[PENDIENTE] P7** Efecto de la ventana de fechas de la convocatoria sobre la creación de `DonationIntent` (§8). Mientras no se decida, la precondición es la vigente en ADR-037 §2.6: solo `status = OPEN`.

### 3.5 Idempotencia de los comandos de escritura del módulo `convocatoria`

Resuelve el hallazgo R4 de `hallazgos-front-fase2.md` para el módulo `convocatoria`. Aplica a todas sus piezas, no solo a `Convocatoria`.

- **[DECISIÓN]** Los comandos de escritura de Convocatoria reutilizan el patrón existente de `core`: `commandId` + registro de comandos procesados (`processed_commands` / `tryClaim`). No se crea un mecanismo de idempotencia distinto.
- **[DECISIÓN]** Alcance mínimo: crear convocatoria; asignar empleado (`AssignEmployeeToCampaign`); designar administrador (`DesignateAdministratorAsCampaignResponsible`); retirar responsable (`RemoveResponsible`); cerrar convocatoria (§3.4); y los comandos de escritura de configuración (edición directa, solicitud de cambio y su aprobación).
- **[REQUISITO]** Se reutiliza el **patrón**, no el código ni la colección de `core`: `convocatoria → contracts` sigue siendo su única dependencia directa (ADR-037 §2), así que el módulo tiene su propio registro de comandos procesados, con el mismo mecanismo (reclamo atómico por `commandId`) y dentro de la misma transacción que la escritura de dominio y el audit log. Si la ejecución original falla y la transacción se revierte, el reclamo también se revierte: solo quedan registradas las ejecuciones con éxito.
- **[DECISIÓN — NUEVA] (N12)** Ante un duplicado con el mismo `commandId`, el comando devuelve el resultado de la ejecución original, sin repetir el efecto. Para ello, el registro de comandos procesados guarda, junto al `commandId`, la referencia al resultado de la ejecución original: para crear convocatoria, `campaignRef` y `publicCode`; para los demás comandos, la referencia del efecto registrado. Motivo: en crear convocatoria el cliente necesita la referencia de lo creado; un no-op sin resultado dejaría al cliente sin saber qué se creó. Criterio de cierre tomado de R4/R11: un test que envía dos veces el mismo `commandId` y verifica que el efecto ocurre una sola vez.
- **[ESTADO]** El patrón existente en `core` no comprueba que el contenido de un comando duplicado coincida con el original (`convocatoria-resumen.md` §6.9.3). Esta enmienda no añade esa comprobación.

---

## 4. Cambios sobre `CampaignAssignment`

Modifica ADR-037 §2.4, §2.5 y §5.

### 4.1 Operaciones

- **[DECISIÓN]** `CampaignAssignment` pasa de "asignación `EMPLOYEE`↔convocatoria" a **asignación de responsable**, con `actingRole ∈ {EMPLOYEE, ADMINISTRATOR}`. `actingRole` es parte del registro de dominio.
- **[DECISIÓN]** Dos operaciones conceptualmente separadas, que pueden compartir infraestructura interna pero mantienen invariantes y autorización explícitos:
  - `AssignEmployeeToCampaign` → `actingRole = EMPLOYEE`.
  - `DesignateAdministratorAsCampaignResponsible` → `actingRole = ADMINISTRATOR`.
  No se introduce un comando genérico basado en `actingRole`. Se separa la operación, no el registro.
- **[DECISIÓN]** Ambas operaciones: exclusivamente `ADMINISTRATOR` de la organización.

### 4.2 Unicidad, cardinalidad y retiro

- **[DECISIÓN]** Índice único parcial restringido a empleados: `{ employeeRef: 1 } UNIQUE WHERE status = "ACTIVE" AND actingRole = "EMPLOYEE"`. Un `EMPLOYEE` es responsable de una sola convocatoria activa; un `ADMINISTRATOR` puede serlo de varias.
- **[REQUISITO]** Nombre de campo: `employeeRef` es el nombre heredado del índice de ADR-037 §2.4 y es de implementación. **No significa que `CampaignAssignment` solo represente empleados**: el registro identifica al responsable, sea `EMPLOYEE` o `ADMINISTRATOR` (el mismo dato que `RemoveResponsible` llama `responsibleRef`). El nombre definitivo del campo se fija al implementar.
- **[DECISIÓN]** La cardinalidad "≥1 responsable activo" (`CampaignResponsibleState`) cuenta a los responsables con `actingRole = EMPLOYEE` y con `actingRole = ADMINISTRATOR`. Se conserva el razonamiento de ADR-037 §2.5 de que el respaldo del `REPRESENTATIVE` no cuenta.
- **[DECISIÓN]** `RemoveResponsible(campaignRef, responsibleRef, replacementRef?)` sigue siendo una única operación, con reemplazo en la misma transacción (nunca una ventana con cero responsables). Si hay reemplazo, se aplican las reglas de la operación correspondiente a su tipo.
- Sin cambios: cada asignación es una inserción; `REMOVED` es histórico y no se reactiva.

### 4.3 Autoasignación

- **[DECISIÓN]** Sustituye "sin autoasignación (excepto la pregunta abierta en §7)" de ADR-037 §5: un `ADMINISTRATOR` puede designarse a sí mismo como responsable; un `EMPLOYEE` nunca se autoasigna.
- **[REQUISITO]** La autoasignación queda marcada como tal en el audit log.
- **[DECISIÓN]** Autoasignarse no equivale a aprobar ningún cambio posterior de configuración.

### 4.4 Autorización contextual del responsable

- **[DECISIÓN]** El administrador responsable puede ejecutar `REGISTER_PHYSICAL_ASSET` y `SPLIT_PHYSICAL_ASSET` **solo sobre la convocatoria de la que es responsable**. Es una capacidad contextual, no global, y se enumera como conjunto cerrado, nunca como "los permisos de `EMPLOYEE`". Ser responsable de una o varias convocatorias no altera su autoridad administrativa global.
- **[DEPENDENCIA]** La regla, el conjunto cerrado de comandos y el puerto que la evalúa pertenecen a ADR-032 (§7).
- **[PENDIENTE] P6** Condición de activación del respaldo del `REPRESENTATIVE` cuando el único responsable es un `ADMINISTRATOR` (§8). Se decide en la enmienda de ADR-032, no aquí. Los textos anteriores a D2 que dicen "cuando no existe un `EMPLOYEE` responsable activo" (`fase-6-estructura…` §3.5, `convocatoria-resumen.md` §2) no resuelven P6.

---

## 5. Cambios sobre `DonationIntent`

Modifica ADR-037 §2.6 y aclara §2.6bis.

### 5.1 Campos y precondición

- **[DECISIÓN — NUEVA]** Campos añadidos:
  - `paymentMethod ∈ {GATEWAY, BANK_TRANSFER, CASH}`.
  - `confirmationSource ∈ {PAYMENT_PROVIDER, ORGANIZATION}`, distinto de `paymentMethod`.
- **[DECISIÓN]** Versión de configuración de la convocatoria contra la que se validó la intención. Por D1 el webhook no revalida configuración: la versión es prueba, no segunda validación.
- **[DECISIÓN]** Fecha/hora de expiración para `BANK_TRANSFER` (§5.2).
- **[DECISIÓN]** `paymentSessionId` (único) aplica solo a `GATEWAY`.
- **[DECISIÓN — NUEVA]** Precondición de creación ampliada: además de `Convocatoria.status = OPEN` y `Organization.verificationStatus = VERIFIED` (ADR-037 §2.6, sin cambios), `MONETARY` y el `paymentMethod` elegido deben estar aceptados en la versión de configuración vigente.
- **[PENDIENTE] P5** Si una donación en efectivo (`CASH`) crea o no una `DonationIntent` (§8). La presencia de `CASH` en `paymentMethod` no decide esa cuestión.

### 5.2 Medios de pago y confirmación

- **[DECISIÓN — NUEVA]** Confirmación por medio (la fila `CASH` queda condicionada por P5):

| `paymentMethod` | `confirmationSource` | Quién confirma | Evidencia | Qué garantiza el sistema |
|---|---|---|---|---|
| `GATEWAY` | `PAYMENT_PROVIDER` | Webhook firmado del proveedor (flujo de ADR-037 §2.6, sin cambios) | `providerEventId`, `paymentSessionId` | Confirmación externa verificable |
| `BANK_TRANSFER` | `ORGANIZATION` | Humano de la organización, tras ver el movimiento en su banco | Referencia bancaria | Declaración atribuida a una persona concreta |
| `CASH` | `ORGANIZATION` | Humano de la organización, en persona (génesis directa, ADR-016) | Número de recibo | Declaración atribuida; no prueba que el dinero existió |

- **[DECISIÓN — NUEVA]** Toda confirmación registra quién, cuándo, qué medio y qué referencia/evidencia. La confirmación humana nunca sustituye al webhook en la pasarela.
- **[DECISIÓN — NUEVA]** La confirmación manual se ejecuta como `CLEAR_FUNDS_AS_GENESIS`, que hoy exige `ADMINISTRATOR` (ADR-032). *[SUSTITUIDO por la Enmienda 2 §3.1: confirmar no es la génesis; se conserva la exigencia de `ADMINISTRATOR` para la confirmación manual.]* Permitir que un `EMPLOYEE` confirme requeriría enmendar ADR-032.
- **[DECISIÓN]** Unicidad: una referencia de pago no puede confirmar más de una operación monetaria.
- **[REQUISITO]** El alcance exacto del índice de unicidad (por organización y medio, u otro) se fija en el contrato de transferencia, cuando se conozca qué identificador aporta el banco.
- **[DECISIÓN]** Vencimiento de `BANK_TRANSFER`: toda intención de transferencia tiene expiración fijada al crearla. Vencida, no puede confirmarse mediante esa intención; si el donante quiere continuar, se crea una nueva. El vencimiento no decide el destino del dinero que llegue tarde (§3.3). Es distinto del estado `EXPIRED-UNKNOWN` de la pasarela y distinto del cierre de la convocatoria (§3.4). El efectivo no tiene intención con vencimiento.
- **[REQUISITO]** La duración del vencimiento es un parámetro de configuración de implementación, no una constante de dominio.
- **[DECISIÓN — NUEVA]** La vista pública y los hechos de auditoría consumidos por IA distinguen "confirmado por el proveedor" de "declarado por la organización". Una confirmación de efectivo nunca se presenta como evidencia independiente de que el dinero existió. Dependencia: §7.4.
- **[REQUISITO]** Se añaden a los identificadores de ADR-037 §6 la versión de configuración y la referencia de pago; ninguno sustituye a otro.

### 5.3 Idempotencia financiera

- **[DECISIÓN]** Modifica el paso 3 del flujo de ADR-037 §2.6:
  - La transición `DonationIntent PENDING → CONFIRMED` es condicional e idempotente y actúa como **barrera antes de cualquier efecto financiero** sobre el ledger, en la misma transacción. *[SUSTITUIDO por la Enmienda 2 §3.3: la barrera del efecto financiero es el reclamo del comando de sistema `APPLY_FUNDS` en la aplicación posterior.]*
  - El `commandId` con que se invoca `clearFundsGenesis` se deriva de forma determinista de la intención; nunca se genera en cada entrega del webhook.
- **[ESTADO]** Motivo verificado en código: con el mismo `commandId`, `clearFundsGenesis` es un no-op silencioso; con un `commandId` nuevo sobre el mismo `fundId`, termina en error tras tres reintentos. Sin la barrera previa, un reintento podría sumar dos veces al ledger. La idempotencia de `clearFundsGenesis` ante reintentos sigue **NO CONFIRMADA en ejecución**: no hay tests de reintento, duplicado ni mismo `fundId` para ese método.
- **[PENDIENTE] P3** Integración del webhook y del proveedor (§8). La correlación por `paymentSessionId` de ADR-037 §2.6 es diseño cerrado y **no se reabre**.

### 5.4 Relación con D1

- **[ESTADO]** Aclaración de ADR-037 §2.6bis, sin cambio de diseño: D1 protege una intención frente al cierre de la convocatoria; el vencimiento de una intención de `BANK_TRANSFER` es un caso distinto, en el que vence la propia intención.

---

## 6. Cambios sobre `STRICT`

Modifica ADR-037 §2.3.

- **[ESTADO]** Diseño aprobado, integración pendiente. Verificado: `TransactionalEventPublisher.appendAndOutbox` (`core/.../TransactionalEventPublisher.java:25`) usa `@Transactional` sin atributos (`Propagation.REQUIRED`): se une a una transacción externa si existe. Hoy ningún llamador abre una, así que cada llamada tiene su propia transacción. El orquestador de `app` que exige ADR-037 §2.3 no existe todavía.
- **[REQUISITO]** El reintento debe envolver la **transacción completa del orquestador**, no vivir dentro de ella: `FundCommandService.clearFundsGenesis` tiene su propio bucle de reintentos, y con `REQUIRED` un conflicto de escritura hace rollback de toda la transacción externa. **No verificado en ejecución**; debe cubrirse con un test de Testcontainers al implementar el orquestador. Hasta entonces no se afirma que funcione.

---

## 7. Dependencias

Esta enmienda no modifica estos documentos. Declara lo que cada uno debe incorporar en su propia enmienda.

### 7.1 ADR-032 (autorización) — [DEPENDENCIA]

- Conjunto cerrado y explícito de comandos contextuales del administrador responsable (hoy `REGISTER_PHYSICAL_ASSET`, `SPLIT_PHYSICAL_ASSET`).
- Redacción normativa del respaldo del `REPRESENTATIVE`. **[ESTADO]** Verificado: no existe en el código; hoy el código lo rechaza en todos los casos (`RoleAuthorizationPolicy.java:21`; tests `RoleAuthorizationPolicyTest.java:66-68`, `RegisterPhysicalAssetFromDonationIntegrationTest:187`, `PhysicalAssetCommandServiceAuthorizationTest:106`).
- Condición de activación de ese respaldo ahora que un `ADMINISTRATOR` puede ser responsable: **[PENDIENTE] P6** (§8).
- Puerto contextual nuevo: definido en `contracts`, implementado por `convocatoria`, consumido por `core` (mismo patrón que `IdentityPrincipalPort`, sin ampliarlo). Mínimo y cerrado: "¿puede este principal ejercer esta capacidad contextual sobre esta convocatoria?".
- Ventana entre comprobar y ejecutar aceptada explícitamente.
- Activos sin convocatoria: solo autoridad ordinaria de `EMPLOYEE`, sin respaldo ni capacidad contextual.

### 7.2 ADR-029 (`PhysicalAsset`) — [DEPENDENCIA]

- `campaignRef` en `PhysicalAsset`: heredado del `Fund` en el Camino A, recibido en el comando en el Camino B, heredado del padre en el split. Inmutable tras el registro.
- Compatibilidad y migración: §9.

### 7.3 Contratos/API (ADR-041 y `claude/front-fase2.md`) — [DEPENDENCIA]

- `POST /campaigns/{campaignRef}/employees` debe actualizarse a las dos operaciones de §4.1. Ver **[PENDIENTE] P4** (§8).
- Los comandos de §3.5 aceptan `commandId`; el contrato HTTP de cada uno debe incluirlo (condición de despliegue web de `claude/front-fase2.md` P-W1).

### 7.4 `core` y ADR-040 (IA) — [DEPENDENCIA]

- La distinción entre "confirmado por el proveedor" y "declarado por la organización" (§5.2) vive en `DonationIntent`, pero la vista pública de seguimiento se construye desde los eventos de `Fund` en `core`, cuyos payloads no llevan `confirmationSource`, y los hechos de auditoría de IA (ADR-040) tampoco la reciben hoy. Cómo llega esa información a la vista de seguimiento y a los hechos de auditoría se decide en `core`/ADR-040 (y, si toca un contrato HTTP, en ADR-041). No es trabajo del módulo `convocatoria`, que solo debe conservar `paymentMethod` y `confirmationSource` en la intención.

---

## 8. Pendientes explícitos

Ninguno está decidido. Una implementación que los necesite se detiene y vuelve a revisión humana.

| # | Pendiente | Tipo | Dónde se decide | Ya fijado | Falta decidir |
|---|---|---|---|---|---|
| P1 | Mecanismo del dinero no aceptable (H-D4.3) | Decisión de producto | Enmienda posterior de ADR-037 | No entra al `Fund` ni al ledger; registro propio con resolución manual trazable (§3.3) | Devolución, conciliación u otro; qué información financiera se necesita, quién la aporta, dónde se guarda y cuánto tiempo (PII) |
| P2 | Ubicación de la validación de `IN_KIND` en el Camino B | Diseño de caso de uso | Diseño del caso de uso de registro en especie | Se valida contra la configuración vigente; no va en el puerto contextual (§3.1) | En `app` antes de llamar a `core`, otro puerto mínimo u otra vía compatible con las fronteras |
| P3 | Integración del webhook y del proveedor | Integración | Contrato del proveedor de pago | Correlación por `paymentSessionId`; barrera `PENDING → CONFIRMED`; `commandId` determinista (§5.3) | Proveedor; contrato real del webhook; alcance del índice de `providerEventId` |
| P4 | Contrato HTTP de asignación de responsables | Contrato | Enmienda de ADR-041 | Dos operaciones separadas (§4.1); `commandId` en el comando (§3.5) | Rutas, cuerpos y respuestas |
| P5 | Efectivo (`CASH`) en el flujo de donación | Semántica del flujo de donación | Diseño del flujo de donación monetaria (ADR-037) | `CASH` es medio habilitable; confirma la organización con número de recibo; génesis directa (ADR-016); sin intención con vencimiento (§5.2) | Si `CASH` crea o no `DonationIntent`; dónde se valida que `CASH` está habilitado; qué versión de configuración queda asociada al registro de efectivo si no existe `DonationIntent` |
| P6 | Activación del respaldo del `REPRESENTATIVE` | Autorización | Enmienda de ADR-032 | El respaldo no cuenta para "≥1 responsable" (§4.2); un `ADMINISTRATOR` puede ser responsable (§4.1) | Si el respaldo se activa cuando no hay `EMPLOYEE` activo, o solo cuando no hay ningún responsable activo |
| P7 | Efecto de la ventana de fechas | Decisión de producto | Enmienda posterior de ADR-037 | No hay cierre automático por fecha (§3.4); mientras no se decida, crear `DonationIntent` solo exige `status = OPEN` (ADR-037 §2.6) | Si fuera de la ventana de fechas se impide crear intenciones, y cómo interactúa con el descubrimiento público |

**P5, P6 y P7 no bloquean la redacción de esta enmienda:** quedan como pendientes explícitos de los documentos donde se deciden, con sus dependencias y límites señalados arriba. Sí bloquean cualquier implementación que dependa de ellos: el registro de efectivo (P5), el respaldo del `REPRESENTATIVE` (P6) y cualquier restricción por fechas (P7).

Verificaciones técnicas que siguen abiertas: idempotencia de `clearFundsGenesis` en ejecución (§5.3); reintento dentro de una transacción externa (§6); comportamiento conjunto de las dos transacciones de `convocatoria` bajo carga; límites operacionales de transacciones multi-documento; existencia del índice único de `event_store` en los entornos desplegados.

---

## 9. Compatibilidad y migración

### 9.1 `campaignRef` en `PhysicalAsset`

- **[ESTADO]** Hoy ningún activo tiene `campaignRef`: ni `PhysicalAsset` (`PhysicalAsset.java:22-43`) ni sus payloads (`AssetRegistered(V2)Payload`, `AssetSplit(V2)Payload`). En `Fund` existe y es opcional.
- **[DEPENDENCIA]** Incorporarlo exige una nueva versión de `ASSET_REGISTERED` y del evento de split, con upcaster para las versiones anteriores. El diseño exacto (número de versión, forma del upcaster) pertenece a la enmienda de ADR-029.
- **[DECISIÓN]** Los activos existentes y los que provengan de un `Fund` con `campaignRef = null` quedan sin convocatoria. Su convocatoria **nunca** se infiere recorriendo proyecciones. Solo admiten la autoridad ordinaria de `EMPLOYEE`.
- **[DEPENDENCIA]** Si se descarta explícitamente cualquier relleno retroactivo (backfill) de `campaignRef` en activos existentes, eso se fija en la enmienda de ADR-029. Precedente: ADR-028/029 prohibieron backfill y valores centinela para `organizationRef` en datos v1.

### 9.2 Piezas nuevas de `convocatoria`

- **[ESTADO]** El módulo `convocatoria` no existe en el código (verificado). `Convocatoria`, `CampaignAssignment`, `DonationIntent`, la solicitud de cambio de configuración, el registro de dinero no aceptable y el registro de comandos procesados del módulo nacen con los campos de esta enmienda: no hay datos previos que migrar.

### 9.3 Contrato HTTP

- **[DEPENDENCIA]** El cambio de `POST /campaigns/{campaignRef}/employees` afecta a consumidores ya diseñados (`claude/front-fase2.md`). Su compatibilidad se resuelve con P4.

---

## 10. Estado y trazabilidad

### 10.1 Cambios de estado en ADR-037

- **[ESTADO]** Encabezado: sustituir "Pendiente de implementación, de una única decisión de producto (D2, autoasignación)…" por "Aprobado con Enmienda 1. Pendiente de implementación, de los pendientes de la Enmienda 1 §8 y de las verificaciones técnicas de §7".
- **[ESTADO]** §3 (Consecuencias), añadir: cambio de esquema de eventos de `PhysicalAsset` (ADR-029); puerto nuevo en `contracts` (ADR-032); piezas nuevas en `convocatoria` (solicitud de cambio, registro de dinero no aceptable, registro de comandos procesados); deuda aceptada: P1 sin resolver.
- **[ESTADO]** §5 (Autorización), añadir: cierre manual → exclusivamente `ADMINISTRATOR` (§3.4).
- **[ESTADO]** §7 (Riesgos y decisiones):
  - D2 → ✅ resuelto por esta enmienda (§4).
  - Idempotencia de los comandos de escritura de Convocatoria (R4) → ✅ resuelto por esta enmienda (§3.5).
  - Idempotencia de `clearFundsGenesis` → "NO CONFIRMADA en ejecución; reglas de diseño decididas (§5.3)". Sigue bloqueando el camino completo del webhook hasta tener evidencia de Testcontainers.
  - `CLOSED` terminal → "no admite cambios de configuración ni nuevas intenciones; cierre manual por `ADMINISTRATOR`; sin cierre automático por fecha (decidido); reapertura fuera del contrato (sin cambios)".
  - `STRICT` → "diseño aprobado, integración pendiente; requisito de reintento (§6)".
  - `CampaignAssignment.ACTIVE` vs. `Account.INACTIVE`: el riesgo aceptado se apoya en el respaldo del `REPRESENTATIVE`, que no existe en código y cuya activación depende de P6. Sin cambio de diseño; se anota la dependencia.
  - Añadir P1–P7 (§8).
  - Sin cambios en el resto de filas.

### 10.2 Trazabilidad

- **[ESTADO]** Añadir a ADR-037 §8: verificación de código del 2026-09-30 (rama `develop`, HEAD `673eda92fcb18626788804a3a48b41730e6784fb`, solo lectura, tests no ejecutados), registrada en `convocatoria-resumen.md` §6.9.
- Las firmas de las operaciones nuevas (§3.2, §3.4, §3.5, §4.1, §5) son propuestas no verificadas contra código, porque el módulo `convocatoria` no existe todavía.

### 10.3 Aprobación

#### 10.3.1 Decisiones que se aprueban por primera vez con esta enmienda

Las reglas marcadas **[DECISIÓN — NUEVA]** no fueron aprobadas el 2026-09-30: §6.3 y §6.4 de `convocatoria-resumen.md` las dejaron como propuestas pendientes de aprobación en la enmienda, o nacen al redactarla. Aprobar esta enmienda es aprobarlas explícitamente:

| # | Decisión | Sección | Origen |
|---|---|---|---|
| N1 | `acceptedDonationTypes` nunca queda vacío; para dejar de recibir se cierra la convocatoria | §3.1 | §6.3 (propuesta) |
| N2 | Una convocatoria solo `IN_KIND` no tiene meta, política ni ledger; el ledger se crea con la convocatoria o con la transición que añade `MONETARY` (modifica ADR-037 §2.2) | §3.1 | §6.3 (propuesta); ajuste de creación del ledger aceptado en la auditoría de cierre (§6.14) |
| N3 | La solicitud de cambio de configuración vive en `convocatoria` como CRUD + audit log | §3.2 | Redacción de la enmienda |
| N4 | El registro de dinero no aceptable vive en `convocatoria` como CRUD + audit log | §3.3 | Redacción de la enmienda (§6.4 fijó solo que va separado de `DonationIntent` y `Fund`) |
| N5 | `paymentMethod` y `confirmationSource` como campos distintos de `DonationIntent` | §5.1 | §6.4 (propuesta) |
| N6 | Precondición de creación ampliada con `MONETARY` y el `paymentMethod` aceptados en la versión vigente | §5.1 | Derivada de D3/D4 en la redacción |
| N7 | Tabla de confirmación por medio de pago (quién confirma, evidencia, qué garantiza); fila `CASH` condicionada por P5 | §5.2 | §6.4 (propuesta) |
| N8 | Toda confirmación registra quién, cuándo, medio y evidencia; la confirmación humana nunca sustituye al webhook | §5.2 | §6.4 (propuesta) |
| N9 | La confirmación manual se ejecuta como `CLEAR_FUNDS_AS_GENESIS` (`ADMINISTRATOR`) *[sustituido por la Enmienda 2 §3.1]* | §5.2 | §6.4 (propuesta) |
| N10 | Vista pública y hechos de auditoría (IA) distinguen proveedor de organización; el efectivo nunca se presenta como evidencia independiente; dependencia declarada en §7.4 | §5.2 | §6.4 (propuesta); dependencia aceptada en la auditoría de cierre (§6.14) |
| N11 | Regla de precedencia: ADR-037 y esta enmienda prevalecen sobre `fase-6-estructura…` y `api-contract-matrix.md` §2 donde se contradigan | §1 | Gate de implementación del 2026-09-30 (B1) |
| N12 | Un comando duplicado (mismo `commandId`) devuelve el resultado de la ejecución original, sin repetir el efecto; el registro guarda la referencia al resultado (`campaignRef` + `publicCode` al crear) | §3.5 | Redacción de la enmienda, a partir de los criterios de cierre de R4/R11; ajuste aceptado en la auditoría de cierre (§6.14) |

No están en esta lista, porque son decisiones humanas del 2026-09-30 ya registradas en `convocatoria-resumen.md`: la reutilización del patrón `commandId` + registro de comandos procesados y su alcance (§3.5, §6.13), y el cierre manual (§3.4, §6.14).

#### 10.3.2 Lista de aprobación

Para pasar de BORRADOR a normativo:

- [x] Confirmación de que ningún **[PENDIENTE]** (P1–P7) quedó redactado como decisión (auditoría final: sin hallazgos A ni C).
- [x] Aprobación humana explícita de las decisiones N1–N12 (§10.3.1) — 2026-09-30.
- [x] Aprobación humana explícita de la enmienda — 2026-09-30.
- [ ] Apertura de las enmiendas dependientes (ADR-032, ADR-029, ADR-041, y `core`/ADR-040 para §7.4) antes de cualquier implementación que las toque.

La auditoría de cierre del 2026-09-30 (§6.14) sustituye la revisión final de consistencia: no se hace otra ronda de diseño antes de aprobar.
