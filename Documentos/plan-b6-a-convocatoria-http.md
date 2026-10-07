# Plan B6-a — HTTP de convocatoria: crear, asignar responsable, detalle público y verificar organización

**Estado:** **EN EJECUCIÓN bajo la autorización de trabajo autónomo de Carlos (2026-10-07)**, que exceptúa temporalmente las reglas 1 y 3.4. Las preguntas de §7 quedan resueltas en §8: Q-B6A-1 por Carlos (opción (a)), y el resto como `[DECISIÓN DELEGADA — pendiente de ratificar por Carlos]` (`decisiones-delegadas-2026-10.md`).
**Origen:** `propuesta-d-api.md` (APROBADO), §3 (B6-a: CV-01, CV-02, CV-07, verificar organización), Q7 (CV-07 con `acceptedPaymentMethods` y el nombre de la organización), Q8 (CV-01 cerrada) y la decisión de Carlos de que **D-7 a D-9 entran en B6-a**; `ficha-CV-01-crear-convocatoria.md` (CONGELADO); `plan-b6-0-base-http.md` (HECHO).
**Depende de:** B6-0 (fusionado en #58).
**Revisión:** cubierto por la excepción a la regla 3.2.

---

## 1. Hechos del código (`develop` tras #58)

- **Módulos.** `api` depende solo de `core` y `contracts`. `app` depende de todos. Los cuatro endpoints cruzan módulos (`convocatoria` o `identity` más el actor del JWT), así que **van en `app.web`** (convención de B6-0 §2.4). Las traducciones de errores de `convocatoria` e `identity` también van en `app.web`, como beans `ApiErrorMappings` propios: `api` no puede ver esas excepciones.
- **CV-01.**
  - `ConvocatoriaLifecycleService.createConvocatoria(CreateConvocatoriaCommand)` → `CreateConvocatoriaResult(campaignRef, publicCode)`.
  - La idempotencia la hace `IdempotentCommandExecutor`: guarda exactamente `{campaignRef, publicCode}` y, si se repite el `commandId` con otro tipo de comando, lanza `CommandIdReusedForDifferentCommandException`.
  - La autorización (`ConvocatoriaAuthorizationPolicy.requireAdministratorOf`) se evalúa antes del reclamo.
  - `ConvocatoriaConfiguration` valida al construirse, así que sus excepciones salen al construir el comando, antes de llamar al servicio.
- **Deudas de la ficha CV-01, verificadas en el código:**

| Deuda | Situación actual |
|---|---|
| D-1 | `currency` solo se comprueba no vacía |
| D-2 | No hay comprobación de `now − 5 min` (el servicio ya tiene un `Clock`, pero solo lo usa para la auditoría) |
| D-3 | `PUBLIC_CODE_LENGTH = 10`, es decir, 50 bits |
| D-7 | Se aceptan medios de pago en una convocatoria solo `IN_KIND` |
| D-8 | Una `visibility` nula da un `NullPointerException` en el constructor (sería un 500) |
| D-9 | `description` no tiene límite |

- **CV-02.**
  - `ResponsibleAssignmentService.assignEmployee(AssignEmployeeToCampaignCommand)` → `AssignmentResult(assignmentId)`.
  - **Primero carga la convocatoria** (`CampaignNotFoundException`) y **después** autoriza. Un 404 frente a un 403 revelaría si existe una convocatoria de otra organización.
  - El destinatario se valida con `requireRecipient`. Si no es `EMPLOYEE` de la organización, lanza `InvalidResponsibleRecipientException`, que es una excepción nombrada.
  - Lo que hace `IdentityPrincipalPort.resolvePrincipal` con una cuenta **inexistente o `INACTIVE`** no está traducido: hoy daría 500.
  - No hay regla para asignar en una convocatoria `CLOSED`. Es el bloqueo "ABIERTO-dominio" de `propuesta-apis-fase6.md:57`.
- **CV-07.**
  - No existe `ConvocatoriaReadPort`. Lo que hay es `ConvocatoriaRepositoryPort.findByPublicCode` y `CampaignFundingLedgerRepositoryPort.findByCampaignRef` (`clearedAmount`).
  - **`Organization` no tiene nombre:** ni el modelo ni `OrganizationDocument` tienen ese campo, y `CreateOrganizationService` no lo recibe. El puerto "solo el nombre de la organización" de Q7 no tiene de dónde leer (Q-B6A-1).
- **Verificar organización.**
  - `VerifyOrganizationService.verifyOrganization(AuthorizationPrincipal, OrganizationId)` devuelve `void` y **no recibe `commandId`**.
  - Lanza `InsufficientPlatformAuthorityException`, `OrganizationNotFoundException` o `InvalidVerificationTransitionException` (si ya está `VERIFIED` o `REJECTED`).
  - Ruta de la matriz: `POST /api/v1/platform/organizations/{id}/verify` → `{organizationId, verificationStatus}`.
- **`CampaignNotFoundException` tendrá dos significados.** En las operaciones de administración (CV-02) debe responder igual que "otra organización". En la lectura pública por `publicCode` (CV-07, y CV-11 en B6-b) es un 404 público. Como cada excepción tiene **una sola** traducción (Q-B60-4), CV-07 no la usa: lee un `Optional` y lanza su propia excepción (§2.3).

## 2. Cambios

### 2.1 CV-01 — `POST /api/v1/organizations/{organizationId}/campaigns`

- **Controlador** `app.web.campaign.CampaignAdministrationController`. Recibe `@CurrentActor HumanActor` y `@CommandId String`, y responde `201` con exactamente `{campaignRef, publicCode}`.
- **Cuerpo anidado** (Q-CV01-12): `title`, `description`, `visibility`, `startDate` y `endDate` arriba; dentro de `configuration`, `acceptedDonationTypes`, `acceptedPaymentMethods`, `currency`, `targetAmount`, `targetPolicy` y `onTargetReached`. El DTO de petición solo traduce:
  - **fechas como texto**, con el patrón ISO-8601 UTC terminado en `Z` (`yyyy-MM-ddTHH:mm:ss[.fffffffff]Z`). Jackson aceptaría también desplazamientos como `+02:00`, y la ficha exige `Z`;
  - **`targetAmount` como texto de dígitos** que quepa en un `long`;
  - **enums con los literales del dominio**; un valor desconocido es un cuerpo ilegible;
  - cualquiera de estos fallos → **400** con la excepción nueva `InvalidRequestFieldException` (en `api.web`, traducida en el registro de `api`). El cuerpo no repite el valor recibido.
- **Duplicado:** mismo `201` y mismo cuerpo, porque el ejecutor devuelve el resultado guardado.

### 2.2 CV-02 — `POST /api/v1/campaigns/{campaignRef}/employees`

- **Controlador** en `CampaignAdministrationController`, con `@CurrentActor HumanActor`, `@CommandId` y el cuerpo `{employeeRef}`. Respuesta según Q-B6A-3.
- **No revela si existe la convocatoria:** `CampaignNotFoundException` se traduce **igual que** `ActorNotInCampaignOrganizationException` y `ActorRoleNotAllowedException` (el mismo 403 que `core`, Q-B60-5; si Q-B60-6 cambia a 404, cambian las tres a la vez). Es la regla de Carlos: "autorizar primero… y consultar la convocatoria después; hacia fuera, dar la misma respuesta a `CAMPAIGN_NOT_FOUND` y a `OTHER_ORGANIZATION`". Aquí no se puede autorizar antes, porque la organización sale de la convocatoria; por eso se iguala la respuesta.
- **Destinatario inválido, inexistente o `INACTIVE`** y **convocatoria `CLOSED`**: según Q-B6A-2.

### 2.3 CV-07 — `GET /api/v1/public/campaigns/{publicCode}` (pública, ya en `PublicRoutes`)

- **`convocatoria`:**
  - puerto de entrada `ConvocatoriaReadPort.findPublicByCode(publicCode)` → `Optional<PublicCampaignView>`;
  - lo implementa `PublicCampaignQueryService` con `ConvocatoriaRepositoryPort` y, si la convocatoria acepta `MONETARY`, `CampaignFundingLedgerRepositoryPort` para `clearedAmount`. Sin un modelo de lectura nuevo (`propuesta-apis-fase6.md:59`).
- **Campos** (Q7, opción (b)): `title`, `description`, `status`, `startDate`, `endDate`, `acceptedDonationTypes`, `acceptedPaymentMethods`, `currency`, `targetAmount` y `clearedAmount` (los dos últimos como texto de dígitos, y solo con `MONETARY`), más el nombre de la organización según Q-B6A-1.
  - **Excluidos:** `publicCode`, `targetPolicy`, `onTargetReached`, `campaignRef`, `organizationRef`, `configurationVersion` y cualquier id interno.
  - Los nulos se omiten con `@JsonInclude(NON_NULL)` en el DTO (Q-B60-2).
  - **Pendiente de cotejar con la ficha CV-07**, que no está en el repositorio (Q-B6A-5).
- **No encontrado:** el controlador lanza `PublicCampaignNotFoundException` (en `app.web`) → **404** con cuerpo fijo. El mismo para un código inexistente y para uno mal formado (ni siquiera se consulta: el código no cumple el formato de D-3).
- **Las `PRIVATE_LINK` se devuelven** por su código, que hace de secreto (A6). **Las `CLOSED` también**, con su `status`.
- **El `publicCode` es un secreto bearer** (ADR-041 §2.7) y viaja en la ruta, porque así lo define la matriz:
  - ningún log de B6-a incluye la ruta ni el código: `ApiExceptionHandler` no registra la ruta y el log de acceso de Tomcat está desactivado;
  - un test de captura de logs lo comprueba en el 200, en el 404 y en un 500 forzado.

### 2.4 Verificar organización — `POST /api/v1/platform/organizations/{organizationId}/verify`

- **Controlador** `app.web.platform.OrganizationVerificationController`, con `@CurrentActor AuthorizationPrincipal`. Responde `200` con `{organizationId, verificationStatus}`.
- **Traducciones:**

| Excepción | Respuesta |
|---|---|
| `InsufficientPlatformAuthorityException` | 403, el mismo cuerpo |
| `OrganizationNotFoundException` | 404 (solo la ve un administrador de plataforma, que puede saber qué organizaciones existen) |
| `InvalidVerificationTransitionException` | 409 |

- **`Command-Id`:** según Q-B6A-4.

### 2.5 Deudas D-1, D-2, D-3, D-7, D-8 y D-9 (en `convocatoria`, cada una con su test de regresión primero)

| Deuda | Corrección | Excepción nombrada (regla 2.6) |
|---|---|---|
| D-1 | `currency` debe ser un código ISO 4217 de tres letras mayúsculas reconocido por `java.util.Currency` | `InvalidCampaignCurrencyException` (nueva) |
| D-2 | `startDate` y `endDate` no anteriores a `now − 5 min`, con el `Clock` del servicio. Solo al **crear**: es una validación de creación, no una máquina de estados (Q-CV01-9a) | `CampaignDateInPastException` (nueva) |
| D-3 | `PUBLIC_CODE_LENGTH = 26` (130 bits); se ajusta `CreateConvocatoriaIntegrationTest.java:68` | — |
| D-7 | Medios de pago en una convocatoria solo `IN_KIND` → rechazo, **igual que el resto de campos monetarios** (Q-CV01-13) | Se reutiliza `MonetaryTermsWithoutMonetaryDonationTypeException` |
| D-8 | `visibility` obligatoria | `CampaignVisibilityRequiredException` (nueva) |
| D-9 | `description` de 5000 caracteres como máximo | `CampaignDescriptionTooLongException` (nueva) |

- D-1 y D-7 viven en `ConvocatoriaConfiguration`, así que también afectan a `editConfiguration`. Es correcto: es la misma invariante.

### 2.6 Traducción de errores de `convocatoria` e `identity`

- Dos beans nuevos en `app.web`: `ConvocatoriaApiErrorMappings` e `IdentityApiErrorMappings`.
  - Validaciones del cuerpo → **400**, con `title` igual al nombre de la regla (`CampaignTitleTooLong`, `InvalidCampaignCurrency`…). El nombre ayuda al cliente y no repite ningún valor.
  - `OrganizationNotVerifiedException`, `CommandIdReusedForDifferentCommandException` y `ResponsibleAlreadyActiveInCampaignException` → **409**.
  - Las de autorización y `CampaignNotFoundException` (§2.2) → el **mismo 403** que `core`.
- **Ninguna traducción genérica por la clase base `ConvocatoriaDomainException`** (ADR-041 §2.5: la API no inventa códigos para excepciones no nombradas).
- **Test de exhaustividad:** recorre por reflexión todas las subclases concretas de `ConvocatoriaDomainException` y las excepciones de `identity` que lanzan los servicios de B6-a. Cada una tiene que estar traducida o en una lista explícita de "fuera de B6-a", con su motivo. Una excepción nueva sin decidir rompe el build, en vez de acabar en un 500 silencioso.

## 3. Tests (primero en rojo, con la salida de Surefire en el PR)

| # | Caso | Tipo |
|---|---|---|
| 1 | CV-01 éxito `MONETARY` y solo `IN_KIND`: `201` con **exactamente** `{campaignRef, publicCode}`; `publicCode` de 26 caracteres del alfabeto | `@SpringBootTest` + Testcontainers |
| 2 | CV-01 duplicado con el mismo `Command-Id` → mismo código y mismo cuerpo byte a byte, un solo efecto (convocatoria, ledger y audit log contados) | ídem |
| 3 | CV-01 con un `Command-Id` de otro comando → 409 | ídem |
| 4 | CV-01: organización ajena e inexistente → **el mismo** 403, byte a byte; organización no `VERIFIED` → 409 | ídem |
| 5 | CV-01: cada fila 400 de la ficha §3.4, **incluidas D-1, D-2, D-7, D-8 y D-9**, fechas sin `Z`, `targetAmount` no numérico o fuera de `long`, enum desconocido; con aserción negativa de que no se escribió nada (convocatoria, ledger, audit log, registro de comando) | ídem |
| 6 | D-2 en el límite: `now − 5 min` aceptado, `now − 5 min − 1 s` rechazado (`Clock` fijo) | unitario de dominio |
| 7 | CV-02: éxito; duplicado → mismo cuerpo; convocatoria inexistente, de otra organización y actor sin rol → **el mismo** 403; destinatario inválido, inexistente o `INACTIVE` y convocatoria `CLOSED` según Q-B6A-2 | `@SpringBootTest` |
| 8 | CV-07 contra Tomcat real: campos exactos (`MONETARY` con `clearedAmount`, `IN_KIND` sin campos monetarios), excluidos ausentes, `PRIVATE_LINK` y `CLOSED` devueltos, código inexistente y mal formado → el mismo 404, sin JWT | `RANDOM_PORT` + `RawHttp` |
| 9 | CV-07: el `publicCode` no aparece en ningún log, ni en el 200 ni en el 404 ni en un 500 forzado | captura de logs |
| 10 | Verificar: éxito → `{organizationId, VERIFIED}`; sin autoridad de plataforma → 403; inexistente → 404; ya verificada → 409 (o lo que decida Q-B6A-4) | `@SpringBootTest` |
| 11 | Exhaustividad de traducciones (§2.6) | unitario por reflexión |
| 12 | Inventario de rutas de B3: las rutas nuevas protegidas se añaden a `PROTECTED`; CV-07 ya era pública | existente, ampliado |
| 13 | ArchUnit de `app.web` (B6-0) en verde con los controladores reales | existente |

**Mutaciones** (con `scripts/mutaciones.py`):
- devolver un 404 distinto para `CampaignNotFoundException` en CV-02;
- añadir `status` a la respuesta de CV-01;
- quitar cada corrección de deuda (D-1, D-2, D-7, D-8 y D-9, una por una);
- volver a `PUBLIC_CODE_LENGTH = 10`;
- incluir `targetPolicy` o `campaignRef` en CV-07;
- aceptar fechas sin `Z`;
- traducir `ConvocatoriaDomainException` de forma genérica.

## 4. Entrega

- **Un PR, `feat/b6-a-convocatoria-http`**, que toca `convocatoria` (deudas y `ConvocatoriaReadPort`), `api.web` (`InvalidRequestFieldException`), `app.web` (controladores y traducciones) e `identity` solo si Q-B6A-1 o Q-B6A-4 lo piden.
- **Commits:**
  1. tests en rojo con esqueleto;
  2. deudas de dominio;
  3. lectura pública;
  4. controladores y traducciones;
  5. documentos y evidencia (HECHO después de la evidencia, regla 2.4).
- **Si Q-B6A-2 tarda,** CV-02 sale de este PR a un **B6-a2**, y CV-01, CV-07 y la verificación siguen. El golden path (paso 2) necesita CV-02, así que B6-a2 no puede esperar a después de B6-b.

## 5. Fuera de alcance

- CV-03 (designar administrador), retirar responsable, cerrar convocatoria, editar configuración por HTTP y el listado de convocatorias.
- Rate limiting (DH-56), CORS y OpenAPI.
- La incorporación normativa de la ficha CV-01 a la matriz (necesita autorización aparte).

## 6. Riesgos

- **Sin `-parameters`** (hallazgo de B6-0): todos los `@PathVariable` llevarán nombre explícito; un test de cada ruta lo cubre.
- **`IllegalArgumentException` → 400** (tabla de B6-0): un `IllegalArgumentException` interno de `convocatoria` o `identity` se vería como un error del cliente. El test de exhaustividad no lo cubre, porque no es una excepción nombrada. Se registra; no se cambia sin decisión.

## 7. Preguntas para Carlos

| # | Pregunta | Recomendación |
|---|---|---|
| Q-B6A-1 | **`Organization` no tiene nombre.** El puerto de Q7 no tiene qué devolver. ¿(a) Añadir `name` a `Organization` (lo recibe `CreateOrganizationService`; enmienda corta de ADR-038) y el puerto `OrganizationPublicNamePort` en `contracts`; o (b) CV-07 sin nombre de organización en B6-a, que entra cuando exista el campo? | **(a)** si la demo enseña quién pide la donación, que es lo que motivó Q7. Coste: un campo, un parámetro en la creación y la enmienda. Si el plazo del 21 aprieta, **(b)**, que deja CV-07 completa salvo esa línea |
| Q-B6A-2 | **CV-02:** (i) destinatario que no es `EMPLOYEE` de la organización, **inexistente o `INACTIVE`**: ¿la misma respuesta para los tres? (ii) ¿Se puede asignar en una convocatoria `CLOSED`? | (i) **Sí, el mismo 409** `InvalidResponsibleRecipient`: así un administrador no puede averiguar qué cuentas existen en otras organizaciones; requiere traducir en `requireRecipient` lo que lanza `resolvePrincipal`. (ii) **No: 409** `CampaignClosed`, con una excepción nombrada nueva. Es una regla de dominio nueva sobre la Enmienda 1 de ADR-037 §4, y queda registrada allí |
| Q-B6A-3 | **Respuesta de CV-02:** la matriz dice `{campaignRef, accountId, status}`; el código guarda `{assignmentId}` | `201` con `{assignmentId}`: es lo que se guarda para los duplicados, así que el cuerpo repetido es idéntico sin cambiar el ejecutor. La matriz se corrige |
| Q-B6A-4 | **Verificar organización y `Command-Id`:** el servicio no recibe `commandId`, y un segundo intento da `InvalidVerificationTransition`. ¿(a) Exento de `Command-Id`, como el login y el webhook, con 409 en el reintento; o (b) `Command-Id` obligatorio con idempotencia en `identity`? | **(a)**: el estado ya hace la operación idempotente en la práctica (no puede verificarse dos veces), y (b) cambia `identity` sin que la demo lo necesite. Q9 solo revirtió T-33 para `core` |
| Q-B6A-5 | **Ficha CV-07:** no está en el repositorio. Los campos de §2.3 salen de A6 con la opción (b) de Q7. ¿Me la pasas para cotejarla, o vale la lista de §2.3? | Pasármela; si no llega antes de empezar el código, vale §2.3 y la cotejo al sincronizar |

## 8. Respuestas (2026-10-07)

| # | Respuesta | Origen |
|---|---|---|
| Q-B6A-1 | **(a):** puerto de `identity` con el nombre de la organización (Carlos: "el puerto de identity con el nombre de la organización"). Detalles: `name` opcional en `Organization` (máx. 200), `OrganizationPublicNamePort` en `contracts` con `Optional<String>`, CV-07 omite el campo sin nombre | Carlos, 2026-10-07; detalles `[DECISIÓN DELEGADA — pendiente de ratificar por Carlos]` (DD-04) |
| Q-B6A-2 | (i) el mismo 409 `InvalidResponsibleRecipient` para no `EMPLOYEE`, inexistente e `INACTIVE`; (ii) 409 `CampaignClosed` | `[DECISIÓN DELEGADA — pendiente de ratificar por Carlos]` (DD-05) |
| Q-B6A-3 | `201 {assignmentId}`; se corrige la matriz | `[DECISIÓN DELEGADA — pendiente de ratificar por Carlos]` (DD-06) |
| Q-B6A-4 | (a) exento de `Command-Id`; 409 en el reintento | `[DECISIÓN DELEGADA — pendiente de ratificar por Carlos]` (DD-07) |
| Q-B6A-5 | Vale la lista de §2.3; se coteja con la ficha CV-07 cuando llegue al repositorio | `[DECISIÓN DELEGADA — pendiente de ratificar por Carlos]` (DD-03) |
| Q-B60-6 | Rige el 403 idéntico para "no existe" y "otra organización" (§2.2) | `[DECISIÓN DELEGADA — pendiente de ratificar por Carlos]` (DD-01) |
