# ADR-037 — Enmienda 5: fecha de creación de `DonationIntent` y vista del historial del donante (A2)

## Status

**PROPUESTO — espera la aprobación de Carlos** (ADR-037 es de su módulo). Redactada el 2026-10-21 por Manuel, con asistencia de Claude. **No autoriza código:** la tarea A2 necesita además su plan aprobado (regla 3.4).

**Por qué es una enmienda.** La regla 3.5 la exige literalmente cuando se "cambie el contrato de un puerto ya usado por otro módulo". `DonationIntentReadPort` es de `convocatoria` y lo usa `app` (`AccountDonationsController.java:27`, `DonationIntentController.java:46`). Manuel eligió la opción (a) de Q-A2-C el 2026-10-21 (`ficha-lecturas-fronts-M07-M15-M26.md` §5).

**Enmienda a:** ADR-037 (Convocatoria, ledger, asignación y `DonationIntent`), con sus enmiendas 1 a 4.
**Origen:** `ficha-lecturas-fronts-M07-M15-M26.md`, A2 (M-15/M-16): decisiones Q-A2-1 a Q-A2-5 (§8, Manuel, 2026-10-18T06:35Z).

Solo recoge lo decidido en Q-A2-1 a Q-A2-5. Las marcas **[Propuesta]** señalan detalles que esas decisiones no fijan y que esta enmienda propone a Carlos.

## Context

### Lo decidido en la ficha L-FRONTS (§8) y que esta enmienda registra

| # | Decisión |
|---|---|
| Q-A2-1 | **(c)** `createdAt?` (nuevo) y `confirmedAt?` (existente) |
| Q-A2-2 | **(a)** `organizationName?` |
| Q-A2-3 | **(a)** `paymentMethod`: los literales `GATEWAY`, `BANK_TRANSFER` y `CASH` pasan a ser contrato público |
| Q-A2-4 | **(b)** `publicCode` solo si la convocatoria es `PUBLIC`, **según su visibilidad actual al leer**; las `PRIVATE_LINK` nunca lo traen (ADR-041 §2.7) |
| Q-A2-5 | **(a)** Orden por `createdAt` descendente, con las antiguas sin fecha al final (enmienda de DD-21) |

### Hechos verificados en el código (`develop` `fdccd99`, 2026-10-21)

- **`DonationIntent` es un documento, no un agregado con eventos.**
  - Se persiste como `DonationIntentDocument` (`@Document`, colección `donation_intents`; `convocatoria/.../infrastructure/persistence/mongo/document/DonationIntentDocument.java:14`), con su mapper (`DonationIntentMapper.java:17` y `:61`).
  - `convocatoria` solo depende de `contracts` (`convocatoria/pom.xml:19`), así que no puede escribir en el Event Store de `core`.
  - Por tanto, añadir un campo **no cambia ningún payload** de evento.
- **`DonationIntent` no guarda su instante de creación.**
  - Ningún campo lo hace (`DonationIntent.java:18-36`), y `create` no lo recibe (`:71`, `:95`).
  - El instante ya se calcula al crear (`Instant now = clock.instant()`, `DonationIntentService.java:131`), pero solo se usa para `expiresAt` y la caducidad del token.
- **`confirmedAt` ya existe**, en `Confirmation(confirmedBy, confirmedAt, paymentMethod, reference)` (`DonationIntent.java:222`). Se persiste en `DonationIntentDocument.java:60` y está presente en `CONFIRMED` y en `FUNDING_REJECTED` (ficha L-FRONTS §3.2).
- **La vista de hoy** es `DonationView(intentId, campaignTitle, amount, currency, status, fundId, fundsAppliedAt)` (`DonationIntentReadPort.java:22-23`). La arma `DonationIntentQueryService.java:46-50`, con una lectura de la convocatoria por intención (`:47`).
- **`organizationRef` y `paymentMethod`** están en el dominio (`DonationIntent.java:20`, `:25`; getters `:178`, `:183`).
- **El orden de hoy** es por `_id` (`MongoDonationIntentRepositoryAdapter.java:258-263`), que es `intentId`, un UUID aleatorio (`DonationIntentService.java:134`). No es cronológico (hallazgo H-L2).
- **`organizationName` viene de `identity`, no de este puerto.**
  - El nombre público lo da `OrganizationPublicNamePort` (`contracts/.../organization/OrganizationPublicNamePort.java:10`), implementado en `identity` (`OrganizationPublicNameQuery.java:17`, `:26`) y consumido en `app` (`PublicCampaignController.java:20`).
  - `convocatoria` no depende de `identity`. La vista solo lleva el `organizationRef`, y `app` resuelve el nombre.

## Decision

### E5-1. `createdAt` de solo lectura en `DonationIntent`

- `DonationIntent` gana `createdAt` (`Instant`): se fija **una vez, al crear**, con el reloj del servidor, el mismo `now` de `DonationIntentService.java:131`. Ninguna transición lo cambia.
- Se guarda en `DonationIntentDocument` y pasa por el mapper en los dos sentidos.
- **Las intenciones existentes quedan sin él.** No se rellena de forma retroactiva: ningún valor sería verdadero (ni `confirmedAt` ni el ULID valen como fecha de creación). En la lectura, `createdAt` es nulo para ellas.
- **[Propuesta]** Ni la creación ni la rehidratación validan `createdAt`, salvo que no sea nulo en las intenciones nuevas. Ninguna regla de negocio lo usa.

### E5-2. Lo que gana `DonationView` en `DonationIntentReadPort`

Solo lo que necesita A2:

| Campo nuevo | Tipo | Origen | Decisión |
|---|---|---|---|
| `createdAt` | `Instant`, nulo en las antiguas | E5-1 | Q-A2-1 |
| `confirmedAt` | `Instant`, nulo si no hay `confirmation` | `Confirmation.confirmedAt` | Q-A2-1 |
| `organizationRef` | `String` | `DonationIntent.organizationRef` | Q-A2-2: `app` lo convierte en `organizationName?` con `OrganizationPublicNamePort` y **nunca lo devuelve** por HTTP |
| `paymentMethod` | `String` (`GATEWAY` \| `BANK_TRANSFER` \| `CASH`) | `DonationIntent.paymentMethod` | Q-A2-3 |
| `publicCode` | `String`, nulo salvo si la convocatoria es `PUBLIC` | la convocatoria, leída en la misma consulta que el título (`DonationIntentQueryService.java:47`) | Q-A2-4 |

- **[Propuesta] El filtro del `publicCode` se aplica dentro de `convocatoria`.** La vista lleva el `publicCode` solo cuando la visibilidad **actual** de la convocatoria es `PUBLIC`, y nulo en otro caso. Así el código de una `PRIVATE_LINK`, que es un secreto *bearer* (ADR-041 §2.7), nunca cruza el puerto, y la vista no necesita llevar la visibilidad.
- **Orden de `findByDonorRef`:** `createdAt` descendente. Las intenciones sin `createdAt` van al final, en el orden de hoy (`_id`). Esto enmienda DD-21 en el orden; el tope de 100 sigue igual. Como el tope se aplica después de ordenar, la página pasa a traer **las 100 más recientes**.
- `IntentStatusView` y `findForStatusToken` **no cambian**.

### E5-3. Lo que no cambia

- **Invariantes y transiciones** de `DonationIntent`. Las de ADR-037 §2.6 y §2.6bis y las de las enmiendas 1 a 4 siguen intactas: `createdAt` no interviene en ninguna.
- **Eventos y cadena.** No hay eventos nuevos ni payloads cambiados, y la canonicalización, el hash, Merkle y el anclaje no se tocan. `DonationIntent` no está en el Event Store.
- **Privacidad del historial.** Ninguna vista lleva el `donorRef` (`DonationIntentReadPort.java:8-9`), y la respuesta HTTP no lleva el `organizationRef`.
- Los campos que ya existen en `DonationView`, y su significado.

## Alternatives

| Alternativa | Por qué no |
|---|---|
| Opción (b) de Q-A2-C: decisión expresa de Carlos de que un campo de solo lectura no requiere enmienda | Descartada por Manuel el 2026-10-21: la regla 3.5 lo pide literalmente para un cambio en el contrato de un puerto usado por otro módulo |
| Rellenar `createdAt` en las intenciones existentes con `confirmedAt` o con otro instante | Sería un dato falso: la creación y la confirmación son hechos distintos, y las `PENDING` o `FAILED` antiguas no tienen ninguno |
| Llevar la visibilidad y el `publicCode` siempre en la vista y filtrar en `app` | El secreto de una `PRIVATE_LINK` saldría de `convocatoria` sin necesidad; un fallo del filtro en `app` lo expondría |
| Resolver `organizationName` dentro de `convocatoria` | `convocatoria` no depende de `identity`, y añadir esa dependencia sería un cambio de módulos mayor que el problema |

## Consequences

- `DonationIntent`, `DonationIntentDocument`, `DonationIntentMapper`, `DonationIntentReadPort.DonationView`, `DonationIntentQueryService` y el orden de `MongoDonationIntentRepositoryAdapter.findByDonorRef` cambian de forma aditiva. `AccountDonationsController` y `DonationDtos.AccountDonation` ganan los campos de la respuesta, que fija la ficha L-FRONTS.
- **DD-21** se enmienda en el orden: pasa de "ordenada por `intentId`" a "por `createdAt` descendente, con las antiguas al final". Se registra con una nota fechada en `decisiones-delegadas-2026-10.md`, sin reescribir la fila.
- El historial ya hace una lectura de convocatoria por intención; la de `identity` por intención es la que aceptó Manuel en el hallazgo H-L4 (como mucho 100).
- **Índice:** hoy solo existe el de `donorRef`. El plan de A2 decide, con datos, si hace falta un índice compuesto `(donorRef, createdAt)`; esta enmienda no lo fija.
- La ficha L-FRONTS pasa A2 a CONGELADO cuando esta enmienda se apruebe (§9 de la ficha).

## Definición de hecho (tests, rojos primero, en el PR de A2)

1. Crear una intención fija `createdAt` con el reloj del servidor. Ninguna transición lo cambia: confirmar, fallar, caducar, `FUNDING_REJECTED`.
2. Una intención guardada sin `createdAt` (anterior a esta enmienda) se rehidrata y se lee con `createdAt` nulo.
3. `DonationView` lleva `confirmedAt` en `CONFIRMED` y en `FUNDING_REJECTED`, y nulo en `PENDING`, `FAILED` y `EXPIRED_UNKNOWN`.
4. `publicCode` presente si la convocatoria es `PUBLIC` al leer, y nulo si es `PRIVATE_LINK`, también cuando la visibilidad cambió después de donar.
5. Orden: las nuevas por `createdAt` descendente y las antiguas sin fecha al final; con más de 100, la página trae las 100 más recientes.
6. Ninguna respuesta HTTP lleva `organizationRef` ni `donorRef` (aserción negativa).
7. Ningún evento nuevo en el Event Store al crear o leer una intención (aserción negativa).
