# ADR-048 — Origen del `donorRef`: seudónimo por cuenta o anónimo, nunca enviado por el cliente

**Estado:** **APROBADO — Carlos, 2026-10-07**, con la opción (C), seudónimo aleatorio por cuenta, y la condición de §7. Debe estar **aprobado antes del merge de B6-b** (regla 3.5; `documento-maestro-proyecto.md:450`: poblar `donorRef` desde `accountId` "requiere su propio ADR").
**Origen:** `propuesta-d-api.md` (APROBADO), A5, Q5 y Q6, y la precisión de Carlos sobre privacidad (derecho de supresión, Habeas Data).

---

## 1. Contexto

- **Hoy, `donorRef` lo envía el cliente** en `CreateDonationIntentCommand`, y se guarda tal cual (`DonationIntentService.java:96`). Con el endpoint HTTP de B6 cualquiera podría donar **a nombre de otro**: es un fallo de seguridad, todavía no explotable porque CV-11 no tiene HTTP.
- `donorRef` viaja a **eventos inmutables**: `FUNDS_CLEARED` (vía `FundsApplicationOrchestrator.java:130`), y a `PhysicalAsset` en el Camino B y en las divisiones.
- Criterios 4 y 6 del golden path: donación con cuenta, visible en su historial (`GET /account/donations`, Q6).
- **Precisión de Carlos:** con `donorRef = "acct:" + accountId`, ese id quedaría grabado para siempre en eventos que no se pueden borrar. Si una persona ejerce su derecho de supresión, no se podría quitar.

## 2. Opciones

| | (A) `accountId` en claro | (B) HMAC del `accountId` con una clave global | (C) **Seudónimo aleatorio por cuenta, guardado en `identity`** |
|---|---|---|---|
| Valor | `acct:` + `accountId` | `acct:` + HMAC(clave, `accountId`) | `acct:` + UUID aleatorio, generado la primera vez y guardado en la cuenta |
| Supresión | **Imposible**: el id queda en eventos inmutables | Parcial: tras borrar la cuenta, nadie tiene el `accountId` para recalcular el HMAC; pero si alguien conserva el id, puede volver a enlazarlo mientras exista la clave. Borrar la clave rompe a **todos** los donantes | **Completa por destrucción del vínculo** (*crypto-shredding*): borrar el seudónimo de la cuenta deja el `donorRef` de los eventos sin enlace con ninguna persona |
| Coste | Ninguno | Una clave y su custodia | Un campo en la cuenta y un puerto en `contracts` |
| `GET /account/donations` | Por `donorRef` | Por `donorRef` recalculado | Por `donorRef` leído de la cuenta |

## 3. Decisión propuesta: (C)

- **Con JWT válido** (CV-11 es de JWT opcional): `donorRef = "acct:" + donorPseudonym`.
  - `donorPseudonym` es un UUID aleatorio de la cuenta, generado la primera vez que dona y guardado en `identity`.
  - Puerto nuevo en `contracts`: `DonorPseudonymPort.pseudonymFor(accountId)` (obtener o crear, idempotente).
- **Sin JWT:** `donorRef = "anon:" + UUID aleatorio`, el "opaco efímero" de `golden-path.md:50`. No se guarda en ningún sitio que lo relacione con una persona.
- **El cliente nunca envía `donorRef`.** El campo desaparece de la petición de CV-11; si llega, se ignora y no se usa.
- **`GET /api/v1/account/donations`** (JWT obligatorio): lee el seudónimo de la cuenta y lista las intenciones con ese `donorRef` (índice nuevo sobre `donorRef` en `convocatoria`), con el estado financiero de `DonationReadPort.findByFundId` y el `trackingCode` derivado (Enmienda 3 de ADR-037, D6).
- **Supresión:** borrar el `donorPseudonym` de la cuenta. Después, sus donaciones dejan de aparecer en `/account/donations` y el `donorRef` de los eventos queda como un dato anónimo. El procedimiento operativo de supresión queda fuera de este corte, pero el diseño ya lo permite.
- **Si Carlos prefiere (A)** para este corte: queda escrito como **riesgo aceptado con su nombre** (el `accountId` no se podrá suprimir de los eventos), según su precisión.

## 4. Consecuencias

- `identity` gana `donorPseudonym` en la cuenta y una implementación de `DonorPseudonymPort`.
- `convocatoria`: `CreateDonationIntentCommand` deja de recibir `donorRef` del exterior; lo recibe ya resuelto desde `app`, que lo calcula con el principal del JWT. Además, índice sobre `donorRef`.
- No cambia ninguna versión de evento: `donorRef` sigue siendo un texto opaco.
- Los `donorRef` ya existentes no importan: no hay datos reales.

## 5. Verificación (en B6-b)

- Con JWT: la intención guarda `acct:` + el seudónimo de esa cuenta; dos donaciones de la misma cuenta dan el mismo `donorRef`; otra cuenta da otro.
- Sin JWT: `anon:` + un UUID nuevo en cada intención.
- Un `donorRef` enviado por el cliente no se usa (test de suplantación).
- `GET /account/donations` muestra solo las de esa cuenta; sin JWT → 401.
- Borrar el seudónimo: el historial queda vacío y los eventos no cambian.
- Mutación: usar el `donorRef` del cliente → el test de suplantación falla.

## 6. Preguntas para Carlos

| # | Pregunta | Recomendación |
|---|---|---|
| A48-Q1 | ¿(C), seudónimo aleatorio por cuenta guardado en `identity`? | Sí: es la única opción con supresión completa y sin una clave que custodiar |
| A48-Q2 | Si no (C), ¿(A) con riesgo aceptado a tu nombre, o (B)? | — |

## 7. Decisión de Carlos (2026-10-07)

**(C) aprobada.** Con un HMAC de clave global (B), quien conserve el id puede volver a enlazar las donaciones mientras exista la clave, y borrar la clave rompe los historiales de todos. Con (C), borrar el seudónimo de una persona la desvincula solo a ella, y los eventos no cambian.

**Condición:** la relación cuenta ↔ seudónimo pasa a ser **el dato más sensible del sistema**:
- **solo `identity` la lee**. `DonorPseudonymPort` devuelve el seudónimo de **una** cuenta a `app`, para calcular el `donorRef`; no hay ninguna operación que vaya del seudónimo a la cuenta, ni que liste la relación;
- **nunca sale por la API**: ningún DTO ni respuesta contiene `donorPseudonym` ni el `donorRef` con prefijo `acct:`;
- **nunca va a los logs**.

Tests en B6-b: una regla ArchUnit que impide leer el campo fuera de `identity`; ninguna respuesta contiene el seudónimo; captura de logs sin el seudónimo.
