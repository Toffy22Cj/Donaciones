# Enmienda a ADR-024 — Separación de la narrativa como sub-recurso

**Insertar esta sección en `ADR-024-Exposicion-Asincrona-Narrativas.md`, cambiando el estado del documento de "Propuesto para aprobación humana" a "Approved with amendment".**

---

## Enmienda — Exposición HTTP como endpoint independiente

La decisión original de ADR-024 (API asíncrona, `NarrativeReadPort`, estados `AVAILABLE`/`PENDING`, `LLM_GENERATED`/`FALLBACK_TEMPLATE`, generación lazy sin `.join()` bloqueante) queda **aprobada sin cambios en su lógica interna**.

Se enmienda exclusivamente el punto de exposición HTTP, que el documento original dejaba implícito. Verificación factual realizada contra el contrato congelado de `PublicDonationTrackingDTO` (`estado-fase3.md`) confirmó que:

1. `narrativeSummary` en ese DTO era un campo `String` simple, nunca tipado para representar estado asíncrono — cambiarlo de tipo o mantenerlo embebido entra en conflicto con la semántica HTTP ya establecida en esta fase (una proyección en estado `PAUSED` se traduce a `200 OK` con `status: EN_PROCESO`, nunca a un código HTTP distinto — la infraestructura interna nunca determina el código de transporte del recurso principal).
2. Existe precedente directo en el propio backlog de Fase 3: el historial de un activo físico ya se sirve como sub-recurso independiente (`GET /tracking/{trackingCode}/assets/{assetRef}/history`), separado del recurso principal, precisamente para no forzar contenido opcional o costoso dentro de la respuesta base.

**Decisión enmendada:**

`NarrativeReadPort` se expone mediante un endpoint HTTP propio, separado del recurso principal de tracking:

```
GET /api/v1/donations/tracking/{trackingCode}/narrative
```

- `PENDING` → `202 Accepted`, body `NarrativeResponseDTO` con `status: PENDING`, sin contenido.
- `AVAILABLE` → `200 OK`, body `NarrativeResponseDTO` con `status: AVAILABLE`, `content`, `source: LLM_GENERATED | FALLBACK_TEMPLATE`.

El recurso principal (`GET /tracking/{trackingCode}`) **no contiene ningún campo relacionado con la narrativa**. Su semántica HTTP permanece exactamente como estaba diseñada antes de ADR-024 (siempre `200` si el token es válido y el fondo existe).

**Autorización:** el mismo `OncePerRequestFilter` de tracking code (ya diseñado, aplicado a todo el patrón de ruta `/tracking/**`) cubre automáticamente este sub-recurso sin ningún mecanismo nuevo — la ruta del endpoint de narrativa cae dentro del patrón ya protegido.

**Impacto en base de datos:** ninguno. No se modifica `donation_projections`, `donation_audit_facts`, `asset_index`, el Event Store, ni la colección existente de `ai` (`DonorReportDocument`).

**`PublicDonationTrackingDTO`:** se retira formalmente el campo `narrativeSummary` de su definición en `estado-fase3.md`. Ver documento actualizado.
