# Plan B6-0 — Base HTTP para los endpoints de la demo

**Estado:** **PROPUESTO** (2026-10-07). Necesita la aprobación de Carlos antes de cualquier código (regla 3.4).
**Origen:** `propuesta-d-api.md` (APROBADO — Carlos, 2026-10-07): A1 (controladores en `api` o en `app.web`), A9 (`Command-Id`), Q10 (B6 en cinco PR; este es el primero).
**Desbloquea:** B6-a, B6-b, B6-c y B6-d, que solo añaden controladores sobre esta base.
**Revisión:** cubierto por la excepción a la regla 3.2.

---

## 1. Hechos del código (`develop` tras #55)

- No hay `@ControllerAdvice` ni `@ExceptionHandler` en ningún módulo. Hoy un error de dominio en un controlador daría el 500 genérico de Spring.
- El principal está en el atributo `authorizationPrincipal` de la request (`JwtAuthFilter.PRINCIPAL_ATTRIBUTE`). `HumanActor` es `record HumanActor(String accountId)` y nadie lo construye desde HTTP.
- `api` depende de `core` y `contracts`; `app` depende de `api` y del resto de módulos. Un `@ControllerAdvice` o un `HandlerMethodArgumentResolver` de `api` se aplica también a los controladores de `app`, porque los dos están en el mismo contexto.
- Excepciones de `core` que llegarán a HTTP: autorización (`CrossOrganizationAccessException`, `InsufficientRoleException`), invariantes de dominio (`DomainInvariantViolationException` y sus subclases: transiciones inválidas, cantidades, `AssetTerminalStateException`…), datos inexistentes (`AggregateNotFoundException`), concurrencia (`ConcurrencyConflictException`, `ConcurrencyRetryExhaustedException`), convocatoria en especie (`CampaignNotEligibleForInKindDonationException` y subclases) y argumentos (`IllegalArgumentException`).
- Las rutas de Fase 3 (seguimiento) y el login ya devuelven `ProblemDetail` por su cuenta y no deben cambiar.

## 2. Cambios

### 2.1 `api`: actor actual
- **`@CurrentActor`**, una anotación de parámetro con su `HandlerMethodArgumentResolver`:
  - un parámetro `HumanActor` se resuelve con el `accountId` del principal;
  - un parámetro `AuthorizationPrincipal`, con el principal completo;
  - en una ruta con JWT obligatorio, la falta de principal es un error de programación (el filtro ya habría respondido 401) y da 500 con log ERROR, nunca un actor nulo;
  - en una ruta con **JWT opcional**, el parámetro debe ser `Optional<HumanActor>`; si no lo es, el arranque falla con un mensaje claro.
- **Regla ArchUnit:** ningún controlador de `api` ni de `app.web` lee el atributo `authorizationPrincipal` ni la cabecera `Authorization` directamente. Siempre usa `@CurrentActor`.

### 2.2 `api`: `Command-Id` (T-33, ampliado por A9)
- **`@CommandId`**, una anotación de parámetro (`String`) con su resolver. Lee la cabecera `Command-Id`:
  - ausente, vacía o que no sea un UUID → **400** `ProblemDetail`, antes de llamar a ningún caso de uso;
  - si es válida, se pasa normalizada (minúsculas).
- La **semántica de duplicado** ("mismo código y mismo cuerpo") la garantiza cada caso de uso: ids deterministas en `core` (división, y registro en B6-c) y resultado original en `convocatoria`. B6-0 no guarda respuestas.

### 2.3 `api`: errores (`ProblemDetail`, DH-02; 401/403, DH-51)
- **`ApiExceptionHandler`** (`@RestControllerAdvice`, `@Order(LOWEST_PRECEDENCE)`), con esta tabla:

| Excepción | HTTP | Cuerpo |
|---|---|---|
| `CrossOrganizationAccessException`, `InsufficientRoleException` | **403** | Mismo `ProblemDetail` para las dos: no revela si el recurso es de otra organización o si falta un rol |
| `AggregateNotFoundException` | 404 | Sin el id |
| `DomainInvariantViolationException` y subclases | **409** | `title` con el nombre de la regla (p. ej. `InvalidAssetTransition`), `detail` genérico |
| `CampaignNotEligibleForInKindDonationException` | 409 | **El mismo** para "no existe" y "es de otra organización" (D-CAMPAIGN) |
| `IllegalArgumentException`, cuerpo ilegible, validación | 400 | Sin eco del valor recibido |
| `ConcurrencyConflictException`, `ConcurrencyRetryExhaustedException` | 409 | `title` `ConcurrentModification`; el cliente puede reintentar con el mismo `Command-Id` |
| Cualquier otra | 500 | `detail` genérico, sin traza ni mensaje interno; log ERROR con un id de correlación que sí aparece en el cuerpo |

- **Ningún cuerpo de error contiene** el mensaje de la excepción, ids internos ni datos de la petición: los mensajes de dominio pueden incluir ids o valores.
- **`app`** podrá añadir su propio `@RestControllerAdvice` para las excepciones de `convocatoria`/`identity`, con una prioridad mayor (`@Order` más alto). B6-a lo usa.
- No toca los controladores de Fase 3 ni el login, que ya componen su propio `ProblemDetail`.

### 2.4 Convención de ubicación (A1, Q1)
- Controladores que solo usan `core` → `api` (`com.traceability.api.<flujo>`).
- Controladores que cruzan módulos → `app` (`com.traceability.app.web.<flujo>`).
- **Regla ArchUnit en `app`:** los controladores de `app.web` no acceden a repositorios ni a infraestructura; solo a casos de uso.

## 3. Tests

**Primero los que deben fallar**, con la salida literal de Surefire en el PR. Se usa un controlador **solo de test** por caso (como en B3).

| # | Caso | Tipo |
|---|---|---|
| 1 | `@CurrentActor HumanActor` recibe el `accountId` del JWT; `AuthorizationPrincipal`, el principal completo | MockMvc y Tomcat real |
| 2 | En una ruta de JWT opcional sin cabecera, `Optional<HumanActor>` está vacío; con un JWT válido, lleno | Tomcat real |
| 3 | Un parámetro `HumanActor` (no `Optional`) en una ruta de JWT opcional → el contexto no arranca | `ApplicationContextRunner` |
| 4 | `Command-Id` ausente, vacío o que no sea UUID → 400, y el caso de uso **no se llama** | MockMvc |
| 5 | Cada fila de la tabla de §2.3 → código y `ProblemDetail` esperados; 403 idéntico byte a byte entre las dos excepciones de autorización | MockMvc |
| 6 | Ningún cuerpo de error contiene el mensaje de la excepción ni el valor recibido (excepciones con un marcador en el mensaje) | MockMvc |
| 7 | 500: cuerpo genérico con un id de correlación; el mismo id aparece en el log ERROR; sin traza | MockMvc y captura de logs |
| 8 | Las rutas de Fase 3 y el login siguen devolviendo sus cuerpos de siempre (tests existentes en verde) | existentes |
| 9 | Reglas ArchUnit de §2.1 y §2.4 | ArchUnit |

**Mutaciones:**
- devolver el mensaje de la excepción en el `detail`;
- 403 distinto para cada excepción de autorización;
- aceptar un `Command-Id` que no sea UUID;
- resolver `HumanActor` nulo en vez de fallar;
- quitar el id de correlación del 500.

**Verificación:** reactor completo, con la salida literal en el PR y el archivo de evidencia; mutaciones con `scripts/mutaciones.py`.

## 4. Entrega
- **Un PR, `feat/b6-0-base-http`**, que toca `api` y las reglas ArchUnit de `app`.
- **Commits:** tests en rojo con esqueleto → resolvers → manejador de errores → ArchUnit → documentos y evidencia (HECHO solo después de la evidencia, regla 2.4).

## 5. Fuera de alcance
- Cualquier endpoint de negocio: B6-a a B6-d.
- Rate limiting (DH-56), CORS y OpenAPI.

## 6. Preguntas para Carlos

| # | Pregunta | Recomendación |
|---|---|---|
| Q1 | ¿Invariantes de dominio → **409**, y no 422 ni 400? | 409: es un conflicto con el estado actual del recurso. 400 queda para la forma de la petición. 422 no está en DH-02 |
| Q2 | ¿El mismo 403 para "otra organización" y "falta un rol"? | Sí: no revela nada sobre el recurso (DH-51) |
| Q3 | ¿500 con un id de correlación en el cuerpo y en el log? | Sí: soporte puede encontrar el error sin exponer la traza |
