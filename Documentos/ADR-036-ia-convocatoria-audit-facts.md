# ADR-036 (número tentativo — confirmar contra el catálogo real antes de commitear) — ConvocatoriaAuditFacts

**Estado:** Aprobado parcialmente — límites de responsabilidad, exclusiones semánticas y frontera modular cerrados. **Tres decisiones estructurales interdependientes siguen abiertas (§7-A, C1-C4)** y son más centrales que en ADR-033/034/035: no son detalles menores, son el núcleo de cómo se construye el componente.
**Fecha:** Sesión de Fase 6, review formal de 12 puntos (Modo de Arquitectura), posterior a ADR-033/034/035.
**Complementa:** `ia-resumen.md`. No reabre el pipeline existente (`DonationAuditFacts`, `AuditFactsPort`, `NarrativeGenerator`, `SpringAiLlmAdapter`, `NarrativePromptSanitizer`, `GroundingValidatorImpl`, `FallbackNarrativeTemplateService`, `NarrativeCacheCoordinator`), implementado y probado desde Fase 2.

---

## 1. Contexto

`ia-resumen.md` distingue dos preguntas de nivel distinto: *"¿qué ocurrió con esta donación?"* (`DonationAuditFacts`, ya cerrado) vs. *"¿qué ocurrió con esta campaña como conjunto?"* (`ConvocatoriaAuditFacts`, diseño nuevo de Fase 6). Este ADR formaliza el perímetro del segundo componente, reutilizando el pipeline de narrativa existente sin modificarlo.

## 2. Decisión

### 2.1 Responsabilidad y contenido

`ConvocatoriaAuditFacts` responde exclusivamente sobre el conjunto agregado de una convocatoria, con cada campo trazado a una fuente determinista:

```
ConvocatoriaAuditFacts
├── identificación/estado/organización responsable   ← Convocatoria
├── targetAmount + targetPolicy                       ← Convocatoria
├── clearedAmount                                     ← CampaignFundingLedger
├── unidades entregadas                               ← SUM(PhysicalAsset.quantity) en DELIVERED
└── receptores distintos                              ← COUNT(DISTINCT beneficiaryRef) en DELIVERED
```

Reutiliza `AuditFactsPort` y el pipeline de `NarrativeGenerator` **sin modificarlos** (ver C1, contradicción pendiente sobre el nombre exacto del puerto).

**Exclusión deliberada, no reabierta por este review**: "familias alcanzadas" — `beneficiaryRef` es una referencia opaca sin invariante de dominio que garantice `1 beneficiaryRef = 1 familia/hogar` (podría ser una organización, un punto de entrega comunitario). El hecho neutral afirmable es "receptores distintos", nunca "familias". También excluidos: análisis predictivo, scoring, inferencias sin respaldo determinista, acceso directo del LLM a MongoDB/Event Store, read model dedicado solo para IA.

### 2.2 Tres decisiones separadas, no una sola

El review estableció que estas tres preguntas son independientes entre sí y no deben resolverse por analogía con `DonationAuditFacts` (que es event-sourced en su totalidad, mientras que dos de las tres fuentes de `ConvocatoriaAuditFacts` son CRUD):

```
A. ¿Cómo se recolectan los datos?           pull / push / combinación
B. ¿Qué vida tiene el resultado?            efímero / snapshot persistido
C. ¿Qué garantía temporal tiene la combinación de fuentes?
                                             lecturas independientes / snapshot / transacción / otra
```

Ninguna de las tres está resuelta por este ADR — ver §7.

### 2.3 Errores — distinción central

**Hechos con valores en cero (convocatoria sin actividad todavía) no son un error** — mismo tratamiento ya existente en `DonationAuditFacts` para una donación recién creada sin transiciones auditadas. El error real es no poder construir los hechos en absoluto (`campaignRef` sin `Convocatoria` correspondiente), categoría distinta y sin nombre de excepción todavía.

### 2.4 Concurrencia — sin mecanismo nuevo

Mientras la construcción sea read-only y no persistida, la concurrencia de lectores no requiere coordinación propia. `NarrativeCacheCoordinator` (single-flight, ya existente) sigue resolviendo la generación redundante de narrativa — **no** constituye una garantía de snapshot consistente entre las tres fuentes, son dos invariantes distintas. Un lock local en IA no puede resolver un problema estructural que vive fuera de su control (las tres fuentes cambian por transacciones que IA no orquesta).

### 2.5 Persistencia

Sin colección propia mientras B esté abierta — crear una para un objeto potencialmente efímero sería arquitectura anticipada sin decisión que la respalde. Único elemento de persistencia que sí es independiente de B: verificar índice `{campaignRef:1, status:1}` sobre la proyección de `PhysicalAsset` usada para la agregación (candidato de rendimiento, no confirmado si ya existe).

### 2.6 Seguridad — perímetro ya definido por contrato HTTP existente

`GET /public/campaigns/{publicCode}/narrative` — pública, sin JWT, distinta del perímetro de `trackingCode` que usa `DonationAuditFacts`. Convocatoria privada-por-enlace: no aparece en descubrimiento público, pero `publicCode` sigue resolviéndola — la privacidad aquí es no-descubribilidad, no autenticación. `ai` no decide autorización — pertenece al perímetro de exposición HTTP, antes de llegar a `AuditFactsPort`.

### 2.7 Estados y transiciones

Sin máquina de estados propia — no hay evidencia equivalente a `COLLECTING` de `MerkleBatch` (ADR-035); introducir una sería fabricar complejidad para ocultar una decisión (B) no tomada, no para resolver un problema real documentado.

## 3. Consecuencias

- Positivas: preserva la separación de responsabilidad entre obtención de hechos deterministas y generación de narrativa, ya validada por `DonationAuditFacts`; identifica con precisión por qué esta capa no es simétrica a las anteriores (mezcla event-sourced + CRUD, algo que ninguna otra capa de Fase 6 combina de esta forma).
- Negativas / deuda aceptada: tres decisiones estructurales sin resolver, más una contradicción de nomenclatura de puerto sin verificar — este ADR cierra menos superficie que ADR-033/034/035 en proporción a lo que queda por diseñar.

## 4. Alternativas descartadas

- **Ampliar `DonationAuditFacts` para cubrir también el nivel de convocatoria**: descartada explícitamente en el diseño original — crearía "un AuditFacts para todo que sabe demasiado, deuda arquitectónica disfrazada de reutilización".
- **Incluir "familias alcanzadas" como campo**: descartada — no existe invariante de dominio que respalde la equivalencia `beneficiaryRef ↔ familia`.
- **Máquina de estados por analogía con `MerkleBatch.COLLECTING`**: descartada — no hay entidad persistida ni construcción por fases equivalente; introducirla sería complejidad sin justificación documentada.
- **Lock de concurrencia en IA para resolver la consistencia temporal entre fuentes**: descartada — el generador no controla las transacciones que modifican `Convocatoria`/`CampaignFundingLedger`/`PhysicalAsset`; un lock local no resuelve un problema distribuido entre módulos que no coordina.
- **Resolver la contradicción `AuditFactsPort`/`CampaignAuditFactsPort` por intención o eligiendo la fuente que parezca más autoritativa**: descartada — ninguna fuente declara equivalencia; requiere verificación de código antes del ADR final (ver §7, C1).

## 5. Autorización

Narrativa de convocatoria: pública, `publicCode`, sin JWT ni `RoleAuthorizationPolicy`. `ai` no participa en la decisión de autorización. Comportamiento para convocatoria `CLOSED`: abierto (§7, C8) — pertenece al contrato del endpoint, no a este componente.

## 6. Observabilidad

Pipeline LLM (sanitización/grounding/fallback/caché) reutilizado sin cambios. La trazabilidad completa "narrativa → hechos → fuente exacta" queda condicionada por completo a la decisión C — sin resolver C no hay un "momento" común que trazar entre las tres fuentes. Consecuencia de un patrón ya existente, no decisión nueva: si `PhysicalAsset` participa vía proyección event-sourced, la agregación debería poder señalar los `eventId`/`streamId` de los eventos `ASSET_DELIVERED` que la componen — pendiente de verificar que la proyección elegida conserva esa trazabilidad de forma accesible.

## 7. Explícitamente NO resuelto por este ADR

### 7-A. Decisiones y contratos abiertos

| # | Pendiente | Bloquea |
|---|---|---|
| C1 | **Contradicción `AuditFactsPort` (`ia-resumen.md`) vs. `CampaignAuditFactsPort` (`api-contract-matrix.md` §5)** — misma pieza con dos nombres, o dos interfaces distintas. No resuelta por intención; requiere verificación de código real | Cierre completo del contrato de entrega al pipeline |
| C2 | Decisión A: mecanismo de recolección de la porción `PhysicalAsset` (pull bajo demanda vs. proyección/change stream como `DonationAuditFactsHandler`) | Diseño del productor |
| C3 | Decisión B: ¿efímero o snapshot histórico persistido? | Esquema de persistencia, si aplica |
| C4 | Decisión C: garantía de consistencia temporal entre las tres fuentes | Corrección de los hechos combinados, trazabilidad completa, pruebas de consistencia |
| C5 | Nombre/firma del productor de `ConvocatoriaAuditFacts` — no existe en ninguna fuente | Materialización del componente |
| C6 | Contrato de error si `campaignRef` no resuelve a ninguna `Convocatoria` | Manejo de errores del productor |
| C7 | `Convocatoria` sin `CampaignFundingLedger` correspondiente — posible hueco no resuelto en ADR-033, no inventado aquí | Semántica de integridad del productor |
| C8 | Comportamiento de la narrativa pública cuando `Convocatoria.status=CLOSED` | Contrato del endpoint HTTP, no de este componente |

### 7-B. Verificación técnica pendiente

Índice `{campaignRef:1, status:1}` sobre la proyección de `PhysicalAsset` usada para agregar (candidato de rendimiento, no confirmado si ya existe) · trazabilidad `eventId`/`streamId` accesible desde esa misma proyección · aislamiento de caché por campaña verificado, no asumido, aunque comparta `promptTemplateVersion` con otra campaña · single-flight verificado específicamente para narrativas de convocatoria concurrentes.

### Riesgos explícitamente retirados durante este review

Máquina de estados propia — no justificada · lock de concurrencia para lectores — no justificado (construcción read-only) · IA decidiendo autorización — descartado, pertenece al perímetro HTTP · "familias alcanzadas" como campo — excluido desde el diseño original, reafirmado con prueba crítica dedicada en el plan de testing.

Ningún punto de §7 bloquea continuar con APIs/Frontend o el Dataset final de Fase 6; C1 y C5 deben resolverse antes de asignar la materialización del productor a un agente de código, y C2-C4 antes de considerar el diseño de este componente completo (no solo su perímetro).

## 8. Trazabilidad de verificación

Se releyó textualmente `api-contract-matrix.md` §5 y `ia-resumen.md` §3 para confirmar que la contradicción C1 es real (dos nombres de puerto distintos, sin declaración de equivalencia en ninguna fuente) y no una diferencia de interpretación. No se inspeccionó código de `contracts` para `AuditFactsPort`/`CampaignAuditFactsPort` en esta sesión — queda como la verificación pendiente más urgente de esta capa.
