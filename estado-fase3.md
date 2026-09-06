# Estado de Fase 3 — Exposición REST de Lectura

**Última actualización:** post-veredicto de arbitraje sobre ADR-024 (separación de narrativa como sub-recurso), 6 de septiembre de 2026
**Estado general:** diseño y contratos congelados para los tres endpoints de lectura. Cero líneas de código de `api` escritas todavía.

---

## 1. Qué es Fase 3, exactamente

Exposición REST de **solo lectura** del sistema construido en Fase 2 (cerrada, deuda técnica resuelta). Explícitamente excluye, en esta primera iteración: comandos de escritura, autenticación por cuenta, offline-first, ML de detección de anomalías, verifiable credentials.

---

## 2. Decisiones congeladas (ADRs)

### ADR-020 — Ubicación de la API
Módulo Maven nuevo, `api`, separado de `app`. Dependencia unidireccional: `app` → `api` → `core` (solo puertos de aplicación, nunca infraestructura interna).

### ADR-021-A/B/C/D — Tracking code y perímetro de exposición
Sin cambios respecto a la versión anterior de este documento. Ver sección 4 para el contrato de datos actualizado.

### ADR-022 — Resolución manual de lotes atascados (JMX)
Sin cambios. Documentado en `documento-maestro-proyecto.md` sección 5.

### ADR-024 — Exposición asíncrona de narrativas (Approved with amendment)

**Decisión interna (sin cambios respecto a la propuesta original):** `NarrativeReadPort`, contrato neutral en `contracts`, estados `AVAILABLE`/`PENDING`, origen `LLM_GENERATED`/`FALLBACK_TEMPLATE`, generación lazy asíncrona sin `.join()` bloqueante en el camino HTTP. El módulo `ai` mantiene `DonorReportDTO`/`CacheKey`/`NarrativeCacheCoordinator` completamente internos — nunca cruzan hacia `api`.

**Enmienda (resuelta en sesión de arbitraje):** la narrativa se expone como **endpoint HTTP independiente**, nunca embebida en el recurso principal de tracking. Ver sección 3 y 5.

---

## 3. Endpoints acordados (tres, no dos)

1. `GET /api/v1/donations/tracking/{trackingCode}` — resumen: `PublicDonationTrackingDTO`. Siempre `200` si el token es válido y el fondo existe. **Ya no contiene ningún campo de narrativa.**
2. `GET /api/v1/donations/tracking/{trackingCode}/assets/{assetRef}/history` — historial detallado por activo, bajo demanda.
3. `GET /api/v1/donations/tracking/{trackingCode}/narrative` — **nuevo, resultado del arbitraje sobre ADR-024**. `PENDING` → `202`; `AVAILABLE` → `200` con `NarrativeResponseDTO`.

Los tres comparten el mismo mecanismo de autorización (tracking code vía `OncePerRequestFilter`, patrón de ruta `/tracking/**`) sin mecanismo de identidad adicional.

---

## 4. Contrato de datos

```java
public record PublicDonationTrackingDTO(
    PublicFinancialSnapshotDTO financialSnapshot,
    String campaignRef,
    List<PublicLogisticsItemDTO> logistics,
    PublicDonationStatus status
    // narrativeSummary RETIRADO — ver ADR-024 enmendado, ahora es
    // endpoint independiente, nunca campo embebido aquí.
) {}

public record PublicFinancialSnapshotDTO(
    String currency,                  // nullable para eventos legacy v1
    long originalAmount,
    long clearedAmount,
    long pendingAllocationAmount,
    long confirmedAllocationAmount,   // derivado en lectura, nunca persistido
    long refundedAmount
) {}

public enum PublicDonationStatus { ACTIVA, EN_PROCESO }

public record PublicLogisticsItemDTO(
    String assetRef,
    String lifecycleStatus,
    String assetType,
    String unitOfMeasure,
    BigDecimal quantity,               // serializado como String en JSON
                                        // vía @JsonFormat(shape = STRING)
    String locationZone,
    PublicCustodianCategory custodianCategory
) {}

public enum PublicCustodianCategory {
    LOGISTICS_PARTNER, REGIONAL_WAREHOUSE, LAST_MILE_CARRIER, LOCAL_ALLY
}

public enum PublicVendorCategory {
    MANUFACTURER, WHOLESALE_DISTRIBUTOR, LOCAL_MERCHANT, INTERNATIONAL_SUPPLIER
}
// Sigue sin incluirse en ningún record — decisión ya congelada, sin cambios.

// NUEVO — contrato del endpoint de narrativa (ADR-024 enmendado)
public record NarrativeResponseDTO(
    NarrativeStatus status,           // AVAILABLE | PENDING
    String content,                   // null si status == PENDING
    NarrativeSource source            // null si status == PENDING
) {}

public enum NarrativeStatus { AVAILABLE, PENDING }
public enum NarrativeSource { LLM_GENERATED, FALLBACK_TEMPLATE }
```

**Manejo de `null` en campos legacy:** `currency` y `campaignRef` son `null` para donaciones cuya génesis ocurrió antes de Tarea 10.3. El mapper omite el campo del JSON, nunca muestra un placeholder de texto.

**Pregunta abierta sin resolver, sin cambios:** `PublicVendorCategory` definido pero no incluido en ningún record — decisión pendiente de si se expone un resumen de `allocations[]`.

---

## 5. Semántica HTTP consolidada (los tres endpoints)

| Endpoint | Caso | Código |
|---|---|---|
| `/tracking/{code}` | Token inválido/expirado/revocado | 401 (uniforme, sin distinguir causa) |
| `/tracking/{code}` | Token válido, fondo existe | 200 |
| `/tracking/{code}/assets/{ref}/history` | `assetRef` no pertenece al fondo autorizado | 401 (mismo body que token inválido) |
| `/tracking/{code}/assets/{ref}/history` | Válido | 200 |
| `/tracking/{code}/narrative` | Narrativa aún generándose | 202, `NarrativeResponseDTO{status: PENDING}` |
| `/tracking/{code}/narrative` | Narrativa lista (LLM o fallback) | 200, `NarrativeResponseDTO` completo |

---

## 6. Deuda técnica de Fase 2 que bloqueaba Fase 3 — RESUELTA

Sin cambios respecto a la versión anterior de este documento — los 7 hallazgos (originalAmount, AllocationProjection, eventos faltantes de PhysicalAsset, Fund incompleto, SagaPolicy inerte, quantity como long, ProjectionRetryDocument builder) siguen resueltos con evidencia real. Ver `documento-maestro-proyecto.md` sección 9 para el detalle completo.

---

## 7. Hilo aparte, sin resolver — módulo de identidad

Sin cambios. Cuenta obligatoria para "Fundación", opcional para donante individual; rol organizacional vs. transaccional; donación entre Fundaciones; QR con identidad vs. referencia opaca — todo pendiente, en otra conversación.

---

## 8. Lo que queda a medias dentro del propio alcance de Fase 3

1. Implementación del mapper (`DonationReadModel` → `PublicDonationTrackingDTO`) — sin cambios de alcance, salvo que ya no incluye narrativa.
2. Mecanismo de tracking code (generación externa a Fase 3, validación interna).
3. Controladores de los TRES endpoints (antes eran dos).
4. Puerto `NarrativeReadPort` en `contracts` + su implementación en `ai` (nuevo, resultado de ADR-024).
5. Tabla de referencia de ubicaciones.
6. Gestión de dos secretos HMAC (tracking code, assetRef) — el secreto de `ai` (si lo tiene, para el LLM) es independiente y no se toca aquí.
7. Estrategia de testing de `api` (`@WebMvcTest` + integración Testcontainers), ahora cubriendo también el endpoint asíncrono de narrativa (verificar transición `PENDING`→`AVAILABLE` en tests de integración con tiempo de espera controlado).

---

## 9. Recomendación de secuencia para retomar

Con la deuda técnica de Fase 2 resuelta y ADR-024 enmendado y aprobado, el orden de implementación (ver `plan-ejecucion-agentes-fase3.md` para el backlog completo con prompts autocontenidos):

1. `DonationReadPort` + `DonationReadModel` (core).
2. Infraestructura de secretos, tabla de ubicaciones, `asset_index` de autorización (paralelo).
3. Servicio de tracking code + filtro de autorización.
4. Servicio de `assetRef`.
5. Mapper del recurso principal.
6. Controlador del primer endpoint.
7. Segundo endpoint (historial).
8. **Tercer endpoint (narrativa)** — ahora con diseño completo, ya no pendiente de Modo de Arquitectura adicional. Ver Tarea 3.11 actualizada en `plan-ejecucion-agentes-fase3.md`.

**Nota de proceso:** esta ronda de arbitraje (Antigravity + Gemini + revisión final) es un buen ejemplo de por qué no conviene aprobar un ADR por consenso de varios agentes sin verificar primero contra el contrato de datos real ya existente — la ambigüedad entre "endpoint separado" y "campo embebido" no era visible hasta que se cruzó explícitamente contra `PublicDonationTrackingDTO`.
