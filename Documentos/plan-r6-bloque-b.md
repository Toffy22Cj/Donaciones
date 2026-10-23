# Plan — Ficha R-6, Bloque B: fecha del pago en el seguimiento (G-2) y recaudado por mes (G-6)

**Estado: BORRADOR — espera "plan aprobado" (regla 3.4).** No autoriza código.
**Fecha:** 2026-10-23. **Autor:** Manuel, con asistencia de Claude. **Base verificada:** `develop` `0b3dc3e`, que incluye el #112.
**Norma:** `Documentos/ficha-R6-datos-estilo-v2.md`, CONGELADA: §4 (Bloque B), §7 (tests 5 y 6), §8, §9 (D-R6-2, D-R6-3, D-R6-4, D-R6-10) y §10 (C-2, C-3 y C-7).
**Rama de implementación prevista:** `feat/r6-bloque-b`, la misma de este plan. Es un PR solo para este bloque (D-R6-10).

---

## 0. Verificación de la ficha contra el código (`0b3dc3e`)

La ficha no se modifica.

### 0.1 §4 de la ficha

| # | Afirmación de la ficha | Resultado | Evidencia |
|---|---|---|---|
| B-1 | El seguimiento es `GET /api/v1/donations/tracking` y su `financialSnapshot` no tiene fechas | **CONFIRMADA** | `PublicDonationController.java:26-34`; `PublicFinancialSnapshotDTO.java:5-12` (`currency?`, `originalAmount`, `clearedAmount`, `pendingAllocationAmount`, `confirmedAllocationAmount`, `refundedAmount`); lo arma `PublicDonationMapper.toPublicDTO` |
| B-2 | Un fondo puede tener varias liquidaciones, y la proyección las acumula con `inc` | **CONFIRMADA** | `DonationProjectionHandler.java:142` (1.0) y `:149` (2.0), `update.inc("financialSnapshot.clearedAmount", …)`. Las liquidaciones posteriores son 2.0 (`Fund.clearFunds`, `Fund.java:117-124`) |
| B-3 | Cada evento guarda `occurredAt` (el hecho) y `recordedAt` (el registro) | **CONFIRMADA** | `TraceabilityEventDocument.java:31-32`, los dos como texto ISO-8601; `MongoEventStoreAdapter.java:95` escribe `occurredAt.toString()`. **Matiz:** `occurredAt` lo fija `AggregateRoot.raiseEvent` con `Instant.now()` (`AggregateRoot.java:44`), sin reloj inyectable (ver §3) |
| B-4 | Leer un stream por `streamId` usa el índice `(streamId, sequence)` | **CONFIRMADA** | `@CompoundIndex idx_stream_sequence {streamId: 1, sequence: 1}`, único (`TraceabilityEventDocument.java:14`). `EventStorePort.loadStream` devuelve el stream ordenado por `sequence` ascendente, con el `occurredAt` de cada `DomainEvent` (`EventStorePort`; `DomainEvent.java:11-15`). Medido: un fondo, 6 claves, 0 ms (§7.1) |
| B-5 | La ruta `GET /organizations/{organizationId}/funds` y su política (DD-31) | **CONFIRMADA** (y C-7) | Ruta en **`api`**: `FundController.java:52`. Política en **`core`**: `FundOperationalQueryService.listForOrganization` (`:43-48`): frontera de organización y `ADMINISTRATOR` o `EMPLOYEE`. Tope `MAX_FUNDS = 200` (`:26`) |
| B-6 | Hoy no hay ninguna lectura por mes ni ninguna zona horaria fija en el código | **CONFIRMADA** | Ninguna coincidencia de `America/Bogota` ni de `ZoneId.of` en `*/src/main` |

### 0.2 Corrección C-2

| Afirmación | Resultado | Evidencia |
|---|---|---|
| `ProjectionRebuildService.rebuildAll()` no tiene disparador en producción, solo un test | **CONFIRMADA en `0b3dc3e`** | Definido en `core/.../projection/ProjectionRebuildService.java:40`. No hay ninguna otra referencia en `*/src/main`, `scripts`, `*.yml` ni `*.properties`. Solo lo llama `DonationProjectionIntegrationTest`, 8 veces. Por eso G-2 se lee del Event Store y no toca `DonationProjectionDocument` |

### 0.3 Corrección C-3 y el matiz encontrado en el #109

| Afirmación | Resultado | Evidencia |
|---|---|---|
| `FUNDS_CLEARED` 1.0 (`FundsClearedPayload`) no lleva `organizationRef` y la 2.0 sí | **CONFIRMADA** | `FundsClearedPayload.java` (`clearedAmount`, `sourceReference`, `currency`, `campaignRef`, `donorRef`); `FundsClearedV2Payload.java` (`organizationRef` en primer lugar). Registro: `EventPayloadRegistry.java:46-47` |
| La pertenencia se resuelve "por el `FUND_REGISTERED`", como hace `MongoFundDirectoryAdapter` | **Inexacta en dos puntos** | (1) El adaptador resuelve por la **génesis** (`sequence = 1`), que puede ser `FUND_REGISTERED` **o `FUNDS_CLEARED`**, y exige `payload.organizationRef` (`MongoFundDirectoryAdapter.java:26-29`). La génesis `FUNDS_CLEARED` 2.0 es la de la aplicación de fondos de ADR-045 (`Fund.clearFundsGenesis`, `Fund.java:96-107`; `FundCommandService.java:85`, `:108`). (2) **`FUND_REGISTERED` 1.0 tampoco lleva `organizationRef`**: `FundRegisteredPayload.java` (`pledgedAmount`, `currency`, `campaignRef`, `donorRef`), y `Fund.apply` no le asigna organización (`Fund.java`, rama `FundRegisteredPayload`) |

**¿Puede existir un fondo cuya génesis sea un `FUNDS_CLEARED` 1.0?**

- **En el código de hoy: no.**
  - `Fund` solo emite payloads 2.0 en sus tres puntos de escritura: `registerFund` (`Fund.java:92`), `clearFundsGenesis` (`:105`) y `clearFunds` (`:123`).
  - Ningún código de producción construye `FundsClearedPayload` ni `FundRegisteredPayload`: `git grep "new FundsClearedPayload(\|new FundRegisteredPayload("` solo encuentra `core/src/test/.../FundTest.java`.
- **En el historial: sí, durante quince días.**
  - Desde el primer commit (`4ab3a34`, 2026-09-01) hasta `0579f41` (2026-09-16), `Fund` escribía `FUND_REGISTERED` 1.0 (`new FundRegisteredPayload(pledgedAmount)`) y `FUNDS_CLEARED` 1.0 (`new FundsClearedPayload(amount, sourceRef)`), tanto en la génesis como en las liquidaciones posteriores. Se ve en `git show 4ab3a34:core/.../Fund.java`, líneas 53, 63 y 73.
  - `0579f41` introdujo `OrganizationRef`, los payloads 2.0 y `InvalidFundGenesisException`.
- **Consecuencia:** un stream con un `FUNDS_CLEARED` 1.0 viene siempre de esa época y su génesis también es 1.0, **sin organización en ningún evento**.
  - Además, ese fondo no puede recibir liquidaciones nuevas: `clearFunds` y las demás órdenes llaman a `checkOrganizationAssigned()` (`Fund.java:111`, `:118`), que lanza `FundNotAssociatedToOrganizationException` porque el `apply` de los payloads 1.0 nunca fija `organizationRef`.
  - **Hoy ya queda fuera de `GET /funds`**: el directorio exige `payload.organizationRef` en la génesis.
- **En los datos:**
  - Las bases de los tests son efímeras (Testcontainers).
  - El Mongo de la demo (`scripts/demo/docker-compose.yml`, creado el 2026-10-07, en `c3a9869`) no declara volúmenes, así que no persiste. Además nació después de `0579f41`.
  - **Desde aquí no se puede comprobar si existe otra base persistente** con eventos anteriores al 2026-09-16 (**P-R6B-1**).
- **Cómo se le asigna organización:** con el Event Store no se puede; **no hay de dónde sacarla** sin inventar. El plan sigue la regla del directorio: un fondo cuenta para una organización solo si su **génesis** lleva `organizationRef`. Los fondos 1.0 sin organización quedan fuera, igual que hoy en `GET /funds` (**P-R6B-1**).
- **Un `FUNDS_CLEARED` 1.0 dentro de un stream con génesis 2.0** no lo produce ningún código, ni el de hoy ni el antiguo. El test 6 de la ficha ("eventos 1.0 y 2.0 sumados en la organización correcta") solo puede construirlo **a mano** en el Event Store (**P-R6B-2**).

## 1. Alcance

**Dentro**
- G-2 y G-6 (ficha §4).
- Los tests 5 y 6 (§7).
- `referencia-api-v1.md`, `api-contract-matrix.md` y `estado-fase6.md`.
- El aviso a la web (§8).

**Fuera**
- Los bloques C y D.
- Cualquier cambio en la proyección de donaciones (C-2).
- Cualquier evento nuevo o cambio de payload (ficha §2).
- Dar organización a los fondos 1.0 sin organización (§0.3).

## 2. Decisiones del plan, con su fuente en la ficha

### 2.1 G-2 · `firstClearedAt?` y `lastClearedAt?` en el seguimiento

**El puerto de `core`.** Ningún puerto de lectura existente da hoy las fechas de las liquidaciones:
- `DonationReadPort` lee la proyección, que no las guarda (C-2);
- `EventStorePort.loadStream` sí las tiene, pero leerlo y filtrar desde `api` pondría lógica de `core` en `api`.

Se propone un puerto de lectura nuevo en `core.application.port.out`, como `DonationReadPort`:

```java
package com.traceability.core.application.port.out;

public interface FundClearingDatesReadPort {

    /** Vacío si el fondo no tiene ningún FUNDS_CLEARED (1.0 o 2.0). Primero y último por sequence, nunca por fecha. */
    Optional<ClearingDates> findClearingDates(String fundId);

    record ClearingDates(Instant firstClearedAt, Instant lastClearedAt) {}
}
```

- **Implementación en `core`:** una consulta en `core.application.query`, junto a `FundOperationalQueryService`.
  - Usa el `EventStorePort.loadStream(fundId)` que ya existe: el stream sale ordenado por `sequence`.
  - Toma el `occurredAt` del primer y del último evento cuyo payload sea `FundsClearedPayload` o `FundsClearedV2Payload` (D-R6-2).
  - Sin autorización propia: la da el `trackingCode`, que el filtro ya resolvió a ese `fundId`, como en el resto del seguimiento.
  - **Nombre: P-R6B-5.**
- **Cómo llega a `api`:**
  - `PublicDonationMapper` recibe el puerto y lo consulta con `model.fundId()`, que ya está en `DonationReadModel`.
  - `PublicFinancialSnapshotDTO` gana `String firstClearedAt` y `String lastClearedAt`, los dos con `@JsonInclude(NON_NULL)` y en ISO-8601 UTC (`Instant.toString()`).
  - Si no hay liquidaciones, faltan los dos.
- **Una sola liquidación:** `firstClearedAt = lastClearedAt`. La ficha lo pide así (test 5) y el front decide si muestra una o dos.
- **Coste:** una lectura más por consulta de seguimiento, del stream de **un** fondo por el índice único (§7.1).
- **Nada más cambia:** ni `fundId` ni `donorRef` salen (DD-76), ni cambia el resto de `financialSnapshot` ni la proyección.

### 2.2 G-6 · `GET /api/v1/organizations/{organizationId}/funds/monthly?months=1..12`

**Ruta, en `api`:** en `FundController`, donde ya está `GET /funds` (C-7).
- `months` es opcional: 6 por defecto, entero de 1 a 12. Fuera de rango o mal formado → 400 `InvalidRequestFieldException("months")`, el formato de `api` para los campos.
- `Cache-Control: no-store`, como pide la ficha.

**Política: la misma de `GET /funds` (DD-31).** Frontera de organización y `ADMINISTRATOR` o `EMPLOYEE`; otra organización o una inexistente, el mismo 403. Para no duplicarla, la consulta vive **en `FundOperationalQueryService`**, que ya la aplica (`:43-48`), y la expone un puerto (**P-R6B-4**).

**Respuesta** (ficha §4): `200 {timezone: "America/Bogota", items: [{month: "2026-10", currency, clearedAmount}]}`.
- `clearedAmount` va como texto en unidades mínimas (§0).
- Un ítem por mes y moneda, **incluidos los meses en 0**.
- Orden: mes ascendente y, dentro del mes, moneda. Ver **P-R6B-3** sobre qué monedas llevan meses en 0.

**Cálculo, en `core`:**
1. Fondos de la organización: `FundDirectoryPort.findFundIdsByOrganization(org, límite)`, por la génesis (§0.3). Sobre el límite: **P-R6B-6**.
2. Sus `FUNDS_CLEARED` (1.0 y 2.0), con **una** consulta por `streamId $in` y `eventType = FUNDS_CLEARED` que devuelva solo `streamId`, `occurredAt`, `clearedAmount` y `currency` (medido: 500 fondos, 10 ms, §7.1). Se propone un puerto de salida nuevo con su adaptador Mongo, porque `EventStorePort` solo lee stream a stream (**P-R6B-5**).
3. Ventana: el mes en curso en `America/Bogota` y los `months - 1` anteriores, con `now` de un `Clock` inyectable (`ObjectProvider<Clock>`, como `CampaignDeliveryFactsQuery.java:30-33`).
4. Cada evento cae en el mes de `occurredAt.atZone(America/Bogota)` (D-R6-4). Las 23:30 del 31 en Bogotá son las 04:30 del día 1 en UTC, y **cuentan en el mes de Bogotá**.
5. La moneda sale del payload; si falta (posible en un 1.0 antiguo), la del fondo. Los importes se suman con `long`.

- **Por qué no se filtra por fecha en Mongo:** `occurredAt` se guarda como texto ISO y `Instant.toString()` omite los decimales cuando son cero, así que comparar textos no es comparar instantes. Se filtra en memoria después de la consulta de (2), que ya está acotada a los fondos de la organización.
- **Sin proyección nueva:** se recalcula en cada petición (ficha §4), con el límite registrado en §7.1.

### 2.3 Clases nuevas y por qué (regla 2.1)

| Clase o cambio | Módulo | Fuente |
|---|---|---|
| `FundClearingDatesReadPort` y su registro `ClearingDates` (nombre en P-R6B-5) | `core` | Ficha §4 G-2: "con un puerto de lectura de `core`"; C-2 |
| Su implementación, una consulta en `application.query` (nombre en P-R6B-5) | `core` | ídem |
| El puerto de lectura de G-6 (nombre y forma en P-R6B-4) y sus registros de respuesta | `core` | Ficha §4 G-6: "consulta en `core` expuesta por un puerto de lectura" |
| El puerto de salida de las liquidaciones por fondos, con su adaptador Mongo (nombre en P-R6B-5) | `core` | §2.2, paso 2: una consulta en lugar de una por fondo |
| `PublicFinancialSnapshotDTO` (dos campos), `PublicDonationMapper` (el puerto) y `FundController` (ruta y registros de respuesta) | `api` | Ficha §4 |

**No hay excepciones nuevas.** El 400 usa `InvalidRequestFieldException` y el 403 las de la política de siempre.

## 3. Tests — rojos primero (ficha §7, tests 5 y 6)

**Cómo se fija `occurredAt`.** `AggregateRoot` usa `Instant.now()` (B-3), así que los tests con fechas concretas escriben los eventos con `EventStorePort.append(streamId, "Fund", expectedVersion, List<DomainEvent>, actor)`. Esa llamada respeta el `occurredAt` de cada `DomainEvent` (`MongoEventStoreAdapter.java:75`, `:95`), y la versión de esquema sale de la clase del payload (`EventPayloadRegistry`), así que los 1.0 se escriben como 1.0.

| # | Test (ficha §7) | Dónde |
|---|---|---|
| 5a | Una liquidación: `firstClearedAt = lastClearedAt = occurredAt` del evento | `core` (consulta, Mongo real) |
| 5b | Dos liquidaciones con los `occurredAt` **desordenados** respecto a `sequence`: la primera y la última **por `sequence`** | `core` |
| 5c | Un fondo sin `FUNDS_CLEARED` (solo `FUND_REGISTERED`): vacío → en `api` faltan los dos campos | `core` y `api` |
| 5d | Una génesis `FUNDS_CLEARED` 1.0 y otra 2.0 dan fechas (versiones 1.0 y 2.0) | `core` |
| 5e | Por HTTP: el seguimiento lleva los dos campos en ISO-8601 UTC y sigue sin `fundId` ni `donorRef` (aserción negativa) | `api`, con un doble del puerto; `app`, con la donación real de `DonationPaymentHttpIntegrationTest` |
| 6a | Meses vacíos en 0: con `months=3` y liquidaciones solo en el mes en curso, los dos meses anteriores aparecen en `"0"` | `core` y `api` |
| 6b | **Corte de mes en `America/Bogota`:** un `FUNDS_CLEARED` con `occurredAt = 2026-10-31T23:30-05:00` (`2026-11-01T04:30:00Z`) cae en `2026-10`, y uno a las 00:30 del 1 de noviembre en Bogotá cae en `2026-11` | `core`, con un `Clock` fijo |
| 6c | **Versiones 1.0 y 2.0 en la organización correcta:** un stream con génesis 2.0 de la organización A y un `FUNDS_CLEARED` 1.0 posterior (escrito a mano; P-R6B-2) suma en A. Un stream de B no suma en A. Un stream con génesis 1.0 sin organización no suma en nadie | `core` |
| 6d | Otra organización, un rol sin permiso (`REPRESENTATIVE` solo) o una organización inexistente → el mismo 403 | `core` (excepción) y `app` (HTTP) |
| 6e | `months` = 0, 13, `-1` o `abc` → 400 que nombra el campo; sin `months` → 6 ítems por moneda | `api` y `app` |
| 6f | `timezone: "America/Bogota"`, `Cache-Control: no-store`, importes como texto, sin `fundId` ni `donorRef` (aserción negativa) | `app` (HTTP) |
| — | `api-contract-matrix.md` y el inventario de rutas: `GET /api/v1/organizations/{organizationId}/funds/monthly` en `PROTECTED` | `app` (`PublicRoutesInventoryIntegrationTest`) |
| — | Común: `scripts/ci-local.sh` completo en verde, con el resumen literal de Surefire | — |

## 4. Mutaciones (`scripts/mutaciones.py`)

1. G-2: primera y última por `occurredAt` en lugar de por `sequence` → muere 5b.
2. G-2: solo `FundsClearedV2Payload` (sin 1.0) → muere 5d.
3. G-2: `recordedAt` en lugar de `occurredAt` → mueren 5a y 5b.
4. G-6: corte de mes en UTC → muere 6b.
5. G-6: sin rellenar los meses vacíos → muere 6a.
6. G-6: solo `FUNDS_CLEARED` 2.0 → muere 6c.
7. G-6: sumar por el `organizationRef` del payload en lugar de por la génesis → muere 6c (el 1.0 de A se pierde).
8. G-6: quitar la comprobación de rol → muere 6d.
9. G-6: aceptar `months = 13` → muere 6e.

## 5. Orden de trabajo (tras "plan aprobado")

1. Tests rojos de `core`, `api` y `app`, con su salida literal.
2. Verde por capas, con el módulo completo al cerrar cada una:
   1. `core` G-2 (puerto y consulta);
   2. `api` G-2 (DTO y mapeador);
   3. `core` G-6 (puerto de salida y adaptador, y después la consulta);
   4. `api` G-6 (ruta);
   5. `app` (inventario y pruebas HTTP).
3. Commit, mutaciones y `ci-local.sh` en un clon limpio, revisando antes que haya al menos 4 GB libres. La evidencia va en `Documentos/evidencia-ci/`.
4. Documentos (§6) y PR.

## 6. Documentos y aviso a la web

- **`referencia-api-v1.md`**, solo con añadidos:
  - §8: nota fechada en `GET /donations/tracking` con `financialSnapshot.firstClearedAt?` y `lastClearedAt?`;
  - §3: fila nueva para `GET /organizations/{organizationId}/funds/monthly`.
- **`api-contract-matrix.md`:** fila nueva de G-6, con la ruta, la autorización (DD-31), la fuente (los `FUNDS_CLEARED` del Event Store) y la forma de la respuesta. Hoy la matriz no tiene ninguna fila de fondos, así que la fila nueva lleva una nota de procedencia.
- **`estado-fase6.md`:** entrada nueva con la evidencia.
- **Aviso a la web y al móvil**, con el commit de `develop` en que se fusione:
  - **A-4** (línea de tiempo del donante): "Pago confirmado" con `firstClearedAt` y, si es distinta, la última con `lastClearedAt`;
  - **B-2** (panel): el recaudado por mes, con `timezone`.

  El plan de pantallas y `paxfide-web/Documentos/prompts-agentes-estilo-v2.md` §3.4 están fuera de este repositorio: allí el hueco pasa a "disponible desde <commit>". Desde aquí no se tocan.

## 7. Riesgos y preguntas

### 7.1 Volumen del cálculo por petición (medido)

Medido el 2026-10-23 en Mongo **6.0.28** (`mongo:6.0`), con los índices reales de `event_store`.
- 303 000 eventos: 2000 organizaciones con 25 fondos cada una, más una organización con 500 fondos.
- Cada fondo con génesis, tres `FUNDS_CLEARED` y dos asignaciones.

| Consulta | Plan ganador | Claves | Documentos | Devueltos | ms |
|---|---|---|---|---|---|
| G-2: el stream de un fondo (`loadStream`) | `FETCH ← IXSCAN(idx_stream_sequence)` | 6 | 6 | 6 | 0 |
| G-6 paso 1: directorio de fondos de la organización (`MongoFundDirectoryAdapter`, el mismo de `GET /funds`) | `SORT ← FETCH ← IXSCAN(idx_event_type_campaign)` | **202 000** | **202 000** | 500 | **510** |
| G-6 paso 2: `FUNDS_CLEARED` de esos 500 fondos (`streamId $in`) | `FETCH ← IXSCAN(idx_stream_sequence)` | 3000 | 3000 | 2000 | 10 |

- **R-R6B-1, el coste lo domina el directorio.**
  - Ningún índice cubre `(aggregateType, sequence, eventType, payload.organizationRef)`, así que Mongo recorre todas las génesis y liquidaciones de la base, de todas las organizaciones.
  - **Ya pasa hoy en `GET /funds`**: G-6 no lo empeora, pero lo repite.
  - Un índice parcial nuevo, sin cambiar ningún evento (precedente: `idx_event_type_campaign`, B5/DD-33), lo resolvería. Es **P-R6B-7**.
- **R-R6B-2: el paso 2 crece con las liquidaciones de la organización**, no con las de la base. 2000 liquidaciones son 10 ms; el orden de magnitud aceptable llega a decenas de miles. Por encima habría que pensar en una proyección (fuera de este corte, C-2).
- **R-R6B-3: una lectura más del Event Store por consulta de seguimiento (G-2).** Es un stream pequeño, por índice único y sin coste medible.
- **R-R6B-4: los fondos 1.0 sin organización** quedan fuera de G-6 sin aviso, como en `GET /funds` (§0.3).
- **R-R6B-5: `occurredAt` en texto con decimales variables** (§2.2). Se filtra en memoria y nunca se compara como texto.

### 7.2 Preguntas para la aprobación

| # | Pregunta | Recomendación |
|---|---|---|
| **P-R6B-1** | ¿Hay alguna base **persistente** con eventos anteriores a `0579f41` (2026-09-16)? Si la hay, sus fondos 1.0 no tienen organización en ningún evento (§0.3). ¿Quedan fuera de G-6, como hoy de `GET /funds`, o se decide otra regla (por ejemplo, una correspondencia fondo → organización fuera del Event Store)? | **Quedan fuera** y se registra. Asignarles organización exigiría un dato que el Event Store no tiene, y decidirlo es una regla nueva (3.5) |
| **P-R6B-2** | Test 6c de la ficha: un `FUNDS_CLEARED` 1.0 dentro de un stream con génesis 2.0 no lo produce ningún código. ¿Se prueba escribiéndolo a mano en el Event Store? | **Sí**: la regla "pertenencia por la génesis" es la que protege ese caso, y el test lo fija aunque hoy no ocurra |
| **P-R6B-3** | "Un ítem por mes y moneda, incluidos los meses con 0": ¿de qué monedas salen los meses en 0? (a) las de los fondos de la organización (génesis); (b) solo las que tienen alguna liquidación en la ventana; (c) siempre `COP` | **(a)**: una organización con fondos en COP ve sus meses en 0 aunque no haya cobrado en la ventana. Sin fondos, `items: []` |
| **P-R6B-4** | Puerto de G-6: ¿un método nuevo en `FundOperationalReadPort` (que usa `api`) o un puerto nuevo implementado por la misma `FundOperationalQueryService`? | **Puerto nuevo**, implementado por `FundOperationalQueryService` para reutilizar la política DD-31 sin duplicarla. No cambia ningún puerto existente (criterio de P-R6A-2) |
| **P-R6B-5** | Nombres de las clases nuevas (§2.3) | Se fijan al aprobar el plan, antes de la fase roja. Propuesta: `FundClearingDatesReadPort` y `FundClearingDatesQuery` (G-2); `FundMonthlyClearingReadPort` (G-6); `FundClearingEventsPort` y `MongoFundClearingEventsAdapter` (salida) |
| **P-R6B-6** | Límite de fondos de G-6. `GET /funds` corta en 200 (`MAX_FUNDS`); con ese tope, el recaudado por mes de una organización con más de 200 fondos saldría **incompleto sin avisar** | **Sin tope de 200 para G-6**, con un límite alto registrado (por ejemplo, 10 000 fondos) y `truncated: true` si se alcanza, como en C-5. Si se prefiere no añadir `truncated`, el límite queda solo en el plan |
| **P-R6B-7** | ¿Índice parcial para el directorio de fondos (R-R6B-1)? Por ejemplo, `{'payload.organizationRef': 1, recordedAt: 1, streamId: 1}` con `partialFilterExpression: {sequence: 1, aggregateType: 'Fund'}` | **Sí, en este PR**, con la medición antes y después en el PR. Si se prefiere separarlo de G-6, queda como deuda registrada |

## 8. Archivos previstos

- **`core`:**
  - `application/port/out/` (los dos puertos de lectura y el de salida);
  - `application/query/` (la consulta de G-2 y `FundOperationalQueryService` para G-6);
  - `infrastructure/persistence/mongo/` (el adaptador del paso 2 y, según P-R6B-7, el índice);
  - sus tests.
- **`api`:**
  - `application/dto/PublicFinancialSnapshotDTO.java`, `application/mapper/PublicDonationMapper.java`, `fund/FundController.java`;
  - sus tests;
  - los dobles del puerto de G-2 en los tests de `api` que levantan el mapeador, como en el Bloque A.
- **`app`:** `PublicRoutesInventoryIntegrationTest` (`PROTECTED`) y las pruebas HTTP.
- **Documentos:** `referencia-api-v1.md`, `api-contract-matrix.md`, `estado-fase6.md` y `Documentos/evidencia-ci/`.
