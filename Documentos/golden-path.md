# Golden Path — Demo de extremo a extremo (no congelado)

**Estado:** Escenario conceptual cerrado. **No es ejecutable hoy** — depende de precondiciones de implementación explícitas (§5; las de dominio de Fase 5 ya están resueltas, quedan las de HTTP/Fase 6). Sirve para derivar el dataset mínimo, los contratos de API y las pantallas del frontend a partir de una única historia real, no de una lista de endpoints inventados.

---

## 1. Precondiciones (ya deben existir antes de que arranque el recorrido en vivo)

- Bootstrap ejecutado: 1 `Account` con `platformAuthority = ADMINISTRATOR`.
- 1 `Organization` registrada, `PENDING_VERIFICATION`, con su `REPRESENTATIVE` y un `Account` adicional con rol `ADMINISTRATOR` de organización.
- Contrato Solidity desplegado en testnet real, wallet con fondos de prueba, RPC provider configurado.
- Proveedor de LLM real conectado vía `SpringAiLlmAdapter` (proveedor concreto — OpenAI/Anthropic/Ollama — sin fijar aquí; es configuración/infraestructura, no dominio).

## 2. El recorrido

```text
1. Platform Administrator verifica la Organización
   VERIFY_ORGANIZATION → Organization.verificationStatus: PENDING_VERIFICATION → VERIFIED

2. La Organización crea la Convocatoria
   Actor: ADMINISTRATOR de organización. Precondición: Organization VERIFIED.
   Crea Convocatoria (targetPolicy = FLEXIBLE — la más simple para el camino feliz),
   visibilidad PUBLIC. Asigna un EMPLOYEE como responsable.
   → publicCode/QR generado, convocatoria visible en el panel de descubrimiento público.

3. Donación — dos sub-pasos del mismo flujo, no dos caminos de negocio distintos
   3A. Donante SIN cuenta / 3B. Donante CON cuenta (login previo si aplica)
       accede por publicCode/QR → ve info pública
       inicia intención de donación (monto X) → redirige a pasarela de pago
       webhook confirma pago → clearFundsGenesis(organizationRef, campaignRef,
         donorRef, currency, amount, sourceRef, commandId, actorRef)
       → Fund nace ya CLEARED (sequence=0, sin estado PLEDGED intermedio)
         + CampaignFundingLedger actualizado (misma transacción)
       → trackingCode calculado inmediatamente tras el éxito de clearFundsGenesis
         (HMAC sobre fundId ya confirmado)
       → canal de entrega del trackingCode al donante: PENDIENTE (ver §5.4)

   Nota de corrección: la versión anterior de este paso describía
   RegisterFund → PLEDGED → webhook → ClearFundsAsGenesis. Verificado contra
   FundCommandService.java (103 líneas): el servicio expone registerFund,
   clearFundsGenesis, confirmAllocation, reverseAllocation — NO expone
   clearFundsForPledge. El flujo de dos pasos (promesa diferida, confirmación
   posterior) no es ejecutable con lo inspeccionado; el camino de un solo paso
   (clearFundsGenesis directo) sí lo es. Ver §5.3.

   Criterio de aceptación de arquitectura: 3A y 3B deben converger en el mismo
   clearFundsGenesis sin duplicar lógica de dominio — solo cambia el origen
   del donorRef (opaco efímero vs. derivado de accountId).

4. Transmutación a especie
   Actor: EMPLOYEE asignado.
   Camino A (compra con el Fund): registerPhysicalAsset(commandId, fundId,
     organizationRef, ..., allocationId, ...). organizationRef debe ser el del
     Fund (se valida contra el Fund cargado y se rechaza si difiere);
     donorRef = null, donationRef = null (ADR-029 §2.1). Emite
     ASSET_REGISTRATION_SAGA → AssetRegisteredSagaPolicy confirma la
     asignación en el Fund (ADR-033).
   Camino B (donación en especie directa): registerPhysicalAssetFromDonation.
     organizationRef y donorRef son entrada directa; donationRef se genera
     por acto de donación. No depende de un Fund (ADR-029).
   Estado: ambos caminos implementados en core; sin endpoint HTTP (ver §5).

5. Ciclo logístico
   DISPATCH → RECEIVE → DELIVER. beneficiaryRef se sella en DELIVER (ADR-014).

6. Anclaje blockchain
   MerkleBatch Producer (diseño Fase 6) reclama los eventos del recorrido,
   calcula el root, PENDING.
   ADR-019: SUBMITTING → SUBMITTED → ANCHORED, en testnet real.
   Anclaje preparado y confirmado ANTES de la sesión de demo — el recorrido
   funcional es real, lo que se adelanta es el tiempo operacional de confirmación
   (una testnet pública no debe formar parte del camino crítico de una demo en vivo).

7. Vista pública / donante
   Por trackingCode o publicCode: recaudado, estado, trazabilidad del activo,
   evidencia de anclaje (IntegrityVerificationPort.verifyBatch → MATCH).
   Narrativa IA: DonationAuditFacts (donación individual) + ConvocatoriaAuditFacts
   (convocatoria completa — "unidades entregadas", "receptores distintos"; nunca
   "familias", por diseño ya cerrado). Generada con proveedor LLM real, no fallback.
```

## 3. Qué NO demuestra este Golden Path (deliberadamente — se prueba aparte)

- Todos los roles y todas las transiciones de verificación de `Organization` (`REJECTED`, `NEEDS_MORE_INFORMATION`).
- Capacidad de respaldo de `REPRESENTATIVE` (enmienda ADR-032).
- `targetPolicy = STRICT` o `CLOSE_ON_TARGET` (se usa `FLEXIBLE`, la más simple).
- Cualquier camino de recuperación blockchain (`STUCK`, `ANCHOR_MISMATCH`, `RESUBMIT`/`ABANDON`).
- Concurrencia (dos donaciones simultáneas agotando `STRICT`, dos revocaciones de Platform Administrator, etc.).
- Fallos del proveedor de IA (`FallbackNarrativeTemplateService` se prueba, no se demuestra en vivo).
- Panel administrativo de Platform Administrator (usuarios/organizaciones/gráficas — consultas cross-organización, diseño pendiente aparte).

## 4. Real vs. fixture en la demo

| Elemento | Real | Fixture/preparado |
|---|---|---|
| Comandos, eventos, agregados | Real, en vivo | — |
| Pago del donante | — | Webhook simulado (no hay pasarela de pago real integrada) |
| Anclaje blockchain | Real (testnet) | Confirmado *antes* de la sesión, mostrado ya resuelto |
| Narrativa IA | Real (proveedor real) | — |
| Verificación de integridad | Real | — |

## 5. Precondiciones de implementación — bloquean el paso 4/5, no son notas al pie

> **Actualización (cierre del Bloque A de Fase 5, C6).** Verificado contra el código de `feat/fase5-a7-1-a7-2`:
> - **§5.1 resuelta:** `HumanActor` existe (ADR-035) y `registerPhysicalAssetFromDonation` está implementado (Tarea 5.4).
> - **§5.2 resuelta en `core`:** `PhysicalAssetCommandService` invoca `authorize(...)` (P9 → P7) en `registerPhysicalAsset`, `registerPhysicalAssetFromDonation`, `splitPhysicalAsset` y `deliverAsset`. `dispatch`/`receive` siguen sin método público en el servicio.
> - **§5.3:** `clearFundsForPledge` ya existe en `FundCommandService`; todos sus comandos invocan `authorize(...)` salvo `reverseAllocation`, interno de la saga por diseño (ADR-036).
> - **Sigue pendiente:** no hay endpoints HTTP de escritura que construyan un `HumanActor` (la integración real con Identity solo está demostrada para `registerFund`), ni derivación de `organizationRef` para `ExternalActor` (webhook), ni §5.4. El Golden Path sigue **sin ser ejecutable de extremo a extremo**, ahora por la capa HTTP/Fase 6 y no por el dominio.
>
> El texto original de §5.1–§5.3 se conserva abajo como registro de la inspección que lo motivó.

**5.1 — `HumanAccount`.** `RegisterPhysicalAssetFromDonation` está bloqueado desde Fase 5 (ADR-031) hasta que exista `HumanAccount`. El Golden Path completo, tal como está descrito, **no es ejecutable hoy de extremo a extremo** — describe el producto deseado, no el estado actual.

Esto no reabre Identidad — el contrato de `HumanAccount` ya está cerrado conceptualmente (`identity-resumen.md`, §2). Lo que falta es **implementarlo**: la forma final (`accountId + organizationId + roles efectivos`) ya está decidida; falta escribir el código que lo construye y lo pasa al comando.

**5.2 — Integración de P7 en `PhysicalAssetCommandService` (hallazgo nuevo, independiente del anterior).** Verificado por inspección de código: ninguno de los nueve comandos de `PhysicalAsset` (`REGISTER_PHYSICAL_ASSET`, `REGISTER_PHYSICAL_ASSET_FROM_DONATION`, `SPLIT_PHYSICAL_ASSET`, `DISPATCH_PHYSICAL_ASSET`, `RECEIVE_PHYSICAL_ASSET`, `DELIVER_PHYSICAL_ASSET`) invoca `RoleAuthorizationPolicy`/`OrganizationBoundaryPolicy` hoy. La matriz P7 (ADR-032) está diseñada y decidida como política, pero **nunca se conectó** a `PhysicalAssetCommandService` — que es Fase 1-2, anterior a la existencia de esas políticas (Fase 5). Además, `dispatch`/`receive` ni siquiera están expuestos como métodos públicos del servicio; `deliverAsset` sí está expuesto pero ejecuta sin ningún chequeo de autorización.

`AssetAuthorizationService` (ADR-011, Fase 3) se investigó como posible autorización oculta y se descartó — resuelve pertenencia `assetId→fundId` para lectura pública de tracking, no autorización de comandos de escritura por rol.

**Consecuencia**: incluso resuelto §5.1, los pasos 2, 4 y 5 del Golden Path no tienen hoy la garantía de autorización que el diseño de Convocatoria/Identidad asume. Ambos bloqueadores son independientes — resolver uno no resuelve el otro.

**Acción pendiente, sin nuevo diseño**: integrar `OrganizationBoundaryPolicy → RoleAuthorizationPolicy → (backup ADR-032 cuando aplique) → Aggregate` en cada `PhysicalAssetCommandService`, con tests de autorización positiva y de rechazo — antes de exponer estas operaciones como endpoints HTTP.

**5.3 — `clearFundsForPledge` no está implementado en `FundCommandService`.** Verificado por código: el servicio expone `registerFund`, `clearFundsGenesis`, `confirmAllocation`, `reverseAllocation` — no `clearFundsForPledge`. **No bloquea el Golden Path MVP**, que ya fue corregido (§2, paso 3) para usar `clearFundsGenesis` directo, sin estado `PLEDGED` intermedio. Bloquea únicamente el flujo alternativo de "promesa diferida, confirmación posterior", que queda fuera del camino feliz de esta demo.

`FundCommandService` tampoco integra `RoleAuthorizationPolicy` — mismo patrón que §5.2. No se etiqueta automáticamente como riesgo de seguridad: `registerFund`/`clearFundsGenesis` los invoca previsiblemente un actor externo (webhook de pago), no un rol humano a autorizar. `confirmAllocation`/`reverseAllocation` sí podrían necesitarlo si algún humano los dispara — pendiente de verificar quién los invoca antes de asumir que están exentos.

**5.4 — Canal de entrega del `trackingCode` al donante.** El momento de cálculo ya está resuelto (§2, inmediatamente tras el éxito de `clearFundsGenesis`). Pendiente: mediante qué mecanismo concreto llega al donante (respuesta de una operación iniciada por el frontend, callback/correlación con la pasarela, consulta autenticada posterior, u otro). No se resuelve metiéndolo dentro de `FUNDS_CLEARED` ni convirtiéndolo en un campo de dominio solo para tener dónde guardarlo.

**Nota sobre concurrencia con el trabajo del equipo**: §5.1-§5.4 reflejan el estado del código en el momento en que se inspeccionó durante esta conversación. Fase 5 se está implementando activamente en paralelo — es posible que algunos de estos huecos ya estén cerrados o en proceso al momento de leer este documento. Verificar contra el repositorio real antes de tratarlos como pendientes, no asumir que siguen abiertos.

## 6. Nota de procedencia

Este documento combina decisiones ya cerradas en `convocatoria-resumen.md`, `identity-resumen.md`, `blockchain-resumen.md` e `ia-resumen.md` en un único recorrido concreto. No introduce decisiones de dominio nuevas — solo selecciona, de todo lo ya diseñado, el subconjunto mínimo que forma una historia demostrable de principio a fin, y expone la precondición de implementación que esa selección revela.
