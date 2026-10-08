# ADR-047 — JWT: librería, algoritmo y secreto de firma (D-JWT)

## Status
**APROBADO — Carlos, 2026-10-07**, con Q1–Q5 según la recomendación y las precisiones P1–P4 de la revisión, ya incorporadas en D4, D5, D7 y en la definición de hecho. Los riesgos residuales de §"Riesgos aceptados" los acepta Carlos por escrito. Redactado por el agente a petición de Carlos (decisión D-JWT de `plan-cierre-fase6-codigo.md`). Dueño: Identidad. Cubre la dependencia nueva (regla 3.5); el código de B3 necesita además su plan aprobado (regla 3.4).

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

## Decision

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
- **La clave anterior caduca (P2).** Una clave rotada por estar comprometida no puede seguir aceptándose indefinidamente porque nadie la quitó de la configuración:
  - Junto a la clave anterior es **obligatorio** `JWT_SIGNING_SECRET_PREVIOUS_ACCEPT_UNTIL` (instante ISO-8601). Si falta, la aplicación no arranca.
  - Ese instante no puede quedar más allá de **arranque + `exp`**: no hace falta más para que caduquen los tokens firmados con ella. Si queda más allá, la aplicación no arranca.
  - Pasado ese instante, los tokens de la clave anterior dan 401, aunque siga configurada.
  - Mientras esté configurada, un **WARN al arrancar** recuerda retirarla, con su `kid` y la fecha de fin, nunca con el secreto (D7).
- Rotar = mover la actual a "anterior", poner una nueva y, pasado `exp`, retirar la anterior. Sin *vault*: coherente con `estado-fase3.md:132`.
- Si se prefiere no tener rotación en este corte, la alternativa es solo `kid` fijo y rotación con cierre de todas las sesiones (todos los usuarios vuelven a entrar). Ver Q3.

### D5. Claims y validación

- Claims: **solo** `sub` (= `accountId`), `iat`, `exp` (ADR-038). No se añaden `iss`/`aud`/`jti` (añadirlos sería ampliar ADR-038; ver Q4).
- **Validación en el filtro, en este orden exacto (P1):**
  1. Parsear la cabecera. El `alg` debe ser `HS256`; cualquier otro, incluido `none`, da 401 sin seguir.
  2. Leer el `kid` y **elegir la clave**. El `kid` **solo se busca como clave del mapa de claves configuradas** (actual y anterior vigente). Nunca se usa para construir una ruta, una consulta, un nombre de fichero ni nada parecido: es un vector conocido de inyección. Un `kid` desconocido da 401.
  3. **Verificar la firma** con la clave elegida.
  4. Comprobar `exp`, con una tolerancia de reloj de **30 s**.
  5. `resolvePrincipal(sub)`, que rechaza las cuentas `INACTIVE` e inexistentes.
- **Cualquier** fallo del filtro → 401 con **el mismo cuerpo** `ProblemDetail` (DH-51; "401 uniforme"): token ausente, mal formado, firma inválida, caducado, `kid` desconocido, cuenta `INACTIVE` o inexistente.
- `exp`: **8 horas** por defecto, configurable (`traceability.security.jwt-ttl`). Motivo: sin *refresh* (ADR-043), un `exp` corto obliga a reentrar varias veces por jornada en campo; y la desactivación no depende de `exp`, porque `resolvePrincipal` se ejecuta en cada request. Es un valor de producto: lo confirma Carlos (Q2).

### D7. Nunca registrar el token ni el secreto (P3)

- Ni el token, ni el secreto, ni la cabecera `Authorization` aparecen en ningún log, **ni completos ni en parte** (ni prefijos, ni sufijos, ni hashes truncados), en ningún nivel.
- Tampoco en los mensajes de excepción ni en el cuerpo de error (`ProblemDetail`).
- Los logs pueden incluir el `kid` y el motivo interno del rechazo, nunca el material criptográfico.
- El PR de B3 lo comprueba con un test que captura los logs de un login y de un rechazo y busca el token y el secreto.

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

## Riesgos aceptados — Carlos, 2026-10-07

Riesgos residuales que se aceptan para la demo. Constan como **decisión**, no como olvido:

1. **Quien conozca el secreto puede emitir tokens** (HS256, emisor y verificador en el mismo monolito). Mitigación: secreto propio, *fail-fast*, rotación con fin de aceptación (D3, D4) y nunca en logs (D7).
2. **Un token robado sirve hasta 8 h mientras la cuenta siga `ACTIVE`.** Mitigación: desactivar la cuenta surte efecto en la siguiente request, porque `resolvePrincipal` se ejecuta en cada una.
3. **Sin límite de intentos (DH-56) el login admite fuerza bruta.** Como BCrypt(12) consume CPU, también facilita la **denegación de servicio**. El límite de intentos sigue abierto en `propuesta-apis-fase6.md`.

## Preguntas — respondidas por Carlos el 2026-10-07 (todas según la recomendación)

| # | Pregunta | Decisión |
|---|---|---|
| Q1 | Librería (D1) | Nimbus JOSE + JWT, solo en `api` |
| Q2 | Duración de `exp` (D5) | 8 h, configurable |
| Q3 | Rotación (D4) | `kid` + una clave anterior opcional, **con fin de aceptación obligatorio (P2)** |
| Q4 | ¿Mantener los claims solo en `sub/iat/exp`? (D5) | Sí, tal como fija ADR-038 |
| Q5 | ¿Mitigar el oráculo de tiempo del login en B3? (D6) | Sí, con un hash ficticio; no cambia el contrato |

## Definición de hecho de B3 (P4)

Lista mínima de tests. Sin ellos, B3 no se fusiona:

1. `alg: none` → 401.
2. Otro algoritmo (HS512, RS256) → 401.
3. Payload alterado (firma que ya no corresponde) → 401.
4. Token caducado → 401; caducado hace menos de 30 s → aceptado.
5. `kid` desconocido → 401, y el `kid` no se usa en ninguna búsqueda fuera del mapa de claves.
6. Clave anterior aceptada mientras está configurada y vigente; rechazada al retirarla y pasado su fin de aceptación.
7. La aplicación no arranca: sin `JWT_SIGNING_SECRET`; con uno de menos de 32 bytes; con clave anterior sin fin de aceptación, o con un fin más allá de arranque + `exp`.
8. Las tres causas de fallo del login (email inexistente, contraseña incorrecta, `INACTIVE`) dan **respuestas idénticas** (código y cuerpo, byte a byte), y el **tiempo no revela** si el email existe (Q5: se compara contra un hash ficticio cuando no existe).
9. Ni el token ni el secreto aparecen en los logs (D7).
10. Cuenta desactivada después de emitir el token → 401 en la siguiente request.
11. Un fallo de `issue()` → 500, nunca 401.
12. Separación de mecanismos: un token de seguimiento en una ruta JWT → 401, y un JWT en la ruta de seguimiento → 401.

## Implementación (B3, tras la aprobación y con plan propio, regla 3.4)

- `contracts`: `AuthenticateAccountPort`, `TokenIssuerPort`, `TokenIssuanceException`.
- `identity`: `AuthenticateAccountService` (respeta las reglas ArchUnit del módulo).
- `api`: `JwtSecurityProperties` (fail-fast), `NimbusTokenIssuer` (implementa `TokenIssuerPort`), `JwtAuthFilter` (excluye `/api/v1/donations/tracking/**` y `/api/v1/auth/login`), `LoginController`.
- `app`: conexión de los puertos.
- Tests: la definición de hecho de arriba (P4). Plan detallado en `plan-b3-jwt.md`.
