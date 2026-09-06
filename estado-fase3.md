# Estado de Fase 3 — Exposición REST de Lectura

**Última actualización:** sesión de diseño del 3-5 de septiembre de 2026
**Estado general:** diseño y contratos congelados para el primer endpoint de lectura. Cero líneas de código de implementación escritas todavía.

---

## 1. Qué es Fase 3, exactamente

Exposición REST de **solo lectura** del sistema construido en Fase 2 (cerrada, con matices — ver sección 5). Explícitamente excluye, en esta primera iteración: comandos de escritura, autenticación por cuenta, offline-first, ML de detección de anomalías, verifiable credentials.

---

## 2. Decisiones congeladas (ADRs)

### ADR-020 — Ubicación de la API
Módulo Maven nuevo, `api`, separado de `app`. `app` permanece como bootstrap puro (sin lógica, sin controladores). Dirección de dependencia: `app` depende de `api`, `api` depende de `contracts`/`core` (solo puertos de lectura). Mismo patrón unidireccional que ya usan `crypto` y `ai`.

### ADR-021-A — Mecanismo primario de acceso
**Tracking code** (bearer credential), sin cuenta autenticada, sin exponer `donorRef`. Se descartó cuenta autenticada como mecanismo primario para este flujo (fricción excesiva para donantes ocasionales en contexto humanitario).

> **Nota de reversión parcial:** en una discusión posterior sobre requisitos del equipo, surgió que el registro de cuenta SÍ será obligatorio para el actor "Fundación" (opera campañas). Esto no invalida ADR-021-A para el flujo de consulta pública de donación — sigue siendo tracking code, sin cuenta — pero confirma que en paralelo existirá un módulo de identidad para otro propósito. Ver sección 6.

### ADR-021-B — Naturaleza del tracking code
**Stateless, derivado por HMAC-SHA-256 completo** (sin truncar) a partir de `fundId`, con dominio/versión propia (`"tracking:v1:" + fundId`, análogo al de `assetRef`), **expiración embebida** en el propio token firmado, y **lista de revocación dispersa** (`revoked_tracking_codes`, solo `tokenHash` + `revokedAt` — no un registro de cada token emitido). Rechazadas explícitamente: persistencia completa de cada token (Tarea propuesta originalmente, descartada por sobre-ingeniería) y hash truncado (riesgo de colisión, rechazado con cálculo explícito de la paradoja del cumpleaños).

### ADR-021-C — Generación
Derivable bajo demanda a partir de `fundId`, sin evento de dominio nuevo, sin tocar `Fund` ni el Event Store. Responsabilidad del módulo `api`, no de `core`.

### ADR-021-D — Perímetro de exposición (campo por campo, verificado contra código real)

**Excluidos siempre:** `sourceTransactionId`, `allocations[].allocationId`, `allocations[].requirementId`, `logistics[].sourceAllocationId`, `logistics[].parentAssetRef`, `logistics[].rootAssetRef`, `logistics[].statusBeforeSplit`, `AuditMetadata` completo, `sequence` de transiciones de historial, `donorRef` (nunca estuvo en la proyección).

**Enmascarados:** `logistics[].currentCustodian` → `PublicCustodianCategory` (enum cerrado); `allocations[].vendorId` → `PublicVendorCategory` (enum cerrado, tipo distinto); `logistics[].currentLocation` → `locationZone` (tabla de referencia determinista a ciudad, nunca heurística de texto libre; `null` si no está en la tabla — un dato ausente es preferible a uno incorrecto).

**Exponibles:** `lifecycleStatus`, `assetType`, `unitOfMeasure`, `quantity`, `financialSnapshot` completo salvo `sourceTransactionId` (incluye `originalAmount`, corregido en Tarea 10.1), monto confirmado (**derivado en lectura**, nunca persistido, para no repetir el patrón de bug de Tarea 10.1).

**Transformado, no excluido:** `assetId` → `assetRef` (HMAC-SHA-256 completo, secreto dedicado y distinto del secreto del tracking code, dominio `"asset-ref:v1:"`, Base64URL, sin truncar).

**Traducción de presentación:** `status` de la proyección (`ACTIVE`/`PAUSED`, estado interno del proyector) → `PublicDonationStatus` (`ACTIVA`/`EN_PROCESO`). Nunca se expone el valor interno crudo ni el motivo técnico de la cuarentena.

**Retirado del diseño original:** `PublicCustodianCategory.BENEFICIARY` — se verificó con código que `currentCustodian` nunca puede contener `beneficiaryRef` (ADR-014 se respeta también en la proyección), así que ese valor de enum no tiene fuente de datos legítima y se eliminó.

---

## 3. Endpoints acordados

- `GET /donations/{id}` (o ruta equivalente vía tracking code — **convención de ruta exacta sin decidir**, ver sección 4) — resumen: `PublicDonationTrackingDTO`.
- `GET /donations/{id}/assets/{assetId}/history` — historial detallado por activo, bajo demanda. **DTO de este endpoint sin diseñar todavía**, aunque el perímetro de exposición de `AssetHistoryProjectionDocument` ya está clasificado (ver sección 2, ADR-021-D aplica igual a `AssetTransition`).

Separación motivada por dos razones: tamaño de payload (una donación con miles de activos no debe forzar un JSON gigante) y fidelidad arquitectónica (`DonationProjection` y `AssetHistoryProjection` ya son colecciones separadas con responsabilidades distintas desde ADR-015; la API refleja esa frontera, no la aplana).

---

## 4. Contrato de datos — `PublicDonationTrackingDTO` (congelado)

```java
public record PublicDonationTrackingDTO(
    PublicFinancialSnapshotDTO financialSnapshot,
    List<PublicLogisticsItemDTO> logistics,
    PublicDonationStatus status,
    String narrativeSummary // placeholder — orquestación con `ai` sin resolver
) {}

public record PublicFinancialSnapshotDTO(
    String currency,                 // constante "COP" — ver deuda técnica, sección 5
    long originalAmount,
    long clearedAmount,
    long pendingAllocationAmount,
    long confirmedAllocationAmount,  // derivado en lectura
    long refundedAmount
) {}

public enum PublicDonationStatus { ACTIVA, EN_PROCESO }

public record PublicLogisticsItemDTO(
    String assetRef,
    String lifecycleStatus,
    String assetType,
    String unitOfMeasure,
    long quantity,
    String locationZone,
    PublicCustodianCategory custodianCategory
) {}

public enum PublicCustodianCategory {
    LOGISTICS_PARTNER, REGIONAL_WAREHOUSE, LAST_MILE_CARRIER, LOCAL_ALLY
}

public enum PublicVendorCategory {
    MANUFACTURER, WHOLESALE_DISTRIBUTOR, LOCAL_MERCHANT, INTERNATIONAL_SUPPLIER
}
```

**Pregunta abierta sin resolver dentro del propio contrato:** `PublicVendorCategory` está definido pero no incluido en ningún record — `vendorId` vive en `allocations[]`, no en `logistics[]`, y `PublicDonationTrackingDTO` no incluye actualmente un resumen de `allocations`. Decidir si se agrega un array de asignaciones enmascaradas o si el vendor se omite de esta versión.

`campaignRef` **excluido de esta versión** del DTO — no existe en el dominio real (ver sección 5).

---

## 5. Deuda técnica descubierta *durante* el diseño de Fase 3 (no era parte del alcance original)

Diseñar el DTO obligó a verificar el esquema real de las proyecciones, lo que sacó a la luz tres defectos de Fase 2 que estaban marcados como cerrados:

| Hallazgo | Severidad | Estado |
|---|---|---|
| `DonationProjectionDocument.originalAmount` en 0 para donaciones sin promesa (`FUNDS_CLEARED` directo) | Alta | ✅ Corregido — Tarea 10.1, 14 criterios verificados con Testcontainers |
| `AllocationProjection` sin campo de estado, monto confirmado calculado con resta implícita frágil | Alta | ✅ Corregido — Tarea 10.1 |
| `DonationProjectionHandler` no manejaba `ASSET_CUSTODY_TRANSFERRED`, `ASSET_DEPLETED`, `ASSET_SPLIT_COMPENSATED`, `ASSET_DELIVERED` — la vista pública **nunca mostraba "entregado"** | Crítica | ✅ Corregido — Tarea 10.2, 9 criterios adicionales, incluyendo reconstrucción retroactiva |
| `Fund` no tiene `currency` ni `campaignRef` en ningún punto del flujo de escritura (comando, Aggregate, evento), pese a que el documento maestro los documenta como parte del estado del Aggregate | Media (bloquea funcionalidad, no corrompe datos) | 🟡 Registrado como deuda técnica en sección 9.1 del maestro. Pendiente de decisión: ¿se resuelve ahora (evolución de esquema de eventos, con estrategia de compatibilidad retroactiva) o se pospone hasta que exista requisito real de multi-moneda/campañas? |
| **`OutboxSagaCoordinator` sin ninguna implementación concreta de `SagaPolicy` en `main`** — ninguna asignación se confirma automáticamente, ningún split crea a su hijo, ninguna compensación se dispara tras fallo | 🔴 **Crítica — la más grave encontrada en toda la sesión** | 🔴 **Sin corregir. Auditoría de verificación adicional solicitada** (ver `auditoria-fase2-completa.md`) antes de dimensionar la corrección como posible Tarea 7.1 |

**Consecuencia directa para Fase 3:** el hallazgo de `SagaPolicy`, si se confirma en su totalidad, significa que los datos que el propio DTO de Fase 3 expone (`allocations`, `logistics`) pueden estar permanentemente incompletos en producción — no por un bug de proyección como 10.1/10.2, sino porque las transiciones que los completarían nunca se disparan. Fase 3 puede terminar exponiendo, con total fidelidad, un estado que nunca avanza más allá de "pendiente".

---

## 6. Hilo aparte, sin resolver — módulo de identidad

No es parte de Fase 3 tal como está definida, pero surgió en paralelo y quedó anotado para retomar en otra conversación:

- Cuenta obligatoria para el rol "Fundación" (opera campañas), opcional para donante individual.
- Separación propuesta (hipótesis, no decidida por el equipo completo): rol organizacional (Organización/Individuo) vs. rol transaccional (Donante/Operador).
- Sin resolver: ¿puede una Fundación donar a otra? ¿Debe el QR físico incluir identidad de Fundación+usuario, o solo una referencia opaca de resolución (recomendación técnica: la segunda)?
- Sin resolver: modelo de "donante anónimo" — distinto del anonimato por defecto que ya tiene el DTO público (que nunca expone `donorRef` a nadie); esto sería un flag interno para que ni la propia Fundación vea al donante.

---

## 7. Lo que queda explícitamente a medias dentro del propio alcance de Fase 3

Nada de esto tiene decisión de diseño pendiente — son implementación directa sobre lo ya congelado, pero **ninguno tiene código escrito todavía**:

1. DTO del segundo endpoint (`assets/{assetId}/history`).
2. Implementación del mapper real (`DonationProjectionDocument` → `PublicDonationTrackingDTO`): cálculo HMAC, consulta a la tabla de referencia de ubicaciones (que tampoco existe como estructura de datos todavía), lógica de mapeo a los enums.
3. Implementación real del mecanismo de tracking code: generación, validación, la colección de revocación dispersa, el filtro de autorización (Spring Security u otro).
4. Los controladores REST en sí.
5. **Orquestación con el módulo `ai` para `narrativeSummary`** — pregunta planteada dos veces en la sesión, aplazada las dos veces por algo más urgente. Sigue sin respuesta ni siquiera de diseño.
6. Convención final de rutas HTTP — vimos dos formas distintas flotando (`/donations/{id}` vs. `/api/v1/donations/tracking/{trackingCode}`) sin fijar una.
7. Gestión del secreto HMAC (dónde vive, cómo se rota) — decisión de infraestructura, no de producto, pendiente igual.
8. Estrategia de testing de la capa `api` (`@WebMvcTest`, integración) — no discutida.

---

## 8. Pendiente de decisión del equipo completo (no solo técnica)

- Rutas HTTP y convención final.
- Alcance real: ¿qué funcionalidades exactas entran en esta iteración del producto? Determina si vale la pena implementar ya lo diseñado o esperar más definición.
- Prioridad relativa: dado el hallazgo de `SagaPolicy`, ¿el equipo prefiere cerrar esa corrección antes de escribir cualquier controlador de Fase 3, o avanzar en paralelo?

---

## 9. Recomendación de secuencia para retomar

1. Correr la auditoría completa de Fase 2 (`auditoria-fase2-completa.md`) para dimensionar el hallazgo de `SagaPolicy` y descartar más discrepancias similares.
2. Con eso resuelto (o al menos acotado), decidir en equipo si `currency`/`campaignRef` se arregla ahora o se pospone.
3. Recién entonces, implementación de Fase 3 en el orden: mapper → tracking code → controlador de lectura → segundo endpoint → orquestación con `ai`.

No se recomienda escribir controladores de Fase 3 antes del paso 1 — si el hallazgo de sagas resulta tan grave como parece, cualquier endpoint construido ahora expondría datos que el propio backend nunca termina de procesar.
