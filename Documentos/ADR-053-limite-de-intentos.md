# ADR-053 — Límite de intentos (T-03; cierra DH-56)

## Status

**PROPUESTO — redactado según el plan aprobado; falta la firma del texto.** El usuario aprobó el 2026-11-14 el plan `plan-evolucion-producto.md`, cuya §2.3 fija el diseño de este ADR: filtro en `api` antes del JWT, ventana deslizante en memoria, `429` con `Retry-After` y `ProblemDetail` fijo, cuotas por configuración, la IP de `CF-Connecting-IP` solo detrás del túnel e implementación propia en lugar de Bucket4j. Este texto concreta ese diseño. Lo que añade sin estar escrito en el plan está marcado como **[detalle]**.

Cubre, por la regla 3.5, un mecanismo nuevo de protección frente a reintentos abusivos y el cierre del riesgo aceptado DH-56 (B3). No añade dependencias.

## Context

- No había ningún `429` en `*/src/main`. DH-56 aceptaba ese riesgo desde B3.
- Superficies expuestas sin límite: el login, el registro, la recuperación de contraseña, la verificación de correo, el seguimiento por `trackingCode` (adivinable por fuerza bruta), la consulta de intención por `Intent-Token`, la creación de intenciones, el webhook de pagos y las subidas de fotos.
- El despliegue es **una instancia** detrás de un túnel de Cloudflare (`cloudflared` en el mismo equipo, que conecta desde `127.0.0.1`).
- El `401` del login es uniforme y no revela si la cuenta existe (B3, `AuthenticateAccountPortImpl`). El límite no puede romper esa propiedad.

## Decision

- **D1 — Superficies (`RateLimitGroup`, la única lista).** Son ocho grupos: `login` (`/auth/login`, `/auth/google`), `registration`, `account-recovery` (recuperación y verificación de correo), `tracking` (`/donations/tracking/**`), `donation-intent-read`, `donation-intent-create`, `payment-webhook` y `media-upload` (logo, portada y evidencia). Una ruta que no está en la lista no tiene límite.
- **D2 — Algoritmo.** Ventana deslizante **exacta**: por cada clave, los instantes de las peticiones aceptadas, como mucho `limit`. Las rechazadas no alargan la ventana. **[detalle]** La decisión se toma bajo el monitor de la cola de esa clave, y una limpieza concurrente marca la cola que retira para que nadie siga contando en ella. Así, dos hilos nunca aceptan más de `limit`.
- **D3 — Clave.** `(grupo, IP del cliente)`. En `POST /api/v1/auth/login` se suma `(correo normalizado: recortado y en minúsculas)` con su propia cuota (`login-email`), para frenar la fuerza bruta contra una cuenta desde muchas IP.
  - **La IP** es la de la conexión. Solo si la conexión viene de un proxy de confianza configurado (`client-ip.trusted-proxies`, IP o CIDR) se toma `CF-Connecting-IP`, y solo si trae una única IP literal. **`X-Forwarded-For` nunca se lee.**
  - **[detalle]** El cuerpo del login se lee hasta 16 KB para encontrar el correo y se devuelve íntegro al controlador. Un cuerpo mayor se limita solo por IP.
- **D4 — Configuración y respuesta.**
  - Cuotas y ventanas en `traceability.rate-limit.*`, sin valores fijos en el código, con *fail-fast*: falta una cuota, una clave de grupo desconocida (una errata) o un valor no positivo impiden arrancar (`RateLimitConfigurationException`).
  - El rechazo es `429` con `Retry-After` en segundos (≥ 1) y un `ProblemDetail` fijo, idéntico byte a byte para todos los grupos.
  - **Se cuentan todas las peticiones del grupo**, no solo las fallidas: el `429` no depende de que la cuenta exista ni de la contraseña.
- **D5 — Memoria. [detalle]**
  - Una limpieza periódica (`sweep-interval`) borra las claves sin peticiones dentro de su ventana.
  - Con `max-keys` claves vivas incluso después de una limpieza forzada (como mucho una por segundo), **una clave nueva se rechaza (falla cerrado)**. Las ya presentes siguen igual.
- **D6 — Orden.** `FilterRegistrationBean` con orden `HIGHEST_PRECEDENCE + 10`: detrás del CORS (un *preflight* nunca cuenta) y delante de `JwtAuthFilter` y `TrackingCodeAuthFilter`. Las subidas protegidas se limitan antes de verificar el JWT.
- **D7 — Logs.** Nunca el correo, la IP ni el cuerpo; solo el nombre del grupo, en DEBUG.

Valores iniciales en `application.yml`, que el aprobador puede cambiar sin tocar el código. Las cuotas por IP son holgadas porque detrás de una IP puede haber muchas personas (campus, NAT):

| Grupo | Límite / ventana |
|---|---|
| `login` (por IP) | 30 / 5 min |
| `login-email` (por correo) | 10 / 15 min |
| `registration` | 10 / 1 h |
| `account-recovery` | 10 / 15 min |
| `tracking` | 120 / 1 min |
| `donation-intent-read` | 120 / 1 min (la web consulta el estado) |
| `donation-intent-create` | 10 / 10 min |
| `payment-webhook` | 300 / 1 min |
| `media-upload` | 30 / 10 min |

## Alternatives

| Opción | Veredicto |
|---|---|
| Bucket4j | Dependencia nueva y menos código propio. Descartada mientras haya una instancia (plan §2.3). Reconsiderarla si se escala |
| Ventana fija o contador ponderado | Admiten ráfagas de hasta 2× en el borde de la ventana. La exacta cuesta, como mucho, `limit` instantes por clave |
| Límite en Cloudflare | Depende de la cuenta y del plan; no protege si se accede sin túnel. Puede sumarse, no sustituye a este |
| Contar solo los fallos del login | Obligaría a distinguir la cuenta existente en el filtro; contar todo es más simple y no filtra nada |

## Consequences

- Con varias instancias, cada una cuenta por su lado (deuda consciente). Si se escala, el contador pasa a MongoDB con TTL, no a Redis (plan F-08 §4).
- Con `max-keys` lleno de claves vivas (un ataque desde muchas IP), las IP nuevas reciben `429` hasta que expiren sus ventanas. Se acepta: es fallar cerrado en superficies de credenciales.
- Detrás del túnel hay que configurar `TRACEABILITY_RATE_LIMIT_TRUSTED_PROXIES=127.0.0.1`. Sin ella, todo el tráfico del túnel comparte una sola IP y las cuotas se agotan entre todos. Queda escrito en `despliegue-backend-2026-10.md`.
- El `application.yml` de los tests de `app` lleva cuotas que ningún test ajeno al límite alcanza. Los tests del límite fijan las suyas.
- Pruebas: `SlidingWindowRateLimiterTest`, `ClientIpResolverTest`, `RateLimitFilterTest`, `RateLimitPropertiesTest` (api) y `RateLimitHttpIntegrationTest` (app, Tomcat real). Las mutaciones están en `Documentos/evidencia-ci/mutaciones-t03-*.txt`.
