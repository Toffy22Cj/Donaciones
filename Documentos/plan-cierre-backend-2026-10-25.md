# Plan — Cierre del backend de Manuel: D7, G-8 `truncated`, M-21, T3 y T5 (2026-10-25)

**Estado: PROPUESTO — espera un "plan aprobado" de Manuel, citable** (comentario en el PR de este plan o nota en `decisiones-delegadas-2026-10.md`). Un solo mensaje aprueba las cinco secciones y sus puntos **[Plan]**. T5 necesita además que Manuel apruebe `ADR-021-D-enmienda-1-huella-evidencia.md`.

> **Nota (2026-10-25): APROBADO por Manuel**, por delegación de Carlos (comentario de `buelvasmanuel` en #147, 2026-10-25T22:35:54Z, https://github.com/Toffy22Cj/Donaciones/pull/147#issuecomment-6404356890; registrado en `decisiones-delegadas-2026-10.md`). El estado de arriba se conserva; esta nota lo supera. Respuestas: **P-E2-2**, aceptado como deuda; **P-M21-3**, solo `CONFIRMED`; **P-M21-4**, el conteo por activo se acepta y se presenta como "unidades entregadas en las convocatorias que apoyaste". El resto, como propone el plan.

**Qué entra lo decidió Manuel** el 2026-10-25, por delegación de Carlos (`decisiones-delegadas-2026-10.md`, nota del 2026-10-25, "comunicado por Manuel"), en las decisiones 1, 3, 4, 5 y 7. Este plan fija el **cómo**.

**Base verificada:** `develop` `0f71bfc` (merge de #145), 2026-10-25.

---

## 0. Común a las cinco tareas

- **Un PR por tarea, hacia `develop`, en este orden:**
  1. D7 (`feat/identity-d7-reactivacion`);
  2. G-8 (`feat/r6-g8-truncated`);
  3. M-21 (`feat/account-impact`);
  4. T3 (`feat/adr051-t3-portada`);
  5. T5 (`feat/adr051-t5-evidencia-lectura`).

  Si una tarea depende de otro PR sin fusionar, sale de su rama y su cuerpo dice "depende de #N".
- **Cómo se hace cada una:**
  - tests en rojo primero, por aserción o por compilación, con la salida literal;
  - verde por capas con el módulo completo (`mvn test -pl <módulo> -am`);
  - mutaciones con `scripts/mutaciones.py` en un clon limpio, con la línea base en verde;
  - `free -g` con al menos 4 GB disponibles y `scripts/ci-local.sh` en un clon limpio del último commit de código;
  - la evidencia en `Documentos/evidencia-ci/`.
- **Documentación:** `estado-fase6.md`, una sección por PR en la serie **0.60 a 0.69**. Este PR de documentos usa la 0.60.
- **Nombres:** los marcados **[Plan]** son propuestas que fija el "plan aprobado". Ningún otro nombre nuevo.
- **Regla 3.5:** ningún puerto que ya use otro módulo cambia de contrato salvo donde se dice, con su motivo.

---

## D7. Reactivación por el administrador de plataforma (ADR-050 D7, test 26)

### D7.1 Hechos verificados

- ADR-050 D7 (`ADR-050-cuenta-y-sesiones.md:133-137`) define:
  - la ruta `POST /api/v1/platform/accounts/{accountId}/reactivate`;
  - un `PlatformCommandType` nuevo, `REACTIVATE_ACCOUNT`;
  - la respuesta `200 {accountId, status}`;
  - la auditoría `ACCOUNT_REACTIVATED`;
  - que los tokens anteriores siguen rechazados (D4).

  El test 26 (`:282`) dice: solo el administrador de plataforma reactiva; cualquier otro recibe el 403 uniforme.
- `ReactivateAccountService.reactivateAccount(AuditActor, AccountId)` (`identity/.../service/ReactivateAccountService.java:32-55`):
  - **no autoriza**: recibe un `AuditActor` ya construido;
  - lee la cuenta, llama a `Account.reactivate()` y, solo si cambia, guarda y audita `ACCOUNT_REACTIVATED` (`:38-53`);
  - no tiene ruta HTTP.
- La usan hoy `AccountApplicationServiceIntegrationTest.java:211-231` y `:672` (`identity`) y `AccountPasswordHttpIntegrationTest.java:260` (`app`).
- `Account.reactivate()` (`identity/.../model/Account.java:167-173`) pasa a `ACTIVE` y **no toca `credentialsNotBefore`**. `deactivate` ya lo fijó (`:133-145`, `revokeTokensIssuedBefore`), así que los tokens anteriores a la desactivación siguen rechazados (D4) sin hacer nada más.
- `PlatformCommandType` (`identity/.../authorization/PlatformCommandType.java:6-18`) no tiene `REACTIVATE_ACCOUNT`.
- El patrón que se calca es `RevokePlatformAuthorityService.java:56-61`, que llama a `policy.authorize(principal, PlatformCommandType.…)` y a `auditActorMapper.toAuditActor(principal)`.
- Su ruta está en `PlatformAdministratorsController.java:90-99`. Una cuenta inexistente da 404 `PlatformAuthorityTargetNotFound` (DD-70: "solo la ve la plataforma").

### D7.2 Alcance

- `identity`:
  - `PlatformCommandType.REACTIVATE_ACCOUNT` (el nombre lo da ADR-050 D7);
  - `ReactivateAccountService` gana un método `reactivateAccount(AuthorizationPrincipal, AccountId)` **[Plan, P-D7-1]**. Autoriza con `PlatformAuthorizationPolicy` **antes** de leer la cuenta, construye el actor con `AuthorizationAuditActorMapper` y delega en el método actual, que **no cambia** y conserva sus llamadores.
- `app`:
  - ruta en `com.traceability.app.web.platform.PlatformAccountsController` **[Plan]**;
  - `200 {accountId, status: "ACTIVE"}`, con `Cache-Control: no-store`.
- **Casos:**
  - cuenta inexistente → **404**, como DD-70 **[Plan, P-D7-2]**, con una excepción de `app` `PlatformAccountNotFoundException` **[Plan]** traducida en `IdentityApiErrorMappings`;
  - cuenta ya `ACTIVE` → **200** con el mismo cuerpo, sin auditoría (idempotente, como el método actual) **[Plan, P-D7-3]**;
  - quien no es administrador de plataforma → el 403 uniforme, **antes** de leer la cuenta.
- Sin `Command-Id` (DH-34, como las demás rutas de plataforma).
- La ruta va al inventario de rutas, como **autenticada**.

### D7.3 Tests (rojos primero)

| # | Test | Capa |
|---|---|---|
| D7-1 | Administrador de plataforma reactiva una cuenta `INACTIVE` → 200 `{accountId, status: "ACTIVE"}`; la cuenta queda `ACTIVE` y hay **una** auditoría `ACCOUNT_REACTIVATED` con su actor | `identity` y HTTP |
| D7-2 (test 26) | `EMPLOYEE`, `ADMINISTRATOR` o `REPRESENTATIVE` de una organización, y una cuenta sin organización → el 403 uniforme, byte a byte; la cuenta sigue `INACTIVE` y no hay auditoría (`never()`) | HTTP |
| D7-3 | El 403 llega sin leer la cuenta: con un `accountId` inexistente, también 403 | HTTP |
| D7-4 (D4) | Desactivar, reactivar y usar un token emitido **antes** de desactivar → 401 | HTTP |
| D7-5 | Tras reactivar, iniciar sesión de nuevo → token nuevo que vale | HTTP |
| D7-6 | Cuenta ya `ACTIVE` → 200 y ninguna auditoría nueva | `identity` y HTTP |
| D7-7 | Cuenta inexistente, pedida por el administrador de plataforma → 404 | HTTP |
| D7-8 | Sin JWT → 401; la ruta está en el inventario como autenticada | HTTP |

### D7.4 Mutaciones

1. Sin `policy.authorize` → D7-2.
2. Autorizar después de leer la cuenta → D7-3.
3. Auditar aunque no cambie → D7-6.
4. `reactivate()` sin cambiar el estado → D7-1.
5. `reactivate()` que borra `credentialsNotBefore` → D7-4.
6. 404 traducido como 403 → D7-7.

### D7.5 Documentos

- `referencia-api-v1.md`: la ruta.
- `estado-fase6.md`: sección 0.6x.
- `plan-d6-desactivacion-propia.md`: nota fechada que da D7 por hecho.

### D7.6 Puntos para Manuel

| # | Punto | Recomendación |
|---|---|---|
| P-D7-1 | Método nuevo en `ReactivateAccountService` que autoriza y delega; el actual no cambia | Sí. Es una clase de servicio, no un puerto de `contracts` |
| P-D7-2 | Cuenta inexistente | 404, como DD-70 |
| P-D7-3 | Cuenta ya activa | 200 idempotente, sin auditoría |
| P-D7-4 | Nombres: `PlatformAccountsController`, `PlatformAccountNotFoundException` | Como están |

---

## G-8. `truncated` en la actividad reciente (Enmienda 2 de la ficha R-6)

### G-8.1 Hechos verificados

- `OrganizationActivityQuery.java:61` (`DIRECTORY_LIMIT = 10_000`) lee `funds.findFundIdsByOrganization(organizationId, DIRECTORY_LIMIT)` y `assets.findAssetIdsByOrganization(organizationId, DIRECTORY_LIMIT)` (`:126-127`). No avisa si hay más.
- G-7 lee `DIRECTORY_LIMIT + 1` y calcula `truncated` (`OrganizationIntegrityQuery.java:64-68`).
- `ActivityPage` (`OrganizationActivityQuery.java:82`) es `(items, next)`.
- `PageResponse` (`OrganizationActivityController.java:49-50`) es `(items, nextCursor)`, con `NON_NULL`.
- Los dos directorios ordenan **ascendente** por la génesis (`MongoFundDirectoryAdapter.java:30`, `MongoAssetDirectoryAdapter.java:27`). La consecuencia está en `ficha-R6-enmienda-2.md` §4.

### G-8.2 Alcance

- `OrganizationActivityQuery`:
  - lee `DIRECTORY_LIMIT + 1` de cada directorio y calcula `truncated` con el criterio de G-7;
  - usa solo los primeros `DIRECTORY_LIMIT`;
  - `ActivityPage` gana `boolean truncated`.
- `OrganizationActivityController.PageResponse` gana `truncated`, que siempre llega (P-E2-1). Como es un `boolean` primitivo, `NON_NULL` no lo quita.
- Sin puertos nuevos ni cambiados.

### G-8.3 Tests (rojos primero)

Los de `ficha-R6-enmienda-2.md` §6:
- 10 001 activos → `true`;
- 10 001 fondos → `true`;
- exactamente 10 000 → `false`;
- organización pequeña → `false`, sin cambiar los ítems;
- por HTTP, el campo en las tres formas de la respuesta.

Las pruebas de volumen usan dobles de los directorios en la prueba de la consulta, como el 10d de G-7, para no sembrar 10 001 documentos por HTTP.

### G-8.4 Mutaciones

1. `truncated` siempre `false` → tests 1 y 2.
2. `>=` en lugar de `>` → test 3.
3. Solo mira los activos → test 2.
4. Usa los `DIRECTORY_LIMIT + 1` → un test que comprueba que el stream 10 001 no se lee (`never()` sobre su evento).

### G-8.5 Documentos

- `referencia-api-v1.md`: nota en la ruta de G-8.
- `estado-fase6.md`: sección 0.6x.
- `ficha-R6-enmienda-2.md`: nota "implementada".

### G-8.6 Puntos para Manuel

Los de la enmienda: **P-E2-1** (siempre) y **P-E2-2** (aceptar los más antiguos en este corte).

---

## M-21. "Mi impacto" del donante

### M-21.1 Hechos verificados

- `GET /api/v1/account/donations` (`AccountDonationsController.java:44-52`):
  - lee el seudónimo **sin crearlo** y las intenciones con `donorRef = "acct:" + seudónimo`, con un tope de 100 (`:28-29`, DD-21);
  - el detalle por intención (`DonationIntentReadPort.DonationView`, `convocatoria/.../DonationIntentReadPort.java:28-30`) trae `amount`, `currency`, `status`, `fundId`, `fundsAppliedAt`, `publicCode` y `campaignTitle`.
- `DonationIntentStatus` (`convocatoria/.../DonationIntentStatus.java:11-…`) tiene `PENDING`, `CONFIRMED`, `FAILED`, `FUNDING_REJECTED` y `EXPIRED_UNKNOWN`. `CONFIRMED` no significa que los fondos estén aplicados (`:6`).
- Lo entregado de una donación:
  - `DonationReadPort.findByFundId` (`core/.../DonationReadPort.java:6`) devuelve `DonationReadModel` con `logistics` (`DonationReadModel.java`);
  - cada ítem (`LogisticsReadItem.java:5-15`) lleva `lifecycleStatus`, `unitOfMeasure` y `quantity`;
  - es la misma definición de "de la donación" que DD-76: el stream del fondo y los activos de su proyección.
- El boceto de la web (`PaxFide`, `Documentos/paxfide-pantallas-web.html:397-403`, sección "05 · M-21") pide la ruta `/account/impact`. Hoy deriva el total por moneda y por convocatoria de `GET /account/donations`, y marca como "sin contrato" los bienes entregados, las personas alcanzadas y la evolución en el tiempo.

### M-21.2 Alcance

- Ruta nueva **`GET /api/v1/account/impact`** **[Plan, P-M21-1]**: JWT de cualquier cuenta, `Cache-Control: private, no-store`.
- Respuesta **[Plan, P-M21-2]**:

  `200 {donationsCount, totals: [{currency, amount}], campaignsSupported, deliveredUnits: [{unitOfMeasure, quantity}], truncated}`

  | Campo | Cómo se calcula |
  |---|---|
  | `donationsCount` | Intenciones `CONFIRMED` de la cuenta **[P-M21-3]** |
  | `totals` | Suma de `amount` de esas intenciones, **por moneda** (en unidades mínimas, como texto, como `/account/donations`). Nunca se suman monedas distintas |
  | `campaignsSupported` | Convocatorias distintas de esas intenciones (`campaignRef` o `publicCode` interno, nunca expuesto) |
  | `deliveredUnits` | Para cada intención con fondos aplicados (`fundId` no nulo), los ítems de `logistics` con `lifecycleStatus = DELIVERED`, sumados **por `unitOfMeasure`**. Nunca se suman unidades distintas |
  | `truncated` | `true` si la cuenta tiene más de 100 intenciones (el mismo tope de DD-21) |

- **Nunca:**
  - el seudónimo, el `donorRef`, `fundId` ni `assetId`;
  - el nombre de un beneficiario ni de un custodio;
  - las ubicaciones;
  - las "personas alcanzadas" (no hay dato; F-HOG: nunca "familias" ni "hogares", DD-39).
- **Una cuenta que nunca donó** → todo a cero y listas vacías, sin crear el seudónimo (como `/account/donations`).
- **Módulo:** `app` (compone `convocatoria`, `core` e `identity`). Lo hace una clase `com.traceability.app.application.account.AccountImpactQuery` **[Plan]**, con su controlador `com.traceability.app.web.account.AccountImpactController` **[Plan]**.
- **Coste:** una lectura de intenciones y una de `DonationReadPort` por intención con fondos, como mucho 100. Es el mismo orden de lecturas que el historial (H-L4).

### M-21.3 Tests (rojos primero)

| # | Test |
|---|---|
| M21-1 | Dos donaciones `CONFIRMED` en COP y una en USD, en dos convocatorias → `donationsCount` 3, `totals` con COP y USD por separado y `campaignsSupported` 2 |
| M21-2 | Una `PENDING` y una `FAILED` no cuentan (P-M21-3) |
| M21-3 | Un activo `DELIVERED` y otro `IN_TRANSIT` del mismo fondo → solo cuenta el entregado; dos unidades de medida distintas salen por separado |
| M21-4 | Una cuenta que nunca donó → ceros, y el seudónimo **no** se crea (`never()`) |
| M21-5 | Sin JWT → 401. Otra cuenta no ve los datos de esta (dos cuentas, respuestas distintas) |
| M21-6 | El cuerpo no contiene `donorRef`, el seudónimo, `fundId`, `assetId`, `campaignRef` ni `organizationRef` (búsqueda de los valores sembrados) |
| M21-7 | 101 intenciones → `truncated: true` |
| M21-8 | La ruta está en el inventario como autenticada |

### M-21.4 Mutaciones

1. Contar todos los estados → M21-2.
2. Sumar sin agrupar por moneda → M21-1.
3. Contar activos no entregados → M21-3.
4. Sumar unidades distintas → M21-3.
5. Crear el seudónimo → M21-4.
6. Sin `truncated` → M21-7.

### M-21.5 Documentos

- `referencia-api-v1.md`: la ruta.
- `estado-fase6.md`: sección 0.6x.
- Aviso a la web (el boceto marca la ruta como "depende de backend").

### M-21.6 Puntos para Manuel

| # | Punto | Recomendación |
|---|---|---|
| P-M21-1 | Nombre de la ruta | `GET /api/v1/account/impact`, el que propuso Manuel y el del boceto |
| P-M21-2 | Forma de la respuesta | La de arriba |
| P-M21-3 | Qué estados cuentan como "donado" | Solo `CONFIRMED`, como DD-46 (`CONFIRMED` suma). Alternativa: solo las que tienen fondos aplicados (`fundsAppliedAt` no nulo), más estricta |
| P-M21-4 | Un activo financiado con varias donaciones | Cada donación cuenta el activo **entero**, porque la proyección no reparte cantidades entre fondos. Se acepta en este corte y se dice en la referencia |
| P-M21-5 | Nombres `AccountImpactQuery` y `AccountImpactController` | Como están |

---

## T3. Portada de la convocatoria (ADR-051 F6, F8, F10; calca T2)

### T3.1 Hechos verificados

- ADR-051:
  - F8 (`:142-160`) define `PUT` y `DELETE /api/v1/organizations/{organizationId}/campaigns/{campaignRef}/cover`, multipart con el campo `file`. `PUT` responde `200 {coverImageUrl}` y `DELETE` responde `204`;
  - solo `ADMINISTRATOR` (C1 (a), `:204`, y F8 `:147`);
  - máximo 2 MB (`MediaPurpose.CAMPAIGN_COVER`, `app/.../media/MediaPurpose.java:11`);
  - F6 (`:123`): lectura pública `GET /api/v1/public/campaigns/{publicCode}/cover`, con `public, max-age=3600` y `ETag`;
  - F6 (`:128`): las vistas públicas (descubrimiento y CV-07) ganan `coverImageUrl?`;
  - F9: `convocatoria` guarda la referencia de la portada.
- La reasignación a Manuel frente a `ADR-051:299` ("Responsable sugerido: Carlos") la registra la decisión 4.
- El servicio común ya admite la portada: `MediaUploadService.store` exige `ownerRef` (el `campaignRef`) solo para `CAMPAIGN_COVER` (`app/.../media/MediaUploadService.java:60`).
- El patrón que se calca es T2:
  - `plan-adr051-tarea2-logo.md` §4.2 a §4.7;
  - `OrganizationLogoService` (`app/.../media/OrganizationLogoService.java:24-57`, con `REPLACED` y `REMOVED` en `:27-28`);
  - `Organization.logoObjectKey` (`identity/.../model/Organization.java:30`).
- `Convocatoria` (`convocatoria/.../model/Convocatoria.java:39-49`) no tiene campo de imagen.
- `ConvocatoriaAuthorizationPolicy.requireAdministratorOf(actorAccountId, organizationRef)` (`convocatoria/.../authorization/ConvocatoriaAuthorizationPolicy.java:36`) es la regla de "solo `ADMINISTRATOR`".
- Las vistas públicas ya llevan `organizationLogoUrl?`:
  - el descubrimiento, en `PublicCampaignDiscoveryController.java:34`;
  - CV-07, en `CampaignDtos.java:56`.

### T3.2 Alcance

- `convocatoria`:
  - `Convocatoria`, su documento y su mapper ganan `coverObjectKey` **[Plan, P-T3-1]**, opcional. Los documentos sin el campo se leen sin portada;
  - un servicio de escritura de la referencia `ConvocatoriaCoverReferenceService` **[Plan]** (`replace` y `remove`, con `ConvocatoriaTransactionRetryHelper`). Autoriza con `requireAdministratorOf` y devuelve la referencia anterior;
  - la lectura de la referencia se añade a las dos vistas públicas que ya existen, `PublicCampaignView` (CV-07, `convocatoria/.../query/PublicCampaignView.java:16`) y `PublicCampaignSummary` (descubrimiento, `PublicCampaignDiscoveryQuery.java:26`) **[Plan, P-T3-2]**.
- `app`:
  - `com.traceability.app.application.media.CampaignCoverService` **[Plan]** calca `OrganizationLogoService`:
    1. permiso antes de leer los bytes;
    2. 2 MB en el controlador;
    3. `store(CAMPAIGN_COVER, organizationId, campaignRef, …)`;
    4. la referencia nueva en `convocatoria`;
    5. F10: borrar la anterior **después** (`REPLACED`/`REMOVED`) y, si falla, `200` igual con `media.orphan`;
  - controladores `com.traceability.app.web.campaign.CampaignCoverController` y `PublicCampaignCoverController` **[Plan]**;
  - `coverImageUrl?` en el descubrimiento y en CV-07, solo si hay portada;
  - 404 por `CampaignCoverNotFoundException` **[Plan]**, en `MediaApiErrorMappings`, como el logo.
- `DELETE` sin portada → `204` sin tocar el proveedor (como P-T2-4).
- Una convocatoria `CLOSED` puede cambiar su portada **[Plan, P-T3-3]**.
- Las rutas van a `PublicRoutes` (la pública) y al inventario (las cuatro).

### T3.3 Tests (rojos primero; calcan T2-1 a T2-16)

| # | Test |
|---|---|
| T3-1 | `ADMINISTRATOR` sube un PNG → `200 {coverImageUrl}`; los bytes guardados no tienen metadatos |
| T3-2 | `REPRESENTATIVE` o `EMPLOYEE` de la organización, u otra organización → el 403 uniforme **sin leer los bytes** (un `MultipartFile` que cuenta las lecturas); nada guardado |
| T3-3 | Más de 2 MB → 413; formato no admitido → 415 |
| T3-4 | Reemplazar → la anterior se borra del proveedor después y queda `deletedAt` con `REPLACED`; si el borrado falla, 200 y `media.orphan` |
| T3-5 | `DELETE` → 204 y la pública da 404; `DELETE` sin portada → 204 |
| T3-6 | `GET /public/campaigns/{publicCode}/cover` sin JWT → los bytes, con `public, max-age=3600` y `ETag`; una convocatoria no visible por su código → 404, igual que sin portada |
| T3-7 | Descubrimiento y CV-07: `coverImageUrl` solo si hay portada; ninguna respuesta pública contiene `organizationId` ni `campaignRef` |
| T3-8 | Un documento de convocatoria sin el campo se lee sin portada |
| T3-9 | `campaignRef` de otra organización en la ruta → el mismo 403 |
| T3-10 | Inventario: las cuatro rutas clasificadas, una pública |

### T3.4 Mutaciones

1. Permitir al `REPRESENTATIVE` → T3-2.
2. Autorizar después de leer → T3-2.
3. Borrar la anterior antes de confirmar → T3-4.
4. Sin el límite de 2 MB → T3-3.
5. `coverImageUrl` siempre → T3-7.
6. La pública sin comprobar la visibilidad → T3-6.

### T3.5 Documentos

- `referencia-api-v1.md`: las cuatro rutas y `coverImageUrl?`.
- Nota fechada en `ADR-051:299` con la reasignación.
- `estado-fase6.md`: sección 0.6x.

### T3.6 Puntos para Manuel

| # | Punto | Recomendación |
|---|---|---|
| P-T3-1 | Campo `coverObjectKey` en `Convocatoria` | Como `logoObjectKey` (P-T2-1) |
| P-T3-2 | Dónde se lee la referencia | En las dos vistas públicas que ya existen (CV-07 y descubrimiento), para no sumar una lectura por ítem del descubrimiento |
| P-T3-3 | ¿Portada en una convocatoria `CLOSED`? | Sí: es presentación, no configuración (la Enmienda 4 de ADR-037 trata la configuración) |
| P-T3-4 | Nombres de las clases [Plan] de T3.2 | Como están |
| P-T3-5 | Auditoría del cambio de portada | No, como P-T2-5: el metadato ya lo guarda |

---

## T5. Lectura de la evidencia, retirada por la plataforma y huella en el seguimiento

**Bloqueo:** esta sección no se implementa hasta que Manuel apruebe `ADR-021-D-enmienda-1-huella-evidencia.md` (decisión 5).

### T5.1 Hechos verificados

- ADR-051:
  - F6 (`:124` y `:126`): `GET /api/v1/physical-assets/{assetRef}/evidence` con JWT, para un miembro de la organización del activo o el administrador de plataforma (`PlatformCommandType` nuevo `READ_DELIVERY_EVIDENCE`), con `Cache-Control: private, no-store`. Sin imagen → 404; imagen borrada por F7 → **410** `EvidenceRemoved`;
  - F7 (`:139`): `POST /api/v1/platform/organizations/{organizationId}/media/remove` con `{purpose, ref, reason}` y un `PlatformCommandType` nuevo `REMOVE_MEDIA`. Borra los bytes y deja en el metadato `deletedAt`, `deletedBy` y `reason`, sin borrarlo. Sirve también para logo y portada (`:140`);
  - F6 (`:131-133`): la huella en el seguimiento, con el contrato de la enmienda de ADR-021-D.
- Ni `READ_DELIVERY_EVIDENCE` ni `REMOVE_MEDIA` existen (búsqueda en el código sin resultados).
- `MediaObjectStore` (`app/.../media/MediaObjectStore.java`) ya tiene:
  - `findByOrganizationIdAndPurposeAndSha256` (`:19`);
  - `findByObjectKey` (`:23`);
  - `markDeleted(objectKey, deletedBy, deletedAt, reason)` (`:29`);
  - `bindEvidence` (`:41`).
- Las rutas de activo de `api` usan `{assetRef}` (`PhysicalAssetController.java:156`, `GET /{assetRef}`).
- La huella vive en el payload de `ASSET_DELIVERED` (`AssetDeliveredPayload.java:14`).
- El historial público sale de la proyección `asset_history` (`MongoAssetHistoryReadAdapter.java:20-35`), sin `evidenceRef`.
- El Bloque C añadió una lectura del Event Store por stream (`StreamEventAnchorDataPort.findByStream`).

### T5.2 Alcance

- **Lectura** `GET /api/v1/physical-assets/{assetRef}/evidence`, en `app`, con `com.traceability.app.web.asset.DeliveryEvidenceReadController` **[Plan]**:
  1. autorizar (miembro de la organización del activo, o la plataforma con `READ_DELIVERY_EVIDENCE`) antes de nada;
  2. leer el `evidenceRef` del `ASSET_DELIVERED` del activo;
  3. si no es `sha256:` o no hay entrega → 404;
  4. el metadato por `(organizationId, DELIVERY_EVIDENCE, sha256)` → con `deletedAt`, 410; sin bytes, 404;
  5. los bytes con `private, no-store`.
- **Retirada** `POST /api/v1/platform/organizations/{organizationId}/media/remove`, en `app`, con `com.traceability.app.web.platform.PlatformMediaRemovalController` **[Plan]**:
  - `{purpose, ref, reason}`; `ref` es el `evidenceRef` para la evidencia, el `campaignRef` para la portada y nada para el logo **[Plan, P-T5-2]**;
  - borra los bytes y marca el metadato (`markDeleted` con el `accountId` del administrador);
  - para logo y portada, además quita la referencia en `identity` o `convocatoria`, para que las vistas dejen de dar la URL;
  - responde `204` **[Plan, P-T5-3]**;
  - `reason` es obligatorio, de 1 a 500 caracteres, y no se registra en el log **[Plan]**.
- **Huella en el seguimiento:** el contrato de la enmienda de ADR-021-D. La lectura del `evidenceRef` sale del Event Store **[Plan, P-T5-1]**: el historial ya lee los eventos del stream para G-3 (`StreamEventAnchorDataPort.findByStream`) y ese puerto de `core` gana el `evidenceRef` del payload en su proyección. Es un puerto de `core` que solo usa `app`, así que la regla 3.5 no se aplica a otro módulo. Así no hay que cambiar ni reconstruir la proyección `asset_history`.
- `PlatformCommandType` gana `READ_DELIVERY_EVIDENCE` y `REMOVE_MEDIA` (los nombres los da ADR-051).

### T5.3 Tests (rojos primero; criterios 12 a 14 de ADR-051)

| # | Test |
|---|---|
| T5-1 | Miembro de la organización del activo → los bytes con `private, no-store`; administrador de plataforma → igual |
| T5-2 | Otra organización, o una cuenta sin organización → el 403 uniforme; sin JWT → 401 |
| T5-3 | Activo sin entrega, o entregado con texto libre → 404 |
| T5-4 (criterio 14) | Tras la retirada → 410 `EvidenceRemoved`; los bytes ya no están en el proveedor; el metadato conserva motivo, autor y fecha |
| T5-5 | La retirada la pide quien no es de la plataforma → el 403 uniforme y nada cambia (`never()` sobre el borrado) |
| T5-6 | Retirar un logo o una portada → la vista pública deja de dar la URL y la lectura da 404 |
| T5-7 (criterio 13) | Los siete tests de la enmienda de ADR-021-D, por HTTP sobre el seguimiento público |
| T5-8 | El `reason` no aparece en los logs ni en ninguna respuesta |
| T5-9 | Inventario: las dos rutas, autenticadas |

### T5.4 Mutaciones

1. Leer la evidencia sin autorizar → T5-2.
2. 404 en lugar de 410 → T5-4.
3. Exponer el texto libre en el seguimiento → T5-7 (2).
4. Aceptar `SHA256:` en mayúsculas → T5-7 (4).
5. Retirar sin borrar los bytes → T5-4.
6. Retirar sin la plataforma → T5-5.
7. No quitar la referencia del logo al retirarlo → T5-6.

### T5.5 Documentos

- `referencia-api-v1.md`: las dos rutas y los campos del seguimiento.
- `documento-maestro-proyecto.md`: la nota de la enmienda de ADR-021-D.
- `ADR-051:255`: nota fechada.
- `estado-fase6.md`: sección 0.6x.
- Aviso a la web y al móvil.

### T5.6 Puntos para Manuel

| # | Punto | Recomendación |
|---|---|---|
| P-T5-1 | De dónde sale el `evidenceRef` para el seguimiento | Del Event Store, ampliando el puerto de `core` que ya lee el stream para G-3, sin tocar la proyección |
| P-T5-2 | Qué es `ref` en la retirada según el propósito | `evidenceRef`, `campaignRef` o nada para el logo |
| P-T5-3 | Respuesta de la retirada | `204`; retirar algo ya retirado → `204` igual (idempotente) |
| P-T5-4 | Nombres de las clases [Plan] de T5.2 | Como están |

---

## Hallazgos registrados en este plan (para Manuel; no se resuelven aquí)

| # | Hallazgo | Ruta:línea |
|---|---|---|
| H-CB-1 | El documento de cierre dice "parte 1 en #135"; el código de la parte 1 de la Enmienda 1 de ADR-050 entró con #137 (`37827c0`), y #135 (`536ffe6`) solo registró la aprobación de la parte 3 | `ADR-050-enmienda-1-iat-del-login.md` (nota de la parte 1) |
| H-CB-2 | "E2" ya es el nombre de G-9 en la Enmienda 1 de la ficha R-6; el cambio de G-8 va como Enmienda 2 | `ficha-R6-enmienda-1.md:77` |
| H-CB-3 | Con más de 10 000, G-8 muestra la actividad de los streams más antiguos | `MongoFundDirectoryAdapter.java:30`, `MongoAssetDirectoryAdapter.java:27` |
| H-CB-4 | ADR-021-D excluye `parentAssetRef`; G-4 expone uno opaco con ese nombre | `documento-maestro-proyecto.md:172`; `PublicLogisticsItemDTO.java:16` |
| H-CB-5 (M-22) | La lista de campos del comprobante no está en ningún repositorio legible (`Donaciones`, `PaxFide` y `PaxFide_Mv`; el PDF del 16 de octubre no está subido). Sin ella no se puede comprobar que no falta ninguno. Hoy `/account/donations` trae `intentId`, `campaignTitle`, `amount`, `currency`, `status`, `trackingCode?`, `createdAt?`, `confirmedAt?`, `organizationName?`, `paymentMethod?` y `publicCode?`. La web prohíbe el `trackingCode` en el comprobante (`PaxFide`, `prompts-agentes-estilo-v2.md:162`, T1-7) | `DonationDtos.java:30-32` |
| H-CB-6 | `auditoria-pendientes-manuel-2026-10-24.md:187` usa "M-21" para otra cosa (E1 en el historial), no para "Mi impacto" | Ese archivo |
