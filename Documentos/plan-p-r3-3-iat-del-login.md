# Plan P-R3-3 — El login emite con `iat = max(segundo actual truncado, credentialsNotBefore)`

**Estado: BORRADOR. Espera el "plan aprobado" de Manuel**, por la delegación de Carlos del 2026-10-24 (`decisiones-delegadas-2026-10.md`, nota del 2026-10-24). No se escribe código antes (regla 3.4). Carlos aprueba el PR de implementación con Approve en GitHub.

> **Nota (2026-10-25): APROBADO por Carlos** (`decisiones-delegadas-2026-10.md`, nota del 2026-10-25: "Carlos aprobó todo lo pendiente …", comunicado por Manuel; texto literal pendiente de registrar). El estado de arriba se conserva; esta nota lo supera. **B-1 queda resuelto:** la Enmienda 1 de ADR-050, parte 1, está aprobada (misma nota). Los puntos P-PR33-1 a P-PR33-5 se aplican como los recomienda el plan.
**Fecha:** 2026-10-24 (UTC).
**Contrato:** `ADR-050-enmienda-1-iat-del-login.md` (E1-1, E1-2 y su definición de hecho), que enmienda ADR-050 D4. Este plan no decide nada que la enmienda no fije, salvo los puntos marcados **[Plan]**, que se aprueban con el plan.
**Origen:** plan R-3 + R-2 §10.2 (`plan-r3-r2-revocacion-y-cambio-de-contrasena.md:199-208`): opción (a) con (c); (b) descartada por seguridad.
**Base:** la rama `feat/identity-r3-r2-revocacion-contrasena` (`bf2ea21`, PR #127) mientras #127 siga abierto, y `develop` cuando se fusione (regla de `plan-ejecucion-cierre-manuel-2026-10-24.md:74`). Rama: `fix/identity-p-r3-3-iat-del-login` **[Plan]**.
**Dueño:** Manuel.

## 0. Dependencias y bloqueos

| # | Dependencia | Estado al redactar | Efecto |
|---|---|---|---|
| **B-1** | **Enmienda 1 de ADR-050, parte 1** (E1-1 y E1-2). `ADR-050-enmienda-1-iat-del-login.md:5` dice "PROPUESTA — espera la aprobación de Carlos". #120 la llevó a `develop`, pero se fusionó sin review ni Approve (H-1) | Pregunta abierta para Carlos: ¿la fusión de #120 cuenta como aprobación? | **No se escribe código hasta que Carlos lo confirme**, aunque Manuel apruebe este plan. La regla 3.5 lo exige, porque el cambio toca `AuthenticateAccountPort`, que está en `contracts` y usan `api` e `identity` |
| **B-2** | R-3 + R-2 (#127): `credentialsNotBefore`, `TokenIssuerPort.issue(accountId, issuedAt)` y `AccountPasswordHttpIntegrationTest` | Abierto, en verde, espera a Carlos | La rama sale de la de #127 y el PR dice "depende de #127". Cuando #127 se fusione, se integra `develop` sin forzar y se repite `ci-local.sh` |
| **B-3** | R-5, D3 (P-R5-1 en #122): la opción (a) recomendada cambia también `AuthenticateAccountPort.authenticate` (versiones aceptadas y `AcceptanceRequiredException`) | Plan R-5 en BORRADOR; P-R5-1 sin decidir | Este plan va **antes** que D3. Si P-R5-1 queda en (a), R-5 parte del tipo de retorno que define este plan y vuelve a pasar por la regla 3.5. Este plan no anticipa nada de R-5 |
| **B-4** | R-4 (#121, APROBADO) | Espera a B-1, B-2 y a este PR | **El código de P-R3-3 se fusiona antes que el de R-4** (`ADR-050-enmienda-1-iat-del-login.md:7`) |

## 1. Alcance

Dentro (enmienda E1-1 y E1-2):
- `AuthenticateAccountPort.authenticate(email, password)` devuelve `{accountId, credentialsNotBefore}` en lugar de `String`. **Cambia la firma; no se añade un método** (E1-1).
- `credentialsNotBefore` se lee del mismo `Account` que validó la contraseña, en la misma lectura (`findByEmail`). Es nulo si la cuenta no lo tiene.
- `LoginController` emite con `tokenIssuerPort.issue(accountId, iat)`, donde `iat = max(segundo actual truncado, credentialsNotBefore)`. Si `credentialsNotBefore` es nulo, `iat` es el segundo actual. `exp = iat + ttl` (D5).
- Se quita el avance de un segundo de `deactivateReactivate_andAPreviousTokenIs401` (enmienda, test 4).
- Nota fechada en `referencia-api-v1.md` que da por corregido el defecto conocido. El texto anterior no se borra.

Fuera:
- El login con Google (R-6); la enmienda deja la misma regla a su plan.
- R-5 / D3 (B-3).
- R-4.
- Cualquier cambio en el filtro, en el verificador o en `TokenIssuerPort`.

## 2. Hechos verificados en la rama de #127 (`bf2ea21`, 2026-10-24)

- `contracts/src/main/java/com/traceability/contracts/authentication/AuthenticateAccountPort.java:13`: `String authenticate(String email, String password)`.
- `api/src/main/java/com/traceability/api/auth/LoginController.java`:
  - `:55`: `accountId = authenticateAccountPort.authenticate(...)`;
  - `:62`: `tokenIssuerPort.issue(accountId)`;
  - el constructor (`:42`) recibe solo los dos puertos y no tiene `Clock`.
- `identity/src/main/java/identity/application/service/AuthenticateAccountPortImpl.java`:
  - una lectura (`:39`, `findByEmail`);
  - `matches` siempre, contra un hash ficticio si la cuenta no existe (`:41`, ADR-047 Q5);
  - el estado se comprueba después (`:43`);
  - se devuelve solo el id (`:46`).
- **Consumidores del puerto** (`git grep` en la rama): `LoginController` y `LoginControllerTest` (`api`), `AuthenticateAccountPortImpl` y `AuthenticateAccountPortImplTest` (`identity`). Ni `core`, ni `convocatoria`, ni `app` lo usan directamente. Coincide con la enmienda.
- `NimbusTokenIssuer`:
  - ya tiene `issue(String accountId, Instant issuedAt)`;
  - toma el reloj de la aplicación con `ObjectProvider<Clock>` (R-3 + R-2, P-R3-2);
  - conserva un constructor de paquete con `Clock` para los tests.
- `JwtTokenVerifier` no valida un `iat` futuro (ADR-047 D5; ADR-050 D4 lo da por aceptado).
- Tests HTTP que inician sesión: `AuthenticationEndToEndIntegrationTest`, `PathEvasionRealServerIntegrationTest`, `PublicRoutesInventoryIntegrationTest`, `DemoScenarioSeederIntegrationTest`, `DemoSeedRunnerIntegrationTest`, `P2EndpointsHttpIntegrationTest` y `AccountPasswordHttpIntegrationTest`. Usan el login real, no el puerto, así que no cambian.

## 3. Cambios por módulo

### `contracts`

| Archivo | Cambio | Fuente |
|---|---|---|
| `authentication/AuthenticatedAccount.java` (nuevo) **[Plan]** | `record AuthenticatedAccount(String accountId, Instant credentialsNotBefore)`, con `credentialsNotBefore` anulable | E1-1 |
| `authentication/AuthenticateAccountPort.java` | `AuthenticatedAccount authenticate(String email, String password)`. El Javadoc de los fallos (ID01-D1) no cambia | E1-1 |

### `identity`

| Archivo | Cambio | Fuente |
|---|---|---|
| `application/service/AuthenticateAccountPortImpl.java` | Devuelve `new AuthenticatedAccount(id, account.getCredentialsNotBefore())` del mismo `Account` de `:39`. No cambia el orden `findByEmail → matches → estado` ni el hash ficticio | E1-1; ADR-047 Q5 |

### `api`

| Archivo | Cambio | Fuente |
|---|---|---|
| `auth/LoginController.java` | Reloj por `ObjectProvider<Clock>`, como `NimbusTokenIssuer`, más un constructor de paquete con `Clock` para los tests **[Plan]**. `iat = max(clock.instant().truncatedTo(SECONDS), cnb)` cuando `cnb` no es nulo, y si no el segundo truncado. Emite con `issue(accountId, iat)` | E1-2 |

### `app`

| Archivo | Cambio | Fuente |
|---|---|---|
| `test/.../account/AccountPasswordHttpIntegrationTest.java` | Se quita `TestBeans.CLOCK.pinned = t.plusSeconds(1)` de `deactivateReactivate_andAPreviousTokenIs401` (enmienda, test 4), y se añaden los tests 1 y 2 de la enmienda (§5) | Enmienda, definición de hecho |

### Documentos

- `referencia-api-v1.md`: nota fechada "Defecto P-R3-3 corregido en …". El párrafo del defecto conocido se conserva.
- `estado-fase6.md`: sección nueva.
- `ADR-050-enmienda-1-iat-del-login.md`: nota fechada "implementada en …", solo después de que Carlos la apruebe (B-1).

## 4. Comportamiento ante fallos

- Los fallos de autenticación no cambian: la misma `AuthenticationFailedException`, el mismo 401 y el mismo coste (ID01-D1, ADR-047 Q5).
- Un fallo al emitir sigue dando 500, nunca 401 (ID01-D4).
- **[Plan]** El cálculo del `iat` va dentro del mismo `try` que la emisión (`LoginController.java:61-66`). Así, un fallo inesperado ahí también da 500, nunca 401.

## 5. Tests (rojos primero)

Primero los de la enmienda (definición de hecho 1 a 8):

| # | Test | Módulo | Rojo esperado |
|---|---|---|---|
| E-1 | Con el reloj fijo: cambiar la contraseña y, en el mismo segundo, iniciar sesión con la nueva → el token funciona (200) | `app` (`AccountPasswordHttpIntegrationTest`) | 401 (aserción) |
| E-2 | Dos cambios seguidos y un login inmediato → el token funciona | `app` | 401 (aserción) |
| E-3 | Negativa: el token anterior al cambio sigue en 401 | `app` (ya existe: `afterTheChange_…`) | No es rojo; es la guarda |
| E-4 | `deactivateReactivate_andAPreviousTokenIs401` sin el avance de un segundo: el login del mismo segundo funciona | `app` | 401 (aserción) |
| E-5 | Cuenta sin `credentialsNotBefore` → el `iat` es el segundo actual | `api` (`LoginControllerTest`) | Compilación (firma nueva) |
| E-6 | Los fallos del login siguen siendo el mismo 401 (los tests existentes de `LoginControllerTest` y `AuthenticateAccountPortImplTest`, adaptados solo al tipo de retorno) | `api`, `identity` | Compilación |
| E-7 | Mutaciones (§6) | — | — |
| E-8 | `GoldenPathHttpIntegrationTest` y `scripts/ci-local.sh` en verde en un clon limpio | todos | — |

Tests unitarios **[Plan]**:
- **L-1** (`LoginControllerTest`): `cnb` en el futuro → `issue("acc-1", cnb)`.
- **L-2:** `cnb` en el pasado → `issue("acc-1", segundo actual truncado)`. Mata `iat = cnb` sin `max`.
- **L-3:** `cnb` nulo → `issue("acc-1", segundo actual truncado)`, con el reloj a mitad de segundo.
- **L-4:** nunca se llama a `issue(String)` (`verify(never())`).
- **A-1** (`AuthenticateAccountPortImplTest`): devuelve el `credentialsNotBefore` de la cuenta autenticada, y nulo si no lo tiene.
- **A-2:** el test de coste existente no cambia (ADR-047 Q5).

## 6. Mutaciones (`scripts/mutaciones.py`)

En el clon limpio, antes hay que instalar con sus tests los módulos que no se mutan (`mvn install -pl .,contracts,core,crypto,ai,convocatoria`) y pasar la línea base en verde, como en #127. Si no, una dependencia ausente cuenta como mutación muerta.

| # | Mutación | Debe morir en |
|---|---|---|
| 1 | El login emite con `issue(accountId)`, ignorando `cnb` (enmienda, test 7) | L-1, L-4, E-1, E-2, E-4 |
| 2 | `AuthenticateAccountPortImpl` devuelve siempre `credentialsNotBefore = null` | A-1, E-1, E-2, E-4 |
| 3 | `iat = cnb` cuando no es nulo, sin `max` | L-2 |
| 4 | `max` cambiado por `min` | L-1, E-1 |
| 5 | Sin truncar el segundo actual | L-3 |

La mutación 5 puede sobrevivir si el emisor trunca de todos modos al serializar (`NumericDate` en segundos), y L-3 la mata solo si comprueba el `Instant` que se pasa a `issue`. Si sobrevive, se dice y se razona; no se fuerza.

## 7. Pasos

1. Confirmación de Carlos sobre B-1. Sin ella no se empieza.
2. Rama desde `feat/identity-r3-r2-revocacion-contrasena`, o desde `develop` si #127 ya se fusionó.
3. Tests en rojo (§5), con su resumen de Surefire.
4. Código (§3).
5. Verde por capas: `mvn test -pl contracts`, `identity`, `api` y `app`, cada uno con su resumen literal.
6. Mutaciones (§6).
7. `free -g` con al menos 4 GB y `scripts/ci-local.sh` en un clon limpio. La evidencia va a `Documentos/evidencia-ci/`.
8. Autoauditoría contra E1-1, E1-2 y los tests 1 a 8 de la enmienda: tabla "norma → lo hecho → coincide" en el PR.
9. PR hacia `develop` (o hacia la rama de #127, si sigue abierto, con "depende de #127"), con Carlos de revisor. No se fusiona sin su Approve.

## 8. Puntos para el "plan aprobado"

| Punto | Recomendación |
|---|---|
| **P-PR33-1** | Nombre y sitio del tipo de retorno: `AuthenticatedAccount` en `contracts.authentication` **[Plan]** |
| **P-PR33-2** | El reloj del login por `ObjectProvider<Clock>`, como `NimbusTokenIssuer`, para que los tests HTTP con reloj fijo (E-1, E-2, E-4) controlen el `iat` |
| **P-PR33-3** | El `max` se calcula en `LoginController` (E1-2 lo sitúa ahí), no en `identity` ni en el emisor |
| **P-PR33-4** | El cálculo del `iat` va dentro del `try` de la emisión (§4) |
| **P-PR33-5** | Base: la rama de #127 mientras esté abierto |

## 9. Riesgos

- **R-PR33-1:** tras varios cambios seguidos, el token del login puede salir con `iat` unos segundos en el futuro y `exp` unos segundos más tarde. D4 y D5 ya lo aceptan.
- **R-PR33-2:** colisión con R-5 (B-3): si los dos tocan el puerto en paralelo, hay conflicto de firma. Se evita con el orden: primero P-R3-3.
- **R-PR33-3:** no se cambia el filtro ni el verificador. Un cliente con un reloj muy desviado no notará nada nuevo, porque el servidor no valida `iat` futuro.
