# Plan — Ficha R-6, Bloque A: `assetType` (G-4b), fechas de las convocatorias (G-5) y convocatoria y organización en el seguimiento (G-1)

**Estado: BORRADOR — espera "plan aprobado" (regla 3.4).** No autoriza código.
**Fecha:** 2026-10-21. **Autor:** Manuel, con asistencia de Claude. **Base verificada:** `develop` `fdccd99`.
**Norma:** `Documentos/ficha-R6-datos-estilo-v2.md`, CONGELADA (sha256 `ccc3801288de557c57a759c598018f6355d059e24496990b0daa23900e884248`): §3 (Bloque A), §7 (tests 1 a 4), §8, §9 (D-R6-1, D-R6-10) y §10 (C-1).
**Rama de implementación prevista:** `feat/r6-bloque-a`, desde el `develop` del momento. Es un PR solo para este bloque (D-R6-10).

---

## 0. Verificación de la ficha contra el código (`fdccd99`)

La ficha no se modifica. Resultado de comprobar sus correcciones de §10:

| # | Afirmación | Resultado | Evidencia |
|---|---|---|---|
| C-1 | `OrganizationPublicNamePort` está declarado en `contracts` e implementado en `app`; `api/pom.xml` depende solo de `core` y `contracts` | **CONTRADICHA en parte** | Declarado en `contracts/.../organization/OrganizationPublicNamePort.java:10`: sí. **Implementado en `identity`**, no en `app` (`identity/.../service/OrganizationPublicNameQuery.java:17`); `app` lo **consume** (`PublicCampaignController.java:20`, `PublicCampaignDiscoveryController.java:37`). `api/pom.xml:16-24`: de los módulos del proyecto, solo `core` y `contracts` (lo demás son librerías): sí. El patrón que pide D-R6-1 ("`contracts` declara, `app` implementa") sí existe en el código, con otro puerto: `CampaignAuditFactsPort` (`contracts/.../CampaignAuditFactsPort.java`), implementado en `app` (`CampaignAuditFactsProducer.java:21`) |
| C-2 | `ProjectionRebuildService.rebuildAll()` no tiene disparador en producción, solo un test | **CONFIRMADA** | Definido en `core/.../projection/ProjectionRebuildService.java:40`. Ninguna otra referencia en `*/src/main/` ni en scripts o configuración (`git grep`). Solo lo llama `DonationProjectionIntegrationTest.java:550, 572, 584, 587, 740, 767, 770, 797` |
| C-3 | `FundsClearedPayload` (1.0) no tiene `organizationRef`; `MongoFundDirectoryAdapter` resuelve la pertenencia con `FUND_REGISTERED` | **CONFIRMADA, con un matiz en la segunda mitad** | `FundsClearedPayload.java:9-15`: `clearedAmount`, `sourceReference`, `currency`, `campaignRef`, `donorRef`; **sin `organizationRef`**. 2.0: `FundsClearedV2Payload.java:10` lo lleva. Registro: `EventPayloadRegistry.java:46-47`. `MongoFundDirectoryAdapter.java:26-29` resuelve por el **evento génesis del fondo** (`sequence = 1`) cuando es `FUND_REGISTERED` **o `FUNDS_CLEARED` 2.0**, con `payload.organizationRef`; no solo por `FUND_REGISTERED`. Un fondo cuyo génesis fuera un `FUNDS_CLEARED` 1.0 no tendría organización por esa vía. Esto toca a G-6 (Bloque B), no al Bloque A |
| C-4 | `AnchorStatus` tiene diez valores; `RELEASED`, `FAILED` y `COLLECTING_FAILED`, ¿liberan los eventos? | **CONFIRMADA** | Diez valores en `crypto/.../domain/AnchorStatus.java:4-15`. Solo `RELEASED` libera: `CollectingFailedBatchService.release` (`app/.../anchoring/CollectingFailedBatchService.java:57-73`) llama a `releaseClaim` (`MongoUnanchoredEventAdapter.java:117-130`, `merkleBatchId = null` solo en la cobertura propia), y es el único llamador en producción. Así lo dice la Enmienda 1 de ADR-039 §2.3 (líneas 92-95). `FAILED` (`MerkleBatchMongoAdapter.markFailed`, `:253-257`, desde `AnchorConfirmationPoller.java:71`; y `ABANDON` en `:288`) y `COLLECTING_FAILED` (`:340`) solo cambian el `status` del lote y **no** liberan eventos. La tabla de G-3 queda como está |
| C-5 | `TrackingIntegrityService.MAX_EVENTS = 10_000` | **CONFIRMADA** | `app/.../integrity/TrackingIntegrityService.java:47`; se usa en `:115` |
| C-6 | (decisión, sin afirmación sobre el código) | No aplica | — |
| C-7 | Dónde viven `GET /organizations/{organizationId}/funds`, su política (DD-31) y `CampaignTimelineQuery.closedAt` | **Localizado** | La ruta está en **`api`**: `api/.../fund/FundController.java:52`. La política está en **`core`**: `FundOperationalQueryService.listForOrganization` (`core/.../query/FundOperationalQueryService.java:43-48`), con frontera de organización y `ADMINISTRATOR` o `EMPLOYEE`. `closedAt` está en **`convocatoria`**: `CampaignTimelineQuery.java:20` (registro) y `:30-31`, y se deriva del **registro de auditoría** (`CONVOCATORIA_CLOSED`, el primero), no de un campo de `Convocatoria` |

**Efecto en este bloque.** C-1 no cambia la decisión D-R6-1: el puerto se declara en `contracts` y se implementa en `app`, con `CampaignAuditFactsPort` como precedente. Solo es inexacto el precedente que cita la ficha. C-3 y C-7 son para los bloques B y D.

## 1. Alcance

**Dentro:** G-4b, G-5 y G-1 (ficha §3), los tests 1 a 4 (§7), `referencia-api-v1.md` y el aviso a la web (§8).
**Fuera:** los bloques B, C y D; la ciudad de la convocatoria (§3, G-1); cualquier campo derivado como "cierra pronto" (G-5).

## 2. Decisiones del plan, con su fuente en la ficha

### 2.1 G-4b · `assetType` en las lecturas operacionales de activos

- **`PhysicalAssetOperationalView`** (`core/.../port/out/PhysicalAssetOperationalView.java:10-12`) gana `String assetType` como componente nuevo del registro. Su Javadoc ya excluye `donorRef`, finanzas y genealogía, y eso sigue igual.
- **Quién lo llena:** `PhysicalAssetOperationalQueryService`, en sus dos construcciones (`:51` y `:70`), con `asset.getAssetType()` (`PhysicalAsset.java:481`).
  - La ficha dice "su adaptador": en el código, quien construye la vista es este servicio de consulta, que rehidrata el agregado desde el Event Store. No hay un adaptador aparte.
- **Respuesta:** `PhysicalAssetDtos.OperationalResponse` (`api/.../asset/PhysicalAssetDtos.java:36-37`, `NON_NULL`) gana `assetType`. Se rellena en los dos controladores que la usan:
  - `OrganizationAssetsController.java:33-34`, la lista;
  - `PhysicalAssetController.java:158-160`, `GET /physical-assets/{assetRef}`.
- **Siguen fuera** `parentAssetRef` y `rootAssetRef` (§2 de la ficha).

### 2.2 G-5 · `startDate` y `endDate` en la lista de convocatorias de la organización

- **`OrganizationCampaignsQuery.AdminCampaignView`** (`convocatoria/.../query/OrganizationCampaignsQuery.java:34-36`) gana `Instant startDate` y `Instant endDate`, que se rellenan en `view` (`:65-72`) con `c.getStartDate()` y `c.getEndDate()` (`Convocatoria.java:179-180`; inmutables, `:45-46`).
- **Respuesta:** `OrganizationCampaignsController.Item` (`app/.../campaign/OrganizationCampaignsController.java:24-26`) gana `startDate` y `endDate` como texto ISO-8601 UTC, con `Instant.toString()`, igual que CV-07 (`PublicCampaignController.java:34`).
- Quién puede leerla no cambia (`ADMINISTRATOR`, `OrganizationCampaignsQuery.java:53`).
- **Contrato del puerto:** `OrganizationCampaignsQuery` es un `@Component` de `convocatoria` que usa `app`, y `AdminCampaignView` crece. Se trata como en la ficha: cambio aditivo de una vista de lectura. Si Carlos considera que la regla 3.5 lo alcanza, como en Q-A2-C, es la pregunta **P-R6A-2**.

### 2.3 G-1 · `campaignTitle?` y `organizationName?` en el seguimiento

**El puerto, en `contracts`** (D-R6-1; C-1). Paquete `com.traceability.contracts.campaign`, donde ya están `CampaignResponsibilityPort` y `CampaignInKindEligibilityPort`:

```java
package com.traceability.contracts.campaign;

import java.util.Optional;

/**
 * Título de una convocatoria y nombre público de su organización, para el seguimiento del donante (ficha R-6, G-1;
 * D-R6-1). Solo lectura. Declarado en contracts para que lo use api; implementado en app.
 */
public interface CampaignPublicSummaryPort {

    /** Vacío si la convocatoria no existe. {@code organizationName} es nulo si la organización no tiene nombre (DD-04). */
    Optional<CampaignPublicSummary> findByCampaignRef(String campaignRef);

    record CampaignPublicSummary(String title, String organizationName) {}
}
```

- Solo usa tipos de Java: cumple la regla de ArchUnit de `contracts` (`MediaArchitectureTest.CONTRACTS_ONLY_JAVA`).
- **Implementación en `app`** (D-R6-1): una clase en `app.application.campaign`, junto a `CampaignAuditFactsProducer`. Necesita:
  - el título por `campaignRef`, que viene de `convocatoria`;
  - el nombre por `organizationRef`, con `OrganizationPublicNamePort`, que implementa `identity`.
- **Hueco que la ficha no resuelve.** Hoy ninguna lectura de `convocatoria` que use `app` da el título por `campaignRef`:
  - `ConvocatoriaReadPort` solo busca por `publicCode` (`ConvocatoriaReadPort.java:9`);
  - `CampaignFundingFacts` no lleva el título, a propósito (`CampaignFundingFacts.java`: "Sin título ni descripción");
  - `ConvocatoriaRepositoryPort.findByCampaignRef` es un puerto de salida de `convocatoria` que hoy no usa ninguna clase de `app`.

  Ver **P-R6A-1**.
- **Consumo en `api`:** `PublicDonationController` (`api/.../controller/PublicDonationController.java:26-34`) o el mapeador (`PublicDonationMapper.java:27`) usan el puerto cuando `model.campaignRef()` no es nulo (`:52`).
  - `PublicDonationTrackingDTO` (`api/.../dto/PublicDonationTrackingDTO.java:6-11`) gana `campaignTitle` y `organizationName`, los dos con `@JsonInclude(NON_NULL)`, como `campaignRef`.
  - Sin convocatoria, o si no se encuentra, faltan los dos (rama defensiva de la ficha).
- **Nada más cambia en la respuesta:** sigue sin `fundId` ni `donorRef` (DD-76), y el filtro de `trackingCode` tampoco cambia.

### 2.4 Clases nuevas y por qué (regla 2.1)

| Clase | Módulo | Fuente |
|---|---|---|
| `CampaignPublicSummaryPort` y su registro `CampaignPublicSummary` | `contracts` | Ficha R-6 §3 G-1; D-R6-1; C-1 |
| Su implementación (nombre en P-R6A-3) | `app` | D-R6-1 |
| Lo que resulte de P-R6A-1 (método o consulta de título por `campaignRef`) | `convocatoria` | Hueco de §2.3 |

No hay excepciones nuevas: la rama defensiva omite los campos.

## 3. Tests — rojos primero (ficha §7, Bloque A)

| # | Test | Dónde |
|---|---|---|
| 1 | Lista de lotes con `assetType` igual al registrado; también `GET /physical-assets/{assetRef}`. Aserción negativa: sin `parentAssetRef`, `rootAssetRef` ni `donorRef` | `api` (controladores), `core` (`PhysicalAssetOperationalQueryService`) |
| 2 | Lista de convocatorias con `startDate` y `endDate` iguales a los del dominio, en ISO-8601 UTC | `convocatoria` (`OrganizationCampaignsQuery`), `app` (controlador) |
| 3 | Seguimiento con `campaignTitle` y `organizationName`. Sin convocatoria, o si no se encuentra, faltan los dos; la organización sin nombre da `campaignTitle` sin `organizationName`. Aserción negativa: sin `fundId` ni `donorRef` | `api` (con un doble del puerto) y `app` (integración con la implementación real) |
| 4 | Arquitectura: `api` no depende de `convocatoria` ni de `identity` (ArchUnit sobre `com.traceability.api..`, con una violación deliberada que demuestre que la regla está activa) | `app` (ve todos los módulos) |
| — | Común: `scripts/ci-local.sh` completo en verde, con el resumen literal de Surefire | — |

**Doble del puerto en `api`.** Las pruebas de `api` levantan el controlador sin `app`, así que necesitan un `CampaignPublicSummaryPort` de prueba. En `PublicDonationControllerIntegrationTest` hace falta un bean de test o `@MockitoBean`.

## 4. Mutaciones

1. `assetType` nulo en la vista → muere el test 1.
2. Intercambiar `startDate` y `endDate` → muere el test 2.
3. Devolver el título aunque falte `campaignRef` → muere el test 3, rama defensiva.
4. Incluir `fundId` en el DTO → muere el test 3, aserción negativa.
5. Quitar la condición de la regla de ArchUnit → muere el test 4 con la violación deliberada.

## 5. Orden de trabajo (tras "plan aprobado")

1. Tests rojos 1 a 4 y salida roja literal.
2. Verde: `core` (vista y servicio) → `api` (DTO y controladores) → `convocatoria` (`AdminCampaignView`) → `app` (controlador) → `contracts` (puerto) → `app` (implementación) → `api` (seguimiento).
3. Commit, mutaciones y `ci-local.sh` en un clon limpio, con la evidencia en `Documentos/evidencia-ci/`.
4. Documentos (§6).

## 6. Documentos y aviso a la web (ficha §8)

- **`referencia-api-v1.md`**, con notas fechadas y solo añadidas:
  - §3 (`GET /organizations/{organizationId}/physical-assets`, línea 60, `assetType`; `GET /organizations/{organizationId}/campaigns`, línea 52, `startDate` y `endDate`);
  - §6 (`GET /physical-assets/{assetRef}`, línea 107, `assetType`);
  - §8 (`GET /donations/tracking`, línea 124, `campaignTitle?` y `organizationName?`).
- **`api-contract-matrix.md`:** el Bloque A no la cambia; las filas de G-4, G-6, G-7 y G-8 son de otros bloques.
- **Aviso a la web.** `paxfide-web/Documentos/prompts-agentes-estilo-v2.md` §3.4 está en **otro repositorio**: los huecos G-4b, G-5 y G-1 pasan a "disponible desde <commit>". Se avisa a la web y al móvil con el commit de `develop` cuando se fusione. Este PR de documentación no lo hace.

## 7. Riesgos y preguntas

### 7.1 Riesgos

- **R-R6A-1:** en G-1, para una convocatoria `PRIVATE_LINK`, quien tenga el `trackingCode` verá su título. La ficha lo justifica porque CV-07 ya lo devuelve por `publicCode`, pero el `trackingCode` y el `publicCode` son secretos distintos, y quien tenga uno puede no tener el otro. Se registra para Carlos (**P-R6A-4**).
- **R-R6A-2:** una lectura de `convocatoria` y otra de `identity` por consulta de seguimiento. Es una sola donación por consulta, así que el coste es acotado.
- **R-R6A-3:** cambiar un registro como `PhysicalAssetOperationalView` rompe en compilación a todo el que lo construya. Hay dos sitios (`PhysicalAssetOperationalQueryService.java:51`, `:70`) y los dobles de los tests.

### 7.2 Preguntas para la aprobación

| # | Pregunta | Recomendación |
|---|---|---|
| **P-R6A-1** | ¿Cómo obtiene `app` el título de una convocatoria por `campaignRef`? (a) Un método nuevo en `ConvocatoriaReadPort`: es cambiar un puerto que usa otro módulo, la regla 3.5, como en Q-A2-C. (b) Una consulta nueva de solo lectura en `convocatoria.application.query` (un `@Component`, como `CampaignFundingFactsQuery`), sin tocar puertos existentes. (c) Implementar el puerto en `convocatoria`, pero allí no se puede resolver el nombre de la organización | **(b)**: no cambia ningún contrato existente y sigue el patrón de `CampaignFundingFactsQuery`, que ya consume `app` |
| **P-R6A-2** | ¿Ampliar `AdminCampaignView` (G-5) cuenta como cambio de contrato de un puerto usado por otro módulo (regla 3.5)? | No: `OrganizationCampaignsQuery` es una consulta de lectura sin interfaz y el cambio es aditivo. Lo confirma Carlos |
| **P-R6A-3** | Nombre de la implementación del puerto en `app` | `CampaignPublicSummaryProducer`, como `CampaignAuditFactsProducer` |
| **P-R6A-4** | ¿El título de una `PRIVATE_LINK` en el seguimiento es aceptable (R-R6A-1)? | Sí, si Carlos lo confirma: quien tiene el `trackingCode` es el donante, que conoció la convocatoria por su enlace |

## 8. Archivos previstos

- **`contracts`:** `campaign/CampaignPublicSummaryPort.java`.
- **`core`:** `application/port/out/PhysicalAssetOperationalView.java`, `application/query/PhysicalAssetOperationalQueryService.java` y sus tests.
- **`api`:**
  - `asset/PhysicalAssetDtos.java`, `asset/OrganizationAssetsController.java`, `asset/PhysicalAssetController.java`;
  - `application/dto/PublicDonationTrackingDTO.java`, `application/mapper/PublicDonationMapper.java` o `application/controller/PublicDonationController.java`;
  - sus tests.
- **`convocatoria`:** `application/query/OrganizationCampaignsQuery.java` y lo que resulte de P-R6A-1, con tests.
- **`app`:** `web/campaign/OrganizationCampaignsController.java`, la implementación del puerto en `application/campaign/`, el test de arquitectura y los tests de integración.
- **Documentos:** `referencia-api-v1.md`, `estado-fase6.md` y `Documentos/evidencia-ci/`.
