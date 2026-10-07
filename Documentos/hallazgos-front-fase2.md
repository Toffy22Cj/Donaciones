# Hallazgos del diseño de frontend web (`paxfide-web`) — explicados

**Estado:** documento de hallazgos, no ADR. No toma decisiones de backend: explica cada problema, por qué lo es, qué bloquea y cómo se sabrá que está resuelto. Las opciones que aparecen son **opciones**, no elecciones.
**Origen:** Fase 2 del frontend (`front-fase2.md`). Algunos hallazgos son nuevos de esta fase y otros son heredados de documentos anteriores; se indica en cada caso.
**Fuentes:** `front-fase2.md`, `front-fase1.md`, `api-contract-matrix.md`, `ADR-041`, `golden-path.md`, `contract-wiring-review.md`, `identity-resumen.md`, `convocatoria-resumen.md`.
**Numeración de ADR:** las referencias usan la numeración de Fase 6 confirmada el 2026-09-28 (ADR-037 Convocatoria, ADR-038 Identidad, ADR-039 Blockchain, ADR-040 IA, ADR-041 APIs/Frontend; ADR-042 frontend web). Los archivos del catálogo que aún conserven la numeración anterior (033–037) se renombran según `plan-correccion-fase5-e-ia.md`.

---

## 0. Cómo leer este documento

### La idea central

Durante el diseño de `paxfide-web` se recorrió cada pantalla preguntando dos cosas:

1. ¿Existe el contrato del endpoint que la pantalla necesita?
2. ¿Puede el usuario **obtener los datos de entrada** que ese endpoint exige?

La primera pregunta casi siempre tiene respuesta "sí": la matriz de contratos está bastante completa en comandos. La segunda es la que falla. Muchos comandos piden un identificador (`organizationId`, `accountId`, `donationRef`) que ninguna lectura permite descubrir. Es como tener una cerradura bien diseñada y ninguna forma de saber qué llave corresponde a qué puerta.

Esto no es un error del frontend. El frontend **no debe** resolverlo inventando endpoints, pidiendo al usuario que teclee identificadores internos ni esquivando respuestas `403`. Por eso cada hallazgo termina en backend.

### Formato de cada hallazgo

- **Qué es:** el problema en una frase.
- **Dónde aparece:** fuente documental concreta.
- **Por qué es un problema:** con un escenario real.
- **Qué bloquea:** pantallas y acciones afectadas.
- **Qué NO hacer:** las tentaciones que el diseño prohíbe.
- **Direcciones posibles:** sin elegir.
- **Dueño:** qué capa o módulo debe resolverlo.
- **Criterio de cierre:** qué evidencia demuestra que está resuelto.

### Severidad

| Nivel | Significado |
|---|---|
| **Crítica** | Puede producir un efecto incorrecto e irreversible (duplicación, actor equivocado, fuga de credencial) |
| **Alta** | Deja sin pantalla posible una parte del recorrido principal |
| **Media** | Degrada un flujo o lo deja incompleto, sin efecto dañino |
| **Verificación** | No se sabe si es un problema; hay que mirar el repositorio |

---

## 1. Resumen

| ID | Nombre corto | Grupo | Severidad | Dueño |
|---|---|---|---|---|
| R11 | Idempotencia por `commandId` en comandos web | Comandos | **Crítica** | PhysicalAsset / Convocatoria |
| R4 | Idempotencia de comandos administrativos | Comandos | **Crítica** | Convocatoria / Identity |
| C2/H1 | `trackingCode` en la URL en web | Seguridad | **Crítica** | Frontend + verificación backend |
| N1 | Contrato "quién soy / capacidades" | Identidad | **Alta** — ficha CONGELADA, pendiente de incorporación normativa (2026-10-05) | Identity |
| R1 | Descubrimiento de organizaciones | Lecturas | **Alta** | Identity |
| R6 | Lectura de miembros de la organización | Lecturas | **Alta** | Identity (+ `app`) |
| R8 | Lectura para `from-donation` | Lecturas | **Alta** | Core / Convocatoria (+ `app`) |
| R10 | Lectura de activo más estrecha que su escritura | Autorización | **Alta** | PhysicalAsset / `api` |
| R9 | Registro de organización sin contrato HTTP | Identidad | **Media** | Identity |
| — | Cuerpo de `register` no especificado | Comandos | **Media** | PhysicalAsset |
| — | Código HTTP de "no encontrada" en `/c` | Contratos | **Media** | Convocatoria / `api` |
| — | `HumanAccount` + P7 (heredado) | Autorización | **Crítica** | Core / Identity |
| — | `PhysicalAssetOperationalReadPort` | Verificación | **Verificación** | PhysicalAsset |
| R7 | Descubrimiento de PhysicalAsset | Lecturas | **Resuelto** | — |

---

## 2. Grupo A — Contratos de comandos

### R11 — Idempotencia por `commandId` en los comandos que ejecuta la web

**Severidad:** Crítica · **Nuevo de esta fase** · **Dueño:** PhysicalAsset (`split`, `register`) y Convocatoria (crear convocatoria)

**Qué es.**
La web ejecuta tres comandos (`split`, registrar activo y crear convocatoria) y no sabemos si el backend los protege contra ejecución duplicada mediante un `commandId`.

**Dónde aparece.**
`front-fase2.md` §6 y §10. La matriz (`api-contract-matrix.md` §2 y §4) define ruta, autenticación y respuesta de estos comandos, pero **no su cuerpo**. No hay forma de saber desde la matriz si aceptan `commandId`.

**Por qué es un problema.**
En Flutter, un comando del que no sabemos si llegó al backend (`AMBIGUOUS`) tiene el Outbox: se guarda, se puede verificar leyendo el estado del activo y se reintenta con el mismo `commandId`. En web decidimos **no** traer el Outbox, porque no hay requisito offline y sería sobreingeniería. Sin él, ante un timeout o un error 5xx la **única** salida segura es reintentar con el mismo `commandId`, y eso solo es seguro si el backend reconoce ese `commandId` y no ejecuta dos veces.

Escenario concreto:

```text
1. Un empleado abre /assets/A (100 kits) y divide 60 / 40.
2. La red del centro de acopio es lenta: la petición tarda y el navegador da timeout.
3. La web muestra: "No pudimos confirmar". El empleado pulsa "Reintentar".

Si el backend es idempotente por commandId:
   → reconoce el segundo envío, no divide de nuevo, responde el mismo resultado. Correcto.

Si NO lo es:
   → puede intentar una segunda división sobre un activo que ya fue dividido,
     o producir un rechazo cuyo significado el usuario no puede interpretar.
   → En el peor caso, eventos duplicados en un Event Store inmutable:
     no se pueden borrar, solo compensar.
```

Para `split` hay un agravante. En Flutter, "Verificar estado" compara `lifecycleStatus`. Pero `PhysicalAssetOperationalReadModel` **excluye** la genealogía (`parentAssetRef`/`rootAssetRef`, ADR-041 §2.2), y los cuatro valores de `lifecycleStatus` no incluyen "dividido". **No existe forma honesta de comprobar desde web si una división ocurrió.** La idempotencia no es una mejora opcional para `split`: es la única protección posible.

**Qué bloquea.**
El despliegue de `split` en `/assets/:assetRef`, de "Registrar activo" en `PanelHome` y de "Crear convocatoria" en `/panel/campaigns`. Por G-W1, mientras R11 no esté resuelto, esas superficies **no existen** en producción: la ruta o acción responde 404, no "próximamente".

**Qué NO hacer.**
- Reintentar automáticamente al detectar un timeout.
- Tratar un 5xx como "falló, se puede repetir": un 5xx no demuestra que no se ejecutó (P-W1a).
- Generar un `commandId` nuevo en cada reintento: eso anula la idempotencia.
- Confiar en "deshabilitar el botón" como protección: es UX, no idempotencia (ADR-041 §2.6).
- Desplegar `split` "de todos modos" con un mensaje de advertencia.

**Direcciones posibles (no elegidas).**
- Que cada uno de los tres comandos acepte `commandId` en su contrato y el backend registre los ya procesados, devolviendo el mismo resultado ante un duplicado. Es el patrón que ya existe en `clearFundsGenesis(…, commandId, …)`.
- Para `PhysicalAsset`, verificar si el diseño de Fase 1 ya lo cubre. Los comandos se diseñaron con `commandId` como clave de idempotencia; falta confirmar que la implementación y la capa HTTP lo exponen.

**Criterio de cierre.**
Para cada uno de los tres comandos:
1. El contrato en la matriz incluye `commandId` en el cuerpo.
2. Hay un test de integración que envía dos veces el mismo `commandId` y verifica que **el efecto ocurre una sola vez** (un solo evento en el Event Store, o un solo documento). No basta con que la segunda respuesta sea 200: hay que comprobar el resultado final (regla 2.5).
3. Output literal de Surefire del módulo completo.

---

### R4 — Idempotencia de comandos administrativos

**Severidad:** Crítica · **Nuevo de esta fase** (se solapa con R11 en crear convocatoria) · **Dueño:** Convocatoria (`campaigns`, `employees`) e Identity (`verify`)

**Qué es.**
Los comandos del panel administrativo no tienen definida qué pasa si llegan dos veces.

**Dónde aparece.**
`front-fase2.md` §6. ADR-041 §7-A.4 solo define idempotencia para la creación de `DonationIntent`. Para `POST /organizations/{id}/campaigns`, `POST /campaigns/{campaignRef}/employees` y `POST /platform/organizations/{id}/verify` no hay nada.

**Por qué es un problema.**
Una petición llega dos veces por causas normales: doble clic, reintento de red del navegador, un usuario que vuelve atrás y reenvía el formulario, o dos pestañas abiertas (con W2 cada pestaña es independiente).

Escenario concreto:

```text
1. Una administradora crea la convocatoria "Emergencia invernal 2026".
2. La primera petición tarda; ella pulsa otra vez desde otra pestaña.
3. Resultado posible: DOS convocatorias con el mismo título,
   cada una con su propio publicCode.
4. La fundación imprime el QR de una; los donantes que llegan por la otra
   donan a una convocatoria que nadie gestiona.
5. El dinero queda repartido en dos CampaignFundingLedger.
   La meta de ninguna de las dos refleja la realidad.
```

Es distinto de R11. R11 trata de "¿puedo reintentar con seguridad después de un fallo ambiguo?". R4 trata de "¿qué ocurre con un duplicado, aunque no haya ningún fallo?". Crear convocatoria sufre ambos.

Para `employees`, ADR-037 ya protege el caso concreto "un empleado en dos convocatorias activas" con un índice único parcial (`EmployeeAlreadyAssignedException`). Pero eso es una **invariante de dominio**, no idempotencia. Ante un duplicado exacto, el segundo intento recibe un 409 que la web mostraría como error, aunque la operación del usuario **sí** tuvo éxito.

`verify` hereda además un hueco de ADR-038: qué pasa si se verifica una organización que ya está `VERIFIED`.

**Qué bloquea.**
Crear convocatoria y asignar empleado en `/panel/campaigns`. Verificar organización ya está fuera de v1 por R1.

**Qué NO hacer.**
- Introducir deduplicación HTTP genérica en `api`: ADR-041 §2.6 la descarta porque crearía una segunda semántica de idempotencia compitiendo con la del comando.
- Deducir en el frontend que "un 409 después de un timeout significa éxito": sería inventar una semántica que el backend no ha declarado.

**Direcciones posibles (no elegidas).**
- `commandId` por comando con registro de procesados (mismo mecanismo que R11).
- Una clave natural de unicidad en el dominio (por ejemplo, título + organización), que es una decisión de negocio que no le toca al frontend.
- Para `employees`: distinguir en la respuesta "ya estaba asignado **a esta misma** convocatoria" (duplicado benigno) de "está asignado a **otra**" (conflicto real).

**Criterio de cierre.**
Para cada comando: una semántica de duplicado documentada en la matriz (o en su ADR de origen) y un test que envía el mismo comando dos veces y verifica el estado final (una sola convocatoria, una sola asignación).

---

### R10 — La lectura del activo es más estrecha que su escritura

**Severidad:** Alta · **Nuevo de esta fase** · **Dueño:** PhysicalAsset / `api`

**Qué es.**
Un `REPRESENTATIVE` puede ejecutar `split` y `register` sobre un activo, pero no puede leer ese activo.

**Dónde aparece.**
`api-contract-matrix.md`:
- §4: `register` y `split` son `JWT + EMPLOYEE/REPRESENTATIVE backup`.
- §4b: `GET /physical-assets/{assetRef}` es `JWT + EMPLOYEE + OrganizationBoundaryPolicy`. **No incluye a `REPRESENTATIVE`.**

La capacidad de respaldo está en la enmienda a ADR-032 y en `convocatoria-resumen.md`: el `REPRESENTATIVE` actúa como respaldo en `REGISTER_PHYSICAL_ASSET` y `SPLIT_PHYSICAL_ASSET` cuando no hay `EMPLOYEE` asignado.

**Por qué es un problema.**
Escenario concreto: una fundación pequeña que todavía no tiene empleados asignados a la convocatoria.

```text
1. La representante legal (REPRESENTATIVE) actúa como respaldo.
2. Escanea el QR de una caja: se abre /assets/A.
3. La web pide GET /physical-assets/A → 403 (no es EMPLOYEE).
4. Nunca ve la pantalla del activo, así que nunca llega al botón "Dividir",
   aunque el backend SÍ le permitiría ejecutar el split.

Igual con register:
1. Registra un activo nuevo → éxito → la web navega a /assets/{nuevo}.
2. → 403. No puede ver el activo que acaba de crear.
```

La capacidad de respaldo existe en el dominio pero es **inalcanzable** desde cualquier interfaz que siga el patrón "leer y luego actuar". Ni la web ni el móvil pueden ejercerla.

**Qué bloquea.**
Todo el flujo de `REPRESENTATIVE` como respaldo en web. Mientras no se resuelva, `front-fase2.md` no afirma que ese flujo esté soportado.

**Qué NO hacer.**
- Mostrar el botón "Dividir" aunque la lectura dé 403, pidiendo al usuario los datos a mano.
- Guardar en el cliente los datos del activo recién registrado para "no tener que leerlo": sería duplicar una fuente de verdad y esquivar una decisión de autorización.
- Tratar el 403 como si fuera un error de red.

**Direcciones posibles (no elegidas).**
- Ampliar la autorización de lectura a `REPRESENTATIVE` en las mismas condiciones en que tiene la capacidad de respaldo de escritura (sin `EMPLOYEE` activo en la convocatoria). Mantiene la simetría "si puedes operar, puedes ver aquello sobre lo que operas".
- Decidir que la capacidad de respaldo solo se ejerce en móvil o por otra vía. Sería una decisión de producto explícita, que cambiaría lo que hoy dice la matriz.

**Criterio de cierre.**
La fila de `GET /physical-assets/{assetRef}` en la matriz es coherente con las de `register`/`split`, y hay un test de autorización positivo (un `REPRESENTATIVE` en situación de respaldo lee el activo) y uno negativo (un `REPRESENTATIVE` fuera de esa situación recibe 403).

---

### Cuerpo de `register` no especificado (anotado, sin número)

**Severidad:** Media · **Nuevo de esta fase** · **Dueño:** PhysicalAsset

**Qué es.**
No sabemos qué datos pide `POST /physical-assets/register`.

**Dónde aparece.**
`api-contract-matrix.md` §4 dice "ídem" en la respuesta (`{assetRef, status, donationRef, campaignRef}`) y no describe el cuerpo de la petición.

**Por qué es un problema.**
Sin cuerpo no se puede diseñar el formulario. Hay además una sospecha, no confirmada: si el registro exige `campaignRef`, aparece otro hueco de lectura. El empleado no tiene forma de consultar a qué convocatoria está asignado, porque `GET /organizations/{id}/campaigns` es solo para `ADMINISTRATOR` y no hay lectura de "mi asignación". No se numera porque puede que el backend derive la convocatoria de la asignación activa del empleado (ADR-037 garantiza que es única), y entonces el problema no existiría.

**Qué bloquea.**
El formulario de "Registrar activo" en `PanelHome`.

**Criterio de cierre.**
Cuerpo del request documentado en la matriz. Si incluye `campaignRef`, se abre formalmente un hallazgo de lectura (sería R12).

---

## 3. Grupo B — Lecturas de descubrimiento

Estos tres forman un paquete, porque tienen la misma causa: la matriz se diseñó comando por comando, y **faltan las consultas que permiten encontrar el recurso sobre el que opera cada comando**. Conviene resolverlos juntos y con el mismo criterio de perímetro.

### R1 — El Platform Administrator no puede descubrir organizaciones

**Severidad:** Alta · **Nuevo de esta fase** · **Dueño:** Identity

**Qué es.**
El administrador de plataforma puede verificar, rechazar o pedir información a una organización, pero no puede listar las organizaciones pendientes.

**Dónde aparece.**
- `api-contract-matrix.md` §1: `verify`/`reject`/`request-information` en DISEÑO CERRADO. Ninguna lectura.
- `identity-resumen.md` §7: las lecturas cross-organización del dashboard de plataforma quedan **deliberadamente fuera**, porque rompen una frontera hoy absoluta (`OrganizationBoundaryPolicy`/`CrossOrganizationAccessException`) y necesitan auditoría reforzada.

**Por qué es un problema.**
Todos los comandos exigen `/platform/organizations/{id}/...`. ¿De dónde saca el administrador ese `{id}`? De ninguna parte.

```text
Platform Administrator
        ↓
¿qué organizaciones esperan verificación?
        ↓
no existe lectura
        ↓
no hay pantalla implementable
```

Es el **paso 1 del Golden Path** (`golden-path.md` §2): "Platform Administrator verifica la Organización". Hoy ese paso no tiene superficie web. Para la demo tendrá que ejecutarse por otra vía, como el bootstrap del primer administrador.

Por qué no es trivial: no basta con "añadir un GET". Es la primera lectura que cruza organizaciones. Identity decidió, con razón, no abrir esa frontera sin diseñar su auditoría: quién consultó qué organización, cuándo y por qué.

**Qué bloquea.**
Todo el subárbol `/panel/platform/**`, que queda fuera de web v1 (T6).

**Qué NO hacer.**
- Crear un `GET /organizations` "rápido" sin el tratamiento que Identity §7 exige.
- Pedir al administrador que pegue un `organizationId` recibido por correo.
- Reutilizar el endpoint de organización de un `ADMINISTRATOR` de organización con otra autorización.

**Direcciones posibles (no elegidas).**
- Una lectura específica y mínima de "organizaciones en `PENDING_VERIFICATION`", con solo los campos necesarios para decidir la verificación, bajo `platformAuthority=ADMINISTRATOR` y con cada consulta registrada en el Audit Log.
- Abordarlo dentro del diseño completo del dashboard de plataforma que Identity §7 ya lista como pendiente.

**Criterio de cierre.**
Contrato en la matriz con autorización, perímetro de campos y paginación; tests de autorización positiva y negativa; test de que la consulta queda auditada.

---

### R6 — Asignar empleado exige un `accountId` que nadie puede consultar

**Severidad:** Alta · **Nuevo de esta fase** · **Dueño:** Identity, con composición en `app`

**Qué es.**
Para asignar un empleado a una convocatoria hay que enviar su `accountId`, pero no existe ninguna lectura que liste los miembros de la organización.

**Dónde aparece.**
- `api-contract-matrix.md` §2: `POST /campaigns/{campaignRef}/employees` → `{campaignRef, accountId, status}`.
- No hay contrato HTTP de lectura de miembros.
- `contract-wiring-review.md` §P2 **propone** un puerto interno `OrganizationMembersReadPort.findMembers(organizationId)` para otra necesidad: rellenar `responsables{accountId, fullName}` dentro de `ConvocatoriaAdminReadModel`.

**Por qué es un problema.**
El `accountId` es un ULID interno, algo como `01J8Z…`. Escenario concreto:

```text
1. La administradora quiere asignar a Juan a la convocatoria.
2. El formulario le pide el accountId de Juan.
3. Ella no lo conoce. Juan tampoco. No aparece en ninguna pantalla.
4. Única salida: que alguien lo busque en la base de datos.
```

Pedirle a un usuario que teclee un identificador interno es un defecto de diseño y un vector de errores: asignar a la persona equivocada con un carácter mal copiado.

Sobre `OrganizationMembersReadPort`: es un **puerto interno** propuesto para que `app` componga el `ReadModel` administrativo sin que `convocatoria` importe `identity`. No es un contrato HTTP y fue pensado para otra necesidad. Puede ser la base de la solución, pero no es la solución. `front-fase2.md` lo anota así a propósito.

**Qué bloquea.**
La acción "Asignar empleado" en `/panel/campaigns`.

**Qué NO hacer.**
- Un campo de texto libre para el `accountId`.
- Extraer los empleados de los `responsables` de otras convocatorias: eso solo muestra a quienes ya están asignados, justo los que no se pueden asignar a otra convocatoria activa (ADR-037), y además saca datos del perímetro para el que se diseñaron.
- Ampliar `IdentityPrincipalPort`: `contract-wiring-review.md` §P2 exige que conserve una sola responsabilidad.

**Direcciones posibles (no elegidas).**
- Un contrato HTTP de lectura de miembros de **la propia** organización (`ADMINISTRATOR` + `OrganizationBoundaryPolicy`), apoyado en el puerto de §P2, con perímetro mínimo (`accountId`, `fullName`, roles).
- Una lectura aún más específica: "empleados asignables", es decir, sin asignación activa.

**Criterio de cierre.**
Contrato en la matriz con perímetro explícito; test de que un `ADMINISTRATOR` de otra organización recibe 403 y de que el perímetro no expone campos adicionales (email, estado de cuenta).

---

### R8 — `from-donation` no puede descubrir la donación

**Severidad:** Alta · **Nuevo de esta fase** · **Dueño:** Core / Convocatoria, con composición en `app`

**Qué es.**
Registrar un activo físico a partir de una donación exige identificar la donación, y no existe una lectura operacional para hacerlo.

**Dónde aparece.**
- `api-contract-matrix.md` §4: `POST /physical-assets/from-donation` responde `{assetRef, status, donationRef, campaignRef}`. Además está BLOQUEADO por `HumanAccount` + P7.
- `golden-path.md` §2, paso 4: "Transmutación a especie", el `EMPLOYEE` asignado ejecuta `RegisterPhysicalAssetFromDonation`.

**Por qué es un problema.**
Es el paso donde el dinero se convierte en ayuda física. El empleado necesita ver algo como "donaciones confirmadas de mi convocatoria, pendientes de convertirse en especie" para elegir cuál transformar. Esa lectura no existe.

La solución obvia sería reutilizar `DonationReadModel` o `DonationProjection`, que ya existen desde Fase 3. **No se debe.** Se diseñaron para otro consumidor: el donante, que consulta **su** donación con su credencial de tracking. ADR-041 §2.2 establece que un `ReadModel` es una **frontera**, no un espejo de lo que hay en Mongo, y que estar autenticado no amplía el perímetro. Una lectura para empleados tiene otro consumidor, otra autorización y otro perímetro: el empleado necesita el importe y la convocatoria, pero probablemente no la referencia del donante. Hay que diseñarla alrededor de la operación `from-donation`, no heredarla.

**Qué bloquea.**
`from-donation`, que queda fuera de web v1. Aunque se resuelva R8, sigue bloqueado por `HumanAccount` + P7.

**Qué NO hacer.**
- Reutilizar `DonationReadModel` con autorización JWT.
- Una "mega-consulta de todas las donaciones de la organización porque algún día servirá".
- Pedir al empleado que teclee un `donationRef`.

**Direcciones posibles (no elegidas).**
- Una lectura específica, por convocatoria asignada, de donaciones confirmadas aún no transformadas, con un perímetro diseñado para esa decisión y nada más.

**Criterio de cierre.**
Contrato en la matriz con perímetro justificado campo por campo; test de que no expone `donorRef` ni datos que el empleado no necesita; test de frontera de organización y de convocatoria.

---

### R7 — Descubrimiento de PhysicalAsset (RESUELTO)

**Qué era.**
`split` necesita un `assetRef`, y la única lectura (`GET /physical-assets/{assetRef}`) exige ya conocerlo.

**Cómo se resolvió.**
Sin endpoint nuevo. El QR del activo contiene la URL canónica `/assets/{assetRef}` (matriz §4b), y la web sirve esa ruta (W1-bis):

```text
QR del activo → /assets/{assetRef} → GET /physical-assets/{assetRef} → AssetPage → split
```

El QR **es** el mecanismo de descubrimiento, ya aprobado en el contrato. Una búsqueda o listado de activos queda fuera de alcance hasta que se demuestre que hace falta. Se incluye aquí para que nadie lo reabra creyendo que sigue pendiente.

---

## 4. Grupo C — Identidad

### N1 — La interfaz no sabe quién es el usuario ni qué puede hacer

**Severidad:** Alta · **Heredado de `front-fase1.md` §16, agravado en web** · **Dueño:** Identity

**Estado (2026-10-05): ficha CONGELADA, pendiente de incorporación normativa.**
- Contrato: `claude/ficha-N1-quien-soy.md` — `GET /api/v1/me`, respuesta con exactamente `accountId`, `organizationId`, `roles` y `platformAuthority` (nulos omitidos salvo `roles`), `Cache-Control: no-store`, 401 sin JWT válido o con cuenta `INACTIVE`. Respuestas Q-N1-1 a Q-N1-4 de Carlos, 2026-10-05.
- Uso en el frontend: `claude/delta-front-fase2-N1.md`, **APROBADO** el 2026-10-05 (D-N1-1 a D-N1-4).
- **No es normativo** hasta que se apruebe la Enmienda 1 de ADR-041 (prefijo, `ProblemDetail` y 401/403 son [DHR] en borrador).
- **No está implementado:** depende de `feat/identity-adr-038` en `develop` del backend y del endpoint en `api`. Hasta entonces `/panel` y `/panel/campaigns` siguen aprobado-bloqueadas y responden 404 (D5 / G-W1).
- El criterio de cierre de abajo sigue abierto: contrato en la matriz y tests en backend.

**Qué es.**
Tras el login, el cliente no sabe a qué organización pertenece el usuario ni qué roles tiene.

**Dónde aparece.**
- `identity-resumen.md` y `front-fase1.md` §3: el JWT es deliberadamente mínimo (`sub = accountId`, `iat`, `exp`, firma). **Nunca** lleva `organizationId`, roles ni `platformAuthority`.
- El `AuthorizationPrincipal` se resuelve en backend en cada petición y el cliente nunca lo ve.
- No existe endpoint "quién soy / capacidades".

**Por qué es un problema.**
La decisión del JWT mínimo es correcta: si los roles viajaran en el token, un cambio de rol no tendría efecto hasta que el token expirara (`api-contract-matrix.md`, regla 5). Pero deja a la interfaz ciega. En web tiene dos consecuencias:

1. **No sabe qué panel mostrar.** Con W3 hay hasta cuatro superficies posibles: plataforma, organización, empleado y respaldo de representante. Por eso `PanelHome` es provisional: muestra todas las entradas y cada una responde con su 403 como estado de pantalla. Funciona, pero es una interfaz de "prueba y error".
2. **No sabe su propio `organizationId`, y lo necesita.** `GET /organizations/{organizationId}/campaigns` lo exige en el path. Según `identity-resumen.md` §1, una cuenta pertenece como mucho a una organización, así que el dato existe y es único, pero el cliente no tiene forma de obtenerlo. **Resultado: `/panel/campaigns` no puede ni siquiera hacer su primera petición.**

**Qué bloquea.**
`/panel/campaigns` completo (listado, crear, asignar). `PanelHome` queda en modo provisional.

**Qué NO hacer.**
- Meter `organizationId` o roles en el JWT: rompería la regla de efecto inmediato de cambios de rol.
- Decodificar el JWT en el cliente buscando datos que no contiene.
- Tratar la respuesta de N1, cuando exista, como autorización: sería solo para **representar** la interfaz. La autorización sigue siendo backend en cada petición.

**Direcciones posibles (no elegidas).**
- Un endpoint de lectura del principal propio (organización, roles efectivos, `platformAuthority`), resuelto en cada llamada y nunca cacheado como fuente de verdad.
- Alternativa parcial solo para `organizationId`: endpoints implícitos del tipo "convocatorias de mi organización", que eliminan la necesidad del id en el path (T5). Sería un cambio de contrato de backend.

**Criterio de cierre.**
Contrato en la matriz. Test de que un cambio de rol se refleja en la siguiente llamada sin nuevo login. Documentar explícitamente que no sustituye la autorización de cada endpoint.

---

### R9 — Registro de organización sin contrato HTTP

**Severidad:** Media · **Nuevo de esta fase** · **Dueño:** Identity

**Qué es.**
No hay vía HTTP definida en la matriz para que una fundación se registre en la plataforma.

**Dónde aparece.**
`golden-path.md` §1 lo trata como **precondición**: "1 Organization registrada, `PENDING_VERIFICATION`". La matriz no contiene ningún endpoint para ello. `front-fase1.md` §16 registra además que no existe vía implementable de creación de cuentas en general.

**Precisión importante.**
No se afirma que el registro de organización no exista en el **dominio**. El aggregate `Organization` y su creación (`CreateOrganization`) existen desde Fase 4. Lo que falta es la **vía HTTP** y el flujo de producto: quién la inicia, con qué datos y cómo se crea la cuenta del representante inicial.

**Por qué es un problema.**
Es el primer paso de cualquier fundación real. Sin él, todas las organizaciones se crean por fuera del producto (scripts o semillas de datos). Para la demo académica es aceptable como precondición; para un sistema que se presenta como servicio, no.

**Qué bloquea.**
No bloquea ninguna pantalla de v1, porque el registro nunca entró en el árbol. Condiciona el origen de todos los datos con los que trabaja el panel.

**Criterio de cierre.**
Contrato en la matriz, junto con el flujo de creación de la cuenta del `REPRESENTATIVE` inicial, que hoy tampoco tiene contrato.

---

## 5. Grupo D — Seguridad, heredados y verificaciones

### C2 / H1 — `trackingCode` en la URL del navegador

**Severidad:** Crítica · **H1 heredado de `front-fase1.md` §16, agravado en web** · **Dueño:** frontend (decisión) + backend (verificación)

**Qué es.**
El `trackingCode` es una credencial: quien lo tiene, ve la donación. En web viaja en la URL `/tracking/{trackingCode}`.

**Dónde aparece.**
- ADR-021-A/B: HMAC con validez embebida de 365 días por defecto.
- Matriz §4b: el QR de tracking contiene la URL `/tracking/{trackingCode}`.
- ADR-041 §2.7: tratar el código como secreto; no en logs, no en analítica, no en URLs externas.

**Por qué es un problema.**
En Flutter, H1 era una pregunta acotada: ¿se persiste la ruta en el estado de navegación del dispositivo? En un navegador, una URL con una credencial se filtra por canales que la aplicación no controla:

| Canal | Qué ocurre |
|---|---|
| Historial del navegador | Queda guardada; cualquiera con acceso al equipo la ve |
| Logs del servidor web y del hosting | La petición de la página registra el path completo |
| Cabecera `Referer` | Si la página enlaza a otro sitio, el navegador puede enviarle la URL |
| Analítica | Las herramientas registran las URLs visitadas por defecto |
| Sincronización del navegador | El historial puede replicarse a otros dispositivos |

Hay además una **discrepancia sin resolver**: la matriz §5 y `contract-wiring-review.md` dicen `GET /tracking/{trackingCode}` (código en el path de la API), mientras que la Tarea 3.4 de Fase 3 implementó el filtro con el código en `Authorization: Bearer`. No se puede diseñar el cliente sin saber cuál está vigente.

**Qué bloquea.**
`/tracking/:trackingCode` en web, que responde 404 hasta resolverse (G-W1). La propuesta C2 (solo cliente, `noindex`, sin OG) **reduce** la exposición pero **no la elimina**: el path sigue llegando al servidor web.

**Qué NO hacer.**
- Desplegar `/tracking` en web con la sensación de que `noindex` basta.
- Añadir JWT al tracking "por seguridad": cambia la semántica del contrato (ADR-041 §2.2 y §4).

**Direcciones posibles (no elegidas).**
- Prohibir que el código permanezca en la URL tras cargar la página (reemplazar la entrada del historial).
- Que la URL del QR lleve el código en un fragmento (`#`), que el navegador no envía al servidor. Implica cambiar la URL canónica decidida en la matriz §4b.
- Aceptar el riesgo documentándolo como tal, con su justificación.

**Criterio de cierre.**
(1) Contrato vigente verificado contra el repositorio y la matriz corregida si difiere. (2) H1 decidido explícitamente. (3) Test en navegador real de que el código no aparece en las peticiones al servidor web ni en el `Referer` de los enlaces salientes.

---

### `HumanAccount` + P7 — Comandos de PhysicalAsset sin autorización integrada

**Severidad:** Crítica · **Heredado de `golden-path.md` §5.1–5.2** · **Dueño:** Core / Identity

**Qué es.**
Dos bloqueos independientes. Resolver uno no resuelve el otro.

1. **`HumanAccount` no está implementado.** Es la forma de registrar en cada evento quién actuó (`accountId + organizationId + roles efectivos`). Sin él, `REGISTER_PHYSICAL_ASSET_FROM_DONATION` está bloqueado para todos los roles (ADR-031).
2. **P7 no está conectado.** Verificado por inspección de código en `golden-path.md` §5.2: ninguno de los comandos de `PhysicalAssetCommandService` invoca `RoleAuthorizationPolicy` ni `OrganizationBoundaryPolicy`. `deliverAsset` se ejecuta sin ningún chequeo de autorización.

**Por qué es un problema.**
Si se expusieran estos comandos por HTTP tal cual, cualquier cuenta autenticada podría operar sobre activos de **cualquier** organización. En un Event Store inmutable, esas operaciones no se pueden borrar. El frontend no puede mitigarlo: ocultar un botón no es control de acceso.

**Qué bloquea.**
`split` y registrar activo (además de R11), y `from-donation` (además de R8).

**Criterio de cierre.**
El indicado en `golden-path.md` §5.2: la cadena `OrganizationBoundaryPolicy → RoleAuthorizationPolicy → (respaldo ADR-032) → Aggregate` integrada en cada comando, con tests de autorización positiva **y de rechazo**.

**Nota de vigencia.**
El propio `golden-path.md` advierte que Fase 5 avanza en paralelo. Verificar contra el repositorio antes de tratarlo como pendiente.

---

### Código HTTP de "no encontrada" en `/c/:publicCode`

**Severidad:** Media · **Nuevo de esta fase** · **Dueño:** Convocatoria / `api`

**Qué es.**
Cuando alguien abre `/c/ABC123` y ese código no existe, la web muestra "Convocatoria no encontrada" como estado de pantalla (P-W3). Como `/c` se renderiza en servidor, la respuesta HTTP debe llevar un código coherente, y no sabemos cuál devuelve el backend.

**Por qué es un problema.**
Si la página "no encontrada" se sirve con 200, un buscador o un servicio de vista previa la trata como contenido válido. `noindex` mitiga lo primero, pero el código HTTP sigue siendo parte del contrato. ADR-041 §2.5 **propone** 404 para "no encontrado", y lo marca como propuesta no confirmada por ninguna fuente.

**Qué NO hacer.**
Fijar 404 en el frontend "porque es lo normal". Si el backend distingue, por ejemplo, "no existe" de "existía y se cerró", el frontend necesita saberlo antes.

**Criterio de cierre.**
Mapeo de excepciones a HTTP de Convocatoria confirmado en la matriz o en ADR-037/041.

---

### `PhysicalAssetOperationalReadPort` — por verificar

**Severidad:** Verificación · **Dueño:** PhysicalAsset

**Qué es.**
El contrato HTTP `GET /physical-assets/{assetRef}` y su `PhysicalAssetOperationalReadModel` están definidos (matriz §4b), pero `contract-wiring-review.md` §P4 registró que **no existían el puerto ni su adaptador**, y que no debe reutilizarse `AssetHistoryProjection` (tiene otro fin: historial público de tracking).

**Por qué importa.**
Es la base de `AssetPage`, la ruta autenticada más cercana a ser implementable. Si el puerto no existe, esa pantalla está bloqueada por implementación, no solo por contrato.

**Por qué "por verificar" y no "hueco".**
La revisión de wiring es de una fecha concreta y Fase 5 avanza en paralelo. Afirmar que falta sin mirar el repositorio violaría la regla 2.4 (cero inferencia de estado por memoria de sesiones previas).

**Criterio de cierre.**
Inspección del repositorio. Si existe: confirmar que respeta las exclusiones del `ReadModel` con un test que verifique la **ausencia** de `donorRef`, datos financieros y genealogía. Si no existe: pasa a ser hueco de implementación.

---

## 6. Mapa de desbloqueo

Qué pantalla o acción queda desbloqueada al resolver cada hallazgo. Una superficie solo es desplegable cuando **todos** sus bloqueos están resueltos.

| Superficie | Bloqueada por |
|---|---|
| `/c/:publicCode` | Módulo `convocatoria` sin código; narrativa: ADR-040; código HTTP de "no encontrada" |
| `/tracking/:trackingCode` | C2/H1 + verificación del contrato de credencial |
| `/login` | Implementación de `/auth/login`; fallo de `TokenIssuerPort` (ADR-038) |
| `/assets/:assetRef` (lectura) | `PhysicalAssetOperationalReadPort` (verificar); R10 para `REPRESENTATIVE` |
| `split` | P7; cuerpo; R10; **R11** |
| Registrar activo | P7; cuerpo de `register`; R10; **R11** |
| `/panel/campaigns` (listado) | **N1** (ficha congelada; falta Enmienda 1 de ADR-041 + implementación backend) |
| Crear convocatoria | N1; **R4**; **R11**; firma (ADR-037) |
| Asignar empleado | **R6**; R4; firma; D2 (ADR-037) |
| `/panel/platform/**` | **R1** (fuera de v1) |
| `from-donation` | **R8**; `HumanAccount`; P7 (fuera de v1) |

### Palanca de cada hallazgo

Sugerencia de lectura, **no un orden decidido**: la prioridad la fija el equipo.

- **N1** desbloquea todo `/panel/campaigns` y saca a `PanelHome` de su estado provisional. Es el de más superficie por unidad de trabajo. *(2026-10-05: ficha congelada y delta de frontend aprobado; queda la parte normativa y la implementación.)*
- **R11** es condición para desplegar los tres comandos web. Si `PhysicalAsset` ya tiene `commandId` desde Fase 1, puede ser sobre todo una verificación.
- **P7** (`golden-path.md` §5.2) es condición de seguridad para cualquier comando de `PhysicalAsset`, en web y en móvil.
- **Verificar `PhysicalAssetOperationalReadPort`** es barato y decide si `AssetPage` en modo lectura es la primera pantalla autenticada implementable.
- **R1 y R8** abren superficies que hoy están fuera de v1. Son las de mayor costo de diseño: R1 cruza organizaciones y R8 necesita un perímetro nuevo.

---

## 7. Lo que este documento no hace

- No elige ninguna de las "direcciones posibles".
- No asigna tareas ni ramas: cualquier hallazgo que introduzca un mecanismo de concurrencia, idempotencia o un nuevo contrato entre módulos requiere su ADR antes del código (regla 3.5).
- No sustituye a `front-fase2.md`, que sigue siendo la fuente de las decisiones de frontend.
- No reabre ningún ADR de dominio: los hallazgos heredados se citan con su fuente de origen.
