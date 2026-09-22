# IA — Resumen de diseño conceptual (no congelado)

**Estado:** Diseño pre-ADR para la pieza nueva (`ConvocatoriaAuditFacts`); referencia consolidada para lo ya existente (narrativa de donación individual).
**Para qué sirve:** punto de continuidad para retomar la capa IA de Fase 6 sin reconstruir el hilo completo de la conversación.

---

## 1. Lo que ya existía antes de esta conversación (Fase 2, Tarea 11-12, cerrado e implementado)

```text
event_store (privado, ai nunca lo toca)
     ↓
DonationAuditFacts (hechos deterministas por donación individual;
                     único documento que ai puede leer)
     ↓
AuditFactsPort (contrato en contracts)
     ↓
NarrativeGenerator
     ├── SpringAiLlmAdapter — salida estructurada vía BeanOutputConverter;
     │   las citas las declara el LLM, nunca se fabrican en el adaptador
     ├── NarrativePromptSanitizer — defensa probada contra inyección de prompt
     │   (delimitador real """)
     ├── GroundingValidatorImpl — comparación tipada por FactType (fechas
     │   ISO-8601, montos exactos), nunca contains()
     ├── FallbackNarrativeTemplateService — sentinel modelIdentifier="FALLBACK",
     │   con nextRetryAt
     └── NarrativeCacheCoordinator — single-flight, evita llamadas duplicadas
         concurrentes al proveedor
```

- `DonationAuditFacts` incluye transiciones auditadas (`DISPATCHED→RECEIVED`, `DISPATCHED→DELIVERED`, `RECEIVED→DELIVERED`, umbrales configurables vía `application.yml`, congelados en el propio registro histórico al momento de generarse) y `financialFlags` (`causedDeficit` de `Fund`).
- Cobertura de pruebas real: Testcontainers, timeout, error de proveedor, sanitización con inyección real, aislamiento de caché por `promptTemplateVersion`, camino de grounding fallido con verificación estructural.
- El LLM nunca es fuente de verdad, nunca accede directamente al Event Store ni a documentos internos de `core` (ADR-015).

## 2. Lo genuinamente pendiente de lo ya existente — no es diseño, es configuración

`SpringAiLlmAdapter` usa la abstracción `ChatModel` de Spring AI — agnóstico de proveedor. El mecanismo está construido y probado; **el proveedor real (qué starter de Spring AI, qué modelo, qué presupuesto de tokens) no está conectado todavía**. Mismo patrón que "conexión a red EVM real" en Blockchain: es una decisión de negocio/costo/infraestructura, no un hueco de arquitectura.

## 3. Diseño nuevo — `ConvocatoriaAuditFacts` (Fase 6)

**Por qué es una pieza separada, no una ampliación de `DonationAuditFacts`:** responden preguntas de nivel distinto — *"¿qué ocurrió con esta donación?"* vs. *"¿qué ocurrió con esta campaña como conjunto?"*. Mezclarlas crearía un "AuditFacts para todo" que sabe demasiado — deuda arquitectónica disfrazada de reutilización.

```text
Convocatoria + CampaignFundingLedger + fuentes deterministas de impacto
     ↓
ConvocatoriaAuditFacts
     ↓
AuditFactsPort (mismo contrato, sin modificar)
     ↓
NarrativeGenerator (mismo pipeline, sin modificar)
```

**Contenido cerrado, cada campo con fuente determinista verificada — no se incluyó nada sin poder señalar de dónde sale:**

```text
ConvocatoriaAuditFacts
├── identificación / estado / organización responsable
├── targetAmount + targetPolicy
├── clearedAmount                                    ← CampaignFundingLedger
├── unidades entregadas
│    └── SUM(PhysicalAsset.quantity)
│        para assets en estado DELIVERED de la convocatoria
└── receptores distintos
     └── COUNT(DISTINCT beneficiaryRef)
         para entregas ASSET_DELIVERED de la convocatoria
```

**Deliberadamente excluido, y por qué:**

- **`familiasAlcanzadas`** — verificado contra ADR-014 y el glosario del proyecto: `beneficiaryRef` es una referencia opaca al receptor final, distinta de `custodianRef` (responsable operativo). No existe en el dominio una invariante que establezca `1 beneficiaryRef = 1 familia/hogar` — podría representar igualmente una organización, un punto de entrega comunitario, u otro receptor. Afirmar "familias" sería una afirmación demográfica que el modelo actual no respalda — exactamente el tipo de salto que `GroundingValidator` existe para impedir. El hecho neutral que sí se puede afirmar es "receptores distintos" (conteo de `beneficiaryRef`), no "familias".
- Análisis predictivo, recomendaciones generadas por IA, scoring de campañas, inferencias de impacto sin respaldo determinista, acceso directo del LLM a MongoDB/Event Store, un nuevo read model dedicado solo para alimentar la IA.

## 4. Perímetro consolidado

```text
Ya cerrado (Fase 2)
├── Narrativa de donación individual (DonationAuditFacts)
└── Pipeline completo: grounding, sanitización, fallback, cache

Nuevo (Fase 6)
└── ConvocatoriaAuditFacts — reutiliza el pipeline existente sin modificarlo

Fuera de arquitectura (infraestructura/negocio, no bloquea el diseño)
└── Elección de proveedor de LLM, starter de Spring AI, credenciales,
    presupuesto de tokens, tuning de prompts por proveedor
```

## 5. Nota de procedencia

Narrativa de donación individual, `AuditFactsPort`, `NarrativeGenerator` y todo su pipeline (grounding, sanitización, fallback, cache) ya existían, implementados y probados, antes de esta conversación (Fase 2). `ConvocatoriaAuditFacts` es diseño nuevo de esta sesión, sin código todavía — reutiliza el contrato y el pipeline existentes sin modificarlos.
