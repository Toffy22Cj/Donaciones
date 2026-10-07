# Propuesta D-P8 — Mensaje de outbox de la génesis de `Fund`

**Estado:** **DECIDIDO — opción A, Carlos, 2026-10-07.** Formalizado como enmienda de ADR-037 §2.3, con una corrección de ADR-045 §1 (con su conformidad) y P8 cerrado en la Enmienda 2. Lo redactó el agente a petición de Carlos.
**Origen:** P8 en `ADR-037-enmienda-2-convocatoria.md` §3 y §7: "ADR-037 §2.3 lo exige, pero hoy `clearFundsGenesis` no escribe ninguno y ningún documento define cuál debe ser. Se decide en `core`."
**Bloquea:** B1 de `plan-cierre-fase6-codigo.md`. Es el inicio de la cadena crítica del dinero: D-P8 → T1/P8 → orquestador de ADR-045 → API → golden path.

---

## 1. Qué exige hoy la documentación

- **ADR-037 §2.3:** `STRICT` y `CLOSE_ON_TARGET` se protegen con una transacción MongoDB compartida que incluye "`CampaignFundingLedger` + `Fund.FUNDS_CLEARED` (append en `core`) + mensaje de Outbox", orquestada desde `app`.
- **ADR-045 §1:** la Tx 2 es "reclamo `APPLY_FUNDS` + incremento del ledger + `clearFundsGenesis` + outbox".
- **Ningún documento dice qué contiene ese mensaje, qué `sagaType` tiene ni quién lo consume.**

## 2. Hechos verificados en el código (`develop`, 2026-10-07)

| Hecho | Evidencia |
|---|---|
| `clearFundsGenesis` llama a `appendAndOutbox(..., List.of(), commandId)`: hoy no escribe outbox | `core/.../FundCommandService.java:94` |
| `appendAndOutbox` escribe el evento, los mensajes y el reclamo de `commandId` en la misma transacción (`@Transactional`, `REQUIRED`), y se une a una transacción externa si existe | `TransactionalEventPublisher.java:25-28` |
| `OutboxMessage` = `messageId`, `sagaType`, `sourceAggregateId`, `correlationId`, `payload` (texto), `status`, `retryCount`, `createdAt`, `nextRetryAt` | `core/.../saga/OutboxMessage.java` |
| El coordinador despacha por `sagaType`. **Un mensaje cuyo `sagaType` no tiene política se ignora en silencio (`continue`)**: no se completa, no se pone en cuarentena y se vuelve a leer en cada ciclo | `OutboxSagaCoordinator.java:42-45` |
| `fetchPendingMessages` lee todos los `PENDING` vencidos, **sin límite** | `MongoOutboxPort.java:31-39` |
| La única política existente es `ASSET_REGISTRATION_SAGA` (`AssetRegisteredSagaPolicy`) | `core/.../saga/` |
| Las proyecciones (`DonationProjection`, `DonationAuditFacts`) se alimentan del `event_store` por Change Stream (ADR-015/017), **no** del outbox | `ProjectionEventSource`; documento maestro §8.4 |
| ADR-007 reserva el outbox para la comunicación **entre streams** (Fund→PhysicalAsset, PhysicalAsset padre→hijo) | documento maestro §5, ADR-007 |

**Consecuencia:** escribir un mensaje de génesis sin una política que lo consuma no es inocuo. Se acumula como `PENDING` para siempre, se relee completo en cada ciclo del coordinador (sin límite) y no tiene salida (regla 2.6).

## 3. ¿Qué necesita hoy un consumidor del outbox después de la génesis?

| Posible consumidor | ¿Necesita outbox? |
|---|---|
| Proyecciones de lectura y `DonationAuditFacts` | No: leen `FUNDS_CLEARED` del Change Stream |
| `CampaignFundingLedger` | No: se actualiza **dentro** de la misma Tx 2 (ADR-045) |
| Asignación Fund→PhysicalAsset | No en la génesis: empieza con `requestAllocation`, que tiene su propio flujo (ADR-012/034) |
| Entrega del `trackingCode` al donante | No con la dirección elegida (Q-v2-7 (a): lectura posterior al checkout, síncrona y sin email). Solo la necesitaría una entrega asíncrona (email), que está descartada por ahora |
| Notificaciones o integraciones externas | No existen ni están diseñadas |

**Hoy no hay ningún consumidor que justifique el mensaje.**

## 4. Opciones

### Opción A — Sin mensaje de outbox en la génesis (recomendada)

- Enmendar ADR-037 §2.3 y la descripción de la Tx 2 en ADR-045 §1 para que la Tx 2 sea: reclamo `APPLY_FUNDS` + ledger + `FUNDS_CLEARED` (con su reclamo de `commandId`). **Sin mensaje de outbox.**
- La atomicidad que §2.3 protege (ledger y `Fund` no divergen) se mantiene íntegra: la da la transacción, no el outbox.
- **Regla añadida:** si en el futuro aparece un consumidor (p. ej. notificación por email), el mensaje y su `SagaPolicy` entran **juntos**, en el mismo PR y con ADR (regla 3.5). Nunca un mensaje sin consumidor.
- **Efecto en B1:** P8 se cierra sin código. B1 queda solo con T1 (variante de `clearFundsGenesis` sin reintento interno y que conserva la causa).
- **Coste:** una enmienda documental pequeña. Cero código.

### Opción B — Mensaje con consumidor definido

- `sagaType` nuevo (p. ej. `FUND_GENESIS_SAGA`) con su `SagaPolicy`. *Payload* mínimo: `fundId`, `campaignRef`, `organizationRef`, `amount`, `currency`, `intentId`. Sin `donorRef` ni PII. `correlationId` = `intentId`.
- Exige definir **qué hace** la política, su compensación (ADR-008) y su idempotencia (ADR-009), y no hay hoy un caso de negocio para ello.
- **Coste:** un ADR o una enmienda, una política nueva y tests. Añade trabajo al camino crítico sin un criterio del golden path que lo pida.

### Opción C — Mensaje sin consumidor, "para el futuro" (desaconsejada)

- Cumple la letra de §2.3, pero crea mensajes `PENDING` eternos que el coordinador relee sin límite (§2). Viola la regla 2.6. **No se recomienda.**

## 5. Recomendación

**Opción A.** El requisito del outbox en §2.3 se escribió antes de conocerse cómo trata el coordinador los `sagaType` desconocidos y sin un consumidor identificado. Lo que §2.3 protege es la atomicidad entre ledger y `Fund`, y eso lo cubre la transacción compartida.

Si el dueño de `core` elige A, queda pendiente:
1. Una enmienda de ADR-037 §2.3 y una nota en ADR-045 §1 (Tx 2 sin outbox). ADR-045 está APROBADO, así que tocar su §1 requiere la conformidad de Carlos.
2. Cerrar P8 en la Enmienda 2 §7.
3. Reducir B1 a T1 en `plan-cierre-fase6-codigo.md`.

**Deuda separada que este análisis revela** (no es P8): `OutboxSagaCoordinator` ignora en silencio los mensajes sin política y `fetchPendingMessages` no tiene límite. Conviene registrarla para `core` (p. ej. cuarentena o alerta de `sagaType` desconocido y lectura por lotes), con independencia de lo que se decida aquí.
