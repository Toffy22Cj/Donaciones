# Plan Detallado de Implementación — Fase 3

**Propósito de este documento:** mapear cada pieza restante de Fase 3 como un punto de decisión explícito, con opciones y recomendación, para que la discusión avance punto por punto sin descubrir decisiones sobre la marcha. No reemplaza el rigor de Modo de Arquitectura para cada pieza — lo organiza.

**Estado de entrada:** deuda técnica de Fase 2 resuelta (ver `estado-fase3.md` sección 5). ADR-020, ADR-021-A/B/C/D congelados. `PublicDonationTrackingDTO` diseñado y actualizado.

---

## Orden de trabajo (ya decidido, sección 9 de `estado-fase3.md`)

```
1. Mapper (DonationProjectionDocument → PublicDonationTrackingDTO)
2. Mecanismo de tracking code
3. Controlador del primer endpoint
4. Segundo endpoint (historial de activo)
5. Orquestación con ai
```

Cada bloque abajo sigue este orden. Dentro de cada uno, los puntos están ordenados por lo que bloquea al resto.

---

## BLOQUE 1 — Mapper (`DonationProjectionDocument` → `PublicDonationTrackingDTO`)

### 1.1 Tabla de referencia de ubicaciones (bloqueante para `locationZone`)

**Ya decidido:** mapeo determinista a ciudad, nunca heurística de texto, `null` si no está en la tabla.

**Falta decidir:**
- ¿Dónde vive la tabla? Opciones:
  - (a) Colección Mongo nueva (`location_reference`), administrable sin redeploy.
  - (b) Archivo de configuración estático (`application.yml` o `.json` embebido), requiere redeploy para actualizar.
- ¿Quién la puebla inicialmente? Dado que hoy no existe ninguna lista de "bodegas/ubicaciones conocidas" en el sistema, alguien tiene que construirla a mano con datos reales de operación.

**Mi recomendación:** (a) colección Mongo, porque el conjunto de ubicaciones va a crecer con el uso real (nuevas bodegas, nuevas ciudades de operación) y no queremos que cada alta requiera un despliegue. Empezar con una tabla vacía o mínima (2-3 ubicaciones de prueba) es aceptable para el primer incremento — el propio diseño ya contempla `null` como resultado válido cuando falta la entrada.

**Nivel de bloqueo:** medio — el mapper puede escribirse con la interfaz de consulta a la tabla ya definida, aunque la tabla esté vacía al principio.

### 1.2 Cálculo de `assetRef` y gestión del secreto HMAC

**Ya decidido:** HMAC-SHA-256 completo, dominio `"asset-ref:v1:"`, secreto dedicado y distinto del de tracking code.

**Falta decidir:**
- ¿El secreto vive en variable de entorno directa, o en un archivo `.env`/`secrets.yml` no versionado leído por Spring? (Dado el contexto académico, ambas son aceptables — la diferencia real solo importa si esto llegara a producción real).
- ¿Se genera el secreto ahora mismo (valor fijo para desarrollo) o se deja como placeholder documentado hasta el despliegue final?

**Mi recomendación:** variable de entorno vía `@ConfigurationProperties`, con un valor de desarrollo generado ahora (ej. `openssl rand -hex 32`) documentado en el `README` o en un `.env.example`, nunca committeado el valor real.

**Nivel de bloqueo:** bajo — es configuración, no diseño. Se puede resolver en minutos cuando se llegue a implementar.

### 1.3 Estructura interna del mapper (dónde vive el código)

**Falta decidir:**
- ¿Un solo `PublicDonationTrackingDtoMapper` monolítico, o mappers separados por sub-objeto (`FinancialSnapshotMapper`, `LogisticsItemMapper`)?
- ¿El mapper vive en `api` (module nuevo) consumiendo `core` solo por sus puertos de lectura, o necesita acceso directo a `DonationProjectionDocument` (lo cual violaría la frontera de módulo, ya que ese documento es interno de `core.infrastructure`)?

**Este último punto es importante y no lo hemos resuelto:** `DonationProjectionDocument` es una clase de infraestructura de `core` (`core.infrastructure.projection.mongo.documents`). Si `api` necesita mapear directamente desde ahí, `api` tendría que depender de una clase de infraestructura interna de `core` — lo cual contradice ADR-020 (`api` depende solo de puertos de lectura de `core`, no de sus documentos internos).

**Mi recomendación:** definir un puerto de lectura nuevo en `contracts` o en `core.application.port.out` (análogo a `AuditFactsPort`) — algo como `DonationReadPort` con un método `Optional<DonationReadModel> findByFundId(String fundId)`, donde `DonationReadModel` es un DTO de lectura propio de `core.application`, no el documento Mongo crudo. `api` mapea desde `DonationReadModel` a `PublicDonationTrackingDTO`, nunca desde `DonationProjectionDocument` directamente.

**Nivel de bloqueo:** ALTO — esto es una decisión de arquitectura real que afecta cómo se escribe todo lo demás. Recomiendo resolver esto primero, en su propia mini-sesión de Modo de Arquitectura, antes de escribir el mapper.

---

## BLOQUE 2 — Mecanismo de tracking code

### 2.1 Implementación exacta del HMAC y la expiración embebida

**Ya decidido:** stateless, HMAC-SHA-256 completo, dominio `"tracking:v1:" + fundId`, expiración embebida.

**Falta decidir:**
- Formato exacto del token: ¿`Base64URL(fundId + "|" + expiryTimestamp + "|" + HMAC(...))`, o un formato más estructurado tipo JWT (con sus propios claims estándar)?
- ¿Cuánto dura la expiración? ¿Es fija (ej. 1 año) o configurable por campaña?

**Mi recomendación:** formato simple propio, no JWT completo — JWT trae una librería nueva y una superficie de features (algoritmos alternativos, claims estándar) que no necesitamos para este caso de uso acotado. Expiración fija de 1 año como default, configurable vía `@ConfigurationProperties` si se necesita variar después.

**Nivel de bloqueo:** medio — se puede empezar a implementar con un formato simple y ajustar.

### 2.2 Lista de revocación dispersa — estructura de la colección

**Ya decidido:** `revoked_tracking_codes`, solo `tokenHash` + `revokedAt`, no un registro de cada token emitido.

**Falta decidir:**
- ¿Quién puede revocar un tracking code? (Sin módulo de identidad todavía, probablemente esto empieza como una operación administrativa manual, similar en espíritu a `resolveStuckBatch` — JMX o algo equivalente.)
- ¿Se necesita un TTL en la colección de revocados, o se mantienen indefinidamente? (Dado que el token ya expira solo por su propia fecha embebida, revocados con fecha de expiración ya pasada son basura acumulada — un TTL igual a la expiración máxima del token tiene sentido.)

**Mi recomendación:** exposición vía JMX (mismo patrón ya usado y aprobado para `resolveStuckBatch`), con TTL en la colección igual al máximo de expiración de un token (1 año, según 2.1).

**Nivel de bloqueo:** bajo — no bloquea el camino feliz, solo hace falta antes de considerar el mecanismo "completo".

### 2.3 Filtro de autorización — dónde y cómo se valida

**Falta decidir:**
- ¿Spring Security con un filtro custom, o un interceptor/`HandlerInterceptor` más liviano sin traer todo Spring Security como dependencia nueva?
- Dado que hoy el proyecto no tiene ninguna dependencia de seguridad (`spring-boot-starter-security` no está en el stack), esto es una **dependencia nueva** que, según la Regla de Oro del proyecto, requiere justificación explícita antes de agregarse.

**Mi recomendación:** no traer Spring Security completo para un solo mecanismo de validación de token stateless. Un `HandlerInterceptor` o `OncePerRequestFilter` simple, escrito a mano, que valide el HMAC y la expiración, es suficiente y evita una dependencia pesada con mucha superficie no usada (autenticación de usuarios, roles, sesiones — nada de eso aplica aquí).

**Nivel de bloqueo:** medio — es una decisión de dependencia que sí necesita pasar por el mismo proceso de justificación que cualquier otra ("qué problema resuelve, por qué el stack actual no basta, costo, alternativas").

### 2.4 Generación y entrega del tracking code — flujo de origen

**Pregunta abierta de larga data, nunca resuelta esta sesión:** ¿en qué momento del flujo se genera el tracking code y cómo llega al donante? Esto depende de un flujo de creación de `Fund` que hoy no tiene ningún punto de entrada HTTP (no hay endpoint de creación de donación — solo lectura, según el alcance actual de Fase 3).

**Esto puede requerir una decisión de producto que está fuera del alcance de "solo lectura":** si Fase 3 es estrictamente de lectura, ¿cómo se genera el primer tracking code? Posibilidades:
- (a) Fase 3 incluye un endpoint mínimo de generación bajo demanda (`GET /tracking-code/{fundId}` protegido de alguna otra forma, quizás solo accesible desde un backend interno de la pasarela de pago) — esto amplía el alcance de "solo lectura".
- (b) El tracking code se calcula completamente fuera de este sistema (en el backend de la pasarela de pago, que ya conoce el `fundId` al momento de crear el `Fund`) usando el mismo algoritmo HMAC, y Fase 3 solo lo valida, nunca lo genera.

**Mi recomendación:** (b) — mantiene Fase 3 genuinamente de solo lectura. El HMAC es determinista y stateless, así que cualquier sistema que conozca el secreto y el `fundId` puede calcularlo, no hace falta que sea el mismo servicio que lo valida. Pero esto significa que el secreto HMAC tiene que ser compartido con quien sea que genere el `Fund` originalmente — lo cual es una decisión de infraestructura entre sistemas que no hemos definido (¿es el mismo proceso Spring Boot? ¿un sistema externo?).

**Nivel de bloqueo:** ALTO — esto es una pregunta que toca el límite de qué sistema hace qué, y no se puede posponer indefinidamente. Recomiendo resolverla en la misma sesión que el Bloque 1.3.

---

## BLOQUE 3 — Controlador del primer endpoint

### 3.1 Manejo de errores HTTP

**Falta decidir:** códigos de estado exactos para: token inválido/expirado (401? 403?), `fundId` no encontrado (404? o 401 para no revelar si el fundId existe — comportamiento tipo "oráculo" que evita enumeración), proyección en `PAUSED`/error interno (500? 200 con `EN_PROCESO`?).

**Mi recomendación:** token inválido/expirado/revocado → 401 uniforme (no distinguir "no existe" de "inválido", para no dar pistas de enumeración a un atacante probando tokens). Proyección pausada → 200 normal con `status: EN_PROCESO` (ya lo decidimos, es información legítima, no un error).

**Nivel de bloqueo:** bajo — se resuelve rápido, no depende de nada más.

### 3.2 Formato de error (RFC 7807 Problem Details, o formato propio)

**Mi recomendación:** RFC 7807 (`application/problem+json`), que Spring Boot 3 soporta nativamente sin dependencias nuevas — es el estándar razonable y no cuesta nada adoptarlo.

**Nivel de bloqueo:** ninguno — trivial.

---

## BLOQUE 4 — Segundo endpoint (historial de activo)

### 4.1 Diseño del DTO (pendiente, mencionado varias veces, nunca hecho)

Ya tenemos el perímetro de exposición clasificado (ADR-021-D aplica a `AssetTransition`). Falta el `record` en sí — es mecánico, aplicando exactamente las mismas reglas que ya usamos para `PublicLogisticsItemDTO`.

**Nivel de bloqueo:** bajo — puramente de ejecución, no de decisión.

### 4.2 Autorización del segundo endpoint

**Falta decidir:** ¿el mismo tracking code del `fundId` autoriza consultar el historial de *cualquier* `assetRef` asociado a ese fondo, o hace falta una verificación adicional de que el `assetRef` pertenece realmente a ese `fundId` (vía `asset_index`)?

**Mi recomendación:** sí, verificación explícita contra `asset_index` (que ya existe y es exactamente para esto — resolver `assetId → projectionId`) antes de servir el historial. Sin esa verificación, alguien con un tracking code válido para el Fund A podría enumerar `assetRef` de otros Funds y ver su historial.

**Nivel de bloqueo:** medio — es una decisión de seguridad real, no trivial, hay que resolverla antes de implementar el segundo endpoint.

---

## BLOQUE 5 — Orquestación con `ai`

Esta es la pieza más compleja de las cinco y la única que sigue sin ningún diseño, ni siquiera preliminar. Recomiendo que sea su propia sesión completa de Modo de Arquitectura, separada de todo lo demás. Preguntas que esa sesión tiene que resolver (ya las habíamos anotado, las repito aquí para que queden en un solo lugar):

1. Contrato nuevo en `contracts` (puerto inverso a `AuditFactsPort`) para que `api` obtenga la narrativa sin depender de `ai` directamente.
2. Quién implementa ese puerto — adaptador en `app`, o el propio `ai` lo expone.
3. Síncrono (bloquea la respuesta HTTP) vs. asíncrono (responde sin narrativa la primera vez).
4. Confirmar que ningún dato fuera del perímetro ya clasificado llega a `ai` por esta vía nueva.

**Nivel de bloqueo:** esto no bloquea nada de los Bloques 1-4 — el campo `narrativeSummary` puede quedar como `null`/ausente en el DTO hasta que esta pieza se resuelva por separado.

---

## Resumen — qué decidir primero, en orden de urgencia real

| Prioridad | Punto | Bloque | Por qué primero |
|---|---|---|---|
| 1 | Puerto de lectura nuevo (`DonationReadPort`) vs. acceso directo a documento Mongo | 1.3 | Sin esto, no se puede escribir NINGÚN código del mapper sin violar ADR-020 |
| 2 | Origen del tracking code — quién lo genera y cuándo | 2.4 | Determina si Fase 3 sigue siendo "solo lectura" o se amplía |
| 3 | Dependencia de seguridad para el filtro de autorización | 2.3 | Requiere justificación formal antes de traerla, mejor resolverlo temprano |
| 4 | Verificación `asset_index` en el segundo endpoint | 4.2 | Decisión de seguridad, no trivial |
| 5 | Todo lo demás (tabla de ubicaciones, formato HMAC, códigos HTTP, DTO del segundo endpoint) | varios | Bajo riesgo, se resuelve rápido o sobre la marcha |
| — | Orquestación con `ai` | 5 | Independiente, no bloquea nada, su propia sesión aparte |

**Recomendación de secuencia de discusión:** resolver los puntos 1-4 de la tabla en una sola sesión de Modo de Arquitectura (son pocas decisiones, pero todas de peso real), y con eso congelado, la implementación de los Bloques 1-4 puede avanzar de corrido sin más paradas de diseño — dejando solo el Bloque 5 (orquestación con `ai`) como la única pieza que sigue necesitando su propia conversación completa.
