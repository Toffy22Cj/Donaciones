# Plan R-3 + R-2 — Revocación de sesiones y cambio de contraseña

**Estado: PROPUESTO — espera la aprobación de Carlos (regla 3.4). No se escribe código antes.**
**Fecha:** 2026-10-18 (UTC).
**Contrato:** ADR-050 (APROBADO — Carlos, 2026-10-18), D4 y D5, y la parte de D6 que fija `credentialsNotBefore` al desactivar. Enmienda ADR-047 D5. Este plan no decide nada que el ADR no fije, salvo los puntos marcados **[Plan]**, que necesitan aprobación junto con el plan.
**Base:** `develop` en `4bef7be`. **Rama:** `feat/identity-r3-r2-revocacion-contrasena`.
**Dueño:** Carlos.

> **Revisión del 2026-10-24 (Manuel, con asistencia de Claude).** El texto de arriba y el de §1 a §7 se conservan tal como se redactaron el 2026-10-18; lo que cambia va en notas fechadas y en §8.
> - **Estado:** BORRADOR revisado. Espera el **"plan aprobado" de Manuel**, por la delegación de Carlos del 2026-10-24 (mensaje por WhatsApp, comunicado por Manuel; texto pendiente de registrar en `decisiones-delegadas-2026-10.md`). Carlos aprueba el PR de implementación con Approve en GitHub.
> - **Dueño:** Manuel (indicación de Manuel, 2026-10-24).
> - **Base:** `develop` `0b3dc3e`. Rama desde el `develop` del momento de implementar, con su hash.
> - **Hechos reverificados:** §8.1. Cambian dos: el número de clases de test que usan `Account.reconstitute` y el entorno (§7).
> - **Choque con ADR-050 D5 en el orden de los pasos:** §8.2, P-R3-1. Este plan no lo resuelve; lo decide Manuel al aprobar.
> - **Lo que faltaba:** mutaciones (§8.3) y el procedimiento de evidencia (§8.4).

## 1. Alcance

Dentro:
- `credentialsNotBefore` en la cuenta y la regla de ADR-050 D4 (`max(segundo actual truncado, valor actual) + 1 s`).
- Rechazo en el filtro JWT de todo token con `iat` anterior a `credentialsNotBefore`.
- `POST /api/v1/account/password` (ADR-050 D5), que devuelve un token nuevo con `iat = credentialsNotBefore`.
- Fijar `credentialsNotBefore` al cambiar la contraseña y al desactivar la cuenta, en los servicios que ya existen.

Fuera: recuperación de contraseña (R-4, la reutilizará), rutas de desactivación y reactivación (tarea 4 de ADR-050), aceptaciones legales (R-5), Google (R-6).

## 2. Hechos verificados en `4bef7be` (2026-10-18)

- `JwtTokenVerifier` no lee `iat`; `JwtVerification` solo lleva `subject`. `NimbusTokenIssuer` siempre escribe `iat`.
- `JwtAuthFilter` llama a `IdentityPrincipalPort.resolvePrincipal(sub)`. Ese método lo usan también `core` (5 llamadas) y `convocatoria` (3), que no tienen `iat`.
- Implementaciones de `IdentityPrincipalPort`: `IdentityPrincipalPortImpl` y dos dobles de test (`core/.../TestIdentityPrincipalPort`, `convocatoria/.../FakeIdentityPrincipalPort`). Ninguna lambda. `TokenIssuerPort`: solo `NimbusTokenIssuer`.
- `ChangeCredentialsService.changeCredentials(actor, accountId, nuevaContraseña)` existe, sin ruta HTTP y sin comprobar la contraseña actual; lo usan 8 llamadas de `AccountApplicationServiceIntegrationTest`.
- `Account.changeCredentials(PasswordHash)` y `Account.deactivate()`: 1 llamada de producción cada uno; 2 y 8 llamadas de test.
- `Account.reconstitute(...)` (6 argumentos) lo usan el mapeador y 10 clases de test.
- Los servicios de `identity` obtienen la hora con `ObjectProvider<Clock>` (`InviteMemberService`); `ChangeCredentialsService` y `DeactivateAccountService` usan `Instant.now()`.
- `NimbusTokenIssuer` y `JwtTokenVerifier` son `@Component` cuyo constructor público usa `Clock.systemUTC()`: en el contexto de Spring **no** usan el `Clock` de la aplicación. `OrganizationMembersAndInvitationsHttpIntegrationTest` ya controla el reloj del contexto para las invitaciones.

## 3. Cambios por módulo

### `contracts`

| Archivo | Cambio | Fuente |
|---|---|---|
| `authorization/IdentityPrincipalPort.java` | Método nuevo `AuthorizationPrincipal resolveAuthenticatedPrincipal(String accountId, Instant issuedAt)`. `resolvePrincipal(String)` no cambia | ADR-050 D4 |
| `authentication/TokenIssuerPort.java` | Método nuevo `String issue(String accountId, Instant issuedAt)`. `issue(String)` no cambia | ADR-050 D4, D5 |

**[Plan]** Los dos métodos nuevos son **abstractos**, no `default`. Un `default` que delegara en `resolvePrincipal` ignorando `iat` dejaría pasar tokens revocados sin que nadie lo notara (regla 2.2). Por eso se actualizan los dos dobles de test de `core` y `convocatoria`, que lanzarán `UnsupportedOperationException` si alguien los llama (ninguno de esos módulos lo hace).

### `identity`

| Archivo | Cambio | Fuente |
|---|---|---|
| `domain/model/Account.java` | Campo `credentialsNotBefore` (`Instant`, nulo en cuentas existentes). `changeCredentials(PasswordHash, Instant now)` y `deactivate(Instant now)` fijan `credentialsNotBefore = max(truncar(now), actual) + 1 s` (en `deactivate`, solo si la cuenta cambia de estado). Método `boolean acceptsTokenIssuedAt(Instant iat)`: `true` si `credentialsNotBefore` es nulo o `iat >= credentialsNotBefore`. `reconstitute` gana un séptimo argumento; **[Plan]** se conserva la firma de 6 argumentos delegando con `null`, para no tocar las 10 clases de test que reconstruyen cuentas | D4 |
| `domain/exception/CredentialsRevokedException.java` (nuevo) | Token emitido antes de `credentialsNotBefore` | D4 |
| `domain/exception/CurrentPasswordIncorrectException.java` (nuevo) | Contraseña actual incorrecta al cambiarla | D5 |
| `infrastructure/persistence/mongo/documents/AccountDocument.java`, `mappers/AccountMapper.java` | Campo `credentialsNotBefore` | D4 |
| `application/service/IdentityPrincipalPortImpl.java` | `resolveAuthenticatedPrincipal`: carga la cuenta una vez; si `INACTIVE` → `InactiveAccountException` (como hoy); si `!acceptsTokenIssuedAt(iat)` → `CredentialsRevokedException`; si no, el mismo principal que `resolvePrincipal` | D4 |
| `application/service/ChangeCredentialsService.java` | Hora por `ObjectProvider<Clock>`. Método nuevo `Instant changeOwnCredentials(AccountId, String currentPassword, String newPassword)`: valida la política de la nueva antes de leer nada (como hoy); en la transacción con reintento, comprueba la actual con BCrypt (`CurrentPasswordIncorrectException` si no coincide), guarda el hash, fija `credentialsNotBefore`, audita `CREDENTIALS_CHANGED` sin contraseñas y devuelve el `credentialsNotBefore` confirmado. **[Plan]** El método existente `changeCredentials(actor, accountId, nueva)` se conserva (lo reutilizará R-4, que no tiene contraseña actual) y **también** fija `credentialsNotBefore`: ningún camino cambia la contraseña sin revocar | D5; D4 |
| `application/service/DeactivateAccountService.java` | Hora por `ObjectProvider<Clock>`; llama a `deactivate(now)` | D4 (desactivar revoca) |

### `api`

| Archivo | Cambio | Fuente |
|---|---|---|
| `auth/jwt/JwtVerification.java` | Lleva también `issuedAt` | D4 |
| `auth/jwt/JwtTokenVerifier.java` | Lee `iat`. **[Plan]** Un token sin `iat` → `MISSING_CLAIMS` (el mismo 401). ADR-038 y ADR-047 ya fijan `iat` como claim del token y el emisor siempre lo escribe, así que ningún token válido emitido lo pierde | ADR-047 D5 enmendado por ADR-050 D4 |
| `auth/jwt/JwtAuthFilter.java` | Llama a `resolveAuthenticatedPrincipal(sub, iat)`. `CredentialsRevokedException` llega como excepción genérica, igual que hoy `InactiveAccountException` (`api` no depende de `identity`), y produce **el mismo 401** byte a byte | D4 |
| `auth/jwt/NimbusTokenIssuer.java` | `issue(accountId, issuedAt)`: `iat = issuedAt`, `exp = issuedAt + ttl`. `issue(accountId)` llama al nuevo con la hora del reloj | D5 |
| `auth/jwt/NimbusTokenIssuer.java`, `auth/jwt/JwtTokenVerifier.java` | **[Plan]** El constructor que usa Spring recibe `ObjectProvider<Clock>` (con `Clock.systemUTC()` si no hay ninguno), como los servicios de `identity`. Sin esto, con un reloj controlado el login emitiría tokens con la hora real mientras `credentialsNotBefore` usa la controlada, y los tests 2, 3 y 3b no probarían nada. En producción no cambia el comportamiento: no hay otro `Clock` que el del sistema | D4 (verificabilidad) |

### `app`

| Archivo | Cambio | Fuente |
|---|---|---|
| `web/account/AccountPasswordController.java` (nuevo) | `POST /api/v1/account/password`, JWT, cuerpo `{currentPassword, newPassword}`. Campo vacío → 400 con el campo nombrado sin llamar al dominio. Llama a `changeOwnCredentials` y después a `issue(accountId, credentialsNotBefore)`. Responde `200 {token}` con `Cache-Control: no-store`. Nunca registra contraseñas ni el token | D5 |
| `web/IdentityApiErrorMappings.java` | `CurrentPasswordIncorrectException` → **400** `CurrentPasswordIncorrect` (nunca 401, ADR-043 T-1). `CredentialsRevokedException` no se mapea: solo aparece en el filtro | D5 |

**Sin cambios:** `PublicRoutes` (la ruta es protegida), `core` y `convocatoria` salvo sus dobles de test. Ninguna dependencia nueva.

## 4. Comportamiento ante fallos

| Situación | Resultado |
|---|---|
| Contraseña actual incorrecta | 400 `CurrentPasswordIncorrect`; nada cambia (hash, `credentialsNotBefore`, auditoría) |
| Contraseña nueva corta | 400 `PasswordTooShort`; nada cambia |
| Dos cambios simultáneos de la misma cuenta | La transacción con reintento serializa: el segundo relee la cuenta, su contraseña actual ya no coincide y recibe 400. Nunca quedan los dos aplicados |
| El cambio se confirma pero falla la emisión del token | 500 (`TokenIssuanceException`, ADR-038 #7). **La contraseña sí cambió** y todas las sesiones quedaron revocadas, incluida la actual: el cliente debe ir al inicio de sesión. No es un fallo ambiguo para el backend (la escritura está confirmada); se documenta en `referencia-api-v1.md` para la web y el móvil |
| Cuenta desactivada entre la petición y la transacción | `InactiveAccountException` del dominio (ya existe) |

## 5. Tests (rojos primero)

Numeración de la definición de hecho de ADR-050.

| Test | Módulo | Cubre |
|---|---|---|
| `AccountTest` (ampliado) | `identity` | Regla de `credentialsNotBefore`: primer cambio, dos cambios en el mismo segundo (`+1 s` sobre el anterior), `acceptsTokenIssuedAt` en los bordes (igual, un segundo antes, nulo); `deactivate` fija el valor solo si cambia el estado |
| `IdentityPrincipalPortImplTest` (ampliado) | `identity` | `resolveAuthenticatedPrincipal`: revocado, inactivo, aceptado; `resolvePrincipal` sin cambios (7) |
| `AccountApplicationServiceIntegrationTest` (ampliado, Testcontainers) | `identity` | 8 (actual incorrecta: hash, `credentialsNotBefore` y auditoría intactos), 9, 10; el método existente también fija `credentialsNotBefore`; dos cambios simultáneos con `CyclicBarrier`: uno gana, el otro 400 y ninguno queda a medias |
| `JwtTokenVerifierTest` (ampliado) | `api` | `iat` leído; token sin `iat` → rechazado |
| `JwtAuthFilterTest` (ampliado) | `api` | Llama a `resolveAuthenticatedPrincipal` con el `iat` del token y **nunca** a `resolvePrincipal` (aserción negativa); revocado → el mismo cuerpo 401 que los demás rechazos (6) |
| `NimbusTokenIssuerTest` (ampliado) | `api` | `issue(accountId, issuedAt)` escribe ese `iat` y `exp = iat + ttl` |
| `AccountPasswordHttpIntegrationTest` (nuevo, Testcontainers) | `app` | 1 (token anterior → 401 tras el cambio), 2 (token del mismo segundo → 401, reloj controlado), 3 (el token devuelto funciona en el mismo segundo), 3b (dos cambios en el mismo segundo), 4 (desactivar con el servicio, reactivar y token anterior → 401), 5 (cuenta sin `credentialsNotBefore` conserva su token), 8 por HTTP (400, no 401), campos vacíos → 400, sin JWT → 401, ninguna contraseña ni token en los logs (captura de salida) |
| Tests existentes de `core` y `convocatoria` | — | 7: pasan sin cambios salvo los dos dobles |
| `GoldenPathHttpIntegrationTest` | `app` | Sigue en verde |

**Reloj controlado:** los tests de los casos 2, 3 y 3b fijan el `Clock` del contexto (el mismo patrón que las invitaciones), para que las peticiones caigan en el mismo segundo sin depender de la velocidad de la máquina.

## 6. Pasos

1. Rama desde `develop`. Línea base: `mvn -B clean test -fae` del reactor, con el resumen de Surefire.
2. Tests de §5 en rojo, comprobando que fallan por la razón esperada.
3. `contracts` y dobles de test → `identity` → `api` → `app`, corriendo el módulo completo tras cada uno (`mvn test -pl <módulo>`).
4. `scripts/ci-local.sh` sobre el último commit.
5. Documentos en el mismo PR: nota en ADR-047 D5 que remite a ADR-050 D4 (la validación pasa a seis pasos); `referencia-api-v1.md` con la ruta nueva y el caso del 500 tras confirmar; `estado-fase6.md` o el documento de estado de Fase 7 que el equipo use.
6. PR hacia `develop` con el resumen literal de Surefire y una aprobación humana (regla 3.2).

## 7. Entorno

Igual que el plan R-1 §6: en la sesión de agente del 2026-10-18 Maven no resuelve dependencias (proxy). La evidencia de tests la ejecuta y la pega el humano (`estado-fase6.md` §7.2).

---

## 8. Revisión del 2026-10-24 contra `develop` `0b3dc3e`

### 8.1 Hechos de §2, comprobados uno por uno en `4bef7be` y en `0b3dc3e`

| Hecho de §2 | En `0b3dc3e` | ¿Cambió? |
|---|---|---|
| `JwtTokenVerifier` no lee `iat`; `JwtVerification` solo lleva `subject`; `NimbusTokenIssuer` siempre escribe `iat` | `JwtVerification.java:7` (`record JwtVerification(String subject, RejectReason rejectReason)`); `NimbusTokenIssuer.java:49` (`.issueTime(Date.from(now))`) | No |
| `JwtAuthFilter` llama a `resolvePrincipal(sub)`; `core` lo llama 5 veces y `convocatoria` 3 | `JwtAuthFilter.java:97`. Cuenta en `src/main`: `FundCommandService` 1, `PhysicalAssetCommandService` 1, `FundOperationalQueryService` 1, `PhysicalAssetOperationalQueryService` 2 y `ConvocatoriaAuthorizationPolicy` 3. Igual en `feat/r6-bloque-b` (#113) | No |
| Implementaciones de `IdentityPrincipalPort` y `TokenIssuerPort` | `IdentityPrincipalPortImpl`, `TestIdentityPrincipalPort` (`core`), `FakeIdentityPrincipalPort` (`convocatoria`); `NimbusTokenIssuer`. Ninguna clase anónima ni lambda | No |
| `ChangeCredentialsService.changeCredentials(actor, accountId, nueva)` sin ruta HTTP ni comprobación de la actual; 8 llamadas en `AccountApplicationServiceIntegrationTest` | `ChangeCredentialsService.java:37-61`; 8 llamadas (`:137`, `:156`, `:169`, `:268`, `:280`, `:332`, `:361`, `:464`) | No |
| `Account.changeCredentials` y `Account.deactivate`: 1 llamada de producción cada uno; 2 y 8 de test | `ChangeCredentialsService.java:46`; `DeactivateAccountService.java:38`. Test: `AccountTest` (2 y 6) e `IdentityPrincipalPortImplTest` (2) | No |
| `Account.reconstitute(...)` (6 argumentos): el mapeador y **10** clases de test | **11** clases de test: se añadió `OrganizationLogoReferenceIntegrationTest` (#111). La firma de 6 argumentos que §3 conserva la cubre igual | **Sí** |
| Hora: los servicios de `identity` con `ObjectProvider<Clock>`; `ChangeCredentialsService` y `DeactivateAccountService` con `Instant.now()` | `ObjectProvider<Clock>` en `AcceptInvitationService`, `InviteMemberService`, `OrganizationInvitationsQuery` y `RevokeInvitationService`. `Instant.now()` en `ChangeCredentialsService.java:52` y `DeactivateAccountService.java:45`. `app/src/main` no declara ningún `Clock` | No |
| `NimbusTokenIssuer` y `JwtTokenVerifier`: `@Component` con `Clock.systemUTC()` en el constructor público | `NimbusTokenIssuer.java:24`, `:31-32`; `JwtTokenVerifier.java:35`, `:45-46` | No |
| El test de invitaciones ya controla el reloj del contexto | `OrganizationMembersAndInvitationsHttpIntegrationTest.java:90-112` (`MovableClock`) | No |
| §3: nombres que el plan reutiliza | Existen: `IdentityApiErrorMappings` (`app/web`), `PasswordHasherPort.matches` (`:19`), `MongoTransactionRetryHelper.executeWithRetry` (`identity`, solo reintenta errores transitorios), `AuditAction.CREDENTIALS_CHANGED`, `TokenIssuanceException`, `JwtVerification.RejectReason.MISSING_CLAIMS`, `AccountDocument`, `AccountMapper` | No |
| §3 `api`: `InactiveAccountException` llega al filtro como excepción genérica | `JwtAuthFilter.java:100-103` (`catch (RuntimeException e)`; comentario "api no depende de identity") | No |
| §7: Maven no resuelve dependencias en la sesión del agente | Hoy `mvn` y `scripts/ci-local.sh` corren en la sesión (evidencias de #110 a #113 en `Documentos/evidencia-ci/`) | **Sí**: §7 queda sustituido por §8.4 |

**Desde `4bef7be`,** el diff de `identity/src/main`, `api/.../auth` y `contracts/src/main` solo trae el logo, `AccountEmailQuery`, el estado de verificación, `MediaStoragePort` y `CampaignPublicSummaryPort`. Nada toca el filtro JWT, los dos puertos ni `ChangeCredentialsService`.

### 8.2 Choque con ADR-050 D5 (para la aprobación de Manuel)

| # | Qué | Opciones | Recomendación |
|---|---|---|---|
| **P-R3-1** | ADR-050 D5 (`:107-110`) fija el orden "en una transacción de `identity`": **1.** comprobar la actual con BCrypt; **2.** aplicar la política a la nueva; **3.** guardar, fijar `credentialsNotBefore` y auditar. §3 de este plan valida la política de la nueva **antes** de leer nada, fuera de la transacción ("como hoy", `ChangeCredentialsService.java:40`). Si la actual es incorrecta y la nueva es corta, el ADR responde `CurrentPasswordIncorrect` y este plan, `PasswordTooShort` | (a) El orden literal del ADR: dentro de `executeWithRetry`, primero `matches`, después `new PlainPassword(...)`. El helper solo reintenta errores transitorios, así que las dos excepciones de dominio salen sin reintentos. (b) Mantener el orden de §3 y registrar la diferencia como nota de ADR-050 | **(a)**: es lo que dice el ADR, y no cambia el método existente `changeCredentials`, que no tiene contraseña actual. Test nuevo: actual incorrecta y nueva corta → `400 CurrentPasswordIncorrect`, y nada cambia |
| **P-R3-2** | Puntos **[Plan]** de §3 que aprueba Manuel con el plan: los métodos nuevos del puerto son abstractos; `reconstitute` de 6 argumentos se conserva; el método existente `changeCredentials` también revoca; token sin `iat` → `MISSING_CLAIMS`; `Clock` por `ObjectProvider` en `NimbusTokenIssuer` y `JwtTokenVerifier` | Aprobar o cambiar cada uno | Aprobar los cinco tal como están |

### 8.3 Mutaciones (`scripts/mutaciones.py`)

| # | Mutación | Test que la mata |
|---|---|---|
| 1 | `credentialsNotBefore` sin el `+ 1 s` | Test 2 (token del mismo segundo) |
| 2 | Sin el `max` con el valor anterior | Test 3b (dos cambios en el mismo segundo) |
| 3 | `acceptsTokenIssuedAt` con `>` en lugar de `>=` | Test 3 (el token devuelto funciona en el mismo segundo) |
| 4 | El filtro vuelve a llamar a `resolvePrincipal(sub)` | `JwtAuthFilterTest`, aserción negativa |
| 5 | `changeOwnCredentials` no comprueba la contraseña actual | Test 8 |
| 6 | `deactivate(now)` no fija `credentialsNotBefore` | Test 4 |
| 7 | La ruta emite el token con la hora del reloj en lugar de `credentialsNotBefore` | Test 3 |
| 8 | El método existente `changeCredentials` no fija `credentialsNotBefore` | Su test en `AccountApplicationServiceIntegrationTest` |
| 9 | El verificador acepta un token sin `iat` | `JwtTokenVerifierTest` |
| 10 | La auditoría `CREDENTIALS_CHANGED` lleva la contraseña | Test 10 |

### 8.4 Pasos y evidencia (sustituyen a §6.1, §6.4 y §7)

1. Rama `feat/identity-r3-r2-revocacion-contrasena` desde el `develop` del momento, con su hash.
2. Tests de §5 (más el de P-R3-1) en rojo, con la salida literal. El rojo es una aserción o un fallo de compilación, nunca un test mal escrito.
3. Verde por capas con el módulo completo en cada una (`mvn test -pl <módulo>`): `contracts` y los dobles de `core` y `convocatoria`, después `identity`, `api` y `app`.
4. Mutaciones de §8.3 con `scripts/mutaciones.py`.
5. Documentos de §6.5.
6. `free -g` con al menos 4 GB y `scripts/ci-local.sh` en un clon limpio del último commit. La evidencia va a `Documentos/evidencia-ci/`.
7. Autoauditoría contra ADR-050 D4, D5 y D6 (la parte de `credentialsNotBefore`), y sus tests 1 a 10.
8. PR hacia `develop` con Carlos como revisor; nada se fusiona sin su Approve.

