# Ficha L-FRONTS — Tres lecturas que piden los fronts (M-07, M-15/M-16, M-26)

**Naturaleza:** ficha breve de contrato HTTP, en el formato de `Documentos/ficha-S18-estado-verificacion-organizacion.md`. **No es un ADR, no es normativa y no autoriza código.**
**Estado (2026-10-18T06:35Z, Manuel):**
- **A1 (M-07) y A3 (M-26): CONGELADO** — Q-A1-1 a Q-A1-3 y Q-A3-1 a Q-A3-3 respondidas; registro en §8.
- **A2 (M-15/M-16): DECIDIDO, BLOQUEADO** — Q-A2-1 a Q-A2-5 respondidas, pero no se planifica hasta que Carlos responda **Q-A2-C** (§5): cambia `DonationView` de `DonationIntentReadPort` (puerto de `convocatoria` que consume `app`) y añade `createdAt` al dominio de `DonationIntent`, lo que por la regla 3.5 exige una enmienda de ADR-037 o una decisión expresa de Carlos de que no la requiere.

Congelado **no** autoriza código: falta el plan de implementación aprobado (§9).
**Fecha:** 2026-10-18 (UTC).
**Autor del borrador:** Manuel (backend/BD, identidad), con asistencia de Claude.
**Base verificada:** `Toffy22Cj/Donaciones` `develop` **`4bef7be`** (leído de una copia exacta del commit con `git archive`; ningún archivo del repo se modificó). Las rutas de este documento son relativas a la raíz de ese repositorio salvo las marcadas `paxfide-web:` (repo `Toffy22Cj/PaxFide`, `develop` `41272c4`).
**Origen:** documento "Funciones de la app móvil y pantallas del empleado" (Carlos, 2026-10-16): M-07 (insignia "verificada"; responsable de backend: Manuel), M-15/M-16 (Mis donaciones y su detalle), M-26 (Cuenta: correo). Bocetos web en `~/Documentos/bocetos-web-manuel.md` §2, §3 y §6, que marcan estos datos como "DEPENDE DE BACKEND".
**Reglas comunes:** las de `Documentos/referencia-api-v1.md` §0. Las tres son lecturas: sin `Command-Id`. En rutas protegidas, toda negativa da el mismo 403 (DD-01).

---

## 1. Problema que resuelve

| Pantalla | Qué falta hoy | Evidencia |
|---|---|---|
| M-07: convocatoria pública con la insignia "organización verificada" | Ni `GET /public/campaigns` ni `GET /public/campaigns/{publicCode}` dicen nada de la verificación: solo el nombre de la organización | `app/src/main/java/com/traceability/app/web/campaign/PublicCampaignDiscoveryController.java:27-29` (campos del ítem); `app/src/main/java/com/traceability/app/web/campaign/PublicCampaignController.java:29-33` (campos del detalle) |
| M-15/M-16: Mis donaciones y su detalle | La web no puede mostrar fecha, organización, medio de donación ni enlace a la convocatoria | `app/src/main/java/com/traceability/app/web/donation/DonationDtos.java:24-25` (seis campos) |
| M-26: Cuenta | La web no puede mostrar el correo de la cuenta | `api/src/main/java/com/traceability/api/auth/MeController.java:14-15,24-25` ("Sin email, nombre ni estado") |

---

## 2. Fuentes aplicables

| Fuente | Estado | Qué aporta |
|---|---|---|
| `Documentos/ADR-041-api-frontend-contratos-http.md` §2.7 (línea 57) | Aprobado | `publicCode` y `trackingCode` son secretos tipo bearer: "no en logs, no en analítica innecesaria, no en URLs externas, no en mensajes de error" |
| `Documentos/plan-b6-a-convocatoria-http.md` §2.3 (líneas 62-75) | Plan ejecutado (B6-a) | Campos de CV-07; **excluye** `publicCode`, `organizationRef` e ids internos (línea 68). La ficha CV-07 **no está en el repositorio** (línea 70, Q-B6A-5) |
| `Documentos/fichas-p2-endpoints.md` línea 36-37 | Ficha P2.6 | El descubrimiento nunca lista `PRIVATE_LINK` y solo `OPEN` (DD-52); sin `campaignRef` ni `organizationRef` |
| `Documentos/decisiones-delegadas-2026-10.md` DD-21 (línea 48) | Ratificada | `/account/donations`: una página de 100, "ordenada por `intentId`" |
| `paxfide-web:Documentos/ficha-N1-quien-soy.md` | **CONGELADO** (línea 4) | `GET /me` devuelve exactamente cuatro campos; "**Ningún otro campo** (`email`, `fullName`…)" (línea 40); Q-N1-2 descartó la opción con `email` por ser PII (líneas 64-65); test negativo previsto: "la respuesta nunca contiene `email`" (línea 104) |
| `Documentos/referencia-api-v1.md` §1 | Referencia de lo implementado | `POST /auth/register` guarda el email sin espacios y en minúsculas (DD-74) |
| `reglas-equipo-y-agentes.md` §2.1 y §2.6 | Vigente | No inventar; todo estado con salida |

---

## 3. Lo que hay en el código (verificado en `4bef7be`)

### 3.1 A1 · M-07 — Verificación de la organización de una convocatoria

**(a) Crear una convocatoria exige `VERIFIED`: SÍ.**
- `convocatoria/src/main/java/com/traceability/convocatoria/application/service/ConvocatoriaLifecycleService.java:89` (Javadoc: "`ADMINISTRATOR` de una organización `VERIFIED`") y `:95-96`: `OrganizationVerification.requireVerified(…, organizationVerificationPort.isVerified(…))` antes de `Convocatoria.create` (`:97`) y de insertarla (`:100`).
- `convocatoria/src/main/java/com/traceability/convocatoria/domain/model/OrganizationVerification.java:15-18`: si no está verificada, lanza `OrganizationNotVerifiedException` (409 por `app/src/main/java/com/traceability/app/web/ConvocatoriaApiErrorMappings.java:91`).
- El adaptador real responde `true` **solo** con `VERIFIED`; una organización inexistente o una referencia vacía dan `false`: `app/src/main/java/com/traceability/app/infrastructure/identity/OrganizationVerificationAdapter.java:16-17,29-38`.
- `Convocatoria.create` solo se invoca en producción desde ese punto: búsqueda de `Convocatoria.create(` en `*/src/main` → únicamente `ConvocatoriaLifecycleService.java:97`. La otra fábrica, `Convocatoria.reconstitute`, solo la usa el mapper de persistencia (`convocatoria/src/main/java/com/traceability/convocatoria/infrastructure/persistence/mongo/mapper/ConvocatoriaMapper.java:58`).
- Además, crear una **intención de donación** vuelve a exigir `VERIFIED`: `convocatoria/src/main/java/com/traceability/convocatoria/application/service/DonationIntentService.java:129-130`.

**(b) `VERIFIED` es terminal: SÍ, en el dominio.**
- `identity/src/main/java/identity/domain/model/Organization.java:275-298`: `verify()`, `reject()` y `requestInformation()` lanzan `InvalidVerificationTransitionException` si el estado ya es `VERIFIED` o `REJECTED` (`:276`, `:284`, `:293`). Son las únicas asignaciones de `verificationStatus` fuera del constructor (búsqueda de `verificationStatus =` en `identity/src/main` y `app/src/main`: `:40`, `:279`, `:287`, `:296`).
- Las organizaciones nacen en `PENDING_VERIFICATION`: `Organization.java:84` (la segunda fábrica, `:91-98`, delega en la primera, `:96`).
- **Límite:** `Organization.reconstitute` (`Organization.java:48-61`, "uso exclusivo de adaptadores de persistencia") acepta **cualquier** estado que haya en la base; la terminalidad la garantiza el dominio, no la base de datos. Un cambio manual en Mongo la saltaría.

**(c) ¿Hay algún camino por el que una convocatoria pertenezca a una organización no verificada?**

| Camino | Resultado | Evidencia |
|---|---|---|
| Semilla de escenario (`demo-seed`) | **No.** Todo por HTTP ("**Nunca** hay inserciones directas en la base"); verifica la organización (`:150-151`) **antes** de crear las tres convocatorias (`:165-170`). La organización pendiente (`:139-141`) no recibe convocatorias | `app/src/main/java/com/traceability/app/bootstrap/DemoScenarioSeeder.java:42-48,55,136-151,165-170` |
| Semilla `dev` (`DemoSeedRunner`) | **No.** Crea una organización `PENDING_VERIFICATION` y cuentas; no crea convocatorias (sin ninguna mención a convocatorias/campañas en el archivo) | `app/src/main/java/com/traceability/app/bootstrap/DemoSeedRunner.java:28-29,36-37,91` |
| Migraciones | **No hay.** Búsqueda de `mongock`, `ChangeUnit`, `@ChangeSet`, `flyway`, `liquibase` en `*.java` y `*.xml`: sin resultados | — |
| Tests del módulo `convocatoria` | **Sí, solo en test.** El doble `FakeOrganizationVerificationPort` da **todas** las organizaciones por verificadas salvo las marcadas; las organizaciones de esos tests no existen en `identity` | `convocatoria/src/test/java/com/traceability/convocatoria/support/FakeOrganizationVerificationPort.java:8-10,25-27` |
| Tests de integración de `app` | **Sí, solo en test.** El puerto se sustituye por un mock que responde `true` para cualquier referencia | `app/src/test/java/com/traceability/app/infrastructure/convocatoria/CampaignInKindEligibilityWiringIntegrationTest.java:87,99`; `app/src/test/java/com/traceability/app/application/funds/FundsApplicationOrchestratorIntegrationTest.java:124,152` |
| Fixtures de dominio | Crean `Convocatoria` sin pasar por el servicio (sin precondición), en memoria | `convocatoria/src/test/java/com/traceability/convocatoria/domain/model/DomainFixtures.java:32` |

**Conclusión de A1:** en producción, por construcción, **toda convocatoria pertenece a una organización que estaba `VERIFIED` al crearla y que no puede dejar de estarlo** (dominio). La garantía es un **invariante de código**, no un dato almacenado en la convocatoria. Solo los tests (y una edición manual de la base) rompen la premisa.

Cómo se resuelve hoy el nombre: una consulta por convocatoria a `identity` (`identity/src/main/java/identity/application/service/OrganizationPublicNameQuery.java:26-31`), invocada por ítem en el descubrimiento (`PublicCampaignDiscoveryController.java:55`) y una vez en el detalle (`PublicCampaignController.java:29`).

### 3.2 A2 · M-15/M-16 — `GET /account/donations`

**Campos que devuelve hoy** (`DonationDtos.java:24-25`; se construyen en `app/src/main/java/com/traceability/app/web/account/AccountDonationsController.java:39-43`):
`intentId`, `campaignTitle?`, `amount` (texto), `currency`, `status`, `trackingCode?` (derivado de `fundId` + `fundsAppliedAt` en `:43`). Tope de 100 (`:24`).

La vista intermedia es `DonationView(intentId, campaignTitle, amount, currency, status, fundId, fundsAppliedAt)` (`convocatoria/src/main/java/com/traceability/convocatoria/application/query/DonationIntentReadPort.java:22-23`), que arma `DonationIntentQueryService.java:46-48`: el título sale de **una lectura de la convocatoria por intención** (`:47`).

**Campos del dominio `DonationIntent`** (`convocatoria/src/main/java/com/traceability/convocatoria/domain/model/DonationIntent.java:18-36`):
`intentId`, `fundId`, `organizationRef`, `campaignRef`, `donorRef`, `amount`, `currency`, `paymentMethod`, `confirmationSource`, `configurationVersion`, `paymentSessionId`, `providerEventId`, `expiresAt` (solo `BANK_TRANSFER`, `:78-81`), `status`, `confirmation`, `applicationTracking` (`fundsAppliedAt` y reintentos, `:211-212`), `fundingRejection` (`rejectedAt`, `reason`, `:218`), `access` (proveedor y hash del token, `:200`).
**No tiene instante de creación**: ningún campo lo guarda; `create` no recibe ni fija uno (`:71-88`, `:95-111`).

**¿`Confirmation` guarda un instante utilizable como "fecha de confirmación"? SÍ.**
- `record Confirmation(String confirmedBy, Instant confirmedAt, PaymentMethod paymentMethod, String reference)`, obligatorio y no nulo (`DonationIntent.java:222-231`).
- Es el **reloj del servidor** en el momento de confirmar, no la hora del evento del proveedor: pasarela, `GatewayPaymentService.java:77,79-81`; confirmación manual de la organización, `DonationIntentService.java:195-196` (ambos en `convocatoria/src/main/java/com/traceability/convocatoria/application/service/`).
- Se persiste: `DonationIntentDocument.java:60` y `DonationIntentMapper.java:40` (en `…/infrastructure/persistence/mongo/`).
- **Solo existe si la intención llegó a confirmarse.** `confirm()` solo actúa desde `PENDING` (`DonationIntent.java:158-168`). Una intención `PENDING`, `FAILED` o `EXPIRED_UNKNOWN` no tiene `confirmation`.
- **`FUNDING_REJECTED` conserva la confirmación** (verificado 2026-10-18):
  - en el dominio, `confirmation` solo se asigna en el constructor (`DonationIntent.java:59`) y en `confirm()` (`:167`); ninguna línea la pone a `null` (búsqueda de `confirmation =` en el archivo: solo `:59` y `:167`);
  - el paso a `FUNDING_REJECTED` exige `status = CONFIRMED` y en persistencia **solo** escribe `status`, `fundingRejectedAt` y `fundingRejectionReason` (`convocatoria/src/main/java/com/traceability/convocatoria/infrastructure/persistence/mongo/adapter/MongoDonationIntentRepositoryAdapter.java:76-82`); el único `unset` del adaptador borra campos de reintento de aplicación, no de confirmación (`:130`);
  - el estado lo documenta así: `FUNDING_REJECTED` "significa exclusivamente que la intención estaba `CONFIRMED`" (`convocatoria/src/main/java/com/traceability/convocatoria/domain/model/DonationIntentStatus.java:7-8`).
  
  Por tanto, `confirmedAt` existe en `CONFIRMED` y en `FUNDING_REJECTED`.

**Datos que pide la web, uno a uno**

| Dato | Clasificación | Evidencia |
|---|---|---|
| **Fecha de confirmación** | **Existe en el dominio; solo falta exponerla** (`confirmedAt`), y solo para las confirmadas | `DonationIntent.java:222`; `DonationIntentDocument.java:60` |
| **Fecha de creación** (para todas, y para ordenar) | **Requiere cambio de dominio**: `DonationIntent` no la guarda. El instante ya se calcula al crear (`DonationIntentService.java:131`) pero no se almacena | `DonationIntent.java:18-36` |
| **Orden cronológico** | **Requiere cambio**: la lista se ordena por `_id` (`MongoDonationIntentRepositoryAdapter.java:259-260`), que es `intentId` (`DonationIntentDocument.java:33-34`), un **UUID aleatorio** (`DonationIntentService.java:134`). Hoy el orden no es cronológico, aunque DD-21 diga "ordenada por `intentId`" | ver hallazgo H-L2 |
| **Organización** | **Existe; solo falta exponerla.** La intención guarda `organizationRef` (`DonationIntent.java:20`) y el nombre público ya se resuelve con `OrganizationPublicNamePort` (`OrganizationPublicNameQuery.java:26-31`) | — |
| **Medio de donación** | **Existe; solo falta exponerlo.** `paymentMethod` (`DonationIntent.java:25`) con valores `GATEWAY`, `BANK_TRANSFER`, `CASH` (`convocatoria/src/main/java/com/traceability/convocatoria/domain/model/PaymentMethod.java:7-9`). La web lo rotula "medio de donación" (decisión del 16 oct) | — |
| **Enlace a la convocatoria** | **Choca con una decisión vigente para las privadas.** El enlace exige el `publicCode` (`Convocatoria.java:41`), secreto bearer (ADR-041 §2.7, línea 57). CV-07 lo excluye de su respuesta (`plan-b6-a-convocatoria-http.md:68`). Pero el descubrimiento **ya** publica el `publicCode` de las `PUBLIC` y `OPEN` (`PublicCampaignDiscoveryController.java:27`; `fichas-p2-endpoints.md:36`) | Q-A2-4 |

### 3.3 A3 · M-26 — Correo de la cuenta

- `GET /me` está implementado con exactamente cuatro campos y **sin email** (`MeController.java:14-15,24-25`); lo comprueba `app/src/test/java/com/traceability/app/web/P2EndpointsHttpIntegrationTest.java:171-179` (igualdad exacta del JSON).
- La ficha N1 está **CONGELADA** y descartó el email de forma expresa (Q-N1-2; `paxfide-web:Documentos/ficha-N1-quien-soy.md:40,60-65,117`).
- `MeController` vive en `api` y solo recibe el `AuthorizationPrincipal` (`MeController.java:28`), que no lleva email.
- El correo está en `identity`: `Account.email` (`identity/src/main/java/identity/domain/model/Account.java:13`). `app` ya depende de `identity` (`OrganizationVerificationAdapter.java:4-5`), igual que `AccountDonationsController` vive en `app/web/account`.

---

## 4. Lo que esta ficha NO decide

- La insignia de la **app móvil** ni su diseño (M-07: Carlos, diseño; Adonis, móvil).
- El comprobante de donación (M-22), el panel "Mi impacto" (M-21) ni la paginación con cursor del historial.
- Cambiar el correo, verificarlo, el nombre completo de la cuenta ni ningún dato de perfil (ADR de cuenta y sesiones).
- Qué ocurre con una convocatoria si un día se permite revocar la verificación (hoy no existe: §3.1 b).
- El texto de las etiquetas en la interfaz.

---

## 5. Preguntas abiertas

### Q-A1-1 — Insignia "verificada": ¿campo explícito o derivado en el front?

> **Respondida (2026-10-18T06:35Z, Manuel): (a), con el puerto existente `OrganizationVerificationPort`, no ampliando `OrganizationPublicNamePort`.** Ver §8.

| Opción | Consecuencia |
|---|---|
| **(a) Campo `organizationVerified` (booleano), calculado con el estado real de la organización** — recomendada | La insignia es una afirmación del backend, no una suposición del front (regla 2.1). Sobrevive a un cambio futuro del invariante (revocación, H-S18-2). Coste: ampliar la consulta por convocatoria que ya existe para el nombre (`OrganizationPublicNameQuery.java:26-31`) para que devuelva también el estado, sin peticiones nuevas a la base. Enmienda de CV-07 y de la ficha del descubrimiento (P2.6) |
| (b) Derivarlo en el front: toda convocatoria pública muestra la insignia | Cero cambios en el backend. Pero la web afirma algo que la API no dice; si el invariante cambia, la insignia miente sin que ningún test lo detecte. Los dobles de los fronts (`paxfide-web:scripts/fake-backend.js`) tendrían que reproducir la regla |
| (c) Campo constante `true` en el DTO | Igual que (b), pero escondido en el backend: un test lo vería siempre verde aunque la organización cambiase |

La opción (a) se decidió con el puerto existente OrganizationVerificationPort, no ampliando OrganizationPublicNamePort (ver §8).

### Q-A1-2 — Forma del campo (si Q-A1-1 = a)

> **Respondida (2026-10-18T06:35Z, Manuel): (a).** Ver §8.

| Opción | Consecuencia |
|---|---|
| **(a) Booleano `organizationVerified`** — recomendada | Responde justo la pregunta de la insignia; no publica estados internos de identidad |
| (b) Enumerado `organizationVerificationStatus` | Publicaría `PENDING_VERIFICATION`, `NEEDS_MORE_INFORMATION` y `REJECTED`, que hoy no pueden darse en una convocatoria (§3.1) y no son información pública |

### Q-A1-3 — Dónde queda registrada la enmienda de CV-07

> **Respondida (2026-10-18T06:35Z, Manuel): (a).** Ver §8.

La ficha CV-07 no está en el repositorio (`plan-b6-a-convocatoria-http.md:70`, Q-B6A-5).

| Opción | Consecuencia |
|---|---|
| **(a) Nota fechada en `plan-b6-a-convocatoria-http.md` §2.3 y en `fichas-p2-endpoints.md` (P2.6), más la fila de `referencia-api-v1.md` §5** — recomendada | Cambios de solo añadir, junto a donde ya está el contrato; mismo patrón que la nota a DD-68 de S-18 |
| (b) Reconstruir la ficha CV-07 completa en `Documentos/` | Cierra Q-B6A-5, pero es reescribir un contrato ya ejecutado: más trabajo y riesgo de contradecir el plan B6-a |

### Q-A2-1 — ¿Qué fecha se muestra y con qué se ordena?

> **Respondida (2026-10-18T06:35Z, Manuel): (c).** Ver §8.

| Opción | Consecuencia |
|---|---|
| (a) Solo `confirmedAt?` (exponer lo que ya existe) | Sin cambio de dominio. Las `PENDING`, `FAILED` y `EXPIRED_UNKNOWN` no tienen fecha; no permite ordenar toda la lista |
| (b) Solo `createdAt` (cambio de dominio) | Todas las intenciones nuevas tienen fecha; las ya guardadas no (campo opcional, sin migración). Cambio en `DonationIntent`, documento y mapper |
| **(c) Las dos: `createdAt?` (nuevo) y `confirmedAt?` (existente)** — recomendada | "Donaste el…" para todas las nuevas y "Confirmada el…" para las confirmadas. Permite ordenar por `createdAt` (Q-A2-5). Coste: el cambio de dominio de (b) |

### Q-A2-2 — Organización

> **Respondida (2026-10-18T06:35Z, Manuel): (a).** Ver §8.

| Opción | Consecuencia |
|---|---|
| **(a) `organizationName?` con `OrganizationPublicNamePort`** — recomendada | Solo exposición. Una consulta más a `identity` por intención (hasta 100), como ya pasa con el título (`DonationIntentQueryService.java:47`) |
| (b) No exponerla | La tarjeta sigue sin organización |

### Q-A2-3 — Medio de donación

> **Respondida (2026-10-18T06:35Z, Manuel): (a).** Ver §8.

| Opción | Consecuencia |
|---|---|
| **(a) `paymentMethod` (`GATEWAY`, `BANK_TRANSFER`, `CASH`)** — recomendada | Solo exposición; los literales del enum pasan a ser contrato público. La web los traduce |
| (b) No exponerlo | Sin cambio |

### Q-A2-4 — Enlace a la convocatoria (`publicCode`)

> **Respondida (2026-10-18T06:35Z, Manuel): (b), según la visibilidad actual al leer.** Ver §8.

| Opción | Consecuencia |
|---|---|
| (a) `publicCode` de todas | Choca con ADR-041 §2.7 para las `PRIVATE_LINK`: el secreto volvería a salir en una respuesta autenticada; quien robe la sesión obtiene todos los enlaces privados donados |
| **(b) `publicCode` solo si la convocatoria es `PUBLIC`** — recomendada | Coherente con el descubrimiento, que ya los publica (`PublicCampaignDiscoveryController.java:27`). Las privadas, sin enlace. Hay que leer la visibilidad en la misma consulta que el título. Una `PUBLIC` cerrada sigue viéndose por código (DD-52) |
| (c) No exponerlo | Sin enlace en ningún caso; el donante busca la convocatoria por su cuenta |

### Q-A2-5 — Orden del historial

> **Respondida (2026-10-18T06:35Z, Manuel): (a).** Ver §8.

| Opción | Consecuencia |
|---|---|
| **(a) Por `createdAt` descendente (las nuevas primero), con las antiguas sin fecha al final** — recomendada si Q-A2-1 = (b) o (c) | Orden útil; enmienda DD-21 |
| (b) Mantener el orden por `_id` | Sigue siendo un orden aleatorio (H-L2) |

### Q-A3-1 — Correo de la cuenta: ¿ruta nueva o reabrir N1?

> **Respondida (2026-10-18T06:35Z, Manuel): (a).** Ver §8.

| Opción | Consecuencia |
|---|---|
| **(a) Ruta nueva `GET /api/v1/account` → `{email}`, `Cache-Control: no-store`** — recomendada | N1 sigue intacta y su test exacto (`P2EndpointsHttpIntegrationTest.java:171-179`) no cambia. El correo **solo** se lee cuando la pantalla Cuenta lo pide, no en cada página. Sin parámetro de ruta: solo la propia cuenta (sin riesgo de leer el de otro). Vive en `app/web/account` junto a `AccountDonationsController`. Requiere añadir la ruta a `PROTECTED` del inventario de rutas |
| (b) Reabrir N1 y añadir `email` a `GET /me` | Cambia una decisión congelada (Q-N1-2) y su test. `GET /me` se llama al entrar en cada página del panel (`paxfide-web:src/lib/auth/usePrincipal.ts`), así que el correo viajaría en todas ellas. `MeController` (en `api`) necesitaría un puerto nuevo, porque el principal no lleva email |

### Q-A3-2 — ¿Correo completo o enmascarado?

> **Respondida (2026-10-18T06:35Z, Manuel): (a).** Ver §8.

| Opción | Consecuencia |
|---|---|
| **(a) Completo** — recomendada | Es el dato de la propia cuenta, para saber con qué correo inició sesión. Riesgo acotado por (a) de Q-A3-1: no-store, solo su dueño, nunca en logs |
| (b) Enmascarado como en las invitaciones (`emailMasked`) | Menos exposición si alguien mira la pantalla; pero el usuario puede no reconocer su propio correo |

### Q-A3-3 — Campos de `GET /account` (si Q-A3-1 = a)

> **Respondida (2026-10-18T06:35Z, Manuel): (a).** Ver §8.

| Opción | Consecuencia |
|---|---|
| **(a) Solo `email`** — recomendada | Lo mínimo para M-26; lo demás ya está en `/me` |
| (b) `email` y `status` | `status` no aporta: una cuenta `INACTIVE` recibe 401 (`referencia-api-v1.md` §0) |

### Q-A2-C — Para Carlos: ¿el cambio de A2 necesita enmienda de ADR-037? (abierta; bloquea A2)

Lo decidido en Q-A2-1 a Q-A2-5 obliga a:
- añadir `createdAt` al dominio de `DonationIntent` (`DonationIntent.java:18-36`), a su documento y a su mapper;
- ampliar `DonationView` de `DonationIntentReadPort` (`convocatoria/src/main/java/com/traceability/convocatoria/application/query/DonationIntentReadPort.java:22-23`), puerto de `convocatoria` que consume `app` (`AccountDonationsController.java:41-43`), con `createdAt`, `confirmedAt`, `organizationRef` (para el nombre), `paymentMethod` y la visibilidad y el `publicCode` de la convocatoria.

| Opción | Consecuencia |
|---|---|
| (a) Enmienda de ADR-037 (regla 3.5) | Registro formal del campo nuevo del agregado y del contrato del puerto; A2 espera a que se apruebe |
| (b) Decisión expresa de Carlos: añadir campos de **solo lectura** que no cambian invariantes ni transiciones no requiere enmienda | A2 se planifica en cuanto se registre la decisión; queda como precedente para otros campos de lectura |

Sin recomendación de este borrador: es una decisión de arquitectura que corresponde a Carlos.

---

## 6. Hallazgos registrados (fuera del alcance; no se resuelven aquí)

- **H-L1 — La garantía de M-07 es un invariante de código, no de datos.** `Organization.reconstitute` acepta cualquier estado (`Organization.java:48-61`), y los tests de `convocatoria` y `app` crean convocatorias de organizaciones que no existen en `identity` (§3.1 c). Si algún día se admite revocar la verificación, hay que decidir qué pasa con sus convocatorias.
- **H-L2 — El historial no está en orden cronológico.** DD-21 dice "ordenada por `intentId`" (`decisiones-delegadas-2026-10.md:48`), pero `intentId` es un UUID aleatorio (`DonationIntentService.java:134`) y la consulta ordena por `_id` (`MongoDonationIntentRepositoryAdapter.java:259-260`). El orden es estable pero sin sentido para el usuario.
- **H-L3 — La ficha CV-07 sigue sin estar en el repositorio** (`plan-b6-a-convocatoria-http.md:70`, Q-B6A-5).
- **H-L4 — Coste por elemento.** El historial ya hace una lectura de convocatoria por intención (`DonationIntentQueryService.java:47`) y el descubrimiento una lectura de organización por ítem (`PublicCampaignDiscoveryController.java:55`). Con lo decidido (§8), el descubrimiento añade **otra** lectura por ítem (`isVerified`, Q-A1-1; páginas de 20) y el historial una por intención para `organizationName` (Q-A2-2; máx. 100). Aceptado por Manuel.

---

## 7. Tests previstos (rojos primero, cuando haya plan aprobado)

Se concretan tras las respuestas. Con las opciones recomendadas:

1. **A1:** `GET /public/campaigns` y `GET /public/campaigns/{publicCode}` llevan `organizationVerified: true` para una organización verificada; test con una organización en otro estado (como los casos de `app/src/test/java/com/traceability/app/infrastructure/identity/OrganizationVerificationWiringIntegrationTest.java:117-142` para el puerto) que dé `false`, para que el campo no sea una constante.
2. **A2:** el historial trae `createdAt` en las nuevas, `confirmedAt` solo en las confirmadas, `organizationName`, `paymentMethod`, y `publicCode` **solo** en las `PUBLIC`; una donación a una `PRIVATE_LINK` no lo trae (aserción explícita). Orden: la más reciente primero.
3. **A3:** `GET /account` → 200 con el correo de la cuenta del JWT y `no-store`; sin JWT o `INACTIVE` → 401; la respuesta de `GET /me` sigue siendo exactamente la de `P2EndpointsHttpIntegrationTest.java:176-177`. El correo no aparece en ningún log (captura de logs, como en B6-a, `plan-b6-a-convocatoria-http.md:124`).
4. Inventario de rutas: `GET /api/v1/account` en `PROTECTED`.

---

## 8. Decisiones

Respondidas por Manuel (responsable de backend/BD e identidad) el **2026-10-18T06:35Z**.

| # | Decisión | Consecuencia |
|---|---|---|
| Q-A1-1 | **(a)**, con un cambio: `organizationVerified` se calcula con el puerto **existente** `OrganizationVerificationPort.isVerified(…)` (`convocatoria/src/main/java/com/traceability/convocatoria/application/port/out/OrganizationVerificationPort.java:12`). **No** se amplía `OrganizationPublicNamePort`: está en `contracts`, lo usan `app` e `identity`, y cambiarlo exigiría un ADR (regla 3.5) | Una consulta más por convocatoria en el descubrimiento (páginas de 20), además de la del nombre |
| Q-A1-2 | **(a)** Booleano `organizationVerified` | No se publican los estados internos de identidad |
| Q-A1-3 | **(a)** Notas fechadas, **solo añadidas**, en `plan-b6-a-convocatoria-http.md` §2.3, `fichas-p2-endpoints.md` (P2.6) y `referencia-api-v1.md` §5 | Ningún texto existente se reescribe |
| Q-A2-1 | **(c)** `createdAt?` (nuevo) y `confirmedAt?` (existente) | Cambio de dominio; pendiente de Q-A2-C |
| Q-A2-2 | **(a)** `organizationName?` | Una lectura de `identity` por intención (máx. 100) |
| Q-A2-3 | **(a)** `paymentMethod` | Los literales `GATEWAY`, `BANK_TRANSFER`, `CASH` pasan a ser contrato público |
| Q-A2-4 | **(b)** `publicCode` solo si la convocatoria es `PUBLIC`, **según su visibilidad actual al leer** | Las `PRIVATE_LINK` nunca lo traen (ADR-041 §2.7) |
| Q-A2-5 | **(a)** Por `createdAt` descendente; las antiguas sin fecha, al final | Enmienda de DD-21 |
| Q-A3-1 | **(a)** `GET /api/v1/account`, `Cache-Control: no-store`; la ficha N1 queda intacta | Ruta nueva en `PROTECTED` del inventario |
| Q-A3-2 | **(a)** Correo completo | Solo para la propia cuenta (sin parámetro de ruta) |
| Q-A3-3 | **(a)** Solo `email` | — |
| Q-A2-C | **Abierta**, para Carlos | A2 bloqueado hasta su respuesta |

---

## 9. Criterio de cierre

- **A1 y A3:** CONGELADO (2026-10-18T06:35Z). Congelado **no** autoriza código: falta el plan de implementación aprobado.
- **A2:** pasa a CONGELADO cuando Carlos responda Q-A2-C y su respuesta se registre en §8 (y, si es la opción (a), cuando se apruebe la enmienda de ADR-037).
