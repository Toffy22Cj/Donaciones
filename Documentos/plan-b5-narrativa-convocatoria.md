# Plan B5 — Narrativa de convocatoria (criterios 14 y 19)

**Estado:** **HECHO** (2026-10-07; evidencia `evidencia-fase6/b5-narrativa-convocatoria-7d32202-2026-10-07.txt`). Era **EN EJECUCIÓN** bajo la segunda autorización de trabajo autónomo de Carlos (2026-10-07), P1.3. Las preguntas Q-DIA-1 a 6 de `propuesta-d-ia.md` se deciden con su recomendación, como `[DECISIÓN DELEGADA — pendiente de ratificar por Carlos]` (DD-33 a DD-38). El plan entero es DD-39.
**Condiciones obligatorias de Carlos (no son delegadas):**
1. el LLM solo recibe hechos deterministas, sin PII ni identificadores internos;
2. cada afirmación de la narrativa pasa la validación de *grounding*; si no la pasa, **no se publica**: la respuesta dice "narrativa no disponible", nunca texto sin validar;
3. "receptores distintos", nunca "familias alcanzadas";
4. tests con un LLM simulado, incluido un caso de *prompt injection* en un texto de la convocatoria (título o descripción).

---

## 1. Decisiones (de `propuesta-d-ia.md`)

| # | Decisión |
|---|---|
| DD-33 (Q-DIA-1, C2) | Las entregas se recolectan **bajo demanda desde el event store, en `core`** (`CampaignDeliveryFactsQuery`): activos cuya génesis `ASSET_REGISTERED` 3.0 lleva ese `campaignRef` (incluidos los hijos de una división y el Camino B), rehidratados. `unitsDelivered = Σ quantity` de los `DELIVERED`; `distinctRecipients = |{beneficiaryRef}|` de esos mismos. Índice nuevo en `event_store` sobre `(eventType, payload.campaignRef)`: no cambia ningún evento, hash ni Merkle |
| DD-34 (Q-DIA-2, C3) | Hechos **efímeros**. La narrativa se guarda en memoria por convocatoria, con la clave `sha256(hechos) + promptTemplateVersion + modelIdentifier`, y se genera de forma asíncrona y de un solo vuelo. Si los hechos cambian, se regenera. Un fallo del proveedor se reintenta tras `fallbackRetryInterval`; un rechazo de grounding no, con los mismos hechos. *Ajuste en la ejecución:* un mapa propio en `CampaignNarrativeGenerator`, no el `NarrativeCacheCoordinator`, que está tipado a `DonorReportDTO` (no se toca la narrativa individual) |
| DD-35 (Q-DIA-3, C4) | Lecturas **independientes**, con el instante de cada fuente en los hechos |
| DD-36 (Q-DIA-4) | `CampaignAuditFactsDTO` gana `currency`, `fundingReadAt` y `deliveriesReadAt` (no tiene implementación ni consumidor: no rompe nada) |
| DD-37 (Q-DIA-5, C5) | Productor `CampaignAuditFactsProducer` en `app` (cruza `convocatoria` y `core`); entregas `CampaignDeliveryFactsQuery` en `core`; financiación `CampaignFundingFactsQuery` en `convocatoria`; consumidor `CampaignNarrativeGenerator` en `ai`; ruta pública en `app.web` |
| DD-38 (Q-DIA-6, C8) | La narrativa se sirve también para `CLOSED`; `PRIVATE_LINK` por su código, como CV-07 |

## 2. Qué recibe el LLM (condición 1)

Solo números y estados: `status` de la convocatoria, `targetAmount`, `targetPolicy`, `clearedAmount`, `unitsDelivered` y `distinctRecipients`, con su moneda. **Nunca** el título, la descripción, el `campaignRef`, el `organizationRef`, ids de activos, `beneficiaryRef` ni `donorRef`. El título se muestra en la página por CV-07, fuera del LLM. Así una inyección en el título no llega al modelo: el test lo comprueba leyendo el *prompt* que recibe el LLM simulado.

## 3. Validación de grounding de la convocatoria (condición 2)

`CampaignGroundingValidator` rechaza la narrativa si:
- no cita ningún hecho, o cita uno que no coincide **exactamente** con los hechos (tipos `UNITS_DELIVERED`, `DISTINCT_RECIPIENTS`, `CLEARED_AMOUNT`, `TARGET_AMOUNT`, `CAMPAIGN_STATUS`, en un enum propio, `CampaignFactType`, para no cambiar el `FactType` de la narrativa individual);
- el texto contiene un número que no es ninguno de los hechos citados (no puede afirmar cifras sin respaldo);
- el texto contiene "familia(s)", "familiar(es)", "hogar(es)" o sus equivalentes en inglés (condición 3).

Si no pasa, o el proveedor falla, la respuesta es `status: "UNAVAILABLE"` con el texto fijo "Narrativa no disponible". Nunca el texto del LLM.

## 4. Ruta

`GET /api/v1/public/campaigns/{publicCode}/narrative` (ya pública). Respuestas:
- `200 {status: "AVAILABLE", content, source: "LLM_GENERATED", facts}`;
- `202 {status: "PENDING", facts}` mientras se genera;
- `200 {status: "UNAVAILABLE", content: "Narrativa no disponible", facts}` si no pasó el *grounding*.

`facts` son los hechos deterministas públicos (`status`, `currency`, `targetAmount`, `clearedAmount`, `unitsDelivered`, `distinctRecipients`): la "evidencia visible" del golden path §7. Un código inexistente da el mismo 404 que CV-07.

## 5. Tests (todos con un LLM simulado; ninguno llama a un proveedor real)
1. `core`: la consulta suma las unidades del padre y del hijo, cuenta los receptores distintos, incluye el Camino B y excluye los activos sin convocatoria y los de otra convocatoria.
2. `ai`: grounding (cita inexistente, número sin citar, "familias", sin citas → no se publica); fallo del proveedor → "Narrativa no disponible"; caché por hash.
3. `app`, HTTP: *prompt injection* en el título y la descripción → no llegan al *prompt*, y una respuesta del LLM que obedezca la inyección no se publica.
4. Recorrido: criterios 14 y 19 en `GoldenPathHttpIntegrationTest`.

## 6. ¿LLM real o simulado en la demo?

El adaptador real (`SpringAiCampaignLlmAdapter`) usa el mismo cliente que la narrativa individual. La clave **solo** llega por la variable de entorno `SPRING_AI_OPENAI_API_KEY` (`application.yml` tiene un valor ficticio por defecto). Sin la variable, la llamada falla y la ruta responde `UNAVAILABLE` con "Narrativa no disponible", nunca texto sin validar. Con ella, el texto del LLM pasa el mismo grounding.
