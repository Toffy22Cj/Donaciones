# Plantillas de los correos

Las usa `MailTemplates` (`app/src/main/java/com/traceability/app/infrastructure/mail/`). Cada correo tiene una pieza
HTML y una de texto plano, y se envían juntas (`multipart/alternative`): los clientes que no muestran HTML ven el texto.

| Fichero | Correo |
|---|---|
| `marco.html`, `marco.txt` | El marco común: cabecera con la marca, tarjeta, botón, enlace de respaldo y pie |
| `verificacion.*` | Confirmar el correo al crear la cuenta (ADR-050 Enmienda 2, E2-2) |
| `invitacion.*` | Invitación a una organización (ADR-049 D5) |
| `recuperacion.*` | Recuperar la contraseña (ADR-050 D8) |
| `aviso-seguridad.*` | Avisos de seguridad, sin enlace con token (ADR-050 Enmienda 2, E2-4) |

## Sintaxis

- `{{nombre}}`: un valor. En HTML se escapa siempre (`&`, `<`, `>`, `"`, `'`), así que un nombre de organización no
  puede inyectar marcado. En texto va tal cual.
- `{{{contenido}}}`: la pieza del correo ya renderizada dentro del marco. Solo la usa el marco.
- `{{#nombre}}…{{/nombre}}`: el bloque solo aparece si el valor no está vacío (el logo, el correo de soporte, el botón).

Un `{{…}}` sin valor hace fallar el envío con un error claro; los tests renderizan todas las plantillas.

## Reglas que no se cambian aquí

- **El token va en el fragmento** (`#token=…`), nunca en la ruta ni en la query (ADR-049 D3).
- **La invitación no lleva datos de quien invita** (ADR-049 D5): ni su nombre ni su correo.
- **Los avisos de seguridad no llevan enlaces con token**, contraseñas, IP ni dispositivo (E2-4, Q5).
- No hay nombre del destinatario: la cuenta no lo guarda.
- Los asuntos están en el código del adaptador (ADR-049 y E2-4 los fijan).

## Valores opcionales (por entorno)

| Variable | Para qué | Si falta |
|---|---|---|
| `TRACEABILITY_MAIL_LOGO_URL` | URL pública **https** de un PNG de 80×80 para la cabecera (no SVG: Gmail no lo muestra) | Solo la marca en texto |
| `TRACEABILITY_MAIL_SUPPORT_EMAIL` | Correo de soporte del pie | El pie no ofrece contacto |
