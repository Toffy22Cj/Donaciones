# Plan R-5 + D1 — Aceptaciones legales, perfil y minimización de la auditoría

**Estado: BORRADOR. Espera el "plan aprobado" de Manuel**, por la delegación de Carlos del 2026-10-24 (`decisiones-delegadas-2026-10.md`, nota del 2026-10-24). No se escribe código antes (regla 3.4). Carlos aprueba el PR de implementación con Approve en GitHub.
**Fecha:** 2026-10-24 (UTC).
**Contrato:** ADR-050 (APROBADO — Carlos, 2026-10-18): D1, D2, D3 (con Q1 (a)) y D10 (minimización), y los tests 19 a 23 de su definición de hecho. Los puntos **[Plan]** se aprueban con el plan.
**Indicación de Carlos (orden v2, 2026-10-24):** versiones `v1-2026-10` en las tres `traceability.legal.*-version`. Los textos van en `paxfide-web/Documentos/legal/`, que no existe: quedan como **dependencia de la web**, sin detenerse por ello.
**Base:** `develop` `19b4e0f`. Rama: `feat/identity-r5-aceptaciones-perfil`, desde el `develop` del momento, con su hash.
**Dueño:** Manuel.

## 0. Dependencias y bloqueos

| # | Qué | Efecto |
|---|---|---|
| **B-1** | **Choque con ADR-050 Q1 (§3, P-R5-1):** D3 exige que el login responda `403 AcceptanceRequired`, y eso cambia `AuthenticateAccountPort` | Sin aprobación de Carlos, **D3 (el login) no se implementa**. El resto del plan no depende de ello y puede ir en un primer PR (P-R5-2) |
| **B-2** | La Enmienda 1 de ADR-050 (#120) ya cambia la firma de `AuthenticateAccountPort.authenticate` (P-R3-3) | Si D3 entra, su cambio del puerto se coordina con ese: un solo cambio de firma, o dos seguidos y ordenados |
| **B-3** | R-3 + R-2 fusionado | El login y `Account` cambian en los dos; la rama de R-5 sale de un `develop` que ya lo contenga |
| **B-4** | Los textos legales (`paxfide-web/Documentos/legal/`) | No bloquea el backend: el backend solo guarda versiones, nunca textos (D2). La web publica los textos con esas versiones |

## 1. Alcance

Dentro:
- **D1, paso 1:** `POST /api/v1/auth/register` pide además `acceptedTermsVersion` y `acceptedDataPolicyVersion`.
- **D1, paso 2:** `GET` y `PUT /api/v1/account/profile` (`givenName?`, `familyName?`) y la auditoría `PROFILE_UPDATED` sin valores.
- **D2:**
  - la cuenta guarda versión y fecha de cada aceptación;
  - `POST /api/v1/organizations` pide `acceptedOrganizationTermsVersion`, que la `Organization` guarda con la cuenta y la fecha;
  - las tres versiones van en configuración, sin valor por defecto fuera de `dev` y con fail-fast;
  - versión distinta o ausente → 400 con el campo nombrado (`InvalidRequestField`).
- **D3** (bloqueado, B-1): el login no emite token hasta aceptar las versiones vigentes.
- **D10:** `ACCOUNT_CREATED` deja de guardar el correo. Las entradas antiguas no se reescriben (ADR-025).

Fuera: los textos legales (web), la supresión de datos (D10: manual, fuera del sistema), D6 y D7, Google y la verificación del correo.

## 2. Hechos verificados en `develop` `19b4e0f` (2026-10-24)

- **Registro:** `AccountRegistrationController.register` (`app/.../web/account/AccountRegistrationController.java:38-48`) recibe `RegisterRequest(email, password)`. Si falta un campo, lanza `InvalidRequestFieldException(campo)`, que da un 400 de cuerpo fijo; el campo solo va en el log.
- **Auditoría del alta:** `CreateAccountService.createAccount` audita `ACCOUNT_CREATED` con `Map.of("email", email.value(), "selfRegistration", true)` (`CreateAccountService.java:58-59`). Es lo que D10 minimiza.
- **`AuditAction`** no tiene `PROFILE_UPDATED`.
- **Organización:**
  - `OrganizationRegistrationController` recibe `CreateOrganizationRequest(type, name)` (`:33`);
  - `CreateOrganizationService.createOrganization(actor, type, representante, name)` (`:44-45`);
  - `OrganizationDocument` no tiene aceptaciones.
- **`Account`** (tras R-3 + R-2): `accountId`, `email`, `passwordHash`, `status`, `organizationId`, `platformAuthority` y `credentialsNotBefore`. No tiene nombre ni aceptaciones.
- **Login:** `LoginController` está en `api` (`api/.../auth/LoginController.java:55`, `:62`) y solo conoce `AuthenticateAccountPort.authenticate(email, password) → String` (`contracts/.../AuthenticateAccountPort.java:13`). `AuthenticateAccountPortImpl` es "Solo lectura, sin transacción" (`:18`).
- **Configuración:**
  - `app/src/main/resources/application.yml` no tiene `traceability.legal.*`; `application-dev.yml` tampoco;
  - el fail-fast existente para `traceability.mail.from` y `traceability.web.base-url` se hace en el constructor de `SmtpInvitationMailAdapter`;
  - `app/src/test/resources/application.yml` sustituye al de producción en los tests.
- **Quién llama hoy a registro y login, y tendrá que mandar las versiones:**
  - Registro:
    - main: `DemoScenarioSeeder.java:124`;
    - tests: `AuthenticationEndToEndIntegrationTest`, `P2EndpointsHttpIntegrationTest`, `OrganizationMembersAndInvitationsHttpIntegrationTest`, `AccountHttpIntegrationTest`;
    - `api`: `PublicRoutesTest` y `JwtAuthFilterTest` (solo como ruta).
  - Login (con D3):
    - main: `DemoScenarioSeeder.java:131`;
    - tests: `AuthenticationEndToEndIntegrationTest`, `P2EndpointsHttpIntegrationTest`, `DemoScenarioSeederIntegrationTest`, `DemoSeedRunnerIntegrationTest`, `PathEvasionRealServerIntegrationTest`, `AccountPasswordHttpIntegrationTest`, `PublicRoutesInventoryIntegrationTest`, `LoginControllerTest`;
    - el script `scripts/demo/recorrido.py:99`.
  - Crear organización: `DemoScenarioSeeder` y los tests que llaman a `POST /api/v1/organizations` (la lista exacta se saca al implementar con `grep`).
- **Tests 22 y 23 de ADR-050 hoy:** `ACCOUNT_CREATED` lleva el correo. `GET /me` (N1), la lista de miembros y la de responsables no tienen nombre, porque no existe.

## 3. Choque con ADR-050 Q1 (no se resuelve aquí)

**P-R5-1.** ADR-050 Q1 (`ADR-050-cuenta-y-sesiones.md:197`) eligió (a) "El login no emite token hasta aceptar: responde `403 AcceptanceRequired` con las versiones pendientes y el cliente repite el login con `acceptedTermsVersion`/`acceptedDataPolicyVersion` en el cuerpo", y lo justifica con "**no toca el filtro ni el puerto**".

En el código, el login está en `api` y solo ve `AuthenticateAccountPort`, que devuelve el `accountId`. Para decidir el 403 hay que conocer las versiones aceptadas por la cuenta, y para registrar la aceptación del segundo intento hay que escribir en `identity`. Las dos cosas exigen **cambiar `AuthenticateAccountPort`** (`contracts`; lo usan `api` e `identity`), lo que activa la regla 3.5. Además, la autenticación deja de ser de solo lectura.

| Opción | Qué es | Exige |
|---|---|---|
| (a) | `authenticate(email, password, acceptedTermsVersion?, acceptedDataPolicyVersion?)`. En `identity`, después de validar la contraseña: si faltan versiones vigentes y el cuerpo no las trae (o trae otras), lanza `AcceptanceRequiredException(pendientes)` en `contracts`. Si las trae y son las vigentes, las guarda y sigue. Devuelve el registro de P-R3-3 | Enmienda de ADR-050 Q1 o D3, aprobada por Carlos. Se recomienda **como parte 3 de la Enmienda 1 (#120)**, que ya cambia esa firma, para que haya un solo cambio del puerto |
| (b) | Mover el login de `api` a `app`, que ve `identity` | Cambia ADR-038 §2.7 y la organización de `api`; más grande |
| (c) | Dejar D3 fuera de R-5 y registrarlo como pendiente | Las cuentas existentes y las de semilla nunca aceptarían una versión nueva; incumple D3 |

**Recomendación: (a), como parte 3 de #120.** Se respeta el orden de comprobaciones de ID01-D1: primero la contraseña (siempre `matches`, con el mismo coste) y después las aceptaciones. Una contraseña incorrecta sigue siendo el mismo 401 y nunca un 403, para no revelar que la cuenta existe.

## 4. Cambios por módulo (D1, D2, D10; D3 según P-R5-1)

### `identity`

| Archivo | Cambio | Fuente |
|---|---|---|
| `domain/model/Account.java` | `givenName?`, `familyName?` y aceptaciones `{termsVersion, termsAcceptedAt, dataPolicyVersion, dataPolicyAcceptedAt}`, nulas en las cuentas existentes. Métodos: `updateProfile(givenName, familyName)` (recorta; de 1 a 100 puntos de código; `null` borra) y `acceptLegalTerms(terms, dataPolicy, now)`. `reconstitute` gana los campos y **[Plan]** conserva las firmas anteriores, como en R-3 + R-2 | D1, D2 |
| Excepción de perfil **[Plan: nombre, `InvalidProfileFieldException(campo)`]** | Fuera de rango → 400 con el campo nombrado | D1 |
| `domain/model/Organization.java`, `OrganizationDocument`, mapeador | `organizationTermsAcceptance {version, acceptedBy, acceptedAt}`, nula en las existentes | D2 |
| `domain/model/AuditAction.java` | `PROFILE_UPDATED` | D1 |
| `application/service/CreateAccountService.java` | Recibe las dos versiones aceptadas, las valida contra las vigentes (P-R5-3) y las guarda con la fecha. `ACCOUNT_CREATED` pasa a `Map.of("selfRegistration", true)`, **sin el correo** | D1, D2, D10 |
| `application/service/CreateOrganizationService.java` | Recibe `acceptedOrganizationTermsVersion`, lo valida y lo guarda con el actor y la fecha | D2 |
| Servicio de perfil **[Plan: `AccountProfileService`, `AccountProfileQuery`]** | Leer y actualizar el perfil de la cuenta del principal. Audita `PROFILE_UPDATED` con un resumen sin valores (por ejemplo, qué campos cambiaron: `{fields: ["givenName"]}`) **[Plan, P-R5-4]** | D1 |
| `AccountDocument`, `AccountMapper` | Los campos nuevos | D1, D2 |
| Versiones vigentes **[Plan, P-R5-3]** | Un registro `LegalVersions(terms, dataPolicy, organizationTerms)` en `identity`, construido en `app` desde la configuración y entregado como bean. `identity` no lee la configuración de Spring | D2 |
| D3 (si P-R5-1 se aprueba) | `AuthenticateAccountPortImpl` según P-R5-1 (a) | D3 |

### `app`

| Archivo | Cambio | Fuente |
|---|---|---|
| `web/account/AccountRegistrationController.java` | `RegisterRequest(email, password, acceptedTermsVersion, acceptedDataPolicyVersion)`. Versión ausente o distinta de la vigente → 400 `InvalidRequestField` con el campo en el log, y sin cuenta creada | D1, D2 |
| `web/organization/OrganizationRegistrationController.java` | `acceptedOrganizationTermsVersion`, con la misma regla | D2 |
| `web/account/AccountProfileController.java` (nuevo) **[Plan: nombre]** | `GET /api/v1/account/profile` → `200 {email, givenName?, familyName?}`, `no-store`. `PUT` con `{givenName, familyName}` → `200` con el mismo cuerpo. Fuera de rango → 400 con el campo nombrado. El nombre nunca va a un log | D1 |
| `config/LegalVersionsConfig.java` (nuevo) **[Plan: nombre]** | Lee `traceability.legal.terms-version`, `data-policy-version` y `organization-terms-version`. Si alguna falta o está en blanco, la aplicación no arranca (como DD-61) | D2 |
| `application.yml` | Las tres claves desde variables de entorno, **sin valor por defecto** (`${TRACEABILITY_LEGAL_TERMS_VERSION}` y las otras dos) **[Plan: nombres de las variables]** | D2 |
| `application-dev.yml` y `app/src/test/resources/application.yml` | `v1-2026-10` en las tres (indicación de Carlos) | D2 |
| `bootstrap/DemoScenarioSeeder.java` | Registro, login y creación de organización con las versiones vigentes, que lee de `LegalVersions` | D2, D3 |
| `web/IdentityApiErrorMappings.java` | La excepción del perfil → 400; con D3, `AcceptanceRequiredException` → 403 `AcceptanceRequired` con las versiones pendientes **[Plan, P-R5-5: el cuerpo lleva `{pendingTermsVersion?, pendingDataPolicyVersion?}` y nunca el texto]** | D1, D3 |

### `api` (solo con D3)

`LoginController`: lee del cuerpo `acceptedTermsVersion?` y `acceptedDataPolicyVersion?` y los pasa al puerto. Con `AcceptanceRequiredException`, responde 403 con las versiones pendientes. Sin token. El 401 no cambia.

### `contracts` (solo con D3, según P-R5-1)

`AuthenticateAccountPort` y `AcceptanceRequiredException`, según la enmienda.

### Fuera del backend

- `scripts/demo/recorrido.py` manda las versiones en el login y en el registro.
- **Aviso a la web y al móvil** (con el commit, en el informe): los campos nuevos del registro y de la organización, el 403 del login y la ruta del perfil.

## 5. Comportamiento ante fallos

| Situación | Resultado |
|---|---|
| Registro sin versión, o con una versión vieja | 400 `InvalidRequestField`, sin cuenta creada y sin auditoría |
| Organización sin `acceptedOrganizationTermsVersion`, o con otra | 400, sin organización |
| Perfil: un campo de 0 o de más de 100 puntos de código tras recortar | 400 con el campo nombrado; nada cambia |
| Perfil con `null` | Borra el campo |
| Configuración sin alguna versión, fuera de `dev` | La aplicación no arranca |
| Login (D3) con contraseña incorrecta | El mismo 401 de siempre, nunca 403 |
| Login (D3) con contraseña correcta y versiones pendientes | 403 `AcceptanceRequired` con las pendientes, sin token |
| Login (D3) repetido con las versiones vigentes | Se guardan con la fecha y se emite el token |

## 6. Tests (rojos primero)

| Test | Módulo | Cubre |
|---|---|---|
| `AccountTest` (ampliado) | `identity` | `updateProfile` (bordes de 1 y 100 puntos de código, con emojis de dos unidades UTF-16, recorte, `null`); `acceptLegalTerms` |
| Integración de `identity` (Testcontainers) | `identity` | 20 (versión y fecha guardadas; la organización guarda las suyas, con la cuenta); 22 (`ACCOUNT_CREATED` nuevo sin el correo; las entradas antiguas no se tocan); `PROFILE_UPDATED` sin valores |
| `AccountRegistrationHttpIntegrationTest` **[Plan: nombre]** | `app` | 19 (sin aceptación o con versión vieja de cualquiera de las dos → 400 y sin cuenta, comprobado en Mongo) |
| `AccountProfileHttpIntegrationTest` **[Plan: nombre]** | `app` | `GET` y `PUT`; 400 con el campo; `no-store`; 23 (el nombre no aparece en `GET /me`, en los miembros, en los responsables ni en ninguna ruta pública; aserciones negativas); el nombre no aparece en los logs |
| Test de arranque | `app` | 21 (sin una versión, el contexto no arranca, como el de DD-61) |
| D3 (si entra) | `api`, `app` | El 403 con las pendientes; el segundo intento guarda y emite el token; con contraseña incorrecta, el 401 de siempre y nunca 403 |
| Tests existentes (§2) | `app`, `api` | Mandan las versiones; `GoldenPathHttpIntegrationTest` en verde |

## 7. Mutaciones

| # | Mutación | La mata |
|---|---|---|
| 1 | El registro acepta una versión distinta de la vigente | 19 |
| 2 | `ACCOUNT_CREATED` vuelve a llevar el correo | 22 |
| 3 | El perfil acepta 101 puntos de código | Borde de `AccountTest` |
| 4 | `GET /me` devuelve `givenName` | 23 |
| 5 | `PROFILE_UPDATED` lleva el valor | Test de auditoría |
| 6 | La organización no guarda quién aceptó | 20 |
| 7 | Falta una versión y la aplicación arranca igual | 21 |
| 8 (D3) | El login emite token con versiones pendientes | Test del 403 |
| 9 (D3) | Una contraseña incorrecta con versiones pendientes da 403 | Test del 401 |

## 8. Pasos y evidencia

1. Esperar B-3 y decidir P-R5-1 y P-R5-2.
2. Rama desde el `develop` del momento.
3. Rojo con la salida literal; verde por capas (`identity`, `api` si hay D3, `app`); mutaciones.
4. Documentos: `referencia-api-v1.md` (registro, organización, perfil y, con D3, el 403 del login), `estado-fase6.md`, y la nota sobre los textos como dependencia de la web.
5. `free -g` con al menos 4 GB y `ci-local.sh` en un clon limpio; autoauditoría contra D1, D2, D3, D10 y los tests 19 a 23; PR con Carlos.

## 9. Puntos para la aprobación de Manuel

| # | Qué | Recomendación |
|---|---|---|
| **P-R5-1** | D3 contra Q1 ("no toca el puerto") | (a), como parte 3 de la Enmienda 1 (#120), que aprueba Carlos |
| **P-R5-2** | Dividir en dos PR: primero D1, D2 y D10; después D3, cuando Carlos apruebe | Sí: lo primero no depende del puerto |
| **P-R5-3** | `LegalVersions` como bean de `app` hacia `identity`, y la validación de la versión en el servicio de `identity` (dominio), con el controlador traduciéndola a 400 con el campo | Aprobar |
| **P-R5-4** | Resumen de `PROFILE_UPDATED`: solo los nombres de los campos que cambiaron | Aprobar |
| **P-R5-5** | Cuerpo del 403: `{pendingTermsVersion?, pendingDataPolicyVersion?}`, con `title = AcceptanceRequired` | Aprobar |
| **P-R5-6** | Nombres marcados **[Plan]** y nombres de las variables de entorno | Aprobar o renombrar |

## 10. Riesgos

- **Aceptado en ADR-050 (4):** con Q1 (a), los tokens emitidos antes de una versión nueva sirven hasta 8 h sin aceptarla.
- **Nuevo, solo registrado:** al desplegar R-5 con D3, **todas** las cuentas existentes (y las de la demo) reciben 403 en su siguiente login hasta aceptar. Es lo que pide D3 ("también las existentes y las de semilla"), pero la web y el móvil deben tener lista la pantalla de aceptación **antes** del despliegue.
- **Dependencia de la web:** sin los textos en `paxfide-web/Documentos/legal/` con las versiones `v1-2026-10`, la web no puede mostrar lo que se acepta. El backend no lo comprueba ni lo bloquea.
