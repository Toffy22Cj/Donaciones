# Plan P1.1 — Camino A por HTTP: fondos de la organización y asignaciones

**Estado:** **EN EJECUCIÓN** bajo la segunda autorización de trabajo autónomo de Carlos (2026-10-07), P1.1. Plan y decisiones `[DECISIÓN DELEGADA — pendiente de ratificar por Carlos]` (DD-29 a DD-32, `decisiones-delegadas-2026-10.md` §3).
**Origen:** hallazgos H-B6C-1 (la asignación del Camino A no tiene ruta) y H-B6D-1 (el empleado no tiene de dónde sacar el `fundId`), registrados en B6-c y B6-d.
**Objetivo:** que el criterio 7 del golden path ("`PhysicalAsset` creado y asociado a la donación") se cumpla por HTTP con el Camino A, sin leer la base de datos ni llamar servicios desde el test.

---

## 1. Hechos del código (`develop` en `74b5787`)

- `FundCommandService.requestAllocation(commandId, fundId, allocationId, amount, actor)` y `confirmAllocation(...)` existen, con `REQUEST_ALLOCATION` y `CONFIRM_ALLOCATION` (`ADMINISTRATOR`, P7). El `allocationId` lo aporta el llamador.
- `requestAllocation` hace un no-op silencioso si el `commandId` ya existe; no distingue un reenvío de un `Command-Id` de otro comando (DD-11 lo resolvió para los activos).
- `confirmAllocation` es idempotente en el dominio (sin evento si ya está confirmada). En el Camino A la confirma la saga del registro (`AssetRegisteredSagaPolicy`, ADR-033).
- Las excepciones del `Fund` (`DuplicateAllocationException`, `InsufficientAvailableFundsException`, `InvalidFundTransitionException`) son `DomainInvariantViolationException` → 409.
- `DonationProjectionDocument` no guarda la organización, así que no permite listar los fondos de una organización. La génesis (`FUNDS_CLEARED`/`FUND_REGISTERED` v2) sí la lleva.

## 2. Cambios

### 2.1 `core`
- **`requestAllocation`** guarda en su reclamo el resultado `REQUEST_ALLOCATION:fundId:allocationId`; un reenvío con otro resultado → `CommandIdReusedException` (409), como DD-11.
- **`allocationId` determinista** para HTTP: `AllocationIds.of(fundId, commandId)` = UUIDv5 en un espacio de nombres propio; un reenvío devuelve el mismo id (DD-29).
- **Fondo inexistente** → `FundNotFoundException`, con el mismo 403 que "otra organización" (como DD-12) (DD-30).
- **Lectura `FundOperationalReadPort`**: los fondos de una organización, leídos del event store por su génesis (como B6-c DD-13), con `fundId`, `campaignRef`, `currency`, `clearedAmount`, disponible y asignaciones `{allocationId, amount, status}`. Sin `donorRef` ni datos del donante (DD-31). Exige `ADMINISTRATOR` o `EMPLOYEE` de esa organización.

### 2.2 `api` (`FundController`)

| Ruta | Rol | Respuesta |
|---|---|---|
| `GET /api/v1/organizations/{organizationId}/funds` | `ADMINISTRATOR` o `EMPLOYEE` de la organización | `200 {items: [...]}` (una página, como DD-21) |
| `POST /api/v1/funds/{fundId}/allocations` | `ADMINISTRATOR` (P7), `Command-Id` | `201 {allocationId, status: "REQUESTED"}`; cuerpo `{amount}` (texto de dígitos) |
| `POST /api/v1/funds/{fundId}/allocations/{allocationId}/confirm` | `ADMINISTRATOR` (P7), `Command-Id` | `200 {allocationId, status: "CONFIRMED"}` |

- Organización del path distinta de la del llamador, o inexistente → el mismo 403 (DD-01).
- La confirmación manual se expone porque Carlos la pidió; en el recorrido normal la hace la saga al registrar el activo, y repetirla no tiene efecto (DD-32).

### 2.3 `GoldenPathHttpIntegrationTest`
El criterio 7 usa el Camino A **solo por HTTP**: el administrador lista los fondos, pide la asignación y el empleado registra el activo. El Camino B queda como escenario adicional.

## 3. Tests
1. Asignación: 201 con id determinista; reenvío idéntico; `Command-Id` de otro comando → 409; importe mayor que el disponible → 409; sin rol `ADMINISTRATOR` → 403; fondo de otra organización e inexistente → el mismo 403.
2. Confirmación manual y confirmación por la saga: el estado queda `CONFIRMED` una sola vez.
3. Listado: solo los fondos de la organización, campos exactos, sin `donorRef`; `EMPLOYEE` puede leerlo; otra organización → 403.
4. Recorrido completo por HTTP con el Camino A.

**Mutaciones:** id aleatorio; aceptar un `Command-Id` reutilizado; listar fondos de otra organización; incluir `donorRef`; 404 para el fondo inexistente; quitar el rol en la asignación.
