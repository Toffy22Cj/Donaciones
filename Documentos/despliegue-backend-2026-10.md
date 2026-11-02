# Despliegue del backend (2026-10)

**Estado:** guía operativa del 2026-10-31, comprobada contra el código. Cada ruta:línea es de `develop` `b0654aa`, salvo que se marque:
- **(#165)**: rama `feat/identity-r7-perfil-organizacion`, el perfil de la organización (R-7);
- **(#166)**: rama `feat/identity-adr050-r6-google`, el login con Google.

Cuando se fusionen esos PR, sus líneas pasan a `develop`.

**No lleva ningún valor real.** Los secretos y los ID de cliente se ponen solo por entorno.

---

## 1. Variables de entorno

Todas las lee `app/src/main/resources/application.yml`.
- **"Obligatoria"** quiere decir que, sin ella, **la aplicación no arranca**: falla al resolver el marcador `${…}` o por una comprobación propia (*fail-fast*).
- El perfil `dev` (`application-dev.yml`) da valores locales a algunas de ellas. **Producción no activa `dev`.**

### 1.1 Base de datos, correo y web

| Variable | Para qué | ¿Obligatoria? | Si falta | Dónde |
|---|---|---|---|---|
| `SPRING_DATA_MONGODB_URI` | Conexión a MongoDB. **No está en `application.yml`:** la lee Spring Boot por su nombre estándar | Sí, en la práctica | Spring usa `mongodb://localhost:27017/test`. Hace falta un *replica set*, porque la aplicación usa transacciones (`app/src/main/java/com/traceability/app/config/TraceabilityInfrastructureConfig.java:14`) | (propiedad estándar de Spring Boot) |
| `SPRING_MAIL_HOST` | Servidor SMTP de todos los correos: verificación, recuperación, invitaciones y avisos de seguridad | **Sí** | No arranca (el marcador no tiene valor por defecto) | `application.yml:6` |
| `SPRING_MAIL_PORT`, `SPRING_MAIL_USERNAME`, `SPRING_MAIL_PASSWORD`, `SPRING_MAIL_PROPERTIES_MAIL_SMTP_AUTH`, `SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_ENABLE` | Puerto, credenciales y TLS del SMTP (Gmail: `smtp.gmail.com`, 587, contraseña de aplicación) | Según el servidor | Spring usa sus valores por defecto (puerto 25, sin autenticación). La aplicación arranca, pero los envíos fallan | `application.yml:2-4` |
| `TRACEABILITY_MAIL_FROM` | Remitente de los correos | **Sí** | No arranca | `application.yml:67` |
| `TRACEABILITY_WEB_BASE_URL` | Base de los enlaces de los correos (verificar, recuperar, invitar) | **Sí** | No arranca | `application.yml:69` |
| `TRACEABILITY_CORS_ALLOWED_ORIGINS` | Orígenes web exactos, separados por comas; nunca `*` | No | Ningún origen cruzado: **la web en otro dominio no puede llamar a la API** | `application.yml:72`; `api/src/main/java/com/traceability/api/web/CorsConfig.java:43` |

### 1.2 Versiones legales (ADR-050 D2)

Hoy las tres valen `v1-2026-10`, como indicó Carlos (`auditoria-pendientes-manuel-2026-10-24.md:184`).

| Variable | Para qué | ¿Obligatoria? | Si falta | Dónde |
|---|---|---|---|---|
| `TRACEABILITY_LEGAL_TERMS_VERSION` | Versión vigente de los términos | **Sí** | No arranca: "… es obligatoria (ADR-050 D2)" | `application.yml:62`; `app/src/main/java/com/traceability/app/config/LegalVersionsConfig.java:25,33` |
| `TRACEABILITY_LEGAL_DATA_POLICY_VERSION` | Versión vigente de la política de datos | **Sí** | No arranca | `application.yml:63`; `LegalVersionsConfig.java:26` |
| `TRACEABILITY_LEGAL_ORGANIZATION_TERMS_VERSION` | Versión de los términos para organizaciones | **Sí** | No arranca | `application.yml:64`; `LegalVersionsConfig.java:27` |

**Al cambiar una versión:**
- las cuentas tienen que volver a aceptar en el próximo login (`403 AcceptanceRequired`, D3);
- las organizaciones **no** vuelven a aceptar (P-R7-3, §4).

### 1.3 Secretos

Todos deben ser distintos entre sí: el cursor lo comprueba (`DiscoveryCursorConfig.java:12`).

| Variable | Para qué | ¿Obligatoria? | Si falta | Dónde |
|---|---|---|---|---|
| `JWT_SIGNING_SECRET` | Firma de los JWT; al menos 32 bytes | **Sí** | No arranca | `application.yml:107`; `api/src/main/java/com/traceability/api/auth/jwt/JwtSecurityProperties.java:33,48` |
| `JWT_SIGNING_KID` | Identificador de la clave actual | No (por defecto `k1`) | — | `application.yml:108`; `JwtSecurityProperties.java:49-50` |
| `JWT_TTL` | Vida del token | No (por defecto `PT8H`) | — | `application.yml:109` |
| `JWT_SIGNING_SECRET_PREVIOUS`, `JWT_SIGNING_KID_PREVIOUS`, `JWT_SIGNING_SECRET_PREVIOUS_ACCEPT_UNTIL` | Rotación de la clave (ADR-047 D4). Van las tres juntas, y el fin de aceptación no puede pasar de arranque + `ttl` | No | Sin rotación. **Con solo una o dos de las tres, no arranca** | `application.yml:111-114`; `JwtSecurityProperties.java:62-83` |
| `TRACKING_CODE_SECRET` | HMAC del `trackingCode` del donante | **Sí** | No arranca | `application.yml:103`; `core/src/main/java/com/traceability/core/infrastructure/security/properties/TrackingSecurityProperties.java:19-20` |
| `ASSET_REF_SECRET` | HMAC de las referencias de activos | **Sí** | No arranca | `application.yml:104`; `TrackingSecurityProperties.java:23` |
| `TRACEABILITY_DISCOVERY_CURSOR_KEY` | Clave AES-256 del cursor opaco del descubrimiento y de la cola de verificación: 32 bytes en Base64 | **Sí** | No arranca; tampoco si no son 32 bytes o si coincide con otro secreto | `application.yml:90`; `app/src/main/java/com/traceability/app/config/DiscoveryCursorConfig.java:12,21-28` |

### 1.4 Imágenes (ADR-051 F1)

| Variable | Para qué | ¿Obligatoria? | Si falta | Dónde |
|---|---|---|---|---|
| `MEDIA_S3_ENDPOINT`, `MEDIA_S3_REGION`, `MEDIA_S3_BUCKET`, `MEDIA_S3_ACCESS_KEY_ID`, `MEDIA_S3_SECRET_ACCESS_KEY` | Almacenamiento compatible con S3, con *bucket* privado (logo, portada, evidencia) | **Sí, las cinco** | No arranca: "… es obligatoria con traceability.media.storage=s3" | `application.yml:76-82`; `app/src/main/java/com/traceability/app/infrastructure/media/MediaStorageConfig.java:108-109` |

`storage: local` solo se admite con el perfil `dev` (`MediaStorageConfig.java:47-50`).

### 1.5 Convocatorias y pagos

| Variable | Para qué | ¿Obligatoria? | Si falta | Dónde |
|---|---|---|---|---|
| `CONVOCATORIA_BANK_TRANSFER_EXPIRATION` | Duración de una intención de donación por transferencia (ISO-8601, p. ej. `PT72H`). No hay un valor de producto decidido | **Sí** | No arranca | `application.yml:120`; `convocatoria/src/main/java/com/traceability/convocatoria/application/service/DonationIntentService.java:70` |
| `CONVOCATORIA_FUNDS_APPLICATION_RECOVERY_ENABLED` | Programador de respaldo de ADR-045 §2.2 | No (por defecto `false`, también en producción) | — | `application.yml:125` |
| `TRACEABILITY_DEMO_WEBHOOK_SECRET` | Firma del webhook del pago simulado | **Solo en `dev`/demo** | Con `dev`, no arranca | `application-dev.yml`. Fuera de `dev`, el pago simulado está apagado (`application.yml:96`) |

> **Nota (2026-11-01): `CONVOCATORIA_BANK_TRANSFER_EXPIRATION` = `PT48H` (48 horas) en producción.** Lo decidió Manuel (D-1, `decisiones-delegadas-2026-10.md`, nota del 2026-11-01), a quien Carlos asignó el tema el 2026-11-01.
> - Esto supera "No hay un valor de producto decidido" de la tabla, que se conserva.
> - El `PT72H` de `application-dev.yml` sigue siendo solo de desarrollo y no cambia.
> - `application.yml:120` sigue sin valor por defecto: el `PT48H` se pone por entorno al desplegar.

### 1.6 Anclaje en blockchain

| Variable | Para qué | ¿Obligatoria? | Si falta | Dónde |
|---|---|---|---|---|
| `WEB3_PRIVATE_KEY`, `WEB3_NODE_URL` | Billetera y nodo del anclaje | **Sí, en la práctica.** El marcador tiene valor vacío por defecto, pero `@NotBlank` lo rechaza | No arranca ("Web3j private key must be configured") | `application.yml:33-34`; `crypto/src/main/java/com/traceability/crypto/infrastructure/web3j/Web3Configuration.java:17-21` |
| `CRYPTO_ANCHOR_NETWORK`, `CRYPTO_ANCHOR_SMART_CONTRACT`, `CRYPTO_ANCHOR_CHAIN_ID`, `CRYPTO_ANCHOR_CONFIRMATIONS_REQUIRED` | Red, contrato, cadena y confirmaciones | No (por defecto Sepolia, contrato `0x0…0`, 11155111 y 12) | Con el contrato por defecto el anclaje no sirve | `application.yml:38-41` |
| `CRYPTO_ANCHOR_COLLECTING_RECOVERY_TIMEOUT_SECONDS`, `CRYPTO_ANCHOR_COLLECTING_RECOVERY_MAX_PER_CYCLE`, `CRYPTO_ANCHOR_COLLECTING_RECOVERY_WARN_AFTER_ATTEMPTS` | Recuperación de lotes atascados | No (300, 10 y 5) | — | `application.yml:54-56` |

### 1.7 Otras

| Variable | Para qué | ¿Obligatoria? | Si falta | Dónde |
|---|---|---|---|---|
| `SPRING_AI_OPENAI_API_KEY` | Clave del proveedor de IA (Groq, compatible con OpenAI) | No (por defecto `dummy`) | Arranca; las llamadas a la IA fallan | `application.yml:18` |
| `TRACEABILITY_BOOTSTRAP_PLATFORM_ADMIN_ENABLED`, `TRACEABILITY_BOOTSTRAP_PLATFORM_ADMIN_EMAIL` | Crear el administrador de plataforma inicial (ADR-038 §2.4). Spring las enlaza por nombre; no están en `application.yml` | No | Sin administrador inicial | `app/src/main/java/com/traceability/app/bootstrap/PlatformAdminBootstrapRunner.java:17,27` |
| **`GOOGLE_OAUTH_CLIENT_IDS`** (#166) | ID de cliente de Google aceptados como `aud`: web, Android `com.paxfide.mobile` y Android demo `com.paxfide.mobile.demo`, separados por comas. **Los valores los da Manuel** y nunca van al repositorio. El secreto del cliente no se usa | No | Arranca con el log `google-sign-in disabled: no client ids configured`, y `POST /auth/google` responde 401 | (#166) `application.yml:67-69`; `app/src/main/java/com/traceability/app/config/GoogleSignInConfig.java:36-41` |

---

## 2. Orden de despliegue y condiciones

1. **Comprobar el SMTP de producción** antes de desplegar la Enmienda 2 de ADR-050 (#163, ya en `develop`):
   - el login exige el correo verificado (E2-3, `ADR-050-enmienda-2-verificacion-correo-y-avisos.md:88-93`);
   - las cuentas existentes quedan **sin verificar** (Q2 (a), `:13`);
   - sin SMTP no llega el enlace, así que nadie nuevo puede entrar y las cuentas existentes quedan bloqueadas.

   Para probarlo: enviar un correo de verificación a un buzón de control con `SPRING_MAIL_*` y `TRACEABILITY_MAIL_FROM` reales, **antes** de abrir el tráfico.
2. **La web y el móvil manejan el `403 EmailNotVerified`**, con "Reenviar", y el **`403 AcceptanceRequired`**, repitiendo el login con las versiones.
   - Es condición de Q2: no se despliega hasta entonces (`ADR-050-enmienda-2-verificacion-correo-y-avisos.md:18`; `ADR-050-cuenta-y-sesiones.md:86`).
   - Con Google (#166) aplican los mismos dos 403 en `POST /auth/google`.
3. **Los textos legales `v1-2026-10` están publicados** en `paxfide-web/Documentos/legal/` (`auditoria-pendientes-manuel-2026-10-24.md:184`), y las tres variables de §1.2 los nombran. Con R-7 (#165), el texto de los términos de organización lo cambia Carlos (`ficha-R7-perfil-organizacion.md:131`).
4. **DIVIPOLA va dentro del jar** (#165): `app/src/main/resources/divipola/divipola.json`, con `catalogVersion` `DIVIPOLA-2026-09`.
   - Si falta, la aplicación no arranca (*fail-fast*: (#165) `app/src/main/java/com/traceability/app/config/LocationCatalogConfig.java:20-22`).
   - Si su forma no es la esperada, tampoco (`DivipolaLocationCatalog.java:42-73`).
   - No hay que configurar nada.
5. **Google** (#166): poner `GOOGLE_OAUTH_CLIENT_IDS` (§1.7). La salida HTTPS hacia `https://www.googleapis.com/oauth2/v3/certs` tiene que estar permitida.
   - Las llaves se piden la primera vez que hacen falta.
   - Si Google no responde, `POST /auth/google` da 503 `GoogleSignInUnavailable`, y el login con contraseña sigue funcionando.
6. **Orden de los PR pendientes:** #165 y después #166 (son independientes). No hay migraciones manuales: los índices nuevos (`uq_organization_nit`, `uq_google_subject`) son únicos y parciales, y se crean al arrancar (`application.yml:26`).

---

## 3. Comprobaciones tras desplegar

No hay *actuator* ni ruta de salud. Se comprueba con rutas públicas reales (`api/src/main/java/com/traceability/api/auth/PublicRoutes.java:30-51`):

1. **Arranque:** el log no tiene ningún `IllegalStateException` de §1.
2. **Descubrimiento:** `GET /api/v1/public/campaigns` → `200` (`PublicRoutes.java:39`).
3. **DIVIPOLA** (#165): `GET /api/v1/public/locations` → `200`, con `catalogVersion` `DIVIPOLA-2026-09` y `Cache-Control` público de 1 día.
4. **Login:** `POST /api/v1/auth/login` (`PublicRoutes.java:31`) con una cuenta de control verificada → `200 {token}`.
   - Con la contraseña errónea → `401`.
   - Con una cuenta sin verificar → `403 EmailNotVerified`.
5. **Correo de verificación:** registrar una cuenta de prueba (`POST /api/v1/auth/register`, `:32`) o pedir el reenvío (`POST /api/v1/auth/email-verification`, `:37`).
   - Comprobar que llega el enlace con `TRACEABILITY_WEB_BASE_URL`.
   - Confirmarlo (`POST /api/v1/auth/email-verification/confirm`, `:38`) y repetir el login → `200`.
6. **Google** (#166), si está configurado: con un `idToken` real de una cuenta de control vinculada, `POST /api/v1/auth/google` → `200`.
   - Sin vínculo → `401`.
   - El log de arranque **no** debe decir `google-sign-in disabled`.

---

## 4. Deudas y riesgos aceptados abiertos

| Deuda o riesgo | Qué pasa | Dónde |
|---|---|---|
| **DH-56**, sin límite de intentos por IP | El login (con contraseña y con Google), la confirmación de recuperación y la verificación admiten fuerza bruta. Riesgo aceptado 1 de Carlos | `ADR-050-cuenta-y-sesiones.md:239-240`; `ADR-050-enmienda-2-verificacion-correo-y-avisos.md:153`; `plan-adr050-r6-google.md:28` |
| **P-DB1-3**, el logo y la portada no deduplican | Volver a subir los bytes de un logo o una portada retirados por F7 crea un objeto nuevo y vivo | `decisiones-delegadas-2026-10.md:284`; `estado-fase6.md:330` |
| **P-R7-3**, organizaciones existentes con la aceptación anterior (#165) | El backend exige `TRACEABILITY_LEGAL_ORGANIZATION_TERMS_VERSION` solo al crear una organización. Al cambiarla, las existentes no vuelven a aceptar. Límite conocido aprobado por Manuel | `plan-r7-perfil-organizacion.md:126` |
| **La plataforma no puede desvincular un Google** (#166) | El usuario puede desvincular el suyo (`DELETE /api/v1/account/google/link`, ampliación 2 de D9), pero un administrador de plataforma no tiene forma de quitar el vínculo de otra cuenta | `plan-adr050-r6-google.md:130` (P-G-5) y la nota del 2026-10-31 bajo D9 en `ADR-050-cuenta-y-sesiones.md` (#166) |
| **Cuentas existentes sin verificar** (Q2 (a) de la Enmienda 2) | Tras desplegar, cada cuenta real existente tiene que verificar su correo antes de entrar | `ADR-050-enmienda-2-verificacion-correo-y-avisos.md:13,18` |
| **Tokens ya emitidos** | Las cuentas existentes con `credentialsNotBefore = null` conservan sus tokens hasta `exp` (8 h). Tras publicar una versión legal nueva, los tokens emitidos sirven hasta 8 h sin haberla aceptado | `ADR-050-cuenta-y-sesiones.md:242-243` |
| **G-2**, sin verificar | El `aud` del `idToken` del móvil no se ha comprobado en el código del móvil; lo confirmará Adonis al cambiar el `applicationId` a `com.paxfide.mobile`. No bloquea: el backend acepta los tres ID de la lista | `decisiones-delegadas-2026-10.md:343`, y la nota del 2026-10-31 (#166) |
| **`CONVOCATORIA_BANK_TRANSFER_EXPIRATION` sin valor de producto** | El valor de `dev` (`PT72H`) es provisional; producción debe fijarlo | `application-dev.yml:4-6`; `application.yml:118-120` |

> **Nota (2026-11-01):** la fila "`CONVOCATORIA_BANK_TRANSFER_EXPIRATION` sin valor de producto" queda **resuelta**: en producción es `PT48H`, por decisión de Manuel (D-1; ver la nota de §1.5). La fila se conserva. El valor de `dev` (`PT72H`) no cambia.
