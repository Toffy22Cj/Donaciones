# ADR-050 — Cuenta y sesiones: registro en dos pasos, aceptaciones legales, contraseña, revocación, desactivación y Google

## Status

**APROBADO — Carlos, 2026-10-18.** Redactado por el agente a petición de Carlos (tareas de backend de Fase 7: M-27, M-30, M-33, M-34).

Aprobaciones de Carlos, explícitas en la conversación de trabajo:
- **2026-10-18T18:05Z:** D1 a D9 en la opción (a), la regla de `credentialsNotBefore`, el envío diferido del correo de recuperación, la minimización del correo en la auditoría, el enfriamiento de 5 minutos y la reserva de ADR-050 y ADR-051.
- **2026-10-18T18:26Z y 18:34Z (aprobación definitiva):** el diseño de detalle de D1 a D10 (rutas, códigos de error, puertos aditivos, colección de recuperación, envío diferido, verificación de Google), los cuatro riesgos aceptados y las preguntas Q1 a Q5 (§"Preguntas").

Aprobarlo **no** autoriza código: cada tarea (R-2 a R-6) necesita su plan aprobado (regla 3.4).

Dueño: Identidad. Cubre, por la regla 3.5:
- un mecanismo nuevo de revocación de sesiones;
- un mecanismo nuevo de envío diferido de correo;
- la ampliación de dos puertos de `contracts` (`IdentityPrincipalPort`, `TokenIssuerPort`);
- una integración externa nueva (Google), sin librería nueva.

**Enmienda ADR-047** (D5, validación del token) y **complementa** ADR-026, ADR-038 §2.7 y ADR-049 sin contradecirlos.

> **Nota (2026-10-25, sesión de Manuel, con asistencia de Claude): enmiendas de este ADR.** El texto de este ADR se conserva. Las enmiendas van en `ADR-050-enmienda-1-iat-del-login.md`:
> - **Parte 1 (E1-1, E1-2), enmienda de D4 (P-R3-3):** el login emite con `iat = max(segundo actual truncado, credentialsNotBefore)`, y `AuthenticateAccountPort.authenticate` devuelve también `credentialsNotBefore`, leído en la misma lectura. Está en `develop` (#120, `8e37667`), pero dice **PROPUESTA** (`ADR-050-enmienda-1-iat-del-login.md:5`) y #120 se fusionó sin review. Pregunta abierta para Carlos: ¿cuenta como aprobación? El plan corto de su código es #128 (BORRADOR).
> - **Parte 2, enmienda de ADR-038 E1 (P-R4-1 (a)):** el actor de `CREDENTIALS_CHANGED` en la confirmación de la recuperación (R-4). En #125, abierto; espera el Approve de Carlos.
> - **Posible parte 3 (P-R5-1, plan R-5 en #122):** D3 (`403 AcceptanceRequired`) exige cambiar `AuthenticateAccountPort`, y eso choca con Q1 (a) ("no toca el filtro ni el puerto", §"Preguntas", fila Q1; línea 197 antes de esta nota). No está redactada; la decide Carlos.
> - **Implementación:** R-3 + R-2 (tarea 1 de §"Implementación") en #127, abierto y en verde, sin la parte 1 (P-R3-3 queda como defecto conocido en `referencia-api-v1.md`).
> - Mientras ninguna parte esté aprobada, rigen D4 y Q1 tal como están escritos.

## Context

### Lo que ya está decidido y no se reabre

| Decisión | Fuente |
|---|---|
| JWT con claims exactamente `sub`, `iat`, `exp`; `exp` de 8 h; sin *refresh* | ADR-047 D5, Q2 y Q4; ADR-043 T-1 |
| El principal se resuelve en cada petición; una cuenta `INACTIVE` da 401 en la siguiente | ADR-038 §2.7; ADR-047 D5 |
| Todo fallo de autenticación es el mismo 401, byte a byte | ADR-047 D5; DH-51 |
| Un 401 con JWT lleva al cliente móvil a `LOGGED_OUT` | ADR-043 T-1 |
| Contraseña de al menos 12 puntos de código, BCrypt de factor 12 | H-P2-1; `PlainPassword`; `BCryptPasswordHasherAdapter` |
| Correo por `spring-boot-starter-mail` solo en `app`; token aleatorio de 256 bits guardado como SHA-256; token en el fragmento de la URL | ADR-049 D1, D2, D3 |
| Ninguna respuesta revela si un correo tiene cuenta, ni por el cuerpo ni por el tiempo | ADR-049 §Context; ADR-047 Q5 |
| Una cuenta de administrador de plataforma no se puede desactivar | ADR-026 (enmienda de desactivación); `Account.deactivate()` |
| El representante no sale de la organización sin transferir antes | ADR-026, invariante 5 |
| `GET /me` devuelve exactamente cuatro campos | Ficha N1 (CONGELADA) |
| Los responsables no exponen `fullName` | H-P2-2 (cerrado, 2026-10-07) |
| El registro de auditoría de `identity` es de solo escritura y **sin** cadena de hash | ADR-025, líneas 20 y 28 |
| Los eventos de `core` no contienen el correo ni el nombre del donante (`donorRef` seudónimo) | ADR-048 |

### Hechos verificados en el código (`develop` `4bef7be`, 2026-10-18)

- `POST /api/v1/auth/register` pide solo `email` y `password` (`AccountRegistrationController`).
- `ChangeCredentialsService` existe, **sin ruta HTTP** y **sin comprobar la contraseña actual**.
- `DeactivateAccountService` y `ReactivateAccountService` existen, **sin ruta HTTP**.
- **Hueco:** al reactivar una cuenta, los tokens emitidos antes de la desactivación **vuelven a valer** hasta su `exp`, porque el único control es `status == INACTIVE`.
- `CreateAccountService` escribe el **correo en claro** en los datos de `ACCOUNT_CREATED`.
- `IdentityPrincipalPort.resolvePrincipal(sub)` lo usan el `JwtAuthFilter` y además `core` y `convocatoria` (11 llamadas), que no tienen `iat`.
- `TokenIssuerPort.issue(accountId)` no permite fijar `iat`.
- `Account` no tiene nombre ni aceptaciones legales; no hay verificación de correo.

## Decision

### D1. Registro en dos pasos [Carlos]

- **Paso 1** (`POST /api/v1/auth/register`): `email`, `password`, `acceptedTermsVersion`, `acceptedDataPolicyVersion` (D2).
- **Paso 2**, opcional y en cualquier momento: nombre y apellido.
- Nombre y apellido viven **solo** en la cuenta de `identity`:
  - nunca en eventos de `core`, en el hash, en Merkle ni en la blockchain;
  - nunca en `GET /me` (N1 intacta), en las listas de miembros ni en las de responsables (H-P2-2), ni en ninguna ruta pública.
- Contrato del paso 2:
  - `GET /api/v1/account/profile` → `200 {email, givenName?, familyName?}`, `Cache-Control: no-store`.
  - `PUT /api/v1/account/profile` con `{givenName, familyName}` → `200` con el mismo cuerpo. Cada campo: recortado, de 1 a 100 puntos de código, o `null` para borrarlo. Fuera de rango → 400 con el campo nombrado.
  - Audita `PROFILE_UPDATED` **sin** los valores.
- Los datos de la organización siguen en `POST /api/v1/organizations` (DD-68); no cambian.

### D2. Autorización de tratamiento de datos separada de los términos [Carlos]

- Dos aceptaciones con versión propia: **términos y condiciones** y **autorización de tratamiento de datos personales** (Ley 1581 de 2012).
- La cuenta guarda, por cada una, la **versión aceptada y la fecha**, nunca el texto.
- Una tercera aceptación, **términos de organización**, la da el representante al crear la organización (`acceptedOrganizationTermsVersion` en `POST /api/v1/organizations`). Se guarda en la `Organization` con la cuenta que aceptó y la fecha.
- Las versiones vigentes van en configuración (`traceability.legal.terms-version`, `traceability.legal.data-policy-version`, `traceability.legal.organization-terms-version`), sin valor por defecto fuera de `dev` y con *fail-fast* al arrancar, como los demás valores de entorno.
- Una versión distinta de la vigente, o ausente → 400 con el campo nombrado (`InvalidRequestField`).
- El texto de la autorización debe declarar la finalidad de seguridad y auditoría del registro de `identity` (D10 de este ADR).

### D3. Versión nueva de los términos [Carlos]

- Cuando cambia una versión vigente, toda cuenta (también las existentes y las de semilla) debe aceptarla en su **siguiente inicio de sesión**. No se rellena nada de forma retroactiva.
- Mecanismo (**Q1 (a)**): el login no emite token hasta aceptar; responde `403 AcceptanceRequired` con las versiones pendientes y el cliente repite el login con las versiones en el cuerpo.

### D4. Revocación de sesiones con `credentialsNotBefore` [Carlos]

**Regla:**
- La cuenta guarda `credentialsNotBefore` (instante con precisión de segundos, o `null`).
- Al cambiar o recuperar la contraseña, y al desactivar la cuenta: `credentialsNotBefore = max(segundo actual truncado, credentialsNotBefore actual) + 1 s` (si el actual es `null`, cuenta como el segundo truncado).
- Un token vale solo si `credentialsNotBefore` es `null` o `iat >= credentialsNotBefore`.

**Por qué el `+ 1 s`:** `iat` va en segundos enteros. Con el instante truncado sin sumar, un token viejo emitido en el mismo segundo que el cambio seguiría valiendo. Sumando un segundo, todo token anterior al cambio queda rechazado, incluido el del mismo segundo.

**Por qué el `max`:** sin él, dos cambios dentro del mismo segundo darían el mismo valor y el token devuelto por el primero (con `iat` igual a ese valor) seguiría valiendo tras el segundo. Con el `max`, el valor crece en cada cambio, así que cada cambio revoca todos los tokens anteriores. Un token puede salir con `iat` unos segundos en el futuro tras varios cambios seguidos; el filtro no valida `iat` futuro (ADR-047 D5), así que no afecta.

**Consecuencias:**
- Cierra el hueco de la reactivación: tras desactivar y reactivar, los tokens anteriores siguen rechazados.
- Las cuentas que ya existen quedan con `null` y sus tokens siguen valiendo hasta `exp`. No hace falta migración.
- El token **no** cambia: siguen solo `sub`, `iat`, `exp` (ADR-047 Q4 intacta).

**Puertos, de forma aditiva:**
- `IdentityPrincipalPort` gana `resolveAuthenticatedPrincipal(String accountId, Instant issuedAt)`, que hace lo mismo que `resolvePrincipal` y además rechaza un `iat` anterior a `credentialsNotBefore` con `CredentialsRevokedException` (excepción nombrada en `identity`). Solo lo llama el `JwtAuthFilter`.
- `resolvePrincipal(String)` **no cambia**: `core` y `convocatoria` siguen igual.
- `TokenIssuerPort` gana `issue(String accountId, Instant issuedAt)`; `issue(String)` se conserva y equivale a `issue(accountId, ahora)`.

**Enmienda de ADR-047 D5:** la validación del filtro pasa de 5 a 6 pasos. El 5 queda: "`resolveAuthenticatedPrincipal(sub, iat)`, que rechaza cuentas `INACTIVE`, inexistentes o con `iat` anterior a `credentialsNotBefore`". El rechazo es **el mismo 401**, sin distinguir la causa.

### D5. Cambiar la contraseña devuelve un token nuevo [Carlos]

- `POST /api/v1/account/password` con JWT y `{currentPassword, newPassword}`.
- Pasos, en una transacción de `identity`:
  1. Comprobar `currentPassword` con BCrypt.
  2. Aplicar la política a `newPassword` (`PlainPassword`).
  3. Guardar el hash, fijar `credentialsNotBefore` (D4) y auditar `CREDENTIALS_CHANGED` (ya existe), sin contraseñas.
- Responde `200 {token}`. El token se emite con **`iat = credentialsNotBefore`** de forma explícita y `exp = iat + ttl`. Así la sesión actual sigue y todas las demás quedan revocadas.
- Contraseña actual incorrecta → **400** `CurrentPasswordIncorrect` (`CurrentPasswordIncorrectException`), **nunca 401**: un 401 cerraría la sesión del móvil (ADR-043 T-1). No cambia nada (aserción negativa en los tests).
- `ChangeCredentialsService` se amplía con la comprobación de la actual; no se crea un servicio paralelo.

### D6. Desactivación de la propia cuenta [Carlos]

- Lógica: la cuenta pasa a `INACTIVE` y fija `credentialsNotBefore` (D4). No se borra nada.
- La puede pedir cualquier cuenta, salvo:
  1. un administrador de plataforma (invariante existente, `PlatformAdministratorDeactivationException`) → 409;
  2. el `REPRESENTATIVE` de una organización → 409 `RepresentativeTransferRequired` (ADR-026, invariante 5);
  3. un responsable activo de una convocatoria no cerrada → 409 `ActiveCampaignResponsible`, consultado con `CampaignResponsibilityPort` (ADR-049 D9, con su misma ventana de concurrencia aceptada).
- Contrato: `POST /api/v1/account/deactivate` con JWT y `{currentPassword}` (Q3 (a): exige la contraseña actual). Responde `204`. Audita `ACCOUNT_DEACTIVATED` (ya existe).
- La membresía de un `EMPLOYEE` o `ADMINISTRATOR` desactivado **no cambia**: la organización puede retirarlo con la operación existente (ADR-049 D8). Su principal ya no se resuelve, así que no puede actuar.
- Desactivar no responde a H-S18-2 (representante atrapado tras `REJECTED`); sigue abierto.

### D7. Reactivación solo por el administrador de plataforma [Carlos]

- `POST /api/v1/platform/accounts/{accountId}/reactivate`, con un `PlatformCommandType` nuevo `REACTIVATE_ACCOUNT`. Responde `200 {accountId, status}`. Audita `ACCOUNT_REACTIVATED` (ya existe).
- Los tokens anteriores a la desactivación siguen rechazados (D4). La persona inicia sesión de nuevo.
- Desactivar cuentas ajenas queda fuera de este ADR.

### D8. Recuperación de contraseña [Carlos]

**Pedir** — `POST /api/v1/auth/password-reset` con `{email}`, ruta pública:
- Responde **siempre** `202` con el mismo cuerpo, exista o no la cuenta y esté o no activa.
- Cuenta inexistente o `INACTIVE` → no hace nada más: no crea token, no envía correo, no reactiva.
- **Enfriamiento de 5 minutos por cuenta [Carlos]:** si la cuenta ya tiene un token `PENDING` creado hace menos de 5 minutos, no se crea otro ni se envía correo.
- Si no: se crea un token nuevo y el anterior `PENDING` de esa cuenta pasa a `SUPERSEDED` **[Carlos]**.

**El token** (patrón de ADR-049 D2):
- 32 bytes de `SecureRandom`, Base64 URL sin relleno; en la base solo su SHA-256 con índice único.
- Caduca a los **30 minutos** y es de un solo uso.
- Colección `password_reset_tokens` de `identity`: `{tokenHash, accountId, status: PENDING | USED | SUPERSEDED, createdAt, expiresAt, usedAt?}`. `EXPIRED` se deriva de `expiresAt`. Una sola `PENDING` por cuenta.

**Envío diferido [Carlos]:**
- El correo se envía **después de responder**, para que el tiempo de respuesta no revele si el correo tiene cuenta.
- Mecanismo: `identity` define `PasswordResetMailPort`; `app` lo implementa con `JavaMailSender` (dependencia que ya existe, ADR-049 D1) y un `ThreadPoolTaskExecutor` de Spring acotado (sin dependencia nueva). El servicio entrega el token al ejecutor tras confirmar la transacción. Q5 (a): la tarea se entrega al ejecutor solo después de que `executeWithRetry` confirme la transacción, sin reintentos.
- Si el envío falla o la cola está llena: se registra con el id interno del token, **nunca** con el token ni el correo, y no se reintenta. La persona vuelve a pedirlo pasado el enfriamiento.
- **Riesgo residual:** el camino con cuenta hace una escritura en la base que el camino sin cuenta no hace. La diferencia es de milisegundos, no de segundos como el SMTP. Se acepta y se documenta.

**El correo:**
- Enlace `{TRACEABILITY_WEB_BASE_URL}/recuperar#token=<token>`: el token va en el fragmento, con la misma excepción consciente que ADR-049 D3.
- Texto plano: el enlace, la caducidad y "si no lo pediste, ignora este correo". Sin otros datos.

**Confirmar** — `POST /api/v1/auth/password-reset/confirm` con `{token, newPassword}`, ruta pública:
- Busca por `SHA-256(token)`. Debe estar `PENDING`, sin caducar, y la cuenta `ACTIVE`.
- Marca `USED` con escritura condicional `status = PENDING`: de dos confirmaciones simultáneas gana una.
- Guarda la nueva contraseña, fija `credentialsNotBefore` (D4) y audita `CREDENTIALS_CHANGED` con un dato `{via: "PASSWORD_RESET"}`.
- Responde `204`. No inicia sesión: la persona entra con la nueva contraseña.
- Token desconocido, caducado, usado o reemplazado, o cuenta `INACTIVE` → **el mismo 400** `PasswordResetTokenInvalid`.
- La API nunca acepta el token en la ruta ni en la query.

**Rutas públicas:** ambas se añaden a `PublicRoutes` y al test de inventario en el mismo PR.

### D9. Google sin vinculación automática [Carlos]

- Nunca se vincula Google a una cuenta por coincidir el correo. Hoy los correos no se verifican, así que esa coincidencia no prueba nada.
- La vinculación es explícita, con sesión iniciada: `POST /api/v1/account/google/link` con JWT y `{idToken}`. Guarda el `sub` de Google en la cuenta, con índice único.
- `POST /api/v1/auth/google` con `{idToken}`: busca la cuenta por el `sub` de Google, **nunca por el correo**. Sin cuenta vinculada → el mismo 401 que un login fallido. Con cuenta `INACTIVE` → el mismo 401.
- Verificación del `idToken` con Nimbus, que ya está en `api` (ADR-047 D1):
  - firma con las llaves públicas de Google, obtenidas y cacheadas desde su JWKS;
  - `iss` igual a `accounts.google.com` o `https://accounts.google.com`;
  - `aud` igual a uno de los ID de cliente configurados (web y móvil tienen ID distintos);
  - `exp` vigente y `email_verified = true`.
- Configuración: `GOOGLE_OAUTH_CLIENT_IDS` por entorno. **Para verificar un `idToken` no hace falta el secreto del cliente**; no se configura.
- Consecuencias: una conexión saliente nueva desde el backend hacia Google; si no responde, el login con Google falla y el de contraseña sigue funcionando.
- R-6 es la última prioridad: si el plazo no alcanza, este apartado se queda sin implementar sin afectar al resto.

### D10. Ley 1581: desactivación, supresión y auditoría [Carlos]

- **Desactivar no es suprimir [Carlos].** La desactivación es un cambio de estado; los datos de la cuenta siguen.
- **Minimización [Carlos]:** `ACCOUNT_CREATED` deja de guardar el correo en sus datos. El `accountId` identifica la cuenta; el correo ya está en ella.
- Las entradas que ya tienen el correo **no se reescriben**: el registro es de solo escritura (ADR-025). Se conservan con la finalidad de seguridad y auditoría, que el texto de la autorización (D2) debe declarar.
- **Si alguien pide la supresión:**
  - La atiende el administrador de plataforma, de forma manual y fuera del sistema, en un máximo de 15 días hábiles prorrogables 8 más (Q4; art. 15 de la Ley 1581).
  - Es viable sin romper la integridad: los eventos de `core` no tienen el correo ni el nombre (ADR-048). Basta con anonimizar el correo y el nombre de la cuenta en `identity` y dejarla `INACTIVE`. La cadena de hash y el anclaje no cambian.
  - Este ADR no implementa esa operación.

### D11. Sin cambios en eventos ni en la cadena

Nada de esto toca payloads de eventos de `core`, versiones de esquema, canonicalización, hash, Merkle ni anclaje. Todo vive en las colecciones de `identity` (`accounts`, `organizations`, `password_reset_tokens`, auditoría).

## Preguntas — cerradas por Carlos el 2026-10-18

| # | Pregunta | Opciones | Recomendación del agente | Decisión |
|---|---|---|---|---|
| **Q1** | Cómo se exige aceptar una versión nueva (D3) | (a) El login no emite token hasta aceptar: responde `403 AcceptanceRequired` con las versiones pendientes y el cliente repite el login con `acceptedTermsVersion`/`acceptedDataPolicyVersion` en el cuerpo. Los tokens ya emitidos siguen hasta su `exp` (como mucho 8 h). (b) El login emite token y el filtro bloquea todas las rutas salvo la de aceptar hasta que se acepte | **(a)**: no toca el filtro ni el puerto; la ventana de 8 h es aceptable porque los términos cambian poco | **(a)** |
| **Q2** | ¿Se puede crear una cuenta nueva con Google, sin contraseña? | (a) No en este corte: Google solo para cuentas ya vinculadas. (b) Sí; exige aceptar términos en ese flujo y permitir cuentas sin contraseña, que hoy el dominio prohíbe (`Account.createAccount` exige `PasswordHash`) | **(a)**: (b) cambia un invariante del dominio | **(a)** |
| **Q3** | ¿Desactivar la propia cuenta pide la contraseña actual? | (a) Sí. (b) Basta el JWT | **(a)**: con un token robado no se puede bloquear al dueño | **(a)** |
| **Q4** | Plazo y responsable de atender una petición de supresión | Lo fija la política de tratamiento de datos. Referencia aportada el 2026-10-18: el art. 15 de la Ley 1581 de 2012 da 15 días hábiles desde el día siguiente al recibo, prorrogables hasta 8 días hábiles más informando al titular. Sin verificar contra el texto oficial | Que la política fije responsable y plazo dentro del máximo legal, y se cite aquí. Quien redacta los términos (M-33) | **Máximo de 15 días hábiles desde el día siguiente al recibo, prorrogable hasta 8 días hábiles más informando al titular (art. 15 de la Ley 1581 de 2012). La atiende el administrador de plataforma. Se fija en la política de tratamiento (M-33)** |
| **Q5** | Ejecutor del envío diferido | (a) `ThreadPoolTaskExecutor` acotado en `app`, sin reintentos. (b) Bandeja de salida persistente con reintentos | **(a)**: el enfriamiento y volver a pedir cubren el fallo, como en ADR-049. Requisito para el plan de R-4: el envío se entrega al ejecutor solo **después** de que la transacción confirme (el equivalente a `afterCommit`; `identity` usa `MongoTransactionRetryHelper`, así que es tras volver de `executeWithRetry`), y con el token del **intento que confirmó**, nunca con el de un intento reintentado o revertido | **(a)** |

## Alternatives

| Alternativa | Por qué no |
|---|---|
| Versión de credenciales dentro del token | Reabre ADR-047 Q4 (claims fijos). `credentialsNotBefore` logra lo mismo sin cambiar el token |
| Lista negra de tokens | Exige guardar cada token o su `jti`, que el token no lleva (ADR-047 Q4) |
| Cambiar `resolvePrincipal(sub)` en vez de añadir un método | Obliga a tocar 11 llamadas de `core` y `convocatoria` que no tienen `iat` |
| `iat` comparado con el instante del cambio sin `+ 1 s` | Deja válidos los tokens del mismo segundo, o rechaza el token nuevo de D5 |
| Contraseña actual incorrecta como 401 | Cerraría la sesión del móvil (ADR-043 T-1) |
| Correo de recuperación enviado dentro de la petición | El tiempo de respuesta revelaría qué correos tienen cuenta |
| Token de recuperación firmado (JWT) | No se puede invalidar al pedir otro sin guardar estado; mismo motivo que ADR-049 |
| Vincular Google por coincidencia de correo | Toma de cuenta: los correos no se verifican |
| Una sola casilla para términos y datos | La Ley 1581 pide la autorización de tratamiento de forma expresa |
| Borrar el correo de las entradas antiguas de auditoría | Rompe la regla de solo escritura de ADR-025 |

## Consequences

**Positivas**
- Cambiar o recuperar la contraseña cierra todas las demás sesiones sin cambiar el token.
- Se cierra el hueco por el que los tokens viejos revivían al reactivar una cuenta.
- Ninguna ruta nueva revela si un correo tiene cuenta, ni por la respuesta ni por el tiempo del SMTP.
- `core` y `convocatoria` no cambian.

**Negativas**
- Dos puertos de `contracts` crecen (de forma aditiva).
- Un mecanismo nuevo de envío en segundo plano en `app`.
- Una conexión saliente nueva hacia Google, si se implementa D9.
- Tres valores de configuración nuevos con *fail-fast*.

**Riesgos aceptados — Carlos, 2026-10-18**
1. Sigue sin límite de intentos por IP (DH-56): el login y la confirmación de recuperación admiten fuerza bruta. El enfriamiento solo protege contra el envío masivo de correos a una misma cuenta.
2. Diferencia de milisegundos entre el camino con cuenta y sin cuenta al pedir recuperación (D8).
3. Las cuentas existentes, con `credentialsNotBefore = null`, conservan sus tokens hasta `exp` (8 h).
4. Con Q1 (a), tras publicar una versión nueva, los tokens ya emitidos sirven hasta 8 h sin haberla aceptado.

## Definición de hecho (tests, rojos primero)

**Revocación (R-3)**
1. Cambiar la contraseña → el token anterior recibe 401 en la siguiente petición.
2. Token emitido **en el mismo segundo** antes del cambio → 401.
3. El token devuelto por R-2 funciona de inmediato, aunque se use en el mismo segundo del cambio.
3b. Dos cambios de contraseña dentro del mismo segundo (reloj controlado) → el token devuelto por el primero recibe 401 y el del segundo funciona.
4. Desactivar, reactivar y usar un token anterior → 401.
5. Cuenta con `credentialsNotBefore = null` → su token sigue valiendo.
6. Todos los rechazos dan el mismo 401, byte a byte, que los de ADR-047.
7. Las llamadas de `core` y `convocatoria` a `resolvePrincipal(sub)` siguen pasando sus tests sin cambios.

**Cambio de contraseña (R-2)**
8. Contraseña actual incorrecta → 400 `CurrentPasswordIncorrect`, el hash no cambia, `credentialsNotBefore` no cambia y no hay auditoría (aserciones negativas).
9. Contraseña nueva de menos de 12 → 400 `PasswordTooShort` y nada cambia.
10. La auditoría `CREDENTIALS_CHANGED` no contiene ninguna contraseña.

**Recuperación (R-4)**
11. Correo existente, inexistente e inactivo → misma respuesta (estado, cuerpo, cabeceras); solo el existente y activo envía correo (captura de correos).
12. La respuesta llega antes de que termine el envío (envío con un `JavaMailSender` de test bloqueado).
13. Segunda petición antes de 5 minutos → misma respuesta, sin token nuevo ni correo; después de 5 minutos → token nuevo y el anterior `SUPERSEDED` (reloj controlado).
14. Confirmar con token usado, caducado, reemplazado, desconocido o con cuenta inactiva → el mismo 400.
15. Dos confirmaciones simultáneas del mismo token → una gana y la otra recibe 400.
16. Confirmar revoca los tokens JWT anteriores (como el test 1).
17. El token de recuperación no aparece en logs, respuestas ni en la base (solo su hash); en la query o la ruta no se acepta.
18. El enlace del correo lleva el token después de `#`.

**Registro, aceptaciones y perfil (R-5, D1)**
19. Registro sin aceptación, o con una versión vieja de cualquiera de las dos → 400 con el campo nombrado y sin cuenta creada.
20. La cuenta guarda versión y fecha de cada aceptación; crear una organización guarda la de términos de organización.
21. Sin las versiones vigentes en la configuración, la aplicación no arranca (salvo `dev`).
22. `ACCOUNT_CREATED` nuevo no contiene el correo.
23. El nombre no aparece en `GET /me`, en la lista de miembros, en la de responsables ni en ninguna ruta pública.

**Desactivación y reactivación (D6, D7)**
24. Representante → 409; responsable activo → 409; administrador de plataforma → 409. En los tres, la cuenta sigue `ACTIVE`.
25. Desactivar la propia cuenta → la siguiente petición con su token da 401.
26. Solo el administrador de plataforma reactiva; cualquier otro recibe el 403 uniforme.

**Google (R-6, si entra)**
27. `idToken` con firma inválida, `iss` o `aud` ajenos, caducado o con `email_verified = false` → 401.
28. Un `idToken` válido de un correo que ya tiene cuenta **no vinculada** → 401, y la cuenta no queda vinculada.
29. Tras vincular con sesión iniciada, `POST /auth/google` inicia sesión con esa cuenta.

**Común**
30. Los 19 criterios del golden path siguen en verde (`GoldenPathHttpIntegrationTest`), con `scripts/ci-local.sh` completo.

## Implementación (tras la aprobación, una tarea y un plan por PR)

| Orden | Tarea | Módulos |
|---|---|---|
| 1 | R-3 + R-2: `credentialsNotBefore`, puertos aditivos, enmienda ADR-047, cambio de contraseña | `contracts`, `identity`, `api`, `app` |
| 2 | R-4: recuperación con envío diferido | `identity`, `app` |
| 3 | R-5 y D1: aceptaciones, perfil, minimización de la auditoría | `identity`, `app` |
| 4 | D6 y D7: desactivación propia y reactivación | `identity`, `app` |
| 5 | R-6: Google (última prioridad) | `identity`, `api`, `app` |

*Nota (2026-10-25), decisiones de Manuel por delegación de Carlos (`decisiones-delegadas-2026-10.md`, nota del 2026-10-25, "comunicado por Manuel"):*
- **Fila 5 (R-6, Google): se pospone, fuera de este corte** (decisión 2). D9 y los tests 27 a 29 no cambian; quedan para cuando se retome.
- **Fila 4, D7: entra en este corte** (decisión 1), tal como lo define D7. Lo implementa un PR propio, con su sección en `plan-cierre-backend-2026-10-25.md`. D6 ya está en `develop` (#146, `f5191c6`).

La tabla de arriba se conserva.

*Nota (2026-10-30), G-0 de Manuel (`decisiones-delegadas-2026-10.md`, nota del 2026-10-30, "comunicado por Manuel"):* **la fila 5 (R-6, Google) se retoma.** Sustituye a "se pospone, fuera de este corte" de la nota del 2026-10-25, que se conserva. D9 y los tests 27 a 29 no cambian. El cómo va en `plan-adr050-r6-google.md`, que espera el plan aprobado de Manuel.
