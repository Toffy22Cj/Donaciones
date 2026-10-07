# Plan D-CAMPAIGN — `campaignRef` en `PhysicalAsset` (implementación de la Enmienda 1 de ADR-029)

**Estado:** **APROBADO — Carlos, 2026-10-07** (regla 3.4), con Q1–Q4 según la recomendación y los ajustes de §8, ya incorporados en el texto.
**Origen:** `ADR-029-enmienda-1-campaignref.md`, **APROBADA — Carlos, 2026-10-07**, con Q1–Q5 según la recomendación. Este plan no reabre ninguna decisión de la enmienda; solo fija cómo se implementa.
**Desbloquea:** B5 (narrativa de convocatoria, criterio 14) y B1-bis (herencia en la división, criterio 15).
**Revisión:** cubierto por la excepción a la regla 3.2 (`reglas-equipo-y-agentes.md` §3.2); la evidencia de tests sustituye al segundo revisor.

---

## 1. Lo que se implementa (de la enmienda)

| Decisión | Resumen |
|---|---|
| D1 | `ASSET_REGISTERED` y `ASSET_SPLIT` **3.0**, con `campaignRef` (puede ser `null`). **Toda escritura nueva usa la 3.0.** La 1.0 y la 2.0 no cambian; no hay *upcaster* de almacenamiento |
| D2 | Camino A: `campaignRef` **heredado del `Fund`** que ya carga `assertOrganizationMatchesFund`. No es parámetro del comando |
| D3 + Q1/Q2/Q5 | Camino B: `campaignRef` **opcional** en el comando, validado **después de autorizar y antes de persistir** (ajuste de seguridad de §8) con un **puerto en `contracts`** implementado por `convocatoria`. Comprueba que la convocatoria existe, es de la misma organización, está `OPEN` **en el momento del registro** y acepta `IN_KIND`. Limitación escrita: lo registrado después del cierre queda sin convocatoria |
| D4 | División: el hijo hereda el `campaignRef` del padre en `AssetSplitV3Payload` |
| D5/D6 | Inmutable, sin *backfill*, nunca inferido; v1/v2 = sin convocatoria |
| D7 | La narrativa lee el `campaignRef` **solo** del payload v3 del propio activo |

## 2. Hechos del código que condicionan el plan (`develop` tras #42)

Rutas relativas a `core/src/main/java/com/traceability/core/`, salvo que se indique otra.

- `PhysicalAsset.register` (Camino A), `create` (Camino B) y `split` escriben hoy v2 (`domain/physicalasset/PhysicalAsset.java:83-85, 119-121, 189-193`).
- `assertOrganizationMatchesFund` ya rehidrata el `Fund` (`application/command/PhysicalAssetCommandService.java:173-184`); `Fund.getCampaignRef()` existe y es opcional (`domain/fund/Fund.java:22, 243`).
- `registerPhysicalAssetFromDonation` genera `donationRef` y llama a `PhysicalAsset.create` (`PhysicalAssetCommandService.java:189-238`).
- `ConvocatoriaConfiguration` solo tiene `acceptsMonetary()` (`convocatoria/.../domain/model/ConvocatoriaConfiguration.java:68`).
- `core` y `convocatoria` dependen solo de `contracts`; la conexión entre ambos se hace en `app` (patrón de `OrganizationVerificationPort`).
- Unas 40 clases de test de `core` construyen contextos Spring con `@MockBean` para los puertos de `contracts`.
- Desde #42, `ProjectionPayloadContractTest` **obliga** a que cada manejador declare cada clase registrada: la 3.0 no pasa CI sin declararse.
- `AssetProjectionRouting.registration(...)` es la vista común del registro (v1/v2) y `isInKind` usa `donationRef` (`application/projection/AssetProjectionRouting.java`).

## 3. Cambios

### 3.1 `contracts`

- **`CampaignInKindEligibilityPort`** (`com.traceability.contracts.campaign`):
  ```java
  InKindEligibility checkInKindEligibility(String campaignRef, String organizationRef);
  ```
- **`InKindEligibility`** (enum): `ELIGIBLE`, `CAMPAIGN_NOT_FOUND`, `OTHER_ORGANIZATION`, `CAMPAIGN_CLOSED`, `IN_KIND_NOT_ACCEPTED`. Se usa un enum, no un `boolean`, para que el rechazo diga el motivo sin que `core` conozca `convocatoria`.
- Es un **contrato nuevo** entre módulos: cubierto por la enmienda aprobada (regla 3.5, D3 Q1).

### 3.2 `core`

1. **Payloads:**
   - `AssetRegisteredV3Payload`: campos de la v2 + `campaignRef`.
   - `AssetSplitV3Payload`: campos de la v2 + `campaignRef`.
   - Registro en `EventPayloadRegistry` como `"3.0"`. La 1.0 y la 2.0 se quedan para leer el histórico.
2. **`PhysicalAsset`:**
   - Campo `campaignRef`.
   - `apply` de V3 lo fija; V1/V2 lo dejan en `null`.
   - `register` y `create` reciben `campaignRef` y escriben **V3**.
   - `split` escribe **V3** con `this.campaignRef`.
   - El `apply` de `AssetSplitV3Payload` en el padre es igual que el de la V2.
3. **`PhysicalAssetCommandService`:**
   - **Camino A:** `assertOrganizationMatchesFund` pasa a devolver el `Fund` cargado. `registerPhysicalAsset` pasa `fund.getCampaignRef()` a `register`. La firma pública **no cambia**.
     - **Decisión escrita, no olvido:** el Camino A **no comprueba que la convocatoria siga `OPEN`**. Gastar fondos ya recaudados después del cierre es legítimo, igual que una intención válida sobrevive al cierre (ADR-037 §2.6bis, D1). El activo hereda el `campaignRef` del `Fund` sin consultar el estado de la convocatoria.
   - **Camino B:** `registerPhysicalAssetFromDonation` añade el parámetro `campaignRef` (opcional).
     - Orden: **primero autorizar** (el actor puede registrar activos en la organización declarada), **después** consultar el puerto si `campaignRef` no es `null`, y por último persistir.
     - Cada resultado distinto de `ELIGIBLE` lanza su **excepción nombrada** (regla 2.6), todas subclases de `CampaignNotEligibleForInKindDonationException` (nueva, en `core`): `InKindCampaignNotFoundException`, `InKindCampaignOfOtherOrganizationException`, `InKindCampaignClosedException` e `InKindNotAcceptedByCampaignException`.
     - **Misma respuesta externa** para "no existe" y "es de otra organización": las dos excepciones llevan **el mismo mensaje público**, sin el motivo, y la API (B6) las traduce al mismo código y cuerpo. El motivo real solo queda en el log interno.
     - Un rechazo no deja eventos, ni reclamo del `commandId`, ni outbox.
   - **Puerto obligatorio (Q2):** `PhysicalAssetCommandService` recibe `CampaignInKindEligibilityPort` como **dependencia obligatoria**, no opcional.
     - Si falta la implementación, la aplicación **no arranca**: un error de cableado nunca queda oculto como un rechazo silencioso.
     - Los tests de `core` reciben **un único stub compartido**: un `@Component` en las fuentes de test de `core`, que los contextos que escanean `com.traceability.core` recogen automáticamente. Los tests unitarios que construyen el servicio a mano le pasan un *fake*. No hacen falta 40 `@MockBean`.
   - La firma anterior de `registerPhysicalAssetFromDonation` (sin `campaignRef`) se conserva como sobrecarga que delega con `null`, para no romper a los llamadores actuales (solo tests).
4. **Proyecciones** (lo exige el test de contrato):
   - `AssetProjectionRouting.registration(...)` reconoce `AssetRegisteredV3Payload`. `Registration` gana el campo `campaignRef`; para v1/v2 vale `null`.
   - `DonationProjectionHandler` declara como tratadas `AssetRegisteredV3Payload` y `AssetSplitV3Payload` (la división v3 se trata igual que la v2).
   - `DonationAuditFactsHandler` y `PendingAllocationProjectionHandler` las ignoran por construcción, porque sus conjuntos de ignorados se derivan del registro.
   - **`campaignRef` por activo en el modelo de lectura:** `DonationProjectionDocument.LogisticsProjection` gana `campaignRef`, tomado **solo** del payload v3 del propio activo (D7). Para v1/v2 vale `null`.
     - El índice `{campaignRef, status}` y el modelo de agregación de la narrativa quedan para B5/C5 de ADR-040: aquí solo se deja el dato disponible.

### 3.3 `convocatoria`

- `ConvocatoriaConfiguration.acceptsInKind()`, simétrico de `acceptsMonetary()`.
- **`CampaignInKindEligibilityAdapter`** (`@Component`, en `infrastructure`), que implementa el puerto de `contracts`:
  - carga la convocatoria por `campaignRef`;
  - inexistente → `CAMPAIGN_NOT_FOUND`;
  - otra organización → `OTHER_ORGANIZATION`;
  - no `OPEN` → `CAMPAIGN_CLOSED` (Q5: estado en el momento del registro);
  - sin `IN_KIND` → `IN_KIND_NOT_ACCEPTED`;
  - en otro caso, `ELIGIBLE`.
- Solo lectura, sin transacción propia. La **ventana** entre esta comprobación y la escritura en `core` es el **riesgo aceptado** de la enmienda (D3).

### 3.4 `app`

- Nada que conectar a mano: `app` ya escanea `com.traceability`, así que el adaptador de `convocatoria` queda disponible para `core`.
- **`CampaignInKindEligibilityWiringIntegrationTest`**, con el patrón de `OrganizationVerificationWiringIntegrationTest`:
  - hay **una sola** implementación del puerto en el contexto completo y es la de `convocatoria`. El stub de test de `core` no está en el classpath de producción: se comprueba como con `FakeOrganizationVerificationPort`;
  - **sin la implementación, el contexto no arranca.** Se prueba con un `ApplicationContextRunner` que excluye el adaptador de `convocatoria` y espera el fallo por la dependencia ausente;
  - `PhysicalAssetCommandService` la recibe;
  - prueba de punta a punta: registrar un activo en especie con el `campaignRef` de una convocatoria real `OPEN` que acepta `IN_KIND` produce un `ASSET_REGISTERED` 3.0 con ese `campaignRef`;
  - con una convocatoria `CLOSED`, el rechazo no deja eventos.

### 3.5 Documentos

- `plan-d-campaign.md` → hecho.
- `estado-fase6.md`: D-CAMPAIGN implementada.
- Catálogo (entrada de ADR-029): Enmienda 1 implementada.
- `golden-path.md` §5: se retira la nota "ningún `PhysicalAsset` tiene `campaignRef`".

## 4. Tests (Definition of Done)

**Primer commit con los tests que deben fallar** (salida literal de Surefire guardada para el PR), como en B-PROJ:

1. **Dominio (`PhysicalAssetTest`):**
   - `register` y `create` escriben V3 con el `campaignRef` recibido (o `null`);
   - `split` escribe V3 con el `campaignRef` del padre;
   - un stream con eventos V1/V2/V3 se rehidrata, y en V1/V2 `campaignRef = null`.
2. **Camino A (integración en `core`):**
   - el activo hereda el `campaignRef` del `Fund`;
   - un `Fund` sin convocatoria da un activo sin convocatoria;
   - el llamador no puede imponer otro (no hay parámetro).
3. **Camino B (integración en `core`, con el stub compartido):**
   - `campaignRef` válido → V3 con ese valor;
   - los cuatro rechazos, cada uno con su excepción nombrada y **sin efectos**: sin eventos, sin reclamo del `commandId` y sin outbox;
   - "no existe" y "otra organización" tienen **el mismo mensaje público**;
   - **un actor no autorizado recibe el error de autorización y el puerto no se consulta** (verificado con el stub), así que no puede sondear `campaignRef`;
   - `null` → registro sin convocatoria.
4. **Idempotencia:** el mismo `commandId`, con o sin `campaignRef`, no duplica el activo.
5. **Integridad:** los eventos v2 guardados conservan su hash. Los v3 se encadenan con `campaignRef` dentro del hash: alterar el `campaignRef` de un v3 guardado cambia su hash recalculado.
6. **Proyecciones:**
   - `ProjectionPayloadContractTest` en verde con la 3.0 declarada;
   - en `ProjectionChangeStreamE2ETest`, el Camino A con un `Fund` con convocatoria deja `logistics[].campaignRef` igual al del `Fund`, y un v2 histórico deja `null`.
7. **`convocatoria`:** tests del adaptador para los cinco resultados del enum, y `acceptsInKind()`.
8. **`app`:** el test de *wiring* de §3.4.

**Verificación:**
- `mvn clean test -fae` del reactor completo con la salida literal de Surefire de cada módulo.
- Mutaciones, cada una debe hacer fallar algún test:
  - `register` sin el `campaignRef` del `Fund`;
  - `split` sin heredar;
  - aceptar `CAMPAIGN_CLOSED`;
  - consultar el puerto antes de autorizar;
  - dar mensajes distintos a "no existe" y "otra organización";
  - no declarar `AssetRegisteredV3Payload` en las proyecciones.
- Relectura adversarial del diff.

## 5. Forma de entrega

- **Una rama y un PR:** `feat/d-campaign-campaignref`. Toca `contracts`, `core`, `convocatoria` y `app`, pero es un único cambio coherente: el contrato nuevo y su primera implementación van juntos, para no dejar en `develop` un puerto sin implementar.
- **Commits separados:** tests que fallan → `contracts` → `core` → proyecciones → `convocatoria` → `app` (*wiring*) → documentos.

## 6. Fuera de alcance

- La saga del hijo de la división (B1-bis): este plan deja el `campaignRef` en `AssetSplitV3Payload`, y la saga lo usará.
- El modelo de agregación y el índice de la narrativa de convocatoria (B5, C5 de ADR-040).
- La proyección de los activos del Camino B (Q3 de la enmienda): siguen ignorados por las proyecciones, aunque ya lleven `campaignRef` en su evento.
- H1 con activos en especie (Q4: solo cuentan las intenciones; pendiente registrado).
- El contrato HTTP del registro (D-API/B6).

## 7. Preguntas — respondidas por Carlos el 2026-10-07

| # | Pregunta | Decisión |
|---|---|---|
| Q1 | Resultado del puerto: ¿enum o `boolean`? | **Enum**, con cada valor asociado a una excepción nombrada; hacia fuera, "no existe" y "otra organización" dan la misma respuesta |
| Q2 | ¿Qué pasa si no hay implementación del puerto? | **Puerto obligatorio** con stub compartido en los tests de `core` (la alternativa más limpia de la revisión): sin implementación, la aplicación no arranca, y el test de `app` lo comprueba |
| Q3 | ¿`campaignRef` en `LogisticsProjection` ya en este PR? | **Sí** |
| Q4 | ¿Un único PR multimódulo? | **Sí** |

## 8. Ajustes de la revisión (2026-10-07), incorporados arriba

1. **Seguridad — autorizar antes de consultar la convocatoria** y la misma respuesta externa para "no existe" y "otra organización", para que un actor no pueda averiguar si un `campaignRef` existe en otra organización. Corrige el orden de la Enmienda 1 de ADR-029 (D3), que queda anotado en la enmienda.
2. **Sin ocultar errores de cableado:** el puerto es una dependencia obligatoria (§3.2.3), y el test de `app` falla si falta la implementación (§3.4).
3. **Decisión escrita:** el Camino A no comprueba que la convocatoria siga `OPEN` (§3.2.3).
