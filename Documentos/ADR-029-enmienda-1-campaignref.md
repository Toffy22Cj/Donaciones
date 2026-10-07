# ADR-029 — Enmienda 1: `campaignRef` en `PhysicalAsset` (D-CAMPAIGN)

**Estado:** **APROBADA — Carlos, 2026-10-07**, con las respuestas de §7 (Q1–Q5 según la recomendación). Redactada por el agente a petición de Carlos. La implementación necesita, además, la aprobación de su plan (regla 3.4).
**Origen:** decisión D-CAMPAIGN de `plan-cierre-fase6-codigo.md` (B0). Cumple las dependencias que ADR-037 Enmienda 1 §7.2 y §9.1 dejan a "la enmienda de ADR-029".
**Bloquea:** B5 (narrativa de convocatoria, criterio 14 de `golden-path.md` §8), B1-bis (saga de la división, criterio 15) y, por el hallazgo H-PROJ (§6), también B7.

---

## 1. Qué está decidido antes de esta enmienda (vinculante, no se reabre)

| Decisión | Fuente |
|---|---|
| `campaignRef` en `PhysicalAsset`: **heredado del `Fund`** en el Camino A, **recibido en el comando** en el Camino B, **heredado del padre** en la división. Inmutable tras el registro | ADR-037 Enmienda 1 §7.2; `implementation_plan.md` §11 (fila ADR-029) |
| Los activos existentes y los que provengan de un `Fund` con `campaignRef = null` **quedan sin convocatoria**. Su convocatoria **nunca** se infiere recorriendo proyecciones. Solo admiten la autoridad ordinaria de `EMPLOYEE` | ADR-037 Enmienda 1 §9.1; ADR-040 §8 (corrección posterior) |
| Incorporarlo exige una **nueva versión** de `ASSET_REGISTERED` y del evento de división; el número de versión y la lectura de las anteriores los fija esta enmienda | ADR-037 Enmienda 1 §9.1 |
| La agregación de `ConvocatoriaAuditFacts` **excluye**, no infiere, los activos sin `campaignRef` | ADR-040 §8 |
| Precedente: ADR-028/029 prohibieron *backfill* y valores centinela para `organizationRef` en datos v1 | ADR-029 §4; ADR-037 Enmienda 1 §9.1 |

Esta enmienda solo fija **cómo** se materializa esa dirección y lo que las fuentes dejaron abierto: el esquema, la lectura de lo anterior, la validación del Camino B y la renuncia explícita al *backfill*.

## 2. Hechos verificados en el código (`develop` en `c5456a8`, 2026-10-07)

Rutas relativas a `core/src/main/java/com/traceability/core/`, salvo que se indique otra.

| Hecho | Evidencia |
|---|---|
| El registro de payloads se indexa por `(eventType, schemaVersion)`. La versión que se escribe la decide la **clase** del payload. Hoy: `ASSET_REGISTERED` 1.0/2.0 y `ASSET_SPLIT` 1.0/2.0 | `application/event/EventPayloadRegistry.java:17-36, 67-73`; `MongoEventStoreAdapter.java:60` |
| **No hay *upcaster*.** Cada versión se deserializa a su propio *record*. El agregado trata cada clase en su `apply` | `EventCanonicalMapper.java:66-69`; `PhysicalAsset.java:242-272`; grep `[Uu]pcast` sin resultados |
| `AssetRegisteredV2Payload` = los 10 campos de v1 + `organizationRef`, `donorRef`, `donationRef`. `AssetSplitV2Payload` = v1 + esos tres. **Ninguno tiene `campaignRef`** | `domain/physicalasset/payloads/AssetRegisteredV2Payload.java:11-25`, `AssetSplitV2Payload.java:11-24` |
| `PhysicalAsset` no tiene campo `campaignRef` | `domain/physicalasset/PhysicalAsset.java:22-46` |
| Camino A: `registerPhysicalAsset(commandId, fundId, organizationRef, …)` **ya carga el `Fund`** en `assertOrganizationMatchesFund` (antes de autorizar y de persistir), pero no lee su `campaignRef` | `application/command/PhysicalAssetCommandService.java:105-184` |
| Camino B: `registerPhysicalAssetFromDonation(commandId, organizationRef, donorRef, …)` sin parámetro ni comprobación de convocatoria | `PhysicalAssetCommandService.java:189-234` |
| División: `split` hereda `organizationRef`/`donorRef`/`donationRef` del padre en `AssetSplitV2Payload`. **No existe la saga que crea el stream hijo** (D-SPLIT) | `PhysicalAsset.java:171-198`; `plan-cierre-fase6-codigo.md`, D-SPLIT |
| `Fund.campaignRef` es **opcional** y se persiste en `FUND_REGISTERED` y `FUNDS_CLEARED` (v1 y v2). El orquestador de ADR-045 pasa el `campaignRef` de la `DonationIntent` | `domain/fund/Fund.java:22, 90, 103, 194-213`; `app/.../FundsApplicationOrchestrator.java:89-91` |
| El hash del evento incluye el **payload completo** y `schemaVersion`, y se calcula **solo al escribir**. La verificación de integridad compara el hash guardado, no lo recalcula | `EventCanonicalMapper.java:51-58`; `MongoEventStoreAdapter.java:66-80`; `app/.../IntegrityVerificationUseCase.java:53-74` |
| El `ObjectMapper` del lector falla ante propiedades desconocidas: un evento v2 guardado **no** puede leerse como un *record* con más campos, ni al revés con campos de sobra | `EventCanonicalMapper.java:24-28` |
| `core` depende solo de `contracts`; `convocatoria`, también. `core` no puede consultar `convocatoria` sin un puerto en `contracts` (patrón de `IdentityPrincipalPort` y `OrganizationVerificationPort`) | `core/pom.xml:17`; `convocatoria/pom.xml:19`; `app/pom.xml:17,42` |
| `convocatoria` no tiene `acceptsInKind()`: solo comprueba `MONETARY`. `IN_KIND` solo aparece en Javadoc y mensajes | `convocatoria/.../ConvocatoriaConfiguration.java:46, 68-70`; `Convocatoria.java:127-146` |
| Ningún llamador de producción usa los servicios de `PhysicalAsset` (no hay endpoint): solo los tests | grep en `api`, `app`, `ai`, `convocatoria` |
| `CampaignAuditFactsPort` y `CampaignAuditFactsDTO` existen en `contracts`, **sin productor ni consumidor** | `contracts/.../CampaignAuditFactsPort.java:11-13` |

## 3. Decisión propuesta

### D1. Esquema: versión 3.0 de los dos eventos

- **`ASSET_REGISTERED` 3.0 → `AssetRegisteredV3Payload`** = los campos de `AssetRegisteredV2Payload` + `campaignRef` (texto, **puede ser `null`**).
- **`ASSET_SPLIT` 3.0 → `AssetSplitV3Payload`** = los campos de `AssetSplitV2Payload` + `campaignRef` del padre (puede ser `null`).
- **Toda escritura nueva usa la 3.0**, también cuando `campaignRef` es `null`. Así hay una sola versión de escritura y "sin convocatoria" se registra de forma explícita.
- **Las versiones 1.0 y 2.0 no cambian**: sus *records* siguen registrados para leer el histórico. No se reescribe ningún evento guardado.
- **Sin *upcaster* de almacenamiento.** La lectura sigue el patrón vigente: el `apply` de `PhysicalAsset` trata `V1`, `V2` y `V3`; para `V1` y `V2`, `campaignRef = null` (igual que hoy `organizationRef` en v1).
- **Integridad:** los eventos 3.0 se encadenan con su `campaignRef` dentro del hash. Los 1.0 y 2.0 conservan su hash guardado. No cambia nada de lo ya anclado.

### D2. Camino A: heredado del `Fund`, nunca del llamador

- `campaignRef` **no es parámetro** de `registerPhysicalAsset`. Se toma de `Fund.campaignRef` del mismo `Fund` que ya carga `assertOrganizationMatchesFund`. Así no puede divergir del `Fund` y no hace falta validarlo.
- Si el `Fund` no tiene convocatoria (`null`), el activo nace sin convocatoria. No es un error.
- No cambia el orden actual: validación contra el `Fund`, después autorización y después persistencia (ADR-029 §2.1).

### D3. Camino B: recibido en el comando y validado contra `convocatoria`

- `registerPhysicalAssetFromDonation` recibe un `campaignRef` **opcional**. `null` = donación en especie sin convocatoria (lo que existe hoy).
- Si viene informado, se valida **después de autorizar y antes de persistir**: un rechazo no deja eventos, igual que el Camino A. *Corrección aprobada por Carlos el 2026-10-07 al revisar el plan de implementación: el texto aprobado decía "antes de autorizar". Consultar la convocatoria antes de autorizar permitiría a un actor de otra organización averiguar si un `campaignRef` existe, porque `CAMPAIGN_NOT_FOUND` y `OTHER_ORGANIZATION` darían errores distintos. Además, hacia fuera los dos casos dan la **misma respuesta**.*
  - la convocatoria existe;
  - pertenece a la misma `organizationRef` del comando;
  - está `OPEN`;
  - acepta `IN_KIND`.
- **Mecanismo — decidido: (a), puerto en `contracts` (Carlos, 2026-10-07, Q1).** Opciones consideradas:
  - **(a) Recomendada — puerto en `contracts`**, provisionalmente `CampaignInKindEligibilityPort`, con una sola pregunta cerrada: "¿esta convocatoria acepta ahora una donación en especie de esta organización?". Lo implementa `convocatoria` y se conecta en `app`, con el mismo patrón que `OrganizationVerificationPort`. `core` lo consume sin conocer `convocatoria`. Requiere añadir `acceptsInKind()` a `ConvocatoriaConfiguration`.
  - (b) Validar solo en el caso de uso de `app`/`api` antes de llamar a `core`. Más barata, pero `core` aceptaría cualquier `campaignRef` de otros llamadores (tests, futuros consumidores, `SystemActor`). Contradice el criterio de ADR-029 §2.1: el invariante no se delega en quien llama.
  - (c) Sin validación. **Desaconsejada:** un `campaignRef` inventado o de otra organización contaría unidades en la narrativa de otra convocatoria.
- Como en ADR-037 Enmienda 1 §7.1, se **acepta** la ventana entre la comprobación y la escritura: una convocatoria que se cierre en ese intervalo puede recibir un último activo.
- **Momento de referencia de la comprobación de `OPEN` (pregunta Q5, añadida el 2026-10-07 tras la revisión):** en el dinero, una intención creada con la convocatoria `OPEN` conserva su validez tras el cierre (ADR-037 §2.6bis, D1). En especie no hay un registro previo del acto de donación: el activo **es** el primer registro. Una donación entregada el último día y registrada dos días después, ya `CLOSED`, quedaría sin convocatoria con la regla de arriba. Opciones:
  - **(a) `OPEN` en el momento del registro** (texto actual). Simple y sin datos declarados. **Limitación conocida que se deja escrita:** las donaciones registradas después del cierre quedan sin convocatoria.
  - **(b) `OPEN` en el momento del acto de donación**, coherente con D1. Exige dos cosas que hoy no existen: (1) que el comando reciba la **fecha del acto**, un dato **declarado** por el operador e inverificable, y (2) que `convocatoria` conserve la **fecha de cierre** (`Convocatoria` no tiene `closedAt`: `Convocatoria.java:31-41`; solo queda en el audit log). Riesgo: con una fecha declarada, se podría asociar a una convocatoria cerrada un activo recibido mucho después. Mitigación posible: un plazo máximo de gracia tras el cierre (valor de producto) y la fecha declarada dentro del payload v3, con hash.
  - **Decidido: (a) — Carlos, 2026-10-07 (Q5).** `OPEN` se comprueba en el momento del registro. **Limitación conocida:** una donación en especie registrada después del cierre de su convocatoria queda sin convocatoria. (b) queda como evolución si el caso aparece en producción.
- **Riesgo aceptado, explícito:** la comprobación ocurre **antes** de la escritura y **fuera** de la transacción de `core` (el puerto consulta `convocatoria`, que es otro módulo). Si la convocatoria se cierra entre las dos, el activo queda asociado igualmente. Es una ventana pequeña y benigna: el activo es real y de la misma organización, y solo hay una convocatoria posible. No se cierra con bloqueos.
- Ese mecanismo nuevo queda cubierto por esta enmienda cuando se apruebe (regla 3.5).

### D4. División: heredado del padre

- El hijo hereda el `campaignRef` del padre a través de `AssetSplitV3Payload`, igual que `organizationRef`, `donorRef` y `donationRef` (ADR-029 §3).
- La saga del hijo (B1-bis) crea el stream con ese valor y **no lo vuelve a calcular**. Un padre v1/v2 da un hijo sin convocatoria.

### D5. Inmutabilidad y renuncia al *backfill*

- **Ningún comando** cambia `campaignRef` después del registro.
- **Se descarta explícitamente cualquier *backfill*** de `campaignRef` en activos existentes, y cualquier valor centinela. Esto cierra la dependencia de ADR-037 Enmienda 1 §9.1 con el mismo criterio que ADR-028/029 para `organizationRef`.
- La convocatoria de un activo **nunca** se infiere de proyecciones, de su `Fund` después del registro ni de su `donationRef`.

### D6. Activos anteriores

- Los activos **v1** (sin `organizationRef`) siguen igual: se leen, pero rechazan toda mutación (ADR-029 §4).
- Los activos **v2** operan con normalidad como **activos sin convocatoria**: autoridad ordinaria de `EMPLOYEE`, sin capacidad contextual (ADR-037 Enmienda 1 §9.1), y fuera de la narrativa de convocatoria.

### D7. Lectura para la narrativa (B5)

- La agregación por convocatoria necesita `campaignRef` **por activo** en un modelo de lectura, con el índice `{campaignRef: 1, status: 1}` que ya anticipa ADR-040 (§7-B). El modelo concreto (proyección nueva o ampliación de una existente) lo fija C5 de ADR-040, no esta enmienda.
- Requisito que sí fija esta enmienda: el `campaignRef` del modelo de lectura sale **solo** del payload v3 del propio activo. Los activos v1/v2 quedan con `campaignRef = null` y la agregación los **excluye**.
- Depende de H-PROJ (§6): hoy la proyección no procesa ni siquiera los eventos v2.

## 4. Lo que esta enmienda no decide

- **H1 ("primera donación") con activos en especie.** ADR-037 Enmienda 1 pide que, cuando exista `campaignRef` en los activos, la comprobación de primera donación de `convocatoria` tenga en cuenta los activos en especie. `convocatoria` no ve el event store de `core`. Queda como **pregunta Q4**: o el puerto de D3 registra el primer uso en `convocatoria`, o se acepta que H1 siga contando solo intenciones hasta que exista una consulta inversa. No bloquea la demo.
- **Autorización contextual** del responsable de la convocatoria sobre sus activos (ADR-032, P6): sigue pendiente; esta enmienda solo aporta el dato.
- **Contrato HTTP** del registro y la división (D-API, B6).

## 5. Plan de implementación (orientativo; necesita aprobación propia por la regla 3.4)

1. **`core`:** `AssetRegisteredV3Payload` y `AssetSplitV3Payload`, registrados como 3.0. `PhysicalAsset` con `campaignRef`, `apply` de V1/V2/V3, `register`/`create`/`split` escribiendo V3. Camino A con `campaignRef` del `Fund`. Camino B con parámetro opcional y consumo del puerto de D3.
2. **`contracts`:** el puerto de D3 y su excepción de rechazo.
3. **`convocatoria`:** `ConvocatoriaConfiguration.acceptsInKind()` y el adaptador del puerto.
4. **`app`:** conexión del adaptador y test de *wiring* (patrón de `OrganizationVerificationWiringIntegrationTest`).
5. **Tests (DoD):**
   - Camino A: el activo hereda el `campaignRef` del `Fund`; `Fund` sin convocatoria → activo sin convocatoria.
   - Camino B: `campaignRef` válido aceptado. Rechazos sin efectos: convocatoria inexistente, de otra organización, `CLOSED` o solo `MONETARY`. `null` aceptado.
   - División: el payload V3 lleva el `campaignRef` del padre; un padre v2 → `null`.
   - Lectura: un stream con eventos v1/v2/v3 se rehidrata; v1/v2 → `campaignRef = null`.
   - Integridad: los eventos v2 guardados conservan su hash y siguen verificando contra su lote.
   - Idempotencia: el mismo `commandId` no duplica el activo con o sin `campaignRef`.

## 6. Hallazgo H-PROJ (verificado; fuera de D-CAMPAIGN pero bloqueante)

**`DonationProjectionHandler` y `DonationAuditFactsHandler` solo reconocen los payloads v1, y el código escribe v2.**

| Evento escrito hoy | Lo que hace la proyección | Evidencia |
|---|---|---|
| `FUNDS_CLEARED` 2.0 (`clearFundsGenesis`, la génesis del orquestador de ADR-045) y `FUND_REGISTERED` 2.0 | No coincide con `instanceof FundsClearedPayload`/`FundRegisteredPayload`: solo avanza la secuencia; **no proyecta importe, moneda ni `campaignRef`** | `application/projection/DonationProjectionHandler.java:97-107`; `Fund.java:90, 103, 121` |
| `ASSET_REGISTERED` 2.0 (Camino A y Camino B) | `resolveProjectionId` solo resuelve `AssetRegisteredPayload` (v1) → `null` → `MissingDependencyException`. El activo **nunca se proyecta** y sus eventos siguientes tampoco | `DonationProjectionHandler.java:146-152, 174, 216, 225-255` |
| `ASSET_REGISTERED` 2.0 en la auditoría | Igual en `DonationAuditFactsHandler` | `DonationAuditFactsHandler.java:233` |

- **Ningún test lo detecta:** los tests de proyección no pasan eventos v2 por los manejadores (grep en `core/src/test`).
- **Impacto:** el seguimiento público (Fase 3) de una donación aplicada por ADR-045 mostraría importe 0 y ningún activo. La narrativa individual y la de convocatoria no tendrían hechos. Afecta a los criterios del golden path que consultan la trazabilidad (§7.1, §7.3 y §7.4) y al 14.
- **Hueco de diseño adicional:** la `DonationProjection` se indexa por `fundId`. Un activo del **Camino B** no tiene `Fund` ni `allocationId`, así que hoy no tiene proyección a la que pertenecer, ni siquiera con v1. Dónde se proyecta una donación en especie es una decisión de diseño (Fase 3/ADR-040 C5), no un arreglo.
- **Propuesta:** un bloque nuevo **B-PROJ** en `core`, independiente de esta enmienda y previo a B5 y B7. *Actualización 2026-10-07 tras la revisión:* B-PROJ pasa a ser una **corrección** (`fix/`) de lo ya fusionado, con prioridad sobre el resto; su plan está en `plan-b-proj.md`, que añade un segundo defecto más grave (**origen de la secuencia**: el event store numera la génesis como 1 y los manejadores la esperan en 0, así que ningún stream real se proyecta). El soporte de la versión 3 se añade con la implementación de D-CAMPAIGN. Los manejadores tratan las versiones 1, 2 y 3. Tests de proyección con eventos reales de `clearFundsGenesis`, `registerPhysicalAsset` y `registerPhysicalAssetFromDonation`. El hueco del Camino B se decide aparte (pregunta Q3).

## 7. Preguntas para Carlos — respondidas el 2026-10-07 (todas según la recomendación)

| # | Pregunta | Recomendación |
|---|---|---|
| Q1 | Mecanismo de validación del Camino B (D3) | (a) puerto en `contracts` implementado por `convocatoria` |
| Q2 | ¿`campaignRef` del Camino B opcional (`null` = sin convocatoria)? | Sí: conserva la donación en especie sin convocatoria que ya existe |
| Q3 | H-PROJ: ¿se abre B-PROJ ya, y quién decide dónde se proyecta un activo del Camino B? | Abrir B-PROJ ya (camino crítico de B5 y B7); el diseño del Camino B, con C5 de ADR-040 |
| Q4 | H1 con activos en especie (§4) | Aceptar que H1 cuente solo intenciones en este corte y registrarlo como pendiente |
| Q5 | ¿`OPEN` en el momento del registro o del acto de donación? (D3) | (a) registro, con la limitación escrita; (b) como evolución |

## 8. Consecuencias

**Positivas**
- La narrativa de convocatoria puede contar los activos de forma verificable: el `campaignRef` va dentro del evento con hash y anclaje.
- No se toca el histórico ni sus hashes.
- La división hereda la convocatoria sin lógica nueva en la saga.

**Negativas / restricciones**
- Una tercera versión de dos eventos y un `apply` con tres ramas por evento.
- Los activos anteriores a la 3.0 quedan fuera de toda narrativa de convocatoria para siempre (decisión ya tomada, D5).
- Un puerto más entre `core` y `convocatoria` (si se elige Q1 (a)) y su conexión en `app`.
- La ventana entre la validación y la escritura en el Camino B se acepta, no se cierra.
