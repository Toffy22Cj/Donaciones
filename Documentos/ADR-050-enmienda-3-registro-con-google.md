# ADR-050 — Enmienda 3: crear la cuenta con Google (y contraseña)

## Status

**PROPUESTA — espera la aprobación de Carlos.**

- **Qué se construye** lo pidió Manuel el 2026-11-11: "necesito poder registrarme con el correo de google".
- **La forma** la eligió Manuel el mismo día entre dos opciones: Google **y** contraseña (no una cuenta solo con Google).
- **El código ya está escrito** en las ramas `claude/relaxed-curie-9271o4` de `Donaciones` y `PaxFide`, por decisión de Manuel ("enmienda + código"), **sin fusionar**. Si Carlos no aprueba la enmienda, esas ramas no se fusionan. Si la aprueba con cambios, el código se ajusta antes de fusionar.

**Enmienda a:** ADR-050 (APROBADO — Carlos, 2026-10-18), pregunta **Q2** y decisión **D9**. Usa las mismas piezas que D9 (verificación del `idToken`, índice único `uq_google_subject`) y que el alta de D1 y D2.
**Fecha:** 2026-11-11 (UTC). **Base verificada:** `develop` `11f8698`.

**Es una enmienda** porque cambia una respuesta aprobada (Q2 (a): "No en este corte: Google solo para cuentas ya vinculadas") y añade una ruta pública nueva (regla 3.5). **No cambia ningún invariante del dominio:** toda cuenta sigue naciendo con contraseña (`Account.createAccount` exige `PasswordHash`), que era el motivo de elegir Q2 (a).

## Context

- Hoy, para entrar con Google hay que crear la cuenta con correo y contraseña, abrir el enlace de verificación, entrar y vincular Google en Perfil › Seguridad pidiendo otra vez la contraseña (D9 y su ampliación 1). Son cinco pasos para algo que la persona espera hacer con un clic.
- Q2 descartó la opción (b) porque exige **cuentas sin contraseña**, y eso cambia un invariante del dominio y todo lo que pide la contraseña actual (cambiarla, desactivar, vincular y desvincular Google).
- Hay una tercera opción que Q2 no listó: **Google pone y prueba el correo; la persona pone la contraseña.** El invariante no se toca y lo de D9 sigue igual.

## Decisión

### E3-1. Ruta nueva `POST /api/v1/auth/google/register`, pública

- Cuerpo `{idToken, password, acceptedTermsVersion, acceptedDataPolicyVersion}`. Es el alta de `POST /auth/register` (D1, D2) con el correo que prueba Google en lugar de uno escrito.
- **Orden de las comprobaciones**, para no llamar a Google con una petición que va a fallar igual:
  1. `idToken` y `password` presentes → si no, 400 con el campo (`InvalidRequestField`).
  2. Las dos versiones legales vigentes → si no, 400 con el campo (D2).
  3. La política de la contraseña (al menos 12 caracteres, H-P2-1) → si no, 400 `PasswordTooShort`.
  4. El `idToken`, verificado como en el login (D9: firma, `iss`, `aud`, `exp`, `email_verified = true`) → inválido, 400 `GoogleIdTokenInvalid`; Google sin responder, 503 `GoogleSignInUnavailable`.
  5. Ese `sub` ya vinculado a otra cuenta → 409 `GoogleAccountAlreadyLinked`.
  6. El correo de Google ya registrado → 409 `DuplicateEmail`, como el alta con correo. **La cuenta existente no se toca ni se vincula:** quien ya tiene cuenta entra con su contraseña y vincula Google en Seguridad (D9 sin cambios).
- Sin `Command-Id` (como `POST /auth/register`, DH-34): repetir la petición da 409.

### E3-2. La cuenta nace verificada, con las aceptaciones y vinculada, en una sola transacción

- El correo es el del `idToken`, normalizado como el de las cuentas.
- **Verificada:** el `idToken` lleva `email_verified = true` y es el mismo correo, así que prueba el correo como el enlace de la Enmienda 2 (el mismo criterio que P-G-1 al vincular). **No se envía correo de verificación.**
- **Aceptaciones:** las dos versiones, con la fecha del alta (D2).
- **Vínculo:** el `sub` con `linkGoogleIfUnlinked`; el índice único `uq_google_subject` cubre dos altas simultáneas con el mismo Google (la segunda revierte y responde 409).
- **Auditoría** (actor: la cuenta nueva, como en el alta): `ACCOUNT_CREATED` con `{selfRegistration: true, via: "GOOGLE"}`, `GOOGLE_LINKED` sin datos y `EMAIL_VERIFIED` con `{via: "GOOGLE_REGISTER"}`. Sin el correo (D10), sin el `sub` ni el `idToken`.
- **Sin aviso de seguridad** por correo: la cuenta es nueva (los avisos de D9 son para cambios en una cuenta que ya existía).

### E3-3. Respuesta: `201 {accountId, status, token}`, con la sesión iniciada

- Como la cuenta ya está verificada y con las aceptaciones, nada impide emitir el token del login (con el `iat` de E1-2). La persona entra sin otro paso.
- Si emitir el token falla, la respuesta sigue siendo 201 con `token: null`: la cuenta existe y se entra con Google o con la contraseña. La web lo dice así.
- **Revelar que el correo ya tiene cuenta (409 `DuplicateEmail`)** es lo mismo que hace hoy `POST /auth/register`, y aquí con menos riesgo: solo lo recibe quien presenta un `idToken` válido de ese correo, es decir, su dueño.

### E3-4. Lo que no cambia

- **Q2 en lo esencial:** no hay cuentas sin contraseña. Cambiar la contraseña, desactivar, vincular y desvincular Google siguen pidiendo la contraseña actual.
- **D9:** el login con Google sigue buscando **solo por el `sub`**, nunca por el correo. Una cuenta creada con correo y contraseña no se vincula sola por tener el mismo correo que un Google.
- `POST /auth/register` y `POST /auth/google` no cambian.

## Web y móvil

- **Web (`PaxFide`):** en "Crear cuenta", el botón oficial "Registrarse con Google" (`text: signup_with`) arriba del formulario, tras la superficie nueva `action:google-register` y el ID de cliente (sin cualquiera de los dos, ni botón ni script, como G-W1). Tras el clic, el campo del correo se sustituye por "Te registras con <correo>" y "Usar otro correo"; la contraseña, su repetición y las dos casillas siguen siendo obligatorias. El `idToken` solo vive en memoria.
- **Supera G-W4** del plan de la web (Anexo 6: "«Crear cuenta» no tiene Google") y la parte de G-W2 que decía que "Crear cuenta" no carga el script de Google.
- **Móvil (`PaxFide_Mv`):** fuera de esta enmienda. Puede usar la misma ruta cuando lo planifique.

## Preguntas

| # | Pregunta | Opciones | Recomendación | Decisión |
|---|---|---|---|---|
| **E3-Q1** | ¿Se acepta registrar con Google **y** contraseña (no cambia el invariante de Q2)? | (a) Sí. (b) No, se queda Q2 (a). (c) Sí, pero solo con Google, sin contraseña (Q2 (b)) | **(a)** | Pendiente (Carlos) |
| **E3-Q2** | Con el registro hecho, ¿se inicia la sesión ya (201 con `token`)? | (a) Sí. (b) No: responde `201 {accountId, status}` y la persona entra con Google | **(a)**: la cuenta ya está verificada y con las aceptaciones; un paso más no protege nada | Pendiente (Carlos) |
| **E3-Q3** | Correo de Google ya registrado | (a) 409 `DuplicateEmail` sin tocar la cuenta. (b) Vincular la cuenta existente | **(a)**: (b) elegiría la cuenta por el correo, que D9 prohíbe | Pendiente (Carlos) |

## Tests

- **Backend** (`app`, `GoogleRegistrationHttpIntegrationTest`, GR-1 a GR-8): alta verificada, vinculada y con aceptaciones y el token sirve; después entran Google y la contraseña; sin correo de verificación; 409 por correo y por `sub` sin crear ni tocar cuentas; 400 por `idToken` inválido sin cuenta; campos, contraseña y versiones antes de llamar a Google; ni el `idToken`, ni el `sub`, ni la contraseña en los registros. `PublicRoutesTest` y `PublicRoutesInventoryIntegrationTest`: la ruta es pública.
- **Web:** contrato de `googleRegister`, la pantalla (botón `signup_with`, correo de Google, validaciones, cada fallo con su texto) y un E2E del recorrido completo contra el servidor falso.
