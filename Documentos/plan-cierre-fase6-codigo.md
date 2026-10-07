# Plan de código — Cierre de la Fase 6

**Estado:** PROPUESTO (2026-10-07). Necesita aprobación de Carlos (regla 3.4) antes de escribir código. **No autoriza código** de ningún bloque: cada uno exige su PR con aprobación de una persona distinta del autor.
**Origen:** decisiones de Carlos del 2026-10-07 (`estado-fase6.md` §0.4): ADR-045 y Enmienda 2 aprobados; `ASSET_SPLIT` entra en la demo; criterio de cierre = `golden-path.md` §8, criterios 1–19.
**Fuentes verificadas en código:** `develop` en `58da613`. Las referencias a archivos y líneas son de ese commit.

## Criterio de cierre y reglas

**Criterio de cierre:** `golden-path.md` §8, criterios 1–19 (enmendado el 2026-10-07), cumplidos de punta a punta con evidencia real, y sin ninguna pieza fusionada que no tenga ADR aprobado.

**Reglas comunes a todos los PR:**
- una rama y un PR por bloque;
- aprobación de una persona distinta del autor (§3.2);
- `mvn clean test` del reactor con salida literal de Surefire (§2.3);
- ADR aprobado antes del merge si se introduce una dependencia o un mecanismo nuevo (§3.5).

## Bloques

### B0. Decisiones previas (sin código)

| # | Decisión | Dueño | Bloquea |
|---|---|---|---|
| D-P8 | ✅ **Cerrado el 2026-10-07: opción A (Carlos)**. La génesis no escribe outbox (enmienda de ADR-037 §2.3; `propuesta-P8-outbox-genesis.md`) | `core` | — |
| D-JWT | Librería JWT y gestión del secreto de firma. Es una dependencia nueva: ADR o enmienda de ADR-038 §2.7 (§3.5). **ADR-047 APROBADO — Carlos, 2026-10-07** (plan de B3: `plan-b3-jwt.md`) | Identidad | B3 |
| D-B39 | Aprobar la Enmienda 1 de ADR-039 (P1–P5) | Blockchain | B4 |
| D-IA | ADR-040 C2–C5 y C8 | IA | B5 |
| D-API | Fichas de los endpoints de la demo, empezando por **CV-01** (ya en el repositorio) y CV-07; `GET /account/donations` y la relación `accountId` ↔ `donorRef` (criterios 4 y 6); contrato del **webhook simulado** (condiciones de B6); consulta de estado de la intención con lectura del `trackingCode` (dirección Q-v2-7) | API / Convocatoria | B6 |
| D-ASSET | `dispatch`/`receive` sin método público en `PhysicalAssetCommandService` | `core` | B6 (paso 5 del recorrido) |
| D-SPLIT | La división en la demo (**`ASSET_SPLIT` entra en la demo — Carlos, 2026-10-07**). Ver el detalle verificado abajo. **Propuesta: `propuesta-d-split.md` (PROPUESTO, 2026-10-07)** | `core` (+ Convocatoria e IA por `campaignRef`) | B1-bis, B5, B6, B7 |
| D-CAMPAIGN | `campaignRef` en `PhysicalAsset` (registro y división): **enmienda de ADR-029, con evolución del esquema de eventos**. Caminos esbozados en `implementation_plan.md` §11: el camino A lo hereda del `Fund`, el camino B lo recibe en el comando y la división lo hereda del padre; nunca se infiere. La enmienda debe fijar: (1) **payloads nuevos** `AssetRegisteredV3Payload` y `AssetSplitV3Payload` (hoy la última versión es la 2.0 de cada uno, `EventPayloadRegistry.java:31,36`), sin tocar los v2 ni los eventos históricos; (2) **lectura de los v1/v2 sin `campaignRef`**: quedan sin convocatoria y la narrativa los excluye, nunca se infiere ni se rellena (ADR-030, ADR-040 §8); (3) **camino A verificado**: `Fund` persiste `campaignRef` en `FUND_REGISTERED`/`FUNDS_CLEARED` v2 y lo reconstruye (`Fund.java:90,103,194-213`), así que hay de dónde heredarlo; `clearFundsGenesis` lo recibe | `core` | **B5 (criterio 14, aunque no haya ninguna división)**, B1-bis |

> **D-CAMPAIGN, 2026-10-07:** `ADR-029-enmienda-1-campaignref.md` **APROBADA — Carlos, 2026-10-07** (Q1–Q5 según la recomendación). La implementación necesita su propio plan aprobado (regla 3.4). La redacción reveló el hallazgo **H-PROJ**: las proyecciones (`DonationProjectionHandler`, `DonationAuditFactsHandler`) solo reconocen payloads v1 y el código escribe v2. Se propone el bloque **B-PROJ** (abajo).

**D-SPLIT — verificación en el código (2026-10-07), respuesta a los cuatro puntos:**
1. **Servicio de aplicación:** existe `PhysicalAssetCommandService.splitPhysicalAsset` (`:304`, tras #45). Autoriza con `CommandType.SPLIT_PHYSICAL_ASSET` sobre el `organizationRef` del padre; acepta cualquier `ActorRef`, incluido `HumanActor`; es idempotente por `commandId`.
2. **Saga del hijo: no existe.** El servicio llama a `appendAndOutbox(..., outboxMessages = null, ...)` y **no escribe ningún mensaje de outbox**. `SplitPhysicalAssetSagaPolicy` no existe; la única política es `AssetRegisteredSagaPolicy`, como ya decía el documento maestro §7.1 y `estado-fase5.md:308`. **El stream hijo nunca se crea**, así que el hijo no puede despacharse ni entregarse. La compensación (`PhysicalAsset.compensateSplit`) existe en el dominio, pero **ningún caso de uso ni saga la invoca**. El diseño está decidido (ADR-005/007/008/009); falta la implementación.
3. **`campaignRef`:** *(resuelto por D-CAMPAIGN, #45: `AssetSplitV3Payload` lleva `campaignRef` y el hijo lo hereda sin recalcularlo; texto original:)* el hijo **no** lo hereda, porque **ningún `PhysicalAsset` tiene `campaignRef`**: el dominio `physicalasset` no tiene ese campo. `AssetSplitV2Payload` lleva al hijo `organizationRef`, `donorRef`, `donationRef`, `rootAssetRef`, ubicación y custodio, pero no `campaignRef`. Por eso hace falta D-CAMPAIGN, una enmienda a ADR-029 para todo `PhysicalAsset` y no solo para los hijos. Sin ella, la narrativa de convocatoria (ADR-040 §8) no cuenta ningún activo.
4. **Contrato:** `api-contract-matrix.md:54`: `POST /physical-assets/{assetRef}/split`, `SPLIT_PHYSICAL_ASSET`, respuesta "assets resultantes", "CONTRATO DEFINIDO / integración P7 pendiente". Hay que fijar en la ficha la respuesta, porque el hijo nace de forma asíncrona y no puede devolverse en la misma llamada salvo como `childAssetRef` pendiente.

### B1-bis. `core`: saga de la división (dueño `core`; tras D-SPLIT y D-CAMPAIGN; en paralelo con B1)

- **`splitPhysicalAsset`** escribe en la misma transacción el mensaje de outbox de la saga.
- **`SplitPhysicalAssetSagaPolicy`** registra el stream hijo con:
  - la cantidad extraída y la unidad;
  - la herencia de `organizationRef`/`donorRef`/`donationRef`/`rootAssetRef`, y de `campaignRef` según D-CAMPAIGN;
  - ubicación y custodio del payload.
- **Fallo permanente:** dispara `compensateSplit` en el padre (ADR-008), con la protección contra la doble compensación (ADR-009).
- **Tests:** el hijo existe con la cantidad y las referencias heredadas; la cantidad del padre se reduce; compensación ante fallo permanente; reintento sin hijo duplicado.

### B1. `core`: T1 + P8 (dueño `core`; tras D-P8)

- **T1:** variante de `clearFundsGenesis` para el orquestador.
  - No pasa por `CommandRetryTemplate`.
  - Se une a la transacción externa.
  - Propaga la excepción transitoria **con su causa** (hoy `ConcurrencyRetryExhaustedException` la descarta, `CommandRetryTemplate.java:24,35`).
  - El método actual no cambia para los demás llamadores.
- **P8:** cerrado por D-P8 (opción A): sin código. `List.of()` (`FundCommandService.java:94`) queda como diseño. **B1 = solo T1.**
- **Deuda aparte (no bloquea):** `OutboxSagaCoordinator` ignora en silencio los mensajes cuyo `sagaType` no tiene política, y `fetchPendingMessages` lee sin límite (`propuesta-P8-outbox-genesis.md` §5). Conviene una alerta o cuarentena para `sagaType` desconocido y una lectura por lotes.
- **Tests:**
  - un conflicto dentro de una transacción externa no reintenta y su causa llega al llamador;
  - un rollback externo no deja evento ni outbox;
  - `ProcessedCommandIdempotencyIntegrationTest` sigue en verde.

### B2. `app` + `convocatoria`: ADR-045 (dueño Carlos; tras B1)

- **`convocatoria`:**
  - metadatos de recuperación (`applicationAttempts`, `firstApplicationAttemptAt`, `lastApplicationAttemptAt`, `lastApplicationError`, `applicationQuarantined`);
  - `fundsAppliedAt`, escrito en la misma transacción que el reclamo `APPLY_FUNDS`;
  - escritura condicional de los metadatos después del rollback;
  - consulta reescrita: excluye las ya aplicadas y las que están en cuarentena, ordena por `applicationAttempts ASC, lastApplicationAttemptAt ASC, _id` y usa un índice parcial;
  - `releaseApplicationQuarantine` auditado.
- **`app`:**
  - `FundsApplicationOrchestrator`, para la Tx 2 de ADR-045: reclamo + ledger + génesis de B1 + outbox;
  - **un único caso de uso de confirmación**, que hace Tx 1 → disparo inmediato de Tx 2, y que comparten el webhook simulado y el futuro webhook real;
  - `FundsApplicationRecoveryScheduler`, con el patrón de `BlockchainAnchorProducer`:
    - un intento por intención y por ejecución;
    - la taxonomía de ADR-045 §2.3;
    - `retry-window` de 4 h y `max-attempts` configurable;
    - `enabled=false` por defecto en producción;
  - JMX para liberar la cuarentena, métricas de P9 y de cuarentena, `runId`/`correlationId`.
- **DoD:** ADR-045 §6 completo, incluidas la concurrencia con dos instancias (réplica real) y la prueba de equidad.

### B3. Identidad + `api`: autenticación JWT (dueño Identidad; tras D-JWT; en paralelo con B1/B2)

- `contracts`: `AuthenticateAccountPort`, `TokenIssuerPort` y `TokenIssuanceException`.
- `identity`:
  - `authenticate` responde **401** con código y cuerpo idénticos para los tres fallos (email inexistente, contraseña incorrecta, `INACTIVE`), según ADR-038 §2.7 y la regla "401 uniforme" de `propuesta-apis-fase6.md` §4;
  - corrige las divergencias ID01-D1…D4 de ese documento.
- `api`:
  - `LoginController` y el filtro JWT, separados por ruta del de tracking (T-36);
  - JWT `sub/iat/exp/firma`;
  - `resolvePrincipal` en cada request;
  - un fallo de `issue()` da 500.
- **Tests:** indistinguibilidad de los tres fallos, expiración, firma inválida y cuenta desactivada después de emitir el token.

### B4. Blockchain: B-9/B-10 (dueño Blockchain; tras D-B39)

- Antes de implementar, la verificación P4: ¿existen eventos anteriores al corte `0579f41` en algún entorno?
- Implementación de la Enmienda 1 de ADR-039, con su DoD §5 (10 tests).

### B-PROJ. `core`: proyecciones de los eventos v2/v3 — *actualizado 2026-10-07: corrección prioritaria con plan propio en `plan-b-proj.md`, **APROBADO — Carlos, 2026-10-07**, que añade el defecto del origen de la secuencia. Orden: PR 1 (corrección) → D-CAMPAIGN → PR 2 (reconstrucción, tras enmendar ADR-042)*

- **Hallazgo H-PROJ (verificado):** `DonationProjectionHandler` y `DonationAuditFactsHandler` solo tratan `FundRegisteredPayload`, `FundsClearedPayload` y `AssetRegisteredPayload` de la versión 1. Hoy se escriben las versiones 2: la génesis de ADR-045 no proyecta importe, moneda ni `campaignRef`, y todo activo registrado acaba en `MissingDependencyException` (detalle y citas en la Enmienda 1 de ADR-029, §6).
- **Alcance:** los manejadores tratan las versiones 1, 2 y (tras D-CAMPAIGN) 3; tests de proyección con eventos reales de `clearFundsGenesis`, `registerPhysicalAsset` y `registerPhysicalAssetFromDonation`.
- **Fuera de alcance, decisión aparte:** dónde se proyecta un activo del Camino B, que no tiene `Fund` (con C5 de ADR-040).
- **Bloquea:** B5 y B7 (criterios del golden path que consultan la trazabilidad y la narrativa).

### B5. IA: narrativa de convocatoria (dueño IA; tras D-IA y **D-CAMPAIGN + su implementación en `core`**)

- Productor de `CampaignAuditFactsPort`, en el módulo que fije C5.
- Consumidor en `ai` que reutiliza el pipeline de `DonorReportGenerator`.
- Sin "familias" ni activos sin `campaignRef` (ADR-040 §8).

### B6. API del recorrido de la demo (dueño API/Convocatoria; tras D-API, B2 y B3)

**Alcance según `propuesta-apis-fase6.md` §6:**
- verificar organización;
- **división de activo** (`POST /physical-assets/{assetRef}/split`, tras B1-bis) y su pantalla en `paxfide-web`;
- CV-01 crear convocatoria y CV-02 asignar responsable;
- CV-07 detalle por `publicCode`;
- CV-11 crear intención;
- consulta del estado de la intención con lectura del `trackingCode`;
- **webhook simulado**;
- registrar activo y dispatch/receive/deliver (D-ASSET);
- tracking (Fase 3);
- narrativa de convocatoria.

Todo con `HumanActor` construido desde el JWT.

**Fuera de la demo:** endpoint de registro de organización (R9), cola de verificación y **endpoint de confirmación manual** (Q-v2-6 (a′)), además de la predicción (mock server externo).

**Webhook simulado: requisitos obligatorios (sin ellos no se fusiona):**
1. Llama al **mismo** caso de uso de confirmación de `app` que usará el webhook real (Tx 1 → Tx 2 de ADR-045). No tiene atajo que confirme o aplique fondos directamente.
2. Solo existe en los perfiles `dev`/`demo` (bean condicional a un perfil o propiedad, ausente por defecto). Un test verifica que en el perfil de producción la ruta no existe (404).
3. Registra `confirmationSource = SIMULATED` (o el valor que fije la ficha), distinto de cualquier fuente real, para que una confirmación de demo nunca se confunda con un pago real. Test incluido.

**Semilla de la organización (Q-v2-1):**
- Un *seeder* de perfil `demo` que invoca los **servicios de aplicación** existentes: `CreateAccountService`, `CreateOrganizationService`, el bootstrap de plataforma y `VerifyOrganizationService` o el endpoint de verificación.
- **Nunca inserciones directas en Mongo**, porque se saltarían la verificación de `Organization` y el audit log (mismo criterio que ADR-044 P5).
- Un test comprueba que la semilla deja entradas en el audit log.

### B7. Ejecución del golden path (dueño Carlos)

- El recorrido de `golden-path.md` enmendado (enmendado el 2026-10-07), con su tabla real/fixture: webhook simulado; anclaje en testnet confirmado antes de la sesión. **El guion espera a que aparezca el hijo de la división** (nace de forma asíncrona, por la saga) antes de despacharlo.
- Evidencia persistida en `Documentos/evidencia-fase6/`.
- Cierre de la Fase 6 en `estado-fase6.md` y en el documento maestro.

### Orden y paralelismo

```
B0 (decisiones, todas en paralelo)
 ├─ D-P8 → B1 → B2 ──────────────┐
 ├─ D-SPLIT + D-CAMPAIGN → B1-bis ┤
 ├─ D-JWT → B3 ──────────────────┤
 ├─ D-B39 → B4 (independiente)    ├→ B6 → B7
 ├─ D-CAMPAIGN (core) + D-IA → B5 ┤
 └─ D-API, D-ASSET ──────────────┘
```

**Dos cadenas críticas:**
- **dinero:** D-P8 → B1 → B2 → B6 → B7;
- **narrativa de convocatoria:** D-CAMPAIGN → (implementación en `core`) + B-PROJ → B5 → B7. Sin `campaignRef` en los activos, el criterio 14 cuenta cero unidades aunque no haya ninguna división.

**Aviso de alcance:** con la división (D-SPLIT, B1-bis) y D-CAMPAIGN, el cierre de la Fase 6 creció. `plan-cierre-fase6-codigo.md` incluye una sección "Prioridad según la fecha de la demo" que pide a Carlos la fecha y ordena los bloques por las dos cadenas críticas, para que decida qué se pospone si no llega a todo.

## Prioridad según la fecha de la demo

Con la división (D-SPLIT, B1-bis) y `campaignRef` (D-CAMPAIGN), el alcance creció. **Pendiente: que Carlos fije la fecha de la demo.** Con esa fecha, el orden de corte si no se llega a todo es:

1. **Imprescindible — cadena del dinero:** D-P8 → B1 → B2, y de B6 lo mínimo para el paso 3 del recorrido (CV-01, CV-07, CV-11, webhook simulado).
2. **Imprescindible — cadena de la narrativa:** D-CAMPAIGN → `campaignRef` en `core` → B5 (criterio 14).
3. **Necesario para los criterios 15–19:** D-SPLIT → B1-bis, más el endpoint de división.
4. **Necesario para los criterios 4 y 6:** B3 (JWT) y `GET /account/donations` (D-API).
5. **Independiente del recorrido:** B4 (B-9/B-10). No bloquea ningún criterio del §8, pero sí la regla de "nada fusionado sin ADR" si se toca blockchain.

Si la fecha no permite todo, posponer criterios exige una decisión explícita de Carlos y una enmienda de §8. No se puede omitir en silencio.
