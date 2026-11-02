# Plan — Preverificación de organizaciones, Nivel 1 (2026-11-02)

**Estado: PROPUESTO.** Espera un "plan aprobado" de Manuel que se pueda citar: un comentario de `buelvasmanuel` en el PR de este plan. Un solo mensaje aprueba el plan, sus nombres **[Plan]** y los puntos P-PV-1 a P-PV-8. Si Manuel responde a un punto, su respuesta sustituye a la recomendación. **No hay código antes de esa aprobación** (regla 3.4).

**Qué entra** lo fija `ficha-preverificacion-organizaciones.md`, **CONGELADA el 2026-11-02** por Manuel, dueño del backend, por delegación de Carlos (https://github.com/Toffy22Cj/Donaciones/pull/168#issuecomment-6589529148; nota de congelación en la ficha, §11): P1 (a), P2 (a), P3 (c), P4 (a), P5 no aplica, P6 (a), P7 (a), y las dos condiciones. Este plan fija el **cómo**.

**Decisiones de Manuel para el plan (2026-11-01, comunicadas por Manuel):**
- **D-PV-1.** El campo se llama `missingForVerification`: lista de strings con los nombres de campo del perfil que faltan, en orden fijo `["legalName", "nit"]`. Vacía = "Datos completos".
- **D-PV-2.** Un solo método del dominio en `Organization`, que usan a la vez `verify()` y la respuesta de la bandeja. `verify()` conserva **exactamente** su comportamiento actual (`Organization.java:439-441`): el mismo 409 `VerificationDataMissing` y el mismo orden de comprobaciones respecto al estado.
- **D-PV-3.** Se calcula al leer: nada se guarda en Mongo, sin migración y sin índice nuevo.
- **D-PV-4.** Solo en las respuestas de plataforma: `GET /api/v1/platform/organizations` (no existe un detalle de plataforma, H-PV-12). **Nunca** en `GET /api/v1/organizations/{id}`, en las respuestas públicas ni en las de la propia organización.
- **D-PV-5.** No ordena ni filtra la cola: el orden y la paginación actuales de `GET /platform/organizations` no cambian.

**Base verificada:** `develop` `3726414`. La rama de este plan sale de la punta de #168 (`e9b803b`), que solo añade documentos, así que cada ruta:línea de código vale para las dos. **Depende de #168**, que trae la ficha y su congelación.

---

## 0. Hechos verificados en `develop` `3726414`

| # | Hecho | Ruta:línea |
|---|---|---|
| H-PV-1 | **`verify()` comprueba primero el estado y después los datos.** Desde `VERIFIED` o `REJECTED` → `InvalidVerificationTransitionException` (409), aunque falten los datos. Después, sin `legalName` **o** sin `nit` → `VerificationDataMissingException("Verification requires legalName and nit")` (409 `VerificationDataMissing`). Si pasa, `VERIFIED` y se borra la solicitud | `identity/src/main/java/identity/domain/model/Organization.java:435-445` (estado `:436-438`; datos `:439-441`) |
| H-PV-2 | **El perfil nunca es nulo:** vale `EMPTY` por defecto, y al reconstituir un `null` pasa a `EMPTY`. Así `this.profile.legalName()` no puede dar `NullPointerException` | `Organization.java:38`, `:128` |
| H-PV-3 | **Un `legalName` presente nunca está en blanco:** se recorta y, con 0 puntos de código, es 400 `legalName`. Un `nit` presente siempre es un `Nit` válido (9 dígitos y dígito de la DIAN). Por eso "falta" se reduce a `== null`, igual que hoy en `verify()` | `identity/src/main/java/identity/domain/model/OrganizationProfile.java:81-91`, `:46-52`; `Nit.java:19-24` |
| H-PV-4 | **Las organizaciones anteriores a R-7** se leen con el perfil a `null` campo por campo, y por tanto con los dos datos ausentes | `identity/src/main/java/identity/infrastructure/persistence/mongo/mappers/OrganizationMapper.java:125-131` |
| H-PV-5 | **La bandeja, servicio:** `QueueItem(organizationId, name, type, verificationStatus, informationRequest, profile)`. Se arma en un único sitio, `item(Organization o)`, a partir de la `Organization`. El constructor de 5 argumentos (perfil `EMPTY`) **no lo usa nadie** (`git grep -n "new QueueItem"` solo encuentra `:73`) | `identity/src/main/java/identity/application/service/OrganizationVerificationQueueQuery.java:28-36`, `:72-77` |
| H-PV-6 | **La bandeja, orden y paginación:** `findByVerificationStatusAfter(statuses, after, limit + 1)`, por orden de creación, `limit` del controlador = 20, y `next` = id del último si hay uno de más | `OrganizationVerificationQueueQuery.java:59-70`; `app/src/main/java/com/traceability/app/web/platform/OrganizationVerificationQueueController.java:36` |
| H-PV-7 | **La bandeja, HTTP:** `Item(organizationId, name, type, verificationStatus, informationRequest, @JsonUnwrapped profile)`, con `@JsonInclude(NON_NULL)`; `Page(items, nextCursor)`; `no-store` | `OrganizationVerificationQueueController.java:41-49`, `:59-73` |
| H-PV-8 | **403 antes que 400:** el controlador llama a `queue.authorize(principal)` antes de interpretar `status` y `cursor`; quien no es de la plataforma recibe el mismo 403 | `OrganizationVerificationQueueController.java:63-64`; `OrganizationVerificationQueueQuery.java:50-53` |
| H-PV-9 | **`OrganizationProfileJson.ProfileFields` es compartido** por tres respuestas: la bandeja, `GET /organizations/{id}` y `PUT /organizations/{id}/profile`. **Un campo puesto ahí saldría también a la organización** (contra D-PV-4) | `app/src/main/java/com/traceability/app/web/organization/OrganizationProfileJson.java:22-34`; usos en `OrganizationVerificationQueueController.java:46,70`, `OrganizationVerificationStatusController.java:29,44`, `OrganizationProfileController.java:34,58` |
| H-PV-10 | **La respuesta de la propia organización:** `GET /organizations/{id}` arma `OrganizationStatus(organizationId, name, type, verificationStatus, informationRequest, logoUrl, @JsonUnwrapped profile)` desde `StatusView`; `PUT /profile` arma `ProfileResponse(name, @JsonUnwrapped profile)` | `app/.../web/organization/OrganizationVerificationStatusController.java:24-29`, `:37-45`; `identity/.../application/service/OrganizationVerificationStatusQuery.java:23-26`; `app/.../web/organization/OrganizationProfileController.java:34`, `:58` |
| H-PV-11 | **Las respuestas públicas** leen la organización por `OrganizationLogoReferenceQuery.publicOrganization`, que devuelve `PublicOrganization(name, verified, hasLogo, publicProfile)`, y `PublicProfile` solo con `description`, `website`, `location` y `contactEmail`. Las usan el detalle público y el descubrimiento | `identity/.../application/service/OrganizationLogoReferenceQuery.java:28-32`, `:38-39`, `:74-78`; `app/.../web/campaign/PublicCampaignController.java:34-45`; `app/.../web/campaign/PublicCampaignDiscoveryController.java:132-133` |
| H-PV-12 | **No existe un detalle de plataforma** de una organización: bajo `/api/v1/platform/organizations` solo hay `GET` de la lista y los `POST` `verify`, `reject` y `request-information` | `OrganizationVerificationQueueController.java:59`; `app/.../web/platform/OrganizationVerificationController.java:40,47,55` (`git grep -n '@GetMapping' -- app/src/main` no encuentra otro) |
| H-PV-13 | `VerifyOrganizationService` autoriza, lee y llama a `organization.verify()`; no tiene comprobación propia de los datos | `identity/.../application/service/VerifyOrganizationService.java:61`, `:83` |
| H-PV-14 | **Test existente que fija la forma del ítem:** el de la cola exige que los campos de cada ítem sean un subconjunto de `organizationId, name, type, verificationStatus, informationRequest`. Con el campo nuevo, siempre presente, **falla**: hay que ampliar la lista (sin quitar ninguna aserción) | `app/src/test/java/com/traceability/app/web/organization/OrganizationRegistrationAndQueueHttpIntegrationTest.java:189-191` |
| H-PV-15 | **Test existente de `verify()` sin datos:** sin ninguno, solo con razón social y solo con NIT → `VerificationDataMissingException`; con los dos, verifica | `identity/src/test/java/identity/domain/model/OrganizationTest.java:542-561` |
| H-PV-16 | **Test HTTP existente del 409:** `p2b_verifyWithoutLegalNameOrNit_is409VerificationDataMissing`, y el 403 de la cola para quien no es de la plataforma | `app/src/test/java/com/traceability/app/web/organization/OrganizationProfileHttpIntegrationTest.java:330`; `OrganizationRegistrationAndQueueHttpIntegrationTest.java:241-253` |
| H-PV-17 | ArchUnit ya está en `identity` | `identity/pom.xml`; `identity/src/test/java/identity/architecture/ArchitectureTest.java` |

**Contradicciones con un ADR o con la ficha congelada:** ninguna nueva.
- El orden de comprobaciones de `verify()` (H-PV-1) **se conserva sin cambios**: el método nuevo solo sustituye la condición de `:440`, después de la de estado.
- D-2 dice "guarda un resultado", y P6 (a) no guarda nada. Ya está anotado en la ficha (P6, `ficha-preverificacion-organizaciones.md`, fila P6 de §7) y lo resolvió la congelación (§11).
- Las secciones §3 a §5, §8 y §10 de la ficha hablan de `preverification` y `PASSED`/`FAILED`; la nota de congelación (§11) las sustituye por `missingForVerification`. Este plan sigue la nota.

## 1. Común

- **Un PR de código**, `feat/identity-preverificacion-organizaciones` **[Plan]**, desde `develop` una vez fusionados #168 y este plan. Si no lo están, sale de la rama de este plan y dice "depende de #N".
- **Ciclo:**
  - rojo primero, con la salida literal;
  - verde por capas con el módulo completo (`identity` y después `app`);
  - mutaciones con `scripts/mutaciones.py` en un clon limpio, con la mutación 0 (sin cambios: todo verde);
  - `free -g` ≥ 4 GB y `scripts/ci-local.sh` en un clon limpio del commit exacto, con la salida en `Documentos/evidencia-ci/`.
- **Ningún puerto que use otro módulo cambia** (regla 3.5). `QueueItem` es un registro de `identity` que solo usa `app`, y crece de forma aditiva. `contracts` no cambia.
- **Nada en Mongo** (D-PV-3): ni `OrganizationDocument` ni `OrganizationMapper` ni índices cambian. Sin migración.
- **Aviso a la web** en el PR (§7).

## 2. Archivos por módulo

### `identity`

| Archivo | Cambio | Regla |
|---|---|---|
| `domain/model/Organization.java` | **Método nuevo `missingForVerification()` [Plan]**: devuelve una `List<String>` inmutable, en orden fijo: `"legalName"` si `profile.legalName() == null`, y después `"nit"` si `profile.nit() == null`. Vacía si están los dos. **`verify()`**: la comprobación de estado (`:436-438`) se queda **igual y primero**; la condición de `:440` pasa a ser `if (!missingForVerification().isEmpty())`, con **la misma excepción y el mismo mensaje** (`:441`). No se toca nada más | Condición 1; D-PV-1, D-PV-2 |
| `application/service/OrganizationVerificationQueueQuery.java` | `QueueItem` gana `List<String> missingForVerification` **[Plan]** (último componente). Se rellena **solo** en `item(Organization o)` con `o.missingForVerification()`: ningún otro sitio calcula la lista. El constructor de 5 argumentos, sin uso (H-PV-5), se borra (P-PV-2). `page()` no cambia: misma consulta, mismo `limit + 1`, mismo `next` | Condición 1; D-PV-4, D-PV-5 |
| `application/service/VerifyOrganizationService.java` | **Sin cambio:** la regla vive en `Organization.verify()` (H-PV-13) | D-PV-2 |
| `OrganizationVerificationStatusQuery.java`, `OrganizationLogoReferenceQuery.java`, `UpdateOrganizationProfileService.java` | **Sin cambio.** Se nombran para dejar escrito que no ganan el campo | D-PV-4 |
| `infrastructure/persistence/mongo/**` | **Sin cambio** | D-PV-3 |

### `app`

| Archivo | Cambio | Regla |
|---|---|---|
| `web/platform/OrganizationVerificationQueueController.java` | `Item` gana `List<String> missingForVerification` **[Plan]**, **fuera** de `ProfileFields` (no dentro del `@JsonUnwrapped`), copiado de `QueueItem`. Se mantiene `@JsonInclude(NON_NULL)`, que **sí** escribe la lista vacía `[]` (P-PV-3). La autorización (`:64`), `PAGE_SIZE`, el cursor y `Page` no cambian. Se actualiza el Javadoc (`:29`) | D-PV-1, D-PV-4, D-PV-5 |
| `web/organization/OrganizationProfileJson.java` | **Sin cambio.** `ProfileFields` es compartido con las respuestas de la organización (H-PV-9): si ganara el campo, saldría en `GET /organizations/{id}` y en `PUT /profile` | D-PV-4 |
| `web/organization/OrganizationVerificationStatusController.java`, `OrganizationProfileController.java` | **Sin cambio** (H-PV-10) | D-PV-4 |
| `web/campaign/PublicCampaignController.java`, `PublicCampaignDiscoveryController.java` | **Sin cambio** (H-PV-11) | D-PV-4 |
| `web/IdentityApiErrorMappings.java` | **Sin cambio:** el 409 `VerificationDataMissing` ya existe | D-PV-2 |

## 3. Tests de la Definición de hecho, con su rojo esperado

| # | Test | Módulo | Rojo esperado |
|---|---|---|---|
| a | **`OrganizationTest`, condición 1 de Carlos [Plan]:** test parametrizado con las **4 combinaciones** de `legalName` y `nit` (presente/ausente), sobre una organización `PENDING_VERIFICATION`. En cada una: `verify()` lanza `VerificationDataMissingException` **si y solo si** `missingForVerification()` no está vacía; si lanza, el estado sigue en `PENDING_VERIFICATION`; si no, pasa a `VERIFIED` | `identity` | Compilación: `missingForVerification()` no existe |
| a′ | **`OrganizationTest`, mismo orden que hoy [Plan]:** una organización `VERIFIED` y otra `REJECTED`, **sin** `legalName` ni `nit` → `verify()` lanza `InvalidVerificationTransitionException`, **no** `VerificationDataMissingException`. Y el mensaje del 409 sigue siendo `"Verification requires legalName and nit"` | `identity` | Pasa ya en `develop` (fija el comportamiento actual); lo matan las mutaciones 4 y 5 |
| b | **`OrganizationTest`, orden fijo [Plan]:** sin los dos → exactamente `["legalName", "nit"]` (`containsExactly`); solo sin NIT → `["nit"]`; solo sin razón social → `["legalName"]`; con los dos → `[]`. La lista es inmutable (`add` → `UnsupportedOperationException`). Una organización reconstituida con el perfil `null` (H-PV-2, H-PV-4) → `["legalName", "nit"]` | `identity` | Compilación |
| b′ | **`OrganizationTest`, negativa:** llamar a `missingForVerification()` en los cuatro estados no cambia `verificationStatus` ni la solicitud de información (ficha §8, test 2) | `identity` | Compilación |
| c | **`OrganizationPreverificationHttpIntegrationTest` [Plan]** (Testcontainers): una organización creada con `legalName` y `nit`, otra sin nada, otra solo con NIT, y una anterior a R-7 insertada directamente en Mongo sin campos de perfil (ficha §8, test 5). `GET /platform/organizations` trae `missingForVerification` en **cada** elemento: `[]`, `["legalName","nit"]`, `["legalName"]` y `["legalName","nit"]`. **Negativas sobre el JSON completo:** `GET /organizations/{id}` (como `REPRESENTATIVE`), `PUT /organizations/{id}/profile` y `GET /public/campaigns/{code}` **no** contienen la clave `missingForVerification` en ningún nivel | `app` | `missingForVerification` ausente en el ítem |
| c′ | El mismo: `PUT /profile` que añade lo que faltaba → en la siguiente lectura de la bandeja la lista pasa a `[]` (se calcula al leer, sin escribir nada más); y `verify` sobre una con la lista no vacía → 409 `VerificationDataMissing`, sobre una con `[]` → 200 | `app` | Ídem |
| d | El mismo: **orden y paginación no cambian.** 25 organizaciones pendientes creadas alternando completas e incompletas: la concatenación de las páginas (20 + 5) da los ids **en orden de creación**, sin repetidos ni huecos, igual que hoy, y `nextCursor` solo en la primera. El orden **no** agrupa por la lista (las completas no salen primero). `?status=` sigue filtrando solo por estado | `app` | Pasa ya en el orden (fija el comportamiento); falla con la mutación 9 |
| e | El mismo: **403 para quien no es plataforma, igual que hoy.** `ADMINISTRATOR`, `REPRESENTATIVE` y una cuenta sin organización → el mismo 403, con el mismo cuerpo que `develop`, y sin `missingForVerification` en él; sin token → 401 | `app` | Pasa ya (fija el comportamiento); falla con la mutación 10 |
| f | **`ArchitectureTest`, negativa (ficha §8, test 6):** `OrganizationVerificationQueueQuery` no llama a `Organization.verify`, `reject` ni `requestInformation` (`callMethod` de ArchUnit), y no hay ningún `@Scheduled` en `identity.application.service` que lo haga | `identity` | Pasa ya (fija la prohibición, ficha §6) |
| g | `GoldenPathHttpIntegrationTest` en verde y `scripts/ci-local.sh` completo, con el resumen literal de Surefire | todos | — |

**Tests que cambian sin perder aserciones:**
- `OrganizationRegistrationAndQueueHttpIntegrationTest.java:190-191` (H-PV-14): la lista de `isSubsetOf` gana `"missingForVerification"`. Además se añade la aserción de que el campo **está** en cada ítem. No se quita nada.
- Los tests de `verify()` existentes (H-PV-15, H-PV-16) no cambian; deben seguir en verde tal cual, como prueba de que el 409 es el mismo.

## 4. Mutaciones (`scripts/mutaciones.py`)

Dos ficheros de especificación **[Plan]**: uno con `"module": "identity"` (tests a, a′, b, b′, f y `OrganizationTest`), y otro con `"module": "identity,app"` y `"args": ["-Dsurefire.failIfNoSpecifiedTests=false"]` para matar con los tests de `app` (c, c′, d, e y los dos de H-PV-14 y H-PV-16).

| # | Mutación | Archivo | Debe matarla |
|---|---|---|---|
| 1 | **Quitar `"nit"` de la lista** (`missingForVerification` solo mira `legalName`) | `Organization.java` | a, b, c |
| 2 | **Quitar `"legalName"` de la lista** | `Organization.java` | a, b, c |
| 3 | **Invertir la condición en `verify()`:** `if (missingForVerification().isEmpty())` | `Organization.java` | a, H-PV-15, c′ |
| 4 | Comprobar los datos **antes** que el estado en `verify()` | `Organization.java` | a′ |
| 5 | Cambiar el mensaje de `VerificationDataMissingException` | `Organization.java` | a′ |
| 6 | Invertir el orden de la lista (`"nit"` antes que `"legalName"`) | `Organization.java` | b, c |
| 7 | **Exponer el campo en la respuesta de la organización:** `OrganizationStatus` de `GET /organizations/{id}` gana `missingForVerification` | `OrganizationVerificationStatusController.java` (y `StatusView`) | c |
| 8 | Exponer el campo en `ProfileFields` (sale en la bandeja, en `GET /organizations/{id}` y en `PUT /profile`) | `OrganizationProfileJson.java` | c |
| 9 | La bandeja ordena por la lista (las completas primero) dentro de la página | `OrganizationVerificationQueueQuery.java` | d |
| 10 | Quitar `queue.authorize(principal)` del controlador | `OrganizationVerificationQueueController.java` | e, H-PV-16 |
| 11 | `@JsonInclude(NON_EMPTY)` en `Item` (la lista vacía deja de salir) | `OrganizationVerificationQueueController.java` | c |
| 12 | `item()` calcula la lista por su cuenta desde el perfil, solo con `legalName`, en vez de llamar a `o.missingForVerification()` | `OrganizationVerificationQueueQuery.java` | c (organización solo con razón social) |

Cada mutación tiene que quedar **MUERTA**; una VIVA se anota y se cierra con un test antes de pedir la revisión.

## 5. Puntos a decidir (P-PV-n), con recomendación

| # | Punto | Recomendación |
|---|---|---|
| P-PV-1 | Nombre y forma del método del dominio | `Organization.missingForVerification()` **[Plan]**, público, devuelve `List<String>` inmutable (`List.copyOf`/`List.of`). Mismo nombre que el campo, para que se lea que son lo mismo |
| P-PV-2 | El constructor de 5 argumentos de `QueueItem`, sin uso (H-PV-5) | **Borrarlo.** Si se quedara, tendría que inventar la lista o calcularla aparte, y la condición 1 pide un solo cálculo |
| P-PV-3 | ¿La lista vacía sale como `[]` o el campo se omite? | **Siempre presente; vacía = `[]`.** `NON_NULL` ya lo hace así. La web no tiene que distinguir "ausente" de "completo" (P3 (c): no hay tercer estado) |
| P-PV-4 | Posición del campo en el JSON | Después de `informationRequest` y antes de los campos del perfil. El orden de claves no es contractual; se anota solo por claridad |
| P-PV-5 | Test de arquitectura (f) | **Sí**, como en la ficha §8, test 6: protege la prohibición de §6 aunque el cálculo sea una lectura |
| P-PV-6 | ¿Los valores de la lista son contrato estable? | **Sí:** solo `"legalName"` y `"nit"`, que son los nombres de los campos en el JSON del perfil. Si algún día se exige otro campo para verificar, es un cambio de la regla de `verify()` y necesita su ficha |
| P-PV-7 | Rama del PR de código | `feat/identity-preverificacion-organizaciones` **[Plan]** |
| P-PV-8 | `estado-fase6.md` | El siguiente número libre de la serie 0.70 a 0.79 al abrir el PR de código (hoy la última es 0.72, `estado-fase6.md:252`; la zona en cada paso, #169, puede tomar uno antes) |

## 6. Documentos que se actualizan con el PR de código

- **`referencia-api-v1.md`:** una nota fechada **debajo** de la tabla de R-7 (las filas de arriba no se reescriben):

  | Método y ruta | Auth | Cuerpo | Respuestas | Errores |
  |---|---|---|---|---|
  | `GET /platform/organizations` | como antes | — | Cada ítem gana **`missingForVerification: string[]`**, siempre presente: los nombres de campo del perfil que faltan para verificar, en orden fijo `["legalName", "nit"]`; `[]` = datos completos. Se calcula al leer. **No** cambia el orden ni la paginación, y no se puede filtrar por él | como antes (403 a quien no es de la plataforma) |
  | `POST /platform/organizations/{id}/verify` | como antes | — | como antes | El 409 `VerificationDataMissing` se da **si y solo si** `missingForVerification` no está vacía (mismo método del dominio) |
  | `GET /organizations/{id}`, `PUT /organizations/{id}/profile`, rutas públicas | — | — | **No** llevan `missingForVerification` (ficha P4 (a)) | — |

- **`ficha-S18-estado-verificacion-organizacion.md`:** una nota fechada: la preverificación **no** es una transición ni cambia `verificationStatus`.
- **`ficha-preverificacion-organizaciones.md`:** una nota fechada que enlaza el PR de código.
- **`estado-fase6.md`** (P-PV-8).

## 7. Aviso a la web

- **Bandeja de la plataforma** (`GET /api/v1/platform/organizations`): cada solicitud lleva `missingForVerification`.
  - Lista **vacía** → chip **verde "Datos completos"**.
  - Lista **no vacía** → chip **rojo "Faltan datos: razón social / NIT"**, con solo lo que falte: `legalName` = "razón social", `nit` = "NIT", en ese orden y separados por " / ".
  - El chip **no** dice "coincide" ni invita a una revisión rápida: la identidad se comprueba a mano (RUES, fuera del sistema) (ficha P7 (a)).
- **Sin ordenar ni filtrar** por el chip: la cola sigue en orden de creación y con la misma paginación.
- **Los botones no cambian.** El botón **Verificar puede seguir activo** aunque el chip sea rojo: si faltan datos, el backend responde **409 `VerificationDataMissing`**, y la web muestra ese error. Verificar, rechazar y pedir información siguen siendo de una persona.
- **Formulario de la organización** (crear y editar perfil): marcar **razón social** y **NIT** como **"necesarios para verificar"**, con un **texto fijo**. La organización **no** recibe `missingForVerification` (ficha P4 (a)); el formulario no lo lee.
