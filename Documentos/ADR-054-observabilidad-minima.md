# ADR-054 — Observabilidad mínima: salud interna y logs estructurados (D-01)

## Status

**PROPUESTO — redactado según el plan aprobado; falta la firma del texto.** El usuario aprobó el plan `plan-evolucion-producto.md` el 2026-11-14. Su §2.5 fija:

- `spring-boot-starter-actuator` con solo `health` (con Mongo) e `info`, en un puerto de gestión que el túnel no publica;
- logs JSON en el perfil del servidor;
- MDC con `correlationId`, `streamId`, `eventId` y `batchId`, también en los *schedulers*;
- `paxfide.sh estado` consulta la salud interna.

Lo que este texto añade sin estar escrito en el plan está marcado como **[detalle]**.

Por la regla 3.5 cubre una dependencia nueva: `spring-boot-starter-actuator`, versión gestionada por el BOM de Spring Boot.

## Context

- No había forma de saber si el backend estaba vivo salvo llamando a una ruta pública ni de seguir una petición o una vuelta de un *scheduler* en el log. El `correlationId` solo existía en los `500`, generado en `ApiExceptionHandler` y sin relación con nada más.
- Pendiente abierto: «origen del `correlationId` de *scheduler*».
- El servidor publica el puerto 8080 por un *quick tunnel* de Cloudflare (`paxfide.sh`, `tunnels()`); ningún otro.

## Decision

- **D1 — Salud.**
  - `health` e `info` solamente (`management.endpoints.access.default: none`), en `MANAGEMENT_SERVER_PORT` (8081 por defecto), escuchando en `127.0.0.1`. En el puerto de la API, `/actuator/**` no existe (404).
  - `health` incluye MongoDB y muestra los componentes, porque solo responde en local.
  - **[detalle]** El indicador del correo está apagado: un SMTP caído no hace que el backend esté «DOWN». Ya se ve en el log de cada envío.
- **D2 — Logs JSON.** El perfil `servidor` (`application-servidor.yml`) activa `logging.structured.format.console: ecs`, el formato nativo de Spring Boot 3.4. Las claves del MDC salen como campos. Sin el perfil, los logs siguen en texto (desarrollo).
- **D3 — MDC.**
  - **Petición:** `CorrelationIdFilter` (`api`, orden `HIGHEST_PRECEDENCE + 5`, antes del límite de intentos y del JWT) pone un `correlationId` nuevo, siempre generado por el servidor, nunca aceptado de la petición. `ApiExceptionHandler` reutiliza ese mismo valor en el cuerpo y el log de un `500`. **[detalle]** Al terminar escribe una línea de acceso con el método, el **patrón** de la ruta (sin identificadores), el estado y la duración.
  - **Evento:** `MongoEventStoreAdapter` escribe una línea por evento escrito, con `streamId`, `sequence`, `eventType` y `eventId`, nunca el *payload*. Lleva el `correlationId` de quien lo escribió.
  - **Proyecciones:** el *listener* del *change stream* pone `eventId` y `streamId` mientras proyecta cada evento.
  - **Scheduler:** un `TaskDecorator` sobre el `ThreadPoolTaskScheduler` de Boot da a cada vuelta de cada `@Scheduled` su `correlationId` (`sched-<uuid>`) y lo limpia al terminar. **Cierra el pendiente.** El anclaje añade `batchId` mientras procesa un lote.
- **D4 — `paxfide.sh estado`** consulta `http://127.0.0.1:$MANAGEMENT_SERVER_PORT/actuator/health`.

## Alternatives

| Opción | Veredicto |
|---|---|
| Ruta de salud propia en la API | Sin dependencia, pero pública detrás del túnel y sin el indicador de Mongo ya hecho |
| Logback con `logstash-encoder` | Dependencia nueva; Boot 3.4 ya trae ECS |
| `correlationId` aceptado de la cabecera `X-Request-Id` | Útil detrás de un proxy que lo genere; hoy no lo hay. Aceptarlo permitiría inyectar valores en los logs |
| Micrometer, métricas y Prometheus | Fuera de alcance (D-01 mínimo). Se puede añadir sobre el mismo actuator con su propio ADR |

## Consequences

- Una línea de log por petición y una por evento: volumen bajo en este despliegue.
- El scheduler de recuperación de fondos ya ponía su propio `correlationId` por intención y lo quitaba. Durante el resto de su vuelta no lleva ninguno, salvo el que le da el decorador al empezar. Se acepta.
- Para los logs JSON en el servidor hay que poner `SPRING_PROFILES_ACTIVE=servidor` en `deploy/backend.env`. Queda escrito en `despliegue-backend-2026-10.md`.
- Pruebas en `app`, contra Tomcat real:
  - `ObservabilityHttpIntegrationTest`: health en el puerto de gestión y 404 en el de la API; el mismo `correlationId` en la línea de acceso y en la del evento de una petición, y distinto entre peticiones; un `correlationId` por vuelta de *scheduler*; ni la contraseña, ni el JWT, ni `Bearer`, ni el correo en el log;
  - `HealthWithMongoDownIntegrationTest`: 503 y DOWN con Mongo parado.
