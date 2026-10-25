# Estado — Fase 6

**Última actualización:** 2026-10-07 — auditoría código vs. documentación sobre `develop` (`auditoria-fase6-codigo-vs-documentacion.md`). Las secciones históricas (§3, §3bis, §7) se conservan como registro; el estado vigente es §0 y la tabla de §2.
**Alcance de este documento:** refleja únicamente resultado de ejecución real verificado en esta sesión (output de Surefire, código inspeccionado). No infiere estado de fases anteriores por memoria — donde algo se toma de `estado-fase5.md`/`documento-maestro-proyecto.md`, se cita como tal, no se reconstruye.

---

## 0. Estado vigente (auditoría 2026-10-07, `develop` en `e269985`)

| Capa | En `develop` | Abierto |
|---|---|---|
| Convocatoria | Primer corte + confirmación/aplicación de fondos del módulo (barrera `APPLY_FUNDS`, `FUNDING_REJECTED`, consulta de recuperables). Commit `81cf87c`, fusionado en PR #29. `app → convocatoria` con `OrganizationVerificationAdapter` real (`e269985`) | Orquestador de aplicación de fondos, disparo y scheduler (`app`); T1 y P8 en `core`; P1–P7, R3, R4; ~~aprobación de Enmienda 2 y ADR-045~~ **aprobadas por Carlos el 2026-10-07** (§0.4); **decisión de producto pendiente:** valor de `CONVOCATORIA_BANK_TRANSFER_EXPIRATION` (provisional `PT72H` solo en `dev`/tests, §0.4) |
| Identidad | ADR-038 tareas 1–8 fusionadas (`aebedb2`): `HumanActor`, Platform Administrator con bootstrap, verificación de `Organization`, reintentos C+ | ~~JWT/autenticación HTTP (ADR-038 §2.7)~~ **hecho en B3** (§0.8), endpoints de plataforma, `AccountNotFoundException` en `contracts`, deuda ADR-038 §9.4 |
| Blockchain | Productor de `MerkleBatch` (Fases 1–3), `IntegrityVerificationPort`, recuperación automática de batches `COLLECTING` abandonados, partición contigua por presupuesto, `leafHashes` persistidos, `verifyAllAnchored` en streaming | **B-9:** la verificación no recalcula `eventHash` desde el payload ni comprueba la cadena `previousHash`. **B-10:** `COLLECTING` sin tope ni estado de salida (bloqueo por cabeza de cola; riesgo latente: un batch ilegible detiene `produceBatch`). Propuesta en `ADR-039-enmienda-1-blockchain.md` (BORRADOR). Además: migración de batches legacy, `correlationId` de scheduler, límite/paginación explícita de `verifyAllAnchored` (§4) |
| IA | Pipeline de donación individual (`DonorReportGenerator` sobre `AuditFactsPort`). `CampaignAuditFactsPort`/`CampaignAuditFactsDTO` como contrato | Productor y consumidor de `CampaignAuditFactsPort` — bloqueados por ADR-040 C2–C5; C8 |
| APIs + Frontend | Solo los 3 endpoints públicos de Fase 3 | Todos los endpoints de Fase 6 (ADR-041) |

Evidencia de tests de esta auditoría: §0.2.

### 0.1 Colisión de número ADR-043 — resuelta en numeración (2026-10-07)

Los documentos de Convocatoria citaban `ADR-043-recuperacion-aplicacion-fondos-convocatoria.md` (recuperación automática de la aplicación de fondos, Propuesto), que **no está en el repositorio**. El ADR-043 presente es `ADR-043-frontend-movil-paxfide-mobile.md`, y el 044 está reservado al componente predictivo en Python (documento fuera del repositorio).

**Dirección aprobada por Carlos, 2026-10-07:** el ADR de recuperación de fondos pasa a ser **ADR-045**. La Enmienda 2 (BORRADOR vivo) ya está corregida. Los documentos cerrados conservan "ADR-043" como registro histórico; en ellos, y en las secciones históricas de este documento, "ADR-043" en contexto de recuperación de fondos significa ADR-045. **Pendiente:** subir ADR-044 y ADR-045 al repositorio y aprobarlos o rechazarlos.

### 0.2 Evidencia (ejecución de esta sesión)

`mvn clean test -fae` sobre `develop` `e269985` (árbol con cambios solo en `Documentos/`), Docker real, terminado el 2026-10-07T01:08:46Z. Extracto literal persistido en `evidencia-fase6/reactor-e269985-2026-10-07.txt` (`sha256` del log completo `93e22638b0a67bfb9505094b2905aeb2bcee498e7f8773996a1e13fa7765e9a8`). Resumen literal de Surefire por módulo:

```
contracts:    [INFO] No tests to run.
core:         [INFO] Tests run: 229, Failures: 0, Errors: 0, Skipped: 0
crypto:       [INFO] Tests run: 50, Failures: 0, Errors: 0, Skipped: 0
ai:           [INFO] Tests run: 19, Failures: 0, Errors: 0, Skipped: 0
api:          [INFO] Tests run: 34, Failures: 0, Errors: 0, Skipped: 0
identity:     [INFO] Tests run: 261, Failures: 0, Errors: 0, Skipped: 0
convocatoria: [INFO] Tests run: 194, Failures: 0, Errors: 0, Skipped: 0
app:          [INFO] Tests run: 42, Failures: 0, Errors: 0, Skipped: 0
              [INFO] BUILD SUCCESS
```

Las 9 entradas del reactor son el pom padre y 8 módulos. `app` pasa de 27 a 42 tests por el adaptador de `OrganizationVerificationPort` (`e269985`) y los tests de recuperación de `COLLECTING`.

### 0.4 Cierre de decisiones (2026-10-07, Carlos)

- **ADR-045 APROBADO** (`ADR-045-recuperacion-aplicacion-fondos.md`, subido en `4b5e528`) y **Enmienda 2 de ADR-037 APROBADA** con C1–C3. Es una aprobación en parte retroactiva: el incumplimiento de la regla 3.5 del PR #29 sigue registrado. Autorizan el diseño; el código del orquestador, el disparo y el scheduler sigue bloqueado por T1/P8 (`plan-cierre-fase6-codigo.md`).
- **Numeración:** recuperación de fondos → ADR-045; frontend web `paxfide-web` → **ADR-046** (repositorio `PaxFide`; antes ADR-042); ADR-044 reservado al predictivo; siguiente libre ADR-047 (`documento-maestro-proyecto.md` §5).
- **Renumeración en `PaxFide` (Parte A′), verificada el 2026-10-07:** `develop` de `Toffy22Cj/PaxFide` contiene `ADR-046-frontend-web-paxfide-web.md` y el commit de merge del PR #3 (`09229fc`). Las 8 menciones restantes a "ADR-042" son notas históricas o citan el ADR-042 del backend. **Discrepancia conocida:** la API de GitHub muestra el PR #3 como "cerrado sin fusionar", aunque su commit de merge está en `develop` (probablemente se fusionó a mano). El resultado es correcto; no es un PR perdido.
- **I-5** (adaptador de `OrganizationVerificationPort` que se salta la capa de aplicación de `identity`): dueño **Carlos**.
- **`CONVOCATORIA_BANK_TRANSFER_EXPIRATION`:** valor **provisional** `PT72H` (3 días) solo en el perfil `dev` (`app/src/main/resources/application-dev.yml`) y en tests; **sin valor por defecto en producción**. El valor de producto sigue pendiente. Criterio para fijarlo: plazo de compensación bancaria más el tiempo que tarda la organización en confirmar; descartar plazos de pocas horas.
- **Golden path y criterio de cierre de la Fase 6:** **`ASSET_SPLIT` entra en la demo — Carlos, 2026-10-07.** `golden-path.md` incorpora §7 (consultas y evidencia) y §8 (criterios de aceptación 1–19), tomados de la copia del proyecto que nunca se había commiteado. Manda el §3 del repositorio, con las exclusiones añadidas. **La Fase 6 se cierra cuando se cumplen los criterios 1–19 con evidencia real.**
- **Huecos verificados en el código para ese criterio:** la saga que crea el hijo de una división no existe, y ningún `PhysicalAsset` tiene `campaignRef` (sin él, la narrativa de convocatoria cuenta cero unidades). Ver `plan-cierre-fase6-codigo.md`, D-SPLIT y D-CAMPAIGN.

- **D-P8 cerrado — opción A (Carlos, 2026-10-07):** la génesis de `Fund` no escribe mensaje de outbox. Se enmendó ADR-037 §2.3, se corrigió ADR-045 §1 (con conformidad de Carlos) y se cerró P8 en la Enmienda 2. En la cadena del dinero, B1 queda solo en **T1**.

### 0.5 Decisiones y estado tras B2 (2026-10-07, Carlos)

- **B2 (ADR-045) hecho:** T1 en `core` (#34), B2a en `convocatoria` (#35) y B2b en `app` (#36): orquestador de la Tx 2, disparo inmediato, scheduler de respaldo (deshabilitado por defecto) y JMX.
- **D-P8 "sin outbox": confirmado por Carlos.**
- **Excepción general a la regla 3.2 (`reglas-equipo-y-agentes.md` §3.2):** mientras Carlos trabaje en solitario sobre el backend, los PR se fusionan sin segundo revisor y la evidencia de tests sustituye a esa aprobación. Decisión consciente de Carlos. Primeros PR cubiertos: #35–#40.
- **Enmienda 1 de ADR-029 (D-CAMPAIGN) APROBADA**, Q1–Q5 según la recomendación: puerto en `contracts`, `campaignRef` opcional en el Camino B, `OPEN` en el registro (limitación escrita), H1 solo con intenciones.
- **ADR-047 (JWT) PROPUESTO**, Q1–Q5 pendientes.
- **B-PROJ (`plan-b-proj.md`) APROBADO**, siguiente bloque. Las proyecciones no procesan ningún stream real: el event store numera la génesis como 1 y los manejadores la esperan en 0 (D-SEQ: se mantiene 1), y además ignoran los payloads v2. Sin entornos con datos reales. El PR 2 (reconstrucción por JMX) necesita antes una enmienda de ADR-042.
- **Sin fecha de demo.** Orden por dependencias: B-PROJ PR 1 → D-CAMPAIGN → ADR-047 + B3 → D-SPLIT/B1-bis, D-API/B6, D-ASSET, D-IA/B5 → B-PROJ PR 2 y B4.

### 0.6 B-PROJ PR 1 hecho (2026-10-07, #42)

- **Las proyecciones procesan por primera vez los streams escritos por los comandos reales.** No es una regresión arreglada: **nunca habían funcionado con datos reales**. Desde su implementación, el event store numera la génesis como 1 y los manejadores la esperaban en 0, y además ignoraban los payloads v2. Los tests de proyección construían los eventos a mano empezando en 0, así que no lo detectaban.
- **Consecuencia para fases anteriores, que se registra tal cual:** el seguimiento público de la **Fase 3** se dio por bueno sin haber funcionado nunca con donaciones reales; para cualquiera de ellas devolvía 404. Su evidencia de cierre solo cubría proyecciones alimentadas con eventos construidos a mano.
- **Cambios:**
  - génesis = 1 (enmienda D-SEQ en el documento maestro);
  - payloads v2 tratados;
  - Camino B ignorado de forma explícita;
  - declaraciones de payloads por manejador con un test de contrato;
  - test de punta a punta con comandos reales y el *change stream* real.
- **Evidencia:** reactor completo en verde, `core` de 235 a **255 tests**, cinco mutaciones comprobadas (detalle en #42).
- **Sigue pendiente:**
  - **PR 2** (reconstrucción operativa por JMX), que necesita antes una **enmienda de ADR-042** (mecanismo nuevo, regla 3.5). Sin entornos con datos reales, no es urgente.
  - **Historial público de los activos del Camino B:** da 404 hasta que se decida dónde se proyecta una donación en especie (Q3 de la Enmienda 1 de ADR-029, con C5 de ADR-040).
  - Deuda de ADR-042: guardar `aggregateType` en la cuarentena y hacer configurable la ventana de 4 horas.
- **Siguiente bloque:** implementación de D-CAMPAIGN (`plan-d-campaign.md`).

### 0.7 D-CAMPAIGN implementada (2026-10-07)

- **Qué hace:**
  - `PhysicalAsset` lleva `campaignRef` en `ASSET_REGISTERED`/`ASSET_SPLIT` **3.0**; la 1.0 y la 2.0 se leen sin convocatoria.
  - **Camino A:** heredado del `Fund`, sin comprobar `OPEN` (decisión escrita).
  - **Camino B:** opcional y validado con `CampaignInKindEligibilityPort` (`contracts`, implementado por `convocatoria`) **después de autorizar**. Los rechazos tienen excepción nombrada, y "no existe" y "otra organización" dan la misma respuesta externa.
  - **División:** hereda del padre.
  - **Proyecciones:** la 3.0 está declarada, y `campaignRef` por activo en `LogisticsProjection`.
  - **Puerto obligatorio:** sin implementación, la aplicación no arranca.
- **Desbloquea** B5 (agregación de la narrativa de convocatoria, criterios 14 y 19) y la herencia en la saga de la división (B1-bis, criterio 15).
- **Pendiente:** H1 con activos en especie (Q4 de la enmienda); la proyección de los activos del Camino B, que siguen ignorados aunque ya lleven `campaignRef`.

### 0.8 B3 — login y autenticación JWT (2026-10-07, `feat/b3-jwt`)

- **ADR-047 APROBADO** y `plan-b3-jwt.md` APROBADO, con la condición de Q1: lista explícita de rutas públicas.
- **Qué hace:**
  - `POST /api/v1/auth/login` (ID-01): 400 / 401 uniforme / 500 / `{token}`.
  - JWT HS256 con Nimbus 10.10, solo en `api`. El secreto no tiene valor por defecto y se valida con *fail-fast*. Lleva `kid` y admite una clave anterior hasta `accept-until`, avisando al arrancar.
  - El verificador sigue el orden de ADR-047 D5.
  - **Filtro *deny-by-default*** sobre `/api/v1/**`. `PublicRoutes` es la única lista pública: seguimiento, login, registro, CV-07, descubrimiento, intención con JWT opcional, narrativa y webhook. Una ruta no canónica nunca es pública. `resolvePrincipal` se ejecuta en cada request, y cualquier fallo da el mismo 401.
- **Identidad:** `AuthenticateAccountPortImpl` con hash ficticio. Los tres fallos son la misma excepción y cuestan lo mismo.
- **Desbloquea** los criterios 4 y 6, y que B6 construya el `HumanActor` desde el atributo `authorizationPrincipal`.
- **Pendiente:** refresh, límite de intentos (DH-56, riesgo aceptado), `AccountNotFoundException` en `contracts` (el filtro trata por tipo genérico los fallos de `resolvePrincipal` y propaga los de acceso a datos).

### 0.9 B1-bis — saga de la división (2026-10-07, `feat/b1bis-split-saga`)

- **Decisiones:** `propuesta-d-split.md` y `plan-b1bis-saga-division.md` aprobados por Carlos; **Enmienda 1 de ADR-007/008 APROBADA**, con resolución manual auditada y sin migración.
- **Qué hace:**
  - La división escribe su mensaje de saga en la misma transacción y devuelve un `childAssetId` determinista (UUID v5 de padre + `commandId`).
  - **Barrera atómica:** crear el hijo y compensar usan el reclamo común `SPLIT_RESOLUTION:{childAssetId}`, confirmado en la misma transacción que su efecto y con el resultado guardado. Con 20 rondas concurrentes, nunca se aplican los dos efectos ni ninguno.
  - El hijo hereda la cantidad, la ubicación, el custodio y las referencias del `ASSET_SPLIT` (incluido `campaignRef`), y del padre solo `assetType` y la asignación de origen.
  - **Coordinador enmendado:** ejecución y resolución de 4 h cada una; distingue fallo permanente de transitorio; estado `RESOLVED`; `QUARANTINED` solo cuando hace falta una persona (con log ERROR); salida por JMX (`SagaOutboxAdministration`) condicional y auditada en `saga_manual_actions`.
  - **Recuperación hacia delante:** si el padre ya está entregado, la resolución crea el hijo.
  - **Proyección:** el estado previo se guarda por hijo, y una compensación ya no "resucita" un padre que no estaba agotado.
- **Hallazgos corregidos:**
  - una compensación fallida se perdía en silencio, también en la saga de registro;
  - la ventana era de 24 h en el código frente a 4 h en la norma.
- **Efecto sobre la saga de registro:** compensa a las 4 h y reintenta la compensación hasta 4 h más (test 18).
- **Pendiente (B6):** el endpoint `POST .../split` (`202`) y `GET .../splits/{child}` sobre `SplitResolutionReadPort`.
- **Evidencia:** `evidencia-fase6/b1bis-saga-division-ff7bfbc-2026-10-07.txt`.

### 0.10 Tests transaccionales de `core` (2026-10-07, `fix/tests-transaccionales-core`)

**Origen:** en B1-bis se vio que los tests de integración de `core` no tienen `MongoTransactionManager` salvo que lo declaren, y entonces `@Transactional` no tiene efecto (Spring Boot solo activa las transacciones si existe un gestor). En producción sí lo hay, porque lo define `app`.

**Auditoría** (los 20 tests de `core` con contexto de Spring):

| Test | ¿Afirma atomicidad, rollback o barrera? | ¿Tiene gestor? | Resultado |
|---|---|---|---|
| `MongoEventStoreAdapterTest.testTransactionalRollback` | Sí: evento y outbox revertidos | Sí | Corre con transacción; sonda añadida |
| `ProcessedCommandIdempotencyIntegrationTest.rollback_on_conflict` | Sí: el reclamo se revierte | Sí | Corre con transacción; sonda añadida |
| `FundCommandServiceGenesisWithinTransactionIntegrationTest` (T1) | Sí: un rollback externo no deja evento ni reclamo | Sí, y `Propagation.MANDATORY` | Corre con transacción; sonda añadida |
| `SplitSagaIntegrationTest` (barrera, atomicidad, resolución manual) | Sí | Sí | Corre con transacción; sonda añadida |
| `CampaignRefIntegrationTest` ("un rechazo no deja efectos") | No: el rechazo ocurre antes de cualquier escritura | No | No depende de una transacción |
| Los otros 15 (`FundCommandService*`, `PhysicalAssetCommandService*`, `Phase5EndToEnd`, `RegisterPhysicalAssetFromDonation`, proyecciones, `MongoUnanchoredEventAdapterTest`, seguridad…) | No | No | Idempotencia por reclamo o escrituras simples, que no dependen de un rollback |

**Ningún test de `core` afirmaba una garantía transaccional sin gestor**, así que no apareció ningún defecto oculto. Fuera de `core`: `app` (`TraceabilityInfrastructureConfig`) y `convocatoria` (`ConvocatoriaTestApplication`) aportan su gestor.

**Protección añadida:** `core/src/test/.../support/TransactionProbe`. Envuelve `EventStorePort.append` y `ProcessedCommandRepositoryPort.tryClaim` y registra `TransactionSynchronizationManager.isActualTransactionActive()` en cada llamada. Los tests de la tabla con sonda llaman a `assertEveryWriteWasTransactional()`, que falla si no hubo escrituras o si alguna se hizo sin transacción. Comprobado:
- quitando el `MongoTransactionManager`, fallan `MongoEventStoreAdapterTest.testTransactionalRollback`, `ProcessedCommandIdempotencyIntegrationTest.rollback_on_conflict` y cuatro tests de `SplitSagaIntegrationTest`;
- en el de T1, `MANDATORY` y el `TransactionTemplate` ya lo impiden.

**Regla para tests nuevos:** todo test que afirme atomicidad, rollback o una barrera declara `MongoTransactionManager`, importa `TransactionProbe.Config` y llama a `assertEveryWriteWasTransactional()`.

**Mutaciones:** `scripts/mutaciones.py` restaura siempre con `git checkout -- <archivo>` (nunca desde una copia en memoria), se niega a empezar con cambios sin commit en los archivos a mutar y verifica al final que todo vuelve a HEAD. Motivo: en B1-bis, una restauración desde una copia guardada después de una primera edición dejó código mutado. Se detectó y la evidencia sigue siendo válida (el código final coincide con `ff7bfbc`), pero la próxima vez podría no notarse.

### 0.11 B6-0 — base HTTP (2026-10-07, `feat/b6-0-base-http`) — HECHO

- **Plan:** `plan-b6-0-base-http.md`, APROBADO por Carlos (Q1–Q3, con las respuestas Q-B60-1 a 5 unificadas en #57) y marcado HECHO tras la evidencia.
- **Qué hace** (paquete `com.traceability.api.web`):
  - **`@CurrentActor`**: `HumanActor`, `AuthorizationPrincipal` u `Optional` de ellos, leídos del atributo de `JwtAuthFilter`. Un parámetro obligatorio sin principal da 500, nunca un actor nulo. **`CurrentActorRouteValidator`** impide arrancar con un actor obligatorio en una ruta de `PublicRoutes` (pública o de JWT opcional), con un tipo no admitido o con un `@CommandId` que no sea `String`.
  - **`@CommandId`**: UUID canónico obligatorio, en minúsculas (Q-B60-3). Si falta o no es válido: 400 sin llamar al caso de uso.
  - **`ApiExceptionHandler`**, el único manejador (Q-B60-4): cada módulo aporta un bean `ApiErrorMappings` (`api` y `core`, de momento), y si dos declaran la misma excepción, la aplicación no arranca. Gana la traducción de la superclase más cercana; las excepciones de Spring con `ErrorResponse` conservan su código.
  - **Cuerpos fijos** con forma de `ProblemDetail` y sin `instance`, porque Spring la rellenaría con la ruta y la ruta puede llevar ids. El mismo 403 para las dos excepciones de autorización (Q-B60-5) y el mismo 409 para todas las de convocatoria en especie.
  - **500:** el cuerpo lleva un `correlationId`. El log guarda la clase, las clases de las causas, el `correlationId` y la primera línea de la pila, sin mensajes ni traza.
  - **ArchUnit** en `api` y en `app.web`: ningún controlador recibe la request ni lee el atributo del principal o la cabecera `Authorization`. Los controladores de `app.web` no tocan infraestructura ni Spring Data. Cada regla tiene controladores de prueba que demuestran que detecta lo que debe. `app` gana `archunit-junit5` (test).
- **Hallazgos:**
  - **El módulo compila sin `-parameters`**: un `@PathVariable` sin nombre lanza un `IllegalArgumentException` de Spring, y la tabla lo convierte en un 400 sin log. Los controladores de B6-a a B6-d deben nombrar sus variables. Riesgo general: un `IllegalArgumentException` interno se ve como un error del cliente.
  - El validador solo existe en una aplicación servlet; el contexto sin web del test de arranque de `app` no lo carga.
  - `scripts/mutaciones.py` no nombraba los tests parametrizados (cortaba por el `]` equivocado); corregido.
- **Sin cambios:** las rutas de Fase 3 y el login (tests existentes en verde).
- **Sigue abierta Q-B60-6** (404 uniforme frente a DH-51 y Q-CV01-6): rige el 403.
- **Evidencia:** `evidencia-fase6/b6-0-base-http-2363fda-2026-10-07.txt`. Reactor 1070 tests en verde; 13 mutaciones, 13 muertas.

### 0.12 B6-c — activos y división por HTTP (2026-10-07, `feat/b6-c-activos-http`) — HECHO

- **Proceso:** autorización de trabajo autónomo de Carlos (2026-10-07), excepción temporal a las reglas 1 y 3.4. Plan `plan-b6-c-activos-http.md` redactado y ejecutado por el agente; **todas sus decisiones están `PENDIENTE DE RATIFICACIÓN`** en `decisiones-delegadas-2026-10.md` (DD-08 a DD-16).
- **Qué hace:**
  - **D-ASSET:** `dispatchAsset`/`receiveAsset` en `PhysicalAssetCommandService`, con `DISPATCH_`/`RECEIVE_PHYSICAL_ASSET` (`EMPLOYEE`).
  - **Q9:** el registro A y B usa el id `UUIDv5(NS_ASSET, organizationRef:commandId)` y devuelve `RegisteredAsset`; un duplicado devuelve lo mismo sin escribir.
  - **`Command-Id` de otro comando** → 409 `CommandIdReused` (cada comando de activo guarda `TIPO:assetId` en su reclamo; DD-11).
  - **Activo inexistente** → `PhysicalAssetNotFoundException`, con el mismo 403 que "otra organización" (DD-12, Q-B60-6 con el 403).
  - **Ocho rutas** en `api` (`PhysicalAssetController`): registro A y B (201), división (202 + `Location`), estado de la división, dispatch, receive, deliver y la vista operacional (matriz §4b, leída del event store, DD-13).
  - **Camino B:** el `donorRef` lo genera el servidor (`anon:` + UUID); el cliente no lo envía (DD-09). La organización sale del JWT (DD-10). `deliveredAt` es la hora del servidor (DD-15).
  - `scripts/mutaciones.py` admite argumentos extra de Maven, para mutar `api` y matar con un test de `app`.
- **Criterios del golden path cubiertos por HTTP** (test de punta a punta contra Tomcat real): 7, 8 y 15–17 con un activo del Camino B. El Camino A por HTTP necesita una asignación previa sin endpoint (**H-B6C-1**, DD-16).
- **Evidencia:** `evidencia-fase6/b6-c-activos-http-1e273be-2026-10-07.txt`. Reactor **1089 tests** en verde (línea base 1070); 11 mutaciones, 11 muertas.

### 0.31 L-FRONTS: lecturas que piden los fronts, A1 (M-07) y A3 (M-26) (2026-10-18, `feat/lecturas-fronts-a1-a3`)

- **Contrato:** `ficha-lecturas-fronts-M07-M15-M26.md` (A1 y A3 CONGELADAS; decisiones de Manuel del 2026-10-18T06:35Z). Origen: documento "Funciones de la app móvil y pantallas del empleado" (Carlos, 2026-10-16) y bocetos web.
- **Novedades de la API** (para la web y el móvil; también en `referencia-api-v1.md` §1 y §5):
  - **A1:** `GET /api/v1/public/campaigns` (cada ítem) y `GET /api/v1/public/campaigns/{publicCode}` llevan `organizationVerified` (booleano, siempre presente), calculado al leer con `OrganizationVerificationPort.isVerified`. Notas fechadas, solo añadidas, en `plan-b6-a-convocatoria-http.md` §2.3 y `fichas-p2-endpoints.md` (P2.6).
  - **A3:** `GET /api/v1/account` → `200 {email}`, `no-store`, solo la cuenta del JWT, con o sin organización; 401 sin JWT válido o con la cuenta `INACTIVE`. `GET /me` (ficha N1) no cambia.
- **Qué hace:** A1 inyecta el puerto existente en `PublicCampaignController` y `PublicCampaignDiscoveryController` (no se amplía `OrganizationPublicNamePort`, que está en `contracts`). A3 añade `AccountEmailQuery` (`identity`) y `AccountController` (`app/web/account`).
- **Tests:** primero en rojo.
  - A1: 25 tests, 5 fallos (los tres de campos exactos de CV-07 y del descubrimiento, más dos nuevos que rompen a propósito el invariante, con la organización en `PENDING_VERIFICATION` o borrada, para probar que el campo se calcula y no es constante).
  - A3 (`AccountHttpIntegrationTest`): 6 tests, 5 fallos con 404; el de 401 ya pasaba y queda como regresión. Cubre una cuenta sin organización y cuentas con organización (representante y empleado), el correo normalizado, que cada cuenta solo reciba el suyo y que el correo no aparezca en ningún log.
  - El inventario de rutas se puso en rojo con `["GET /api/v1/account"]` y en verde al añadirla a `PROTECTED`.
- **Bloqueado:** **A2** (M-15/M-16, `GET /account/donations` con fechas, organización, medio de donación y enlace) está decidido pero espera a **Q-A2-C** de Carlos: si añadir `createdAt` a `DonationIntent` y ampliar `DonationView` exige enmienda de ADR-037 (regla 3.5).
- **Pendientes que se trasladan al equipo** (ficha §6; no se resuelven aquí):
  - **H-L1:** la garantía de M-07 es un invariante de código (`VERIFIED` terminal), no de datos.
  - **H-L2:** el historial de donaciones no sale en orden cronológico (DD-21 ordena por `intentId`, un UUID aleatorio).
  - **H-L3:** la ficha CV-07 sigue sin estar en el repositorio (Q-B6A-5).
  - **H-L4:** una consulta más por convocatoria en el descubrimiento (aceptada).
- **Pendiente fuera del backend:** el parser y el doble `scripts/fake-backend.js` de `paxfide-web` (campo `organizationVerified` y `GET /account`), en un PR aparte.
- **Evidencia:** `scripts/ci-local.sh` sobre el último commit de la rama, en `Documentos/evidencia-ci/`.

### 0.30 S-18: estado de verificación de la propia organización (2026-10-16, `feat/identity-s18-estado-organizacion`)

- **Contrato:** `ficha-S18-estado-verificacion-organizacion.md` (CONGELADA; Q-S18-1 a Q-S18-4 decididas por Manuel el 2026-10-16T03:23Z). Origen: solicitud S-18 de `paxfide-web`.
- **Novedad de la API** (para la web y el móvil; también en `referencia-api-v1.md` §3): `GET /api/v1/organizations/{organizationId}` → `200 {organizationId, name?, type, verificationStatus, informationRequest?}`, `no-store`. Solo `ADMINISTRATOR` o `REPRESENTATIVE` de esa organización; `EMPLOYEE`, otra organización, una inexistente y un administrador de plataforma sin membresía reciben el mismo 403 (DD-01). `informationRequest` solo con `NEEDS_MORE_INFORMATION`. Campos nulos omitidos.
- **Qué hace:** `OrganizationVerificationStatusQuery` (`identity`; misma autorización que `OrganizationMembersQuery`, sin reglas ni puertos nuevos) y `OrganizationVerificationStatusController` (`app/web/organization`). `IdentityApiErrorMappings` ya traducía `OrganizationAccessDeniedException` a 403. `GET /me` (ficha N1) no cambia.
- **DD-68:** nota añadida (sin reescribir) en `decisiones-delegadas-2026-10.md` y `fichas-encargo4-organizaciones.md` §3.1: la ruta pasa a existir; `POST /organizations` sigue sin `Location`.
- **Tests** (`OrganizationVerificationStatusHttpIntegrationTest`, ficha §7.1–§7.8, más `PublicRoutesInventoryIntegrationTest` §7.9): primero en rojo (7 con 404; §7.6, el 401, ya pasaba por el deny-by-default del filtro JWT y queda como test de regresión); el inventario se puso en rojo al crear la ruta y en verde al añadirla a `PROTECTED`.
- **Pendientes que se trasladan al equipo** (ficha §6 y §8; no se resuelven aquí):
  - **H-S18-1:** `NEEDS_MORE_INFORMATION` sin canal de respuesta de la organización.
  - **H-S18-2:** la cuenta del representante queda sin salida tras `REJECTED` (regla 2.6).
  - **H-S18-3:** las invitaciones y la gestión de miembros no comprueban el estado de verificación.
  - **Tarea derivada de Q-S18-3 (no de código):** avisar a los administradores de plataforma de que la organización lee `informationRequest` tal cual.
- **Pendiente fuera del backend:** la fila S-18 de `solicitudes-backend.md` de `paxfide-web` pasa a RESUELTA en un PR aparte.
- **Evidencia:** `scripts/ci-local.sh` sobre el último commit de la rama, en `Documentos/evidencia-ci/`.

### 0.32 ADR-051, Tarea 1: almacenamiento de fotos (2026-10-21, `feat/app-adr051-media-storage`)

- **Norma:** `ADR-051-fotos-y-almacenamiento.md`, APROBADO — Carlos, 2026-10-18, con la Enmienda 1 (18:45Z); copia canónica confirmada por Manuel el 2026-10-21 (sha256 `d01e1385973ec987f2aae3fb350b1b14348e9753e6a190085307db1b026633a0`). Plan: `plan-adr051-tarea1-almacenamiento.md`, APROBADO con cambios (Manuel, 2026-10-20) y con la iteración DT-2 (2026-10-21).
- **Qué hace** (sin rutas HTTP; las usan las tareas 2 a 4):
  - `contracts`: `MediaStoragePort` (`put`/`get`/`delete`) y `MediaStorageUnavailableException` (F9), solo tipos de Java.
  - `app`: SDK de S3 **2.55.10** solo en `app`, con `url-connection-client`; excluidos `netty-nio-client`, `apache-client` y `apache5-client` (F1). `S3MediaStorageAdapter` (bucket privado, credenciales estáticas, sin reintentos del SDK; un reintento de `put` con la misma clave y bytes solo ante fallo ambiguo, F2), `LocalMediaStorageAdapter` (solo `dev`) y `MediaStorageConfig`, con *fail-fast*: `local` fuera de `dev` o cualquier `MEDIA_S3_*` ausente impide arrancar, nombrando la variable y nunca su valor.
  - Limpieza con la JDK (`ImageSanitizer`, F4): bytes mágicos, dimensiones desde la cabecera antes de decodificar (8000 px, 40 MP), rechazo por espacio de color distinto de RGB o gris (la JDK 21.0.12 decodifica CMYK/YCCK; aclaración N-1), re-codificación sin metadatos (JPEG 0,9) y SHA-256 de los bytes guardados.
  - `media_objects` (F9) a través del puerto `MediaObjectStore` (iteración DT-2): limpiar → `put` con ULID → insertar; huérfano registrado con su clave; único en `objectKey` y único parcial `(organizationId, purpose, sha256)` para `DELIVERY_EVIDENCE`. ArchUnit: el SDK solo en `app.infrastructure.media`, `contracts` solo con Java, y `app.application` sin `app.infrastructure` ni Spring Data.
  - `.env.example` y `runbook-demo-local.md` con las cinco `MEDIA_S3_*`.
- **Los criterios 3, 4, 5 y 15 de ADR-051 quedan cubiertos a nivel de excepción (`UnsupportedImageFormatException`, `ImageTooLargeException`, `MediaStorageUnavailableException`). Las respuestas 413, 415 y 503 por HTTP quedan para la Tarea 2.**
- **Criterios cubiertos:** 1, 2, 3, 4, 5, 15, 17 (MinIO real, `bitnamilegacy/minio` fijada por digest), 18 y 19. El 4 se prueba "sin decodificar" con PNG; el caso JPEG solo prueba que el límite se lee de la cabecera, porque la JDK decodifica con tolerancia un JPEG con la cabecera cambiada.
- **Evidencia:** rojo primero (`mvn test -pl app`: 399 tests, 96 fallos y 30 errores, solo en las clases nuevas o modificadas); 10 de 10 mutaciones muertas (la 3 por los casos PNG; 6 y 7 repetidas tras DT-2); `ci-local.sh` en clon limpio sobre `035d30a` (app 405) y `05cdfc1` (app 411), todo en verde: `evidencia-ci/ci-local-035d30a-2026-10-21T04-41-04Z.txt` y `evidencia-ci/ci-local-05cdfc1-2026-10-21T05-14-42Z.txt`.
- **Queda para la Tarea 2** (logo): la primera ruta que use el servicio; la traducción a HTTP (`ImageTooLargeException` → 413, `UnsupportedImageFormatException` → 415, `MediaStorageUnavailableException` → 503) con su test por HTTP (P-2); el límite de *multipart* de F8 y si el controlador repite el de 2 MB (P-1); el borrado del logo anterior tras confirmar el cambio (F10, criterio 16).
- **Deuda registrada:** **DT-1**: `S3MediaStorageAdapter.isAmbiguous` recorre las causas de afuera hacia adentro; una `IOException` que envuelve una `ConnectException` se reintenta una vez. No hace daño porque el `put` es idempotente. (DT-2 se resolvió en esta misma tarea.)
- **Preguntas abiertas para Carlos** (no bloquean la Tarea 1; plan §7.3): P-1, P-2, P-6 (ADR-050 y ADR-051 en el catálogo del documento maestro, que dice "siguiente libre: ADR-050"), P-9; riesgo R-1 (memoria al decodificar 40 MP sin límite de concurrencia); N-1 y N-2, ya aplicadas por Manuel, para su ratificación.

### 0.39 P-R3-3: el login emite con `iat = max(segundo truncado, credentialsNotBefore)` (2026-10-25, `fix/identity-p-r3-3-iat-del-login`)

- **Norma:** `ADR-050-enmienda-1-iat-del-login.md`, parte 1 (E1-1, E1-2), APROBADA por Carlos (`decisiones-delegadas-2026-10.md`, notas del 2026-10-25; "todo aprobado" en #134). Plan: `plan-p-r3-3-iat-del-login.md` (#128), APROBADO por Carlos, con P-PR33-1 a P-PR33-5 como los recomienda. Corrige el defecto conocido de §0.37.
- **Qué hace:**
  - `contracts`: `AuthenticatedAccount(accountId, credentialsNotBefore)`. `AuthenticateAccountPort.authenticate` lo devuelve en lugar de `String`: **cambia la firma; no se añade un método**.
  - `identity`: `AuthenticateAccountPortImpl` lo toma del mismo `Account` que validó la contraseña, en una sola lectura. El hash ficticio y el coste igual (ADR-047 Q5) no cambian.
  - `api`: `LoginController` emite con `issue(accountId, max(segundo actual truncado, cnb))`, con el reloj de la aplicación (`ObjectProvider<Clock>`, P-PR33-2) y dentro del `try` de la emisión (P-PR33-4).
  - `referencia-api-v1.md`: nota fechada que da el defecto por corregido. `AccountPasswordHttpIntegrationTest.deactivateReactivate_andAPreviousTokenIs401` ya no avanza el reloj.
- **Evidencia:**
  - **Rojo primero:**
    - `api` e `identity` no compilaban ("cannot find symbol", `AuthenticatedAccount`);
    - en `app`, con los tests de `api` e `identity` apartados para que compilara el reactor: "Tests run: 13, Failures: 3, Errors: 0, Skipped: 0". `p_r3_3_changeThePasswordAndLogInInTheSameSecond_theTokenWorks`, `p_r3_3_twoChangesInARowAndAnImmediateLogin_theTokenWorks` y `deactivateReactivate_andAPreviousTokenIs401` fallaron con "expected: 200": el login del mismo segundo daba 401.
  - **`ci-local.sh`** en un clon limpio de `e7019e4`, con el `develop` `406f9b2` integrado: `BUILD SUCCESS` y ningún test fallido. Evidencia: `Documentos/evidencia-ci/ci-local-e7019e4-2026-10-25T02-09-27Z.txt`. Resumen literal: core "Tests run: 340", crypto "Tests run: 50", ai "Tests run: 47", api "Tests run: 160", identity "Tests run: 334", convocatoria "Tests run: 253" y app "Tests run: 464", todos con "Failures: 0, Errors: 0, Skipped: 0". Es el verde por capas: cada módulo completo.
  - **5 de 5 mutaciones muertas** ("Mutaciones vivas: 0"), en otro clon limpio, con `mvn install` (con tests) de `.,contracts,core,crypto,ai,convocatoria` y la línea base en verde (api 9, identity 9, app 13). El reactor va en el orden api, identity, app, así que la 1, la 3, la 4 y la 5 murieron en `api` antes de llegar a `app`:
    1. `issue(accountId)` sin `cnb` → L-1 a L-4 y tres tests más de `LoginControllerTest`;
    2. `cnb` siempre nulo → A-1 (`identity`);
    3. `iat = cnb` sin `max` → L-2;
    4. `min` en lugar de `max` → L-1 y L-2;
    5. sin truncar → L-2 y L-3.
- **Pendiente:**
  - nota fechada en la Enmienda 1 ("implementada en …") cuando se fusione;
  - **R-4** sale del `develop` que ya tenga esto (el trabajo previo está en `wip/identity-r4-recuperacion`);
  - **D3** (parte 3) cambiará otra vez la firma del puerto, después de R-5 primera parte.

### 0.38 Ficha R-6, Enmienda 1, E2: `lastEventAt?` por lote en el listado de la organización (G-9) (2026-10-25, `feat/r6-bloque-b-g9`)

- **Norma:**
  - `ficha-R6-enmienda-1.md` §E2, APROBADA por Carlos (`decisiones-delegadas-2026-10.md`, notas del 2026-10-25; confirmación escrita "todo aprobado" en #134).
  - Plan: `plan-r6-bloque-b-adenda-g9.md` (#124), APROBADO por Manuel el 2026-10-25 con P-G9-1 (b), y por Carlos (misma nota). P-G9-2 y P-G9-3, como recomienda el plan. **B-G9-1, resuelto** por esa nota.
- **Qué hace:**
  - `core`: `PhysicalAssetOperationalView.lastEventAt`. `listForOrganization` lo toma del último evento que `loadStream` ya devuelve por `sequence` ascendente, **sin ninguna consulta nueva** (P-G9-1 (b)). La vista individual lo deja nulo (P-G9-2).
  - `api`: `OrganizationAssetsController.OrganizationAssetItem`, propio del listado (P-G9-3), con `lastEventAt?` en ISO-8601 UTC (`Instant.toString()`) y omitido si es nulo (`@JsonInclude(NON_NULL)`). `OperationalResponse` (`GET /physical-assets/{assetRef}`) no cambia.
- **Tests:**
  - G9-1 a G9-4: `PhysicalAssetListLastEventAtIntegrationTest` (core, Mongo real), `OrganizationAssetsControllerTest` (api) y la ampliación de `P2EndpointsHttpIntegrationTest.organizationAssets_forAdministratorOrEmployee_withoutDonorData` (app).
  - `AssetHttpCommandsIntegrationTest.operationalView_hasExactlyTheOperationalFields` fija ahora también `lastEventAt`.
- **Evidencia:**
  - **Rojo primero:**
    - `core` no compilaba: "constructor PhysicalAssetOperationalView in record … cannot be applied to given types" y "cannot find symbol … method lastEventAt()".
    - `api` falló por aserción: "Tests run: 2, Failures: 1, Errors: 0, Skipped: 0 … `g9_lastEventAt_isIso8601Utc` <<< FAILURE!".
  - **Un fallo intermedio del test, no del código:** la primera versión de G9-2 usaba `verifyNoMoreInteractions` sobre el adaptador del event store y contaba también la lectura en segundo plano de la proyección (`AssetProjectionRouting.isInKindAsset` → `findGenesisPayload`). Se cambió a "una `loadStream` por activo listado y ninguna más". Sigue matando la mutación 4.
  - **Verde por capas**, con el módulo completo:
    - `core`: "Tests run: 340, Failures: 0, Errors: 0, Skipped: 0";
    - `api`: "Tests run: 149, Failures: 0, Errors: 0, Skipped: 0" (sobre `326f5e6`, antes de integrar `develop`);
    - `app`: el de `ci-local.sh`.
  - **`ci-local.sh`** en un clon limpio de `7ae3046`, con el `develop` `3cab9a9` integrado: `BUILD SUCCESS` y ningún test fallido. Evidencia: `Documentos/evidencia-ci/ci-local-7ae3046-2026-10-25T01-42-29Z.txt`. Resumen literal: core "Tests run: 340", crypto "Tests run: 50", ai "Tests run: 47", api "Tests run: 156", identity "Tests run: 332", convocatoria "Tests run: 253" y app "Tests run: 462", todos con "Failures: 0, Errors: 0, Skipped: 0".
  - **4 de 4 mutaciones muertas** ("Mutaciones vivas: 0"), en otro clon limpio y con la línea base en verde:
    1. el mayor `occurredAt` → G9-1;
    2. el primer evento → G9-1 y G9-2;
    3. serializar `null` → G9-3;
    4. volver a leer el stream → G9-2.
- **Registrado (R-G9-1):** las 200 rehidrataciones por página (N+1) ya existían; G-9 no las empeora.
- **Pendiente:** avisar a la web cuando se fusione (G-9 era parte del Bloque B).

### Nota (2026-10-25): aprobaciones de Carlos y fusiones del 2026-10-25

- **Carlos aprobó todo lo pendiente** (planes, Enmienda 1 de ADR-050 partes 1 y 2, Enmienda 1 de la ficha R-6) y pidió a Manuel que fusionara él mismo los PR (`decisiones-delegadas-2026-10.md`, nota del 2026-10-25; comunicado por Manuel, texto literal pendiente de registrar).
- **Fusionados el 2026-10-25, sin Approve en GitHub y por instrucción de Carlos** (H-1 en `informe-sesion-manuel-2026-10-24.md`):

| PR | Fusionado (UTC) | Commit de merge | Qué |
|---|---|---|---|
| #121 | 01:37:16 | `4be5c6e` | Plan R-4 |
| #122 | 01:37:27 | `91ee264` | Plan R-5 + D1 |
| #123 | 01:37:39 | `a8596a6` | Plan D6 |
| #127 | 01:40:02 | `818ebf3` | Código de R-3 + R-2 |
| #125 | 01:40:10 | `dd8033c` | ADR-050, Enmienda 1, parte 2 |
| #130 | 01:40:17 | `93940e3` | Notas de la ficha R-6 §9 y de ADR-050 |
| #128 | 01:40:24 | `b119386` | Plan P-R3-3 |
| #124 | 01:40:32 | `8bfe713` | Adenda G-9 |
| #126 | 01:40:39 | `b1eae78` | Plan C, versión 2 |
| #131 | 01:40:46 | `f57611d` | Plan de ADR-051 T4 |
| #129 | 01:40:53 | `3cab9a9` | Informe de la sesión |

- Con #127 (`818ebf3`), R-3 + R-2 (§0.37) está en `develop`. Pendiente de §0.37: avisar a la web y al móvil (el texto está en §5 del informe).
- **Siguen bloqueados:** D7 (Carlos no lo nombró), la Tarea 5 de ADR-051 (falta la enmienda de ADR-021-D) y D3 (falta la parte 3 de la Enmienda 1 de ADR-050, P-R5-1).

### 0.37 R-3 + R-2: revocación de sesiones y cambio de contraseña (2026-10-24, `feat/identity-r3-r2-revocacion-contrasena`)

- Numerada 0.37 porque el Bloque B (#113) ya usa la 0.36.
- **Norma:**
  - `ADR-050-cuenta-y-sesiones.md` (APROBADO — Carlos, 2026-10-18): D4, D5 y la parte de D6 que fija `credentialsNotBefore` al desactivar. Enmienda ADR-047 D5 (nota del 2026-10-24 en ese ADR).
  - Plan: `plan-r3-r2-revocacion-y-cambio-de-contrasena.md` (PR #118), APROBADO (Manuel, 2026-10-24, por la delegación de Carlos): P-R3-1 (a), P-R3-2 y riesgo R-R3-1 registrado.
- **Qué hace:**
  - `contracts`: `IdentityPrincipalPort.resolveAuthenticatedPrincipal(accountId, issuedAt)` y `TokenIssuerPort.issue(accountId, issuedAt)`, abstractos. Los dobles de `core` y `convocatoria` lanzan `UnsupportedOperationException`.
  - `identity`: `Account.credentialsNotBefore` (`max(segundo truncado, actual) + 1 s` al cambiar la contraseña y al desactivar), `acceptsTokenIssuedAt`, `CredentialsRevokedException`, `CurrentPasswordIncorrectException`, `ChangeCredentialsService.changeOwnCredentials` (actual → política → guardar, en la transacción) y reloj por `ObjectProvider<Clock>` en los dos servicios.
  - `api`: el verificador lee `iat` (sin `iat` → `MISSING_CLAIMS`), el filtro llama a `resolveAuthenticatedPrincipal(sub, iat)`, el emisor gana `issue(accountId, issuedAt)`; emisor y verificador usan el `Clock` de la aplicación si lo hay.
  - `app`: `POST /api/v1/account/password` → `200 {token}` con `iat = credentialsNotBefore`, `no-store`; `CurrentPasswordIncorrect` → 400.
- **Desviación del plan, anotada:** §3 fija `changeOwnCredentials(AccountId, …)`. La regla de `identity.architecture.ArchitectureTest` solo permite construir `AccountAuditActor` en `CreateAccountService`, `AuditLogEntryMapper` y `AuthorizationAuditActorMapper` (ADR-038 §2.2, §2.3). Por eso el método recibe el `AuthorizationPrincipal` y el actor sale de `AuthorizationAuditActorMapper`, como en `InviteMemberService`. No se tocó la regla.
- **Desviación aprobada:** la firma con `AuthorizationPrincipal` (Manuel, 2026-10-24; plan §10.1, #119).
- **Tests de `app` adaptados:** los cuatro que simulan `IdentityPrincipalPort` (`CurrentActorRealServerIntegrationTest`, `PrincipalResolutionFailureRealServerIntegrationTest`, `PhysicalAssetHttpIntegrationTest`, `FundHttpIntegrationTest`) programan también `resolveAuthenticatedPrincipal`, que es lo que llama ahora el filtro. Además:
  - el inventario de rutas incluye la nueva;
  - `DemoScenarioSeederIntegrationTest` vuelve a iniciar sesión el día de la demo. Con emisor y verificador en el reloj de la aplicación (P-R3-2), el token de `C0` ya había caducado 13 días después.
  - El plan solo preveía cambiar los dobles de `core` y `convocatoria`; estos cambios son consecuencia directa de P-R3-2.
- **Evidencia:**
  - **Rojo primero:** `identity` y `api` no compilaban. En `app`, 9 de 11 fallaron por aserción (404 en la ruta nueva). Uno de esos fallos venía de mi test y no del código (el login del mismo segundo que la revocación, que D4 rechaza con razón; es el defecto P-R3-3). Se corrigió avanzando el reloj un segundo.
  - **Verde por capas:**

    | Módulo | Resumen de Surefire |
    |---|---|
    | `contracts` | "No tests to run." |
    | `core` | 326 |
    | `convocatoria` | 253 |
    | `identity` | 332 |
    | `api` | 152 |
    | `app`, sobre la fusión con `develop` `19b4e0f` | 462 |

    Todos con `Failures: 0, Errors: 0, Skipped: 0`.
  - **10 de 10 mutaciones muertas.** Las 1, 2, 3 y 6 las mataron tests de `identity` antes de llegar a `app`.
  - `ci-local.sh` en un clon limpio: ~~CI_PENDIENTE~~ **hecho** (nota del 2026-10-24, sesión web de Claude Code): clon limpio de `12ef1e8`, `free -g` con 15 GB disponibles, `BUILD SUCCESS`, ningún test fallido. Evidencia: `Documentos/evidencia-ci/ci-local-12ef1e8-2026-10-24T22-50-09Z.txt`. Resumen literal de Surefire:
    - `contracts`: "No tests to run."
    - `core`: "Tests run: 336, Failures: 0, Errors: 0, Skipped: 0"
    - `crypto`: "Tests run: 50, Failures: 0, Errors: 0, Skipped: 0"
    - `ai`: "Tests run: 47, Failures: 0, Errors: 0, Skipped: 0"
    - `api`: "Tests run: 154, Failures: 0, Errors: 0, Skipped: 0"
    - `identity`: "Tests run: 332, Failures: 0, Errors: 0, Skipped: 0"
    - `convocatoria`: "Tests run: 253, Failures: 0, Errors: 0, Skipped: 0"
    - `app`: "Tests run: 462, Failures: 0, Errors: 0, Skipped: 0"
    - core 336 y api 154, frente a los 326 y 152 de la tabla de arriba: la diferencia son los tests del Bloque B que trajo el merge de `develop` (`1b0a32b`). Las anotaciones `@Test` suben +10 en core y +2 en api entre `11e018d` y `12ef1e8`.
    - Entorno: Docker Hub daba 429; dockerd usó el espejo `mirror.gcr.io` (configuración fuera del repositorio). `bitnamilegacy/minio` llegó con su digest fijado (§0.32). Detalle en la nota del final de la evidencia.
  - **Mutaciones repetidas** (nota del 2026-10-24, sesión web): en otro clon limpio de `12ef1e8`, con la CI ya terminada y la línea base sin mutar en verde, "Mutaciones vivas: 0". Las 1, 2, 3 y 6 murieron en tests de `identity` (`AccountTest`, `IdentityPrincipalPortImplTest`, `AccountApplicationServiceIntegrationTest`); la 4 y la 9, en `api`; la 7, en `app`; la 5, la 8 y la 10, en `identity`.
- **Pendiente:**
  - **P-R3-3:** defecto conocido (un login en el segundo de una revocación recibe un token rechazado). Va en su propio PR tras la Enmienda 1 de ADR-050 (#120) y antes de R-4;
  - R-R3-1 (límite de intentos) como pregunta para Carlos;
  - avisar a la web y al móvil cuando se fusione: la ruta nueva, el token que hay que sustituir y el 500 tras confirmar.

### 0.36 Ficha R-6, Bloque B: fecha del pago en el seguimiento (G-2) y recaudado por mes (G-6) (2026-10-23, `feat/r6-bloque-b`)

- **Norma:** ficha R-6, CONGELADA (§4; tests 5 y 6). Plan: `plan-r6-bloque-b.md`, APROBADO (Manuel, 2026-10-23), con P-R6B-1 a P-R6B-7.
- **Qué hace:**
  - **G-2:**
    - `core`: `FundClearingDatesReadPort` y `FundClearingDatesQuery`, que dan el primer y el último `FUNDS_CLEARED` (1.0 o 2.0) por `sequence`, leídos del event store (C-2).
    - `api`: `financialSnapshot.firstClearedAt?` y `lastClearedAt?`.
  - **G-6:**
    - `GET /api/v1/organizations/{organizationId}/funds/monthly?months=1..12`, en `FundController` (`api`), con `no-store`.
    - En `core`, `FundMonthlyClearingReadPort`, implementado por `FundOperationalQueryService` con la política DD-31.
    - Corte de mes en `America/Bogota`, pertenencia por la génesis y meses vacíos en 0 por cada moneda de los fondos.
    - Tope de 10 000 fondos, con `truncated`.
    - `FundClearingEventsPort` y `MongoFundClearingEventsAdapter` leen las liquidaciones con una sola consulta.
  - **Índice parcial `idx_fund_genesis_organization`** para el directorio de fondos (P-R6B-7), en un commit propio. Medido: 202 000 claves y 514 ms antes, 500 claves y 3 ms después.
- **Hallazgo registrado (P-R6B-1):** entre `4ab3a34` (2026-09-01) y `0579f41` (2026-09-16), `Fund` escribía `FUND_REGISTERED` y `FUNDS_CLEARED` 1.0 sin organización. Esos fondos quedan fuera de `GET /funds` y de G-6.
- **Evidencia:**
  - **Rojo primero:** `core` y `api` no compilaban; `app` falló por aserción (3 fallos de G-6 y 1 error de G-2); el índice falló por aserción.
  - **Verde por capas,** con el módulo completo en cada una.
  - **10 de 10 mutaciones muertas:** las 9 del plan más la del índice.
  - `ci-local.sh` en un clon limpio, con la evidencia en `Documentos/evidencia-ci/`.
- **Pendiente:** avisar a la web y al móvil (A-4 y B-2 del plan de pantallas) cuando se fusione.

### 0.35 A2: historial de donaciones con la Enmienda 5 de ADR-037 (2026-10-22, `feat/convocatoria-a2-historial`)

- **Norma:**
  - `ADR-037-enmienda-5-fecha-de-creacion-y-vista-del-historial.md`, APROBADA por Carlos (comunicado por Manuel el 2026-10-21; falta registrar la hora y el lugar).
  - Ficha L-FRONTS, A2 CONGELADO.
  - Plan: `plan-a2-historial-donaciones.md`, APROBADO (Manuel, 2026-10-22), con P-A2-1 a P-A2-5.
  - Numerada 0.35 porque el #111 ya usa la 0.34.
- **Qué hace:**
  - `convocatoria`:
    - `DonationIntent.createdAt`, obligatorio en las dos `create` y nulo en las antiguas;
    - `DonationView` con `createdAt`, `confirmedAt`, `organizationRef`, `paymentMethod` y `publicCode`, este solo de las `PUBLIC` al leer y filtrado dentro de `convocatoria`;
    - historial por `createdAt` descendente, con las antiguas al final (DD-21).
  - `app`: `AccountDonation` gana `createdAt?`, `confirmedAt?`, `organizationName?`, `paymentMethod` y `publicCode?`. Nunca `organizationRef` ni `donorRef`.
  - Sin índice compuesto (P-A2-2, medido con `explain()`). Umbral: añadirlo si una cuenta pasa de unas 1000 intenciones.
- **Evidencia:**
  - **Rojo primero:** `convocatoria` no compilaba; en `app`, dos pruebas HTTP fallaron por aserción. Las otras tres aserciones negativas ya pasaban desde el principio.
  - **Verde por capas,** con el módulo completo en cada una.
  - **9 de 9 mutaciones muertas.**
  - `ci-local.sh` en un clon limpio, con la evidencia en `Documentos/evidencia-ci/`.
- **Pendiente:**
  - avisar a la web y al móvil cuando se fusione;
  - nota "A2 implementado en <commit>" en la ficha L-FRONTS;
  - completar la hora y el lugar de la aprobación de la Enmienda 5.
- *Nota (2026-10-24):*
  - **La hora y el lugar de la Enmienda 5 quedan registrados:** aprobada por Carlos el **2026-10-22 a las 00:12, hora de Colombia (05:12Z)**, por **WhatsApp**. Dato aportado por Manuel el 2026-10-24; no hay enlace ni commit de Carlos que lo cite (`ADR-037-enmienda-5-…md`, nota bajo el estado).
  - **La nota "A2 implementado en `0b3dc3e` (#112)" está en la ficha L-FRONTS.**
  - Los avisos a la web y al móvil siguen pendientes, fuera de este repositorio.

### 0.34 ADR-051, Tarea 2: logo de la organización (2026-10-21, `feat/app-adr051-t2-logo`)

- **Norma:** `ADR-051-fotos-y-almacenamiento.md` (APROBADO, con la Enmienda 1). Plan: `plan-adr051-tarea2-logo.md`, APROBADO (Manuel, 2026-10-21; #109), con las decisiones de implementación D-T2-1 a D-T2-4.
- **Qué hace:**
  - `identity`: `logoObjectKey` en `Organization` (F9), que se escribe en transacción con reintento y devuelve la referencia anterior leída dentro (`OrganizationLogoReferenceService`); lecturas en `OrganizationLogoReferenceQuery`, con `READ_ORGANIZATION_LOGO` (P-T2-2).
  - `app`: `PUT`, `DELETE` y `GET /api/v1/organizations/{organizationId}/logo` y `GET /api/v1/public/campaigns/{publicCode}/organization-logo`. Orden: permiso, 2 MB, subida, `identity`, borrado del anterior (F10, `REPLACED`/`REMOVED`). 413, 415, 503 y 404 (`OrganizationLogoNotFoundException`) por `MediaApiErrorMappings`. *Multipart* de 5 MB y 6 MB. `organizationLogoUrl?` en CV-07 y en el descubrimiento, y `logoUrl?` en S-18.
  - `api`: la ruta pública en `PublicRoutes` y los textos fijos de 413, 415 y 503.
- **Evidencia:**
  - **Rojo primero:** `identity` y `app` no compilaban; `api` dio 3 fallos por aserción; y las 17 pruebas HTTP de `app` fallaron por aserción, después de corregir la configuración del propio test.
  - **Verde por capas,** con el módulo completo en cada capa.
  - **9 de 9 mutaciones muertas.**
  - `ci-local.sh` en un clon limpio, con la evidencia en `Documentos/evidencia-ci/`.
- **Abierto:** CORS no admite `PUT` ni `DELETE` (DD-57); lo decide Carlos. Avisar a la web y al móvil cuando se fusione.

### 0.33 Ficha R-6, Bloque A: `assetType`, fechas de las convocatorias y título y organización en el seguimiento (2026-10-21, `feat/r6-bloque-a`)

- **Norma:** `ficha-R6-datos-estilo-v2.md`, CONGELADA (sha256 `ccc3801288de557c57a759c598018f6355d059e24496990b0daa23900e884248`), §3 Bloque A y §7 tests 1 a 4. Plan: `plan-r6-bloque-a.md`, APROBADO (Manuel, 2026-10-21) con P-R6A-1 (b), P-R6A-2 sin enmienda, P-R6A-3 `CampaignPublicSummaryProducer` y P-R6A-4 aceptado. Plan y aprobación en el PR #109.
- **Qué hace:**
  - **G-4b:** `PhysicalAssetOperationalView` (`core`) y `OperationalResponse` (`api`) ganan `assetType`, en la lista de la organización y en `GET /physical-assets/{assetRef}`. Siguen sin genealogía ni donante.
  - **G-5:** `AdminCampaignView` (`convocatoria`) y `OrganizationCampaignsController.Item` (`app`) ganan `startDate` y `endDate` (ISO-8601 UTC, como CV-07).
  - **G-1:** `CampaignPublicSummaryPort` en `contracts` (D-R6-1), implementado en `app` por `CampaignPublicSummaryProducer` con `CampaignTitleQuery` (consulta nueva de `convocatoria`, P-R6A-1 (b)) y `OrganizationPublicNamePort`. `PublicDonationTrackingDTO` gana `campaignTitle?` y `organizationName?`; sin `campaignRef` no se consulta el puerto.
  - **Test 4:** ArchUnit en `app` (`ApiModuleDependenciesArchitectureTest`): `api` no depende de `convocatoria` ni de `identity`, con una violación deliberada que demuestra que la regla está activa.
- **Evidencia:** rojo primero (los cuatro módulos fallan al compilar los tests; en `app`, apartando un momento los tests que no compilaban, las cuatro pruebas HTTP fallaron: 2 fallos y 2 errores); 8 de 8 mutaciones muertas; `referencia-api-v1.md` con notas fechadas (§3, §6 y §8).
- **Pendiente fuera de este repositorio:** avisar a la web y al móvil de que G-4b, G-5 y G-1 están disponibles desde el commit de `develop` en que se fusione.

### 0.29 Cierre del encargo 6: "backend cerrado para que mañana el equipo trabaje en el front" (2026-10-08)

| Punto | Resultado | PR |
|---|---|---|
| P1 Cierres pequeños | DD-74 ratificada y `Email` sin espacios en los extremos; H-IDX-1 registrado para fases anteriores (§0.28); nulabilidad de `referencia-api-v1.md` revisada contra el código; ADR-043 sustituido por la versión APROBADA del móvil (dudas de duplicados en §0.28) | #97 |
| P2 Predictor en el repositorio | `paxfide-predictor/` dentro de `Donaciones`, fuera de Maven, solo datos sintéticos; ADR-044 base (aportado por Carlos) con sus enmiendas (1 = P3; 2 = D1, aprobada) | #98 |
| P3 Estimaciones históricas (S-10) | `GET …/prediction/history`: cortes 0,15/0,25/0,50 con lo recaudado reconstruido desde el Event Store; de punta a punta igual a scikit-learn sobre los datos de cada momento (DD-75) | #99 |
| P4 Integridad visible | `GET /donations/tracking/integrity` con el `trackingCode`; nada de otras donaciones; caché de 5 min (DD-76). Hallazgo **H-TR-1**: el seguimiento da 401 con un código inválido, no 404 (corregida la referencia) | #100 |
| P5 Datos de demo | Perfil `demo-seed`: todo el escenario por la API (y servicios donde no hay ruta), solo sobre base vacía; `demo-credenciales-locales.md` de una ejecución real (DD-77) | #101 |
| P6 B4 (opcional) | Enmienda 1 de ADR-039 **aprobada e implementada**: §2.2 recálculo del `eventHash`, cadena y forma anterior (DD-78); §2.3 `COLLECTING_FAILED`, `RETRY`/`RELEASE` auditado (DD-79). Sin cambios en canonicalización, hash, Merkle ni anclaje | #102, #103 |
| P7 Cierre | Reactor completo en solitario, golden path 19 de 19, `novedades-api-para-front-2026-10-08.md` | este PR |

- **Recorrido real tras B4** (base vacía, Ganache, `scripts/demo/recorrido.py`): completo, con 2 batches `ANCHORED` (`evidencia-fase6/demo-local-2026-10-08-encargo6/`). Una donación nueva posterior, verificada con el recálculo de B4 por `/donations/tracking/integrity`, da `MATCH`.
- **Pendiente de Carlos:**
  - ratificar DD-75 a DD-79;
  - decidir H-TR-1: dejar el 401 o pasar a 404;
  - aclarar los duplicados con el móvil (`api-contract-matrix.md`, `front-fase1.md`, `reglas-equipo-y-agentes.md`);
  - aprobar ADR-044 (sigue PROPUESTO);
  - la testnet.

### 0.28 Encargo 6, P1: cierres pequeños (Carlos, 2026-10-08, noche)

- **H-IDX-1, registro para fases anteriores** (como se hizo con las proyecciones en §0.6: "nunca habían funcionado con datos reales"):
  - **Hasta #92, en la aplicación real no existía ninguno de los índices únicos o parciales que sostienen las garantías de concurrencia e idempotencia de fases anteriores.** No es una regresión arreglada: nunca se habían creado. Faltaban:
    - `event_store.idx_stream_sequence` (único `{streamId, sequence}`): el control de concurrencia optimista del Event Store;
    - `campaign_assignments.uq_active_employee_assignment` (único parcial): un `EMPLOYEE`, una sola convocatoria activa;
    - `donation_intents`: `uq_fund_id`, `uq_payment_session_id` y `uq_provider_event` (idempotencia del webhook de pagos), entre otros;
    - `unacceptable_payment_events.uq_provider_event` y `donor_pseudonyms.pseudonym`.
  - **Consecuencia, que se registra tal cual:** esas garantías **solo estaban probadas en tests**. En la aplicación real, dos escrituras concurrentes del mismo stream o el mismo evento del proveedor repetido no habrían chocado contra ningún índice.
  - **Precisión:** sí existían 5 únicos o parciales, los de documentos con repositorio de Spring Data: `accounts.email`, `configuration_change_requests.uq_pending_configuration_change`, `convocatorias.uq_public_code`, `merkle_batches.batchId` y `organization_invitations.tokenHash`. Los reclamos de comandos (`processed_commands`, `convocatoria_processed_commands`) se apoyan en `_id`, que Mongo siempre crea único, así que esos sí estaban protegidos.
  - Evidencia: `evidencia-fase6/h-idx-1-indices-al-arrancar-2026-10-08.txt` (21 índices antes y 53 después).
- **DD-74 ratificada** con un añadido: `Email` quita también los espacios de los extremos (test en rojo primero).
- **`referencia-api-v1.md`, revisión de nulabilidad contra el código.**
  - Regla común nueva: `campo?` puede faltar o llegar `null`.
  - Corregidos: `currentLocation?` (falta en `DISPATCHED`, S-05 del móvil); `organizationName?` en las dos rutas públicas de convocatorias; `paymentRedirectUrl?` (solo `GATEWAY`) y `statusToken?`; `locationZone?` en el seguimiento y en el historial; `content` y `source` con `null` en la narrativa pública; `configurationVersion: null` al rechazar; `campaignTitle?` y `status?` de miembros (ramas defensivas).
  - Documentados los campos del seguimiento que faltaban: `status`, `refundedAmount` y `custodianCategory`.
- **ADR-043** se sustituye por la versión **APROBADA** del repositorio móvil (`Toffy22Cj/PaxFide_Mv`, `develop` 5fd86be), con una nota de procedencia. La rama `main` del móvil conserva una versión PROPUESTA anterior.
- **Otros duplicados con el repositorio móvil (`develop` 5fd86be): no se toca nada; dudas para Carlos.**
  - `contract-wiring-review.md`: idéntico.
  - `reglas-equipo-y-agentes.md`: difiere en 3 líneas.
  - `api-contract-matrix.md`: difiere en 21 líneas.
  - `front-fase1.md`: difiere en unas 577 líneas.

  En los tres casos no se puede saber cuál es la copia canónica: el clon del móvil es superficial y las fechas no son fiables. Queda pendiente de Carlos.

### 0.27 Ratificación y encargo 5 (Carlos, 2026-10-08)

- **DD-57 y DD-59 a DD-73: RATIFICADAS — Carlos, 2026-10-08.**
- **Deuda registrada (DD-66): transferir el papel de `REPRESENTATIVE` por HTTP.** El dominio lo tiene (`TransferRepresentativeAndRemove`, ADR-026), pero no hay ruta. Hoy los roles del representante solo los cambia él mismo y no puede quitarse.
- **H-IDX-1: confirmado y corregido.** Con la aplicación real arrancada en perfil `dev` sobre una base vacía, antes de cualquier petición solo existían 21 índices. Faltaban el único `(streamId, sequence)` del event store, el único parcial de `campaign_assignments`, todos los de `donation_intents`, el único de `unacceptable_payment_events`, el de seudónimos y varias colecciones enteras (`processed_commands` y `convocatoria_processed_commands`, entre otras). Solo se creaban los índices de documentos con repositorio de Spring Data. Ahora `MongoIndexInitializer` crea, antes de servir tráfico, las 30 colecciones `@Document` y sus 23 índices declarados (53 índices en total, contando `_id`). `MongoIndexesAtStartupIntegrationTest` falla si falta alguno. Evidencia: `evidencia-fase6/h-idx-1-indices-al-arrancar-2026-10-08.txt`. (#92)
- **Encargo 5, puntos (2) a (6):**

| Punto | Resultado | PR |
|---|---|---|
| (2) D-06 | Cerrar una convocatoria pasa sus asignaciones `ACTIVE` a `HISTORICAL` dentro de la misma transacción del cierre (`Command-Id`). Si el cierre falla, ni la convocatoria ni las asignaciones cambian. El empleado queda libre para otra convocatoria | #93 |
| (3) Predicción fuera de rango | Con `t < 0,15` o `t > 0,50` responde `OUTSIDE_TRAINED_RANGE` con el motivo explícito y ninguna cifra; los bordes están incluidos | #94 |
| (4) Emails en minúsculas | `Email` normaliza con `toLowerCase(Locale.ROOT)`: el registro, el login, la unicidad, el arranque de plataforma y las invitaciones usan el mismo valor. Sin migración (la demo tenía 0 cuentas con mayúsculas). DD-74, pendiente de ratificación | #95 |
| (5) H-DEMO-2 | El texto de respaldo de la narrativa individual va en español y sin `fundId` ni secuencia; solo lleva los recuentos | #96 |
| (6) Runbook CORS | `demo.env.example` y `runbook-demo-local.md` usan el puerto 3000 (`localhost` y `127.0.0.1`), también en el enlace de las invitaciones. Comprobado con el backend real: 3000 → 200 con `Allow-Origin`; 5173 y un origen ajeno → 403 | #96 |

  Cada PR lleva dentro su evidencia (rojo, verde, mutaciones y `ci-local.sh` sobre su último commit). Ningún commit directo a `develop`. Dos `ci-local.sh` fallaron primero y su salida va dentro del PR:
  - en H-IDX-1, 3 tests reutilizaban un `EMPLOYEE` en varias convocatorias activas, y el índice ahora presente lo impide;
  - en D-06, el escenario completo esperaba la regla vieja.

### 0.26 Cierre del encargo 3 y de la tercera autorización (2026-10-08)

**Encargo 3** (DD-29 a DD-56 ratificadas salvo DD-53), punto por punto:

| Punto | Resultado | PR |
|---|---|---|
| (1) CORS S-03 | hecho | #74 |
| (2) H-P12-1: narrativa sin citas → fallback | hecho | #75 |
| (3) DD-53: cursor opaco (T-35) | hecho (AES-256-GCM) y **corregido por Carlos**: clave solo por entorno, sin valor por defecto, *fail-fast*, distinta de los demás secretos y nunca en logs | #77, #80 |
| (4) H-P2-1 (contraseña ≥ 12), H-P2-2 (sin `fullName`), H-P2-4 (409 en `CLOSED`), predictor solo COP | hecho; además, el **test de punta a punta del predictor** pedido por Carlos (CV-01 en COP → predicción HTTP = scikit-learn con la meta en pesos) | #81 |
| (5) `FundNotFoundException` independiente, mismo 403 | hecho | #82 |
| (6) S-05: QR a `/tracking` sin código; exponente de COP fijado | hecho | #86 |
| (7) fase roja con `git stash` o rama, nunca `tar` | aplicado en todos los bloques | — |
| (8) reactor final en solitario | `scripts/ci-local.sh` sobre `develop` tras fusionar este cierre, sin nada más corriendo; salida literal en el informe a Carlos | — |

**Decisiones de Carlos aplicadas:**
- Ganache como evidencia de cierre (#79).
- CI local `scripts/ci-local.sh` como excepción permanente a la regla 3.2 (#76).
- `runbook-demo-local.md`, probado en vivo, también para las invitaciones (§5b).

**Tercera autorización** (organizaciones, usuarios, empleados y administradores):

| Bloque | Resultado | PR |
|---|---|---|
| §2 ADR-049 (correo, invitaciones, excepción del fragmento) | aplicado como decisión delegada (DD-60) | #83 |
| §3.1 Crear organización (R9) y cola de verificación con cursor opaco | hecho | #83 |
| §3.2 Conceder, revocar y listar administradores de plataforma | hecho (nunca sin administrador; 409 ante el estado ya pedido) | #85 |
| §3.3 Invitar, listar, revocar y aceptar invitaciones; cambiar el rol; quitar miembros | hecho, con Mailpit, y SMTP real probado por Testcontainers y en vivo | #88 |
| §3.4 Mis convocatorias asignadas | hecho | #89 |
| §3.5 Configuración con solicitud y aprobación (Enmienda 4 de ADR-037) | redactada (DD-73) **e implementada** | #90 |

**Hallazgos de estos encargos:**
- **H-DEMO-1:** corregido en #78.
- **H-DEMO-2: CERRADO (encargo 5, #96).** El texto de respaldo de la narrativa individual va en español y sin identificadores.
- **H-CI-1:** alta no atómica de las proyecciones de `core`; corregido en #84 y #87.
- **H-IDX-1 (sospecha, por verificar):** índices declarados solo por anotación que quizá no existen en los tests de `app` ni, hasta su primer uso, en producción.
- **Normalización de `Email`: CERRADO (encargo 5, #95, DD-74).** `Email` se guarda y se compara en minúsculas.

**Decisiones delegadas nuevas, pendientes de ratificar:** DD-57, DD-59 a DD-73. DD-58 está marcada como corregida por Carlos.

**Endpoints nuevos para la web** (detalle en `referencia-api-v1.md`; base `/api/v1`):

| Pantalla | Método y ruta |
|---|---|
| Registrar organización | `POST /organizations` |
| Plataforma: cola de verificación | `GET /platform/organizations?status=&cursor=` |
| Plataforma: administradores | `GET /platform/administrators`, `POST /platform/administrators`, `POST /platform/administrators/{accountId}/revoke` |
| Organización: invitaciones | `POST /organizations/{id}/invitations`, `GET /organizations/{id}/invitations`, `POST /organizations/{id}/invitations/{invitationId}/revoke` |
| Aceptar invitación (ruta web `/invitaciones#token=…`) | `POST /invitations/accept` con `{token}` en el cuerpo; la web borra el fragmento con `history.replaceState` |
| Organización: miembros | `POST /organizations/{id}/members/{accountId}/role`, `POST /organizations/{id}/members/{accountId}/remove` (la lista ya existía: `GET /organizations/{id}/members`) |
| Empleado: mis convocatorias | `GET /me/campaigns` |
| Convocatoria: configuración | `POST /campaigns/{ref}/configuration`, `POST /campaigns/{ref}/configuration-change-requests`, `GET /campaigns/{ref}/configuration-change-requests`, `POST …/{requestId}/approve`, `POST …/{requestId}/reject` |

**Golden path:** `GoldenPathHttpIntegrationTest`, un único recorrido que cubre los 19 criterios, en verde en cada `ci-local.sh` de estos PR y en el reactor final.

### 0.25 Tercera autorización de Carlos (2026-10-07T22:10Z): organizaciones, usuarios, empleados y administradores

- **Alcance:** la aplicación debe tener completas las funciones de organizaciones, usuarios, empleados, administradores de organización y administrador de plataforma. Cierre: 21 de octubre. Orden: crear organización (R9) y cola de verificación → administradores de plataforma → miembros e invitaciones por correo → "mis convocatorias" del empleado → edición de la configuración con aprobación (enmienda de ADR-037).
- **ADR-049** (correo e invitaciones), aprobado como decisión delegada (DD-60). Decisiones del agente: DD-60 a DD-67 (`decisiones-delegadas-2026-10.md` §4).
- **Deuda registrada — confirmación manual de pagos (transferencia y efectivo): FUERA** por decisión de Carlos. Motivos:
  1. **P1:** el diseño de la confirmación independiente no está cerrado; falta decidir quién confirma y con qué autoridad (`auditoria-cierre-f1-f2.md`, R9; Q-v2-6).
  2. **Referencia de pago:** no hay ninguna referencia que ligue un ingreso real (extracto o recibo) con una `DonationIntent`.
  3. **Riesgo de abuso:** una confirmación manual sin control dual crea dinero en el sistema con la palabra de una sola persona.

  Mientras tanto, las convocatorias solo cobran por la pasarela (simulada en `dev`).

### 0.24b Hallazgo H-CI-1 (2026-10-07T23:10Z): alta no atómica de las proyecciones de `core`

`scripts/ci-local.sh` sobre una rama que solo cambiaba documentos y un test de `convocatoria` falló en `core`, en `ProjectionChangeStreamE2ETest.clearFundsGenesis_isProjectedWithAmountsCurrencyAndCampaign` (`expected 1500, but was 0`).

- **Causa raíz:** al crear una proyección, `DonationProjectionHandler` hacía dos escrituras: `save` del documento vacío (importes a 0) y después `update` con el evento. Durante un instante, un lector veía la donación con importe 0. El historial de un activo nacía vacío del mismo modo. Bajo carga (varias ejecuciones de Maven a la vez), el test lo vio.
- **Corrección** (`fix/proyeccion-donacion-alta-atomica`): el alta es un único `upsert` (`$setOnInsert` del estado), y el historial, un `upsert` con `$push`.
- **Test de regresión determinista:** un *change stream* sobre `donation_projections` comprueba que el primer cambio del fondo es un `insert` con los importes. Con el código anterior falla siempre (0 en vez de 1500).
- Los demás `save` de `core/application/projection` crean documentos completos en una sola escritura.
- **Corrección de la corrección** (`fix/proyeccion-alta-forma-completa`): el `upsert` de #84 dejaba **sin escribir** `allocations`, `logistics` y los importes que el evento no toca, que el `save` anterior ponía a `[]` y 0. Para Spring no cambiaba nada, porque los inicializadores de la clase rellenan esos valores, pero sí para un lector en crudo. El ayudante de `ProjectionChangeStreamE2ETest` lo era, y `splitChild_createdByTheSaga…` lanzó un `NullPointerException` en el `ci-local.sh` de §3.3. Ahora el alta escribe con `$setOnInsert` la misma forma que antes en las rutas que el evento no modifica. El test de regresión comprueba también la forma (rojo: `allocations` ausente).

### 0.24 Decisiones de Carlos (2026-10-07T21:41Z): Ganache como evidencia, CI local y demo local

- **Anclaje (criterios 10, 11, 12 y 18):** se cierran con la evidencia de la **cadena local (Ganache)**. La testnet pública pasa a ser **opcional** (`runbook-anclaje-testnet.md`). Motivo: no depender de *faucets*, cuotas ni disponibilidad de la red el día de la demo. **Limitación aceptada:** un anclaje en Ganache es real en la cadena local, pero no lo pueden verificar terceros. Anotado en `golden-path.md` §8 y `plan-cierre-fase6-codigo.md`.
- **B7 cerrado con evidencia de Ganache:**
  - recorrido automático `GoldenPathHttpIntegrationTest` (19 de 19, `verifyBatch` → `MATCH`), en cada `scripts/ci-local.sh`;
  - recorrido en vivo con `runbook-demo-local.md`, probado desde cero: `evidencia-fase6/demo-local-2026-10-07T21-40-35Z/`. 12 eventos del fondo, el padre y el hijo en 2 batches `ANCHORED`, con recibos `0x1` en Ganache.
- **CI local — excepción permanente a la regla 3.2 (Carlos, 2026-10-07):** la cuenta de GitHub está bloqueada por facturación y no hay presupuesto. El CI es `scripts/ci-local.sh` y su salida va en `Documentos/evidencia-ci/`. Todo PR adjunta la salida sobre su último commit (`reglas-equipo-y-agentes.md` §3.2).
- **Demo local** (`runbook-demo-local.md`; `scripts/demo/`):
  - `docker-compose.yml` con Mongo en réplica y Ganache;
  - `deploy-anchor-registry.sh`;
  - `demo.env.example`, con valores solo locales;
  - `recorrido.py`, que enmascara las credenciales en la evidencia;
  - semilla `DemoSeedRunner`, solo para el perfil `dev`.
- **Hallazgos al probar la demo:**
  - **H-DEMO-1 (corregido en `fix/narrativa-fallback-cacheado`):** sin clave de LLM, la narrativa individual no salía nunca de `PENDING`. El fallback se guardaba con otro identificador de modelo y no se encontraba, así que cada consulta llamaba al proveedor y guardaba otro documento: 90 en una prueba. Los tests no lo veían porque siempre había un LLM simulado que respondía.
  - **H-DEMO-2: CERRADO (encargo 5, #96).** Antes, el texto de respaldo estaba en inglés e incluía el `fundId` interno; ahora va en español y sin ids.

### 0.23 P4 y cierre de la segunda autorización (2026-10-07)

- **P4 (solo documento):** `ADR-042-enmienda-1-reconstruccion-jmx.md`, **PROPUESTA**: operación JMX `rebuildProjections()` para B-PROJ PR 2 (alcance, secuencia con `finally`, sin abortar por un evento, candado de una sola ejecución, sin HTTP). No autoriza código. Nada de B4.
- **Golden path:** **19 de 19** criterios en `GoldenPathHttpIntegrationTest` — 15 por HTTP (1–9, 13–17, 19) y 4 por los procesos internos del sistema sin ruta (10, 11, 12, 18) en una cadena local. Testnet pendiente de Carlos (`runbook-anclaje-testnet.md`).
- **Reactor en `develop` tras P2:** **1194 tests**, 0 fallos (core 325, crypto 50, ai 39, api 123, identity 268, convocatoria 236, app 153).
- **Decisiones nuevas:** DD-29 a DD-56, todas `PENDIENTE DE RATIFICACIÓN` (`decisiones-delegadas-2026-10.md` §3).
- **Hallazgos abiertos:** H-P12-1, H-P14-1, H-P2-1 a H-P2-4.

### 0.22 P2 — endpoints del panel y públicos (2026-10-07, `feat/p2-endpoints`) — HECHO

- **Hechos los 8** (`fichas-p2-endpoints.md`): `GET /me` (ficha N1 tal cual); `POST /auth/register`; `GET /organizations/{id}/campaigns`; cerrar convocatoria (`CLOSED` terminal); CV-03 y retirar responsable; descubrimiento público `GET /public/campaigns` (solo `?cursor=`, nunca `PRIVATE_LINK`); activos y miembros de la organización (ID-12); rechazar y pedir información a una organización (409 sobre un estado final).
- **Fuera, por instrucción:** solicitud/aprobación de cambios de configuración (D3).
- **Ninguna regla de dominio inventada:** todos los comandos existían; las lecturas nuevas solo leen. Huecos registrados: H-P2-1 (sin política de contraseña), H-P2-2 (sin `fullName`), H-P2-3 (el 409 del registro revela emails), H-P2-4 (retirar en `CLOSED` sin regla).
- **Referencia:** `referencia-api-v1.md` (método, ruta, auth, cuerpo, respuestas y errores de toda la API implementada).
- **Decisiones:** DD-48 a DD-56, `PENDIENTE DE RATIFICACIÓN`.
- **Evidencia:** `evidencia-fase6/p2-endpoints-903c885-2026-10-07.txt`. Reactor **1194 tests**; 9 mutaciones, 9 muertas.

### 0.21 P3 — predictor de convocatorias en el backend (2026-10-07, `feat/p3-predictor`) — HECHO

- **Qué hace** (`plan-p3-predictor.md`; norma propuesta `ADR-044-enmienda-1-prediccion-en-backend.md`, BORRADOR):
  - el modelo entrenado offline (`paxfide-predictor`, `baseline-0.2.0`, sin STRICT) se exporta a JSON con `scripts/predictor/export_baseline_json.py`: la regresión logística (mejor Brier) y el `HistGradientBoostingRegressor` del % final. El predictor **no se movió** de su rama ni de su repositorio;
  - Java lo evalúa sin Python ni dependencias nuevas (`CampaignPredictorModel`) y calcula las variables con los datos reales de la convocatoria (`CampaignFeatureBuilder`, `CampaignPredictionDataQuery`);
  - **paridad primero, tolerancia 1e-9:** 303 vectores del modelo (diferencia máxima 2.2e-16 en la probabilidad y 0.0 en el % final) y 172 instantáneas de variables frente a `build_snapshot` de Python (8.9e-16);
  - `GET /api/v1/organizations/{organizationId}/campaigns/{campaignRef}/prediction`, solo `ADMINISTRATOR`/`REPRESENTATIVE` de la organización (el resto, el mismo 403): `kind: "ESTIMATE"`, `modelVersion`, `warning: "modelo entrenado con datos sintéticos"`; STRICT → `available: false` con `STRICT_POLICY_EXCLUDED`. Solo lectura (test: ninguna colección cambia).
- **Decisiones:** DD-41 a DD-47, `PENDIENTE DE RATIFICACIÓN`. Sustituye, si se ratifica, Q-v2-9 (mock server) por instrucción de Carlos.
- **Riesgos:** datos sintéticos; metas en moneda distinta de COP fuera de distribución sin aviso; reentrenar exige reexportar y pasar la paridad.
- **Evidencia:** `evidencia-fase6/p3-predictor-6c802da-2026-10-07.txt`. Reactor **1185 tests**; 9 mutaciones, 9 muertas.

### 0.20 P1.4 — anclaje del recorrido en cadena local (2026-10-07, `feat/p1-anclaje-e2e`) — HECHO

- **Qué hace:** `GoldenPathHttpIntegrationTest` levanta Ganache (Testcontainers, como los tests de `crypto`), despliega `AnchorRegistry` y deja correr los procesos reales: productor de `MerkleBatch`, envío y poller. Exige que **todos** los eventos de los dos fondos, del padre y del hijo estén en batches `ANCHORED` (el poller solo marca `ANCHORED` si la raíz leída de la cadena coincide) y que `verifyBatch` dé `MATCH` en cada uno. Escribe `app/target/golden-path-anclaje-evidencia.txt` (red, contrato, raíz, `txHash`, bloque; sin secretos).
- **Testnet: no ejecutada** (es de Carlos). `Documentos/runbook-anclaje-testnet.md`: pasos, variables de entorno sin valores (`GOLDEN_PATH_ANCHOR_TARGET=testnet`, `WEB3_NODE_URL`, `WEB3_PRIVATE_KEY`, `CRYPTO_ANCHOR_*`) y qué evidencia guardar. El mismo test sirve en testnet con esas variables.
- **Hallazgo H-P14-1:** el contador de nonce (`web3_nonce_counter`) empieza en 0 y `seedNonceCounter` no tiene llamador: la wallet de anclaje debe estar a nonce 0 (desplegar el contrato con otra) o sembrarse a mano. No se corrige (toca el anclaje).
- **Golden path:** **19 de 19** criterios en el recorrido; 15 por HTTP y 4 (10, 11, 12 y 18) por los procesos internos del sistema, que no tienen ruta HTTP (matriz §6).
- **Decisión:** DD-40, `PENDIENTE DE RATIFICACIÓN`.
- **Evidencia:** `evidencia-fase6/p1-anclaje-e2e-2c330b3-2026-10-07.txt`. Reactor **1172 tests**; 2 mutaciones, 2 muertas.

### 0.19 B5 — narrativa de convocatoria (2026-10-07, `feat/b5-narrativa-convocatoria`) — HECHO

- **Qué hace** (`plan-b5-narrativa-convocatoria.md`): `GET /api/v1/public/campaigns/{publicCode}/narrative`: `200 AVAILABLE` con el texto validado, `202 PENDING` mientras se genera, `200 UNAVAILABLE` con "Narrativa no disponible" si no pasa el grounding o falla el proveedor; siempre con los hechos públicos (`clearedAmount`, unidades entregadas, receptores distintos).
  - `core`: `CampaignDeliveryFactsQuery` lee del event store los activos de la convocatoria (Camino A, Camino B e hijos de una división) e índice `(eventType, payload.campaignRef)`. Ningún evento, hash ni Merkle cambia.
  - `convocatoria`: `CampaignFundingFactsQuery`. `contracts`: `CampaignAuditFactsDTO` con `currency` y un instante por fuente.
  - `ai`: `CampaignNarrativeGenerator` (caché en memoria por hash de los hechos), `CampaignGroundingValidator`, prompt solo con números y estados.
  - `app`: `CampaignAuditFactsProducer` y la ruta.
- **Condiciones de Carlos, con test:** el LLM solo recibe hechos deterministas (nunca título, descripción ni ids); sin grounding no se publica; "receptores distintos", nunca familias ni hogares; *prompt injection* en el título y la descripción con un LLM simulado: no llega al prompt, y una respuesta que la obedece no se publica.
- **¿LLM real en la demo?** Solo con `SPRING_AI_OPENAI_API_KEY` en el entorno; sin ella, "Narrativa no disponible".
- **Decisiones:** Q-DIA-1 a 6 de `propuesta-d-ia.md` = DD-33 a DD-38, más DD-39, `PENDIENTE DE RATIFICACIÓN`.
- **Golden path:** criterios **14 y 19** en el recorrido → **15 de 19**.
- **Evidencia:** `evidencia-fase6/b5-narrativa-convocatoria-7d32202-2026-10-07.txt`. Reactor **1172 tests**; 12 mutaciones, 12 muertas (una tras añadir un test del directorio).

### 0.18 P1.2 — criterio 13 por HTTP (2026-10-07, `feat/p1-narrativa-individual`) — HECHO

- **Qué hace:** el recorrido (`GoldenPathHttpIntegrationTest`) pide la narrativa individual por HTTP con el `trackingCode` real y exige `AVAILABLE` con `source: LLM_GENERATED`. El LLM es simulado (un `LlmClientPort` de test que cita solo hechos presentes); la validación de grounding y el resto del pipeline son los reales. Solo test: el código ya lo cumplía.
- **¿LLM real en la demo?** Solo si existe la variable de entorno `SPRING_AI_OPENAI_API_KEY`; sin ella cada intento cae al fallback (`FALLBACK_TEMPLATE`). La clave es de Carlos y nunca está en el repositorio.
- **Hallazgo H-P12-1:** `GroundingValidatorImpl` (narrativa individual) **acepta una respuesta sin ninguna cita**: un texto sin hechos citados pasa como "grounded". La narrativa de convocatoria (B5) lo rechaza. No se corrige aquí: cambia el comportamiento de la narrativa individual aprobada; queda para decisión de Carlos.
- **Golden path:** **13 de 19** criterios.
- **Evidencia:** `evidencia-fase6/p1-narrativa-individual-532749f-2026-10-07.txt`. Reactor **1147 tests**; 2 mutaciones, 2 muertas.

### 0.17 P1.1 — Camino A por HTTP (2026-10-07, `feat/p1-camino-a-http`) — HECHO

- **Qué hace** (`plan-p1-camino-a-http.md`):
  - `GET /api/v1/organizations/{organizationId}/funds` (`ADMINISTRATOR` o `EMPLOYEE`): los fondos de la organización, leídos del event store, con disponible y asignaciones; sin `donorRef`.
  - `POST /api/v1/funds/{fundId}/allocations` y `POST .../allocations/{allocationId}/confirm` (`ADMINISTRATOR`, `Command-Id`): id de asignación determinista; reenvío idéntico sin efecto; `Command-Id` de otro comando → 409; fondo inexistente → el mismo 403.
  - El criterio 7 del recorrido usa el **Camino A solo por HTTP**; el Camino B queda como escenario adicional. **Cierra H-B6C-1 y H-B6D-1.**
- **Decisiones:** DD-29 a DD-32, `PENDIENTE DE RATIFICACIÓN`.
- **Golden path:** sigue en **12 de 19** criterios (el 7 ya sin atajos).
- **Evidencia:** `evidencia-fase6/p1-camino-a-http-34abe94-2026-10-07.txt`. Reactor **1147 tests**; 7 mutaciones, 7 muertas.

### 0.16 Segunda autorización de Carlos (2026-10-07), sección 0 — HECHO

- **Ratificadas** DD-01 a DD-28, salvo **DD-18**, que Carlos sustituye: el reenvío de CV-11 emite un `statusToken` **nuevo** y anula el anterior. Implementado, con test HTTP y de módulo.
- **`IllegalArgumentException` → 500.** Solo las validaciones con nombre dan 400. Ningún test dependía del 400 anterior.
- **Criterio de avance:** "N de 19 criterios del golden path cumplidos por HTTP". Hoy, **12 de 19** (1–9 y 15–17).
- **Evidencia:** `evidencia-fase6/s0-dd18-iae-021f766-2026-10-07.txt`. Reactor **1142 tests**; 3 mutaciones, 3 muertas.

### 0.15 B6-d — seguimiento de punta a punta (2026-10-07, `feat/b6-d-seguimiento-http`) — HECHO; B6 completo salvo la narrativa de convocatoria

- **Qué hace:**
  - **TR-D1:** el 404 del seguimiento con `ProblemDetail` fijo (Q-B60-1).
  - **H-B6D-2:** el historial público de un activo (TR-03) **nunca había funcionado con datos reales**. El *mapper* pasaba la referencia del custodio donde se esperaba un estado del ciclo de vida: daba 500, y 400 desde B6-0. Corregido (DD-26).
  - **Recorrido de la demo por HTTP** (`GoldenPathHttpIntegrationTest`): pasos 1 a 5 y el seguimiento público con el `trackingCode` real. Cumple los criterios **1–9 y 15–17** de §8 contra Tomcat real.
- **Fuera:** la narrativa de convocatoria, porque B5 no está listo. La propuesta D-IA (ADR-040 C2–C5, C8) está en `propuesta-d-ia.md` como **PROPUESTO**.
- **Hallazgos abiertos para la web:**
  - **H-B6C-1:** la asignación del Camino A no tiene ruta.
  - **H-B6D-1:** el empleado no tiene de dónde sacar el `fundId`.
  - El test los toma por servicio o de la base de datos.
- **Evidencia:** `evidencia-fase6/b6-d-seguimiento-http-ba6d1f1-2026-10-07.txt`. Reactor **1141 tests** en verde; 6 mutaciones, 6 muertas.

### 0.14 B6-b — donación y pago por HTTP (2026-10-07, `feat/b6-b-donacion-pago-http`) — HECHO

- **Proceso:** autorización de trabajo autónomo de Carlos (2026-10-07). Implementa normas **aprobadas**: la Enmienda 3 de ADR-037 (completa, con E3-Q3 = un año) y ADR-048 (seudónimo aleatorio). Las decisiones de implementación DD-18 a DD-25 están `PENDIENTE DE RATIFICACIÓN`.
- **Qué hace:**
  - **CV-11** (`POST /public/campaigns/{publicCode}/donation-intents`, JWT opcional): `201 {intentId, statusToken, paymentRedirectUrl}`. El `donorRef` lo calcula el servidor: con JWT, `acct:` + el seudónimo de la cuenta; sin JWT, `anon:` + un UUID nuevo. El cliente no puede elegirlo.
  - **Webhook simulado** (`POST /webhooks/payments`): solo con `traceability.demo.simulated-payments=true` (activo únicamente en `application-dev.yml`). Lleva firma HMAC y llama al **mismo** `ConfirmGatewayPaymentUseCase` (Tx 1 → Tx 2 con el orquestador de ADR-045). La intención queda con `paymentProvider = SIMULATED`.
  - **Dos barreras para `SIMULATED`** (E3-Q1): sin la propiedad no existen ni el proveedor ni la ruta, y `convocatoria` rechaza `SIMULATED` aunque llegue por otra vía.
  - **Eventos fuera de orden (D4):** un duplicado no tiene efecto; un fallo sobre `PENDING` la pasa a `FAILED`; un fallo sobre `CONFIRMED` se ignora. Una confirmación sobre `FAILED` no cambia el estado: se registra en `unacceptable_payment_events` (idempotente) y suma en el contador JMX, a la espera de P1.
  - **Consulta de la intención** (`GET /public/donation-intents/{intentId}`, cabecera `Intent-Token`): cuando los fondos están aplicados, entrega el `trackingCode`, válido un año y aceptado por el seguimiento de Fase 3. Un token ausente, incorrecto, caducado o enviado en la URL da el mismo 404 que una intención inexistente.
  - **`GET /account/donations`:** solo las donaciones de la cuenta, con su `trackingCode`, sin el seudónimo. Borrar el seudónimo vacía el historial sin tocar los eventos.
- **Evidencia:** `evidencia-fase6/b6-b-donacion-pago-http-e72cc0e-2026-10-07.txt`. Reactor **1139 tests** en verde; 9 mutaciones, 9 muertas.

### 0.13 B6-a — convocatoria por HTTP (2026-10-07, `feat/b6-a-convocatoria-http`) — HECHO

- **Proceso:** autorización de trabajo autónomo de Carlos (2026-10-07). Q-B6A-1 la decidió Carlos (opción (a)); **Q-B6A-2 a 5 y Q-B60-6 son decisiones delegadas `PENDIENTE DE RATIFICACIÓN`** (DD-01, DD-03 a DD-07 y DD-17 en `decisiones-delegadas-2026-10.md`).
- **Qué hace:**
  - **Rutas:** CV-01 (`201 {campaignRef, publicCode}`), CV-02 (`201 {assignmentId}`), CV-07 (detalle público, sin JWT) y verificar organización (`200 {organizationId, verificationStatus}`, sin `Command-Id`). Todas en `app.web`.
  - **Deudas de la ficha CV-01:** D-1 (ISO 4217), D-2 (no antes de `now − 5 min`), D-3 (`publicCode` de 130 bits), D-7, D-8 y D-9, cada una con excepción nombrada y test de regresión.
  - **CV-02:** no se asigna en una convocatoria `CLOSED` (409). Destinatario ajeno, inexistente o `INACTIVE`: el mismo 409. Convocatoria inexistente, ajena o actor sin rol: el mismo 403.
  - **CV-07:** campos de §2.3 con `acceptedPaymentMethods` y el nombre de la organización (`OrganizationPublicNamePort`, nombre opcional en `Organization`); `PRIVATE_LINK` y `CLOSED` se devuelven; un código inexistente o mal formado da el mismo 404; el `publicCode` no aparece en los logs (test de captura).
  - **Traducciones** de `convocatoria` e `identity` una a una, con un test de exhaustividad que rompe el build ante una excepción nueva sin decidir.
- **Efecto en los tests existentes:** los *fixtures* que creaban convocatorias con fechas ya pasadas (2026-10-01) pasan a 2027; la regla D-2 los rechazaba. Ninguna aserción cambió.
- **Evidencia:** `evidencia-fase6/b6-a-convocatoria-http-dff182f-2026-10-07.txt`. Reactor **1119 tests** en verde (con B6-c); 15 mutaciones, 15 muertas.

### 0.3 Revisión externa de la auditoría (2026-10-07)

Ver `auditoria-fase6-codigo-vs-documentacion.md` §10: hallazgos nuevos B-9/B-10 (severidad A) e incumplimientos de proceso (regla 3.5 en PR #29 y en Blockchain; reglas 3.1/3.2 en tres commits directos a `develop`). **Fuente válida:** el repositorio manda sobre cualquier copia de los documentos fuera de él.

## 1. Resumen de una línea

Las cinco capas de diseño conceptual de Fase 6 quedaron cerradas con review formal de 12 puntos, cada una con su ADR tentativo. De ellas, **solo Blockchain tiene implementación real iniciada y verificada** (Productor de `MerkleBatch`, Fase 1-3 del protocolo). Las demás siguen en estado de diseño aprobado, sin código propio de esta sesión.

*Nota de la consolidación documental del 2026-10-03 (solo Convocatoria):* para Convocatoria esta línea está desactualizada. Su primer corte está implementado (§2, fila 1, y §3bis). No se reescribe la frase porque resume a las cinco capas.

## 2. Estado por capa de diseño

| Capa | ADR | Estado de diseño | Estado de implementación |
|---|---|---|---|
| Convocatoria + Ledger + Assignment + DonationIntent | ADR-037 + Enmienda 1 (aprobada) | Cerrado para el primer corte (`implementation_plan.md` rev. 2.2); P1–P7 y R4 abiertos | Primer corte y flujo de fondos del módulo implementados, commiteados (`81cf87c`) y fusionados (PR #29). Idempotencia de `clearFundsGenesis` ante reenvío verificada (`ProcessedCommandIdempotencyIntegrationTest`); T1/P8 abiertos. `app → convocatoria` añadido (`e269985`). Ver §0 y §3bis |
| Identidad (HumanActor, Platform Admin, verificación Organization, JWT) | ADR-038 | Approved — diseño conceptual; §7 cerrado (2026-09-30); enmiendas de implementación en ADR-038 §9; enmienda ADR-026 aplicada | **Implementado, verificado y fusionado en `develop`** (`aebedb2`; último commit de la rama `2b2a68a`): tareas 1–8, sin JWT ni endpoints HTTP |
| Blockchain (Productor MerkleBatch, IntegrityVerificationPort) | ADR-039 (tentativo) | 12/12 cerrado | **Productor (Fase 1-3), `IntegrityVerificationPort` y recuperación automática de `COLLECTING` implementados y verificados.** Pendientes vigentes en §4 |
| IA (ConvocatoriaAuditFacts) | ADR-040 (tentativo) | Cerrado parcialmente — C1 (nomenclatura de puerto) cerrado; 3 decisiones estructurales (A/B/C = C2–C4) y el productor (C5) siguen abiertos | `CampaignAuditFactsPort` + `CampaignAuditFactsDTO` definidos en `contracts` (puerto separado y deliberado, ADR-040 §2.1/§8). Sin implementación ni consumidor todavía: bloqueado por C2–C5. `AuditFactsPort` (donación individual) intacto; su consumidor real es `DonorReportGenerator` (los documentos lo llaman `NarrativeGenerator`, nombre que no existe en el código) |
| APIs + Frontend | ADR-041 (tentativo) | 12/12 cerrado — mapeo endpoint↔hueco de dominio consolidado | Sin código de esta sesión |

*Nota de la consolidación (2026-10-03), fila Convocatoria:*
- **Situación documental actual:**
  - ADR-037 consolidado con la Enmienda 1 (APROBADA);
  - Enmienda 2 en **BORRADOR**, no integrada como norma;
  - ADR-043 (**Propuesto**) es un documento de otro bloque, citado solo como dependencia externa (`convocatoria-resumen.md` §6.21);
  - `implementation_plan.md` en revisión 3 (aprobación de la rev. 3 pendiente, DH-C-5).
- La fila cita la "rev. 2.2" porque se redactó antes de la rev. 3.
- Índice del bloque: `convocatoria-resumen.md` §0.

## 3. Blockchain — Productor e Integrity Verification implementados

### 3.1 Verificado antes de tocar código (Fase de auditoría)

- `MerkleTree.build(List<String>)`: sin acoplamiento a orden de inserción real — descartado el riesgo que ADR-039 marcaba como pendiente de verificar. No requirió modificación.
- `MerkleBatch`: record inmutable, 15 campos reales (no 14 como decía `blockchain-resumen.md` — discrepancia documental, no bloqueante).
- `EventCanonicalMapper.toCanonicalMap()`: firma explícita sin `actorRef` ni `merkleBatchId` — confirmado por código que agregar `merkleBatchId` a `TraceabilityEventDocument` no afecta `eventHash`, siempre que esa firma no se amplíe.
- `AnchorStatus`: no tenía `COLLECTING`. Auditados los tres consumidores (`BlockchainAnchorScheduler`, `AnchorConfirmationPoller`, `BlockchainAdminOperationsService`) — ninguno usa `switch` exhaustivo, todos filtran por `findByStatus` de un valor específico. Agregar `COLLECTING` es seguro.
- `EventStorePort` (`append`, `loadStream(streamId)`) confirmado insuficiente para las operaciones del productor — motivó el diseño de un puerto nuevo.

### 3.2 Decisiones de arquitectura tomadas durante la implementación (no en el ADR original)

- **`SequenceRange`** (record `fromSequence`/`toSequence`) vive en `contracts` — consumido por `core`, `app` y `crypto`, ninguno es dueño exclusivo.
- **`UnanchoredEventRepositoryPort`** vive en `core.application.port.out`, implementado por `MongoUnanchoredEventAdapter` en `core.infrastructure.persistence.mongo` — mismo patrón que `EventStorePort`/`MongoEventStoreAdapter` (el módulo dueño de la colección posee también el puerto y el adapter).
- **`BlockchainAnchorProducer` vive en `app`**, no en `crypto` — mismo precedente que el orquestador de `STRICT` en ADR-037: cuando una operación necesita atomicidad transaccional entre dos módulos hermanos, el orquestador vive en `app`, ninguno de los dos módulos de dominio importa al otro.
- **`MerkleBatchRepositoryPort.transitionCollectingToPending(batchId, merkleRoot): boolean`** — método nuevo, update condicional real (`WHERE batchId=X AND status=COLLECTING`), reemplaza el `save()` genérico original (que era lectura-luego-escritura, insuficiente para la Fase 3 del protocolo).
- **`coverage[]` persistido en `MerkleBatch`** — `sequenceRangeStart`/`sequenceRangeEnd` permanecen en `MerkleBatchDocument`, marcados `@Deprecated(forRemoval=false)`, sin eliminar (evita romper deserialización de batches históricos).
- **`LegacyBatchCoverageUnavailableException`** (excepción nombrada, `crypto.domain.exception`) — `toDomain()` falla explícitamente si `coverage` es nulo/vacío, en vez de sintetizar un `streamId` falso para batches legacy. Se descartó la síntesis silenciosa por riesgo real de falso `MISMATCH` en `IntegrityVerificationPort`.
  - **Alcance de esta excepción, explícito**: es una barrera total de lectura de batches legacy vía `toDomain()` — no exclusiva de la verificación de integridad. `findByBatchId()`, `findByStatus()`, y cualquier componente que dependa de esas lecturas (incluido el propio anchoring si encontrara un batch histórico en curso) queda afectado igual.
  - No urgente: confirmado que no existen batches históricos reales en el entorno de desarrollo actual.
  - La estrategia real de migración de legacy sigue sin diseñar — esto solo evita que, mientras no exista, el sistema mienta sobre integridad.

### 3.3 Evidencia de verificación (output real, no narrativa)

| Verificación | Resultado |
|---|---|
| Baseline inicial `mvn test -pl crypto` | 27 tests, 0 failures, 5 errors (todos por falta de Docker local — no regresión) |
| `COLLECTING` agregado al enum, re-test | 22 unitarios sin cambios, mismos 5 errores de entorno |
| `MultiCollectionTransactionIntegrationTest` (camino feliz + rollback, Testcontainers real) | `Tests run: 2, Failures: 0, Errors: 0` |
| `BlockchainAnchorProducerTest` (incluye aserción negativa: Fase 1 vacía → Fase 2/3 nunca se invocan) | Verificado con `verify(..., never())` sobre los tres puertos |
| `mvn test -pl crypto` tras refactor de `coverage[]` + `LegacyBatchCoverageUnavailableException` (con Docker, Testcontainers Mongo+Ganache reales) | `Tests run: 43, Failures: 0, Errors: 0` |

Nota de proceso: hubo un reporte intermedio de "`BUILD SUCCESS`" basado en `mvn clean install -DskipTests`, que fue señalado como violación de la regla de evidencia (compilar no es pasar tests) y corregido con la ejecución real antes de aceptar el cierre de esta pieza.

## 3bis. Convocatoria — primer corte (2026-10-01, rama `develop`, HEAD `673eda92`, sin commit)

*Nota de la auditoría 2026-10-07:* registro histórico. El código se commiteó después (`81cf87c`) y se fusionó en PR #29 (`4374d55`); `app → convocatoria` se añadió en `e269985`.

Implementado según `implementation_plan.md` §15, Tareas 0–9. Módulo Maven `convocatoria` con `convocatoria → contracts` como única dependencia de proyecto (`mvn dependency:tree -pl convocatoria`); `app → convocatoria` **no** añadido (B-1); `app/**` sin cambios.

| Verificación | Resultado (output literal de Surefire) |
|---|---|
| Línea base antes de tocar código (`mvn test`, 7 módulos) | `BUILD SUCCESS`; core 229, crypto 50, ai 19, api 34, identity 71, app 23 — `Failures: 0, Errors: 0` |
| `mvn test -pl convocatoria` al cierre de la Tarea 9 | `Tests run: 168, Failures: 0, Errors: 0, Skipped: 0` |
| `mvn clean test` del reactor completo (8 módulos) | `Tests run: 229/50/19/34/71/168/23, Failures: 0, Errors: 0, Skipped: 0` — `BUILD SUCCESS` |

Concurrencia probada contra MongoDB real (Testcontainers, replica set) con `CyclicBarrier`: reintentos reales por `WriteConflict` (`TransientTransactionError`) observados en el log. Incidencia de entorno de test resuelta: recrear colecciones en cada test agotaba los descriptores de `mongod` (`Too many open files` → `WT_PANIC`, leído en el log del contenedor); ahora se crean una vez por JVM y se vacían entre tests.

Decisiones humanas tomadas durante la implementación (2026-10-01): G1 — quitar `MONETARY` en la edición directa borra el ledger en la misma transacción (N2); G2 — `RemoveResponsible` con reemplazo lleva `replacementActingRole`; G3 — una persona tiene como mucho una asignación activa por convocatoria.
*Nota de la consolidación (2026-10-03):* G1–G3 solo constan en este documento de estado. Su confirmación y su registro en `convocatoria-resumen.md` §6 son una decisión humana pendiente (`convocatoria-resumen.md` §0.4, DH-C-4). G3 no figura en ADR-037 §2.4 / Enmienda 1 §4.2.

**C-01 y cierre del flujo de fondos en `convocatoria` (2026-10-02, sin commit).** Especificación: ADR-037 Enmienda 2 (BORRADOR) y ADR-043 (Propuesto); decisiones en `convocatoria-resumen.md` §6.20; plan rev. 3.
- Confirmación (`confirmDonationIntent`): solo `ADMINISTRATOR` de la organización (`requireAdministratorOf`); rechaza las intenciones de pasarela; no toca ledger, registro de comandos ni `Fund`.
- Aplicación (`applyFundsForIntent(intentId)` → `ApplyFundsResult`): consume una intención `CONFIRMED`; barrera = reclamo del comando de sistema `APPLY_FUNDS` (clave conceptual `APPLY_FUNDS:{intentId}`, `_id = {commandType, commandId}`, espacio separado de los comandos de cliente; I1 intacto) antes del incremento del ledger; ante un duplicado devuelve el resultado original (`appliedNow = false`); se une a una transacción externa sin reintento interno y la marca para rollback.
- `FUNDING_REJECTED`: solo por `CampaignFundingLimitExceededException` verificado como permanente; `CLOSE_ON_TARGET + CLOSE` sigue `CONFIRMED` (R4); los fallos transitorios no lo producen.
- Consulta de intenciones `CONFIRMED` sin aplicar (pieza de `convocatoria` de ADR-043), que excluye las de convocatorias `CLOSE_ON_TARGET + CLOSE` mientras R4 no exista (P9, opción a).
- Pruebas de mutación de esta sesión: retirar la barrera, compartir el espacio de claves, marcar el rechazo sin verificar permanencia, quitar la autorización, quitar la exclusión P9, tratar `CLOSE` como permanente o devolver `appliedNow = true` en un duplicado hace fallar los tests correspondientes.

| Verificación (esta sesión) | Resultado (output literal de Surefire) |
|---|---|
| `mvn -o test -pl convocatoria` | `Tests run: 190, Failures: 0, Errors: 0, Skipped: 0` — `BUILD SUCCESS` |

*Nota de la consolidación (2026-10-03):*
- Auditorías posteriores (`auditoria-documental-convocatoria.md`, `auditoria-cierre-final-convocatoria.md`) informan 194 tests en verde.
- En la primera pasada de la consolidación esa cifra no se registró, porque no se habían ejecutado tests y la regla 2.4 exige una ejecución de la sesión que actualiza.
- Quedó confirmada por la ejecución real de la verificación siguiente.
- La especificación citada arriba (Enmienda 2 y ADR-043) sigue en BORRADOR / Propuesto.

**Verificación del 2026-10-03** (ejecución real en la sesión de consolidación documental; sin cambios de código ni de tests: sumas de comprobación de `convocatoria/src` idénticas antes y después):

| Verificación | Resultado (output literal de Surefire) |
|---|---|
| `mvn -o test -pl convocatoria` | `Tests run: 194, Failures: 0, Errors: 0, Skipped: 0` — `BUILD SUCCESS` |

Esta es la cifra vigente del módulo. Las de 168 y 190 se conservan arriba como registro de ejecuciones anteriores. No se ejecutó el reactor completo.

**Bloqueado fuera de `convocatoria`:** orquestador de la aplicación (ledger + génesis + outbox en una transacción), disparo inmediato y scheduler de ADR-043 (`app`): bloqueados por T1 y P8 en `core`. *Actualización 2026-10-07:* el bloqueo de composición por `OrganizationVerificationPort` quedó resuelto — `app` ya depende de `convocatoria` y compone el adaptador de producción (`OrganizationVerificationAdapter`, lee `Organization.verificationStatus` de `identity`); el contexto completo arranca.

**No cubierto por este corte (sigue abierto):** orquestador de la aplicación de fondos y su test de reintento de transacción completa (Enmienda 2 §3.2, ADR-043, §13.3 del plan); T1 en `core`; webhook (P3); efectivo (P5); P1, P2, P4, P6, P7; R3 (solicitud de cambio); R4 (`CLOSE_ON_TARGET` + `CLOSE`); índice de referencia de pago, sin el cual la confirmación manual de `BANK_TRANSFER` no es desplegable.

## 4. Pendiente — Blockchain

*Actualización 2026-10-07 (auditoría):* tres de los pendientes originales ya están implementados en `develop` (commit `793d4b8`) y se retiran de la lista:
- ~~Partición cuando un stream supera `maxEventsPerBatch`~~ — `MongoUnanchoredEventAdapter.claimOrphansAndAssignBatch` reparte el presupuesto entre streams y reclama por `sequence ASC`; un stream grande se parte en batches contiguos (`MongoUnanchoredEventAdapterTest.claimOrphansAndAssignBatch_preservesContiguity`).
- ~~Semántica de `matchedCount==0` en Fase 3~~ — no-op benigno con log (`BlockchainAnchorProducerTest.shouldHandleTransitionReturnsFalseGracefully`).
- ~~Mecanismo que descubre y reintenta batches `COLLECTING` abandonados~~ — `BlockchainAnchorProducer.recoverStaleCollectingBatches()` con `recoveryAttempts` y `crypto.anchor.collecting-recovery.*` (7 tests en `BlockchainAnchorProducerTest`). También se persisten `leafHashes` en el batch (`LegacyBatchLeafHashesUnavailableException` para batches sin ellos).

Siguen abiertos:
- Límite/paginación explícita de `verifyAllAnchored()` — hoy devuelve un `Stream` respaldado por cursor (no carga todo en memoria), sin límite para el llamador.
- Estrategia real de migración de `MerkleBatch` históricos (la excepción de §3.2 es una barrera de seguridad, no una migración).
- Origen del `correlationId` para ejecuciones de scheduler/background.
- Deuda técnica registrada aparte (no bloqueante): el test de integración multi-colección levanta todo el contexto de `app`, incluida configuración de IA (`spring.ai.openai.api-key` simulada) — candidato a acotar con un slice de test más estrecho (`@DataMongoTest` o equivalente).

## 5. Pendiente — resto de Fase 6

Ver §7 de cada ADR para el detalle completo. Resumen de las piezas de mayor severidad, sin repetir lo ya extenso en cada documento:

- **Convocatoria**: idempotencia real de `clearFundsGenesis` ante retry no verificada (bloquea el camino completo del webhook de pago) — máxima severidad de todo Fase 6.
  *Nota de la consolidación (2026-10-03):* la aplicación de fondos ya está protegida dentro de `convocatoria` por la barrera `APPLY_FUNDS` (§3bis; Enmienda 2 §3.3, BORRADOR). Del lado de `core` siguen pendientes:
  - T1 (camino de `clearFundsGenesis` sin reintento interno);
  - P8 (mensaje de outbox de la génesis);
  - la idempotencia de `clearFundsGenesis` en ejecución.

  Ver ADR-037 §7.1 y la Enmienda 2 §6.
- **Identidad**: ADR-038 implementado y fusionado en `develop` (`aebedb2`; detalle en §7 y en ADR-038 §9). Pendiente: emisión de JWT/autenticación HTTP (§2.7), endpoints de plataforma y la deuda técnica de ADR-038 §9.4 (verificada vigente el 2026-10-07).
- **IA**: contradicción C1 `AuditFactsPort`/`CampaignAuditFactsPort` **cerrada** (ADR-040 §7-A, §8): son dos contratos distintos por diseño (donación individual vs. agregado de convocatoria, Interface Segregation Principle). `CampaignAuditFactsPort` no es una interfaz fantasma sino un contrato pendiente de implementar; su implementación (productor en `core`, consumidor en `ai`) queda bloqueada por C2–C5.
- **APIs/Frontend**: ningún hueco propio de severidad alta — hereda los de arriba.
- **Dataset + narrativa de demo**: sin empezar, deliberadamente al final — depende de que el Golden Path funcione de extremo a extremo, lo cual hoy no ocurre (bloqueado por `HumanActor`+P7, entre otros; el modelo de identidad de ADR-038 ya está implementado, falta su exposición HTTP).

## 6. Próximo paso sugerido

*Actualización 2026-10-07 (cierre de decisiones, §0.4):* el orden vigente está en `plan-cierre-fase6-codigo.md` (PROPUESTO), con dos cadenas críticas:
- **dinero:** ~~D-P8~~ (cerrado, opción A) → T1 en `core` → orquestador de ADR-045 en `app` → endpoints de la demo → golden path;
- **narrativa de convocatoria:** D-CAMPAIGN (`campaignRef` en `PhysicalAsset`, enmienda de ADR-029) → implementación en `core` → IA C2–C5 → golden path.

En paralelo: D-SPLIT (saga del hijo), D-JWT, la Enmienda 1 de ADR-039 y las fichas de API. El orden anterior (a)–(e) de esta sección queda sustituido.


Texto original: De los pendientes de mayor severidad, ninguno depende de otro para empezar. Orden por impacto en el Golden Path: (1) resolver la contradicción de puerto en IA, (2) completar pendientes de Convocatoria y core (T1, P8), (3) merge de Identidad a develop y endpoints HTTP.

## 7. Identidad — implementación de ADR-038 (2026-10-01 → 2026-10-04)

Commits, enmiendas, evidencia y deuda técnica: **ADR-038 §9**. Aquí solo el estado y los incidentes de proceso.

### 7.1 Evidencia (output real, ejecutado por el humano)

| Verificación | Resultado |
|---|---|
| `mvn clean test` (reactor completo) sobre `2b2a68a` | `BUILD SUCCESS` — core 229, crypto 50, ai 19, api 34, identity 261, app 27 |
| `BootstrapPlatformAuthorityConcurrencyIntegrationTest`, 10 ejecuciones | 10/10 `Tests run: 2, Failures: 0, Errors: 0` |
| `OrganizationVerificationConcurrencyIntegrationTest`, 5 ejecuciones | 5/5 `Tests run: 7, Failures: 0, Errors: 0` |
| Pruebas de mutación (6B, 7, 8A, 8B) | Cada mutación puso en rojo exactamente los tests previstos (ADR-038 §9.3) |

### 7.2 Incidentes de proceso

- **Tarea 4 (`558a915`): salidas fabricadas.** El reporte del agente presentó como literales salidas de Maven y de git que no lo eran (un ULID con fecha de 2025, líneas de ArchUnit inexistentes, conteos que no sumaban el total, un autor falso). Su apartado de desviaciones decía "ninguna" mientras el diff borraba un comentario y cambiaba sangrías; se corrigió en `6b8a463`. **Regla derivada:** la evidencia de tests la ejecuta y la pega el humano, nunca el agente.
- **Tareas 6A y 8A: tests que no compilaban.** En dos ocasiones, tests rojos del agente usaron una variable local `org`, que tapa los paquetes `org.springframework` y `org.bson`. El compilador del editor dejó en `target/` clases que fallaban al ejecutarse. **Regla derivada:** no usar `org` como nombre de variable y verificar siempre con `mvn clean`.
- **Tarea 7 (`9a68e17`): regresión introducida por el revisor.** El revisor declaró "código muerto" una espera del test de concurrencia del bootstrap a partir del delta de reintentos, sin comprobar qué transacción reintentaba. Sin esa espera, el test falló 7 de 10 veces. La instrumentación mostró que en MongoDB 6.0 el conflicto aparece al confirmar (ADR-038 §9.2, E3), y se corrigió en `0e2440a`. **Regla derivada:** en tests de concurrencia, se instrumenta antes de declarar código muerto, y las aserciones identifican qué hilo falló.
- **Tarea 6B: contexto de Spring con contenedor detenido.** Dos clases con la misma configuración compartían el contexto cacheado de Spring, que apuntaba a un contenedor ya detenido ("Conexión rehusada"). Se corrigió con `@DirtiesContext`.
