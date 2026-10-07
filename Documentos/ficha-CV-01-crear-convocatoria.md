# Ficha CV-01 — `POST /api/v1/organizations/{organizationId}/campaigns` (crear convocatoria)

**Naturaleza:** ficha de contrato HTTP, con el mismo método y las mismas etiquetas que las fichas de contratos API de Fase 6 (§0 de ese documento) y que `ficha-N1-quien-soy.md`. **No es normativa**, no es un ADR, no modifica ninguna fuente y **no autoriza código**.
**Estado: ABIERTO** — quedan 4 elementos sin respaldo (Q-CV01-12 a Q-CV01-15, §5). Las 11 decisiones Q-CV01-1 a Q-CV01-11 están registradas (§9). Aun cerrando las 4 preguntas, la ficha solo podría llegar a **CONGELADO (pendiente de incorporación normativa)**: el prefijo `/api/v1` (DH-01), `ProblemDetail` (DH-02), los códigos 401/403 (DH-51) y la ficha transversal T son [DHR] en borrador hasta que se apruebe la Enmienda 1 de ADR-041.
**Fecha:** 2026-10-07 (decisiones del 2026-10-06).
**Origen:** `Documentos/propuesta-apis-fase6.md` §5 (CV-01 es la siguiente ficha) y §6.

**Regla de esta ficha:** una ficha congelada **no** significa que el código la cumpla. Las diferencias entre este contrato y el código actual están en §7 (deudas de implementación), separadas de la especificación.

---

## 1. Problema que resuelve

Un `ADMINISTRATOR` de una organización `VERIFIED` crea una convocatoria. El caso de uso existe y está probado en el módulo `convocatoria` (`ConvocatoriaLifecycleService.createConvocatoria`), pero no hay contrato HTTP: `handoff-convocatoria.md` y `auditoria-documental-convocatoria.md` registran el cuerpo como "NO DEFINIDO" y una discrepancia de respuesta entre la matriz (`{campaignRef, publicCode, status, ...}`) y el código (`{campaignRef, publicCode}`).

## 2. Fuentes aplicables

| Fuente | Estado | Qué aporta |
|---|---|---|
| `Documentos/ADR-037-convocatoria-ledger-assignment-donationintent.md` §2.1, §2.2, §5 | Aprobado (consolidado con Enmienda 1) | `publicCode` único; `status` persistido `OPEN`/`CLOSED`; ledger; autorización exclusiva de `ADMINISTRATOR`; precondición `VERIFIED` |
| `Documentos/ADR-037-enmienda-1-convocatoria.md` §3.1, §3.5 | Aprobada (2026-09-30, N1–N12) | `acceptedDonationTypes` (N1), solo `IN_KIND` sin meta ni ledger (N2), medios de pago, `onTargetReached`; idempotencia por `commandId` y resultado original `campaignRef` + `publicCode` (N12) |
| `Documentos/convocatoria-resumen.md` §6.8.4, §6.15 (R1) | Decisiones humanas registradas | Invariante de configuración monetaria; `title` obligatorio, `description` opcional, fechas obligatorias con `startDate < endDate`, `currency` si y solo si `MONETARY`; las fechas no limitan intenciones ni cambian `status` |
| `Documentos/ADR-038-identidad-platform-administrator-verificacion-organization.md` §2.5 | Aprobado | El caso de uso consumidor (`CreateConvocatoria`) valida `VERIFIED`; `Organization` solo expone el estado |
| `Documentos/ADR-041-api-frontend-contratos-http.md` §2.1, §2.2, §2.5, §2.6, §2.7, §2.8 | Aprobado | `api` traduce, no decide; JWT; errores solo para excepciones nombradas; sin deduplicación HTTP transversal; `publicCode` es un secreto bearer; orquestación fuera del controller |
| `Documentos/api-contract-matrix.md` §2 | [M] | Ruta, JWT + `ADMINISTRATOR`. Su respuesta `{..., status, ...}` queda sustituida por Q-CV01-1 (regla de precedencia N11 de la Enmienda 1) |
| Fichas de contratos API Fase 6 (2026-10-04), ficha T | [DHR] en borrador. **No está en `Documentos/`** | T-33 (`Command-Id`), T-34 (JSON), T-36 (JWT) |
| Decisiones Q-CV01-1 a Q-CV01-11 | [DHR] 2026-10-06 (§9) | Respuesta, enums, importes, códigos de error, reglas de creación, entropía del `publicCode`, longitud de `title` |
| Código `develop` HEAD `0d4f428` | [IMPL], solo informativo | §7 |

## 3. Elemento por elemento

### 3.1 Petición

| Elemento | Contenido | Respaldo |
|---|---|---|
| Método | `POST` | [M] §2 |
| Ruta | `/organizations/{organizationId}/campaigns` → `POST /api/v1/organizations/{organizationId}/campaigns` | [M] §2; [DHR] DH-01 |
| `organizationId` (path) | Organización en la que se crea la convocatoria; es el `organizationRef` del dominio | [M] §2; [N] ADR-037 §2.1 |
| Autenticación | JWT, `Authorization: Bearer <jwt>` | [N] ADR-041 §2.2; [DHR] Q-T36-1 |
| Header `Command-Id` | Obligatorio, UUID | [N] Enmienda 1 §3.5; [DHR] T-33 (Q-T33-1, Q-T33-2) |
| Estructura del cuerpo | Plano o con objeto `configuration` anidado | **[ABIERTO] Q-CV01-12** |
| `title` | String obligatorio, no vacío, **máximo 200 caracteres** | [DHR] R1 (obligatorio); [DHR] Q-CV01-11 (máximo 200) |
| `description` | String opcional | [DHR] R1. **Longitud máxima: [ABIERTO] Q-CV01-15** |
| `visibility` | `"PUBLIC"` \| `"PRIVATE_LINK"` | [N] ADR-037 §2.1 (visibilidad); [DHR] Q-CV01-2 (literales del dominio). **Obligatoriedad: [ABIERTO] Q-CV01-14** |
| `startDate`, `endDate` | Instantes ISO-8601 UTC con sufijo `Z`; obligatorios; `startDate < endDate`; **ninguno anterior a `now − 5 minutos`**, con `now` = reloj del servidor | [DHR] R1; [DHR] T-34 (Q-T34-1); [DHR] Q-CV01-9 + Q-CV01-9a |
| Semántica de las fechas | Validación **de creación** únicamente: no abren ni cierran la convocatoria, no cambian `status` y no bloquean la creación de `DonationIntent` | [DHR] R1; [N] Enmienda 1 §3.4; P7 sigue abierto |
| `acceptedDonationTypes` | Array no vacío de `"MONETARY"`, `"IN_KIND"` | [N] Enmienda 1 §3.1 (N1); [DHR] Q-CV01-2 |
| `acceptedPaymentMethods` | Array de `"GATEWAY"`, `"BANK_TRANSFER"`, `"CASH"`; **obligatorio y no vacío si `MONETARY` está aceptado** | [N] Enmienda 1 §3.1; [DHR] Q-CV01-2. **Con convocatoria solo `IN_KIND`: [ABIERTO] Q-CV01-13** |
| `currency` | Código ISO 4217 de tres letras; presente **si y solo si** `MONETARY` está aceptado | [DHR] R1; [DHR] Q-CV01-3 |
| `targetAmount` | String de dígitos enteros (sin signo, sin decimales, sin separadores) que representa **unidades mínimas de la moneda** según ISO 4217; `> 0`; presente si y solo si `MONETARY` está aceptado | [N] Enmienda 1 §3.1, N2; [DHR] T-34 (importes como String); [DHR] Q-CV01-3; [DHR] Q-CV01-8 |
| `targetPolicy` | `"FLEXIBLE"` \| `"STRICT"` \| `"CLOSE_ON_TARGET"`; presente si y solo si `MONETARY` está aceptado | [N] ADR-037 §2.2; N2; [DHR] Q-CV01-2 |
| `onTargetReached` | `"CLOSE"` \| `"REJECT_EXCESS"` \| `"ACCEPT_EXCESS"`; presente **si y solo si** `targetPolicy = CLOSE_ON_TARGET` | [N] Enmienda 1 §3.1; [DHR] Q-CV01-2 |
| Convocatoria solo `IN_KIND` | Sin `currency`, `targetAmount`, `targetPolicy` ni `onTargetReached` (y sin ledger) | [N] N2 |
| Nombres de campo JSON | Los nombres del dominio citados en esta tabla | Consecuencia de Q-CV01-2; su disposición (plana o anidada) depende de Q-CV01-12 |

> **Consecuencia aceptada de Q-CV01-2:** los literales de los enums pasan a ser contrato HTTP público. La Enmienda 1 §3.1 decía que el nombre exacto de `onTargetReached` "es de implementación"; para la API **ya no lo es**. Renombrar un valor del enum en el dominio exige cambiar esta ficha.

### 3.2 Autorización y operación

| Elemento | Contenido | Respaldo |
|---|---|---|
| Autorización | Exclusivamente `ADMINISTRATOR` de la organización del path, evaluado por `ConvocatoriaAuthorizationPolicy` (no por las políticas de `core`). El respaldo del `REPRESENTATIVE` no aplica | [N] ADR-037 §4, §5 |
| Precondición | La organización debe estar `VERIFIED` | [N] ADR-037 §5; [N] ADR-038 §2.5 |
| Operación | El controller delega en el caso de uso de creación; no coordina módulos ni decide reglas | [N] ADR-041 §2.1, §2.8 |
| Efecto | Crea la convocatoria en `OPEN` y, si acepta `MONETARY`, su ledger; con audit log y registro de comando, en una sola transacción | [N] ADR-037 §2.1; N2; Enmienda 1 §3.5 |
| `publicCode` | Generado por el servidor con un generador criptográficamente seguro, **≥128 bits de entropía efectiva**, sin derivarse de ningún dato de la convocatoria. **Longitud y alfabeto no forman parte del contrato**: el cliente lo trata como string opaco | [DHR] Q-CV01-10; Q-CV01-10a |
| `campaignRef` | Generado por el servidor; string opaco | [N] ADR-037 §2.1 |

### 3.3 Respuesta de éxito

| Elemento | Contenido | Respaldo |
|---|---|---|
| Código | `201 Created` | [DHR] códigos ratificados |
| Cuerpo | JSON con **exactamente** `{"campaignRef": string, "publicCode": string}`. Ningún otro campo: ni `status`, ni eco de la configuración | [DHR] Q-CV01-1; [N] N12 |
| Duplicado (mismo `Command-Id`) | Mismo código y **mismo cuerpo** que la ejecución original, sin repetir el efecto. Posible porque N12 guarda exactamente `campaignRef` y `publicCode` | [DHR] Q-T33-3; [N] N12 |
| Header `Location` | No forma parte del contrato | Sin respaldo |
| Tratamiento del `publicCode` | Secreto bearer: no en logs, analítica, URLs externas ni mensajes de error | [N] ADR-041 §2.7 |

### 3.4 Errores

Todos con `ProblemDetail` (RFC 7807) [DHR] DH-02.

| Condición | Código | Excepción de dominio (código actual) | Respaldo |
|---|---|---|---|
| Sin JWT, JWT inválido o expirado, cuenta `INACTIVE` | 401 | (filtro JWT; `InactiveAccountException`) | [DHR] DH-51; Q-T36-2 |
| Llamante sin rol `ADMINISTRATOR` en la organización del path | 403 | `ActorRoleNotAllowedException` | [DHR] DH-51; Q-CV01-6 |
| `organizationId` del path distinto de la organización del llamante, **o inexistente** | 403 (nunca 404: la frontera de autorización se evalúa antes de revelar si el recurso existe) | `ActorNotInCampaignOrganizationException` | [DHR] Q-CV01-6; DH-51 |
| Organización no `VERIFIED` | 409 | `OrganizationNotVerifiedException` | [DHR] Q-CV01-5 |
| `Command-Id` ya usado por otro tipo de comando | 409 | `CommandIdReusedForDifferentCommandException` | [DHR] Q-CV01-7 |
| `Command-Id` ausente, vacío o que no es UUID | 400 | — (validación de API) | [DHR] Q-T33-4 |
| Cuerpo ausente, JSON inválido, tipos incorrectos, enum desconocido, `targetAmount` que no son dígitos o que no cabe en el entero del dominio | 400 | — (validación de API) | [DHR] códigos ratificados; Q-CV01-4 |
| `title` ausente, vacío o de más de 200 caracteres | 400 | `CampaignTitleRequiredException`, `CampaignTitleTooLongException` | [DHR] Q-CV01-4; Q-CV01-11 |
| Fechas ausentes o `startDate ≥ endDate` | 400 | `InvalidCampaignDateRangeException` | [DHR] Q-CV01-4 |
| `startDate` o `endDate` anteriores a `now − 5 min` | 400 | **Sin excepción en el código** (deuda D-2, §7) | [DHR] Q-CV01-4; Q-CV01-9a |
| `acceptedDonationTypes` vacío | 400 | `EmptyAcceptedDonationTypesException` | [DHR] Q-CV01-4 |
| `MONETARY` sin medios de pago, sin `targetAmount` o sin `targetPolicy` | 400 | `IncompleteMonetaryConfigurationException` | [DHR] Q-CV01-4 |
| `MONETARY` sin `currency` | 400 | `MissingCampaignCurrencyException` | [DHR] Q-CV01-4 |
| `currency` que no es un código ISO 4217 de tres letras | 400 | **Sin excepción en el código** (deuda D-1, §7) | [DHR] Q-CV01-4; Q-CV01-3 |
| `targetAmount ≤ 0` | 400 | `InvalidTargetAmountException` | [DHR] Q-CV01-4; Q-CV01-8 |
| `onTargetReached` presente sin `CLOSE_ON_TARGET`, o ausente con `CLOSE_ON_TARGET` | 400 | `InvalidOnTargetReachedException` | [DHR] Q-CV01-4 |
| Solo `IN_KIND` con `currency`, `targetAmount`, `targetPolicy` u `onTargetReached` | 400 | `MonetaryTermsWithoutMonetaryDonationTypeException` | [DHR] Q-CV01-4 |
| Fallo interno | 500 genérico, sin detalles | — | [DHR] códigos ratificados |

- **409 no implica reintento automático** del cliente [N] ADR-041 §2.6.
- 422 no se usa: sigue abierto en la ficha T y Q-CV01-4 eligió 400.
- La API no inventa códigos para excepciones no nombradas [N] ADR-041 §2.5. Las dos filas marcadas "sin excepción en el código" exigen crear su excepción nombrada al corregir la deuda (regla 2.6), **no** un mapeo genérico.

## 4. Lo que esta ficha NO decide

- Edición posterior de `title`, `description`, `visibility` o fechas: no existe operación definida (`implementation_plan.md` §1.3).
- Edición de configuración, cierre, asignación de responsables: fichas aparte.
- Lectura pública (CV-07) y lectura administrativa del listado.
- El formato concreto del `publicCode` (Q-CV01-10a): ya no hay consumidor pendiente de revisar (§6); queda pendiente solo de aprobación humana (nota al final de §5). En cualquier caso, no forma parte del contrato.
- Rate limiting, CORS, `correlationId`: transversales abiertos (DH-56, DH-04).

## 5. Preguntas abiertas

### Q-CV01-12 — Estructura del cuerpo

Ninguna fuente fija si la configuración versionada viaja plana o anidada. El comando actual del caso de uso la recibe anidada (`configuration{...}`), pero eso es [IMPL] y no respalda nada.

| Opción | Ventaja | Inconveniente |
|---|---|---|
| (a) **Plano**: todos los campos al mismo nivel | Más simple para el cliente | La frontera "configuración versionada" (Enmienda 1 §3.2) no se ve en el contrato |
| (b) **Anidado**: `title`, `description`, `visibility`, `startDate`, `endDate` arriba; `acceptedDonationTypes`, `acceptedPaymentMethods`, `currency`, `targetAmount`, `targetPolicy`, `onTargetReached` dentro de `configuration` | Refleja qué parte es versionada y podrá reutilizarse en la futura ficha de edición de configuración | Un nivel más para el cliente |

### Q-CV01-13 — Medios de pago en una convocatoria solo `IN_KIND`

La Enmienda 1 §3.1 hace obligatorio `acceptedPaymentMethods` solo con `MONETARY`, pero no dice qué pasa si llega en una convocatoria solo `IN_KIND`. El código lo **acepta y lo guarda** (reportado en `implementation_plan.md` §16), mientras que sí rechaza `currency`, `targetAmount`, `targetPolicy` y `onTargetReached` en ese caso.

| Opción | Consecuencia |
|---|---|
| (a) **Rechazar con 400** | Coherente con el resto de campos monetarios. Requiere cambio en el dominio |
| (b) **Aceptar y guardar** (como el código) | Sin cambio de código; la convocatoria guarda medios de pago que no usa hasta que se añada `MONETARY` |

### Q-CV01-14 — `visibility` obligatoria o con valor por defecto

ADR-037 §2.1 define la visibilidad, pero ninguna fuente dice si es obligatoria. El dominio **no valida** que venga informada: un valor nulo se guardaría.

| Opción | Consecuencia |
|---|---|
| (a) **Obligatoria**; ausente → 400 | El cliente decide siempre de forma explícita. Requiere excepción nombrada en el dominio |
| (b) Por defecto `PRIVATE_LINK` | Lo más restrictivo si el cliente la omite; es una decisión de producto nueva |
| (c) Por defecto `PUBLIC` | Riesgo: una convocatoria pensada como privada aparecería en el descubrimiento por omisión |

### Q-CV01-15 — Longitud máxima de `description`

R1 la hace opcional, sin límite. El código tampoco lo limita. Un campo libre sin tope en un endpoint de escritura es superficie de abuso de tamaño de payload.

| Opción | Consecuencia |
|---|---|
| (a) **Fijar un máximo** (número a decidir) → 400 si se excede | Requiere excepción nombrada en el dominio |
| (b) Sin límite propio; solo el límite general de tamaño del cuerpo del servidor | Sin cambio de dominio; el límite general no está definido en ningún documento |

### Nota sobre Q-CV01-10a (formato concreto del `publicCode`)

No es una pregunta de contrato: el contrato fija solo la entropía (≥128 bits). Revisados los consumidores (§6), ninguno impide alargar el código. Propuesta para la corrección de la deuda D-3, **pendiente de aprobación humana**: 26 caracteres con el alfabeto actual de 32 símbolos (`0-9`, `A-Z` sin `I`, `L`, `O`, `U`), es decir 130 bits. Implica cambiar `PUBLIC_CODE_LENGTH` y la aserción de `CreateConvocatoriaIntegrationTest.java:68`.

## 6. Dependencias

| Dependencia | Estado | Qué bloquea |
|---|---|---|
| Enmienda 1 de ADR-041 (DH-01, DH-02, DH-51, ficha T) | Borrador | Que la ficha sea normativa |
| JWT / autenticación HTTP (ADR-038 §2.7) | No existe en `develop` (`estado-fase6.md` §0) | Llamar al endpoint de punta a punta |
| `OrganizationVerificationPort` real | Existe en `develop` (`e269985`) | — (ya no bloquea) |
| Consumidores del `publicCode` (para Q-CV01-10a) | Revisados el 2026-10-07: `paxfide-web` (`develop`, `classifier.ts`) acepta 1–255 caracteres; **no existe repositorio de `paxfide-mobile`** (confirmado por Carlos), y ADR-043 (mobile) solo fija "longitud máxima defensiva", sin número; el único supuesto de longitud en el backend es `CreateConvocatoriaIntegrationTest.java:68` | Nada. El formato concreto queda pendiente solo de aprobación humana (§5, nota a Q-CV01-10a) |
| Numeración de ADR | `ADR-042-frontend-web-paxfide-web.md` existe en `Toffy22Cj/PaxFide` (APROBADO, 2026-09-28) y contiene la renumeración 037–042; **colisiona** con `ADR-042-orquestacion-centralizada-reintentos-proyeccion.md` del backend. Decisión humana pendiente (como ADR-043) | Nada en esta ficha: cita ADR-037 y ADR-041, que existen con esos números |

*Corrección del 2026-10-07:* una versión anterior de esta tabla decía que `ADR-042-frontend-web-paxfide-web.md` no existía y que el repositorio de `paxfide-mobile` estaba sin identificar. Lo primero era falso (está en otro repositorio); lo segundo se resolvió: no existe.

## 7. Deudas de implementación (código actual vs. este contrato)

Verificado por lectura en `develop` HEAD `0d4f428`, sin ejecutar tests. **Ninguna corrección está autorizada** (decisión humana del 2026-10-06): cada una requerirá su `fix/` con test de regresión que reproduzca la divergencia antes de corregirla.

| # | Contrato | Código actual | Evidencia |
|---|---|---|---|
| D-1 | `currency` ISO 4217 de tres letras | Acepta cualquier texto no vacío | `ConvocatoriaConfiguration` (solo comprueba `null`/vacío) |
| D-2 | Fechas no anteriores a `now − 5 min` | Solo exige `startDate < endDate` | `Convocatoria.create` |
| D-3 | `publicCode` con ≥128 bits de entropía | `SecureRandom` y no derivado de datos (cumple), pero **10 caracteres de un alfabeto de 32 = 50 bits** | `ConvocatoriaLifecycleService.newPublicCode`, `PUBLIC_CODE_LENGTH = 10`; `CreateConvocatoriaIntegrationTest.java:68` fija la longitud 10 |
| D-4 | Según Q-CV01-14 | `visibility` nula aceptada | `Convocatoria.create` no la valida |
| D-5 | Según Q-CV01-13 | Medios de pago aceptados en convocatorias solo `IN_KIND` | `ConvocatoriaConfiguration` |
| D-6 | Según Q-CV01-15 | `description` sin límite | `Convocatoria.create` |

**Coherente con el contrato (sin deuda):** `title` máximo 200 (`TITLE_MAX_LENGTH = 200`); `targetAmount > 0`; resultado `{campaignRef, publicCode}` guardado para duplicados (N12); literales de los enums iguales a los de §3.1.

**Observación (no es decisión ni deuda):** el caso de uso evalúa la autorización **antes** del registro de idempotencia. Un duplicado enviado por una cuenta que ya perdió el rol `ADMINISTRATOR` recibe 403, no la respuesta original. Es coherente con DH-51 (la autorización se evalúa en cada petición) y no contradice T-33, que describe duplicados del mismo llamante autorizado. Se registra para que no se interprete como incumplimiento de T-33.

## 8. Criterio de cierre

1. Q-CV01-12 a Q-CV01-15 respondidas y registradas en §9.
2. Contrato incorporado a la matriz (consolidación aditiva; requiere autorización).
3. Tests de contrato del endpoint, cuando exista:
   - éxito con convocatoria `MONETARY` y con convocatoria solo `IN_KIND`;
   - duplicado con el mismo `Command-Id` → mismo código y cuerpo byte a byte, un solo efecto;
   - `Command-Id` de otro comando → 409;
   - organización ajena e inexistente → **mismo** 403;
   - organización no `VERIFIED` → 409;
   - cada fila 400 de §3.4, con aserción negativa de que no se escribió nada (convocatoria, ledger, audit log, registro de comando);
   - la respuesta 201 nunca contiene campos fuera de `campaignRef` y `publicCode`.
4. D-1 a D-3 corregidas (y D-4 a D-6 según las respuestas) antes de declarar que el código cumple la ficha.

## 9. Registro de respuestas humanas

| Pregunta | Respuesta | Fecha | Efecto |
|---|---|---|---|
| Q-CV01-1 | (a) Exactamente `{campaignRef, publicCode}` | 2026-10-06 | §3.3 |
| Q-CV01-2 | (a) Literales del dominio tal cual (`PUBLIC`, `PRIVATE_LINK`, …), aceptando que pasan a ser contrato | 2026-10-06 | §3.1 |
| Q-CV01-3 | (a) Entero en unidades mínimas de la moneda (String de dígitos) + `currency` ISO 4217 de tres letras | 2026-10-06 | §3.1; deuda D-1 |
| Q-CV01-4 | (a) Violaciones de invariantes del cuerpo → 400 `ProblemDetail` | 2026-10-06 | §3.4 |
| Q-CV01-5 | (a) Organización no `VERIFIED` → 409 | 2026-10-06 | §3.4 |
| Q-CV01-6 | (a) 403 si la organización no corresponde al llamante y también si no existe | 2026-10-06 | §3.4 |
| Q-CV01-7 | (a) `Command-Id` usado por otro tipo de comando → 409 | 2026-10-06 | §3.4 |
| Q-CV01-8 | `targetAmount > 0` | 2026-10-06 | §3.1 |
| Q-CV01-9 | No se permiten fechas anteriores al momento de creación | 2026-10-06 | §3.1 |
| Q-CV01-9a | (c) Tolerancia de 5 minutos respecto al reloj del servidor; validación de creación, no máquina de estados temporal | 2026-10-06 | §3.1; deuda D-2 |
| Q-CV01-10 | (a) Generación criptográficamente segura, ≥128 bits de entropía, no derivada de datos de la convocatoria | 2026-10-06 | §3.2; deuda D-3 |
| Q-CV01-10a | Sí al requisito; formato concreto (p. ej. 26 caracteres con el alfabeto actual) sujeto a revisar `paxfide-mobile`. La decisión es la entropía, no el número 26 | 2026-10-06 | §3.2, §6 |
| Q-CV01-11 | (a) `title` máximo 200 caracteres como contrato | 2026-10-06 | §3.1 |
| Q-CV01-12 | — | — | Pendiente |
| Q-CV01-13 | — | — | Pendiente |
| Q-CV01-14 | — | — | Pendiente |
| Q-CV01-15 | — | — | Pendiente |
