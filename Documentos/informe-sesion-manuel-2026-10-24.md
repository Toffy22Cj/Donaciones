# Informe de la sesión del 2026-10-24 — bloque API/backend de Manuel

**Fecha:** 2026-10-24, con un cierre el 2026-10-25 (UTC).
**Redactado por:** Claude Code. Primero hubo una sesión local; desde las 22:30Z, una sesión web que la retomó con `traspaso-claude-code-web-2026-10-24.md`, que no está en el repositorio.
**Para:** Manuel y Carlos (`Toffy22Cj`).
**Base:** `develop` `8e37667`.
**Fuente:** el plan de ejecución, §3 (`plan-ejecucion-cierre-manuel-2026-10-24.md:112-124`).
**Solo documentos:** este PR no ejecuta `ci-local.sh`.

Todo lo que este informe dice sobre GitHub se consultó el 2026-10-25 con la API de GitHub y con `git log` sobre `develop`. Donde un dato viene solo del traspaso de la sesión local, se dice.

---

## 1. Lo hecho, con sus commits y sus PR

| PR | Rama y commit | Qué | Estado (2026-10-25) |
|---|---|---|---|
| #113 | `feat/r6-bloque-b` (`9ba02a2`) | Ficha R-6, Bloque B: G-2 y G-6, índice del directorio de fondos | Fusionado, `5dc385a`, 21:06:43Z |
| #114 | `chore/docs-r6-planes-c-d` | Planes de los Bloques C y D | Fusionado, `52df764` |
| #115 | `chore/docs-auditoria-cierre-manuel` | Auditoría de pendientes y plan de ejecución, versión 2 | Fusionado, `506734a` |
| #116 | `chore/docs-cierre-enmienda5-a2` | Cierre de la marca de la Enmienda 5 de ADR-037 y nota de A2 | Fusionado, `32089f8` |
| #117 | `chore/docs-ficha-r6-enmienda-1` | Ficha R-6, Enmienda 1 (PROPUESTA): E1 y E2 | Fusionado, `32b091a` |
| #118 | `chore/docs-plan-r3-r2` | Plan R-3 + R-2 revisado | Fusionado, `19b4e0f`, 21:08:08Z |
| #119 | `chore/docs-plan-r3-r2-decisiones` | Plan R-3 + R-2, §10 | Fusionado, `19ab4d1`, 21:54:40Z |
| #120 | `chore/docs-adr050-enmienda-1` (`44d2d1b`) | ADR-050, Enmienda 1, parte 1 (P-R3-3) | Fusionado, `8e37667`, 21:54:54Z |
| #121 | `chore/docs-plan-r4` (`d424df8`) | Plan R-4, APROBADO por Manuel | Abierto |
| #122 | `chore/docs-plan-r5-d1` (`fe32080`) | Plan R-5 + D1, BORRADOR | Abierto |
| #123 | `chore/docs-plan-d6` (`dd88a6c`) | Plan D6, BORRADOR | Abierto |
| #124 | `chore/docs-plan-r6-bloque-b-g9` (`bbced9b`) | Plan B, adenda G-9, BORRADOR | Abierto |
| #125 | `chore/docs-adr050-enmienda-1-parte-2` (`cff94c8`) | Enmienda 1, parte 2: enmienda de ADR-038 E1 | Abierto |
| #126 | `chore/docs-plan-r6-bloque-c-v2` (`9192a01`) | Plan C, versión 2 (E1, prueba de Merkle), BORRADOR | Abierto |
| **#127** | `feat/identity-r3-r2-revocacion-contrasena`: `11e018d`, `1b0a32b`, `12ef1e8`, `bf2ea21` | **Código de R-3 + R-2** (ADR-050 D4, D5 y D6 en parte) | Abierto, `mergeable_state: clean`; sin reviews ni comentarios |
| #128 | `chore/docs-plan-p-r3-3` (`076d809`) | Plan corto de P-R3-3, BORRADOR | Abierto |

Los PR de los puntos c) y d) del encargo de Manuel (notas fechadas y plan de ADR-051 T4) van aparte y no figuran en esta tabla.

## 2. Evidencia literal de #127 (R-3 + R-2)

**`scripts/ci-local.sh`** en un clon limpio de `12ef1e8`, con `free -g` en 15 GB disponibles. La evidencia está en `Documentos/evidencia-ci/ci-local-12ef1e8-2026-10-24T22-50-09Z.txt` (rama de #127):

```
[INFO] No tests to run.                                          (contracts)
[INFO] Tests run: 336, Failures: 0, Errors: 0, Skipped: 0        (core)
[INFO] Tests run: 50, Failures: 0, Errors: 0, Skipped: 0         (crypto)
[INFO] Tests run: 47, Failures: 0, Errors: 0, Skipped: 0         (ai)
[INFO] Tests run: 154, Failures: 0, Errors: 0, Skipped: 0        (api)
[INFO] Tests run: 332, Failures: 0, Errors: 0, Skipped: 0        (identity)
[INFO] Tests run: 253, Failures: 0, Errors: 0, Skipped: 0        (convocatoria)
[INFO] Tests run: 462, Failures: 0, Errors: 0, Skipped: 0        (app)
[INFO] BUILD SUCCESS
```

El nombre del módulo entre paréntesis lo he añadido yo; en la evidencia va en la línea `Building …` anterior.

**Mutaciones** (`scripts/mutaciones.py`), en otro clon limpio y con la CI ya terminada: "Restauración verificada contra HEAD. Mutaciones vivas: 0" (10 de 10). La tabla de qué test mató cada una está en el cuerpo de #127.

**Entorno:** Docker Hub respondía 429, así que dockerd usó el espejo `mirror.gcr.io`. Esa configuración está fuera del repositorio. `bitnamilegacy/minio` llegó con su digest fijado.

**`explain()`:** no se midió nada en esta sesión. Las mediciones que piden los planes C y G-9 van con su código, que todavía no tiene aprobación.

## 3. Lo que queda esperando y a quién

| Espera a | Qué |
|---|---|
| **Carlos** | Review y Approve de #127 (R-3 + R-2) |
| **Carlos** | Approve de #125 (Enmienda 1, parte 2) |
| **Carlos** | ¿Cuenta como aprobación de la Enmienda 1, parte 1, que #120 se fusionara sin review? `ADR-050-enmienda-1-iat-del-login.md:5` sigue en PROPUESTA. Bloquea el código de P-R3-3 (#128, B-1) y, por eso, el de R-4 |
| **Carlos** | B-G9-1 / B-C-1: `ficha-R6-enmienda-1.md:3` sigue en "PROPUESTA" y #117 se fusionó sin review. ¿Está aprobada? Sin la respuesta no empieza el código de G-9 ni el de E1 |
| **Carlos** | D7 (reactivación por el administrador de plataforma): ¿va en el PR de D6, en uno propio o se pospone? (#123) |
| **Carlos** | P-R5-1: D3 (`403 AcceptanceRequired`) choca con ADR-050 Q1 (`ADR-050-cuenta-y-sesiones.md:197`, "no toca el filtro ni el puerto"). Se recomienda una parte 3 de la Enmienda 1 (#122) |
| **Carlos** | R-R3-1 / R-R4: límite de intentos de la contraseña actual y de la confirmación de la recuperación |
| **Carlos** | Quién es dueño de la rama `claude/focused-goodall-meb6sh` (§6) |
| **Manuel** | "Plan aprobado" de #122 (R-5 + D1), #123 (D6), #124 (G-9), #126 (plan C) y #128 (P-R3-3) |

> **Nota (2026-10-25): aprobaciones vigentes de Manuel desde este informe.**
> - **G-9 (#124):** "Plan aprobado — G-9 (#124) con P-G9-1 (b), Manuel, 2026-10-25" (§9 del plan, `7535b66`). B-G9-1 sigue abierto para Carlos.
> - Siguen pendientes #122, #123, #126, #128 y #131 (plan de ADR-051 T4, abierto después de este informe).

**Dependencias de código:**
- R-4 necesita tres cosas: #127 fusionado, el PR de código de P-R3-3 fusionado y #125 aprobado.
- P-R3-3 necesita la confirmación de B-1 y que #127 esté fusionado o, al menos, ser su base.
- D6 y R-5 necesitan #127 fusionado.

## 4. Lista H-1 para Carlos: PR fusionados sin Approve

**Criterio:** fusionados en `develop` sin ninguna review en GitHub.
- La búsqueda `is:merged review:none merged:>=2026-10-08` da 34 PR: del #87 al #120, todos los fusionados desde esa fecha.
- La búsqueda inversa (`-review:none`) da 0.
- **Quién fusionó:** el autor del commit de merge en `develop`. GitHub pone ahí a quien fusiona desde la web.

| PR | Fusionado (UTC) | Fusionó | Commit de merge |
|---|---|---|---|
| #120 | 2026-10-24 21:54:54 | Carlos (`Toffy22Cj`) | `8e37667` |
| #119 | 2026-10-24 21:54:40 | Carlos | `19ab4d1` |
| #118 | 2026-10-24 21:08:08 | Carlos | `19b4e0f` |
| #117 | 2026-10-24 21:07:55 | Carlos | `32b091a` |
| #116 | 2026-10-24 21:07:39 | Carlos | `32089f8` |
| #115 | 2026-10-24 21:07:20 | Carlos | `506734a` |
| #114 | 2026-10-24 21:07:05 | Carlos | `52df764` |
| #113 | 2026-10-24 21:06:43 | Carlos | `5dc385a` |
| #112 | 2026-10-22 06:35:03 | Manuel (`buelvasmanuel`) | `0b3dc3e` |
| #111 | 2026-10-22 06:15:21 | Manuel | `902c100` |
| #110 | 2026-10-22 02:14:43 | Manuel | `aae7492` |
| #109 | 2026-10-22 02:14:16 | Manuel | `c43951c` |
| #108 | 2026-10-22 02:14:00 | Manuel | `3f263fa` |
| #107 | 2026-10-21 19:15:27 | Carlos | `fdccd99` |
| #106 | 2026-10-21 18:36:52 | Carlos | `7b7b214` |
| #105 | 2026-10-16 20:53:49 | Manuel | `4bef7be` |
| #104 | 2026-10-08 08:33:12 | Manuel | `15af37f` |

- **Del #104 al #112** coincide con la auditoría (`auditoria-pendientes-manuel-2026-10-24.md:99`, H-1).
- **Del #113 al #120** se añaden en esta sesión. Para #113 a #118, el traspaso dice que los fusionó Carlos "sin Approve ni review", y `git log` lo confirma.
- **Hallazgo nuevo, solo registrado:** del **#87 al #103** (2026-10-08, de 00:08Z a 08:18Z) tampoco hay ninguna review. Del #92 al #103 los fusionó Manuel, según sus commits de merge en `develop`. Del #87 al #91 no hay commit "Merge pull request" en `develop`: no se ha comprobado en qué rama ni quién los fusionó. La auditoría acotó H-1 al #104 en adelante. Este informe no cambia ese alcance y deja a Carlos decidir si la lista se amplía.
- **#127:** abierto y sin fusionar. Si se fusiona sin Approve, entra en esta lista con una nota fechada.

### Consecuencias de las fusiones sin review

- El commit `4a99fe8` (plan R-3 + R-2, §10) llegó a la rama de #118 después de la fusión y se movió a #119.
- La adenda G-9 tuvo que ir en su propio PR (#124), porque el plan B ya estaba fusionado.
- #120 se fusionó solo con la parte 1 (`44d2d1b`). La parte 2 (`5794282`) se subió después y se movió a #125 como cherry-pick.
- Dos documentos fusionados siguen diciendo PROPUESTA (`ADR-050-enmienda-1-iat-del-login.md:5` y `ficha-R6-enmienda-1.md:3`). Por la regla 2 del traspaso no se marcan como aprobados sin una aprobación citable (§3).

## 5. Avisos a la web (F-C1, F-C2 y F-C3)

Carlos pidió que se le avise cuando cada PR llegue a `develop` (`decisiones-delegadas-2026-10.md:168-169`).

**[Falta: qué PR desbloquea F-C1, F-C2 o F-C3.]**
- Esa correspondencia está en `paxfide-web/Documentos/plan-fase7-fidelidad.md`, que no está en este repositorio ni en esta sesión.
- El único enlace que hay aquí es `ficha-R6-enmienda-1.md:65`: los vectores de E1.5 son "para que la web los use en su test de F-C2".

Hasta que alguien confirme la correspondencia, los textos van por PR, con el F-C entre corchetes.

**Aviso 1 — Bloque B de R-6 (#113), `develop` `5dc385a` (2026-10-24 21:06:43Z). Listo para enviar:**

> Ya está en `develop` (`5dc385a`, PR #113) el Bloque B de la ficha R-6 [¿F-C…?]:
> - **G-2:** el seguimiento trae `financialSnapshot.firstClearedAt?` y `lastClearedAt?`, el primer y el último pago liquidado del fondo. Son opcionales: no salen si el fondo no tiene pagos.
> - **G-6:** `GET /api/v1/organizations/{organizationId}/funds/monthly?months=1..12` da el recaudado por mes y por moneda, con el corte de mes en `America/Bogota` y los meses vacíos en 0. Lleva `truncated` si se supera el tope de 10 000 fondos y responde con `no-store`.
> - **G-9 (`lastEventAt?` por lote) no está incluido:** va en la adenda del plan B (#124), que espera aprobación.

**Aviso 2 — R-3 + R-2 (#127). Se envía cuando se fusione, con el commit de merge:**

> Ya está en `develop` (`<commit>`, PR #127) el cambio de contraseña y la revocación de sesiones (ADR-050 D4 y D5) [¿F-C…?]:
> - `POST /api/v1/account/password`, con JWT y `{currentPassword, newPassword}`, responde `200 {token}`. **El token devuelto sustituye al anterior**: todas las demás sesiones quedan revocadas.
> - Una contraseña actual incorrecta da `400 CurrentPasswordIncorrect`, nunca 401: no hay que cerrar la sesión. Una contraseña nueva de menos de 12 caracteres da `400 PasswordTooShort`.
> - Si responde 500 después de confirmar el cambio, la contraseña sí cambió y todas las sesiones se revocaron: hay que volver a iniciar sesión.
> - **Defecto conocido (P-R3-3):** un login hecho en el mismo segundo que un cambio puede recibir un token que da 401. Repetir el login un segundo después lo resuelve. Se corrige en un PR propio (#128).

## 6. Rama `claude/focused-goodall-meb6sh`

- En el remoto está en `e03f9cc`, sin PR.
- Según la auditoría (`auditoria-pendientes-manuel-2026-10-24.md:164`), tiene "biometría con credencial de dispositivo (`identity`) y lista de convocatorias para el `REPRESENTATIVE`. **Código no fusionado y sin ADR citado**".
- **Pregunta para Carlos:** ¿de quién es y qué se hace con ella? Esta sesión no la tocó (regla 6 del traspaso: no borrar ramas remotas).

## 7. Contradicciones anotadas (con ruta y línea; ninguna resuelta aquí)

| # | Dónde | Qué | Quién decide |
|---|---|---|---|
| C-1 | `ADR-050-cuenta-y-sesiones.md:197` (Q1 (a), "no toca el filtro ni el puerto") frente a D3 | D3 exige cambiar `AuthenticateAccountPort` (P-R5-1) | Carlos |
| C-2 | `ADR-050-enmienda-1-iat-del-login.md:5` y `ficha-R6-enmienda-1.md:3` | Dicen PROPUESTA y ya están en `develop` por fusiones sin review | Carlos |
| C-3 | Plan de ejecución §0.5 (la agregación para G-9) frente al código (`PhysicalAssetOperationalQueryService.listForOrganization` ya carga cada stream ordenado por `sequence`) | P-G9-1 (#124): con lo que ya hay, sale con 0 consultas extra | Manuel |
| C-4 | Ficha R-6, Enmienda 1, E1.2 ("Siempre" `eventHash`) frente a un paso sin evento | P-C-4 (#126): mejor omitirlo que inventarlo | Manuel |
| C-5 | `auditoria-pendientes-manuel-2026-10-24.md:43` (fila M-11) sitúa `PlatformCommandType` en `contracts`; está en `identity` | Se corrige con una nota en el plan D6 (#123) | — (es un dato) |

## 8. Lo que no se pudo hacer y por qué

- **`ci-local.sh` de R-3 + R-2 en la sesión local:** la máquina tenía 3 GB disponibles, por debajo de los 4 que pide la regla. Se hizo en la sesión web (§2).
- **Avisar a Carlos de cada fusión:** esta sesión no tiene un canal hacia Carlos fuera de GitHub. Los avisos van en §5 para que Manuel los reenvíe.
- **La correspondencia con F-C1, F-C2 y F-C3:** vive en otro repositorio (§5).
- **Las mediciones con `explain()`:** dependen de código sin aprobación (§2).

## 9. Corrección de un término usado en la sesión

En un mensaje de la sesión local, no en ningún documento, se llamó **"inofensiva"** a la opción (b) de P-R3-3 (un puerto nuevo que lee `credentialsNotBefore` en una segunda lectura). **Era un error:**
- si la contraseña cambia entre las dos lecturas, un login hecho con la contraseña anterior recibiría un token válido después del cambio, y eso se salta la revocación de D4;
- por eso (b) se descartó por seguridad (`plan-r3-r2-revocacion-y-cambio-de-contrasena.md:207`).

El repositorio no recoge la palabra en relación con P-R3-3 (`grep -ri inofensiv Documentos/`).
