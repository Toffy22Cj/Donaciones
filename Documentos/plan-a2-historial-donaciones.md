# Plan — A2: historial de donaciones de la cuenta (`GET /api/v1/account/donations`)

**Estado: APROBADO — Manuel, 2026-10-22** (ver "Aprobación — Manuel, 2026-10-22", al final). El texto de §0 a §8 se conserva tal como se redactó; P-A2-5 se corrige en la aprobación.
**Fecha:** 2026-10-22. **Autor:** Manuel, con asistencia de Claude. **Base verificada:** `develop` `aae7492`.
**Norma:**
- `Documentos/ADR-037-enmienda-5-fecha-de-creacion-y-vista-del-historial.md`, **APROBADA — Carlos** (comunicado por Manuel el 2026-10-21; falta registrar la hora y el lugar exactos). E5-1, E5-2 y E5-3, y su definición de hecho (criterios 1 a 7).
  - *Nota (2026-10-24):* aprobada por Carlos el **2026-10-22 a las 00:12, hora de Colombia (05:12Z)**, por **WhatsApp**. Dato aportado por Manuel el 2026-10-24; no hay enlace ni commit de Carlos que lo cite.
- `Documentos/ficha-lecturas-fronts-M07-M15-M26.md`, A2 **CONGELADO** (nota del 2026-10-22): §3.2 (lo que hay) y §8 (Q-A2-1 a Q-A2-5).
- DD-21, con la nota del 2026-10-22 por el orden de E5-2.

**Rama de implementación prevista:** `feat/convocatoria-a2-historial`, la misma de este plan, sin PR hasta tener "plan aprobado". No depende del #111.

---

## 0. Verificación de la Enmienda 5 contra el código (`aae7492`)

Cada hecho que cita la enmienda, comprobado con su ruta y su línea:

| # | Hecho citado en la Enmienda 5 | Resultado | Evidencia en `aae7492` |
|---|---|---|---|
| V-1 | `DonationIntent` se persiste como `DonationIntentDocument` (`@Document`, colección `donation_intents`) | **CONFIRMADO** | `convocatoria/.../persistence/mongo/document/DonationIntentDocument.java:14` |
| V-2 | Mapper en los dos sentidos | **CONFIRMADO** | `DonationIntentMapper.java:17` (`toDocument`) y `:61` (`toDomain`) |
| V-3 | `convocatoria` solo depende de `contracts`, así que no puede escribir en el Event Store | **CONFIRMADO** | `convocatoria/pom.xml:13` (comentario "Nunca core, identity…") y `:19` (`contracts`) |
| V-4 | `DonationIntent` no guarda el instante de creación | **CONFIRMADO** | campos en `DonationIntent.java:18-36`; ningún `createdAt` |
| V-5 | `create` no recibe ese instante | **CONFIRMADO** | `DonationIntent.java:71` y `:95`; ninguno lo recibe |
| V-6 | El instante ya se calcula al crear | **CONFIRMADO** | `Instant now = clock.instant()` en `DonationIntentService.java:131`; lo usan `expiresAt` (`:132-133`) y la caducidad del token |
| V-7 | `confirmedAt` existe en `Confirmation` y se persiste | **CONFIRMADO** | `record Confirmation(confirmedBy, confirmedAt, paymentMethod, reference)` en `DonationIntent.java:222`; `DonationIntentDocument.java:60` |
| V-8 | La vista de hoy es `DonationView(intentId, campaignTitle, amount, currency, status, fundId, fundsAppliedAt)` | **CONFIRMADO** | `DonationIntentReadPort.java:22-23` |
| V-9 | La arma `DonationIntentQueryService`, con una lectura de la convocatoria por intención | **CONFIRMADO** | `DonationIntentQueryService.java:46-50`; la lectura es `:47` (`convocatorias.findByCampaignRef`) |
| V-10 | `organizationRef` y `paymentMethod` están en el dominio, con sus getters | **CONFIRMADO** | campos `:20` y `:25`; getters `:178` y `:183` |
| V-11 | El orden de hoy es por `_id`, que es `intentId`, un UUID aleatorio | **CONFIRMADO** | `MongoDonationIntentRepositoryAdapter.java:258-263` (`Sort.by("_id")`); `DonationIntentService.java:134` (`UUID.randomUUID()`) |
| V-12 | El nombre público lo da `OrganizationPublicNamePort`, implementado en `identity` y consumido en `app` | **CONFIRMADO** | `contracts/.../organization/OrganizationPublicNamePort.java:10`; `identity/.../OrganizationPublicNameQuery.java:17` y `:26`; `app/.../PublicCampaignController.java:20` |
| V-13 | `DonationIntentReadPort` es de `convocatoria` y lo usa `app` | **CONFIRMADO** | `AccountDonationsController.java:27`; `DonationIntentController.java:46` |
| V-14 | Ninguna vista lleva el `donorRef` | **CONFIRMADO** | Javadoc `DonationIntentReadPort.java:8-9`; `DonationView` no lo tiene |
| V-15 | Hoy solo existe el índice de `donorRef` | **CONFIRMADO** | `DonationIntentDocument.java:31` y `:40` (`ix_donor_ref`); lo crea `MongoIndexInitializer` y lo comprueba `MongoIndexesAtStartupIntegrationTest.java:100` |

**Matices que no contradicen la enmienda pero condicionan el plan:**
- **M-1. La visibilidad de una convocatoria es inmutable.** El campo es `private final Visibility visibility` (`Convocatoria.java:44`) y solo se asigna en el constructor (`:60`). Ninguna operación la cambia. La regla de Q-A2-4 ("según su visibilidad actual al leer") se cumple igual, pero la segunda mitad del criterio 4 ("también cuando la visibilidad cambió después de donar") solo se puede probar editando el documento en Mongo (**P-A2-3**).
- **M-2. Crear una intención nunca escribe en el Event Store**, por V-3. Confirmar un pago `GATEWAY`, en cambio, sí produce eventos en `core` por la aplicación de fondos (ADR-045). El criterio 7 se mide, por tanto, alrededor de **crear y leer**, no de confirmar.
- **M-3. Las intenciones anónimas nunca llegan al historial.** Cada una tiene su propio `anon:…`, y el historial pide `"acct:" + seudónimo` (`AccountDonationsController.java:40`).

**Ninguna contradicción** entre la Enmienda 5 y el código.

## 1. Alcance

**Dentro**
- E5-1: `createdAt` en el dominio, el documento y el mapper.
- E5-2: `DonationView`, el filtro del `publicCode` dentro de `convocatoria` y el orden de `findByDonorRef`.
- El contrato HTTP de `GET /api/v1/account/donations` (ficha §3.2 y §8).
- Los criterios 1 a 7 de la enmienda.
- `referencia-api-v1.md`, `estado-fase6.md` y el aviso a la web y al móvil.

**Fuera**
- La paginación con cursor: DD-21 no cambia, sigue siendo una página de 100.
- Rellenar `createdAt` en las intenciones existentes: E5-1 lo prohíbe.
- Cualquier cambio en `IntentStatusView` o `findForStatusToken`: E5-2 no los toca.

## 2. Diseño

### 2.1 E5-1 · `createdAt` en `DonationIntent`

- **Dominio** (`DonationIntent`): gana el campo `private final Instant createdAt` y el getter `getCreatedAt()`.
  - **`create`:** las dos variantes (`:71` y `:95`) reciben `Instant createdAt` como parámetro **nuevo y obligatorio** (`Objects.requireNonNull`), porque una intención nueva nunca nace sin él (E5-1). Ver **P-A2-1**.
  - **`reconstitute`:** una cuarta sobrecarga con `createdAt`, admitido nulo para las intenciones antiguas. Las tres actuales siguen igual y delegan con `createdAt = null`. Mismo patrón que la Enmienda 3 (`:140`).
  - **Transiciones:** ninguna toca `createdAt`. El constructor privado lo recibe y lo copia; `confirm`, el paso a `FAILED`, la caducidad y `FUNDING_REJECTED` no lo reasignan. Es `final`.
- **Servicio** (`DonationIntentService.create`, `:131`): pasa el mismo `now` que ya calcula, sin otra llamada al reloj.
- **Documento** (`DonationIntentDocument`): gana `public Instant createdAt`. No hace falta migración ni relleno.
- **Mapper:** `toDocument` escribe `createdAt` y `toDomain` lo lee, nulo si falta, con la sobrecarga nueva de `reconstitute`.
- **Escrituras parciales:** las actualizaciones del adaptador (`MongoDonationIntentRepositoryAdapter`, `$set` de estado y confirmación, `:76-82` y `:130`) no tocan `createdAt`. Lo comprueba el criterio 1.

### 2.2 E5-2 · `DonationView`, el filtro del `publicCode` y el orden

**`DonationView`** (`DonationIntentReadPort.java:22-23`) gana estos componentes **al final**, en este orden:

```java
record DonationView(String intentId, String campaignTitle, long amount, String currency, String status,
                    String fundId, Instant fundsAppliedAt,
                    Instant createdAt, Instant confirmedAt, String organizationRef, String paymentMethod,
                    String publicCode) {}
```

| Componente | Fuente en `DonationIntentQueryService.view` |
|---|---|
| `createdAt` | `i.getCreatedAt()`, nulo en las antiguas |
| `confirmedAt` | `i.getConfirmation() == null ? null : i.getConfirmation().confirmedAt()` |
| `organizationRef` | `i.getOrganizationRef()`; `app` lo convierte en nombre y **nunca** lo devuelve |
| `paymentMethod` | `i.getPaymentMethod().name()` |
| `publicCode` | de la convocatoria que ya se lee en `:47`: `c.getVisibility() == Visibility.PUBLIC ? c.getPublicCode() : null` |

- **El filtro del `publicCode` va dentro de `convocatoria`** (E5-2, aprobado). El código de una `PRIVATE_LINK` nunca cruza el puerto, y la vista no lleva la visibilidad.
- **Coste:** no añade ninguna lectura. El título y el `publicCode` salen de la misma `Convocatoria` (`:47`). La línea pasa de `map(Convocatoria::getTitle)` a leer la convocatoria una vez y tomar de ella los dos datos.
- **Si la convocatoria no se encuentra** (rama defensiva de hoy): `campaignTitle` y `publicCode` quedan nulos.

**Orden de `findByDonorRef`** (`MongoDonationIntentRepositoryAdapter.java:258-263`):

```java
Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("_id"))
```

- En Mongo, con orden descendente los documentos **sin** `createdAt` quedan después de todos los que lo tienen; entre ellos manda `_id`, que es el orden de hoy. Comprobado en Mongo 6.0.28 (§3): `FFFFFFFFNN`.
- El tope (`limit(limit)`) se aplica después de ordenar, así que la página trae las 100 más recientes.
- `findByDonorRef` sigue devolviendo `List<DonationIntent>`; el puerto de salida (`DonationIntentRepositoryPort.java:96`) no cambia de firma.

### 2.3 Contrato HTTP (ficha §3.2 y §8)

`GET /api/v1/account/donations`, con JWT. Los permisos, el tope y la ausencia de `nextCursor` no cambian.

`DonationDtos.AccountDonation` (`DonationDtos.java:24-25`, `NON_NULL`) gana campos **al final**:

| Campo | Tipo | Cuándo | Decisión |
|---|---|---|---|
| `createdAt?` | texto ISO-8601 UTC (`Instant.toString()`) | falta en las intenciones anteriores a la enmienda | Q-A2-1 (c) |
| `confirmedAt?` | ídem | solo en `CONFIRMED` y `FUNDING_REJECTED` | Q-A2-1 (c) |
| `organizationName?` | texto | falta si la organización no tiene nombre o no existe (DD-04) | Q-A2-2 (a) |
| `paymentMethod` | `GATEWAY` \| `BANK_TRANSFER` \| `CASH` | siempre | Q-A2-3 (a) |
| `publicCode?` | texto | **solo** si la convocatoria es `PUBLIC` al leer; **nunca** en una `PRIVATE_LINK` | Q-A2-4 (b); ADR-041 §2.7 |

- **`organizationName` lo resuelve `app`:** `AccountDonationsController` llama a `OrganizationPublicNamePort.findPublicName(d.organizationRef())` por intención, con el puerto existente y sin cambiarlo. Es la lectura aceptada en H-L4.
  - Si el #111 se fusiona antes, `OrganizationLogoReferenceQuery.publicOrganization` también daría el nombre, pero A2 **no** lo necesita y no depende de él.
- **Nunca salen por HTTP** `organizationRef`, `donorRef`, `fundId` ni `fundsAppliedAt`. Los dos últimos siguen usándose solo para derivar el `trackingCode` (`:43`).
- **Ninguna clase nueva:** solo cambian registros y métodos existentes.

## 3. Índice `(donorRef, createdAt)`: decisión con datos

**Cuántas intenciones por donante esperamos.** El repositorio no tiene una estimación de volumen.
- Las intenciones anónimas tienen cada una su propio `anon:…` (M-3), así que solo acumulan historial las cuentas registradas.
- El escenario de demostración (`DemoScenarioSeeder`) crea pocas.
- **Supuesto de trabajo:** de unidades a decenas de intenciones por cuenta, con el tope de 100 por página (DD-21).

**Plan de ejecución medido** el 2026-10-22, en Mongo **6.0.28** (`mongo:6.0`, la misma imagen que los tests).
- Colección con 100 000 intenciones de 2000 donantes, más tres donantes de prueba con 10, 1000 y 10 000 intenciones.
- El 20 % de las intenciones de cada donante sin `createdAt`, como antiguas.
- Consulta: `find({donorRef}).sort({createdAt: -1, _id: 1}).limit(100)`, la misma que tendría A2.

| Índices | Donante | Plan ganador | Claves examinadas | Documentos examinados | Devueltos | ms |
|---|---|---|---|---|---|---|
| solo `ix_donor_ref` (hoy) | 10 | `SORT ← FETCH ← IXSCAN(ix_donor_ref)` | 10 | 10 | 10 | 2 |
| ídem | 1000 | ídem | 1000 | 1000 | 100 | 2 |
| ídem | 10 000 | ídem | 10 000 | 10 000 | 100 | 20 |
| `+ (donorRef 1, createdAt -1, _id 1)` | 10 | `LIMIT ← FETCH ← IXSCAN(ix_donor_ref_created_at)` | 10 | 10 | 10 | 2 |
| ídem | 1000 | ídem | 100 | 100 | 100 | 0 |
| ídem | 10 000 | ídem | 100 | 100 | 100 | 0 |

**Lectura de los datos**
- Sin el índice compuesto, el coste crece con las intenciones **de ese donante**, porque se ordenan en memoria (etapa `SORT`), no con el tamaño de la colección.
- Con el volumen esperado (decenas), la diferencia no se mide: 2 ms en los dos casos. Solo a partir de miles por cuenta compensa el índice.
- El índice compuesto añade una escritura de índice por cada intención creada.

**Recomendación (P-A2-2): no crear el índice en esta tarea.**
- Se registra el umbral: si una cuenta supera unas 1000 intenciones, se añade `(donorRef: 1, createdAt: -1, _id: 1)` en `DonationIntentDocument`, con su comprobación en `MongoIndexesAtStartupIntegrationTest`.
- Si se prefiere crearlo ya, el cambio es solo ese y no afecta al resto del plan.

## 4. Tests — rojos primero

### 4.1 Criterios de la Enmienda 5

| # | Criterio | Test | Dónde |
|---|---|---|---|
| 1 | Crear fija `createdAt` con el reloj del servidor; ninguna transición lo cambia (confirmar, fallar, caducar, `FUNDING_REJECTED`) | Dominio: `create` con un instante, y las cuatro transiciones conservan el mismo `createdAt`. Servicio, con `MutableClock`: `createdAt == clock` al crear; se mueve el reloj, se confirma o falla, se relee desde Mongo y `createdAt` no cambió | `DonationIntentTest`; `CreateDonationIntentIntegrationTest` (`convocatoria`) |
| 2 | Una intención guardada sin `createdAt` se rehidrata y se lee con `createdAt` nulo | Documento insertado a mano sin el campo → `findById` da `getCreatedAt() == null`, y `findByDonorRef` la devuelve | `convocatoria` (persistencia) |
| 3 | `confirmedAt` en `CONFIRMED` y `FUNDING_REJECTED`, nulo en `PENDING`, `FAILED` y `EXPIRED_UNKNOWN` | Cinco intenciones del mismo donante, una por estado, leídas por `DonationIntentReadPort.findByDonorRef` | `convocatoria` (`DonationIntentQueryService`) |
| 4 | `publicCode` presente si la convocatoria es `PUBLIC` al leer y nulo si es `PRIVATE_LINK`, también cuando la visibilidad cambió después de donar | (a) Una `PUBLIC` lo trae. (b) **Una `PRIVATE_LINK` no lo trae: aserción explícita** de nulo en la vista y de ausencia en el JSON. (c) La parte de "cambió después" según **P-A2-3** | `convocatoria` y `app` (HTTP) |
| 5 | Orden: las nuevas por `createdAt` descendente y las antiguas sin fecha al final; con más de 100, las 100 más recientes | (a) Tres nuevas con relojes distintos, insertadas desordenadas, más dos antiguas sin fecha → orden exacto. (b) 101 nuevas → la página trae 100 y la más antigua queda fuera | `convocatoria` (adaptador) |
| 6 | Ninguna respuesta HTTP lleva `organizationRef` ni `donorRef` (aserción negativa) | El cuerpo de `/account/donations` no contiene esas claves ni sus valores (`organizationId` real, `acct:`, el seudónimo) | `app` (HTTP) |
| 7 | Ningún evento nuevo en el Event Store al crear o leer una intención (aserción negativa) | Recuento de `event_store` antes y después de crear una `BANK_TRANSFER` (sin confirmar) y de leer el historial: igual (M-2) | `app` (HTTP) |

### 4.2 Criterios de la ficha L-FRONTS (§7, test 2)

| # | Test | Dónde |
|---|---|---|
| F-1 | El historial trae `createdAt` en las nuevas, `confirmedAt` solo en las confirmadas, `organizationName` (el de la organización), `paymentMethod` y `publicCode` **solo** en las `PUBLIC` | `app` (HTTP, sobre `DonationPaymentHttpIntegrationTest`) |
| F-2 | **Una donación a una `PRIVATE_LINK` no trae `publicCode`** (aserción explícita: `has("publicCode")` es falso) | `app` (HTTP) |
| F-3 | Orden por HTTP: la más reciente primero | `app` (HTTP) |
| F-4 | `createdAt` y `confirmedAt` en ISO-8601 UTC, con `Z` | `app` (HTTP) |
| F-5 | Una organización sin nombre da la intención sin `organizationName` | `app` (HTTP o con un doble del puerto) |
| F-6 | Lo que ya se probaba sigue igual: solo las propias, el `trackingCode` tras aplicar fondos, nunca el seudónimo, 401 sin JWT y vacío para una cuenta sin donaciones | `app` (test existente, sin cambios) |

## 5. Mutaciones (`scripts/mutaciones.py`)

1. `create` no guarda `createdAt` (queda nulo) → mueren los criterios 1 y F-1.
2. `confirm` reasigna `createdAt = confirmedAt` → muere el criterio 1.
3. El mapper no escribe `createdAt` → mueren los criterios 1 (lectura desde Mongo) y 5.
4. `publicCode` sin el filtro de visibilidad → mueren el criterio 4 (b) y F-2.
5. Orden por `_id` (el de hoy) → mueren los criterios 5 y F-3.
6. Orden `createdAt` **ascendente** → mueren los criterios 5 y F-3.
7. `confirmedAt` tomado de `fundsAppliedAt` → muere el criterio 3.
8. `organizationRef` copiado al DTO → muere el criterio 6.
9. `organizationName` sin resolver (siempre nulo) → muere F-1.

## 6. Orden de trabajo (tras "plan aprobado")

1. Tests rojos de `convocatoria` y `app`, con su salida literal.
2. Verde por capas, con el módulo completo al cerrar cada una:
   1. dominio (`DonationIntent`);
   2. persistencia (documento y mapper);
   3. servicio de creación;
   4. adaptador (orden);
   5. `DonationView` y `DonationIntentQueryService`;
   6. `app` (`AccountDonation` y controlador).
3. Commit, mutaciones y `scripts/ci-local.sh` en un clon limpio, con la evidencia en `Documentos/evidencia-ci/` (revisando antes la memoria libre).
4. Documentos (§8) y PR.

## 7. Riesgos y preguntas

### 7.1 Riesgos

- **R-A2-1 (H-L4, aceptado por Manuel).** Una lectura de `identity` por intención para el nombre, hasta 100 por página, además de la de la convocatoria que ya existe (`:47`). Son 200 lecturas de un documento por clave como mucho. Se acepta para la demo. Si molestara, la mejora sería agrupar por `organizationRef` en el controlador para leer una vez por organización distinta, sin tocar puertos. **No** se hace en esta tarea salvo que se pida.
- **R-A2-2.** Ordenar en memoria (§3) si una cuenta acumula miles de intenciones. Mitigado por el umbral de P-A2-2.
- **R-A2-3.** Los literales `GATEWAY`, `BANK_TRANSFER` y `CASH` pasan a ser contrato público (Q-A2-3). Renombrar `PaymentMethod` sería un cambio de contrato.
- **R-A2-4.** Cambiar la firma de `DonationIntent.create` rompe en compilación a quien la use: dos llamadas en producción (`DonationIntentService.java`) y 14 en los tests (`DonationIntentTest` 10, `ConvocatoriaPersistenceIndexesIntegrationTest` 3, `EditConfigurationIntegrationTest` 1). Se actualizan en el mismo PR (P-A2-1).
- **R-A2-5.** Ampliar `DonationView` rompe en compilación a quien construya el registro: solo `DonationIntentQueryService.java:48`. Ningún test ni doble lo construye hoy.

### 7.2 Preguntas para la aprobación

| # | Pregunta | Recomendación |
|---|---|---|
| **P-A2-1** | ¿`createdAt` como parámetro nuevo y obligatorio de las dos `create`, o una sobrecarga que conserve las firmas de hoy? | **Parámetro obligatorio**: una intención nueva sin `createdAt` sería un error que el compilador detecta. Cuesta actualizar 14 llamadas de test (R-A2-4) |
| **P-A2-2** | ¿Índice `(donorRef, createdAt desc, _id)` ahora? | **No**, con el umbral registrado (§3): con decenas de intenciones por cuenta la diferencia es de 0 a 2 ms |
| **P-A2-3** | Criterio 4, "también cuando la visibilidad cambió después de donar": la visibilidad es inmutable hoy (M-1). ¿Se prueba editando `visibility` en el documento de Mongo, o se registra que hoy no puede ocurrir y se prueba solo la regla "visibilidad actual al leer"? | **Editar el documento** en el test: protege la regla para cuando exista un cambio de visibilidad, sin código de producción nuevo |
| **P-A2-4** | `paymentMethod` en el DTO: ¿el de la intención (`DonationIntent.paymentMethod`) o el de la confirmación (`Confirmation.paymentMethod`)? Hoy son el mismo valor (`Confirmation.of(…, paymentMethod, …)`, `DonationIntent.java:159`) | **El de la intención**: existe en todos los estados, no solo en los confirmados |
| **P-A2-5** | ¿Dónde vive el test de F-5 (organización sin nombre)? | Por HTTP, con una organización creada sin nombre (`createOrganization` admite `name` nulo, DD-04), sin dobles |

## 8. Documentos que se actualizan al implementar, y aviso

- **`referencia-api-v1.md` §1, fila `GET /account/donations` (línea 29):** nota fechada, solo añadida, con los cinco campos nuevos, sus condiciones (`publicCode` solo en `PUBLIC`) y el orden por `createdAt` descendente con las antiguas al final.
- **`estado-fase6.md`:** entrada nueva con la evidencia (rojo, verde por capas, mutaciones y `ci-local.sh`).
- **`decisiones-delegadas-2026-10.md`:** ya tiene la nota de DD-21 (2026-10-22); no hace falta otra.
- **Ficha L-FRONTS:** al fusionar, una nota "A2 implementado en <commit>".
- **Aviso a la web y al móvil**, cuando se fusione, con el commit de `develop`:
  - M-15/M-16 ya tienen `createdAt?`, `confirmedAt?`, `organizationName?`, `paymentMethod` y `publicCode?`, y el orden nuevo;
  - la web arma el enlace a la convocatoria solo si llega `publicCode`.

  El documento de prompts de la web (`paxfide-web`) está en otro repositorio y no se toca desde aquí.

## Aprobación — Manuel, 2026-10-22

**Plan aprobado**, con estas respuestas a §7.2:

| # | Decisión |
|---|---|
| **P-A2-1** | `createdAt` como **parámetro obligatorio** de las dos `create` |
| **P-A2-2** | **Sin índice compuesto.** Se registra el umbral: añadir `(donorRef: 1, createdAt: -1, _id: 1)` si una cuenta pasa de unas **1000 intenciones**. También se registra la medición con `explain()` de §3: Mongo 6.0.28, 100 000 intenciones, donantes de 10, 1000 y 10 000. Sin el índice, `SORT ← FETCH ← IXSCAN(ix_donor_ref)` con 10, 1000 y 10 000 claves examinadas (2, 2 y 20 ms); con el índice, `LIMIT ← FETCH ← IXSCAN` con 100 claves como mucho (0 a 2 ms) |
| **P-A2-3** | El criterio 4 se prueba **editando `visibility` en el documento** de Mongo |
| **P-A2-4** | `paymentMethod` **de la intención** |
| **P-A2-5** | **Corrección de la recomendación:** por HTTP no se puede crear una organización sin nombre (DD-68 lo hace obligatorio). F-5 **siembra la organización sin nombre directamente en Mongo** y consulta el historial por HTTP |

