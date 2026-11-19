# Planes de la tanda del 2026-11-19 (Fase A: sin código)

**Estado: PROPUESTO.** Espera **una sola aprobación** de Manuel que se pueda citar (regla 3.4). **Ningún código de estas tareas empieza antes.** Fusionar este PR no aprueba nada.

**Encargo:** «Encargo al agente: firmas recibidas y siguiente tanda (2026-11-19)». **Firmas que desbloquean cada plan:** `decisiones-delegadas-2026-10.md`, «Firmas del 2026-11-19» (#192).

**Base verificada:** `develop` en `80af360` (2026-11-19) para `Donaciones`; `PaxFide` en el commit de `PaxFide#72`; `PaxFide_Mv` en `493e665` (`develop`) y en la rama de `PaxFide_Mv#17` (`claude/focused-dirac-8besqk`). Cada `ruta:línea` es de esa base.

## Orden de ejecución propuesto (Fase B)

Sin preguntar entre una y otra, **cada una cuando la anterior esté fusionada**, un PR por tarea, sin apilar ramas:

| # | Tarea | Repositorio | Revisa Carlos (toca `core`) |
|---|---|---|---|
| 1 | Ficha del lote para `ADMINISTRATOR` y `REPRESENTATIVE`: enmienda de ADR-032 → backend → web → capturas por rol | `Donaciones`, `PaxFide` | **Sí** |
| 2 | ADR-059: comprobar `occurredAt ≤ recordedAt` al guardar | `Donaciones` | **Sí** |
| 3 | ADR-053: traer a `develop` solo el límite de intentos | `Donaciones` | No |
| 4 | Tarea 4 de fotos: la copia censurada en el seguimiento (backend; web aparte) | `Donaciones`, `PaxFide` | No |
| 5 | Móvil: fondos y actividad (E6), fotos en despachar y recibir (E7); la copia en el seguimiento del móvil, bloqueada | `PaxFide_Mv` | — |
| 6 | Reentrenar el predictor a `baseline-0.4.0` | `Donaciones` | No (no toca `core`) |

**Paralelas** (otro repositorio o módulo, no dependen de lo anterior): la 5 empieza cuando `PaxFide_Mv#17` esté fusionado; la 6, cuando se apruebe su enmienda de ADR-044.

**Punto de parada resuelto antes de empezar:** ADR-053 **no depende** de ADR-052, ADR-054 ni ADR-055. Comprobado: el texto de ADR-053 no cita ninguno (`git show origin/claude/festive-newton-687sd0:Documentos/ADR-053-limite-de-intentos.md`, búsqueda de «ADR-052», «ADR-054», «ADR-055» y «ADR-056»: 0 coincidencias) y sus tres commits (`e6e8e5e`, `63632d2`, `c1a7a12`) se aplican sobre `develop` con **un solo conflicto, en `Documentos/despliegue-backend-2026-10.md`** (prueba hecha con `git cherry-pick -n`, descartada después). No hace falta parar.

**Hallazgos que cambian el plan o piden una decisión nueva** (los detalle en cada plan):
1. **Plan 5:** la enmienda 7 de ADR-043 cubre la cámara en despachar y recibir, **no** la copia censurada en el seguimiento del móvil. Esa parte necesita **otra enmienda de ADR-043**, que hoy no existe (Q-M-3).
2. **Plan 1:** el servidor falso de la web deja registrar y dividir al `REPRESENTATIVE` como «respaldo» (`canOperate(actor, backup)`, `scripts/fake-backend.js`), pero el **core real no lo permite**: `RoleAuthorizationPolicy.java:21-22` exige `EMPLOYEE` para registrar, dividir, despachar, recibir y entregar. El doble está más abierto que el real; se corrige en el mismo PR de la web (Q-F-4).
3. **Plan 1:** la semilla `demo-seed` solo tiene un administrador que **también** es empleado (`administrador@demo.paxfide.local`; `demo-credenciales-locales.md`): no hay un `ADMINISTRATOR` puro para las capturas (Q-F-5).

---

## Plan 1 — Ficha del lote para `ADMINISTRATOR` y `REPRESENTATIVE` (opción (b))

**La tarea más urgente:** hoy la publicación de la copia censurada no la puede usar un administrador normal. Verificado: `GET /api/v1/physical-assets/{assetRef}` exige `EMPLOYEE` (`PhysicalAssetOperationalQueryService.loadAuthorized`, `core/src/main/java/com/traceability/core/application/query/PhysicalAssetOperationalQueryService.java:105`) y responde 403 a los demás roles de la misma organización.

### Alcance
**Dentro**
1. **Enmienda 1 de ADR-032** (`ADR-032-enmienda-1-lectura-del-activo.md`, PROPUESTA): `GET /physical-assets/{ref}` pasa a admitir `ADMINISTRATOR` y `REPRESENTATIVE`, además de `EMPLOYEE`, **de la misma organización del activo**. Solo lectura. Los comandos no cambian. La plataforma sigue con sus rutas propias (no abre esta). Una nota fechada en la tabla de DD-14 («lecturas con `EMPLOYEE` + frontera de organización») la remite a la enmienda.
2. **Backend:** la lectura del lote (`findOperationalView`) acepta los tres roles de la organización del activo, **sin tocar** `findSplitStatus` (Q-F-2); 403 uniforme para otra organización y para cuentas sin organización (la guardia de organización ya existe: `OrganizationBoundaryPolicy.assertBelongs`).
3. **Web:** quitar del e2e `e2e/fotos-de-los-pasos.spec.ts` la cuenta con dos papeles (`admin-empleado@demo.test`) y probar con un `ADMINISTRATOR` sin `EMPLOYEE` y con un `REPRESENTATIVE`; el servidor falso (`scripts/fake-backend.js`, la ruta `GET /physical-assets/{ref}`, hoy `allowed = … canOperate(actor, backup)`) pasa a imitar el contrato nuevo.
4. **Capturas por rol contra el backend real** con `dev,demo-seed,demo-historico`: `EMPLOYEE`, `ADMINISTRATOR` puro y `REPRESENTATIVE`, y por estado (sin foto, con foto, retirada). Cada captura dice endpoint, commit del backend y de la web y semilla.

**Fuera:** abrir la lista de lotes (`GET /organizations/{id}/physical-assets`) al `REPRESENTATIVE` (hoy `ADMINISTRATOR` o `EMPLOYEE`; `PhysicalAssetOperationalQueryService.listForOrganization`): no se pidió (Q-F-3). Cualquier comando nuevo. La plataforma.

### Archivos
- `Documentos/ADR-032-enmienda-1-lectura-del-activo.md` (nuevo), nota en `ADR-032-autorizacion-comandos-core-matriz.md` y en `decisiones-delegadas-2026-10.md` (DD-14), `api-contract-matrix.md`, `referencia-api-v1.md`.
- `core/.../query/PhysicalAssetOperationalQueryService.java` (`loadAuthorized`, `:96-109`; docs de la clase `:23-28`).
- Tests de `core`: `AssetHttpCommandsIntegrationTest` (`reads_requireTheEmployeeRoleAndTheOrganization`, `:266-275`, que hoy espera `InsufficientRoleException` para `ADMIN_ONLY`: **cambia**), `PhysicalAssetListLastEventAtIntegrationTest` (sin cambio de aserciones).
- `app`: `GoldenPathHttpIntegrationTest`, `P2EndpointsHttpIntegrationTest`, `PublicRoutesInventoryIntegrationTest` (revisar que ninguna dependa del 403 antiguo).
- Web: `scripts/fake-backend.js`, `e2e/fotos-de-los-pasos.spec.ts`, `__tests__/` de la ficha (los tests de `StepPhotosSection` ya cubren los roles en el componente).

### Contratos
Ninguno nuevo: **la misma respuesta de 7 campos** (`OperationalResponse`); cambia solo quién la recibe. Otra organización y cuentas sin organización: el **mismo 403** que hoy (DD-01 y DD-12, ratificadas; sin oráculo de existencia).

### Tests en rojo primero
**Backend (`core`)**
1. `ADMINISTRATOR` de la misma organización lee el lote → 200 con los mismos 7 campos que el `EMPLOYEE` (comparación de campos, no solo el estado).
2. `REPRESENTATIVE` de la misma organización lee el lote → 200.
3. Un `ADMINISTRATOR` y un `REPRESENTATIVE` de **otra** organización → `CrossOrganizationAccessException` (403 uniforme); una cuenta **sin organización** → 403; un activo inexistente → el mismo 403.
4. `never()`: abrir la lectura no abre ningún comando. `ADMINISTRATOR` puro y `REPRESENTATIVE` → `InsufficientRoleException` en `register`, `register-from-donation`, `split`, `dispatch`, `receive` y `deliver` (los seis exigen `EMPLOYEE`, `RoleAuthorizationPolicy.java:21-22`), y **no se escribe ningún evento** (el stream no cambia de versión).
5. `findSplitStatus` **sigue** exigiendo `EMPLOYEE` (Q-F-2): con `ADMINISTRATOR` y con `REPRESENTATIVE` → `InsufficientRoleException` (aserción de que **no** se abrió por error).
6. Un administrador de plataforma (`platformAuthority`, sin organización) → 403 en esta ruta.

**Backend (`app`, HTTP)** 7. `GET /physical-assets/{ref}` con JWT de cada rol: 200, 200, 200, 403, 403 (otra org), 401 (sin JWT). 8. Los **cuerpos** del 403 de «otra organización» y «inexistente» son idénticos byte a byte.

**Web** 9. Un `ADMINISTRATOR` sin `EMPLOYEE` abre la ficha, ve «Pasos y fotos» y el control de publicar (e2e con el servidor falso nuevo); 10. un `REPRESENTATIVE` abre la ficha y ve la copia **sin** control ni casilla (aserción negativa); 11. ninguno de los dos ve los botones «Despachar», «Recibir» ni «Entregar» (aserción negativa; la web ya los oculta con `/me`, `AssetActions.logisticVisible`).

**Mutaciones:** dejar `loadAuthorized` sin comprobar la organización; aceptar cualquier rol (`PLATFORM` o sin roles); abrir los comandos por error; que el 403 de «inexistente» distinga del de «otra organización».

### Riesgos
1. **Exposición de datos:** los 7 campos incluyen `currentCustodianRef` y `currentLocation`; ya los ve cualquier `EMPLOYEE` de la misma organización. No se añade ningún campo.
2. **Cambia un test de seguridad existente** (el de `:266`): se invierte a propósito y queda en el PR con el motivo.
3. **Representante sin lectura de la lista** (Q-F-3): podría abrir una ficha por enlace pero no verla en `/panel/assets`.
4. Toca `core`: **revisa Carlos**.

### Preguntas (con mi recomendación)
- **Q-F-1 — ¿Una enmienda nueva de ADR-032 o una nota fechada?** Recomiendo **enmienda** (cambia la matriz de autorización, no solo la describe).
- **Q-F-2 — ¿También `findSplitStatus`?** Recomiendo **no**: solo el `EMPLOYEE` puede dividir, así que nadie más necesita seguir una división; menos superficie abierta. Se pide aparte si algún día divide otro rol.
- **Q-F-3 — ¿Abrir también la lista de lotes al `REPRESENTATIVE`?** Recomiendo **no ahora**; se pide aparte si hace falta.
- **Q-F-4 — ¿Quitar la cuenta de dos papeles del servidor falso y cerrar el «respaldo» del `REPRESENTATIVE`?** Recomiendo **sí a las dos**: el e2e debe probar lo que ve un administrador normal, y el doble no puede permitir lo que el core real rechaza.
- **Q-F-5 — Administrador puro para las capturas.** La semilla `demo-seed` solo trae `administrador@demo.paxfide.local` con dos papeles. Recomiendo **añadir una cuenta `administrador-solo@demo.paxfide.local`** a la semilla de desarrollo (mismo perfil `dev`, misma contraseña de ejemplo) y actualizar `demo-credenciales-locales.md`; la alternativa (quitarle `EMPLOYEE` al existente) rompe los recorridos de la demo. Es un cambio de la semilla, solo `dev`.

### Definición de hecho
Enmienda de ADR-032 aprobada y citada; tests 1 a 11 en verde con el rojo previo visible y las mutaciones muertas; `scripts/ci-local.sh` completo del backend (con la salida de Surefire por módulo) y del web (Vitest, Check Server Imports, Playwright); capturas por rol con su procedencia; ningún campo nuevo en la respuesta. Un PR de documentos (enmienda), uno de `core`/`app` y uno de la web, en ese orden, **cada uno fusionado antes del siguiente**.

---

## Plan 2 — ADR-059: comprobar `occurredAt ≤ recordedAt` al guardar

**Decisión de Carlos (2026-11-19):** sí, con una excepción con nombre propio y su test. Hoy nada lo comprueba al guardar (ADR-059 D2): `EventTime.callAt` impide el futuro **solo** en la fuente simulada (`SimulatedTimeInFutureException`, `core/.../domain/shared/EventTime.java`).

### Dónde (decisión que justifico)
**En `MongoEventStoreAdapter.append`** (`core/src/main/java/com/traceability/core/infrastructure/persistence/mongo/MongoEventStoreAdapter.java:41-117`), antes de armar el mapa canónico y de calcular el hash, **no en el envelope**. Razones verificadas:
- `recordedAt` **no existe** hasta ese método: se crea ahí (`:66`, `Instant recordedAt = Instant.now()`), por evento. El envelope (`EventEnvelopeFactory`) no lo conoce; comprobar allí exigiría adivinar la hora de guardado.
- Es el **único escritor** del event store, así que ninguna ruta lo esquiva (comandos, sagas, siembra).
- El resto de invariantes del guardado (`SequenceGapException`, `ConcurrencyConflictException`) ya viven ahí.

La comprobación va **antes de insertar nada** (el `insertAll` es una sola operación): si algún evento del lote la incumple, **no se guarda ninguno** del lote.

### Alcance
**Dentro:** excepción `EventOccurredAfterRecordedException` (en `core.application.exception`, junto a `SequenceGapException`), la comprobación, sus tests, una nota fechada en ADR-059 (al fusionar) y en `referencia-api-v1.md` si el error llega a la API.
**Fuera:** cambiar quién fija cada campo (ADR-059 D1, intacto); revisar eventos ya guardados; el perfil `demo-historico` salvo comprobar que sigue pasando.

### Análisis pedido
- **Deriva de reloj entre `EventTime` y la hora del almacén.** `occurredAt` lo fija el agregado en `raiseEvent` y `recordedAt` el adaptador unos milisegundos después, **en la misma JVM y el mismo reloj** (`Instant.now()` en ambos casos, salvo la fuente simulada, que ya no pasa del reloj real). Con el reloj real la desigualdad se cumple por orden de ejecución; solo la rompería un **salto atrás del reloj del sistema** entre los dos instantes (NTP, ajuste manual). Eso es raro y el comando fallido se puede reintentar. **Recomiendo igualdad estricta con `≤` (sin tolerancia)** (Q-O-1); una tolerancia (p. ej. 1 s) esconde fechas en el futuro real, que es justo lo que se quiere impedir.
- **`demo-historico`.** Fecha hechos en el **pasado** (`EventTime.callAt`) y `recordedAt` es la hora real de la siembra: cumple `occurredAt ≤ recordedAt` por construcción, ahora con una comprobación que lo respalda. La comprobación **no** rechaza el pasado. Se añade un test explícito (`DemoHistoricSeederIntegrationTest` ya existe: 10 tests).
- **Eventos ya guardados.** La comprobación es **solo para escrituras nuevas**. `loadStream` no la repite (un evento viejo con `occurredAt > recordedAt`, si existiera, se sigue leyendo y se verifica su hash igual). Se mide una vez, en lectura, cuántos eventos del almacén de desarrollo la incumplen, **solo para informar** (consulta de solo lectura, sin cambios).
- **`occurredAt == null`.** El adaptador ya tolera `null` (`:96`). **Recomiendo** que la comprobación lo **rechace** con la misma excepción... pero solo si hoy ningún evento nuevo puede llevarlo; lo verifico en el primer test rojo (Q-O-2: si hay un camino con `null`, se deja pasar y se anota).

### Archivos
`MongoEventStoreAdapter.java`, `core/.../application/exception/EventOccurredAfterRecordedException.java` (nuevo), `api/.../CoreApiErrorMappings.java` (decidir el mapeo, Q-O-3), `MongoEventStoreAdapterTest.java` (el que ya existe), `ADR-059-occurredat-y-recordedat.md` (nota).

### Contratos
Ninguno de eventos ni de payload; **cambia el comportamiento de `EventStorePort.append`**: puede lanzar la excepción nueva. Los llamadores (`CommandRetryTemplate`, `TransactionalEventPublisher`) la propagan; **no** la reintentan (es un fallo determinista, como `SequenceGapException`).

### Tests en rojo primero (`MongoEventStoreAdapterTest` y un test de integración)
1. Un evento con `occurredAt` posterior a la hora real → `EventOccurredAfterRecordedException` y **no se guarda nada** (aserción `never()`: el stream queda en la versión anterior).
2. Un lote de tres eventos donde solo el **último** incumple → no se guarda **ninguno** de los tres.
3. `occurredAt` igual a `recordedAt` → se guarda (frontera).
4. `occurredAt` en el pasado (1 año, 1 hora) → se guarda; `recordedAt` es la hora real.
5. Con `EventTime.callAt` en el pasado dentro de un comando real → se guarda, y la cadena de hashes verifica.
6. `EventTime.callAt` con futuro sigue lanzando `SimulatedTimeInFutureException` **antes** (la comprobación nueva no la tapa).
7. `DemoHistoricSeederIntegrationTest` sigue en verde sin cambios.
8. El `eventHash` de un evento **no cambia** por esta tarea (mismos bytes que antes para el mismo evento).
9. Concurrencia: un conflicto de versión (`ConcurrencyConflictException`) sigue siendo ese y no la excepción nueva.

**Mutaciones:** quitar la comprobación; comprobar `<` en lugar de `≤` (rompe la frontera, test 3); comprobar después de insertar; comprobar solo el primer evento del lote; usar `Instant.now()` distinto del de `recordedAt`.

### Riesgos
1. **Falso rechazo por salto atrás del reloj** (arriba). Mitigación: el comando es reintentable y el error tiene nombre propio.
2. **Una ruta que fabrique `occurredAt` con otro reloj** (p. ej. una futura integración): la comprobación la detiene; es su propósito.
3. Toca `core`: **revisa Carlos**.

### Preguntas
- **Q-O-1 — ¿Igualdad estricta (`≤`) o con tolerancia?** Recomiendo **estricta**.
- **Q-O-2 — `occurredAt == null`.** Recomiendo rechazarlo si ningún camino actual lo produce; lo compruebo con el primer test y lo digo en el PR.
- **Q-O-3 — Código HTTP si la excepción llega a una petición.** Hoy `CoreApiErrorMappings` mapea `ConcurrencyConflictException` a 409. Recomiendo **no mapearla**: es un fallo del servidor (un 500 con el `ProblemDetail` genérico), porque el cliente no puede corregirlo.

### Definición de hecho
Tests 1 a 9 en verde con el rojo previo visible y las mutaciones muertas; `ci-local.sh` completo del backend con la salida de Surefire por módulo; la cifra de eventos existentes que incumplen (solo informativa) con su procedencia; nota fechada en ADR-059. Un PR de código (`core`, revisa Carlos).

---

## Plan 3 — ADR-053: traer a `develop` solo el límite de intentos

**Firmado — Carlos, 2026-11-19.** El documento y el código están en `claude/festive-newton-687sd0` (no fusionada). No depende de ADR-052, ADR-054 ni ADR-055 (ver arriba).

### Alcance
**Dentro:** los tres commits del límite — `e6e8e5e` (código, ADR-053, despliegue), `63632d2` (test de concurrencia) y `c1a7a12` (evidencia de mutaciones) — aplicados sobre `develop` **con `git cherry-pick`**, **sin** los otros diez de la rama (observabilidad D-01, escaneo T-07, respaldo T-04, Spring Boot T-01, índice de ADR D-06, estado). La línea de estado de ADR-053 pasa a «APROBADO — Carlos, 2026-11-19».
**Añadidos que pide el encargo** (cubrir lo nuevo de las fotos):
1. **La subida de fotos de paso entra en el grupo `media-upload`:** `POST /api/v1/organizations/*/asset-evidence` (hoy `RateLimitGroup.MEDIA_UPLOAD` lista logo, portada y `delivery-evidence`; **no** la ruta de la Tarea 2). Es el hueco real: sin esta línea, la subida por paso queda sin límite.
2. **`/donations/tracking/**`, incluida la ruta de la copia censurada:** el grupo `tracking` ya cubre `*` sobre `/api/v1/donations/tracking/**`; la ruta nueva `…/assets/{assetRef}/evidence-preview` (Tarea 4) **queda dentro**. Se añade un test que lo ancla (no cambia el patrón).
**Fuera:** los demás commits de la rama, cambiar las cuotas, Bucket4j, el contador compartido para varias instancias.

### Archivos
Los 17 de `e6e8e5e` (`api/.../web/ratelimit/*`, sus 5 tests, `app/.../application.yml` de `main` y de `test`, `RateLimitHttpIntegrationTest`, `ADR-053-limite-de-intentos.md`, `despliegue-backend-2026-10.md`) más `RateLimitGroup.java` (la ruta nueva) y `RateLimitFilterTest`.

### Conflicto previsto
Solo `Documentos/despliegue-backend-2026-10.md` (verificado). Se resuelve conservando lo de `develop` y añadiendo las dos líneas del límite (`TRACEABILITY_RATE_LIMIT_TRUSTED_PROXIES=127.0.0.1`).

### Contratos
**Cambia el comportamiento HTTP:** un `429` con `Retry-After` y un `ProblemDetail` fijo, idéntico para todos los grupos, cuando se supera la cuota. Las respuestas de la web y del móvil a un `429` ya son un error genérico; **se comprueba** que ninguna pantalla trata un `429` como éxito (revisión de `deliveryEvidence.ts` y del cliente móvil de subida: `evidenceErrorFor` y `delivery_evidence_api.dart` lo mapean a «Failed»/genérico; si mostrara algo engañoso, se anota).

### Tests en rojo primero
Los de la rama (`SlidingWindowRateLimiterTest`, `ClientIpResolverTest`, `RateLimitFilterTest`, `RateLimitPropertiesTest`, `RateLimitHttpIntegrationTest`) **llegan con el cherry-pick**: su rojo ya existe en la rama; aquí se **vuelven a ejecutar** sobre `develop`. Los nuevos, **rojos antes de añadir la ruta**:
1. `POST /api/v1/organizations/{org}/asset-evidence` cuenta en el grupo `media-upload` (con `RateLimitGroup.of("POST", …)`); superada la cuota → `429` **antes** de verificar el JWT y **antes de leer el archivo**.
2. `GET /api/v1/donations/tracking/assets/{assetRef}/evidence-preview` pertenece al grupo `tracking` (y `GET /donations/tracking` también).
3. `never()`: `GET /physical-assets/{ref}/evidence/{step}` y `…/evidence/redacted` (lecturas autenticadas de miembros) **no** entran en ningún grupo.
4. Una ruta de otro grupo no consume la cuota de `media-upload`.
**Mutaciones:** la ruta nueva con otro método (`PUT`); el patrón sin comodín de organización; las 13 de la rama deben seguir muertas (se re-ejecutan, `mutaciones.py`).

### Riesgos
1. **Cuota baja para la subida por paso:** 30 por 10 min por IP (`media-upload`); un operador que sube fotos al despachar y recibir muchos lotes seguidos puede llegar. Se acepta (el valor se cambia en `application.yml` sin tocar código) pero se avisa en el PR.
2. **Detrás del túnel hay que configurar `TRACEABILITY_RATE_LIMIT_TRUSTED_PROXIES=127.0.0.1`**; sin ella todo el tráfico comparte IP y las cuotas se agotan entre todos. Queda en `despliegue-backend-2026-10.md`.
3. **Varias instancias:** cada una cuenta por su lado (deuda consciente de ADR-053).
4. La rama `festive-newton` **sigue sin fusionar**: al fusionar este cherry-pick, cuando algún día se fusione la rama habrá un conflicto trivial (commits duplicados); se anota en el PR.

### Preguntas
- **Q-L-1 — ¿Cherry-pick o reescribir los commits?** Recomiendo **cherry-pick** (conserva autoría y la evidencia de mutaciones).
- **Q-L-2 — ¿La cuota de `media-upload` (30/10 min) basta para las fotos por paso?** Recomiendo **dejarla** y medirla en la demo; subirla es un cambio de configuración.
- **Q-L-3 — Línea de estado de ADR-053.** Se cambia **solo esa línea** a «APROBADO — Carlos, 2026-11-19» con la nota fechada de este PR; el resto del texto no se toca.

### Definición de hecho
Los cinco archivos de tests del límite y los nuevos en verde sobre `develop`; las 13 mutaciones de la rama re-ejecutadas muertas; `ci-local.sh` completo del backend con Surefire por módulo; la línea de estado de ADR-053 cambiada; `despliegue-backend-2026-10.md` con la variable. Un PR (`api`, `app`, documentos), **sin** `core`.

---

## Plan 4 — Tarea 4 de fotos: la copia censurada en el seguimiento (ADR-021-D Enm. 2)

**Su código no se fusiona antes de que el límite de ADR-053 esté en `develop`** (Plan 3). Norma: ADR-021-D Enmienda 2 (APROBADA — Manuel, por delegación de Carlos, 2026-11-18) y ADR-051 Enm. 2 (tarea 4: `app`, `api`).

### Alcance
**Backend (un PR):**
1. `PublicTransitionDTO` (`api/.../application/dto/PublicTransitionDTO.java`) gana `evidencePreviewUrl?`, **solo** si: (1) existe la copia censurada, (2) está **publicada**, (3) la original **no** está retirada por F7 (ADR-021-D Enm. 2, E5). En cualquier otro caso el campo **no aparece** y no hay un campo que diga «hay copia pero no está publicada».
2. Ruta `GET /api/v1/donations/tracking/assets/{assetRef}/evidence-preview`: autenticada **como el resto del seguimiento** (`trackingCode` en `Authorization: Bearer`, `TrackingCodeAuthFilter`); responde el PNG con `Cache-Control: private, no-store`. Errores: sin código o inválido → 401; código que no cubre el `assetRef` → el **mismo 401** uniforme; cubre el activo pero **sin copia publicada, con la original retirada o sin foto** → **404 con el mismo cuerpo** en los tres casos (E7).
3. **Un puerto nuevo**, `PublishedEvidencePreviewPort` (`core.application.port.out`, implementado en `app` sobre `RedactedEvidenceService`/`Publication`), porque `api` solo puede depender de puertos de `core` (ADR-020): lo declaro aquí, **está en el plan**. Devuelve los bytes de la copia **solo** si las tres condiciones se cumplen, y nada de quien publicó.
4. El límite de ADR-053 ya cubre la ruta (grupo `tracking`).

**Web (otro PR, otro repositorio, después del backend):** la pantalla de seguimiento del donante pide la imagen con `fetch(evidencePreviewUrl, { headers: { Authorization: 'Bearer <trackingCode>' } })`, la pinta con `URL.createObjectURL` y la **revoca al desmontar** (E5b). Nunca el `trackingCode` en la URL.

**Fuera:** el móvil (Plan 5, bloqueado por su propia enmienda); la foto original por ninguna ruta pública.

### Archivos
`api`: `PublicTransitionDTO`, `AssetHistoryMapper`, un controlador nuevo `PublicEvidencePreviewController` junto a `PublicAssetHistoryController`; `core`: puerto en `core.application.port.out` (interfaz; **sin lógica de dominio**); `app`: la implementación y `PublicRoutesInventoryIntegrationTest` (una fila nueva); `referencia-api-v1.md`, `api-contract-matrix.md`. Web: la pantalla de seguimiento (`src/app/…/tracking`, por localizar en el plan de la web) y `scripts/fake-backend.js`.

### Contratos
Aditivo: `evidencePreviewUrl?` nuevo y una ruta nueva; los clientes que ignoran el campo siguen igual (E5). **Nada** de la original, su URL autenticada, `objectKey` ni quien publicó (E6).

### Tests en rojo primero (los de «Definición de hecho» de ADR-021-D Enm. 2)
1. Copia publicada → el paso lleva `evidencePreviewUrl` y la ruta devuelve la copia con el `trackingCode` correcto.
2. Sin publicar, despublicada o retirada por F7 → sin `evidencePreviewUrl` y la misma 404 en los tres casos y en el de foto inexistente (aserción negativa: **cuerpos idénticos byte a byte**); un `trackingCode` que no cubre el activo → el mismo 401 que el historial (aserción negativa: nunca 404).
3. Ninguna respuesta pública contiene la original, su URL, la `objectKey` ni los datos de quien publicó (aserción `never()` sobre el cuerpo y las cabeceras).
4. `evidenceRef` sigue siendo la huella de la original antes y después de publicar.
5. Los pasos de registrar, despachar y recibir con foto llevan su `evidenceRef`; sin foto, `evidenceRegistered: false`; un texto que no sea `sha256:` nunca aparece (Q-21D2-1 (a)).
6. `trackingCode` en la consulta (`?code=…`, `?trackingCode=…`) sin cabecera → 401; con cabecera válida y además en la consulta, la consulta se ignora (aserción negativa: ninguna respuesta ni registro contiene el código).
7. **Web:** la imagen se pide con `fetch` y cabecera, se muestra con `URL.createObjectURL`, se revoca al desmontar (`revokeObjectURL` llamado) y el `src` no contiene el código.
8. **Límite:** superada la cuota de `tracking`, la ruta da 429 (se apoya en el Plan 3).
9. Retirar la original (F7) o despublicar quita `evidencePreviewUrl` y la ruta da 404 (E7).
**Mutaciones:** devolver la copia sin comprobar la publicación; comprobar solo la publicación y no la retirada; 404 con cuerpo distinto en un caso; aceptar el código en la consulta; `evidencePreviewUrl` siempre presente.

### Riesgos
1. **Falsa seguridad de la copia pixelada** (riesgo residual de ADR-051 Enm. 2): por eso la publicación es del administrador, con casilla explícita y registro.
2. **DH-56 / fuerza bruta de `trackingCode`:** se cierra con el límite; **por eso el orden**.
3. **Cabecera en `fetch` desde el navegador:** exige CORS con `Authorization` en esa ruta; se comprueba en el servidor real.
4. Dos repositorios: el backend se fusiona primero; la web no puede mostrar nada hasta entonces.

### Preguntas
- **Q-T4-1 — ¿Dos PR (backend y web) o uno?** Recomiendo **dos**, en ese orden (son repositorios distintos; «un PR por tarea» se cumple por repositorio).
- **Q-T4-2 — ¿Se toca `core`?** Solo la **interfaz** del puerto, sin lógica de dominio. Recomiendo **pedir de todos modos la revisión de Carlos** por cortesía, porque toca `core.application.port.out` y el historial.

### Definición de hecho
Tests 1 a 9 en verde con el rojo previo visible y las mutaciones muertas; `ci-local.sh` completo (backend y web) con Surefire por módulo; prueba contra el backend real con una copia publicada y otra sin publicar (capturas del seguimiento con procedencia). Un PR por repositorio, backend primero. **No se fusiona hasta que el límite (Plan 3) esté en `develop`.**

---

## Plan 5 — Móvil: fondos y actividad (ADR-043 Enm. 6) y fotos en despachar y recibir (Enm. 7)

**Firmadas — Adonis, 2026-11-19.** Repositorio `Toffy22Cj/PaxFide_Mv`. **Empieza cuando `PaxFide_Mv#17` esté fusionado** (decisión: no apilar ramas). **No hay `flutter` ni `dart` instalados en este entorno** (`which flutter dart`: vacío). **No puedo ejecutar `flutter analyze` ni `flutter test` aquí:** los escribo y **Adonis los ejecuta** y pega la salida; el PR lo dice en su primera línea y no se declara hecho hasta tenerla.

### 5a — Fondos y actividad (Enm. 6)
**Alcance:** pantalla «Datos de la organización» con dos paneles de solo lectura: **fondos mensuales** (`GET /organizations/{id}/funds/monthly?months=1..12`) y **actividad** (`GET /organizations/{id}/activity`, primeras páginas, como mucho 100 eventos). Quién la ve: fondos, `ADMINISTRATOR` o `EMPLOYEE`; actividad, esos y `REPRESENTATIVE`; el donante, nada. Selector de gráfico solo para `ADMINISTRATOR` y `EMPLOYEE`, guardado en `flutter_secure_storage` por `accountId` (nunca el correo) y por panel. Gráficos con `CustomPaint`, sin dependencias nuevas. Sin conexión: la última respuesta cifrada y por cuenta, con su fecha.
**Archivos (por localizar con el código de #17):** `lib/features/` (carpeta nueva para la pantalla), el cliente de las dos lecturas, la tarjeta en Inicio, `lib/core/offline/` (dos entradas de caché), `test/`; documentos: `PENDIENTES_ADONIS.md`, `api-contract-matrix.md`, `README`, nota fechada en la Enmienda 2 §1.
**Tests en rojo:** los 6 de la «Definición de hecho» de la Enmienda 6 (visible por rol y **no** visible al donante; selector solo para dos roles; cada gráfico con datos de `demo-historico` y vacío; la elección persiste y un almacenamiento roto no rompe; 403 y sin conexión con estado propio, nunca un gráfico vacío como «sin datos»; `flutter analyze` y `flutter test` completos).

### 5b — Fotos en despachar y recibir (Enm. 7; Tarea 6 de fotos, parte 1)
**Alcance:** botón «Añadir foto (opcional)» en el formulario del paso (cámara con `image_picker`, ya aprobado, o galería; vista previa; quitar); con conexión se sube a `POST /organizations/{id}/asset-evidence` con `step` (`DISPATCHED` o `RECEIVED`) y el comando lleva `evidenceRef`; si la subida falla (sin red, 413, 415, 503), dice por qué y deja **seguir sin foto**; **la cola sin conexión guarda el paso sin foto** (nunca bytes de imagen); un reintento usa el mismo `Command-Id` y el mismo `evidenceRef`.
**Archivos (verificado en `develop` del móvil):** `lib/features/physical_assets/presentation/command_form_sheet.dart`, `field_action_flow.dart`, `lib/features/physical_assets/data/delivery_evidence_api.dart` (ya sube la foto de la entrega; se generaliza o se añade un cliente hermano para `asset-evidence`), `domain/asset_command.dart` y `asset_operations.dart` (campo `evidenceRef?`), `lib/core/media/photo_picker.dart` (existe), `lib/core/offline/outbox_item.dart` (sin bytes), `test/flows/asset_flow_test.dart`, `test/features/physical_assets/asset_command_test.dart`.
**Tests en rojo:** los 5 de la Enmienda 7: sin foto, **no se llama a la subida** (`never()`); con foto y conexión, subida antes y comando con su `evidenceRef`; sin conexión, el paso entra en la cola **sin** foto y **la cola nunca contiene bytes de imagen** (se comprueba en el almacén); subida fallida con mensaje claro y paso completable sin foto; reintento por respuesta perdida con mismo `Command-Id` y mismo `evidenceRef`. Mutaciones: mandar `evidenceRef: null`, subir sin foto, guardar bytes en la cola, subir en el reintento.

### 5c — La copia censurada en el seguimiento del móvil — **BLOQUEADA**
ADR-051 Enm. 2 la incluye en la Tarea 6, pero **la Enmienda 7 de ADR-043 no la cubre** (solo despachar y recibir; dice incluso «el donante no ve estas fotos»). Hace falta **otra enmienda de ADR-043** (la propongo, **PROPUESTA**, en el repositorio del móvil) y la Tarea 4 en `develop`. **No la empiezo** hasta que ambas estén (Q-M-3).

### Riesgos
1. **Sin Flutter aquí:** no puedo demostrar que los tests pasen ni el rojo previo; lo hace Adonis con salida literal. Escribo los tests y reviso el código a mano, pero **no lo afirmo**.
2. **Cola sin conexión:** el riesgo que más importa es guardar bytes de imagen; el test de almacén lo guarda.
3. **Cámara y permisos** en iOS/Android: ya resueltos para la entrega; se reutiliza.
4. 5a depende de que las dos lecturas existan y sus respuestas coincidan con `referencia-api-v1.md`; se prueba con las respuestas reales de `demo-historico`.

### Preguntas
- **Q-M-1 — ¿5a y 5b en un PR o en dos?** Recomiendo **dos** (cada una con su enmienda y su definición de hecho).
- **Q-M-2 — ¿Quién ejecuta `flutter test`?** Adonis, con la salida pegada en el PR; yo no declaro «hecho» sin ella.
- **Q-M-3 — La copia en el seguimiento del móvil.** Recomiendo **dejarla fuera** de esta tanda hasta que exista su enmienda y la Tarea 4; redacto la enmienda como PROPUESTA en el PR de 5b.

### Definición de hecho
5a y 5b: los tests de cada enmienda con el rojo previo (ejecutado por Adonis), mutaciones muertas, `flutter analyze` y `flutter test` completos con su salida literal, documentos del móvil actualizados. 5c: la enmienda firmada y la Tarea 4 en `develop`.

---

## Plan 6 — Reentrenar el predictor a `baseline-0.4.0`

**Aprobado como tarea — Manuel, 2026-11-19; necesita plan y enmienda de ADR-044.** Origen: ADR-044 Enm. 3 (limitación: `baseline-0.3.0` subestima las convocatorias que recaudan de forma lineal porque el generador sintético reparte en U). No toca `core`.

### Alcance
1. **Generador con mezcla de curvas de llegada** (`paxfide-predictor/generate_synthetic_dataset.py`): hoy una sola curva en U (`_arrival_curve`, `:50-54`: `1.6·exp(-x/0.12) + 0.35 + 1.1·exp(-(1-x)/0.08)`, normalizada a media 1; usada en `:79`). Se añaden la **lineal** (constante) y la **concentrada al inicio**, y cada convocatoria sortea una con probabilidades **fijas y escritas** (Q-P-1). Mismo `--seed` por defecto `20261006`; `datasetVersion: synthetic-v2`; el manifiesto registra la mezcla.
2. **Reentrenar** a `baseline-0.4.0` (`train_baseline.py`, `MODEL_VERSION`, `:54`) y exportar el artefacto `app/src/main/resources/predictor/campaign-predictor-baseline-0.4.0.json`. **El `0.3.0` se conserva** en el repositorio (no se borra).
3. **Paridad Python↔Java a 1e-9** (`scripts/predictor/export_baseline_json.py`, `CampaignPredictorModelParityTest`, `TOLERANCE = 1e-9`, `app/src/test/.../prediction/`): nuevos `…-0.4.0-parity.json` y `…-feature-parity.json`.
4. **Comparación de métricas 0.3.0 frente a 0.4.0 sobre el mismo conjunto de validación** (el generado por el mismo script con la **misma semilla de validación** para ambos modelos; publicada en `paxfide-predictor/RESULTADOS-baseline-0.4.0.md`, con las cifras de `0.3.0` recalculadas allí, no copiadas).
5. **Respuestas de `demo-historico` antes y después:** las de `0.3.0` ya están en `evidencia-fase6/paso2-escenario-historico-d4485a6-2026-11-16.md`; se repiten con `0.4.0` en el mismo escenario y se comparan.
6. **Enmienda 4 de ADR-044** (`ADR-044-enmienda-4-mezcla-de-curvas-baseline-0-4-0.md`, **PROPUESTA**): decisión, números, qué no cambia (D2: el componente no escribe en MongoDB). **Va primero**; su aprobación desbloquea el código (regla 3.5).
**Fuera:** datos reales (no existen); cambiar variables o hiperparámetros del modelo sin que lo pida la enmienda; el selector de modelo en tiempo de ejecución (Q-P-3).

### Archivos
`paxfide-predictor/generate_synthetic_dataset.py`, `train_baseline.py`, `scripts/predictor/export_baseline_json.py`, `paxfide-predictor/RESULTADOS-baseline-0.4.0.md` (nuevo), `app/src/main/resources/predictor/campaign-predictor-baseline-0.4.0.json` (nuevo), `CampaignPredictionUseCase.java` (`MODEL_RESOURCE`, `:30`, y la nota de versión), `CampaignPredictorModelParityTest`, `CampaignFeatureBuilderParityTest`, `CampaignPredictionUseCaseTest`, `CampaignPredictionHistoryUseCaseTest`, `CampaignPredictionHttpIntegrationTest`, `app/src/test/resources/predictor/*`, `ADR-044-enmienda-3-…` (nota fechada: la limitación se resuelve en `0.4.0`, **sin reescribir** el texto), el maestro.

### Contratos
La API pública de predicción **no cambia de forma**: cambian los números y `modelVersion`. La advertencia «datos sintéticos» **sigue siendo obligatoria** (`synthetic: true`).

### Tests en rojo primero
**Python (nuevos, `pytest` sobre el generador)**
1. Con la misma semilla, la misma huella de `snapshots.csv` en dos ejecuciones (reproducibilidad), y la huella del generador **antiguo** con la mezcla desactivada sigue siendo `2d235ef5…` (el `0.3.0` es reproducible).
2. La mezcla produce las tres curvas en las proporciones declaradas (tolerancia estadística explícita) y cada curva está normalizada a media 1.
3. Ningún dato tiene `alreadyReachedAtT` inconsistente (mismas guardas que 0.3.0).
**Java**
4. Paridad Python↔Java a 1e-9 para cada fila del archivo de paridad de 0.4.0.
5. Paridad de variables (`CampaignFeatureBuilderParityTest`) intacta.
6. `modelVersion: baseline-0.4.0` en la respuesta; **0.3.0 sigue cargable** y su paridad pasa (aserción de que el artefacto viejo no cambió: huella igual).
7. La advertencia de datos sintéticos sigue en la respuesta (aserción negativa: nunca desaparece).
8. Las guardas de rango (recorte de variables, STRICT, otra moneda, cerrada) se comportan como en 0.3.0 (tests existentes en verde sin cambiar).
**Mutaciones:** cambiar una probabilidad de la mezcla; normalizar mal una curva; exportar un coeficiente redondeado; cargar 0.3.0 en lugar de 0.4.0; quitar la advertencia.

### Riesgos
1. **El nuevo modelo sigue siendo sintético:** puede mejorar para el ritmo lineal y empeorar para otro; **por eso** la comparación sobre el mismo conjunto y las respuestas antes/después; la enmienda no afirma que sea «mejor para la realidad» (no hay datos reales).
2. **Conjunto de validación igual para ambos** o la comparación es injusta (se fija la semilla de validación y se registra su huella).
3. **Cambia números que ve la gente** (predicción en web y móvil): se avisa en el PR y en la nota de la enmienda.
4. Dependencias de Python: **ninguna nueva** (numpy, pandas y scikit-learn ya están en `requirements.txt`).

### Preguntas
- **Q-P-1 — Proporciones de la mezcla.** Recomiendo **U 40 %, lineal 40 %, concentrada al inicio 20 %** (el dato real no existe; la mezcla evita que una sola forma domine). Es una decisión de producto de Manuel; se escribe en la enmienda y en `manifest.json`.
- **Q-P-2 — ¿Se conserva `baseline-0.3.0` como modelo por defecto o pasa `0.4.0`?** Recomiendo **`0.4.0` por defecto**, con `0.3.0` conservado en el repositorio y cargable por una propiedad (`traceability.prediction.model-resource`), sin selector en la API.
- **Q-P-3 — ¿Selector de modelo en la API?** Recomiendo **no**: añade superficie y comparación visible al usuario sin necesidad.
- **Q-P-4 — Dónde se publican las métricas.** Recomiendo `paxfide-predictor/RESULTADOS-baseline-0.4.0.md`, como el de 0.2.0.

### Definición de hecho
Enmienda 4 de ADR-044 aprobada y citada; tests 1 a 8 en verde con el rojo previo y las mutaciones muertas; paridad a 1e-9; tabla de métricas 0.3.0 vs 0.4.0 sobre el mismo conjunto con procedencia (comando, semilla, huella, entorno); respuestas de `demo-historico` antes y después; `ci-local.sh` completo con Surefire por módulo. Un PR de documentos (enmienda) y uno de código, en ese orden.

---

## Qué necesito de ti para empezar (una sola aprobación)

Una respuesta que cite este documento, con tus decisiones sobre las preguntas **Q-F-1 a Q-F-5, Q-O-1 a Q-O-3, Q-L-1 a Q-L-3, Q-T4-1 y Q-T4-2, Q-M-1 a Q-M-3 y Q-P-1 a Q-P-4** (o «con las recomendaciones»). Las que no contestes quedan con mi recomendación. Los **puntos de parada** del encargo siguen en pie: un plan que choque con el código, una dependencia nueva, un puerto que no estaba en el plan, un cambio de ADR que no sea una nota fechada y que ADR-053 dependa de otros ADR no firmados (verificado: no depende).
