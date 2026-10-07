# Plan B3 — Login y autenticación JWT (implementación de ADR-038 §2.7 y ADR-047)

**Estado:** **HECHO** (`feat/b3-jwt`, 2026-10-07): reactor completo en verde sobre `73d8253` y 12 + 2 mutaciones muertas (`evidencia-fase6/b3-jwt-73d8253-2026-10-07.txt`). **APROBADO — Carlos, 2026-10-07** (Q1 con la condición de la lista explícita de rutas públicas, §2.3.1; Q2 y Q3 sí).
**Origen:**
- ADR-038 §2.7 (aprobado): puertos `authenticate`, `resolvePrincipal` e `issue`.
- **ADR-047 (APROBADO — Carlos, 2026-10-07):**
  - librería, algoritmo, secreto y rotación;
  - el orden exacto de validación;
  - "nunca registrar el token ni el secreto";
  - la definición de hecho (P4).

**Desbloquea:** los criterios 4 y 6 del golden path (donación con cuenta e historial autenticado) y la mayor parte de B6, que construye `HumanActor` desde el JWT.
**Revisión:** cubierto por la excepción a la regla 3.2. La evidencia de tests sustituye al segundo revisor.

---

## 1. Hechos del código que condicionan el plan (`develop`, 2026-10-07)

- **No existe** ningún login, `LoginController`, filtro JWT, `AuthenticateAccountPort` ni `TokenIssuerPort` (`propuesta-apis-fase6.md` §7.1). Las divergencias ID01-D1…D4 describen un adelanto que nunca llegó a `develop`: aquí se implementa la ficha tal como está.
- **Contrato que ya existe:** `IdentityPrincipalPort.resolvePrincipal` (`contracts`). Lanza `InactiveAccountException` para `INACTIVE` y `AccountNotFoundException` para cuentas inexistentes, ambas de `identity` (`IdentityPrincipalPortImpl.java:97-134`).
- **Identity:**
  - `AccountRepositoryPort.findByEmail` existe.
  - `PasswordHasherPort` usa `BCryptPasswordEncoder(12)`; `matches` devuelve `false` con entradas nulas.
  - ArchUnit exige que toda clase `*Service` de `identity.application.service` use `MongoTransactionRetryHelper`. Las implementaciones de puertos de lectura siguen el patrón `*PortImpl`, como `IdentityPrincipalPortImpl`.
- **`api`:**
  - depende solo de `core`, `contracts` y `spring-boot-starter-web`, no de `identity`;
  - el único filtro es `TrackingCodeAuthFilter`, limitado a `/api/v1/donations/tracking/**`;
  - no existe ningún `@ControllerAdvice`.
- **Patrón de secretos:** `TrackingSecurityProperties`, con *fail-fast* si el secreto falta o está sin resolver.
- **Nimbus no está en el repositorio local de Maven.** El PR necesita red para descargarlo (proxy de la sesión). Su versión se fija en el `dependencyManagement` del pom padre (ADR-047 D1).

## 2. Cambios

### 2.1 `contracts`

- `AuthenticateAccountPort`: `String authenticate(String email, String password)` devuelve el `accountId`, o lanza `AuthenticationFailedException`.
- `AuthenticationFailedException`: **una sola excepción**, sin motivo, para los tres casos (email inexistente, contraseña incorrecta, `INACTIVE`). Así el motivo no puede filtrarse ni por el tipo de la excepción.
- `TokenIssuerPort`: `String issue(String accountId)`.
- `TokenIssuanceException`.

### 2.2 `identity`

- **`AuthenticateAccountPortImpl`** (`identity.application.service`, mismo patrón que `IdentityPrincipalPortImpl`):
  - busca la cuenta por email;
  - si no existe, ejecuta **igualmente** `matches` contra un **hash ficticio** de coste 12, generado una vez al arrancar, y lanza `AuthenticationFailedException` (Q5: el tiempo no revela si el email existe);
  - contraseña incorrecta o cuenta `INACTIVE` → `AuthenticationFailedException`. La comprobación de `INACTIVE` va **después** de `matches`, para que los tres casos cuesten lo mismo.
- Solo lectura, sin transacción.

### 2.3 `api`

- **`JwtSecurityProperties`** (`traceability.security.jwt.*`), con *fail-fast* (ADR-047 D3/D4):
  - `signing-secret` obligatorio, de al menos 32 bytes;
  - `kid`;
  - `ttl` (por defecto `PT8H`);
  - `clock-skew` (`PT30S`);
  - opcionalmente `previous-signing-secret`, `previous-kid` y `previous-accept-until`. Si hay clave anterior, `accept-until` es obligatorio y no puede quedar más allá de arranque + `ttl`.
  - Mientras haya clave anterior, un **WARN** al arrancar con su `kid` y la fecha de fin, **nunca con el secreto**.
- **`NimbusTokenIssuer`** (implementa `TokenIssuerPort`): HS256 con la clave actual, cabecera `kid` y claims `sub`, `iat` y `exp`. Cualquier fallo se convierte en `TokenIssuanceException`.
- **`JwtTokenVerifier`**, con el orden exacto de ADR-047 D5 (P1):
  1. el `alg` debe ser `HS256`;
  2. el `kid` se busca **solo** en el mapa de claves configuradas, y la clave anterior únicamente si no ha pasado su `accept-until`;
  3. firma;
  4. `exp`, con la tolerancia;
  5. devuelve el `sub`.
- **`JwtAuthFilter`** (`OncePerRequestFilter`, ordenado después de `TrackingCodeAuthFilter`):
  - Se aplica a todo `/api/v1/**` (*deny-by-default*) **salvo las rutas de la lista explícita de §2.3.1**, que es la única fuente de verdad.
  - Lee `Authorization: Bearer`, verifica el token y llama a `IdentityPrincipalPort.resolvePrincipal(sub)` en **cada** request.
  - Deja el `AuthorizationPrincipal` como atributo de la request. B6 construirá desde él el `HumanActor`.
  - **Cualquier** fallo da 401 con **el mismo** `ProblemDetail`: token ausente o mal formado, `alg` no permitido, `kid` desconocido, firma inválida, caducado, cuenta `INACTIVE` o inexistente.
  - Como `api` no depende de `identity`, las excepciones del puerto se tratan por tipo genérico (`RuntimeException`) y se responden igual.
  - **Excepción: un fallo de acceso a datos (`DataAccessException`) al resolver el principal se propaga y da 500, nunca 401. Decisión de Carlos, 2026-10-07** (Q4 abajo). Un fallo de infraestructura no es un fallo de autenticación: un 401 haría creer al cliente que su token no vale y ocultaría la caída de la base de datos. Lo esencial es que la request no llega al controlador. Lo prueba `PrincipalResolutionFailureRealServerIntegrationTest` contra Tomcat real. La falta de una excepción en `contracts` es un pendiente conocido de ADR-038 (`estado-fase6.md`).
- **`LoginController`**, `POST /api/v1/auth/login` (ficha ID-01):
  - `{email, password}` vacíos → **400** (ID01-D2);
  - `AuthenticationFailedException` → **401** uniforme (ID01-D1);
  - `TokenIssuanceException` → **500**, nunca 401 (ID01-D4);
  - éxito → `{"token": "..."}` (`api-contract-matrix.md:45`).
- **Logs (ADR-047 D7):** nunca el token, ni el secreto, ni la cabecera `Authorization`. El `kid` y el motivo interno del rechazo sí, solo en DEBUG.

#### 2.3.1 Rutas públicas: lista explícita y completa (condición de Q1)

Una sola constante, `PublicRoutes` (en `api`), con método HTTP + patrón. El filtro JWT la consulta y nada más decide qué es público. Origen: `api-contract-matrix.md` y el golden path.

| Método y ruta (bajo `/api/v1`) | Autenticación propia | Uso en la demo | Existe hoy |
|---|---|---|---|
| `GET /donations/tracking/**` | `TrackingCodeAuthFilter` (ADR-041) | seguimiento e historial | sí |
| `POST /auth/login` | ninguna | criterio 4 | B3 |
| `POST /auth/register` | ninguna | alta de donante | no (B6) |
| `GET /public/campaigns/{publicCode}` | ninguna | CV-07, detalle de convocatoria | no (B6) |
| `GET /public/campaigns` | ninguna | descubrimiento | no |
| `POST /public/campaigns/{publicCode}/donation-intents` | **JWT opcional** (ver abajo) | criterio 3 (sin cuenta) y criterio 4 (con cuenta) | no (B6) |
| `GET /public/campaigns/{publicCode}/narrative` | ninguna | narrativa pública | no (B5) |
| `POST /webhooks/payments` | firma del proveedor (simulado en la demo), nunca JWT | confirmación del pago | no (B6) |

- **JWT opcional** (solo en `donation-intents`): sin cabecera `Authorization` → anónimo (criterio 3). **Con** cabecera → se verifica exactamente igual que en una ruta protegida, y un token inválido da 401; **nunca** se degrada a anónimo en silencio. Con token válido se deja el `AuthorizationPrincipal` (criterio 4).
- Las rutas que aún no existen se incluyen ya, para que el criterio 3 no falle cuando entre B6 sobre un filtro *deny-by-default*.
- Toda ruta pública nueva exige editar esta tabla y `PublicRoutes` en el mismo PR.

### 2.4 `app`

- No hay que conectar nada a mano: `app` escanea `com.traceability` e `identity`, así que la implementación de `identity` y los componentes de `api` quedan en el contexto.
- `application.yml`: `traceability.security.jwt.signing-secret: ${JWT_SIGNING_SECRET}`, sin valor por defecto. Un valor de prueba en el `application.yml` de tests y en `application-dev.yml`, marcado como no productivo.

## 3. Tests

**Primero los que deben fallar**, con la salida literal de Surefire en el PR, y con un esqueleto mínimo para compilar, como en B-PROJ y D-CAMPAIGN.

**Definición de hecho de ADR-047 (P4), cada punto con su test:**

| # | Caso | Módulo |
|---|---|---|
| 1 | `alg: none` → 401 | `api` |
| 2 | HS512 y RS256 → 401 | `api` |
| 3 | Payload alterado → 401 | `api` |
| 4 | Caducado → 401; caducado hace menos de 30 s → aceptado | `api` |
| 5 | `kid` desconocido → 401; un `kid` con forma de ruta o consulta (`../x`, `' OR 1=1`) solo se busca en el mapa y da 401 | `api` |
| 6 | Clave anterior aceptada mientras está configurada y vigente; rechazada al retirarla y pasado `accept-until` | `api` |
| 7 | No arranca: sin secreto, con un secreto de menos de 32 bytes, con clave anterior sin `accept-until` o con un `accept-until` más allá de arranque + `ttl` | `api` (`ApplicationContextRunner`) |
| 8 | Los tres fallos de login dan código y cuerpo **idénticos byte a byte**, y el tiempo del email inexistente no se distingue del de la contraseña incorrecta (se verifica que `matches` se ejecuta también con el email inexistente, y se compara la mediana de N intentos con un margen) | `identity` + `app` |
| 9 | Ni el token ni el secreto aparecen en los logs de un login correcto, de un login fallido y de un rechazo del filtro (captura de logs) | `api` / `app` |
| 10 | Cuenta desactivada después de emitir el token → 401 en la siguiente request | `app` |
| 11 | Fallo de `issue()` → 500, nunca 401 | `api` |
| 12 | Separación: un token de seguimiento en una ruta JWT → 401, y un JWT en la ruta de seguimiento → 401 | `app` |

**Además:**
- **Inventario de rutas (condición de Q1):** un test en `app` recorre todos los *handlers* registrados (`RequestMappingHandlerMapping`) y comprueba que cada ruta bajo `/api/v1` está **o** en `PublicRoutes` **o** en una lista explícita `ProtectedRoutes` del propio test. Una ruta nueva en ninguna de las dos hace fallar el build hasta que alguien decida. Para cada ruta pública existente, una request sin `Authorization` no recibe el 401 del filtro JWT; para cada protegida, sí.
- `donation-intents` (cuando exista, o con un controlador de prueba): sin cabecera → anónimo; con token inválido → 401; con token válido → principal presente.
- Campos vacíos en el login → 400.
- Login correcto → token con exactamente `sub`, `iat`, `exp` y `kid`.
- Las reglas ArchUnit de `identity` y `api` siguen en verde.

**Verificación:**
- `mvn clean test -fae` del reactor completo, con la salida literal de Surefire.
- Mutaciones, cada una debe hacer fallar algún test:
  - verificar la firma antes de elegir la clave por `kid`;
  - aceptar `none`;
  - quitar la tolerancia;
  - no comprobar `accept-until`;
  - quitar la comparación con el hash ficticio;
  - registrar el token en un log;
  - devolver 401 ante un fallo de `issue()`.

## 4. Forma de entrega

- **Una rama y un PR:** `feat/b3-jwt`, que toca `contracts`, `identity`, `api` y `app`.
- **Commits por módulo:** tests que fallan → `contracts` → `identity` → `api` → `app` → documentos (`estado-fase6.md`, `propuesta-apis-fase6.md` para ID-01 y este plan, que pasa a hecho).

## 5. Fuera de alcance

- Refresh de tokens.
- Límite de intentos (DH-56): riesgo aceptado en ADR-047.
- Almacenamiento del token en el cliente (ADR-046, ADR-043).
- `GET /account/donations` y la relación `accountId` ↔ `donorRef` (D-API, criterio 6).
- Construir `HumanActor` en los endpoints de negocio (B6).

## 6. Decisiones de Carlos

| # | Pregunta | Recomendación |
|---|---|---|
| Q1 | ¿*Deny-by-default* sobre `/api/v1/**`? | **Sí — Carlos, 2026-10-07, con condición:** lista explícita y completa de rutas públicas (§2.3.1: CV-07, intención sin cuenta, webhook simulado, narrativa, registro, login y seguimiento) y test de inventario que falle ante una ruta sin clasificar |
| Q2 | ¿Respuesta del login solo `{token}`? | **Sí — Carlos, 2026-10-07** |
| Q3 | ¿Un único PR multimódulo? | **Sí — Carlos, 2026-10-07** |
| Q4 | Si Mongo falla al resolver el principal en el filtro, ¿401 o propagar? (surgió en la implementación, no estaba en ADR-038) | **Propagar: 500, nunca 401, y el controlador no se ejecuta — Carlos, 2026-10-07**, con test contra Tomcat real |
