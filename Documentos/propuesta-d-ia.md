# Propuesta D-IA — ADR-040: C2, C3, C4, C5 y C8 (narrativa de convocatoria)

**Estado:** **PROPUESTO** (2026-10-07). Redactado bajo la autorización de trabajo autónomo de Carlos, que pidió esta propuesta "SOLO como documento PROPUESTO, sin código de B5". **No decide nada:** cada punto es una recomendación con su pregunta en §6. No autoriza código.
**Desbloquea, si se aprueba:** B5 (productor y consumidor de `CampaignAuditFactsPort`) y, con él, los criterios 14 y 19 de `golden-path.md` §8.
**Hechos:** verificados en `develop` tras #63 (B6-b), con cita.

---

## 1. Hechos que condicionan la decisión

| # | Hecho | Evidencia |
|---|---|---|
| F1 | `CampaignAuditFactsPort.getCampaignAuditFacts(campaignRef)` y `CampaignAuditFactsDTO` existen en `contracts`, **sin implementación ni consumidor**. El DTO ya fija los campos: estado, organización, meta y política, `clearedAmount`, `unitsDelivered` y `distinctRecipients` | `contracts/.../CampaignAuditFactsPort.java`, `CampaignAuditFactsDTO.java` |
| F2 | Todo `PhysicalAsset` nuevo lleva `campaignRef` en su génesis 3.0: en el Camino A heredado del `Fund`, en el Camino B validado por `convocatoria`, y en la división heredado del padre. Los v1/v2 no tienen convocatoria y nunca se infiere | ADR-029 Enmienda 1; `estado-fase6.md` §0.7 |
| F3 | Las proyecciones **ignoran los activos del Camino B** (no tienen `Fund` al que colgarse) y la logística proyectada **no guarda `beneficiaryRef`** | `DonationProjectionHandler.java:194-200`, `:283-286` |
| F4 | `beneficiaryRef` está en `ASSET_DELIVERED` (`AssetDeliveredPayload`) y en el agregado rehidratado | `PhysicalAsset.deliver` |
| F5 | `clearedAmount` vive en `CampaignFundingLedger` (`convocatoria`, CRUD), y solo existe si la convocatoria acepta `MONETARY` (C7, cerrado) | ADR-040 §7-A C7 |
| F6 | ADR-040 §2.1 **excluye** un "read model dedicado solo para IA" y el acceso del LLM a MongoDB o al event store. §2.5: sin colección propia mientras C3 esté abierta | ADR-040 §2.1, §2.5 |
| F7 | En B6-c, la lectura operacional de activos se resolvió leyendo el event store (`PhysicalAssetOperationalQueryService`), precisamente porque el Camino B no se proyecta | `plan-b6-c-activos-http.md` DD-13 |
| F8 | La ruta `GET /api/v1/public/campaigns/{publicCode}/narrative` ya es pública en `PublicRoutes` y no tiene controlador | `PublicRoutes.java` |

## 2. C2 — Decisión A: cómo se recolecta la parte de `PhysicalAsset`

| Opción | A favor | En contra |
|---|---|---|
| (a) **Pull bajo demanda desde el event store, en `core`** | Una sola fuente de verdad. Incluye el Camino B y las divisiones sin tocar las proyecciones. Sin colección nueva (F6). Mismo patrón que F7 | Coste por petición proporcional a los activos de la convocatoria; lo acota la caché de narrativa (C3) |
| (b) Proyección nueva por `campaignRef` (change stream) | Lectura O(1) | Es el "read model dedicado solo para IA" que excluye ADR-040 §2.1 (F6), y repetiría el problema de B-PROJ con un manejador más |
| (c) Ampliar `DonationProjection.logistics` con `beneficiaryRef` | Reutiliza una proyección | Sigue sin el Camino B (F3); mezcla una pregunta de convocatoria en un documento por `Fund` |

**Recomendación: (a).** Una consulta de `core`, `CampaignDeliveryFactsQuery`, que:
1. localiza los activos de la convocatoria por la génesis 3.0 (`ASSET_REGISTERED` con `payload.campaignRef = X`, que incluye a los hijos de una división);
2. rehidrata cada stream;
3. devuelve `unitsDelivered = Σ quantity` de los `DELIVERED` y `distinctRecipients = |{beneficiaryRef}|` de esos mismos.

Necesita un índice sobre `event_store` (`eventType`, `payload.campaignRef`). Un índice no cambia payloads, versiones, hash, Merkle ni anclaje. Los activos sin `campaignRef` quedan fuera, sin inferir (F2).

## 3. C3 — Decisión B: vida del resultado

**Recomendación: efímero.** Los hechos se construyen en cada petición y no se guardan. La narrativa ya tiene su caché (`NarrativeCacheCoordinator`, single-flight). La clave de caché propuesta es el hash de los hechos más `promptTemplateVersion`: si nada cambió, no hay llamada al LLM; si cambió algo, la narrativa se regenera. Un snapshot histórico persistido necesitaría una colección propia, que ADR-040 §2.5 aplaza a esta decisión, y la demo no lo pide.

## 4. C4 — Decisión C: consistencia temporal entre las tres fuentes

Las tres fuentes cambian en transacciones que IA no orquesta (ADR-040 §2.4): `Convocatoria`, `CampaignFundingLedger` y los streams de activos.

**Recomendación: lecturas independientes, con el instante de cada una en los hechos.** El DTO gana `asOf` por fuente (o un único `generatedAt` más el instante del ledger). La narrativa dice "a fecha de…". El *grounding* valida contra **el mismo** DTO que se le dio al LLM, así que nunca afirma un dato que no esté en los hechos.

Una transacción entre módulos para leer las tres no aporta nada a un informe público y obligaría a `app` a orquestar una lectura multi-colección sin necesidad. Cambiar el DTO es una pregunta en sí misma (Q-DIA-4).

## 5. C5 — Productor; C8 — convocatoria `CLOSED`

**C5. Recomendación:** `CampaignAuditFactsProducer` en **`app`**, porque cruza `convocatoria` (estado, meta, política y `clearedAmount`) y `core` (`CampaignDeliveryFactsQuery`). Es la convención A1 de D-API: lo que cruza módulos vive en `app`.
- El consumidor en `ai` reutiliza el pipeline de `DonorReportGenerator` con un prompt de convocatoria, sin "familias" (ADR-040 §2.1).
- El controlador público se pone en `app.web`, sobre la ruta de F8.
- Convocatoria inexistente: el mismo 404 público que CV-07 (`PublicCampaignNotFoundException`).

**C8. Recomendación:** la narrativa **sí** se sirve para una convocatoria `CLOSED`. Es el informe final, que es lo que más interesa al donante. El estado aparece en los hechos (`status`) y la narrativa lo menciona. Las `PRIVATE_LINK` se sirven por su código, como en CV-07 (no descubribles, pero no autenticadas).

## 6. Preguntas para Carlos

| # | Pregunta | Recomendación |
|---|---|---|
| Q-DIA-1 (C2) | ¿Pull bajo demanda desde el event store en `core`, con un índice sobre `payload.campaignRef`? | Sí (§2) |
| Q-DIA-2 (C3) | ¿Hechos efímeros, con la caché de narrativa indexada por el hash de los hechos? | Sí (§3) |
| Q-DIA-3 (C4) | ¿Lecturas independientes, con el instante de cada fuente en los hechos? | Sí (§4) |
| Q-DIA-4 | El DTO de `contracts` aún no tiene `asOf` por fuente. ¿Se añade? Es un cambio de contrato de un puerto sin implementación ni consumidor | Sí, antes de B5: hoy no rompe a nadie |
| Q-DIA-5 (C5) | ¿`CampaignAuditFactsProducer` en `app` y `CampaignDeliveryFactsQuery` en `core`? | Sí (§5) |
| Q-DIA-6 (C8) | ¿Narrativa también para `CLOSED`? | Sí (§5) |

## 7. Lo que esta propuesta no cambia

- Nada del pipeline de narrativa individual (`DonationAuditFacts`, `AuditFactsPort`, `DonorReportGenerator`).
- Ningún payload de evento, versión de esquema, canonicalización, hash, Merkle ni anclaje.
- La exclusión de "familias alcanzadas" (ADR-040 §2.1).
