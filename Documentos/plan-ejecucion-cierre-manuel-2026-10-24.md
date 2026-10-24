# Plan de ejecución — Cierre de los pendientes de Manuel, 2026-10-24

**Estado: BORRADOR.** Espera la respuesta de Manuel a la revisión. No se ejecuta nada de la Fase 3 antes.
**Fecha:** 2026-10-24 (UTC). **Autor:** Manuel, con asistencia de Claude. **Base:** `origin/develop` `0b3dc3e`.
**Entrada:** `auditoria-pendientes-manuel-2026-10-24.md`. Los identificadores (M-n, B-n, C-n, H-n) son los de la auditoría.

---

## 1. Reglas de ejecución (todas las fases)

- Nunca fusionar, nunca `push --force`, nunca `-DskipTests` ni tests deshabilitados.
- **Ningún plan, ADR ni enmienda se marca como aprobado** sin un texto de Carlos citable con su enlace o su commit ("plan aprobado", una revisión del PR o un comentario). La auditoría (H-1) muestra que hoy **ningún PR tiene revisiones en GitHub**. Por eso la aprobación que se espera en cada punto de parada es **una revisión o un comentario de Carlos en el PR**.
- No se escribe código de un plan sin esa aprobación citada.
- Contradicción con un ADR o una ficha CONGELADA: se anota con la ruta y la línea y se sigue con lo que no dependa de ella.
- Ninguna rama remota se borra (auditoría §5: solo la lista).
- Ningún secreto en el código, Git, `application.yml` ni logs.
- **Antes de cada `ci-local.sh`:** `free -g` con al menos 4 GB disponibles. Si no, detenerse y avisar. El 2026-10-24 había **1 GB** (H-7).
- Toda afirmación de que los tests pasan va con el resumen literal de Surefire.
- Un PR por bloque o tarea, hacia `develop`, con Carlos (`Toffy22Cj`) como revisor.
- **Autoauditoría antes de entregar cada paso (regla 2.2):** releer el ADR o la ficha que lo norma y compararlo literalmente con lo hecho. El resultado va en el PR, en una tabla "norma → lo hecho → coincide".
- **Los PR solo de documentos no corren `ci-local.sh`:** no cambian código. Se dice así en cada PR.

## 2. Orden y por qué

| Orden | Paso | Pendientes | Por qué en este lugar |
|---|---|---|---|
| 0 | PR de esta auditoría y de este plan | — | Deja por escrito lo que se va a hacer antes de hacerlo |
| 1 | Correcciones del #114 | M-2 a M-5 | Están pedidas, son pequeñas y desbloquean la revisión de los planes C y D, que es lo que el front espera antes (⛔ G-3, G-4, G-7, G-8) |
| 2 | Cierre de la Enmienda 5 y nota de A2 | M-16, M-17 | Solo documentos, sin dependencias; cierra §0.35 de `estado-fase6.md` |
| 3 | PR de CORS, marcado "espera el sí de Carlos" | M-15 | El logo (#111) ya tiene `PUT` y `DELETE` que un navegador no puede usar. Va antes de los planes nuevos porque es pequeño y queda listo para cuando Carlos decida |
| 4 | Plan R-3 + R-2 actualizado | M-8 | Primera tarea de ADR-050 (`:289`); R-4 y D6/D7 dependen de `credentialsNotBefore` |
| 5 | Plan R-4 | M-9 | Orden de ADR-050 (`:290`) |
| 6 | Plan R-5 y D1 | M-10 | Orden de ADR-050 (`:291`) |
| 7 | Plan D6 y D7 | M-11 | Orden de ADR-050 (`:292`) |
| 8 | Plan de ADR-051 Tarea 4 | M-13 | De Manuel (`ADR-051:300`). Va antes de Google porque Google es "la última prioridad" (`ADR-050:177`) |
| 9 | Plan de Google, marcado "pendiente de que Carlos confirme si entra en este corte" | M-12 | Al final, por `ADR-050:177` y por el encargo |
| 10 | Implementaciones, cada una cuando se desbloquea | M-6, M-7, M-8 a M-14 | Solo con plan aprobado y citable. Si se desbloquean varias a la vez, el orden es: C, D, R-3 + R-2, R-4, R-5 y D1, D6 y D7, ADR-051 T4, ADR-051 T5 y Google |

## 3. Cada paso

### Paso 0 · PR de la auditoría y del plan

- **Autónomo:** la rama `chore/docs-auditoria-cierre-manuel` ya tiene los dos documentos, en un commit. En la Fase 3, añadir como nota fechada la respuesta de Manuel a la revisión, hacer push sin forzar y abrir el PR.
- **Se detiene en:** la revisión de Carlos del PR. No bloquea ningún paso siguiente.
- **Mientras espera:** el paso 1.

### Paso 1 · Correcciones del PR #114 (`chore/docs-r6-planes-c-d`)

- **Autónomo:**
  - **Plan C, P-R6C-1 (M-3):**
    - El puerto devuelve un `enum` de cuatro valores (`ANCHORED`, `IN_BATCH`, `PENDING`, `UNDER_REVIEW`) declarado en `contracts`, no un `String`.
    - Se cambian la firma de §2.2, la tabla de §2.3 y la recomendación de P-R6C-1, y se añade el nombre del `enum` como propuesta.
    - El test 9c recorre los valores del `enum` además de `AnchorStatus.values()`.
  - **Plan C, sequence de las proyecciones antiguas (M-2):** según lo que Manuel elija en su respuesta a C-1.
    - **(a)** `AssetTransitionReadModel.sequence` pasa a `Long`. El adaptador lee el documento crudo y deja `null` cuando falta el campo. Un paso con `sequence` nulo es `PENDING` sin consultar el mapa.
      - Test nuevo: una proyección guardada sin el campo, con el génesis del activo en un lote `ANCHORED`, da `PENDING` en todos sus pasos.
      - Mutación nueva: leer el campo ausente como 0, o como la `sequence` del génesis.
      - El plan dice que el génesis es la `sequence` 1 (C-1) y que el cambio es defensivo.
    - **(b)** Sin cambio de modelo. Nota fechada en P-R6C-3 con las líneas de C-1.
    - **Sin respuesta:** el commit del plan C lleva M-3 y una nota fechada en P-R6C-3, "pendiente de la elección de Manuel sobre C-1". No se resuelve.
  - **Plan D §2.2 (M-4):** escribir que los lotes de `BATCH_ANCHORED` salen de la agregación por `merkleBatchId` de G-7 (los lotes con eventos propios) y después de `MerkleBatchRepositoryPort.findByBatchId` (`:12`), nunca consultando `coverage`. La fecha es `anchoredAt` de esos lotes `ANCHORED`, y el recuento es el número de eventos propios de la agregación.
  - **Plan D §6.2 (M-5):** añadir una columna "Propuesta de Manuel, pendiente de Carlos".
    - **P-R6D-1:** opción (b), sin `subject` y con `eventCount`.
    - **P-R6D-2:** opción (a), limitada a las convocatorias de la organización con `status = CLOSED`. Se cita `ConvocatoriaStatus.java:8`, `Convocatoria.java:144`, `ConvocatoriaRepositoryPort.java:26` y `:29` (que no filtra por estado), `ConvocatoriaDocument.java:29` y `CampaignTimelineQuery.java:30-31` (la fecha sale del registro de auditoría, no de la convocatoria).
    - Las opciones originales no se borran.
  - Un commit por plan y push sin forzar. Pegar el diff de cada commit.
- **Autoauditoría:** ficha R-6 §5 y §6 línea a línea; la corrección no cambia la ficha.
- **Se detiene en:** la revisión de Carlos del #114, que incluye sus respuestas a P-R6D-1 y P-R6D-2 (B-2).
- **Mientras espera:** el paso 2.

### Paso 2 · Cierre de la Enmienda 5 de ADR-037 y nota de A2 (`chore/docs-cierre-enmienda5-a2`)

- **Autónomo:**
  - **`ADR-037-enmienda-5-…md:5`:** la marca `[Falta: …]` no se borra. Se tacha y se añade una nota fechada: "aprobada el 2026-10-22 a las 00:12, hora de Colombia (05:12Z), por WhatsApp; dato aportado por Manuel el 2026-10-24".
    - El encargo escribe el medio como `<Whatsap>`. Se pone "WhatsApp"; si Manuel quiere el texto literal, lo dice en su respuesta.
    - Se deja escrito que no hay enlace ni commit de Carlos que lo cite (H-3).
  - **`estado-fase6.md` `:235` y `:254`, y `plan-a2-historial-donaciones.md:6`:** la misma nota fechada, sin reescribir las líneas.
  - **Ficha L-FRONTS (M-17):** nota fechada "A2 implementado en `0b3dc3e` (#112)".
  - **Las dos cosas van en un solo PR** porque cierran juntas la lista "Pendiente" de §0.35. Si Manuel prefiere dos PR, se separan.
- **Autoauditoría:** que ninguna línea anterior se reescribe (`git diff` solo con añadidos y tachados).
- **Se detiene en:** la revisión de Carlos.
- **Mientras espera:** el paso 3.

### Paso 3 · CORS con `PUT` y `DELETE` (`fix/cors-put-delete`), **"espera el sí de Carlos"**

- **Tensión que Manuel resuelve en su respuesta:** el encargo pide el PR preparado, y la regla dice "no escribas código de un plan sin aprobación citada". DD-57 está RATIFICADA con `GET`, `POST` y `OPTIONS` (`decisiones-delegadas-2026-10.md:98`).
  - **Propuesta:** un PR **en borrador** (*draft*), con el plan en la descripción, el código y los tests, titulado "[espera el sí de Carlos]".
  - **Alternativa:** si Manuel prefiere no escribir código antes del sí, el PR lleva solo el plan.
- **Autónomo (con la propuesta):**
  - `CorsConfig.ALLOWED_METHODS` (`CorsConfig.java:34`) con `PUT` y `DELETE`;
  - `CorsConfigTest` con la lista exacta;
  - una prueba de *preflight* `OPTIONS` con `Access-Control-Request-Method: PUT` sobre `/api/v1/organizations/{id}/logo`, que pasa sin JWT y devuelve el método permitido;
  - una negativa: `PATCH` sigue sin permitirse;
  - una nota fechada en DD-57 marcada "propuesta, pendiente de Carlos", sin cambiar su estado RATIFICADA;
  - `referencia-api-v1.md`.
  - Rojo primero, verde por capas (`mvn test -pl api` y `mvn test -pl app`), mutación (quitar `DELETE` → muere la prueba de lista exacta) y `ci-local.sh` en un clon limpio **solo si hay 4 GB libres**.
- **Autoauditoría:** DD-57 y S-03 (sin comodines ni credenciales; `max-age` 600; solo `/api/v1/**`).
- **Se detiene en:** el "sí" de Carlos en el PR. El PR sigue en borrador hasta entonces.
- **Mientras espera:** el paso 4.

### Paso 4 · Plan R-3 + R-2 actualizado (`chore/docs-plan-r3-r2`)

- **Autónomo:**
  - Copiar `~/Escritorio/plan-r3-r2-revocacion-y-cambio-de-contrasena.md` a `Documentos/`. El original **no se borra**.
  - Primer commit: la copia literal, para que el diff de lo cambiado se vea. Segundo commit: los cambios, como notas fechadas, sin reescribir:
    - **Dueño:** Manuel, por su indicación del 2026-10-24 (H-4).
    - **Base nueva:** `0b3dc3e`, y rama desde el `develop` del momento de implementar.
    - **§2 reverificado:** la tabla de la auditoría §2. Cambia "10 clases de test" a 11 (`OrganizationLogoReferenceIntegrationTest`); la firma de 6 argumentos que se conserva cubre también esa. Cambia §7: Maven y `ci-local.sh` corren en la sesión del agente.
    - **§6:** el `ci-local.sh` en un clon limpio, con la evidencia en `Documentos/evidencia-ci/`; mutaciones con `scripts/mutaciones.py`.
- **Autoauditoría:** ADR-050 D4, D5, D6 (la parte de `credentialsNotBefore`) y los tests 1 a 10, línea a línea, contra el plan.
- **Se detiene en:** "plan aprobado" de Carlos en el PR. Después, la implementación (paso 10).
- **Mientras espera:** el paso 5.

### Pasos 5 a 7 · Planes nuevos de ADR-050: R-4, R-5 y D1, D6 y D7

Una rama y un PR de documentos por plan: `chore/docs-plan-r4`, `chore/docs-plan-r5-d1`, `chore/docs-plan-d6-d7`.

**Estructura de cada plan:**
- verificación contra el código, con ruta y línea;
- alcance;
- cambios por módulo, con la fuente de cada clase (regla 2.1);
- comportamiento ante fallos;
- tests en rojo con la numeración de la definición de hecho de ADR-050;
- mutaciones;
- pasos;
- riesgos y preguntas con recomendación.

**Lo propio de cada plan:**

| Plan | Tests de ADR-050 | Dependencias que el plan declara | Preguntas previstas |
|---|---|---|---|
| R-4 | 11-18 | M-8 (`credentialsNotBefore`, D8) | Nombre del ejecutor y tamaño de su cola (Q5 (a) fija el tipo, no el tamaño); el texto exacto del correo |
| R-5 y D1 | 19-23 | B-8: las versiones vigentes y quién las fija | Valores de `traceability.legal.*` en `dev` y en los tests |
| D6 y D7 | 24-26 | M-8 (desactivar fija `credentialsNotBefore`); `CampaignResponsibilityPort` (ADR-049 D9) | — |

- **Autoauditoría:** la decisión de ADR-050 correspondiente (D8; D1, D2, D3 y D10; D6 y D7), línea a línea.
- **Se detiene en:** "plan aprobado" de Carlos en cada PR.
- **Mientras espera:** el plan siguiente.

### Paso 8 · Plan de ADR-051 Tarea 4, evidencia de entrega (`chore/docs-plan-adr051-t4`)

- **Autónomo:** el plan, con F3 (formato, vínculo y orden de los pasos), F8 (ruta y permisos de subida), tests 6 a 11 y 9b, `DemoScenarioSeeder` y `GoldenPathHttpIntegrationTest`.
  - El lado del móvil (B-7, Adonis) queda **fuera** y se cita.
  - La Tarea 5 **no** se planifica hasta que exista la enmienda de ADR-021-D (B-5).
- **Autoauditoría:** ADR-051 F3, F8 y F11.
- **Se detiene en:** "plan aprobado" de Carlos.
- **Mientras espera:** el paso 9.

### Paso 9 · Plan de Google (`chore/docs-plan-google`)

- **Autónomo:** el plan de D9 (tests 27 a 29), con el estado "**pendiente de que Carlos confirme si entra en este corte**". Declara B-11 (los ID de cliente) y que `GOOGLE_OAUTH_CLIENT_IDS` nunca va con valores reales en el repositorio.
- **Se detiene en:** la confirmación de Carlos y su "plan aprobado".
- **Mientras espera:** lo que se haya desbloqueado del paso 10; si no hay nada, el informe (Fase 4).

### Paso 10 · Implementaciones (cada una, solo si se cumple su condición)

| Implementación | Condición para empezar (toda citable) | Rama |
|---|---|---|
| Bloque C (G-4, G-3) | #113 fusionado; "plan aprobado" de Carlos del plan C (#114) | `feat/r6-bloque-c` desde ese `develop` |
| Bloque D (G-7, G-8) | B y C fusionados; respuestas de Carlos a P-R6D-1 y P-R6D-2 (y su cambio de ficha, si lo hace, en su propio PR); "plan aprobado" del plan D | `feat/r6-bloque-d` |
| R-3 + R-2 | "plan aprobado" (paso 4) | `feat/identity-r3-r2-revocacion-contrasena` |
| R-4 | "plan aprobado" (paso 5); R-3 + R-2 fusionado | `feat/identity-r4-recuperacion` |
| R-5 y D1 | "plan aprobado" (paso 6); las versiones de B-8 | `feat/identity-r5-aceptaciones-perfil` |
| D6 y D7 | "plan aprobado" (paso 7); R-3 + R-2 fusionado | `feat/identity-d6-d7-desactivacion` |
| ADR-051 T4 | "plan aprobado" (paso 8) | `feat/app-adr051-t4-evidencia` |
| ADR-051 T5 | Enmienda de ADR-021-D (B-5); T4 fusionada; plan aprobado | — |
| Google | Confirmación de Carlos (B-4); "plan aprobado" (paso 9); B-11 | — |

**Cada implementación sigue el mismo procedimiento:**
1. Rama desde el `develop` del momento, con el hash.
2. Tests rojos con la salida literal; el rojo es una aserción o un fallo de compilación, nunca un test mal escrito.
3. Verde por capas, con `mvn test -pl <módulo>` completo en cada una.
4. Mutaciones con `scripts/mutaciones.py`.
5. Documentos.
6. `free -g` y `ci-local.sh` en un clon limpio, con la evidencia commiteada.
7. Autoauditoría.
8. PR.

- **Se detiene en:** la revisión de Carlos de cada PR.
- **Mientras espera:** la siguiente implementación desbloqueada, o el informe.

### Lo que este plan no ejecuta

- **M-18, avisos a la web y al móvil:** son fuera de este repositorio. El informe deja el texto de cada aviso listo para enviar.
- **M-19, DT-1 y R-1:** deuda registrada, sin tarea en este corte.
- **Borrar ramas:** solo la lista (auditoría §5).
- **Las ramas `claude/*` y `chore/front-fase1-env`:** no se tocan (auditoría §5.3).

## 4. Fase 4 · Informe

`Documentos/informe-sesion-manuel-2026-10-24.md`, con:
- lo hecho, con sus commits y sus PR;
- lo que queda esperando y a quién;
- la evidencia literal (Surefire, mutaciones, `explain()`);
- las contradicciones (C-1 y las que aparezcan);
- lo que no se pudo hacer y por qué.

Nada se reporta como hecho sin su evidencia. Va en su propio PR de documentos o, si Manuel lo prefiere, en la rama del paso 0.

## 5. Lo que Manuel decide en su respuesta a la revisión

1. **C-1:** la opción para la `sequence` de las proyecciones antiguas, (a), (b) u otra.
2. **CORS:** PR en borrador con código, o solo el plan.
3. **Enmienda 5:** "WhatsApp" o el texto literal `<Whatsap>`. Y si la nota de A2 va en el mismo PR.
4. **Paso 0:** si este plan y la auditoría van en un PR propio (propuesto) o en otro.
