# ADR-047 — JWT: librería, algoritmo y secreto de firma (D-JWT)

## Status
**PROPUESTO** — 2026-10-07. Redactado por el agente a petición de Carlos (decisión D-JWT de `plan-cierre-fase6-codigo.md`). Dueño: Identidad. Necesita aprobación antes de cualquier código (regla 3.5: dependencia nueva; `plan-ejecucion-agentes-adr-038.md:62`).

Complementa ADR-038 §2.7 sin cambiar nada de lo que ya decide. Bloquea B3 y, a través de B3, los criterios 4 y 6 del golden path (donación con cuenta e historial autenticado).

## Context

### Lo que ya está decidido y no se reabre

| Decisión | Fuente |
|---|---|
| Puertos `AuthenticateAccountPort.authenticate`, `IdentityPrincipalPort.resolvePrincipal`, `TokenIssuerPort.issue` | ADR-038 §2.7 |
| JWT **mínimo**: `sub`/`iat`/`exp`/firma. Sin `organizationId`, roles, `platformAuthority` ni email. `AuthorizationPrincipal` se resuelve **en cada request** autenticada, así que una cuenta desactivada queda bloqueada en la siguiente request sin esperar a `exp` | ADR-038 §2.7; `identity-resumen.md:107-126` |
| Los tres fallos de login (email inexistente, contraseña incorrecta, `INACTIVE`) dan **código y cuerpo idénticos** (401). No cubre el tiempo de respuesta constante | ADR-038 §2.7; ADR-041 §2.5; `propuesta-apis-fase6.md` §4 |
| Un fallo de `issue()` → `TokenIssuanceException` → **500**, nunca 401, y sin reintento | ADR-038 §2.7 (#7) |
| **Sin `spring-boot-starter-security`**: el filtro JWT es un segundo `OncePerRequestFilter` en `api`, junto a `TrackingCodeAuthFilter`, separado por ruta (T-36). JWT y tracking nunca son intercambiables | `identity-resumen.md:137, 165`; ADR-023; ADR-041 §2 y :78 |
| `api` implementa `TokenIssuerPort`; `identity` implementa `AuthenticateAccountPort` | `identity-resumen.md:130-136` |
| Sin estrategia de *refresh* en este corte: un 401 con JWT lleva al cliente a `LOGGED_OUT` | ADR-043 :25, :57-63 |

### Lo que dejaron abierto (y decide este ADR)

`identity-resumen.md:146`: "Algoritmo de firma, duración de `exp`, gestión/rotación de claves — deliberadamente no fijado". ADR-038 no fija ni librería, ni algoritmo, ni clave, ni caducidad, ni rotación.

### Hechos verificados en el código (`develop`, 2026-10-07)

- **No hay ninguna librería JWT** en ningún `pom.xml` (ni jjwt, ni nimbus-jose-jwt, ni java-jwt, ni `spring-security-oauth2-*`). El único artefacto de Spring Security es `spring-security-crypto` (BCrypt), en `identity/pom.xml:34-37`.
- Spring Boot 3.4.4 y Java 21 (`pom.xml:13-16`); `api` depende de `core`, `contracts` y `spring-boot-starter-web` (`api/pom.xml:16-31`), no de `identity`.
- Patrón de secretos vigente: variable de entorno sin valor por defecto y *fail-fast* al arrancar si falta o está sin resolver (`TrackingSecurityProperties.java:17-25`; `Web3Configuration.java:38-47`). No hay *vault* ni rotación (decisión consciente, `estado-fase3.md:132`).
- El código de seguimiento ya es un token HMAC-SHA256 hecho a mano con la JDK (`HmacTrackingCodeService.java:22-23, 89`), con comparación en tiempo constante.
- No existe todavía ningún servicio de login, `LoginController`, filtro JWT ni `TokenIssuerPort` (`propuesta-apis-fase6.md` §7.1).

## Decision (propuesta)

### D1. Librería: Nimbus JOSE + JWT (`com.nimbusds:nimbus-jose-jwt`)

- Dependencia **solo de `api`** (emite y verifica). `identity`, `core` y `contracts` no la ven.
- **Versión fijada a mano** en el `dependencyManagement` del `pom.xml` padre (propiedad `nimbus-jose-jwt.version`): verificado que ni `spring-boot-dependencies` 3.4.4 ni `spring-security-bom` 6.4.4 la gestionan. El PR de B3 elige la última estable y se actualiza como cualquier dependencia de seguridad. Es la librería que usa el propio Spring Security (`spring-security-oauth2-jose`), con licencia Apache 2.0.
- Se usa **sin** Spring Security: solo `JWSSigner`/`JWSVerifier` y `JWTClaimsSet`.

### D2. Algoritmo: HS256 con un secreto de al menos 256 bits

- Un único servicio emite y verifica sus propios tokens (monolito modular): no hay terceros que necesiten verificar con una clave pública.
- **Lista blanca de algoritmos:** el verificador acepta **solo** `HS256`. `alg: none` o cualquier otro algoritmo se rechaza (evita la confusión de algoritmos).
- Si en el futuro un tercero tuviera que verificar tokens, se migra a un algoritmo asimétrico (RS256/EdDSA) con un ADR nuevo.

### D3. Secreto de firma

- `traceability.security.jwt-signing-secret: ${JWT_SIGNING_SECRET}`, **sin valor por defecto**, igual que `TRACKING_CODE_SECRET`.
- *Fail-fast* al arrancar: falta, vacío, sin resolver (`${…`) o **menor de 32 bytes** → `IllegalStateException`. Mismo patrón que `TrackingSecurityProperties`.
- **Separado** de `TRACKING_CODE_SECRET` y `ASSET_REF_SECRET`: nunca se reutiliza un secreto entre mecanismos (ADR-041: los tres mecanismos no son intercambiables).
- En tests y en el perfil `dev`, un valor de prueba en el `application.yml` de tests o en `application-dev.yml`, marcado como no productivo.

### D4. Rotación: `kid` y una clave anterior opcional

- Cada token lleva cabecera `kid` (identificador corto de la clave).
- El verificador acepta la clave **actual** y, si está configurada, **una anterior** (`JWT_SIGNING_SECRET_PREVIOUS`, `kid` propio) durante el tiempo de vida de los tokens emitidos con ella. Se firma siempre con la actual.
- Rotar = mover la actual a "anterior", poner una nueva y, pasado `exp`, retirar la anterior. Sin *vault*: coherente con `estado-fase3.md:132`.
- Si se prefiere no tener rotación en este corte, la alternativa es solo `kid` fijo y rotación con cierre de todas las sesiones (todos los usuarios vuelven a entrar). Ver Q3.

### D5. Claims y validación

- Claims: **solo** `sub` (= `accountId`), `iat`, `exp` (ADR-038). No se añaden `iss`/`aud`/`jti` (añadirlos sería ampliar ADR-038; ver Q4).
- Validación en el filtro, en este orden: firma con algoritmo en lista blanca → `kid` conocido → `exp` con una tolerancia de reloj de **30 s** → `resolvePrincipal(sub)` (que rechaza `INACTIVE` y cuentas inexistentes).
- **Cualquier** fallo del filtro → 401 con **el mismo cuerpo** `ProblemDetail` (DH-51; "401 uniforme"): token ausente, mal formado, firma inválida, caducado, `kid` desconocido, cuenta `INACTIVE` o inexistente.
- `exp`: **8 horas** por defecto, configurable (`traceability.security.jwt-ttl`). Motivo: sin *refresh* (ADR-043), un `exp` corto obliga a reentrar varias veces por jornada en campo; y la desactivación no depende de `exp`, porque `resolvePrincipal` se ejecuta en cada request. Es un valor de producto: lo confirma Carlos (Q2).

### D6. Lo que no decide este ADR

- Estrategia de *refresh* (sigue abierta en Identidad; ADR-043 :258).
- Limitación de intentos de login (DH-56, abierta en `propuesta-apis-fase6.md:232`).
- Tiempo de respuesta constante del login: fuera de alcance según ADR-038 §2.7. Se registra que hoy `BCrypt(12)` solo se ejecuta cuando el email existe, lo que deja un oráculo de tiempo. Mitigación barata posible en B3 (comparar contra un hash ficticio cuando el email no existe), sin cambiar el contrato. Ver Q5.
- Almacenamiento del token en el cliente web (ADR-046, en `PaxFide`).

## Alternatives

| Alternativa | Por qué no |
|---|---|
| **JWT hecho a mano con la JDK** (HMAC-SHA256 + Jackson), como el código de seguimiento | Cero dependencias, pero obliga a implementar y mantener a mano el formato JOSE, la validación de cabeceras y la lista blanca de algoritmos. El código de seguimiento no es un JWT y no tiene ese riesgo; un JWT sí, porque los clientes y las herramientas lo interpretan |
| **jjwt** (`io.jsonwebtoken`) | Válida y popular; equivalente en mantenimiento (tampoco la gestiona el BOM). Se reparte en tres artefactos (`api`/`impl`/`jackson`) y no aporta ventaja funcional para HS256. Nimbus se prefiere por ser la que usa Spring Security, lo que facilita migrar si algún día se adopta |
| **java-jwt** (Auth0) | Válida; menos usada en el ecosistema Spring |
| **`spring-boot-starter-oauth2-resource-server`** | Trae Spring Security completo, descartado expresamente (`identity-resumen.md:137, 165`; ADR-023) |
| **RS256/EdDSA** | Necesario solo si terceros verifican tokens. Añade gestión de un par de claves sin beneficio hoy |
| **Sesión en servidor** (cookie + colección de sesiones) | Contradice ADR-038 (JWT) y el cliente móvil ya diseñado (ADR-043) |

## Consequences

**Positivas**
- Desbloquea B3 con una sola dependencia, en un solo módulo.
- La desactivación de cuentas es inmediata gracias a `resolvePrincipal` en cada request, con independencia de `exp`.
- La rotación es posible sin cerrar todas las sesiones (si se aprueba D4).

**Negativas / restricciones**
- Una dependencia nueva en `api`.
- Un secreto más que gestionar por entorno.
- Con HS256, quien conozca el secreto puede emitir tokens: el secreto debe tratarse como el de `TRACKING_CODE_SECRET`.
- `exp` de 8 h: un token robado sirve hasta 8 h mientras la cuenta siga `ACTIVE`.

## Preguntas para Carlos

| # | Pregunta | Recomendación |
|---|---|---|
| Q1 | Librería (D1) | Nimbus JOSE + JWT, solo en `api` |
| Q2 | Duración de `exp` (D5) | 8 h, configurable |
| Q3 | Rotación (D4) | `kid` + una clave anterior opcional |
| Q4 | ¿Mantener los claims solo en `sub/iat/exp`? (D5) | Sí, tal como fija ADR-038 |
| Q5 | ¿Mitigar el oráculo de tiempo del login en B3? (D6) | Sí, con un hash ficticio; no cambia el contrato |

## Implementación (B3, tras la aprobación y con plan propio, regla 3.4)

- `contracts`: `AuthenticateAccountPort`, `TokenIssuerPort`, `TokenIssuanceException`.
- `identity`: `AuthenticateAccountService` (respeta las reglas ArchUnit del módulo).
- `api`: `JwtSecurityProperties` (fail-fast), `NimbusTokenIssuer` (implementa `TokenIssuerPort`), `JwtAuthFilter` (excluye `/api/v1/donations/tracking/**` y `/api/v1/auth/login`), `LoginController`.
- `app`: conexión de los puertos.
- Tests: indistinguibilidad byte a byte de los tres fallos; `alg: none` y otro algoritmo rechazados; firma inválida; `kid` desconocido; caducado y tolerancia de reloj; cuenta desactivada después de emitir el token → 401; fallo de `issue()` → 500; token de seguimiento en una ruta JWT → 401, y JWT en la ruta de seguimiento → 401; arranque rechazado sin secreto o con uno corto.
