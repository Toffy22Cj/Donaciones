# Decisiones delegadas — octubre de 2026

**Origen:** autorización de trabajo autónomo de Carlos, 2026-10-07 ("Autorización de trabajo autónomo — Carlos, 2026-10-07"). Es una **excepción temporal** a la regla 1 y a la 3.4 de `reglas-equipo-y-agentes.md`, válida hasta que Carlos vuelva y escriba en el chat de la sesión.
**Qué contiene:** cada decisión que el agente tomó **por Carlos**. **DD-01 a DD-28: RATIFICADAS por Carlos el 2026-10-07, salvo DD-18, que sustituyó** (segunda autorización, sección 0). Las nuevas (DD-29 en adelante) nacen `PENDIENTE DE RATIFICACIÓN`. En los documentos afectados se marcan como `[DECISIÓN DELEGADA — pendiente de ratificar por Carlos]`.
**Criterio de decisión** (orden fijado por Carlos): (1) ADR y documentos aprobados en `develop`; (2) recomendaciones ya escritas en la conversación y en las propuestas; (3) la opción más segura, más reversible y más simple que cumpla el golden path; en seguridad, privacidad o dinero, la más restrictiva.
**Límites** (si se alcanzan, el bloque se para y se registra aquí): sin dependencias nuevas (Nimbus ya aprobada); sin tocar payloads de eventos, versiones de esquema, canonicalización, hash, Merkle ni anclaje; sin contradecir un ADR aprobado (única reversión autorizada: T-33); nada que habilite dinero real, el perfil de producción o un proveedor real; sin secretos; sin push a `main`, force push ni borrados; sin debilitar tests; sin PII, ids internos ni datos de otra organización en respuestas públicas.

---

## 1. Decisiones de Carlos aplicadas (no son delegadas; se listan para la trazabilidad)

| Decisión | Dónde quedó aplicada |
|---|---|
| Plan de B6-0 APROBADO; Q1 (409 estado / 400 validación / 404 "no encontrado"), Q2 (mismo 403), Q3 (`correlationId` en cuerpo y log); Q-B60-1 a 5; `Command-Id` en minúsculas | Ya estaba en `develop` (`plan-b6-0-base-http.md` §7, PR #57/#58). Nada que rehacer |
| Enmienda 3 de ADR-037: E3-Q1 sí, E3-Q2 sí, **E3-Q3: el `trackingCode` dura 1 año** | `ADR-037-enmienda-3-pasarela-y-consulta-de-intencion.md`: estado APROBADA completa, D6 y §6 con `P365D` |
| ADR-048 APROBADO con seudónimo aleatorio por cuenta; la tabla cuenta ↔ seudónimo solo la lee `identity` | Ya estaba en `develop` (`ADR-048` §7) |
| Q9: id de activo determinista y `Command-Id` en todos los comandos; **reversión explícita de T-33** con el nombre de Carlos y el motivo | Ya estaba en `develop` (`propuesta-d-api.md` §5 Q9; `propuesta-apis-fase6.md:222`). Se implementa en B6-c |
| CV-07: Q-CV07-1 reabierta solo para `acceptedPaymentMethods`; Q7 de D-API cerrada remitiendo a la ficha | Ya estaba en `develop` (`propuesta-d-api.md` §5 Q7). La ficha CV-07 **no está en el repositorio** (ver DD-03) |
| D-7 a D-9 de CV-01 entran en B6-a | Ya estaba en `plan-b6-a-convocatoria-http.md` §2.5 |
| B6-a incluye "el puerto de identity con el nombre de la organización" | Resuelve Q-B6A-1 en la opción (a) (ver DD-04 para los detalles que sí son delegados) |

## 2. Registro

Columnas: id · fecha UTC · bloque · pregunta · opciones · elegida · motivo · ¿reversible? · estado.

| id | Fecha UTC | Bloque | Pregunta | Opciones | Elegida | Motivo | ¿Reversible? | Estado |
|---|---|---|---|---|---|---|---|---|
| DD-01 | 2026-10-07T14:40Z | B6-0 / todos | **Q-B60-6:** "no existe" y "otra organización", ¿403 o 404? La instrucción de Carlos dice "404 uniforme"; DH-51 y Q-CV01-6 (ficha CV-01 CONGELADA, decidida por Carlos el 2026-10-06) dicen 403 | (a) 403 idéntico para las dos; (b) 404 idéntico para las dos, enmendando DH-51 y la ficha CV-01 | **(a) 403 idéntico** en todas las rutas con organización o recurso protegido; el 404 queda para lo que no existe **dentro** de la propia organización o en rutas públicas | Lo esencial de la instrucción (respuesta **uniforme**, sin oráculo de existencia) se cumple con las dos. (b) revierte una decisión de una ficha congelada, y la autorización dice que la única reversión permitida es T-33. Es la opción que no contradice nada aprobado | Sí: una línea por traducción (`CoreApiErrorMappings` y los registros de B6-a) y sus tests | **RATIFICADA — Carlos, 2026-10-07** |
| DD-02 | 2026-10-07T14:40Z | Proceso | La autorización menciona `chore/docs-sync-api` y "dos planes de B6-0" | — | `chore/docs-sync-api` **no existe** en el remoto (comprobado con `git ls-remote`). El plan de B6-0 ya estaba unificado en `develop` (#57). No hay nada que sincronizar ni sobrescribir | Manda el repositorio | — | **RATIFICADA — Carlos, 2026-10-07** |
| DD-03 | 2026-10-07T14:40Z | B6-a | **Q-B6A-5:** la ficha CV-07 no está en el repositorio | (a) esperar la ficha; (b) usar la lista de `plan-b6-a` §2.3 | **(b)** campos de §2.3 (con `acceptedPaymentMethods`, sin `publicCode` ni `targetPolicy`), a cotejar cuando la ficha llegue | Es lo que recomendaba el plan y coincide con Q7 tal como la cerró Carlos | Sí: un DTO | **RATIFICADA — Carlos, 2026-10-07** |
| DD-04 | 2026-10-07T14:40Z | B6-a | **Q-B6A-1** (detalles): Carlos pidió el puerto de `identity` con el nombre; `Organization` no tiene nombre | `name` obligatorio u opcional en la creación | `name` **opcional** en `Organization` (máx. 200 caracteres), recibido por `CreateOrganizationService`; puerto `OrganizationPublicNamePort` en `contracts` que devuelve `Optional<String>`; CV-07 omite el campo si no hay nombre | Obligatorio rompería a todos los llamadores y tests actuales sin necesidad para la demo. Es una **adición** a ADR-038 (nota de enmienda corta), no una contradicción | Sí: un campo nuevo, sin datos reales | **RATIFICADA — Carlos, 2026-10-07** |
| DD-05 | 2026-10-07T14:40Z | B6-a | **Q-B6A-2:** CV-02 con destinatario inválido, inexistente o `INACTIVE`; asignar en convocatoria `CLOSED` | (i) mismo 409 / respuestas distintas; (ii) permitir / 409 | (i) **el mismo 409** `InvalidResponsibleRecipient` para los tres, traducido en la capa HTTP (`IdentityApiErrorMappings`) para no contradecir X2 de `implementation_plan.md` §6 (`convocatoria` sigue propagando lo que lanza `identity`); (ii) **409** `CampaignClosed`, con excepción nombrada nueva | Recomendación escrita del plan; (i) impide averiguar qué cuentas existen; (ii) es la opción restrictiva y añade una regla, no contradice ninguna | Sí | **RATIFICADA — Carlos, 2026-10-07** |
| DD-06 | 2026-10-07T14:40Z | B6-a | **Q-B6A-3:** cuerpo de CV-02 | `{campaignRef, accountId, status}` (matriz) / `{assignmentId}` (código) | **`201 {assignmentId}`** y se corrige la matriz | Recomendación del plan: es lo que guarda el ejecutor, así que el duplicado devuelve el mismo cuerpo sin tocar el ejecutor; no expone `accountId` | Sí | **RATIFICADA — Carlos, 2026-10-07** |
| DD-07 | 2026-10-07T14:40Z | B6-a | **Q-B6A-4:** verificar organización, ¿con `Command-Id`? | (a) exento, 409 en el reintento; (b) obligatorio con idempotencia nueva en `identity` | **(a)** | Recomendación del plan; Q9 revirtió T-33 solo para `core`; (b) cambia `identity` sin que la demo lo necesite | Sí | **RATIFICADA — Carlos, 2026-10-07** |
| DD-08 | 2026-10-07T14:45Z | B6-c | Aprobación del plan de B6-c (`plan-b6-c-activos-http.md`) | — | El agente redacta el plan y lo ejecuta bajo la excepción a la regla 3.4 | Autorización de Carlos ("incluida la implementación de las APIs de B6") | Sí: el PR queda revisable | **RATIFICADA — Carlos, 2026-10-07** |
| DD-09 | 2026-10-07T14:45Z | B6-c | Camino B por HTTP: ¿quién da el `donorRef`? El dominio lo exige; el golden path dice "entrada directa" | (a) lo envía el cliente; (b) lo genera el servidor | **(b)** `donorRef = "anon:" + UUID aleatorio`, generado en el controlador; el cliente no lo envía y, si lo envía, se ignora | Privacidad (la más restrictiva): un texto libre del cliente acabaría en un evento inmutable y podría llevar un nombre (Habeas Data, mismo motivo que ADR-048). Formato del "opaco efímero" de ADR-048. El vínculo con una cuenta, si hace falta, se añade después sin migrar | Sí, sin datos reales | **RATIFICADA — Carlos, 2026-10-07** |
| DD-10 | 2026-10-07T14:45Z | B6-c | Registro A y B: ¿`organizationRef` en el cuerpo o del JWT? | cuerpo / principal | **Del principal** (`organizationId` del JWT); el cuerpo no lo lleva | Restrictiva: un campo menos que falsificar; la autorización ya exige que coincidan | Sí | **RATIFICADA — Carlos, 2026-10-07** |
| DD-11 | 2026-10-07T14:45Z | B6-c | Reenvío de un `Command-Id` ya usado por **otro** comando de `core` | (a) no-op silencioso (hoy); (b) 409 | **(b)** 409 `CommandIdReused`: cada comando de activo expuesto por HTTP guarda en su reclamo el resultado `TIPO:assetId`; un reenvío con otro resultado (o un reclamo sin resultado) es otro comando | "Mismo código y mismo cuerpo" (T-33 ampliada por Q9) no puede cumplirse si el id era de otro comando; el silencio ocultaría un error del cliente. Mismo criterio que `CommandIdReusedForDifferentCommandException` de `convocatoria` | Sí: solo afecta al resultado del reclamo, no a eventos | **RATIFICADA — Carlos, 2026-10-07** |
| DD-12 | 2026-10-07T14:45Z | B6-c | Activo inexistente en una ruta protegida | 404 / el mismo 403 que "otra organización" | **El mismo 403** (excepción nombrada `PhysicalAssetNotFoundException`, traducida igual que `CrossOrganizationAccessException`) | DD-01: respuesta uniforme sin oráculo de existencia. Es el comportamiento que el código ya tenía (sin organización, la frontera fallaba), ahora con nombre | Sí | **RATIFICADA — Carlos, 2026-10-07** |
| DD-13 | 2026-10-07T14:45Z | B6-c | `GET /physical-assets/{assetRef}`: ¿de la proyección o del event store? | proyección `logistics` / rehidratar del event store | **Event store** (como `SplitResolutionQueryService`) | Los activos del Camino B no se proyectan (decisión pendiente de C5 de ADR-040), así que la proyección daría 404 a la mitad de los activos de la demo. Sin modelo de lectura nuevo | Sí | **RATIFICADA — Carlos, 2026-10-07** |
| DD-14 | 2026-10-07T14:45Z | B6-c | Autorización de `deliver` y de las lecturas | matriz "pendiente" | `deliver` con `DELIVER_ASSET` (`EMPLOYEE`, como ya hace el código; A8 de D-API); lecturas con `EMPLOYEE` + frontera de organización (matriz §4b) | Lo ya decidido en el código y en la matriz | Sí | **RATIFICADA — Carlos, 2026-10-07** |
| DD-15 | 2026-10-07T14:45Z | B6-c | `deliveredAt`: ¿del cliente o del servidor? | cliente / reloj del servidor | **Reloj del servidor** | Restrictiva y simple: el cliente no puede fechar una entrega. Si Flutter necesita la hora de la entrega sin conexión, se añade como dato aparte | Sí | **RATIFICADA — Carlos, 2026-10-07** |
| DD-16 | 2026-10-07T14:45Z | B6-c | Camino A por HTTP necesita una asignación (`requestAllocation`, `ADMINISTRATOR`) que no tiene endpoint en ningún plan | añadir endpoint / dejarlo fuera | **Fuera de B6-c**; se registra como hallazgo (H-B6C-1) para Carlos | No hay contrato aprobado para ese endpoint; añadirlo es una decisión de API nueva. El registro del Camino A por HTTP queda implementado y probado con una asignación creada por servicio | Sí | **RATIFICADA — Carlos, 2026-10-07** |
| DD-17 | 2026-10-07T16:10Z | B6-a | La regla ArchUnit de `identity` exige que toda clase `*Service` use `MongoTransactionRetryHelper`; la lectura del nombre de la organización es una consulta de un documento | (a) envolver la lectura en el helper; (b) nombrarla como las otras lecturas de puertos (`IdentityPrincipalPortImpl`) | **(b)** `OrganizationPublicNameQuery` | La regla protege las escrituras transaccionales; una lectura no necesita transacción. La regla no se debilita; la escritura de B6-b (seudónimo) sí usa el helper | Sí: renombrar | **RATIFICADA — Carlos, 2026-10-07** |
| DD-18 | 2026-10-07T16:40Z | B6-b | CV-11 repetido con el mismo `Command-Id`: T-33 pide "mismo cuerpo", y la Enmienda 3 D6 dice que el `statusToken` "se entrega una sola vez" y solo se guarda su hash | (a) mismo cuerpo, guardando el token en claro; (b) mismo cuerpo sin el token; (c) token nuevo en cada reenvío | **(b)** mismo `201`, `intentId` y `paymentRedirectUrl`, sin `statusToken` | Manda el ADR aprobado (D6) sobre la regla en borrador T-33; (a) contradice "solo su hash"; (c) invalidaría el token del primer intento sin que el cliente lo sepa | Sí | **SUSTITUIDA — Carlos, 2026-10-07:** el reenvío emite un `statusToken` nuevo y anula el anterior (sigue habiendo un solo token válido, Enmienda 3 D6). Motivo: el reintento típico es una respuesta perdida, y un donante anónimo perdería su `trackingCode` |
| DD-19 | 2026-10-07T16:40Z | B6-b | ¿Dónde vive el seudónimo de ADR-048? El ADR dice "en la cuenta"; Carlos dijo "la tabla cuenta-seudónimo" | campo en `Account` / tabla propia | **Tabla propia `donor_pseudonyms`** en `identity` (`_id = accountId`, seudónimo único), escrita con un *upsert* atómico y `MongoTransactionRetryHelper` | Las palabras de Carlos; la supresión es borrar una fila sin tocar la cuenta; dos donaciones concurrentes de la misma cuenta obtienen el mismo seudónimo sin carreras | Sí, sin datos reales | **RATIFICADA — Carlos, 2026-10-07** |
| DD-20 | 2026-10-07T16:40Z | B6-b | Códigos HTTP de las excepciones de CV-11 y del webhook | — | Importe inválido → 400; moneda, tipo o medio no aceptados, convocatoria cerrada, efectivo, `CLOSE_ON_TARGET + CLOSE`, `SIMULATED` desactivado y sin proveedor → 409; sesión desconocida → 404; evento que no cuadra → 409; firma inválida → 401; intención o token sin acceso → el mismo 404 | Q1 de B6-0: 409 para conflictos con el estado del recurso (la configuración de la convocatoria), 400 para la forma de la entrada | Sí | **RATIFICADA — Carlos, 2026-10-07** |
| DD-21 | 2026-10-07T16:40Z | B6-b | `/account/donations`: paginación (T-35) | cursor / una página | **Una página con tope de 100**, ordenada por `intentId`, sin `nextCursor` (T-35 lo omite en la última página) | Suficiente para la demo; el cursor se añade sin romper el contrato | Sí | **RATIFICADA — Carlos, 2026-10-07** |
| DD-22 | 2026-10-07T16:40Z | B6-b | Forma del webhook simulado y su firma | — | Cuerpo JSON `{type: payment.confirmed|payment.failed, paymentSessionId, providerEventId, amount (texto), currency}`; cabecera `X-Simulated-Signature` = HMAC-SHA256 en hexadecimal del cuerpo en bruto; secreto en `TRACEABILITY_DEMO_WEBHOOK_SECRET` (≥ 32 bytes, sin valor por defecto; la aplicación no arranca sin él si el simulado está activo); solo `application-dev.yml` activa el simulado | A2.2 de D-API ("valida una firma HMAC con un secreto solo de demo"); sin secretos en el repositorio | Sí | **RATIFICADA — Carlos, 2026-10-07** |
| DD-23 | 2026-10-07T16:40Z | B6-b | La sesión del proveedor se abre dentro de la transacción de CV-11 | dentro / fuera | **Dentro**, porque el simulado no llama a nadie | Lo más simple para la demo. **Riesgo para un proveedor real:** un reintento de la transacción abriría otra sesión; habrá que moverlo fuera antes de P1 | Sí | **RATIFICADA — Carlos, 2026-10-07** |
| DD-24 | 2026-10-07T16:40Z | B6-b | Un test de B6-0 usaba un controlador de prueba en la ruta de CV-11, que ahora tiene controlador real | quitar el test / cambiarlo de ruta / condición extra | **Condición extra:** el controlador de prueba exige `?b60test=1`; el test conserva sus aserciones | Ninguna aserción se debilita y la ruta sigue siendo la de JWT opcional | Sí | **RATIFICADA — Carlos, 2026-10-07** |
| DD-25 | 2026-10-07T16:40Z | B6-b | Aprobación del plan de B6-b (`plan-b6-b-donacion-pago-http.md`) | — | El agente redacta el plan y lo ejecuta bajo la excepción a la regla 3.4 | Autorización de Carlos; las normas que implementa (Enmienda 3 de ADR-037 y ADR-048) ya estaban aprobadas | Sí: el PR queda revisable | **RATIFICADA — Carlos, 2026-10-07** |
| DD-26 | 2026-10-07T17:30Z | B6-d | Historial público (TR-03): categoría del custodio de las transiciones que no son un estado del ciclo de vida (`SPLIT`, `SPLIT_COMPENSATED`, `CUSTODY_TRANSFERRED`) | error / categoría inventada / `UNCATEGORIZED` | **`UNCATEGORIZED`**, y la categoría de las demás sale del estado de la transición (antes, por error, de la referencia del custodio: H-B6D-2) | La más restrictiva: no revela nada del custodio. Sin ella el historial real no funciona | Sí | **RATIFICADA — Carlos, 2026-10-07** |
| DD-27 | 2026-10-07T17:30Z | B6-d | TR-D1: ¿quién compone el 404 del seguimiento? (Q-B60-1 la mandó a B6-d) | cada controlador / el manejador único de B6-0 | **El manejador único**, con el `ProblemDetail` fijo `NotFound`; los 401 de los filtros de seguimiento no cambian | Q-B60-4 (un solo manejador); alinea el código con la ficha congelada TR-01. La enmienda documental de ADR-024 que pedía Q-TR-1 sigue pendiente | Sí | **RATIFICADA — Carlos, 2026-10-07** |
| DD-28 | 2026-10-07T17:30Z | B6-d | Alcance y plan de B6-d (`plan-b6-d-seguimiento-http.md`) | — | TR-D1, H-B6D-2 y el recorrido de la demo por HTTP; **sin narrativa de convocatoria** (B5 no está listo) | Instrucción de Carlos: "la narrativa de convocatoria solo si B5 está listo" | Sí | **RATIFICADA — Carlos, 2026-10-07** |

*Nota (2026-10-22) a DD-21, por la Enmienda 5 de ADR-037 (E5-2), APROBADA por Carlos:* el orden de `/account/donations` pasa de "ordenada por `intentId`" a **`createdAt` descendente**. Las intenciones sin `createdAt`, anteriores a la enmienda, van al final, en el orden de hoy (`_id`). El tope de 100 y la ausencia de `nextCursor` no cambian. Como el tope se aplica después de ordenar, la página trae las 100 más recientes. Corrige además el hallazgo H-L2 de la ficha L-FRONTS: `intentId` es un UUID aleatorio y ese orden no era cronológico. La fila de DD-21 no se reescribe.

## 3. Segunda autorización (Carlos, 2026-10-07)

**Decisiones de Carlos aplicadas (no delegadas):**
- Ratifica DD-01 a DD-28 salvo DD-18; DD-18 se sustituye: el reenvío de CV-11 emite un `statusToken` nuevo y anula el anterior.
- `IllegalArgumentException` pasa a 500; solo las validaciones con excepción propia dan 400.
- El avance se informa como "N de 19 criterios del golden path cumplidos por HTTP".
- Alcance ampliado: P1 (los 19 criterios), P2 (endpoints de la aplicación), P3 (predictor dentro del backend), P4 (enmienda de ADR-042, solo documento).

| id | Fecha UTC | Bloque | Pregunta | Opciones | Elegida | Motivo | ¿Reversible? | Estado |
|---|---|---|---|---|---|---|---|---|
| DD-29 | 2026-10-07T19:30Z | P1.1 | Id de una asignación pedida por HTTP | lo envía el cliente / aleatorio / determinista | **UUIDv5(NS_ALLOCATION, fundId:commandId)** | Como Q9 para los activos: el reenvío devuelve el mismo id sin guardar nada | Sí (cambiar el espacio de nombres solo afecta a reenvíos) | **RATIFICADA — Carlos, 2026-10-07** |
| DD-30 | 2026-10-07T19:30Z | P1.1 | Fondo inexistente en una ruta protegida | 404 / el mismo 403 | **El mismo 403** (`FundNotFoundException`) | DD-01 y DD-12 (ratificadas): sin oráculo de existencia | Sí | **RATIFICADA — Carlos, 2026-10-07** |
| DD-31 | 2026-10-07T19:30Z | P1.1 | Lectura de los fondos de una organización: fuente, roles y campos | proyección de donaciones / event store | **Event store** (génesis con `organizationRef`), `ADMINISTRATOR` o `EMPLOYEE`; campos `fundId`, `campaignRef`, `currency`, `clearedAmount`, `availableAmount` y asignaciones; **sin `donorRef`**; una página de 200 | La proyección no guarda la organización; el empleado necesita el `fundId` para el Camino A (H-B6D-1); el donante nunca sale | Sí | **RATIFICADA — Carlos, 2026-10-07** |
| DD-32 | 2026-10-07T19:30Z | P1.1 | ¿Confirmación manual de una asignación por HTTP? | no / sí | **Sí**, `ADMINISTRATOR`, idempotente; la saga del registro sigue confirmando sola | Carlos pidió los endpoints de `requestAllocation` y `confirmAllocation`; repetirla no tiene efecto | Sí | **RATIFICADA — Carlos, 2026-10-07** |

| DD-33 | 2026-10-07T17:59Z | P1.3 (B5) | Q-DIA-1 (C2): cómo se recolectan las entregas de una convocatoria | pull del event store en `core` / proyección nueva / ampliar `DonationProjection` | **Pull bajo demanda del event store en `core`** (`CampaignDeliveryFactsQuery`), por la génesis `ASSET_REGISTERED` con `payload.campaignRef` (incluye Camino B e hijos de una división); índice nuevo `(eventType, payload.campaignRef)` en `event_store` | Recomendación de `propuesta-d-ia.md` §2; ADR-040 §2.1 excluye un read model solo para IA; un índice no cambia eventos, hash, Merkle ni anclaje | Sí (borrar el índice y la consulta) | **RATIFICADA — Carlos, 2026-10-07** |
| DD-34 | 2026-10-07T17:59Z | P1.3 (B5) | Q-DIA-2 (C3): vida de los hechos y de la narrativa | efímero / snapshot persistido | **Hechos efímeros; narrativa en memoria** por convocatoria, clave `sha256(hechos) + promptTemplateVersion + modelIdentifier`, generación asíncrona de un solo vuelo; un fallo del proveedor se reintenta tras `fallbackRetryInterval`, un rechazo de grounding no | Recomendación §3; ADR-040 §2.5 aplaza una colección propia; la demo no pide histórico. Se usa un mapa propio en vez de `NarrativeCacheCoordinator` porque este está tipado a `DonorReportDTO` y no se toca la narrativa individual | Sí | **RATIFICADA — Carlos, 2026-10-07** |
| DD-35 | 2026-10-07T17:59Z | P1.3 (B5) | Q-DIA-3 (C4): consistencia temporal | transacción entre módulos / lecturas independientes | **Lecturas independientes**, con el instante de cada fuente | Recomendación §4; el grounding valida contra los mismos hechos que recibió el LLM | Sí | **RATIFICADA — Carlos, 2026-10-07** |
| DD-36 | 2026-10-07T17:59Z | P1.3 (B5) | Q-DIA-4: ¿se amplía `CampaignAuditFactsDTO`? | no / sí | **Sí**: `currency`, `fundingReadAt` y `deliveriesReadAt` | El puerto no tenía implementación ni consumidor: no rompe a nadie; sin `currency` el importe no se puede narrar | Sí | **RATIFICADA — Carlos, 2026-10-07** |
| DD-37 | 2026-10-07T17:59Z | P1.3 (B5) | Q-DIA-5 (C5): dónde vive cada pieza | — | **Productor** `CampaignAuditFactsProducer` en `app`; **entregas** en `core`; **financiación** `CampaignFundingFactsQuery` en `convocatoria`; **generador y grounding** en `ai`; **ruta** en `app.web` | Convención A1 de D-API: lo que cruza módulos vive en `app`; `ai` no depende de `core` (ArchUnit) | Sí | **RATIFICADA — Carlos, 2026-10-07** |
| DD-38 | 2026-10-07T17:59Z | P1.3 (B5) | Q-DIA-6 (C8): ¿narrativa de una convocatoria `CLOSED`? | no / sí | **Sí**; las `PRIVATE_LINK` por su código, como CV-07; código inexistente o mal formado → el mismo 404 que CV-07 | Recomendación §5: es el informe final | Sí | **RATIFICADA — Carlos, 2026-10-07** |
| DD-39 | 2026-10-07T17:59Z | P1.3 (B5) | Contrato de la ruta y del grounding (`plan-b5-narrativa-convocatoria.md`) | — | `GET /api/v1/public/campaigns/{publicCode}/narrative`: `200 AVAILABLE`, `202 PENDING`, `200 UNAVAILABLE` con "Narrativa no disponible", siempre con los hechos públicos. El LLM recibe solo estado, moneda, meta, política, `clearedAmount`, unidades y receptores distintos. El grounding rechaza: sin citas, cita con valor distinto, cifra no citada, "familia(s)/hogar(es)" | Condiciones obligatorias de Carlos (segunda autorización, P1.3); hechos citables en un enum propio (`CampaignFactType`) para no tocar el pipeline individual | Sí | **RATIFICADA — Carlos, 2026-10-07** |
| DD-40 | 2026-10-07T18:06Z | P1.4 | Cómo se prueban los criterios 10, 11, 12 y 18 y cómo los ejecuta Carlos en testnet | test aparte en `crypto` / el mismo `GoldenPathHttpIntegrationTest` | **El mismo recorrido**, con Ganache por Testcontainers (clave determinista #0 de Ganache, solo de test, como los tests de `crypto`; contrato desplegado con la #1) y confirmaciones 0; con `GOLDEN_PATH_ANCHOR_TARGET=testnet` toma red, contrato y wallet de variables de entorno (`runbook-anclaje-testnet.md`) y escribe `app/target/golden-path-anclaje-evidencia.txt` sin secretos | Los eventos anclados son los del recorrido real; no hay ruta HTTP ni CLI para `verifyBatch`, así que sin esto Carlos no podría producir el criterio 12 en testnet; ningún valor real en el código | Sí | **RATIFICADA — Carlos, 2026-10-07** |
| DD-41 | 2026-10-07T18:22Z | P3 | Cómo llega el modelo de Python a Java | ONNX / PMML / JSON propio | **JSON propio** exportado por `scripts/predictor/export_baseline_json.py` (coeficientes, escalado, categorías y árboles) | Sin dependencias nuevas (prohibidas); el formato es trivial de evaluar y de auditar; los floats con `repr` son exactos | Sí | **RATIFICADA — Carlos, 2026-10-07** |
| DD-42 | 2026-10-07T18:22Z | P3 | Qué modelos se sirven | logística / HGB / ambos | **Logística** para la probabilidad (mejor Brier) y **HGB regresor** para el % final | Instrucción de Carlos ("mejor modelo" y "regresor del % final"); `metrics.json` | Sí | **RATIFICADA — Carlos, 2026-10-07** |
| DD-43 | 2026-10-07T18:22Z | P3 | Ruta y autorización | — | `GET /api/v1/organizations/{organizationId}/campaigns/{campaignRef}/prediction`, `ADMINISTRATOR` o `REPRESENTATIVE` de la organización; el resto, el mismo 403 | Instrucción de Carlos; DD-01 | Sí | **RATIFICADA — Carlos, 2026-10-07** |
| DD-44 | 2026-10-07T18:22Z | P3 | Forma de la respuesta | — | `kind: "ESTIMATE"`, `modelVersion`, `warning`, `available`; con estimación `probabilityReachTarget`, `estimatedFinalPctOfTarget`, `pctTimeElapsed` (4 decimales) y `warnings`; sin ella `unavailableReason` y `unavailableText`; `Cache-Control: no-store` | Instrucción de Carlos; la UI debe distinguir estimación de hecho | Sí | **RATIFICADA — Carlos, 2026-10-07** |
| DD-45 | 2026-10-07T18:22Z | P3 | Variables estáticas con datos reales | — | `orgPriorCampaigns` = convocatorias de la organización con `startDate` anterior; `paymentMethodsEnabled` = nº de `acceptedPaymentMethods`; fuera del rango t∈[0.15, 0.50] se sirve con una segunda advertencia | La definición sintética es un atributo, no una regla; es la lectura más cercana y no inventa datos | Sí | **RATIFICADA — Carlos, 2026-10-07** |
| DD-46 | 2026-10-07T18:22Z | P3 | Correspondencia de estados de `DonationIntent` con el entrenamiento | — | `CONFIRMED` suma; `FUNDING_REJECTED` = `rejectedByPolicy`; `FAILED` y `EXPIRED_UNKNOWN` = fallo; `PENDING` no cuenta | Misma semántica que `build_snapshot`; contar `PENDING` como fallo inflaría `failedRate` | Sí | **RATIFICADA — Carlos, 2026-10-07** |
| DD-47 | 2026-10-07T18:22Z | P3 | Sin estimación | — | Motivos explícitos: STRICT, sin meta monetaria, sin empezar, terminada, meta ya alcanzada, más de 10 000 intenciones | Instrucción de Carlos para STRICT; el resto son casos que el entrenamiento excluye o no ve | Sí | **RATIFICADA — Carlos, 2026-10-07** |
| DD-48 | 2026-10-07T18:36Z | P2.8 | VERIFY/REJECT/REQUEST_INFORMATION sobre una organización ya `VERIFIED` o `REJECTED` | 200 idempotente / 409 | **409** `InvalidVerificationTransition` | Instrucción de Carlos ("elige 409 y regístralo"); es la regla de dominio de ADR-038 | Sí | **RATIFICADA — Carlos, 2026-10-07** |
| DD-49 | 2026-10-07T18:36Z | P2.3 | Paginación del listado de convocatorias de la organización | cursor / una página | **Una página con tope de 100**, sin `nextCursor` | Como DD-21 (ratificada) | Sí | **RATIFICADA — Carlos, 2026-10-07** |
| DD-50 | 2026-10-07T18:36Z | P2.5 | Forma HTTP de retirar responsable | `DELETE` / `POST .../remove` | **`POST /campaigns/{campaignRef}/responsibles/{responsibleRef}/remove`** con cuerpo opcional de reemplazo y `Command-Id` | El reemplazo va en la misma operación (ADR-037 §2.5) y `DELETE` con cuerpo no es portable | Sí | **RATIFICADA — Carlos, 2026-10-07** |
| DD-51 | 2026-10-07T18:36Z | P2.5 | Retirar a quien no es responsable activo | 404 / 409 | **409** `ResponsibleAssignmentNotFound` | La convocatoria ya está autorizada; es un conflicto de estado, como "ya responsable" | Sí | **RATIFICADA — Carlos, 2026-10-07** |
| DD-52 | 2026-10-07T18:36Z | P2.6 | ¿El descubrimiento lista convocatorias `CLOSED`? | sí / no | **No: solo `PUBLIC` y `OPEN`** | "Descubrir" es para donar; una cerrada se sigue viendo por su código (CV-07) | Sí | **RATIFICADA — Carlos, 2026-10-07** |
| DD-53 | 2026-10-07T18:36Z | P2.6 | Cursor y página del descubrimiento | — | Orden por `publicCode`; cursor = último `publicCode` en Base64 URL; 20 por página | El `publicCode` de una convocatoria pública ya es público: el cursor no filtra ids internos | Sí | **NO RATIFICADA — Carlos, 2026-10-07:** el cursor debe ser opaco (T-35); se rehace |
| DD-54 | 2026-10-07T18:36Z | P2.7 | Quién lista los activos de la organización | `EMPLOYEE` / `ADMINISTRATOR` o `EMPLOYEE` | **`ADMINISTRATOR` o `EMPLOYEE`**, tope 200, del event store | Como los fondos (DD-31); el panel lo usa el administrador | Sí | **RATIFICADA — Carlos, 2026-10-07** |
| DD-55 | 2026-10-07T18:36Z | P2.7 | Miembros (ID-12): quién y qué campos | — | **`ADMINISTRATOR` o `REPRESENTATIVE`**; `{accountId, roles, status}`, **sin email** | Gestión de la organización; el email es PII | Sí | **RATIFICADA — Carlos, 2026-10-07** |
| DD-56 | 2026-10-07T18:36Z | P2.2 | `Command-Id` en el registro | sí / no | **No** | Identity no usa `commandId` (DH-34, como DD-07); un reintento recibe 409 | Sí | **RATIFICADA — Carlos, 2026-10-07** |
| DD-57 | 2026-10-07T19:59Z | S-03 | Detalles de CORS que la instrucción no fija | — | Además de `Authorization`, `Command-Id` e `Intent-Token`, se permite **`Content-Type`** (sin ella el navegador no puede enviar JSON); métodos `GET`, `POST`, `OPTIONS`; `max-age` 600 s; solo `/api/v1/**`; filtro antes de la autenticación para que el preflight no pida JWT; un origen mal formado o con comodín impide arrancar | Lo mínimo para que el frontend funcione; lo más restrictivo en lo demás | Sí | **RATIFICADA — Carlos, 2026-10-08** |
| DD-58 | 2026-10-07T20:05Z | DD-53 rehecha | Cómo se hace opaco el cursor del descubrimiento (T-35) | Base64 del `publicCode` (DD-53, no ratificada) / cursor firmado / cursor cifrado | **AES-256-GCM** del último `publicCode` con IV aleatorio, en Base64 URL; alterado, fabricado o de otra clave → 400. Clave `TRACEABILITY_DISCOVERY_CURSOR_KEY` (32 bytes en Base64). *Texto anterior: "opcional; sin ella, aleatoria por proceso"* | Opaco de verdad (no se lee ni se fabrica) sin dependencias nuevas | Sí | **CORREGIDA — Carlos, 2026-10-07:** la clave llega solo por variable de entorno, **sin valor por defecto**, con fail-fast, distinta de los demás secretos y sin aparecer en logs (`DiscoveryCursorConfig`). El resto, pendiente de ratificar |
| DD-59 | 2026-10-07T20:13Z | Encargo 3, punto 4 | Detalles de las correcciones de H-P2-1 y del predictor | — | La regla de 12 caracteres cuenta puntos de código, no recorta, y rige también al **cambiar** la contraseña; `UNSUPPORTED_CURRENCY` se evalúa tras "sin meta monetaria" y antes de STRICT. Además se corrige **H-P3-1**: `logTargetAmount` sobre unidades enteras (exponente ISO 4217), no sobre unidades mínimas | Misma política en todas las vías de contraseña; H-P3-1 era un error de unidades que desplazaba la variable fuera de la distribución de entrenamiento | Sí | **RATIFICADA — Carlos, 2026-10-08** |

## 4. Tercera autorización (Carlos, 2026-10-07T22:10Z): organizaciones, usuarios, empleados y administradores

**Decisiones de Carlos aplicadas (no delegadas):**
- **Correo:** única dependencia nueva autorizada, `spring-boot-starter-mail`. SMTP estándar; **Mailpit** (Docker, `scripts/demo/`) por defecto en `dev`/demo; Gmail con contraseña de aplicación solo por variables de entorno; credenciales nunca en código, configuración versionada ni logs → ADR-049 D1.
- **Invitaciones:** token aleatorio de un solo uso, 7 días, guardado solo como hash; el enlace lleva el token en el **fragmento** (`/invitaciones#token=…`). **Excepción consciente a "ningún secreto en la URL", decidida por Carlos el 2026-10-07**; aceptar exige una cuenta autenticada **cuyo email coincida**; se invita como `ADMINISTRATOR` o `EMPLOYEE`, nunca `REPRESENTATIVE` → ADR-049 D2–D5.
- **Crear organización:** cualquier usuario registrado; queda como `REPRESENTATIVE` y la organización nace `PENDING_VERIFICATION`.
- **Confirmación manual de pagos (transferencia y efectivo): FUERA**, como deuda en `estado-fase6.md` con sus tres motivos.
- **Administradores de plataforma:** ante un estado que ya es el pedido, 409; nunca sin ningún administrador.
- **Miembros:** los gestionan `ADMINISTRATOR` y `REPRESENTATIVE`; nunca sin `REPRESENTATIVE`; no se quita ni degrada a un responsable activo sin reemplazarlo (409).
- **Seguridad:** ningún endpoint revela si un email tiene cuenta; el token nunca en logs, respuestas ni URLs de la API; los correos sin datos de terceros salvo el nombre de la organización.

| id | Fecha UTC | Bloque | Pregunta | Opciones | Elegida | Motivo | ¿Reversible? | Estado |
|---|---|---|---|---|---|---|---|---|
| DD-60 | 2026-10-07T22:30Z | §2 | ADR del correo y de las invitaciones (regla 3.5) | — | **ADR-049** redactado y aplicado como decisión delegada; número reservado en el catálogo, que además daba el 048 como libre aunque ADR-048 existe y está aprobado (se añade su entrada) | Orden expreso de Carlos (§2) | Sí: documento | **RATIFICADA — Carlos, 2026-10-08** |
| DD-61 | 2026-10-07T22:30Z | §2 | ¿Valor por defecto del servidor SMTP fuera de `dev`? | `localhost` / ninguno | **Ninguno**: sin `SPRING_MAIL_HOST` (ni remitente ni URL base de la web) la aplicación no arranca; en `dev`, Mailpit en `localhost:1025` | Mismo patrón que los secretos (ADR-047 D3, DD-58); un `localhost` silencioso en producción perdería invitaciones | Sí: una línea de configuración | **RATIFICADA — Carlos, 2026-10-08** |
| DD-62 | 2026-10-07T22:30Z | §2 | Forma del token, del hash y de las invitaciones repetidas | HMAC con secreto / SHA-256; varias pendientes / una | Token de **256 bits** (`SecureRandom`, Base64 URL), **SHA-256 sin sal** con índice único; email guardado **en minúsculas**; **una sola `PENDING` por (organización, email)**: reinvitar revoca la anterior | Con 256 bits el hash no se puede enumerar y no hace falta otro secreto; una sola pendiente evita enlaces vivos olvidados | Sí | **RATIFICADA — Carlos, 2026-10-08** |
| DD-63 | 2026-10-07T22:30Z | §2 | Respuesta al aceptar un token inválido, caducado, revocado, usado o de otro email | respuestas distintas / una | **El mismo 403 `InvitationNotAcceptable`** para todos; la cuenta que ya tiene organización, 409, pero solo tras validar token y email | Sin oráculo sobre el token ni su destinatario (DD-01) | Sí | **RATIFICADA — Carlos, 2026-10-08** |
| DD-64 | 2026-10-07T22:30Z | §2 | Orden transacción/correo, fallo SMTP y organización sin verificar | correo dentro / después; error / 202; permitir / prohibir | **Después** de confirmar la transacción; un fallo SMTP deja `delivery: FAILED` y responde **202** igual; **se permite invitar** sin estar verificada | Sin correos duplicados por reintentos de Mongo; el fallo no depende de la cuenta; la verificación protege convocatorias, no el equipo | Sí | **RATIFICADA — Carlos, 2026-10-08** |
| DD-65 | 2026-10-07T22:30Z | §2 | Lista de invitaciones: ¿email visible? | completo / enmascarado / nada | **Enmascarado** (`m***@dominio`), una página de 100, sin token ni hash | El email es PII (DD-55); enmascarado basta para reconocerlo | Sí | **RATIFICADA — Carlos, 2026-10-08** |
| DD-66 | 2026-10-07T22:30Z | §2 | Cambiar el rol: semántica, estado ya pedido y roles del representante | — | Operación nueva `changeMemberRole(ADMINISTRATOR\|EMPLOYEE)`; el estado ya pedido → **409**; los roles del `REPRESENTATIVE` solo los cambia él mismo (403 para un `ADMINISTRATOR`) | Más restrictiva; `REPRESENTATIVE` se sigue cambiando solo por transferencia (ADR-026) | Sí | **RATIFICADA — Carlos, 2026-10-08** (deuda registrada: transferir el papel de representante por HTTP) |
| DD-67 | 2026-10-07T22:30Z | §2 | ¿Cómo sabe `identity` si un miembro es responsable activo? | puerto en `contracts` / copiar datos | **Puerto de solo lectura `CampaignResponsibilityPort`** en `contracts`, implementado por `convocatoria`; carrera con CV-02/CV-03 concurrentes aceptada como riesgo residual escrito | Sin acoplar módulos (como `CampaignInKindEligibilityPort`) ni candados distribuidos | Sí | **RATIFICADA — Carlos, 2026-10-08** |
| DD-68 | 2026-10-07T22:50Z | §3.1 | Crear organización por HTTP: campos y respuesta | `name` opcional / obligatorio | **`name` obligatorio** (1–200, sin espacios a los lados) y `type` del enum en mayúsculas; `201 {organizationId, verificationStatus}` sin `Location` | La cola y CV-07 muestran el nombre; el dominio sigue admitiéndolo opcional (DD-04), así que no se contradice | Sí | **RATIFICADA — Carlos, 2026-10-08** |
| DD-69 | 2026-10-07T22:50Z | §3.1 | Cola de verificación: estados, orden, cursor y campos | — | `PENDING_VERIFICATION` y `NEEDS_MORE_INFORMATION` (otros → 400); orden por ULID; cursor AES-GCM con la clave de DD-58 y **propósito propio** en el texto cifrado; `{organizationId, name?, type, verificationStatus, informationRequest?}`, sin miembros ni emails | Sin secretos nuevos; un cursor no sirve en otra lista; mínimos datos personales | Sí | **RATIFICADA — Carlos, 2026-10-08** |
| DD-70 | 2026-10-07T23:05Z | §3.2 | Administradores de plataforma: verbo de la revocación, cuenta inexistente y campos | `DELETE` (matriz) / `POST …/revoke`; 404 / 403 | **`POST …/revoke`**; inexistente → **404** (solo la ve la plataforma, como DD-48); lista `{accountId, status}` sin email; autoriza antes de validar | Coherente con DD-50 y con los métodos de CORS (DD-57); sin PII | Sí | **RATIFICADA — Carlos, 2026-10-08** |
| DD-71 | 2026-10-07T23:40Z | §3.3 | Detalles HTTP de invitaciones y miembros que ADR-049 no fija | — | Invitar → **202**; aceptar → `200 {organizationId, roles}`; cualquier query en `accept` → el mismo 403; rol y retirada por `POST …/role` y `POST …/remove`; `RepresentativeTransferRequired` → 409 | El correo es posterior y puede fallar (DD-64); un token nunca por la URL; coherente con DD-50 y DD-70 | Sí | **RATIFICADA — Carlos, 2026-10-08** |
| DD-72 | 2026-10-07T23:55Z | §3.4 | "Mis convocatorias": ¿qué entra? | solo abiertas / también cerradas | Asignaciones **activas** en la **organización actual** (del principal), **también `CLOSED`** con su estado; una página de 100; sin parámetros | El empleado ve su historial y la web filtra; nunca datos de otra organización ni de otra cuenta | Sí | **RATIFICADA — Carlos, 2026-10-08** |
| DD-73 | 2026-10-07T23:59Z | §3.5 | Enmienda de ADR-037 para editar la configuración con solicitud y aprobación (D3) | — | **Enmienda 4** aplicada: edición directa por HTTP solo sin donaciones; **una sola solicitud pendiente** por convocatoria; la propuesta se valida al pedirla; aprueba otro `ADMINISTRATOR` o el `REPRESENTATIVE`, nunca el solicitante (403 `SelfApprovalNotAllowed`); rechaza ese mismo grupo o el solicitante (retirar); si la versión avanzó, la aprobación falla y la solicitud sigue pendiente; **con intenciones nunca se quita `MONETARY`** (409) | Sigue D2/D3 de la Enmienda 1 sin reabrirlas; lo más restrictivo en lo que dejaba abierto | Sí | **RATIFICADA — Carlos, 2026-10-08** |
| DD-74 | 2026-10-08T04:00Z | Encargo 5 (4) | Dónde normalizar el email a minúsculas y qué hacer con las cuentas existentes | Normalizar en cada controlador; búsqueda sin distinguir mayúsculas (regex o collation) sin cambiar lo guardado | **En el objeto de valor `Email`** (`toLowerCase(Locale.ROOT)` tras validar el formato), así registro, login, unicidad de cuentas, arranque del administrador de plataforma e invitaciones usan el mismo valor. ~~No se recortan espacios~~ → **sí se recortan** los espacios de los extremos (añadido de Carlos al ratificar). **Sin migración de datos:** la base de la demo tenía 0 cuentas con mayúsculas (`db.accounts.countDocuments({email:/[A-Z]/})` → 0) y no hay producción | Un solo punto evita que un camino nuevo se olvide de normalizar; el índice único de `accounts.email` queda sin distinguir mayúsculas de hecho; una búsqueda por regex no usa bien el índice y deja duplicados posibles | Sí | **RATIFICADA — Carlos, 2026-10-08**, con el añadido de quitar los espacios de los extremos (test incluido) |
| DD-75 | 2026-10-08T07:00Z | Encargo 6, P3 (S-10) | Estimaciones históricas: fuente de lo recaudado, variables no reconstruibles y cortes sin cifra | Ampliar la respuesta de la predicción / endpoint aparte; reconstruir con las intenciones actuales / con el event store; inventar la tasa de fallos / tomarla como 0 | **Endpoint aparte** (`…/prediction/history`), para no cambiar la respuesta que ya consumen los fronts. Lo recaudado en el corte sale de los **`FUNDS_CLEARED` del event store** con `occurredAt` < corte (como `dayFraction < t`). **Tasa de fallos 0** en los cortes pasados, con advertencia: los pagos fallidos no se registran en el event store y las intenciones no guardan cuándo fallaron (coeficiente del modelo −0,036). Sin cifra: corte futuro, posterior al cierre (auditoría `CONVOCATORIA_CLOSED`), anterior a un cambio de configuración (`CONFIGURATION_EDITED` o `CONFIGURATION_CHANGE_APPROVED` posteriores, porque no se conocen los valores de entonces) o con la meta ya alcanzada (el entrenamiento excluye esas instantáneas; se da `pctRaisedAtCut`). Atributos estáticos de la configuración vigente, válidos solo si no cambió después del corte | Carlos pidió "nunca desde valores actuales"; mejor un corte sin cifra que una cifra con datos de otro momento | Sí | PENDIENTE DE RATIFICACIÓN |
| DD-76 | 2026-10-08T07:30Z | Encargo 6, P4 | Integridad visible por la API: ruta, qué es "de la donación", qué no sale y coste | Ruta propia o campo de TR-01; con o sin `batchId`; límite de frecuencia o caché | **Ruta propia** `GET /donations/tracking/integrity`, con el `trackingCode` como TR-01 y sin cambiar TR-01. "De la donación" = el stream del fondo y los activos de su proyección (`logistics`). **No salen** el `batchId`, la cobertura, las secuencias afectadas, la raíz recalculada ni el `fundId`: `eventsOfThisDonation` y `affectsThisDonation` solo miran los streams propios. Motivos derivados del resultado y del estado del lote (`NOT_ANCHORED`, `LEGACY_BATCH`, `ROOT_MISMATCH`, `LEAF_COUNT_CHANGED`, `VERIFICATION_INCONCLUSIVE`). **Caché** del resultado por lote anclado, 5 min (`traceability.integrity.verification-cache-ttl`), hasta 1000 lotes (al superarse, se vacía). Un lote no anclado no se verifica | La verificación recalcula el árbol entero; la caché acota el coste sin estado por cliente; un `batchId` o una secuencia ajena permitirían relacionar donaciones | Sí | PENDIENTE DE RATIFICACIÓN |
| DD-77 | 2026-10-08T07:15Z | Encargo 6, P5 | Forma de la semilla de la demo | Script externo por la API / perfil de Spring | **Perfil `demo-seed`** (`DemoScenarioSeeder` al `ApplicationReadyEvent`), que llama a la **propia API HTTP** como un front. Solo usa servicios de aplicación donde no hay ruta: el arranque del administrador de plataforma (sin ruta por diseño) y las altas de administrador y empleados (por HTTP exigen el token del correo de invitación). Comprueba que la base está vacía (`accounts`, `organizations`, `convocatorias`, `event_store`, `donation_intents`, `merkle_batches`) y, si no, detiene el arranque con el mensaje. La activa se fecha para estar al **30 %** el día de la demo (configurable). Fichero de credenciales en `app/demo-evidencia/` (ignorado por git) | Por la API sola no se puede crear el administrador de plataforma sobre una base vacía sin reiniciar el backend; dentro de la aplicación, el test de integración (con Mongo real) la prueba en cada `ci-local.sh` | Sí | PENDIENTE DE RATIFICACIÓN |
| DD-78 | 2026-10-08T08:00Z | Encargo 6, P6 (B4, §2.2) | Detalles de la verificación recalculada que la aprobación no fija | — | **Corte** = fecha del commit `0579f41` en UTC (2026-09-17T02:49:04Z); un `recordedAt` ilegible cuenta como posterior. **Recálculo** = el pipeline de producción sobre el evento leído en crudo (`convertPayload` → `toCanonicalMap` → `canonicalizeAndHash` con el `previousHash` guardado). Si el payload ya no se puede leer con su tipo y versión, cuenta como hash que no cuadra (`MISMATCH` después del corte, `CANONICAL_FORM_UNKNOWN` antes). **Precedencia**: cualquier hash o cadena que no cuadre, o una raíz distinta, dan `MISMATCH` aunque haya además eventos de forma desconocida; solo sin nada de eso, `INCONCLUSIVE`/`CANONICAL_FORM_UNKNOWN` con esas secuencias. `VerificationResult` conserva su constructor de cinco componentes (motivo `null`). El seguimiento de P4 muestra `CANONICAL_FORM_UNKNOWN` como motivo propio | Lo más estricto que respeta "nunca MISMATCH" solo para la forma anterior a 0579f41 | Sí | PENDIENTE DE RATIFICACIÓN |
| DD-79 | 2026-10-08T08:30Z | Encargo 6, P6 (B4, §2.3) | Detalles de `COLLECTING_FAILED`, `RETRY` y `RELEASE` que la aprobación no fija | — | **Tope** `crypto.anchor.collecting-recovery.max-attempts` = **10** por defecto (el aviso sigue en 5): con `recoveryAttempts` mayor que el tope, el batch pasa a `COLLECTING_FAILED` sin hacer la fase 2. Un fallo al **leer** los batches viejos (p. ej. un batch antiguo sin cobertura) se registra y la reclamación de eventos nuevos sigue en el mismo ciclo; ese batch ilegible no se marca, porque no se puede leer. **JMX** `CollectingFailedBatchAdminOperations.resolveCollectingFailedBatch(batchId, RETRY\|RELEASE, operator, reason)`; `RELEASE` exige operador y motivo. **Auditoría** en la colección `batch_release_audit` (`batchId`, `coverage`, `eventIds`, `operator`, `reason`, `releasedAt`), en la misma transacción que el paso a `RELEASED` y la liberación. Las dos operaciones solo actúan sobre `COLLECTING_FAILED`; si no, error y nada cambia. **Consumidores** de `AnchorStatus` revisados: el planificador, el poller y las operaciones de anclaje consultan estados concretos y no ven los nuevos; el seguimiento (P4) los muestra como `NOT_ANCHORED` | El tope de 10 deja margen a fallos transitorios (con el aviso a mitad de camino); marcar un batch ilegible exigiría escribir en un documento que no se puede leer | Sí | PENDIENTE DE RATIFICACIÓN |

**Nota a DD-68 (2026-10-16T03:44Z, ficha S-18):** DD-68 registró que no existía `GET /organizations/{id}` porque no lo pedía la autorización. Con `ficha-S18-estado-verificacion-organizacion.md` (CONGELADA; Q-S18-1 (a), decidida por Manuel el 2026-10-16T03:23Z) pasa a existir `GET /api/v1/organizations/{organizationId}`. No es una contradicción de diseño y DD-68 no cambia: `POST /organizations` sigue sin `Location`.

**Nota (2026-10-24): delegación de Carlos en Manuel para los planes de Fase 7 (backend).** Mensaje de Carlos a Manuel, recibido **por WhatsApp el 2026-10-24** y pegado por Manuel en la sesión de trabajo del mismo día. Se transcribe literal:

```
Manuel: para que la web quede completa (fiel a los bocetos), hacen falta
estas piezas del backend. Tienes delegación mía para los detalles técnicos
de cada plan (los puntos [Plan] / [Manuel]); yo doy el visto bueno en cada
PR. Las reglas de siempre: plan antes del código, tests en rojo primero,
resumen literal de Surefire, ci-local completo.

Orden propuesto:

1. ADR-050 R-3 + R-2 (revocación y cambio de contraseña). El plan
   "plan-r3-r2-revocacion-y-cambio-de-contrasena.md" está PROPUESTO: revísalo
   contra develop actual (se escribió sobre 4bef7be) y apruébalo tú con la
   delegación.
2. ADR-050 R-4 (recuperar la contraseña por correo, D8).
3. ADR-050 R-5 + D1 (dos casillas legales en el registro, re-aceptación en
   el login con 403 AcceptanceRequired, términos de organización al crearla,
   y GET/PUT /account/profile con nombre y apellido). Versiones iniciales:
   v1-2026-10 en los tres valores traceability.legal.*-version (textos en
   paxfide-web/Documentos/legal/, borrador académico).
4. ADR-050 D6 (desactivar la propia cuenta).
5. Ficha R-6, bloque B: G-2 (fecha del pago), G-6 (recaudado por mes) y G-9
   (lastEventAt? en la lista de lotes, Enmienda 1 §E2).
6. Ficha R-6, bloque C: G-4 (parentAssetRef) y G-3 con la Enmienda 1 §E1
   (eventHash y prueba de Merkle por paso; reabre D-R6-6). Deja los vectores
   fijos de E1.5 en un recurso: la web los usa en su test.
7. Ficha R-6, bloque D: G-7 y G-8.
R-6 de ADR-050 (Google): sigue de última, sin fecha.

Cada PR que llegue a develop desbloquea una parte de la web (F-C1, F-C2 o
F-C3 del plan de fidelidad). Avísame cuando cada uno esté mergeado.

Documentos: ficha-R6-enmienda-1.md (nuevo), notas del 2026-10-24 en
ficha-R6-datos-estilo-v2.md §9 y en ADR-050.
```

Es la fuente de la delegación que citan `plan-ejecucion-cierre-manuel-2026-10-24.md` (versión 2) y los planes de Fase 7. Según Manuel (respuesta a la revisión del 2026-10-24):
- un "plan aprobado" de Manuel, en la sesión o como comentario del PR, es aprobación citable de los planes y de los puntos [Plan] y [Manuel];
- Carlos aprueba cada PR con Approve en GitHub, y nada se fusiona sin él.

**Nota (2026-10-25): aprobación de Carlos de todo lo pendiente.** Carlos aprobó todo lo pendiente (planes, Enmienda 1 de ADR-050 partes 1 y 2, Enmienda 1 de la ficha R-6) y pidió a Manuel que fusionara él mismo los PR; comunicado por Manuel en la sesión del 2026-10-25; texto literal pendiente de registrar.
- **Qué abarca** (según Manuel en la misma sesión):
  - los planes R-5 + D1 (#122), D6 (#123), adenda G-9 (#124), C versión 2 (#126), P-R3-3 (#128) y ADR-051 T4 (#131);
  - el plan D, con P-R6D-1 (b) y P-R6D-2 (a);
  - `ADR-050-enmienda-1-iat-del-login.md`, partes 1 y 2;
  - `ficha-R6-enmienda-1.md`.
- **Fuera, según Manuel:** D7 (Carlos no lo nombró) y la Tarea 5 de ADR-051 (falta la enmienda de ADR-021-D).
- Los PR #121 a #131 se fusionaron el 2026-10-25 desde la cuenta de Manuel, por instrucción de Carlos y sin Approve en GitHub (H-1 del informe de la sesión).
- Esta nota sustituye, para esos PR, a la regla "Carlos aprueba cada PR con Approve en GitHub" de la nota anterior. No la borra.

**Nota (2026-10-25, más tarde): confirmación escrita de Carlos.** El comentario literal de Carlos en el PR #134, "todo aprobado" (`Toffy22Cj`, 2026-10-25T01:54:37Z, https://github.com/Toffy22Cj/Donaciones/pull/134#issuecomment-6392174129), es la confirmación escrita de su aprobación de la nota anterior. Además aprueba la parte 3 de la Enmienda 1 de ADR-050, que es el contenido de #134. Carlos fusionó #134 él mismo, a las 01:54:41Z, sin Approve formal en GitHub.


**Nota (2026-10-25) sobre DD-57: ampliación con `PUT` y `DELETE` aprobada (plan CORS, PR #138).** Texto de Manuel en la sesión: "Plan aprobado — CORS (#138), Manuel, 2026-10-25, por la delegación de Carlos. P-CORS-1 y P-CORS-2: Carlos aprobó todo lo pendiente (nota del 2026-10-25 en decisiones-delegadas-2026-10.md), lo que incluye ampliar DD-57 con PUT y DELETE. P-CORS-3 y P-CORS-4: aprobados." Con ello, los métodos permitidos de CORS pasan a ser `GET`, `POST`, `PUT`, `DELETE` y `OPTIONS` (sin `PATCH`); el resto de DD-57 no cambia. La aprobación de Carlos que se cita es la nota de la línea 179 de este archivo, cuyo texto no nombra CORS ni DD-57 de forma expresa; la lectura de que la incluye es de Manuel. La fila de DD-57 y su estado (**RATIFICADA — Carlos, 2026-10-08**) no se reescriben: esta nota la amplía.

**Nota (2026-10-25): delegación de Carlos en Manuel para todo lo que queda del backend, y las 8 decisiones de Manuel. Comunicado por Manuel.**
- **Fuente:** `cierre-backend-2026-10-25.md`, documento de Manuel subido a la sesión de trabajo del 2026-10-25. Su encabezado dice: "Carlos dejó a Manuel todo lo que queda del backend y le pidió que lo decida él. Las decisiones de Manuel de este documento son la aprobación citable. Antes de usarlas hay que registrarlas en `Documentos/decisiones-delegadas-2026-10.md`, como 'comunicado por Manuel'." No hay un texto de Carlos en el repositorio ni en GitHub que lo diga; la delegación consta por Manuel.
- **Las decisiones, literales** (§4 del documento, "LAS DECISIONES DE MANUEL (2026-10-25, por delegación de Carlos)"):

```
1. D7 entra en este corte, tal como lo define ADR-050 D7.
2. El login con Google se pospone, fuera de este corte.
3. G-8 gana `truncated` (opción b), mediante la enmienda E2 de la ficha R-6, con el mismo criterio que G-7.
4. T3 (la portada de la convocatoria, M-08) pasa a ser de Manuel y entra; se calca T2.
5. T5 entra; antes se redacta la enmienda de ADR-021-D (C2 (a)) y la aprueba Manuel.
6. M-22: la imagen del comprobante la generan la web y el móvil; no hay backend nuevo. Solo se comprueba que GET /account/donations trae todos los datos.
7. M-21: una ruta de solo lectura con el total donado, el número de donaciones, las convocatorias apoyadas y las unidades entregadas (nombre propuesto GET /api/v1/account/impact, a confirmar en el plan).
8. DT-B-1 se queda como deuda.
```

- **Detalle de la §2 del mismo documento**, que precisa algunas de ellas:
  - **1 (D7):** "Reutiliza `ReactivateAccountService`. Los tokens anteriores siguen rechazados (D4)."
  - **3 (G-8):** "Hace falta una enmienda a la ficha R-6 (CONGELADA), como E1: la respuesta de G-8 (`ficha-R6-datos-estilo-v2.md:144`) gana `truncated`, igual que G-7 (`:134`) y la de fondos (`:89`)."
  - **4 (T3):** "Se anota la reasignación frente a `ADR-051:299`, que se la daba a Carlos. Se calca T2 (logo) en el módulo `convocatoria`. Solo `ADMINISTRATOR`."
  - **5 (T5):** "Primero se redacta la enmienda de ADR-021-D (C2 (a), ya aprobada; `ADR-051:255`) y Manuel la aprueba. Después, el plan y el código."
  - **6 (M-22):** "Si falta alguno, se anota como hallazgo y se pregunta a Manuel."
- **Qué autoriza:** son decisiones de **qué** entra. **No** autorizan código. Cada tarea necesita un "plan aprobado" de Manuel, citable, sobre `plan-cierre-backend-2026-10-25.md` (regla 3.4; nota del 2026-10-24 de este archivo). T5 necesita además que Manuel apruebe la enmienda de ADR-021-D.
- **Nombre "E2":** `ficha-R6-enmienda-1.md:77` ya usa "E2" para G-9. Para no confundirlas, el cambio de G-8 se redacta como **Enmienda 2 de la ficha R-6** (`ficha-R6-enmienda-2.md`).
- **Sustituye en parte:**
  - **D7:** la nota del 2026-10-25 de la línea 185 ("Fuera, según Manuel: D7") y la nota del plan D6 (`plan-d6-desactivacion-propia.md:5`) dejaban D7 fuera. La decisión 1 lo mete en el corte. Las dos notas se conservan.
  - **T5:** la misma línea 185 dejaba la Tarea 5 de ADR-051 fuera por faltar la enmienda de ADR-021-D. La decisión 5 la mete, con esa enmienda antes.
  - **T3:** `ADR-051:299` sugería a Carlos como responsable. La columna dice "Responsable sugerido", así que no es una contradicción.

**Nota (2026-10-25, más tarde): "plan aprobado" de Manuel sobre el cierre del backend.** Fuente citable: comentario de `buelvasmanuel` en #147, 2026-10-25T22:35:54Z, https://github.com/Toffy22Cj/Donaciones/pull/147#issuecomment-6404356890. El comentario dice que lo publicó el agente local de Manuel a petición suya. #147 se fusionó en `develop` como `6b54630`. Texto literal:

```
**Plan aprobado:** `plan-cierre-backend-2026-10-25.md`, con estas respuestas:
- **P-E2-2:** se acepta en este corte que, por encima de 10 000, G-8 muestre los fondos y activos más antiguos; `truncated` lo avisa. Queda como deuda; no se cambian los puertos de los directorios.
- **P-M21-3:** "donado" cuenta solo las donaciones `CONFIRMED` (DD-46).
- **P-M21-4:** se acepta el conteo por activo. La métrica se presenta como "unidades entregadas en las convocatorias que apoyaste", no como unidades atribuidas al donante.
- **Resto de puntos** (P-D7-1 a 4, P-E2-1, P-M21-1, 2 y 5, P-T3-1 a 5, P-T5-1 a 4): aprobados como propone el plan.

**Aprobadas:**
- la **Enmienda 2 de la ficha R-6** (`ficha-R6-enmienda-2.md`: `truncated` en G-8, con el criterio de G-7);
- la **enmienda de ADR-021-D** (`ADR-021-D-enmienda-1-huella-evidencia.md`, P-21D-1 a 4): la huella va en el paso `ASSET_DELIVERED` del historial. En P-21D-4 se permite el `parentAssetRef` opaco, porque así lo aprobó la ficha R-6 (G-4); la enmienda tiene que decirlo de forma explícita.

**M-22:** se cierra en el backend sin cambios. El PDF del 16 de octubre ("Funciones de la app móvil y pantallas del empleado") no lista campos; los define el diseño de Carlos. Si ese diseño necesita un dato que no esté en los 11 campos de `GET /account/donations`, se abre como ítem nuevo.
```

- **Autoriza:**
  - el código de D7, G-8, M-21, T3 y T5, en ese orden y con un PR por tarea;
  - para T5, también la enmienda de ADR-021-D.
- **Deuda nueva:** G-8 por encima de 10 000 (P-E2-2), sin cambiar los puertos de los directorios.

**Nota (2026-10-26): decisiones de Manuel sobre M-21 y T5, y autorización de la regla 3.5 para dos puertos.** Fuente citable: comentario de `buelvasmanuel` en #147, 2026-10-26T02:06:07Z, https://github.com/Toffy22Cj/Donaciones/pull/147#issuecomment-6406618994. Texto literal:

```
1. **M-21 "Mi impacto" (bloqueo por `DonationIntentReadPort.DonationView` sin `campaignRef`, `convocatoria/.../DonationIntentReadPort.java:28-30`): opción (a) APROBADA.**
   - Se añade `campaignRef` a `DonationView`. Es un cambio aditivo del puerto, que también usa `app`.
   - Esta nota es la autorización de reglas-equipo §3.5 para ese puerto.
   - Se descarta (b), sacarlo del fondo, porque dejaría sin contar las donaciones `CONFIRMED` sin fondos aplicados.

2. **T5 evidencia, H-T5-1 (`TrackingLineageAndAnchorHttpIntegrationTest.java:246`: una sola consulta al Event Store por historial): opción (a) APROBADA.**
   - `StepAnchor` (contracts) gana `evidenceRef`. Se rellena en la misma consulta que ya hace `app`.
   - Es un cambio aditivo de un puerto de `contracts`. Esta nota es su autorización §3.5.
   - La medición del Bloque C **no se relaja**: se descartan (b), la caché por petición, y (c), aceptar dos consultas.

Antes del código, hay que registrar estas dos decisiones con una nota fechada en `Documentos/decisiones-delegadas-2026-10.md` y en `plan-cierre-backend-2026-10-25.md`.

El hallazgo de la deduplicación (`MediaUploadService.java:67`, que no mira `deletedAt`) queda fuera de este trabajo. Se anota como deuda, pendiente de decisión aparte.
```

- **Deuda nueva:** la deduplicación de `MediaUploadService.java:67` no mira `deletedAt`, así que volver a subir los mismos bytes de una foto retirada devolvería el metadato retirado. Queda fuera de este trabajo, pendiente de decisión aparte.

**Nota (2026-10-26): decisiones de Manuel sobre tres deudas, "comunicado por Manuel".** Las tomó Manuel por delegación de Carlos. Google (R-6 de ADR-050) no entra: se hará aparte. El "cómo" va en `plan-deudas-1-a-3-2026-10-26.md`, que espera un "plan aprobado" citable de Manuel, y no hay código antes de esa aprobación.

- **D-1. Fotos retiradas y la deduplicación** (hallazgo de T5, `estado-fase6.md` §0.64; `MediaUploadService.java:65-70`):
  - si alguien vuelve a subir exactamente los mismos bytes de una evidencia de entrega que la plataforma retiró por F7 (con `deletedAt`), la subida se **rechaza** con un error claro;
  - ni se reutiliza el metadato retirado ni se guarda como foto nueva: F7 no se puede saltar subiendo la misma imagen;
  - el plan propone el código HTTP y el nombre del error, y comprueba primero si sirve el 410 `EvidenceRemoved` que ya existe. Comprueba también si el logo y la portada tienen el mismo problema; hoy solo `DELIVERY_EVIDENCE` deduplica.
- **D-2. DT-B-1** (`estado-fase6.md` §0.53; `MongoEventStoreAdapter.java:96`): **se arregla**. `recordedAt` se escribe con precisión fija para que el orden como texto sea cronológico.
  - **Restricción dura:** el Event Store es de solo escritura y los eventos ya guardados **no se reescriben**.
  - Antes de proponer nada, el plan comprueba con ruta:línea si `recordedAt` entra en el hash del evento, en el lote Merkle o en el ancla. Si entra, el formato de los eventos ya guardados no puede cambiar y el plan debe tolerarlos.
  - El plan elige cómo convivir con los datos viejos y lo justifica.
- **D-3. P-E2-2** (`ficha-R6-enmienda-2.md:45`): G-8 pasa a mostrar **lo más reciente** cuando hay más de 10 000.
  - **Se autoriza (reglas-equipo §3.5)** añadir a los puertos de los directorios un método **nuevo** de lectura descendente.
  - Los métodos existentes y G-7 no cambian, y `truncated` se mantiene.
  - Hace falta una Enmienda 3 de la ficha R-6, que está CONGELADA: `ficha-R6-enmienda-3.md`.
- **Anotado al registrarlo, sin resolver:** D-2 cita ADR-025 para "el Event Store es de solo escritura", pero ADR-025 habla del Audit Log de `identity` (`ADR-025-persistencia-identidad.md:19-20`). La inmutabilidad del Event Store está en `documento-maestro-proyecto.md:18` y `:499`. La restricción vale igual.

**Nota (2026-10-26): plan de las deudas 1 a 3 APROBADO por Manuel**, por delegación de Carlos. Fuente citable: comentario de `buelvasmanuel` en #153, 2026-10-26T05:48:54Z, https://github.com/Toffy22Cj/Donaciones/pull/153#issuecomment-6409194078.
- **Qué aprueba:** `plan-deudas-1-a-3-2026-10-26.md` y la Enmienda 3 de la ficha R-6 (`ficha-R6-enmienda-3.md`).
- **Deuda 1:**
  - P-DB1-1: el rechazo usa el 410 `EvidenceRemoved` que ya existe;
  - P-DB1-2: la comprobación se hace también en el camino de la carrera;
  - **P-DB1-3:** el logo y la portada quedan **fuera de este corte, como deuda registrada**. Hoy, volver a subir los mismos bytes de un logo o una portada retirados crea un objeto nuevo. Se acepta así porque los sube el administrador de la organización, que de todos modos puede subir otra imagen, y arreglarlo pide un índice nuevo. La nota fechada está en `estado-fase6.md` §0.64.
- **DT-B-1:** P-DT1-1 a 4, como se proponen. Los eventos viejos se toleran al leer y los nuevos se escriben con 9 decimales fijos.
- **P-E2-2:** P-PE2-1 a 3, como se proponen.
  - Se autorizan (§3.5) `findLatestFundIdsByOrganization` y `findLatestAssetIdsByOrganization`.
  - Los métodos existentes y G-7 no cambian.
- **La contradicción anotada:** la cita de ADR-025 era errónea, porque ADR-025 trata del Audit Log de `identity`. La inmutabilidad del Event Store se apoya en `documento-maestro-proyecto.md:18` y `:499`. La restricción no cambia: los eventos guardados no se reescriben.

**Nota (2026-10-29): tres decisiones de Carlos y de Manuel, "comunicado por Manuel".** Llegan en el encargo del 2026-10-29 de Manuel ("Trabajo de Carlos"). Se registran antes de cualquier código (regla 3.4).

- **C-1. Enmienda 1 de ADR-050, partes 1 y 2: aprobación escrita de Carlos.** Carlos aprobó por escrito las partes 1 y 2 y **acepta que `iat = max(segundo actual truncado, credentialsNotBefore)` se quede en `LoginController`, como dice E1-2** (`ADR-050-enmienda-1-iat-del-login.md:51-53`), en lugar de moverlo a `identity`.
  - **Texto literal: [texto cortado].** El texto que llegó a Manuel estaba cortado en "identit", y en esta sesión no se recibió ni siquiera ese fragmento. Queda pendiente de que Manuel lo pegue completo; se pide en el PR. Cuando llegue, se registra en una nota nueva debajo de esta.
  - Completa la nota del 2026-10-25 de la línea 179 de este archivo ("texto literal pendiente de registrar"), que se conserva.
- **C-2. Plan R-1 (búsqueda y descubrimiento de convocatorias): APROBADO por Carlos.** Texto literal de Carlos: "plan-r1-busqueda-descubrimiento.md: PROPUESTO, nunca aprobado. Pásalo aprobado". Además: "Todo está aprobado". Es la aprobación citable del plan (regla 3.4) para el código de R-1.
- **C-3. Enmienda 2 de ADR-050: Carlos deja a Manuel a cargo, y Manuel cierra Q1 a Q5:**
  - **Q1 (a):** sin correo verificado no se inicia sesión.
  - **Q2 (a):** las cuentas existentes quedan sin verificar; la semilla y el administrador de plataforma nacen verificados.
    - **Condición:** el PR de código avisa a la web y al móvil **antes de desplegar**, porque las cuentas reales existentes quedarán bloqueadas hasta verificar el correo.
  - **Q3:** 24 h.
  - **Q4:** sí hay aviso al desactivar.
  - **Q5:** sin IP ni dispositivo.
  - **Qué autoriza:** son decisiones de **qué** entra; **no** autorizan código. El código necesita el plan corto `plan-adr050-enmienda-2.md` aprobado por Manuel con un comentario citable.
- **R-7 (perfil de la organización): no se empieza.** Carlos dijo antes "no lo empieces"; Manuel lo confirma con él. La ficha R-7 solo se sube, con sus parches, en un PR de documentos aparte si Manuel lo pide.

**Nota (2026-10-29, más tarde): precisión de Manuel sobre C-3 y fuentes recibidas.**
- La condición de Q2 queda así: el PR de código de la Enmienda 2 avisa a la web y al móvil de que **no se despliega hasta que manejen el 403 `EmailNotVerified`**.
- La Enmienda 2 autoriza cambiar `AuthenticateAccountPort` (E2-3, regla 3.5).
- R-7 no se empieza hasta que Carlos lo confirme.
- Las fuentes de Carlos llegaron en la rama `wip/fuentes-carlos-2026-10-29` (carpeta `fuentes-carlos/`, que no se fusiona). El texto literal de C-1 sigue pendiente ("[texto cortado]").

**Nota (2026-10-29, más tarde): texto literal de C-1 y C-5, de Carlos, comunicados por Manuel.**
- **C-1, texto completo.** Sustituye a "[texto cortado]" de la nota del 2026-10-29 (C-1, línea 294 de este archivo), que se conserva:

  > "Apruebo la Enmienda 1 de ADR-050 (ADR-050-enmienda-1-iat-del-login.md), partes 1 y 2. Acepto que iat = max(segundo actual truncado, credentialsNotBefore) se calcule en LoginController, como dice E1-2, y no se mueva a identity"
- **C-5, que sustituye a C-4.** Texto literal de Carlos:

  > "Confirmo que R-7 se puede empezar. La ficha R-7 está CONGELADA (P1 a P8 según la recomendación, P9 no). Manuel aprueba el plan corto con la delegación de siempre antes de escribir código. El archivo DIVIPOLA lo descarga una persona desde el DANE."

  **Qué autoriza:** subir la ficha R-7 (`ficha-R7-perfil-organizacion.md`, autor Carlos) y escribir `plan-r7-perfil-organizacion.md`. **No autoriza código:** el código espera el "plan aprobado" de Manuel en un comentario citable. Ningún agente descarga el archivo DIVIPOLA.
- **Aprobaciones de Manuel ya publicadas,** que se registran aquí:
  - el plan de la Enmienda 2 (#158, https://github.com/Toffy22Cj/Donaciones/pull/158#issuecomment-6504627552), con P-E2V-1 a 12 como se recomiendan;
  - las dos observaciones de R-1 (#159, https://github.com/Toffy22Cj/Donaciones/pull/159#issuecomment-6504628005).

**Nota (2026-10-29): "plan aprobado" de Manuel sobre la Enmienda 2 de ADR-050.** Fuente citable: comentario de `buelvasmanuel` en #158, 2026-10-29T18:09:13Z, https://github.com/Toffy22Cj/Donaciones/pull/158#issuecomment-6504627552. Aprueba `plan-adr050-enmienda-2.md`.
- **P-E2V-1 a P-E2V-12:** aprobados tal como los recomienda el plan.
- **P-E2V-2:** las cuentas que crea el sistema (semilla y tests, `CreateAccountService`) nacen verificadas.
- **P-E2V-3:** se acepta el riesgo. Los administradores de plataforma que ya existen **no** se marcan como verificados al desplegar, por coherencia con Q2 (a): recuperan el acceso con "Reenviar" o con la recuperación. **Condición:** antes de desplegar, el SMTP de producción tiene que estar comprobado. Va en el aviso del PR de código.
- **P-E2V-10:** los avisos llevan la hora de America/Bogota; el correo de recuperación (D8) no se toca.
- **Autoriza** el código, en un PR que sale de la rama de #158 ("depende de #158"), con el cambio de `AuthenticateAccountPort` (E2-3, regla 3.5).

**Nota (2026-10-29): P-E2V-13 decidido por Manuel, opción (a).** Fuente citable: comentario de `buelvasmanuel` en #163, 2026-10-29T19:43:28Z, https://github.com/Toffy22Cj/Donaciones/pull/163#issuecomment-6506647217.
- El actor de `EMAIL_VERIFIED` es la propia cuenta (`AccountAuditActor`).
- Se añade `EmailVerificationService` a la regla de `identity/src/test/java/identity/architecture/ArchitectureTest.java:67-88` que fija quién puede construir ese actor, con el precedente de `ChangeCredentialsService` (Enmienda 1 de ADR-050, parte 2). La regla cita esta decisión y E2-2.
- **Es una ampliación de ADR-038 §2.2** para esta clase. No se relaja ninguna otra regla.

**Nota (2026-10-30): se retoma R-6 de ADR-050, el login con Google, "comunicado por Manuel".**
- **G-0.** Se retoma R-6 (D9 y los tests 27 a 29). **Sustituye a la decisión 2 del 2026-10-25** ("El login con Google se pospone, fuera de este corte", línea 200 de este archivo), que se conserva.
- **G-1.** Los ID de cliente ya existen en Google Cloud: web, Android `com.paxfide.mobile` y Android demo `com.paxfide.mobile.demo`.
  - **No van con valores reales en el repositorio,** ni en `application.yml` ni en los tests: `GOOGLE_OAUTH_CLIENT_IDS` se pone por entorno al desplegar (ADR-050 D9; `plan-ejecucion-cierre-manuel-2026-10-24.md:287`).
  - El secreto del cliente no se usa ni se configura.
- **G-2.** Dato de Manuel, **sin verificar en el código del móvil:** la app Flutter usará `google_sign_in` con `serverClientId` igual al ID web, así que el `aud` del `idToken` del móvil será normalmente el ID web. El backend acepta cualquier `aud` de la lista configurada.
- **Qué autoriza:** escribir `plan-adr050-r6-google.md`. **No autoriza código:** el código espera el "plan aprobado" de Manuel en un comentario citable.
