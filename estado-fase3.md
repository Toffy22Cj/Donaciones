# Estado de Fase 3 — Exposición REST de Lectura

**Última actualización:** cierre completo de Fase 3, 7 de septiembre de 2026
**Estado general:** ✅ **COMPLETA**. Los tres endpoints de lectura están implementados, probados end-to-end y mergeados a `develop`. Cero deuda técnica bloqueante pendiente para el alcance definido.

---

## 1. Qué es Fase 3, exactamente

Exposición REST de **solo lectura** del sistema construido en Fase 2. Explícitamente excluyó, en esta iteración: comandos de escritura, autenticación por cuenta, offline-first, ML de detección de anomalías, verifiable credentials — todos quedan para fases futuras.

---

## 2. Decisiones congeladas (ADRs) — todas implementadas

### ADR-020 — Ubicación de la API
Módulo Maven `api`, separado de `app`. Dependencia unidireccional `app → api → core`. Verificado mecánicamente con una regla de ArchUnit propia de `api` (Tarea 3.0), demostrada activa (falla ante una violación deliberada, pasa limpia sin ella) — no una regla "vacía por construcción".

### ADR-021-A/B/C/D — Tracking code y perímetro de exposición
Implementado íntegro: HMAC-SHA-256 con dominio versionado `"tracking:v1:"`, comparación timing-safe (`MessageDigest.isEqual()`), expiración embebida, lista de revocación dispersa con TTL de 365 días. Perímetro de exposición (ADR-021-D) aplicado sin excepciones en los tres DTOs públicos.

### ADR-022 — Resolución manual de lotes atascados (JMX)
Sin cambios respecto a Fase 2 (documentado en `documento-maestro-proyecto.md`).

### ADR-023 — Sin Spring Security
Confirmado en la implementación: `OncePerRequestFilter` propio (`TrackingCodeAuthFilter`), sin `spring-boot-starter-security` en ninguna dependencia del proyecto.

### ADR-024 — Exposición asíncrona de narrativas (Approved with amendment) — implementado
`NarrativeReadPort` en `contracts`, con `getOrTriggerGeneration(fundId)` — nombre elegido para que el efecto colateral (disparar generación) sea visible en la firma. Implementado en `ai` (`AiNarrativeReadAdapter`) reutilizando el single-flight ya existente de Tarea 12, sin ningún `.join()` bloqueante en el camino HTTP. Expuesto como **endpoint HTTP independiente** — nunca embebido en `PublicDonationTrackingDTO` (decisión final tras arbitraje entre dos propuestas de agentes).

---

## 3. Endpoints — los tres implementados y verificados end-to-end

1. **`GET /api/v1/donations/tracking`** — resumen: `PublicDonationTrackingDTO`. Token vía header `Authorization: Bearer`, sin el código en el path. `200` si válido y el fondo existe; `404` si el fondo aún no tiene proyección (consistencia eventual, token ya garantizado válido); `401` uniforme para cualquier fallo de autorización.

2. **`GET /api/v1/donations/tracking/assets/{assetRef}/history`** — historial detallado por activo. `200` si el `assetRef` resuelve y pertenece al fondo autorizado; **`401` uniforme** (no 404) tanto para "sin proyección" como para "assetRef no pertenece" — decisión deliberada para no dar pistas de enumeración/IDOR, documentada explícitamente en el código junto a la diferencia con el caso de Tarea 3.9.

3. **`GET /api/v1/donations/tracking/narrative`** — narrativa generada por IA. `202 Accepted` con `NarrativeResponseDTO{status: PENDING}` mientras se genera (sin bloquear el hilo HTTP); `200 OK` con contenido y `source` (`LLM_GENERATED`/`FALLBACK_TEMPLATE`) cuando está lista; `404` si el fondo no tiene proyección (mismo criterio que Tarea 3.9, ya que aquí no hay superficie de enumeración vía un dato adicional del cliente).

Los tres comparten el mismo filtro de autorización (`TrackingCodeAuthFilter`, patrón de ruta `/api/v1/donations/tracking/**`), verificado explícitamente para cubrir la ruta de narrativa sin configuración adicional.

---

## 4. Contrato de datos — final, implementado tal cual

```java
public record PublicDonationTrackingDTO(
    PublicFinancialSnapshotDTO financialSnapshot,
    String campaignRef,               // nullable, legacy v1
    List<PublicLogisticsItemDTO> logistics,
    PublicDonationStatus status
    // sin narrativeSummary — vive en su propio endpoint
) {}

public record PublicFinancialSnapshotDTO(
    String currency,                  // nullable, legacy v1
    long originalAmount,
    long clearedAmount,
    long pendingAllocationAmount,
    long confirmedAllocationAmount,   // derivado en el adaptador, nunca persistido
    long refundedAmount
) {}

public enum PublicDonationStatus { ACTIVA, EN_PROCESO }

public record PublicLogisticsItemDTO(
    String assetRef,                  // HMAC-SHA-256 completo, sin truncar
    String lifecycleStatus,
    String assetType,
    String unitOfMeasure,
    BigDecimal quantity,              // serializado como String (@JsonFormat STRING)
    String locationZone,              // tabla de referencia exacta, null si desconocida
    PublicCustodianCategory custodianCategory
) {}

public enum PublicCustodianCategory {
    LOGISTICS_PARTNER, REGIONAL_WAREHOUSE, LAST_MILE_CARRIER,
    LOCAL_ALLY, UNCATEGORIZED   // mapeo por lifecycleStatus, no por string de custodio
}

// AssetHistoryPublicDTO / PublicTransitionDTO — mismo perímetro que arriba,
// sequence excluido, location→locationZone, custodian→categoría.

public record NarrativeResponseDTO(
    NarrativeStatus status,
    String content,                   // null si PENDING
    NarrativeSource source             // null si PENDING
) {}
public enum NarrativeStatus { AVAILABLE, PENDING }
public enum NarrativeSource { LLM_GENERATED, FALLBACK_TEMPLATE }
```

**Cambios finales respecto al diseño original que vale la pena registrar:**
- `PublicCustodianCategory` ganó un quinto valor, `UNCATEGORIZED`, no contemplado en el diseño inicial — necesario porque los estados `REGISTERED` y `DEPLETED` del ciclo de vida de un activo no tienen una categoría de custodio defendible con evidencia (no forzar `LOCAL_ALLY` por descarte), y `null` silencioso habría sido una regresión sobre la misma disciplina aplicada al resto del perímetro.
- El mapeo de custodio se resuelve por `lifecycleStatus` (`DISPATCHED`→`LOGISTICS_PARTNER`, `RECEIVED`→`REGIONAL_WAREHOUSE`, `DELIVERED`→`LAST_MILE_CARRIER`, `REGISTERED`/`DEPLETED`→`UNCATEGORIZED`), no por coincidencia de texto contra el valor crudo de `currentCustodian` — un diseño inicial que habría fallado sistemáticamente contra datos reales fue corregido antes de implementarse.

---

## 5. Semántica HTTP consolidada (final)

| Endpoint | Caso | Código |
|---|---|---|
| `/tracking` | Token inválido/expirado/revocado | 401 uniforme |
| `/tracking` | Token válido, fondo sin proyección | 404 |
| `/tracking` | Token válido, fondo existe | 200 |
| `/tracking/assets/{ref}/history` | `assetRef` inválido o de otro fondo | 401 uniforme |
| `/tracking/assets/{ref}/history` | Válido | 200 |
| `/tracking/narrative` | Token inválido | 401 |
| `/tracking/narrative` | Fondo sin proyección | 404 |
| `/tracking/narrative` | Narrativa generándose | 202 |
| `/tracking/narrative` | Narrativa lista | 200 |

---

## 6. Deuda técnica de Fase 2 que bloqueaba Fase 3 — RESUELTA (sin cambios respecto a versión anterior)

Los 7 hallazgos de la auditoría de Fase 2 (originalAmount, AllocationProjection, eventos faltantes de PhysicalAsset, Fund incompleto, SagaPolicy inerte, quantity como BigDecimal, ProjectionRetryDocument builder) siguen resueltos con evidencia real. Ver `documento-maestro-proyecto.md` sección 9 para el detalle completo, incluyendo Tarea 7.1 y 10.1–10.5.

---

## 7. Hilo aparte, sin resolver — módulo de identidad

Sigue pendiente, sin tocar durante Fase 3: cuenta obligatoria para "Fundación", opcional para donante individual; rol organizacional vs. transaccional; donación entre Fundaciones; QR con identidad vs. referencia opaca. Candidato natural para Fase 4.

---

## 8. Deuda técnica y decisiones abiertas al cierre de Fase 3

1. **`PublicVendorCategory`** definido pero nunca incluido en ningún DTO — sigue sin resolverse si conviene exponer un resumen de `allocations[]` enmascarado.
2. **Población real de `location_reference`** — la colección quedó vacía/con datos de prueba; poblarla con ubicaciones operativas reales es trabajo posterior, no de diseño.
3. **Gestión de secretos en producción** — los dos secretos HMAC (tracking code, assetRef) se definieron como variables de entorno vía `@ConfigurationProperties`, sin vault dedicado (decisión consciente de no sobre-ingeniería dado el contexto). Revisar si un despliegue real exige más.
4. **Sin mecanismo operativo para `TrackingCodeService.revoke()`** — el método existe y está probado, pero no hay ningún endpoint administrativo ni JMX para invocarlo en producción (a diferencia de `resolveStuckBatch`, que sí tiene su vía JMX desde ADR-022). Candidato a una mini-tarea futura, mismo patrón.
5. **Hallazgo #5 de la auditoría de Fase 2** (forma de `AggregateRoot`, no genérico, `pull` no atómico) — sigue pospuesto por decisión consciente (alto riesgo de tocar la clase base de Event Sourcing, bajo beneficio inmediato).

---

## 9. Lección de proceso de esta fase

Durante la ejecución, dos veces el contenido de una tarea se aprobó en revisión pero el cierre mecánico (`git commit`/`merge` a `develop`) no se ejecutó de inmediato, permitiendo que el trabajo de la tarea siguiente se acumulara sin comitear en la misma rama de trabajo (Tarea 3.5, y más notablemente Tarea 3.10/3.13, corregido separando ambos en commits y merges independientes antes del cierre final). Ninguna pérdida de trabajo real ocurrió, pero el patrón se detectó únicamente por auditoría explícita del `git log`/`git status`, nunca por confianza en el resumen narrativo del agente — consistente con la disciplina de evidencia literal que rigió toda la fase.

---

## 10. Próximo paso

Con Fase 3 cerrada, el trabajo pendiente identificado es:
- **Fase D** de la auditoría de Fase 2 (Hallazgo #5, `AggregateRoot`) — pospuesto, revisar si el tiempo del proyecto lo justifica.
- **Módulo de identidad** (sección 7) — hilo completamente aparte, sin diseño todavía.
- **Deuda técnica de la sección 8** — ninguna bloquea nada, todas son mejoras incrementales sobre lo ya entregado.
