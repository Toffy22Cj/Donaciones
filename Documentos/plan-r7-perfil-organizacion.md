# Plan — R-7: perfil de la organización (2026-10-29)

**Estado: PROPUESTO.** Espera un "plan aprobado" de Manuel que se pueda citar: un comentario de `buelvasmanuel` en el PR de este plan. Un solo mensaje aprueba el plan, sus nombres **[Plan]** y los puntos P-R7-1 a P-R7-12. Si Manuel responde a un punto, su respuesta sustituye a la recomendación. **No hay código antes de esa aprobación** (regla 3.4).

**Qué entra** lo fija `ficha-R7-perfil-organizacion.md` (CONGELADA por Carlos el 2026-10-29: P1 a P8 según la recomendación, P9 no). Carlos confirmó que R-7 se puede empezar (C-5, `decisiones-delegadas-2026-10.md`, nota del 2026-10-29 "texto literal de C-1 y C-5"). Este plan fija el **cómo**.

**Base verificada:** `develop` `d313bc6`. Cada ruta:línea de este plan se abrió sobre esa base. **Depende del PR de `chore/docs-r7-y-c1`**, que trae la ficha.

---

## 0. Hechos verificados en `develop` `d313bc6`

| Hecho | Ruta:línea |
|---|---|
| `Organization` solo guarda tipo, miembros, estado, solicitud, nombre, logo y aceptación de términos | `identity/src/main/java/identity/domain/model/Organization.java:22-32` |
| `name`: de 1 a 200 **caracteres** (`length()`, no puntos de código) | `Organization.java:35`, `:127` |
| `verify`, `reject` y `requestInformation` solo rechazan desde `VERIFIED` o `REJECTED`; `verify` y `reject` borran la solicitud | `Organization.java:342-366` |
| No hay ninguna operación de la organización que salga de `NEEDS_MORE_INFORMATION` | `Organization.java:342-366` (las tres son de la plataforma, `OrganizationVerificationController.java:40-55`) |
| `GET /organizations/{id}` (S-18): solo `ADMINISTRATOR` o `REPRESENTATIVE` de esa organización; cualquier otro caso es la misma excepción | `identity/.../OrganizationVerificationStatusQuery.java` (método `status`) |
| El logo lo gestionan `REPRESENTATIVE` o `ADMINISTRATOR` (`OrganizationMembersPolicy`) | `identity/.../OrganizationLogoReferenceService.java:18`; `identity/.../authorization/OrganizationMembersPolicy.java:14` |
| `POST /organizations` lleva hoy `{type, name, acceptedOrganizationTermsVersion}` | `app/.../web/organization/OrganizationRegistrationController.java:35`, `:48` |
| La versión de los términos de organización se exige **solo al crear** | `identity/.../CreateOrganizationService.java:53` |
| La versión vigente sale de la configuración | `app/src/main/resources/application.yml:64` (`TRACEABILITY_LEGAL_ORGANIZATION_TERMS_VERSION`) y `app/src/test/resources/application.yml:18` (`v1-2026-10`) |
| El detalle público (CV-07) lee la organización con una sola consulta (`OrganizationLogoReferenceQuery.publicOrganization`) | `app/.../web/campaign/PublicCampaignController.java:31-42` |
| El validador de correo admite dominios de nivel superior de 2 a 6 letras | `identity/.../domain/model/Email.java:14` |
| `PUT /account/profile`: un campo ausente o `null` lo borra | `app/.../web/account/AccountProfileController.java:29`, `:42` |
| Acciones de auditoría de la organización que ya existen | `identity/.../domain/model/AuditAction.java:8`, `:18-20` |
| La ficha S-18 está CONGELADA y fija los cuatro estados | `Documentos/ficha-S18-estado-verificacion-organizacion.md:4`, `:47` |
| En `develop` ningún código conoce un NIT | `git grep -n NIT origin/develop -- identity/src/main` no encuentra nada |

## 1. Común

- **Un PR de código**, `feat/identity-r7-perfil-organizacion` **[Plan]**, desde `develop` una vez fusionados este plan y la ficha. Si no lo están, sale de la rama de este plan y dice "depende de #N".
- **Ciclo:**
  - rojo primero, con la salida literal;
  - verde por capas con el módulo completo (`identity` y después `app`);
  - mutaciones con `scripts/mutaciones.py` en un clon limpio, con la mutación 0;
  - `free -g` ≥ 4 GB y `scripts/ci-local.sh` en un clon limpio del commit exacto, con la salida en `Documentos/evidencia-ci/`.
- **Ningún puerto que use otro módulo cambia** (regla 3.5). Lo que se toca es interno de `identity` o de `app`:
  - `OrganizationLogoReferenceQuery.PublicOrganization` es un registro de `identity` que solo usa `app`; crece de forma aditiva (P-R7-6);
  - el puerto nuevo del catálogo es de `identity`.
- **Aviso a la web y al móvil** en el PR (ficha §10).

## 2. Archivos por módulo

### `identity`

| Archivo | Cambio | Ficha |
|---|---|---|
| `domain/model/OrganizationProfile.java` **[Plan]** | Registro inmutable con los campos opcionales de §3: `description`, `website`, `location`, `contactEmail`, `legalName`, `nit`, `specialTaxRegime` y `contactPhone`. Valida cada campo y lanza `InvalidOrganizationProfileFieldException` **[Plan]** con el campo. `changedFields(OrganizationProfile other)` devuelve los nombres que cambian | §3 |
| `domain/model/Nit.java` **[Plan]** | NIT de 9 dígitos y su dígito de verificación, con el algoritmo de la DIAN (§7): pesos 3, 7, 13, 17, 19, 23, 29, 37 y 41 de derecha a izquierda; `r = suma mod 11`; el dígito es `r` si `r` es 0 o 1, y `11 − r` si no. Si no coincide → `InvalidOrganizationProfileFieldException("nitCheckDigit")` | §3, §7 |
| `domain/model/OrganizationLocation.java` **[Plan]** | `{departmentCode, municipalityCode}`. Solo códigos; los nombres se resuelven al leer (§6) | §3, §6 |
| `domain/model/Organization.java` | Campo `profile`, que nunca es nulo (vacío en las organizaciones existentes).<ul><li>`updateProfile(OrganizationProfile)` **[Plan]**: con `VERIFIED`, si cambian `name`, `legalName`, `nit` o `nitCheckDigit` → `OrganizationProfileLockedException` (P1 (b)). Devuelve los campos cambiados.</li><li>`resubmitForVerification()` **[Plan]**: `NEEDS_MORE_INFORMATION → PENDING_VERIFICATION` y borra la solicitud; desde cualquier otro estado → `VerificationResubmitNotAllowedException` (§5).</li><li>`verify()`: sin `legalName` o sin `nit` → `VerificationDataMissingException` (P2 (b)).</li></ul> | §3, §5, P1, P2 |
| `domain/exception/`: `OrganizationProfileLockedException`, `OrganizationNitAlreadyRegisteredException`, `VerificationResubmitNotAllowedException`, `VerificationDataMissingException` (nombres de la ficha) e `InvalidOrganizationProfileFieldException` **[Plan]** | Una excepción por error de la ficha | §4 |
| `domain/model/AuditAction.java` | `ORGANIZATION_PROFILE_UPDATED` (nombre de la ficha), cuyos datos solo nombran los campos cambiados, y `ORGANIZATION_VERIFICATION_RESUBMITTED` **[Plan]** | §4, §5 |
| `application/port/out/LocationCatalogPort.java` **[Plan]** | `boolean contains(String departmentCode, String municipalityCode)`, `Optional<LocationNames> names(OrganizationLocation)` y `Catalog catalog()` (con `catalogVersion`) | §6 |
| `infrastructure/persistence/mongo/documents/OrganizationDocument.java` y `mappers/OrganizationMapper.java` | Los campos del perfil. Índice único **parcial** `uq_organization_nit` **[Plan]**, sobre `nit` solo cuando existe (P5) | §3, P5 |
| `MongoOrganizationRepositoryAdapter` | Traduce el `DuplicateKeyException` de `uq_organization_nit` en `OrganizationNitAlreadyRegisteredException` (409) | P5 |
| `application/service/CreateOrganizationService.java` | Acepta el perfil opcional, validado antes de escribir y con la ubicación contra el catálogo. Un NIT repetido → 409, sin crear la organización (test 6) | §4 |
| `application/service/UpdateOrganizationProfileService.java` **[Plan]** | `PUT /profile`: `REPRESENTATIVE` o `ADMINISTRATOR` con `OrganizationMembersPolicy`, como el logo; el resto, el mismo 403. En una transacción: `updateProfile`, guardar y auditar `ORGANIZATION_PROFILE_UPDATED` con los nombres de los campos, nunca sus valores. Si nada cambia, no audita | §4 |
| `application/service/ResubmitOrganizationVerificationService.java` **[Plan]** | `POST /verification/resubmit`, con la misma autorización. Hace `resubmitForVerification`, guarda y audita | §5 |
| `application/service/VerifyOrganizationService.java` | Sin cambio de código: la regla P2 (b) vive en `Organization.verify()`. Cambia su test | P2 |
| `application/service/OrganizationVerificationStatusQuery.java` | `StatusView` gana el perfil completo, porque ya solo lo leen `REPRESENTATIVE` y `ADMINISTRATOR` | §4, test 9 |
| `application/service/OrganizationVerificationQueueQuery.java` | `QueueItem` gana el perfil completo, solo para la plataforma | §4 |
| `application/service/OrganizationLogoReferenceQuery.java` | `PublicOrganization` gana `publicProfile` **[Plan]**: `description`, `website`, `location` y `contactEmail`. Nunca NIT, razón social, teléfono ni régimen | §4, P7 |

### `app`

| Archivo | Cambio | Ficha |
|---|---|---|
| `infrastructure/location/DivipolaLocationCatalog.java` **[Plan]** | Implementa `LocationCatalogPort` leyendo del classpath `divipola/divipola.json` **[Plan]**. Lo carga al arrancar y falla si falta o está mal formado (P-R7-1) | §6 |
| `src/main/resources/divipola/divipola.json` | **Lo aporta Manuel** (P-R7-1). El agente no lo descarga ni lo escribe | §6 |
| `src/test/resources/divipola/divipola.json` | Muestra mínima **de test**, marcada como tal en `catalogVersion` (`"test-sample"`): dos departamentos con dos o tres municipios cada uno. Solo sirve para validar la forma y las reglas; no es el catálogo real | §6 |
| `web/organization/OrganizationRegistrationController.java` | El cuerpo gana los campos de §3, todos opcionales | §4 |
| `web/organization/OrganizationProfileController.java` **[Plan]** | `PUT /organizations/{id}/profile` → `200` con el perfil completo y `no-store` | §4 |
| `web/organization/OrganizationVerificationResubmitController.java` **[Plan]** | `POST /organizations/{id}/verification/resubmit` → `200 {verificationStatus}` | §5 |
| `web/organization/OrganizationVerificationStatusController.java` | La respuesta gana los campos de §3 | §4 |
| `web/platform/OrganizationVerificationQueueController.java` | Cada ítem gana los campos de §3 | §4 |
| `web/campaign/PublicCampaignController.java` | `organizationProfile?` con `departmentName` y `municipalityName` resueltos con el catálogo | §4, P7 |
| `web/location/PublicLocationsController.java` **[Plan]** | `GET /public/locations` → `{catalogVersion, departments: [...]}` con `Cache-Control: public, max-age=86400` | §4, §6 |
| `api` `PublicRoutes` | `GET /api/v1/public/locations`, pública | §4 |
| `web/IdentityApiErrorMappings.java` | Los cinco errores nuevos: 400 por campo y los 409 `OrganizationProfileLocked`, `OrganizationNitAlreadyRegistered`, `VerificationResubmitNotAllowed` y `VerificationDataMissing` | §4 |

## 3. Tests (ficha §9), con su rojo esperado

| # | Test | Módulo | Rojo esperado |
|---|---|---|---|
| 1 | `NitTest` **[Plan]**: tres NIT reales de prueba con su dígito, uno inválido, y los casos `r = 0` y `r = 1` | `identity` | Compilación: `Nit` no existe |
| 2 | `OrganizationTest`, ampliado: `resubmitForVerification` desde `NEEDS_MORE_INFORMATION` y desde los otros tres estados | `identity` | Compilación; después, la excepción nombrada no se lanza |
| 3 | `OrganizationTest`: `verify` sin NIT o sin razón social → `VerificationDataMissingException`; con los dos, verifica | `identity` | `Expecting code to raise a throwable` |
| 4 | `OrganizationTest`: un campo bloqueado de una verificada → `OrganizationProfileLockedException`; uno público, sí | `identity` | Compilación |
| 5 | `OrganizationProfileTest` **[Plan]**: `website` con `http:` o `javascript:`; un municipio de otro departamento; una descripción de 501 puntos de código; un teléfono que no empieza por 3 ni por 60; `specialTaxRegime` con `type ≠ FOUNDATION` | `identity` | Compilación |
| 6 | `OrganizationProfileHttpIntegrationTest` **[Plan]**: crear con y sin opcionales; un NIT repetido → 409 sin crear la organización | `app` | `expected 409 but was 201` hasta el índice |
| 7 | El mismo: `PUT /profile` por `REPRESENTATIVE` o `ADMINISTRATOR` → 200; por `EMPLOYEE`, otra organización o un id inexistente → el mismo 403; la auditoría solo con nombres (negativa sobre los valores) | `app` | 404 (ruta inexistente) |
| 8 | El mismo: `GET /public/campaigns/{code}` con `organizationProfile` y **nunca** `nit`, `nitCheckDigit`, `legalName`, `contactPhone`, `specialTaxRegime` ni `organizationId` (negativa sobre el JSON completo) | `app` | Falta `organizationProfile` |
| 9 | El mismo: la cola de la plataforma y `GET /organizations/{id}` muestran los datos de verificación | `app` | Faltan los campos |
| 10 | El mismo: `resubmit` → la organización vuelve a la cola con `PENDING_VERIFICATION` | `app` | 404 |
| 11 | `PublicLocationsHttpIntegrationTest` **[Plan]**: el catálogo de test, su `catalogVersion` y `Cache-Control: public, max-age=86400` | `app` | 401 (la ruta no es pública) |
| 12 | `GoldenPathHttpIntegrationTest` en verde y `ci-local.sh` completo | todos | — |

**Tests que cambian sin perder aserciones** (P-R7-4): los 21 archivos de test de `develop` que verifican una organización (`git grep -l '/verify"\|VerifyOrganizationService' origin/develop -- app/src/test identity/src/test`) le ponen antes razón social y NIT, con un ayudante de test **[Plan]**. Ninguno se desactiva ni se borra.

## 4. Mutaciones

| # | Mutación | Debe matarla |
|---|---|---|
| 1 | Un peso de la DIAN cambiado | 1 |
| 2 | `r = 0 o 1` → `11 − r` | 1 |
| 3 | `resubmitForVerification` también desde `PENDING_VERIFICATION` | 2 |
| 4 | `resubmit` no borra la solicitud | 2, 10 |
| 5 | `verify` sin comprobar el NIT | 3 |
| 6 | El bloqueo de P1 sin `nit` | 4 |
| 7 | Se acepta `http:` | 5 |
| 8 | El índice `uq_organization_nit` sin `unique` | 6 |
| 9 | La auditoría guarda los valores | 7 |
| 10 | `organizationProfile` incluye `nit` | 8 |
| 11 | `EMPLOYEE` puede editar | 7 |
| 12 | `/public/locations` sin cabecera de caché | 11 |

## 5. Puntos a decidir (P-R7-n), con recomendación

| # | Punto | Recomendación |
|---|---|---|
| P-R7-1 | El archivo DIVIPOLA real | **Lo aporta Manuel** (C-5: lo descarga una persona desde el DANE), en `app/src/main/resources/divipola/divipola.json` con el formato del §2 y su fecha de corte en `catalogVersion`. Sin el archivo, la aplicación **no arranca** (fail-fast, como las claves). El código y los tests usan la muestra de test. ¿Prefieres que, sin archivo, arranque con el catálogo vacío y toda ubicación dé 400? |
| P-R7-2 | Formato del JSON | `{catalogVersion, departments: [{code, name, municipalities: [{code, name}]}]}`, el mismo de `GET /public/locations`. Códigos como texto: 2 dígitos el departamento y 5 el municipio (DANE) |
| P-R7-3 | P3, el correo público, y los términos de organización | La ficha §10 dice que el texto cambia y que su versión la cambia Carlos (`paxfide-web/Documentos/legal/terminos-organizacion-v1-2026-10.md`). **Hecho:** el backend solo exige la versión **al crear** (`CreateOrganizationService.java:53`); las organizaciones existentes **no** vuelven a aceptar al cambiar `TRACEABILITY_LEGAL_ORGANIZATION_TERMS_VERSION`, porque no hay mecanismo como D3 para cuentas. Recomendación: este PR no cambia la versión. Carlos publica el texto nuevo y se cambia la variable al desplegar. Las existentes quedan con su aceptación anterior, como límite conocido que se anota. ¿Se acepta? |
| P-R7-4 | P2 (b) rompe los tests que verifican organizaciones sin NIT | Un ayudante de test **[Plan]** les pone razón social y NIT antes de verificar. La semilla (`DemoScenarioSeeder`) también, con un NIT de prueba válido, para que la demo pueda verificar |
| P-R7-5 | NIT de los tests | Tres NIT de entidades públicas, con su dígito comprobado a mano contra el RUES por una persona (ficha §7). **No los invento:** hasta que lleguen, los tests usan NIT construidos con el algoritmo y marcados "sintético" |
| P-R7-6 | Lo público sale por `OrganizationLogoReferenceQuery` | **Sí**, ampliando `PublicOrganization`: es la lectura única por convocatoria (P-T2-7). El descubrimiento (R-1) no muestra el perfil (P7 (a)) y su respuesta no cambia |
| P-R7-7 | `name` cuenta caracteres y los demás campos, puntos de código | Se respeta lo existente: `name` sigue con `length()` (`Organization.java:127`). Los campos nuevos cuentan puntos de código, como la ficha y `Account.updateProfile`. La diferencia se anota, no se cambia |
| P-R7-8 | `contactEmail` y el validador de cuentas, que admite dominios de nivel superior de 2 a 6 letras (`Email.java:14`) | El mismo validador, como dice la ficha. Un dominio de nivel superior largo da 400; es un límite conocido |
| P-R7-9 | `PUT /profile` sin cambios | 200 sin auditoría |
| P-R7-10 | Auditoría de `resubmit` | `ORGANIZATION_VERIFICATION_RESUBMITTED` **[Plan]** con el actor del principal, sin datos |
| P-R7-11 | Enmienda de la ficha S-18 por la transición nueva (ficha R-7 §5, línea 9) | Una nota fechada en `ficha-S18-estado-verificacion-organizacion.md`, en el mismo PR de código: la transición `NEEDS_MORE_INFORMATION → PENDING_VERIFICATION` y su 409. Se cita como "enmienda por la ficha R-7" |
| P-R7-12 | `estado-fase6.md` | §0.72 |

## 6. Documentos que se actualizan con el PR de código

- `referencia-api-v1.md`: las rutas y los campos.
- `ficha-S18-estado-verificacion-organizacion.md`: la nota de P-R7-11.
- `estado-fase6.md` §0.72.
- El aviso a la web y al móvil (ficha §10). El texto de los términos lo cambia Carlos (P-R7-3).
