# ADR-037 (número tentativo — confirmar contra el catálogo real antes de commitear) — Convocatoria, CampaignFundingLedger, CampaignAssignment, CampaignResponsibleState y DonationIntent

**Estado vigente (actualización 2026-10-07, auditoría §4.3):** Aprobado con las Enmiendas 1 y 2 (APROBADAS). **Implementado en `develop`:** el primer corte del módulo `convocatoria` y la aplicación de fondos de ADR-045 (T1 en `core`, #34; B2 en `convocatoria` y `app`, #35 y #36). Siguen abiertos los pendientes de la Enmienda 1 §8 y de la Enmienda 2 §7 (entre ellos P1, P3, P10 y R4). El estado detallado está en `estado-fase6.md`.
**Estado consolidado (histórico, anterior a la implementación):** Aprobado con Enmienda 1. Pendiente de implementación, de los pendientes de la Enmienda 1 §8 (reproducidos en §7.2) y de las verificaciones técnicas de §7. *(Texto de cabecera que prescribe la Enmienda 1 §10.1 [ESTADO]. La Enmienda 1 está APROBADA por decisión humana explícita del 2026-09-30.)* El estado real de la implementación no es materia de este ADR: ver `estado-fase6.md` §3bis.
**Estado original (antes de la Enmienda 1; se conserva como histórico):** Aprobado — diseño conceptual y arquitectónico, con registro de riesgos cerrado (§7). Pendiente de implementación, de una única decisión de producto (D2, autoasignación) y de las verificaciones técnicas listadas en §7.
**Fecha:** Sesión de Fase 6, review formal de 12 puntos (Modo de Arquitectura).
**Enmiendas y documentos asociados:**
- **Enmienda 1** — `ADR-037-enmienda-1-convocatoria.md` — **APROBADA** (decisión humana explícita del 2026-09-30, con N1–N12; estado del documento actualizado el 2026-10-01, `convocatoria-resumen.md` §6.15).
  - **Integrada en este documento** por la consolidación documental del 2026-10-03, marcada **[E1 §x]**.
  - El archivo de la enmienda se conserva íntegro como fuente histórica. Contiene además su historial de revisiones, la tabla de aprobación N1–N12 (§10.3) y el origen de cada decisión.
- **Enmienda 2** — `ADR-037-enmienda-2-convocatoria.md` — **BORRADOR** (2026-10-02).
  - **No está integrada como norma**: según su propio texto requiere aprobación humana explícita para ser normativa (Enmienda 2 §9, casillas sin marcar).
  - Donde afecta a este ADR se indica con la marca *[Enmienda 2 — BORRADOR, no normativa]* y en §9.
- **Referencia externa — ADR-043** (`ADR-043-recuperacion-aplicacion-fondos-convocatoria.md`, estado declarado: Propuesto). **Documento de otro bloque**, no de Convocatoria (instrucción humana del 2026-10-03, `convocatoria-resumen.md` §6.21). No forma parte de esta normativa ni se integra aquí (§9).
**Complementa:** `convocatoria-resumen.md`, `fase-6-estructura-y-perimetro-convocatoria.md`, `contract-wiring-review.md`, `golden-path.md`, `api-contract-matrix.md`. Corrige §3.4 de `fase-6-estructura-y-perimetro-convocatoria.md` (ver §3 de este ADR).
**Nota de numeración — DECISIÓN HUMANA PENDIENTE (discrepancia registrada, no resuelta):**
- El título conserva "(número tentativo …)".
- La Enmienda 1 (`:5-7`) y `convocatoria-resumen.md` §6.1 declaran "Número vigente: ADR-037", con renumeración aprobada el 2026-09-28. Citan como fuente una tabla en `ADR-042-frontend-web-paxfide-web.md`, archivo que no existe en `Documentos/`.
- Documentos de otro bloque (`plan-correccion-fase5-e-ia.md:24`, `estado-fase5.md:33`) siguen diciendo "pendiente de decisión humana".
- El archivo se renombró de `ADR-033-…` a `ADR-037-…` en el commit `b417d3a` (2026-09-27).
- No se cambia ningún número hasta la decisión humana registrada en `convocatoria-resumen.md` §0.4.

**Cómo leer este documento (convención de la consolidación del 2026-10-03):**

| Marca | Significado |
|---|---|
| Texto sin marca | ADR-037 original, vigente salvo donde un bloque **[E1]** lo modifica |
| **[E1 §x]** | Texto incorporado de la Enmienda 1, sección x. Se copia con sus etiquetas originales ([DECISIÓN], [DECISIÓN — NUEVA], [REQUISITO], [PENDIENTE], [ESTADO], [DEPENDENCIA]). Las referencias se adaptan: "ADR-037 §X" pasa a "§X" de este documento, y las secciones internas de la enmienda pasan a "E1 §X". Vigente |
| *Texto original (…)* | Texto de ADR-037 que una decisión aprobada modificó. Se conserva como histórico, no vigente |
| *[Enmienda 2 — BORRADOR, no normativa]* | Lo que la Enmienda 2 propone cambiar. Informativo, no normativo |
| **DECISIÓN HUMANA PENDIENTE** — *[Contradicción registrada]* | Conflicto documental pendiente de decisión humana. No se resuelve aquí. Lista completa: `convocatoria-resumen.md` §0.4–§0.5 |

Regla de vigencia de la Enmienda 1 (E1 §1): **todo lo que la Enmienda 1 no modifica explícitamente permanece vigente sin cambios respecto del ADR-037 original.**

---

## 0. Vigencia, precedencia y etiquetas [E1 §1]

**[E1 §1] Objeto de la Enmienda 1:** incorporar a ADR-037 las decisiones de la ronda D2/D3/D4/`CLOSE_ON_TARGET` del 29–30 sept 2026 y declarar lo que corresponde a otros ADRs.

**[E1 §1] Regla de precedencia — [DECISIÓN — NUEVA] (N11):**
- Donde se contradigan, ADR-037 y la Enmienda 1 prevalecen sobre:
  - `fase-6-estructura-y-perimetro-convocatoria.md`, en particular §3.2, §3.4 y §3.5;
  - `api-contract-matrix.md` §2.
- Esos documentos no se reescriben aquí. Su corrección es el `chore/` documental de `convocatoria-resumen.md` §6.9.4.

**[E1 §1] Regla editorial:**

| Etiqueta | Significado |
|---|---|
| **[DECISIÓN]** | Decisión humana ya aprobada el 2026-09-30 (`convocatoria-resumen.md` §6.7, §6.8, §6.10, §6.11, §6.12, §6.13, §6.14) |
| **[DECISIÓN — NUEVA]** | Regla aprobada por primera vez al aprobar la Enmienda 1 (N1–N12, E1 §10.3.1) |
| **[REQUISITO]** | Requisito de diseño o implementación derivado de una decisión. No introduce decisión de producto nueva |
| **[PENDIENTE]** | Decisión o integración no tomada. Ninguna implementación puede resolverla por su cuenta: vuelve a revisión humana |
| **[ESTADO]** | Actualización del estado de un riesgo, verificación o supuesto. No cambia el diseño |
| **[DEPENDENCIA]** | Cambio que pertenece a otro ADR. Se declara aquí; no se resuelve aquí |

Ningún **[PENDIENTE]** de §7.2 se convierte en decisión implícita.

**[E1 §1] Fuera de alcance de la Enmienda 1** (decidido, se ejecuta después):
- las correcciones documentales de `convocatoria-resumen.md` §6.9.4 (`chore/`);
- el índice mal ubicado en `ProcessedCommandIdempotencyIntegrationTest` (`fix/` con test de regresión).

---

## 1. Contexto

Fase 6 requiere que una `Organization` pueda crear y administrar campañas de recaudación (`Convocatoria`), que personas donen dinero con o sin cuenta, y que el sistema resuelva de forma segura la correlación entre el pago externo (webhook de un proveedor) y el registro financiero interno (`Fund`, Event Sourced, ya existente desde Fase 1-2).

`contract-wiring-review.md` identificó dos huecos que bloqueaban el Golden Path: P1/P2 (contratos de aplicación de Convocatoria e integración de lectura con Identity) y P3 (correlación de pago — el hueco más serio, porque `clearFundsGenesis` necesita `organizationRef/campaignRef/donorRef/currency/amount/commandId/actorRef` que un webhook externo no trae de forma nativa).

Este ADR resuelve P1, P2 y P3, y formaliza el perímetro completo de `Convocatoria` que `fase-6-estructura-y-perimetro-convocatoria.md` dejó pendiente de "review formal de 12 puntos" antes de pasar a ejecución.

*Nota de la consolidación:* los P1/P2/P3 de este párrafo son los de `contract-wiring-review.md`. No son los pendientes P1–P7 de la Enmienda 1 (§7.2): el identificador es el mismo y el significado distinto.

## 2. Decisión

Se introducen cinco componentes, todos CRUD + audit log append-only (no Event Sourced — sus invariantes son de estado actual, mismo criterio ya usado para `identity` en Fase 4), viviendo en un módulo Maven nuevo `convocatoria`, con **`convocatoria → contracts` como única dependencia directa**:

```
Convocatoria              — metadata, configuración, visibilidad, status
CampaignFundingLedger     — clearedAmount agregado, protección de targetPolicy
CampaignAssignment        — asignación EMPLOYEE↔convocatoria (inserción por asignación)
CampaignResponsibleState  — contador de responsables activos (mecanismo de concurrencia)
DonationIntent            — correlación de pago, cierra P3
```

Ninguno importa tipos de `core` ni de `identity`. La coordinación cross-módulo (con `Fund`, con `identity`) vive exclusivamente en `app`, que sí depende de ambos.

**[E1 §2] Decisiones que modifica la Enmienda 1:**

| Tema | Qué decía ADR-037 | Qué cambia | Sección de este documento |
|---|---|---|---|
| D2 / `CampaignAssignment` | D2 abierto; responsable = `EMPLOYEE` asignado; sin autoasignación | `ADMINISTRATOR` puede ser responsable sin `EMPLOYEE`; dos operaciones separadas; `actingRole`; D2 resuelto | §2.4, §2.5, §5 |
| D3 / configuración de tipos | No existía | `acceptedDonationTypes`, configuración versionada, política de cambios con aprobación | §2.1 |
| D4 / medios y confirmación | Solo pasarela (webhook) | `acceptedPaymentMethods`; transferencia y efectivo con confirmación de la organización; vencimiento; referencia única | §2.1, §2.6 |
| `CLOSE_ON_TARGET` | Tres opciones de excedente, nombre pendiente | `onTargetReached`; `REJECT_EXCESS` rechaza la donación completa; dinero no aceptable fuera del ledger | §2.1, §2.2 |
| Ledger | Un ledger por `campaignRef` (1:1) | Solo si la convocatoria acepta `MONETARY` | §2.2 |
| Cierre manual | Sin operación ni autorización definidas | Operación explícita `OPEN → CLOSED`, exclusiva de `ADMINISTRATOR` | §2.1, §5 |
| `DonationIntent` | Correlación por `paymentSessionId` para pasarela | Campos nuevos; barrera de idempotencia; `commandId` determinista | §2.6 |
| Autorización contextual | Respaldo del `REPRESENTATIVE` descrito como decidido | Capacidad contextual del administrador responsable; puerto nuevo; respaldo inexistente en código | §5, §7.3 |
| Idempotencia de comandos de escritura | No definida (solo para la creación de `DonationIntent`; hallazgo R4 de `hallazgos-front-fase2.md`) | `commandId` + registro de comandos procesados, mismo patrón que `core` | §2.7 |

**[E1 §3.2, §3.3, §3.5] Piezas adicionales del módulo `convocatoria`.** Se suman a las cinco anteriores; todas son CRUD + audit log:
- la solicitud de cambio de configuración (N3);
- el registro de dinero no aceptable (N4);
- el registro de comandos procesados del módulo (E1 §3.5).

### 2.1 `Convocatoria`

*Texto original de ADR-037; las partes modificadas están indicadas en el bloque [E1] que sigue.*

- Responsabilidad: identidad pública (`publicCode`, único), configuración de recaudación (`targetAmount`, `targetPolicy`), ventana temporal, visibilidad, `status`.
- `status: OPEN | CLOSED` es **campo persistido**, no derivado de fechas+meta — necesario porque `FLEXIBLE`/`STRICT` nunca cierran automáticamente al alcanzar la meta, solo `CLOSE_ON_TARGET` lo hace; una función pura de `(fechas, clearedAmount, targetAmount)` no podría representar sin ambigüedad "esta STRICT ya alcanzó su meta pero sigue abierta".
- No conoce `Fund`, `PhysicalAsset`, ni el mecanismo de pago — correlación siempre vía `campaignRef`/`organizationRef` opacos.
- `CLOSED` como terminal (sin `CLOSED→OPEN`) queda como suposición por ausencia de evidencia en contra, no como decisión confirmada — ver §7.

**[E1 §3.1] Configuración:**
- **[DECISIÓN]** `acceptedDonationTypes`: subconjunto de `{MONETARY, IN_KIND}` (solo dinero, solo especie o ambos), elegido al crear.
- **[DECISIÓN — NUEVA]** `acceptedDonationTypes` nunca está vacío, ni al crear ni tras un cambio; para dejar de recibir se cierra la convocatoria. (N1)
- **[DECISIÓN]** `acceptedPaymentMethods`: conjunto de `{GATEWAY, BANK_TRANSFER, CASH}`; obligatorio y no vacío solo si `MONETARY ∈ acceptedDonationTypes`. Tipo de donación y medio de pago son configuraciones distintas.
- **[DECISIÓN]** `onTargetReached ∈ {CLOSE, REJECT_EXCESS, ACCEPT_EXCESS}` cuando `targetPolicy = CLOSE_ON_TARGET`. La semántica es la decisión; el nombre exacto del campo/enum es de implementación.
- **[DECISIÓN]** Invariante, comprobado en todo estado, incluida la creación: si `MONETARY ∈ acceptedDonationTypes`, entonces `acceptedPaymentMethods` no está vacío y `targetAmount`/`targetPolicy` están definidos. Añadir `MONETARY` es una sola transición de versión.
- **[DECISIÓN — NUEVA]** Una convocatoria solo `IN_KIND` no tiene `targetAmount`/`targetPolicy` ni `CampaignFundingLedger` (N2; ver §2.2).
- **[DECISIÓN]** La aceptación de `IN_KIND` se valida contra la configuración vigente. Es regla de negocio, no de autorización, y no forma parte del puerto contextual (§5). Ubicación: **[PENDIENTE] P2** (§7.2).
- **[DECISIÓN]** No existe excepción por donante: una donación de un tipo no aceptado nunca se admite individualmente.

**[E1 §3.2] Cambios de configuración:**
- **[DECISIÓN]** La configuración es versionada con efecto prospectivo: cada cambio aprobado produce una versión nueva y rige solo hacia adelante.
- **[DECISIÓN]** Política idéntica para `acceptedDonationTypes` y `acceptedPaymentMethods`:
  - Antes de la primera donación (sin `DonationIntent`, `Fund` ni activo en especie asociado): edición directa por `ADMINISTRATOR`, auditada.
  - Después: solo mediante solicitud de cambio, aprobada por otro `ADMINISTRATOR` o por el `REPRESENTATIVE`, siempre distinto del solicitante. Si no existe ningún aprobador válido, el cambio se bloquea. PaxFide nunca aprueba.
  - Ningún cambio modifica retrospectivamente donaciones registradas ni `DonationIntent` creadas antes de la aprobación: conservan la validez que tenían al crearse.
  - Una convocatoria `CLOSED` no admite cambios de configuración.
- **[DECISIÓN — NUEVA]** La solicitud de cambio de configuración vive en el módulo `convocatoria`, como CRUD + audit log (mismo criterio que el resto de piezas de §2). Nombre, campos y colección son de implementación. (N3)
- **[REQUISITO]** Concurrencia:
  - Cambio contra cambio: escritura condicional sobre la versión esperada; si cambió, conflicto, nunca sobrescritura silenciosa. La solicitud guarda la versión sobre la que se pidió; si al aprobarla la configuración ya avanzó, la aprobación falla.
  - Cambio contra creación de `DonationIntent`: sin conflicto; la intención registra la versión que leyó (§2.6).

**[E1 §3.4] Estado `CLOSED` y cierre manual** (modifica §5 y precisa §2.1):
- **[DECISIÓN]** Cierre manual: operación explícita que cambia `status` de `OPEN` a `CLOSED`. La ejecuta exclusivamente un `ADMINISTRATOR` de la organización (mismo criterio que `CreateConvocatoria`, §5). El respaldo del `REPRESENTATIVE` no aplica (§5, sin cambios).
- **[DECISIÓN]** Una convocatoria `CLOSED` no admite nuevas `DonationIntent` (precondición `status = OPEN` de §2.6, sin cambios).
- **[DECISIÓN]** Las intenciones creadas mientras la convocatoria estaba `OPEN` no se invalidan por el cierre (D1, §2.6bis, sin cambios).
- **[DECISIÓN]** Una convocatoria `CLOSED` no admite cambios de configuración.
- **[DECISIÓN]** La reapertura (`CLOSED → OPEN`) sigue fuera del contrato actual (§7, sin cambios).
- **[DECISIÓN]** No se introduce cierre automático por fecha. El cierre al alcanzar la meta con `targetPolicy = CLOSE_ON_TARGET` y `onTargetReached = CLOSE` (§2.2) es un mecanismo distinto y no cambia.
- **[REQUISITO]** El cierre manual es un comando de escritura sujeto a §2.7 (idempotencia).
- **[PENDIENTE] P7** Efecto de la ventana de fechas de la convocatoria sobre la creación de `DonationIntent` (§7.2). Mientras no se decida, la precondición es la vigente en §2.6: solo `status = OPEN`.

### 2.2 `CampaignFundingLedger`

*Texto original de ADR-037; las partes modificadas están indicadas en el bloque [E1] que sigue.*

- Responsabilidad: proteger `clearedAmount` contra `targetAmount`/`targetPolicy`. Un ledger por `campaignRef` (relación 1:1).
- **`FLEXIBLE`**: sin límite superior.
- **`STRICT`**: `clearedAmount + amount <= targetAmount`, verificado en la misma transacción MongoDB que `Fund.FUNDS_CLEARED` y el mensaje de Outbox.
- **`CLOSE_ON_TARGET`**: comparte la misma infraestructura transaccional; las tres opciones de excedente (cerrar / rechazar el excedente / aceptarlo) ya están documentadas desde antes de este ADR (`convocatoria-resumen.md`); lo que este ADR añade es el requisito de que la opción elegida se fije como configuración en el momento de creación de la convocatoria, no como decisión humana tomada en vivo al cruzar la meta (exigido por la atomicidad de §2.2) — el nombre exacto del campo/enum es implementación, no arquitectura.
- Mecanismo: escritura condicional atómica de un solo documento —

  ```
  updateOne(
    { campaignRef: X, clearedAmount: { $lte: targetAmount - amount } },
    { $inc: { clearedAmount: amount } }
  )
  ```

  **`status` NO forma parte del filtro** (decisión D1, ver §2.6bis) — el cierre de la convocatoria no invalida por sí solo una aplicación de fondos legítima, porque esa legitimidad ya quedó garantizada en el momento de crear el `DonationIntent` correspondiente (que sí exige `status=OPEN`, ver §2.6). El filtro solo protege la capacidad financiera (`STRICT`/`CLOSE_ON_TARGET`); `FLEXIBLE` no tiene condición de capacidad, así que ese `updateOne` se reduce a `{ campaignRef: X }`.
- `matchedCount == 0` tras una transacción que no reportó conflicto transitorio se clasifica determinísticamente en `CampaignNotFoundException` / `CampaignFundingLimitExceededException` (ya no existe `CampaignClosedException` como resultado de este paso — ver §2.6bis).
- Un `TransientTransactionError` (incluye *write conflicts* entre transacciones concurrentes sobre el mismo documento) **no es una categoría de error nueva** — es la misma categoría ya establecida en `convocatoria-resumen.md` ("retry acotado de `TransientTransactionError` para fallos transitorios de Mongo"), aplicada aquí sin modificación. Se descarta explícitamente `LedgerConcurrencyConflictException` como excepción separada: bajo este mecanismo no existe una tercera categoría entre "conflicto de dominio" y "conflicto transitorio de infraestructura".

**[E1 §3.1] Existencia del ledger (N2; modifica "un ledger por `campaignRef`, relación 1:1"):**
- **[DECISIÓN — NUEVA]** El ledger existe solo si la convocatoria acepta `MONETARY`.
- Se crea en la misma transacción que la creación de la convocatoria o que la transición de versión que añade `MONETARY`.
- Motivo: la escritura condicional de §2.2 no crea el documento; sin ledger, la aplicación de fondos terminaría en `CampaignNotFoundException`.

**[E1 §3.3] Meta, ledger y dinero no aceptable:**
- **[DECISIÓN]** `REJECT_EXCESS` rechaza **completa** la donación que haría superar la meta; no existe aceptación parcial. Ejemplo: meta 1.000, acumulado 900, donación de 200 → se rechazan los 200.
- **[DECISIÓN]** El dinero recibido que el dominio no puede aceptar no entra al `Fund` ni al `CampaignFundingLedger`. Son dos casos:
  - el rechazado por `STRICT`/`CLOSE_ON_TARGET + REJECT_EXCESS`;
  - la transferencia llegada tras vencer su intención.

  Ese dinero no se convierte en donación aceptada. Queda en un registro de dinero no aceptable, fuera de ambos, con resolución manual trazable (estado, motivo, actor, momento, evidencia).
- **[DECISIÓN — NUEVA]** El registro de dinero no aceptable vive en el módulo `convocatoria`, como CRUD + audit log, separado de `DonationIntent` y de `Fund`. (N4)
- **[PENDIENTE] P1** Mecanismo definitivo de resolución de ese dinero (§7.2). No se implementa ninguna transición a "devuelto".
- Sin cambios en el mecanismo de escritura condicional de §2.2.

### 2.3 Corrección de `STRICT` — sustituye la decisión de `fase-6-estructura-y-perimetro-convocatoria.md` §3.4

`fase-6-estructura-y-perimetro-convocatoria.md` §3.4 concluyó "STRICT con tolerancia documentada" (ventana de inconsistencia reconciliable, sin transacción compartida) basándose en que `core` no tiene `MongoTransactionManager`. Esa verificación era correcta pero incompleta: **no inspeccionó `app`**.

Verificado directamente por código en esta sesión:

```java
// app/.../TraceabilityInfrastructureConfig.java
@Bean
public MongoTransactionManager transactionManager(MongoDatabaseFactory databaseFactory) {
    return new MongoTransactionManager(databaseFactory);
}

// app/.../TraceabilityApplication.java
@SpringBootApplication(scanBasePackages = "com.traceability")
@EnableMongoRepositories(basePackages = "com.traceability")
```

`app` sí provee un `MongoTransactionManager` real, alcanzable desde cualquier módulo compuesto en ese mismo contexto de Spring. Esto confirma la nota de corrección ya registrada en `convocatoria-resumen.md` §5 e invalida §3.4 como decisión vigente.

**Decisión corregida**: `STRICT` (y `CLOSE_ON_TARGET`) se protegen con **transacción MongoDB real compartida** — `CampaignFundingLedger` + `Fund.FUNDS_CLEARED` (append en `core`) + mensaje de Outbox, en una sola unidad transaccional, orquestada por un Application Service en `app`.

> *Enmienda del 2026-10-07 — **D-P8 (opción A) — Carlos, 2026-10-07** (`propuesta-P8-outbox-genesis.md`):* la génesis **no** escribe mensaje de outbox. La unidad transaccional queda en el reclamo `APPLY_FUNDS` + `CampaignFundingLedger` + `Fund.FUNDS_CLEARED` (con su reclamo de `commandId`). Motivos: no existe ningún consumidor (las proyecciones leen el `event_store` por Change Stream, el ledger se actualiza en la misma transacción y la entrega del `trackingCode` es síncrona), y un mensaje cuyo `sagaType` no tiene política se queda `PENDING` para siempre (`OutboxSagaCoordinator.java:42-45`). La atomicidad entre ledger y `Fund`, que es lo que protege esta sección, no cambia. **Regla:** si aparece un consumidor, el mensaje y su `SagaPolicy` entran juntos, en el mismo PR y con ADR (regla 3.5). `convocatoria` sigue sin importar `core` — la transacción cruza módulos a nivel de infraestructura (mismo `MongoTransactionManager`, mismo cliente Mongo), nunca a nivel de código Java entre `convocatoria` y `core`.

`fase-6-estructura-y-perimetro-convocatoria.md` §3.4 debe marcarse como superado por este ADR en cualquier lectura futura.

**DECISIÓN HUMANA PENDIENTE** — *[Contradicción registrada — CD-06]* `implementation_plan.md` §2 (`:67`) dice que `TraceabilityApplication` declara `scanBasePackages = {"com.traceability", "identity"}` (corregido allí el 2026-10-01). El fragmento anterior cita solo `com.traceability`. Se conserva el fragmento tal como se verificó en su sesión.

**[E1 §6] Cambios sobre `STRICT`:**
- **[ESTADO]** Diseño aprobado, integración pendiente.
  - Verificado: `TransactionalEventPublisher.appendAndOutbox` (`core/.../TransactionalEventPublisher.java:25`) usa `@Transactional` sin atributos (`Propagation.REQUIRED`), así que se une a una transacción externa si existe.
  - Hoy ningún llamador abre una, así que cada llamada tiene su propia transacción.
  - El orquestador de `app` que exige §2.3 no existe todavía.
- **[REQUISITO]** El reintento debe envolver la **transacción completa del orquestador**, no vivir dentro de ella.
  - Motivo: `FundCommandService.clearFundsGenesis` tiene su propio bucle de reintentos, y con `REQUIRED` un conflicto de escritura hace rollback de toda la transacción externa.
  - **No verificado en ejecución.** Debe cubrirse con un test de Testcontainers al implementar el orquestador. Hasta entonces no se afirma que funcione.

### 2.4 `CampaignAssignment`

*Texto original de ADR-037; las partes modificadas están indicadas en el bloque [E1] que sigue.*

- Responsabilidad: registrar la asignación `EMPLOYEE`↔`campaignRef`, con estado y fechas. Componente persistente propio, **no** array embebido en `Convocatoria` — exigido estructuralmente por la invariante "un empleado solo puede estar asignado a una convocatoria activa a la vez", que requiere un índice cruzando convocatorias, imposible de expresar dentro de un documento `Convocatoria` individual indexado por `campaignRef`.
- Cada asignación es una **inserción** de un nuevo documento (no actualización de uno existente), protegida por **índice único parcial**:

  ```
  { employeeRef: 1 } UNIQUE WHERE status = "ACTIVE"
  ```

  MongoDB serializa esto a nivel de motor en el momento de la escritura — no hay ventana entre "leer si ya existe" y "escribir". `DuplicateKeyException → EmployeeAlreadyAssignedException`.

**[E1 §4.1] Operaciones:**
- **[DECISIÓN]** `CampaignAssignment` pasa de "asignación `EMPLOYEE`↔convocatoria" a **asignación de responsable**, con `actingRole ∈ {EMPLOYEE, ADMINISTRATOR}`. `actingRole` es parte del registro de dominio.
- **[DECISIÓN]** Dos operaciones conceptualmente separadas, que pueden compartir infraestructura interna pero mantienen invariantes y autorización explícitos:
  - `AssignEmployeeToCampaign` → `actingRole = EMPLOYEE`.
  - `DesignateAdministratorAsCampaignResponsible` → `actingRole = ADMINISTRATOR`.

  No se introduce un comando genérico basado en `actingRole`. Se separa la operación, no el registro.
- **[DECISIÓN]** Ambas operaciones: exclusivamente `ADMINISTRATOR` de la organización.

**[E1 §4.2] Unicidad:**
- **[DECISIÓN]** Índice único parcial restringido a empleados (sustituye el índice del texto original):

  ```
  { employeeRef: 1 } UNIQUE WHERE status = "ACTIVE" AND actingRole = "EMPLOYEE"
  ```

  Un `EMPLOYEE` es responsable de una sola convocatoria activa; un `ADMINISTRATOR` puede serlo de varias.
- **[REQUISITO]** Nombre de campo: `employeeRef` es el nombre heredado del índice de §2.4 y es de implementación.
  - **No significa que `CampaignAssignment` solo represente empleados.** El registro identifica al responsable, sea `EMPLOYEE` o `ADMINISTRATOR`: el mismo dato que `RemoveResponsible` llama `responsibleRef`.
  - El nombre definitivo del campo se fija al implementar.
- Sin cambios: cada asignación es una inserción; `REMOVED` es histórico y no se reactiva.

### 2.5 `CampaignResponsibleState` — mecanismo de concurrencia para "≥1 responsable"

*Texto original de ADR-037; las partes modificadas están indicadas en el bloque [E1] que sigue.*

**Semántica de la invariante, resuelta por contradicción lógica**: la cardinalidad "≥1 responsable activo" protege exclusivamente `EMPLOYEE` explícitamente asignados vía `CampaignAssignment`. El respaldo automático de `REPRESENTATIVE` (enmienda ADR-032) **no** satisface esta cardinalidad — solo habilita comandos acotados (`REGISTER_PHYSICAL_ASSET`, `SPLIT_PHYSICAL_ASSET`) cuando no hay `EMPLOYEE` activo. Justificación: `Organization` garantiza siempre exactamente un `REPRESENTATIVE`; si este contara para la cardinalidad de la convocatoria, la regla "retirar al último responsable sin reemplazo se rechaza" nunca podría activarse — sería una rama de código muerta. La única lectura que le da sentido real a esa regla es que el `REPRESENTATIVE` no cuenta.

- `CampaignResponsibleState { campaignRef, activeResponsibleCount }` — documento contador, **no** optimistic-locking con versión (una comparación de versión no impide que dos transacciones lean el mismo conteo y ambas decidan proceder antes de que cualquiera detecte el conflicto).
- Retiro sin reemplazo: `updateOne({ campaignRef: X, activeResponsibleCount: { $gt: 1 } }, { $inc: { activeResponsibleCount: -1 } })`. `matchedCount==0` → `LastResponsibleRemovalWithoutReplacementException`.
- Retiro con reemplazo simultáneo: una única transacción, `activeResponsibleCount` no cambia (mismo N, cambia el titular) — `RemoveResponsible(campaignRef, responsibleRef, replacementRef?)` es **un solo comando**, nunca dos comandos independientes (evitaría la ventana intermedia con count=0).
- Primera asignación: `upsert:true` en el mismo `updateOne` de incremento — inicialización e incremento son la misma operación atómica.
- Ambas escrituras (`CampaignAssignment` + `CampaignResponsibleState`) y el registro de audit log ocurren **en la misma transacción MongoDB**, interna al módulo `convocatoria` (no necesita el orquestador de `app`, a diferencia de `STRICT`, porque no cruza `core`).
- `CampaignResponsibleState` es un mecanismo de contención de escritura, no una segunda fuente de verdad — nunca se consulta como read model; cualquier consulta de "cuántos responsables tiene esta convocatoria" cuenta sobre `CampaignAssignment` directamente.

**[E1 §4.2] Cardinalidad y retiro:**
- **[DECISIÓN]** La cardinalidad "≥1 responsable activo" (`CampaignResponsibleState`) cuenta a los responsables con `actingRole = EMPLOYEE` y con `actingRole = ADMINISTRATOR`. Se conserva el razonamiento anterior de que el respaldo del `REPRESENTATIVE` no cuenta.
- **[DECISIÓN]** `RemoveResponsible(campaignRef, responsibleRef, replacementRef?)` sigue siendo una única operación, con reemplazo en la misma transacción (nunca una ventana con cero responsables). Si hay reemplazo, se aplican las reglas de la operación correspondiente a su tipo.
- *Nota de la consolidación:* el texto original habla del "respaldo automático de `REPRESENTATIVE` (enmienda ADR-032)". La Enmienda 1 §7.1 verifica que ese respaldo **no existe en el código** y deja su activación como **[PENDIENTE] P6** (§7.2–§7.3). La frase no describe una regla vigente.

### 2.6 `DonationIntent` — cierre de P3

*Texto original de ADR-037; las partes modificadas están indicadas en los bloques [E1] que siguen.*

Correlación de pago: captura `organizationRef`, `campaignRef`, `donorRef`, `amount`, `currency` **antes** de que exista ningún pago, resolviendo `publicCode → campaignRef + organizationRef` vía `ConvocatoriaReadPort` (ya existente).

**DECISIÓN HUMANA PENDIENTE** — *[Contradicción registrada — CD-06]* `implementation_plan.md` §2 (`:65`) verificó que `ConvocatoriaReadPort` **no existe** en el código. El primer corte resuelve `publicCode` internamente (`implementation_plan.md` §5).

```
DonationIntent
├── intentId
├── fundId              ← generado una sola vez al crear el intent, inmutable;
│                          el webhook NUNCA genera un fundId nuevo
├── paymentSessionId     ← único
├── providerEventId      ← identidad del evento externo concreto, distinta de
│                          paymentSessionId (evita que un segundo evento con
│                          payload distinto se cuele como el mismo hecho)
├── organizationRef, campaignRef, donorRef, amount, currency
└── status: PENDING | CONFIRMED | FAILED | EXPIRED-UNKNOWN
```

*[Enmienda 2 — BORRADOR, no normativa]* La Enmienda 2 §4 propone añadir el estado terminal `FUNDING_REJECTED`: la intención estaba `CONFIRMED` y la aplicación financiera se rechazó de forma permanente. Ver §9.

**DECISIÓN HUMANA PENDIENTE** — *[Contradicción registrada — CD-13]* La grafía `EXPIRED-UNKNOWN` de este documento difiere de la del enum del código (`EXPIRED_UNKNOWN`). Pendiente de decisión humana (`convocatoria-resumen.md` §0.4).

**Precondición de creación (ya fijada en el diseño original de esta pieza, no es una decisión nueva de esta ronda)**: un `DonationIntent` solo puede crearse contra una `Convocatoria` con `status=OPEN` (y `Organization.verificationStatus=VERIFIED`). Esta precondición es la que hace posible D1 (§2.6bis): si toda intención existente fue necesariamente creada mientras la convocatoria estaba abierta, el estado `CLOSED` en el momento del webhook no puede indicar un intento fraudulento colándose después del cierre — solo puede tratarse de una confirmación tardía de algo ya legítimo.

Flujo:
1. `POST /public/campaigns/{publicCode}/donation-intents` crea el `DonationIntent` (con `fundId` ya fijado) antes de contactar al proveedor de pago — rechazado determinísticamente si `Convocatoria.status != OPEN`.
2. El webhook, autenticado por firma del proveedor (nunca JWT), busca por `paymentSessionId`. Si no existe → `PaymentCorrelationNotFoundException` (rechazo + auditoría, no se inventa el contexto). Si `providerEventId`/`amount`/`currency` no coinciden con lo esperado → `PaymentEventMismatchException`. Si el `providerEventId` ya fue procesado → **no es un error**, es el camino feliz de idempotencia (no-op).
3. Si la correlación es válida: invoca `clearFundsGenesis(commandId, fundId, ...)` usando siempre el `fundId` de `DonationIntent`, nunca uno generado en el adaptador — sujeto únicamente a las reglas de `CampaignFundingLedger` (§2.2), sin volver a comprobar `status` (D1, ver §2.6bis).

**[E1 §5.1] Campos y precondición:**
- **[DECISIÓN — NUEVA]** Campos añadidos (N5):
  - `paymentMethod ∈ {GATEWAY, BANK_TRANSFER, CASH}`.
  - `confirmationSource ∈ {PAYMENT_PROVIDER, ORGANIZATION}`, distinto de `paymentMethod`.
- **[DECISIÓN]** La intención guarda la versión de configuración de la convocatoria contra la que se validó. Por D1 el webhook no revalida configuración: la versión es prueba, no segunda validación.
- **[DECISIÓN]** Fecha/hora de expiración para `BANK_TRANSFER`.
- **[DECISIÓN]** `paymentSessionId` (único) aplica solo a `GATEWAY`.
- **[DECISIÓN — NUEVA]** Precondición de creación ampliada (N6). Además de `Convocatoria.status = OPEN` y `Organization.verificationStatus = VERIFIED` (sin cambios), `MONETARY` y el `paymentMethod` elegido deben estar aceptados en la versión de configuración vigente.
- **[PENDIENTE] P5** Si una donación en efectivo (`CASH`) crea o no una `DonationIntent` (§7.2). La presencia de `CASH` en `paymentMethod` no decide esa cuestión.

**[E1 §5.2] Medios de pago y confirmación:**
- **[DECISIÓN — NUEVA]** Confirmación por medio (N7; la fila `CASH` queda condicionada por P5):

| `paymentMethod` | `confirmationSource` | Quién confirma | Evidencia | Qué garantiza el sistema |
|---|---|---|---|---|
| `GATEWAY` | `PAYMENT_PROVIDER` | Webhook firmado del proveedor (flujo de §2.6, sin cambios) | `providerEventId`, `paymentSessionId` | Confirmación externa verificable |
| `BANK_TRANSFER` | `ORGANIZATION` | Humano de la organización, tras ver el movimiento en su banco | Referencia bancaria | Declaración atribuida a una persona concreta |
| `CASH` | `ORGANIZATION` | Humano de la organización, en persona (génesis directa, ADR-016) | Número de recibo | Declaración atribuida; no prueba que el dinero existió |

- **[DECISIÓN — NUEVA]** Toda confirmación registra quién, cuándo, qué medio y qué referencia/evidencia. La confirmación humana nunca sustituye al webhook en la pasarela. (N8)
- **[DECISIÓN — NUEVA]** La confirmación manual se ejecuta como `CLEAR_FUNDS_AS_GENESIS`, que hoy exige `ADMINISTRATOR` (ADR-032). Permitir que un `EMPLOYEE` confirme requeriría enmendar ADR-032. (N9)
  - *[Enmienda 2 — BORRADOR, no normativa]* La Enmienda 2 §3.1 propone sustituir la frase "se ejecuta como `CLEAR_FUNDS_AS_GENESIS`": confirmar no sería la génesis. La confirmación manual seguiría exigiendo `ADMINISTRATOR`, nunca para intenciones de pasarela, con `confirmedBy` como actor real. Ver §9.
- **[DECISIÓN]** Unicidad: una referencia de pago no puede confirmar más de una operación monetaria.
- **[REQUISITO]** El alcance exacto del índice de unicidad (por organización y medio, u otro) se fija en el contrato de transferencia, cuando se conozca qué identificador aporta el banco.
- **[DECISIÓN]** Vencimiento de `BANK_TRANSFER`:
  - Toda intención de transferencia tiene expiración fijada al crearla.
  - Vencida, no puede confirmarse mediante esa intención; si el donante quiere continuar, se crea una nueva.
  - El vencimiento no decide el destino del dinero que llegue tarde (§2.2, E1 §3.3).
  - Es distinto del estado `EXPIRED-UNKNOWN` de la pasarela y distinto del cierre de la convocatoria (§2.1, E1 §3.4).
  - El efectivo no tiene intención con vencimiento.
- **[REQUISITO]** La duración del vencimiento es un parámetro de configuración de implementación, no una constante de dominio.
- **[DECISIÓN — NUEVA]** La vista pública y los hechos de auditoría consumidos por IA distinguen "confirmado por el proveedor" de "declarado por la organización". Una confirmación de efectivo nunca se presenta como evidencia independiente de que el dinero existió. Dependencia: §7.3 (E1 §7.4). (N10)
- **[REQUISITO]** Se añaden a los identificadores de §6 la versión de configuración y la referencia de pago; ninguno sustituye a otro.

**[E1 §5.3] Idempotencia financiera** (modifica el paso 3 del flujo anterior):
- **[DECISIÓN]** La transición `DonationIntent PENDING → CONFIRMED` es condicional e idempotente y actúa como **barrera antes de cualquier efecto financiero** sobre el ledger, en la misma transacción.
- **[DECISIÓN]** El `commandId` con que se invoca `clearFundsGenesis` se deriva de forma determinista de la intención; nunca se genera en cada entrega del webhook.
- **[ESTADO]** Motivo verificado en código:
  - con el mismo `commandId`, `clearFundsGenesis` es un no-op silencioso;
  - con un `commandId` nuevo sobre el mismo `fundId`, termina en error tras tres reintentos;
  - sin la barrera previa, un reintento podría sumar dos veces al ledger.

  La idempotencia de `clearFundsGenesis` ante reintentos sigue **NO CONFIRMADA en ejecución**: no hay tests de reintento, duplicado ni mismo `fundId` para ese método.
- **[PENDIENTE] P3** Integración del webhook y del proveedor (§7.2). La correlación por `paymentSessionId` de §2.6 es diseño cerrado y **no se reabre**.

*[Enmienda 2 — BORRADOR, no normativa]* La Enmienda 2 §3 propone sustituir el paso 3 y la barrera de E1 §5.3 por dos actos. **Acto 1:** confirmación `PENDING → CONFIRMED`, sin efecto financiero. **Acto 2:** aplicación posterior por el sistema, en una transacción del orquestador de `app`, que incluye:
- reclamo del comando de sistema `APPLY_FUNDS` por intención (la barrera);
- incremento del ledger;
- `clearFundsGenesis`;
- outbox.

Ver §9.

**DECISIÓN HUMANA PENDIENTE** — *[Contradicción registrada — CD-03]* Hay dos textos en conflicto sobre la barrera:
- **E1 §5.3** (aprobada): la barrera es la transición `PENDING → CONFIRMED`, en la misma transacción que el efecto financiero.
- **Decisiones humanas F-1, F-2 y D1 del 2026-10-02** (`convocatoria-resumen.md` §6.20, aprobadas): confirmar y aplicar son actos separados, y la barrera es `APPLY_FUNDS`.

El código del módulo implementa la segunda (`estado-fase6.md` §3bis). Según `convocatoria-resumen.md` §6 (encabezado, `:91`), una decisión humana no modifica un ADR sin su enmienda aprobada, y la Enmienda 2 está en BORRADOR. Esta consolidación no elige entre ambos textos. Queda pendiente de la decisión humana sobre la Enmienda 2 (`convocatoria-resumen.md` §0.4).

### 2.6bis D1 — El cierre no invalida retroactivamente una intención ya aceptada

**Decisión**: una `DonationIntent` creada válidamente mientras la convocatoria estaba `OPEN` conserva su validez aunque la convocatoria pase después a `CLOSED`. Un webhook legítimo asociado a esa intención puede completar la operación tras el cierre. `CampaignFundingLedger` no usa `status=OPEN` como condición de aceptación del webhook (§2.2) — la operación sigue sujeta, sin excepción, a la capacidad y `targetPolicy` vigentes, de forma atómica.

Esto **no es "aceptar pagos después del cierre"** — es una consecuencia más estrecha y más defendible: el cierre no puede revocar retrospectivamente una intención que el sistema ya había aceptado como válida. Un `DonationIntent` que nunca pudo crearse (porque la convocatoria ya estaba `CLOSED` en ese momento) nunca entra en este escenario.

Alternativa descartada explícitamente (Camino B): mantener `status=OPEN` en el filtro del ledger y tratar el pago tardío legítimo como un caso de reconciliación manual/batch separado. Se descarta porque reintroduce exactamente la clase de "ventana de inconsistencia reconciliable aceptada a propósito" que este mismo ADR elimina en §2.3 para `STRICT` — sería inconsistente aplicar un estándar distinto al mismo tipo de problema dentro del mismo documento.

**Por qué `fundId` estable es la pieza crítica**: verificado en `FundCommandService`/`MongoEventStoreAdapter` (código real revisado en esta sesión) que el `streamId` (`fundId`) del génesis es un parámetro externo, no algo que el dominio derive — si el adaptador del webhook generara un `fundId` nuevo en cada invocación, dos entregas concurrentes del mismo pago crearían dos streams distintos, dos `Fund` reales, sin que el índice único `(streamId, sequence)` lo detectara nunca (streams diferentes no colisionan). Con `fundId` fijado en `DonationIntent`, una colisión concurrente sobre el mismo `fundId` sí choca contra ese índice → `DuplicateKeyException → ConcurrencyConflictException` (falla ruidosa, no duplicado silencioso).

**[E1 §5.4] Relación con D1:** **[ESTADO]** Aclaración sin cambio de diseño. D1 protege una intención frente al cierre de la convocatoria. El vencimiento de una intención de `BANK_TRANSFER` es un caso distinto, en el que vence la propia intención.

### 2.7 Idempotencia de los comandos de escritura del módulo `convocatoria` [E1 §3.5]

*Sección nueva, incorporada de la Enmienda 1 §3.5.*

Resuelve el hallazgo R4 de `hallazgos-front-fase2.md` para el módulo `convocatoria`. Aplica a todas sus piezas, no solo a `Convocatoria`.

- **[DECISIÓN]** Los comandos de escritura de Convocatoria reutilizan el patrón existente de `core`: `commandId` + registro de comandos procesados (`processed_commands` / `tryClaim`). No se crea un mecanismo de idempotencia distinto.
- **[DECISIÓN]** Alcance mínimo:
  - crear convocatoria;
  - asignar empleado (`AssignEmployeeToCampaign`);
  - designar administrador (`DesignateAdministratorAsCampaignResponsible`);
  - retirar responsable (`RemoveResponsible`);
  - cerrar convocatoria (§2.1);
  - los comandos de escritura de configuración (edición directa, solicitud de cambio y su aprobación).
- **[REQUISITO]** Se reutiliza el **patrón**, no el código ni la colección de `core`.
  - `convocatoria → contracts` sigue siendo su única dependencia directa (§2), así que el módulo tiene su propio registro de comandos procesados, con el mismo mecanismo (reclamo atómico por `commandId`).
  - El reclamo va dentro de la misma transacción que la escritura de dominio y el audit log.
  - Si la ejecución original falla y la transacción se revierte, el reclamo también se revierte: solo quedan registradas las ejecuciones con éxito.
- **[DECISIÓN — NUEVA] (N12)** Ante un duplicado con el mismo `commandId`, el comando devuelve el resultado de la ejecución original, sin repetir el efecto.
  - Para ello, el registro de comandos procesados guarda, junto al `commandId`, la referencia al resultado de la ejecución original: para crear convocatoria, `campaignRef` y `publicCode`; para los demás comandos, la referencia del efecto registrado.
  - Motivo: en crear convocatoria el cliente necesita la referencia de lo creado; un no-op sin resultado dejaría al cliente sin saber qué se creó.
  - Criterio de cierre tomado de R4/R11: un test que envía dos veces el mismo `commandId` y verifica que el efecto ocurre una sola vez.
- **[ESTADO]** El patrón existente en `core` no comprueba que el contenido de un comando duplicado coincida con el original (`convocatoria-resumen.md` §6.9.3). La Enmienda 1 no añade esa comprobación.

*Ampliación posterior, que no forma parte de la Enmienda 1:* la decisión humana ID del 2026-10-01 (`convocatoria-resumen.md` §6.15) extiende este mecanismo a la creación de `DonationIntent` en el primer corte, con el mismo alcance que declara ese registro ("no reescribe la enmienda").

## 3. Consecuencias

- Positivas: elimina el riesgo de duplicación financiera silenciosa por streams distintos ante entregas concurrentes del webhook; cierra P1/P2/P3 del wiring review; formaliza `RemoveResponsible`, contrato que ningún documento previo definía pese a que la regla de negocio lo exigía; corrige una decisión de `STRICT` basada en evidencia incompleta.
- Negativas / deuda aceptada: dos transacciones MongoDB independientes conviviendo (`STRICT` vía orquestador de `app`, `CampaignAssignment`+`ResponsibleState` interna a `convocatoria`) sin evidencia de comportamiento conjunto bajo carga — riesgo registrado, no resuelto (§7).
- **[E1 §10.1]** Se añaden:
  - el cambio de esquema de eventos de `PhysicalAsset` (ADR-029);
  - un puerto nuevo en `contracts` (ADR-032);
  - piezas nuevas en `convocatoria`: solicitud de cambio, registro de dinero no aceptable y registro de comandos procesados;
  - deuda aceptada: P1 sin resolver.

## 4. Alternativas descartadas

- **Metadata del proveedor de pago para derivar `organizationRef`**: descartada — el webhook nunca debe inventar contexto de dominio (regla ya establecida en `contract-wiring-review.md`).
- **Crear `Fund` directamente desde el webhook, sin `DonationIntent`**: descartada — mezclaría infraestructura externa, correlación e identidad de dominio en el adaptador.
- **`Donation` como Aggregate Event-Sourced propio**: descartada — `Fund` ya representa correctamente el lifecycle financiero; el proyecto evita convertir "Donation" en un Aggregate artificial desde las primeras rondas de diseño de agregados.
- **`CampaignResponsibleState` con optimistic locking (campo `version`)**: descartada — no impide que dos transacciones lean el mismo conteo antes de que cualquiera detecte conflicto; sustituida por update condicional atómico.
- **`CampaignAssignment` embebido como array dentro de `Convocatoria`**: descartada — la invariante de unicidad cruza convocatorias, no puede protegerse con un índice sobre un documento indexado por `campaignRef`.
- **Reutilizar `RoleAuthorizationPolicy`/`OrganizationBoundaryPolicy` de `core` directamente en `convocatoria`**: descartada — violaría la frontera `convocatoria → contracts` únicamente; se introduce `ConvocatoriaAuthorizationPolicy` propia, mismo patrón que `PlatformAuthorizationPolicy` en `identity`.
- **`LedgerConcurrencyConflictException` como categoría de error separada**: descartada tras analizar el mecanismo real — un *write conflict* sobre la escritura condicional cae en la categoría ya existente `TransientTransactionError`, sin necesitar nombre nuevo.

**[E1 §2.1] Alternativas descartadas en la ronda D2–D4 — [DECISIÓN]:**
- **Exigir que el administrador responsable tenga también `EMPLOYEE`:** forzaba dos roles permanentes para una sola función y requería una operación nueva en `identity`.
- **Excepción de tipo de donación por donante:** convierte la configuración en sugerencia. La excepción va sobre la configuración (solicitud de cambio), no sobre la donación.
- **Inferir la convocatoria de un activo recorriendo proyecciones:** las proyecciones no son fuente de verdad y la cadena puede terminar en `null`.
- **Aceptación parcial de una donación con `REJECT_EXCESS`:** la donación es indivisible.
- **Autoaprobación de cambios de configuración:** autoasignación ≠ autoaprobación.
- **Comando genérico de asignación con `actingRole`:** las dos operaciones tienen invariantes y autorización distintos.
- **Ampliar el puerto contextual para validar `IN_KIND`:** mezclaría autorización con configuración.
- **Mecanismo de idempotencia propio de Convocatoria, distinto del de `core`:** crearía una segunda semántica de idempotencia en el sistema sin necesidad.
- **Cierre automático por fecha en esta enmienda:** no está decidido en ADR-037 y mezclaría el cierre manual con la ventana temporal, que son mecanismos distintos.

## 5. Autorización

*Texto original de ADR-037; las partes modificadas están indicadas en el bloque [E1] que sigue.*

- `CreateConvocatoria`: `ADMINISTRATOR` de la `Organization` (`VERIFIED`).
- `AssignEmployee`/`RemoveResponsible`: exclusivamente `ADMINISTRATOR`, sin autoasignación (excepto la pregunta abierta en §7).
- Respaldo de `REPRESENTATIVE` (ADR-032) **no aplica** a ninguno de los tres comandos anteriores — exclusivo de `PhysicalAsset`.
- `DonationIntent` (creación): pública u opcional JWT, sin `RoleAuthorizationPolicy`.
- Webhook de pago: firma del proveedor, nunca JWT — actor externo, nunca usuario autenticado.

**[E1 §10.1, §3.4] Cierre manual:** exclusivamente `ADMINISTRATOR` de la organización.

**[E1 §4.1] Asignación y designación:** `AssignEmployeeToCampaign` y `DesignateAdministratorAsCampaignResponsible`, exclusivamente `ADMINISTRATOR` de la organización.

**[E1 §4.3] Autoasignación** (sustituye "sin autoasignación (excepto la pregunta abierta en §7)" del texto original):
- **[DECISIÓN]** Un `ADMINISTRATOR` puede designarse a sí mismo como responsable; un `EMPLOYEE` nunca se autoasigna.
- **[REQUISITO]** La autoasignación queda marcada como tal en el audit log.
- **[DECISIÓN]** Autoasignarse no equivale a aprobar ningún cambio posterior de configuración.

**[E1 §4.4] Autorización contextual del responsable:**
- **[DECISIÓN]** El administrador responsable puede ejecutar `REGISTER_PHYSICAL_ASSET` y `SPLIT_PHYSICAL_ASSET` **solo sobre la convocatoria de la que es responsable**.
  - Es una capacidad contextual, no global, y se enumera como conjunto cerrado, nunca como "los permisos de `EMPLOYEE`".
  - Ser responsable de una o varias convocatorias no altera su autoridad administrativa global.
- **[DEPENDENCIA]** La regla, el conjunto cerrado de comandos y el puerto que la evalúa pertenecen a ADR-032 (§7.3).
- **[PENDIENTE] P6** Condición de activación del respaldo del `REPRESENTATIVE` cuando el único responsable es un `ADMINISTRATOR` (§7.2).
  - Se decide en la enmienda de ADR-032, no aquí.
  - Los textos anteriores a D2 que dicen "cuando no existe un `EMPLOYEE` responsable activo" no resuelven P6: `fase-6-…` §3.5 y `convocatoria-resumen.md` §2.

## 6. Observabilidad

`correlationId` (tracing), `commandId` (comando+idempotencia), `intentId` (proceso de donación), `providerEventId` (evento externo), `eventId` (evento persistido), `streamId`/`fundId` (agregado) — seis identificadores con alcance distinto, ninguno sustituye a otro. `core.domain.event.ActorRef` es exclusivo de `app`↔`core`; `convocatoria` usa su propia representación de actor derivada de `AuthorizationPrincipal`, sin importar el tipo de `core`. El audit log de `convocatoria` participa en la misma transacción que la escritura de dominio que registra.

**[E1 §5.2] [REQUISITO]** Se añaden a estos identificadores la versión de configuración y la referencia de pago; ninguno sustituye a otro.

## 7. Estado final de riesgos y decisiones de producto (tras ronda de cierre)

### 7.1 Tabla original y su estado tras la Enmienda 1

*Texto original de ADR-037, conservado. El estado vigente de cada fila es el del bloque [E1 §10.1] que sigue a la tabla.*

**D1 (pago tardío tras `CLOSED`) queda resuelto — ver §2.6bis.** Solo quedan dos decisiones de producto genuinamente irreductibles por inferencia documental, más un conjunto de verificaciones técnicas que no bloquean seguir planeando:

| # | Tema | Estado | Bloquea implementación de |
|---|---|---|---|
| D1 | Pago legítimo confirmado después de `CLOSED` | ✅ **Resuelto — §2.6bis** | — |
| D2 | ¿Autoasignación prohibida también para un `ADMINISTRATOR` que a la vez es `EMPLOYEE`? | 🔴 **Decisión de producto pendiente** | `AssignEmployee` (caso límite) |
| — | `onTargetReachedBehavior` — semántica ya documentada desde antes de este ADR (cerrar/rechazar excedente/aceptarlo); falta fijar nombre de campo/enum | 🟢 Semántica resuelta, detalle de implementación pendiente | Nada — es especificación, no decisión |
| — | Idempotencia real de `clearFundsGenesis` ante retry — comportamiento de `CommandRetryTemplate` al agotar reintentos, no verificado | 🔴 **Verificación técnica pendiente** | El camino completo del webhook |
| — | Comportamiento conjunto de la transacción de `STRICT` (orquestada en `app`) y la de `CampaignAssignment`+`CampaignResponsibleState` (interna a `convocatoria`) bajo carga simultánea | 🟡 Benchmark/prueba de carga pendiente | Nada de forma inmediata; riesgo de escala |
| — | Desincronización `CampaignAssignment.ACTIVE` vs. `Account.INACTIVE` (Identity) | 🟢 Riesgo aceptado — `REPRESENTATIVE` cubre la operación de respaldo, sin sincronización automática | Nada |
| — | `providerEventId` sin índice único global — depende del contrato exacto del proveedor de pago, no elegido | 🟡 Pendiente de infraestructura externa | Materialización final del índice |
| — | Descubrimiento público de convocatorias (`GET /campaigns` o equivalente) | 🟢 **Necesidad de negocio confirmada** (`convocatoria-resumen.md` + `contract-wiring-review.md`, que trata explícitamente "necesidad confirmada ≠ contrato HTTP cerrado" como la jerarquía correcta, no como contradicción) — el contrato HTTP exacto, paginación, filtros y límites siguen pendientes | El endpoint concreto, no la decisión de si existe |
| — | ¿`Convocatoria.CLOSED` es terminal (sin `CLOSED→OPEN`)? | 🟡 No confirmado documentalmente — tratado como "reapertura fuera del contrato actual", no como invariante irreversible confirmada | Reapertura, si se necesitara |
| — | Ciclo de vida exacto de `CampaignAssignment.REMOVED` | 🟢 **Resuelto como decisión derivada del diseño ya congelado**: `REMOVED` es histórico, no se reactiva; una nueva asignación siempre crea un nuevo documento (consistente con "inserción por asignación", §2.4) | Nada |
| — | Límites operacionales de transacciones Mongo multi-documento (duración, tasa de contención) | 🟡 Benchmark/carga pendiente antes de producción | Nada de forma inmediata |
| — | Firmas exactas de `CreateConvocatoria`/`AssignEmployee`/`RemoveResponsible` | 🟡 Verificación contra código, no decisión arquitectónica | Nada de forma inmediata |

**Autocorrección registrada en esta ronda**: el ítem de descubrimiento público se presentó en una versión anterior de este ADR como una contradicción documental sin resolver entre `convocatoria-resumen.md` y `fase-6-estructura-y-perimetro-convocatoria.md`. Esa lectura ignoraba que `contract-wiring-review.md` —ya presente en el contexto de esta sesión— resuelve exactamente esa tensión de forma explícita, distinguiendo "necesidad de negocio confirmada" de "contrato HTTP cerrado" como la jerarquía correcta del proyecto, no como una inconsistencia. Corregido arriba.

Solo D2 queda como decisión de producto pendiente sin resolver; el resto son verificaciones técnicas o detalles de implementación que no requieren congelar nada nuevo antes de continuar con las capas siguientes de Fase 6 (Identidad, Blockchain, IA).

*Nota de la consolidación:* la frase anterior ("Solo D2 queda…") es texto original. D2 está resuelto por la Enmienda 1 (§2.4, §5; ver el bloque siguiente).

**[E1 §10.1] [ESTADO] Estado vigente de las filas:**
- **D2:** ✅ resuelto por la Enmienda 1 (§2.4, §5).
- **Idempotencia de los comandos de escritura de Convocatoria (R4):** ✅ resuelta por la Enmienda 1 (§2.7).
- **Idempotencia de `clearFundsGenesis`:** "NO CONFIRMADA en ejecución; reglas de diseño decididas (E1 §5.3)". Sigue bloqueando el camino completo del webhook hasta tener evidencia de Testcontainers.
- **`CLOSED` terminal:** no admite cambios de configuración ni nuevas intenciones; cierre manual por `ADMINISTRATOR`; sin cierre automático por fecha (decidido); reapertura fuera del contrato (sin cambios).
- **`STRICT`:** diseño aprobado, integración pendiente; requisito de reintento (§2.3, E1 §6).
- **`CampaignAssignment.ACTIVE` frente a `Account.INACTIVE`:** el riesgo aceptado se apoya en el respaldo del `REPRESENTATIVE`, que no existe en código y cuya activación depende de P6. Sin cambio de diseño; se anota la dependencia.
- **P1–P7:** se añaden (§7.2).
- Sin cambios en el resto de filas.

### 7.2 Pendientes explícitos [E1 §8]

Ninguno está decidido. Una implementación que los necesite se detiene y vuelve a revisión humana.

| # | Pendiente | Tipo | Dónde se decide | Ya fijado | Falta decidir |
|---|---|---|---|---|---|
| P1 | Mecanismo del dinero no aceptable (H-D4.3) | Decisión de producto | Enmienda posterior de ADR-037 | No entra al `Fund` ni al ledger; registro propio con resolución manual trazable (§2.2, E1 §3.3) | Devolución, conciliación u otro; qué información financiera se necesita, quién la aporta, dónde se guarda y cuánto tiempo (PII) |
| P2 | Ubicación de la validación de `IN_KIND` en el Camino B | Diseño de caso de uso | Diseño del caso de uso de registro en especie | Se valida contra la configuración vigente; no va en el puerto contextual (§2.1, E1 §3.1) | En `app` antes de llamar a `core`, otro puerto mínimo u otra vía compatible con las fronteras |
| P3 | Integración del webhook y del proveedor | Integración | Contrato del proveedor de pago | Correlación por `paymentSessionId`; barrera `PENDING → CONFIRMED`; `commandId` determinista (§2.6, E1 §5.3) | Proveedor; contrato real del webhook; alcance del índice de `providerEventId` |
| P4 | Contrato HTTP de asignación de responsables | Contrato | Enmienda de ADR-041 | Dos operaciones separadas (§2.4, E1 §4.1); `commandId` en el comando (§2.7) | Rutas, cuerpos y respuestas |
| P5 | Efectivo (`CASH`) en el flujo de donación | Semántica del flujo de donación | Diseño del flujo de donación monetaria (ADR-037) | `CASH` es medio habilitable; confirma la organización con número de recibo; génesis directa (ADR-016); sin intención con vencimiento (§2.6, E1 §5.2) | Si `CASH` crea o no `DonationIntent`; dónde se valida que `CASH` está habilitado; qué versión de configuración queda asociada al registro de efectivo si no existe `DonationIntent` |
| P6 | Activación del respaldo del `REPRESENTATIVE` | Autorización | Enmienda de ADR-032 | El respaldo no cuenta para "≥1 responsable" (§2.5, E1 §4.2); un `ADMINISTRATOR` puede ser responsable (§2.4, E1 §4.1) | Si el respaldo se activa cuando no hay `EMPLOYEE` activo, o solo cuando no hay ningún responsable activo |
| P7 | Efecto de la ventana de fechas | Decisión de producto | Enmienda posterior de ADR-037 | No hay cierre automático por fecha (§2.1, E1 §3.4); mientras no se decida, crear `DonationIntent` solo exige `status = OPEN` (§2.6) | Si fuera de la ventana de fechas se impide crear intenciones, y cómo interactúa con el descubrimiento público |

**P5, P6 y P7 no bloquearon la redacción de la Enmienda 1:** quedan como pendientes explícitos de los documentos donde se deciden, con sus dependencias y límites señalados arriba. Sí bloquean cualquier implementación que dependa de ellos:
- el registro de efectivo (P5);
- el respaldo del `REPRESENTATIVE` (P6);
- cualquier restricción por fechas (P7).

Verificaciones técnicas que siguen abiertas:
- idempotencia de `clearFundsGenesis` en ejecución (E1 §5.3);
- reintento dentro de una transacción externa (E1 §6);
- comportamiento conjunto de las dos transacciones de `convocatoria` bajo carga;
- límites operacionales de transacciones multi-documento;
- existencia del índice único de `event_store` en los entornos desplegados.

*Otros pendientes, que no forman parte de la Enmienda 1 y solo se citan aquí:*
- **R3:** solicitud de cambio, fuera del primer corte.
- **R4:** `CLOSE_ON_TARGET + CLOSE`.

Ambos están registrados en `convocatoria-resumen.md` §6.15 e `implementation_plan.md` §14.2. P8, P9 y P10 pertenecen a la Enmienda 2 (BORRADOR) y se citan en §9.

### 7.3 Dependencias [E1 §7]

La Enmienda 1 no modifica estos documentos. Declara lo que cada uno debe incorporar en su propia enmienda.

**ADR-032 (autorización) — [DEPENDENCIA]:**
- Conjunto cerrado y explícito de comandos contextuales del administrador responsable (hoy `REGISTER_PHYSICAL_ASSET`, `SPLIT_PHYSICAL_ASSET`).
- Redacción normativa del respaldo del `REPRESENTATIVE`.
  - **[ESTADO]** Verificado: no existe en el código; hoy el código lo rechaza en todos los casos.
  - Evidencia: `RoleAuthorizationPolicy.java:21`; tests `RoleAuthorizationPolicyTest.java:66-68`, `RegisterPhysicalAssetFromDonationIntegrationTest:187`, `PhysicalAssetCommandServiceAuthorizationTest:106`.
- Condición de activación de ese respaldo ahora que un `ADMINISTRATOR` puede ser responsable: **[PENDIENTE] P6** (§7.2).
- Puerto contextual nuevo:
  - definido en `contracts`, implementado por `convocatoria` y consumido por `core`, con el mismo patrón que `IdentityPrincipalPort`, sin ampliarlo;
  - mínimo y cerrado: "¿puede este principal ejercer esta capacidad contextual sobre esta convocatoria?".
- Ventana entre comprobar y ejecutar aceptada explícitamente.
- Activos sin convocatoria: solo autoridad ordinaria de `EMPLOYEE`, sin respaldo ni capacidad contextual.

**ADR-029 (`PhysicalAsset`) — [DEPENDENCIA]:**
- `campaignRef` en `PhysicalAsset`: heredado del `Fund` en el Camino A, recibido en el comando en el Camino B, heredado del padre en el split. Inmutable tras el registro.
- Compatibilidad y migración: §7.4.

**Contratos/API (ADR-041 y `claude/front-fase2.md`) — [DEPENDENCIA]:**
- `POST /campaigns/{campaignRef}/employees` debe actualizarse a las dos operaciones de §2.4 (E1 §4.1). Ver **[PENDIENTE] P4** (§7.2).
- Los comandos de §2.7 aceptan `commandId`; el contrato HTTP de cada uno debe incluirlo (condición de despliegue web de `claude/front-fase2.md` P-W1).
- *Nota de la consolidación:* la ruta `claude/front-fase2.md` es la que cita la Enmienda 1. No existe en el repositorio (`auditoria-inventario-documental-convocatoria.md` §1.4).

**`core` y ADR-040 (IA) — [DEPENDENCIA]:**
- La distinción entre "confirmado por el proveedor" y "declarado por la organización" (§2.6, E1 §5.2) vive en `DonationIntent`, pero ni la vista pública ni la IA la reciben hoy:
  - la vista pública de seguimiento se construye desde los eventos de `Fund` en `core`, cuyos payloads no llevan `confirmationSource`;
  - los hechos de auditoría de IA (ADR-040) tampoco la reciben.
- Cómo llega esa información a la vista de seguimiento y a los hechos de auditoría se decide en `core`/ADR-040 (y, si toca un contrato HTTP, en ADR-041).
- No es trabajo del módulo `convocatoria`, que solo debe conservar `paymentMethod` y `confirmationSource` en la intención.

### 7.4 Compatibilidad y migración [E1 §9]

**`campaignRef` en `PhysicalAsset`:**
- **[ESTADO]** Hoy ningún activo tiene `campaignRef`: ni `PhysicalAsset` (`PhysicalAsset.java:22-43`) ni sus payloads (`AssetRegistered(V2)Payload`, `AssetSplit(V2)Payload`). En `Fund` existe y es opcional.
- **[DEPENDENCIA]** Incorporarlo exige una nueva versión de `ASSET_REGISTERED` y del evento de split, con upcaster para las versiones anteriores. El diseño exacto (número de versión, forma del upcaster) pertenece a la enmienda de ADR-029.
- **[DECISIÓN]** Los activos existentes y los que provengan de un `Fund` con `campaignRef = null` quedan sin convocatoria. Su convocatoria **nunca** se infiere recorriendo proyecciones. Solo admiten la autoridad ordinaria de `EMPLOYEE`.
- **[DEPENDENCIA]** Si se descarta explícitamente cualquier relleno retroactivo (backfill) de `campaignRef` en activos existentes, eso se fija en la enmienda de ADR-029. Precedente: ADR-028/029 prohibieron backfill y valores centinela para `organizationRef` en datos v1.

**Piezas nuevas de `convocatoria`:**
- **[ESTADO, a la fecha de la Enmienda 1]** El módulo `convocatoria` no existía en el código.
- `Convocatoria`, `CampaignAssignment`, `DonationIntent`, la solicitud de cambio de configuración, el registro de dinero no aceptable y el registro de comandos procesados del módulo nacen con los campos de la Enmienda 1: no hay datos previos que migrar.
- El estado posterior de la implementación está en `estado-fase6.md` §3bis.

**Contrato HTTP:** **[DEPENDENCIA]** El cambio de `POST /campaigns/{campaignRef}/employees` afecta a consumidores ya diseñados (`claude/front-fase2.md`). Su compatibilidad se resuelve con P4.

## 8. Trazabilidad de verificación

Este ADR se apoya en código real inspeccionado durante la sesión, no solo en documentación conceptual: `FundCommandService.java`, `MongoEventStoreAdapter.java`, `TraceabilityInfrastructureConfig.java`, `TraceabilityApplication.java` — todos citados en línea en las secciones correspondientes. Las firmas exactas de `CreateConvocatoria`/`AssignEmployee`/`RemoveResponsible` no fueron verificadas contra código (no existe todavía) — son especificaciones propuestas derivadas del review, y deben tratarse como tales hasta la implementación.

**[E1 §10.2] [ESTADO]:**
- Verificación de código del 2026-09-30 (rama `develop`, HEAD `673eda92fcb18626788804a3a48b41730e6784fb`, solo lectura, tests no ejecutados), registrada en `convocatoria-resumen.md` §6.9.
- Las firmas de las operaciones nuevas de la Enmienda 1 eran propuestas no verificadas contra código, porque el módulo `convocatoria` no existía todavía.
- Las firmas implementadas en el primer corte se describen en `implementation_plan.md` §17. Esa sección es descriptiva y no es normativa.

## 9. Enmienda 2 (BORRADOR) y referencia externa a ADR-043 — relación con este ADR

*Sección informativa añadida por la consolidación. **No es normativa.***

- **Qué propone la Enmienda 2** (`ADR-037-enmienda-2-convocatoria.md`, BORRADOR, 2026-10-02):
  - separar la confirmación de una `DonationIntent` de la aplicación de sus fondos (§3);
  - barrera de la aplicación = reclamo del comando de sistema `APPLY_FUNDS` por intención, con `_id = {commandType, commandId}`, en el registro de comandos procesados del módulo (§3.3);
  - estado terminal `FUNDING_REJECTED`, solo por rechazo permanente (§4);
  - confirmación manual solo por `ADMINISTRATOR` de la organización y nunca para intenciones de pasarela (§3.1);
  - recuperación automática (§5), cuyo mecanismo remite a ADR-043, documento externo;
  - pendientes P8 (mensaje de outbox de la génesis), P9 (resuelto, opción a) y P10 (trazabilidad de `FUNDING_REJECTED`) (§7).
- **Secciones de este ADR a las que afectaría:**
  - §2.6: estados, paso 3 y barrera de E1 §5.3;
  - §2.6 E1 §5.2: frase de N9;
  - §2.7: comandos de sistema.
- **Origen:** las decisiones F-1, F-2, D1–D7 y P9 están aprobadas como decisiones humanas del 2026-10-02 (`convocatoria-resumen.md` §6.20). El **texto** de la enmienda no está aprobado (Enmienda 2 §9).
- **Referencia externa — ADR-043** (`ADR-043-recuperacion-aplicacion-fondos-convocatoria.md`, estado declarado: Propuesto).
  - **Documento de otro bloque** (`convocatoria-resumen.md` §6.21). Este ADR no lo integra, no lo resume como norma propia y no depende de su contenido para su texto normativo.
  - La Enmienda 2 (`:6`) lo cita como ADR asociado y condiciona su propia aprobación a la de ADR-043 (Enmienda 2 §9). Es una dependencia externa de la Enmienda 2.
  - De la recuperación, a Convocatoria le corresponden la decisión humana D2 (`convocatoria-resumen.md` §6.20) y la consulta de intenciones recuperables que implementa el módulo (`implementation_plan.md` §17.5).
- **Integración futura:** si la Enmienda 2 se aprueba, se integrará en §2.6, §2.7 y §7 con la misma convención (marca **[E2 §x]** y texto sustituido conservado). Hasta entonces, el texto normativo de esas secciones es el de ADR-037 + Enmienda 1, con la contradicción CD-03 registrada en §2.6.

## 10. Registro de la consolidación documental (2026-10-03)

- **Qué se hizo:**
  - Se integró en este documento el contenido normativo de la Enmienda 1 (APROBADA), siguiendo los cambios [ESTADO] que la propia Enmienda 1 prescribe en §10.1–§10.2 y copiando sus decisiones con sus etiquetas.
  - No se eliminó ningún texto original de ADR-037. Las partes modificadas siguen en su lugar, señaladas por los bloques [E1] y las notas de la consolidación.
- **Qué no se hizo:**
  - No se integró la Enmienda 2 (BORRADOR) ni ADR-043 (Propuesto).
  - No se cambió el título ni el número.
  - No se resolvió ninguna contradicción.
- **Fuentes conservadas sin cambios:**
  - `ADR-037-enmienda-1-convocatoria.md`;
  - `ADR-037-enmienda-2-convocatoria.md`.
- **Documento externo no modificado:** `ADR-043-recuperacion-aplicacion-fondos-convocatoria.md`, de otro bloque.
- **Anotaciones previas sustituidas.** Las añadió una sesión anterior, sin commit. Presentaban la Enmienda 2 (BORRADOR) como sustitución ya vigente. Se reemplazaron por las marcas *[Enmienda 2 — BORRADOR, no normativa]* y se conservan aquí literalmente:
  - Cabecera, línea 5: `**Enmendado por:** Enmienda 1 (`ADR-037-enmienda-1-convocatoria.md`) y Enmienda 2 (`ADR-037-enmienda-2-convocatoria.md`, BORRADOR, 2026-10-02). La Enmienda 2 sustituye el paso 3 y la lista de estados de §2.6: confirmación y aplicación de fondos son actos separados; nuevo estado terminal `FUNDING_REJECTED`.`
  - §2.6, bajo la línea `status`: `                         (+ FUNDING_REJECTED, Enmienda 2 §4)`
  - §2.6, paso 3: `3. *[Sustituido por la Enmienda 2 §3: la correlación válida confirma la intención (`PENDING → CONFIRMED`) y la aplicación posterior — barrera `APPLY_FUNDS` + ledger + génesis + outbox — es un acto separado del sistema.]* Texto original: si la correlación es válida: invoca `clearFundsGenesis(commandId, fundId, ...)` usando siempre el `fundId` de `DonationIntent`, nunca uno generado en el adaptador — sujeto únicamente a las reglas de `CampaignFundingLedger` (§2.2), sin volver a comprobar `status` (D1, ver §2.6bis).`
- **Informe:** `auditoria-ejecucion-consolidacion-convocatoria.md`.
