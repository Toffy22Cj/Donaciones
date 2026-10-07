# Contract Wiring Review (no congelado)

**Estado:** Revisión de las uniones entre `api-contract-matrix.md`/Frontend Contract y los contratos de dominio/aplicación reales. No reabre ningún documento conceptual cerrado (`convocatoria-resumen.md`, `identity-resumen.md`, `blockchain-resumen.md`, `ia-resumen.md`, `golden-path.md`) — hace visibles las dependencias que esos documentos, correctamente, no necesitaban resolver en su momento.
**Para qué sirve:** distinguir "el frontend puede diseñarse" de "el backend puede implementarse tal cual" antes de tocar el Dataset.

---

## Categorías

- **CERRADO Y SOPORTADO**: contrato conceptual y camino de implementación sin dependencias ocultas.
- **CERRADO PERO FALTA MATERIALIZAR**: la decisión ya está tomada; falta el puerto/adaptador/application service concreto.
- **CONTRATO REALMENTE FALTANTE**: ninguna decisión previa lo resuelve; necesita diseño nuevo antes de implementar.

## Cerrado y soportado

- Identidad/JWT como frontera conceptual (`AuthenticateAccountPort`, `IdentityPrincipalPort`, `TokenIssuerPort`).
- `DonationReadPort` (Fase 3, existente).
- Tracking (`GET /tracking/{trackingCode}` y narrativa, Fase 3, existente).
- Estados y transiciones del Aggregate `PhysicalAsset`.
- `RoleAuthorizationPolicy`/`OrganizationBoundaryPolicy` como mecanismo (aunque su integración con `PhysicalAssetCommandService` está pendiente — ver abajo).
- Event Sourcing de `Fund` (`FundCommandService.registerFund`/`clearFundsGenesis`, existente).
- `Convocatoria` + `CampaignFundingLedger` como modelo CRUD.

## Cerrado pero falta materializar (P1-P5 de la revisión)

### P1 — Convocatoria: application contracts
Verificar/definir en implementación: `CreateConvocatoria`, `AssignEmployee`, `ConvocatoriaReadPort` (público, ya diseñado), `ConvocatoriaAdminReadPort` (nuevo), contrato de lectura/actualización de `CampaignFundingLedger`.

### P2 — Identity read integration (Hallazgo 1)
`ConvocatoriaAdminReadModel` necesita `responsables{accountId, fullName}`, pero `IdentityPrincipalPort.resolve(accountId)` resuelve un solo principal autenticado — no es una consulta de miembros de organización. `convocatoria → contracts` es la única dependencia directa permitida; no se resuelve importando `identity`.

**Solución propuesta, mismo patrón que ya usamos para `CampaignFundingLedger↔Fund`**: la composición vive en `app`, mediante un contrato de lectura de Identity todavía por definir (ej. `OrganizationMembersReadPort.findMembers(organizationId): List<{accountId, fullName, roles}>`) — nuevo puerto, no una ampliación de `IdentityPrincipalPort` (que debe seguir teniendo una única responsabilidad: resolver el principal autenticado).

### P4 — PhysicalAsset read contract (Hallazgo 3)
`PhysicalAssetOperationalReadModel` está definido (campos, exclusiones) en `api-contract-matrix.md` §4b, pero no existe `PhysicalAssetOperationalReadPort` ni su adaptador. No reutilizar `AssetHistoryProjection` — tiene otra finalidad (historial público/tracking, no vista operacional interna del empleado).

### P5 — PhysicalAsset command wiring (Hallazgo 4)
Extender de forma consistente, para `DISPATCH_PHYSICAL_ASSET`/`RECEIVE_PHYSICAL_ASSET`/`DELIVER_PHYSICAL_ASSET`:
```
CommandType → RoleAuthorizationPolicy → PhysicalAssetCommandService → Aggregate
```
Incluyendo la política de respaldo de `REPRESENTATIVE` (enmienda ADR-032) donde corresponda. Mismo hallazgo ya registrado en `golden-path.md` §5.2 — aquí se enumera como pieza de trabajo, no se redecide.

## Contrato realmente faltante

### P3 — Payment correlation (Hallazgo 2 — el más serio)
`clearFundsGenesis` necesita `organizationRef`, `campaignRef`, `donorRef`, `currency`, `amount`, `commandId`, `actorRef`. El webhook de pago recibe un evento externo que no trae estos datos de forma nativa — necesita:
```
donationIntent → paymentSessionId → evento externo del proveedor
    → correlación con campaignRef + organizationRef + donorRef
    → clearFundsGenesis(...)
```
La derivación de `organizationRef` para `ExternalActor` quedó pendiente desde Fase 5 (identificada junto a `HumanAccount` como los dos huecos gemelos de esa fase) — **no se resuelve inventando el dato dentro del adaptador del webhook**. Es el bloqueador más serio del Golden Path porque afecta la primera donación real, antes que cualquier pieza de `PhysicalAsset`.

### Consulta de estado de payment session
Ya identificado en `golden-path.md` §5.4/`api-contract-matrix.md` — contrato semántico cerrado (`PENDING/CONFIRMED/FAILED/EXPIRED-UNKNOWN`), HTTP concreto pendiente del proveedor de pago (no elegido).

### Verificación de email
Sin diseñar, ya registrado en `identity-resumen.md`.

## Precisión sobre el descubrimiento público (no es una inconsistencia, es la jerarquía ya aplicada en todo el proyecto)

`convocatoria-resumen.md` confirma la **necesidad de negocio** del panel de descubrimiento. `api-contract-matrix.md` marca el **contrato HTTP concreto** (`listPublicOpen`, paginación, filtros) como no detallado. Mismo tratamiento que `dispatch`/`receive`: necesidad confirmada ≠ contrato HTTP cerrado. No se corrige — es la distinción correcta.

## P6 — Audit de actores (verificación transversal, no bloqueante)

Confirmar en cada punto de entrada que el actor correcto llega al evento:
```
HTTP humano (empleado, admin)  → HumanAccount
Payment webhook                → ExternalActor
Saga interna                   → SystemActor
```
Coherente con la taxonomía cerrada de ADR-031 — verificación de wiring, no nueva decisión.

## Próximo paso

El orden de resolución, por severidad: **P3 (correlación de pago) primero** — bloquea la primera donación real y es anterior a cualquier trabajo de `PhysicalAsset`. P2, P4, P5 pueden avanzar en paralelo una vez resuelto P3, ya que no dependen entre sí. El Dataset sigue esperando al final, como ya se decidió — diseñar datos reales antes de estabilizar estos contratos sería, como bien se dijo, decorar una casa sin saber dónde están los enchufes.
