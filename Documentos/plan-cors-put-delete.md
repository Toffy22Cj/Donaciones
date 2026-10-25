# Plan CORS — `PUT` y `DELETE` en los métodos permitidos (cambio a DD-57)

**Estado: BORRADOR. [espera el sí de Carlos].** Cambia DD-57, que está **RATIFICADA — Carlos, 2026-10-08**
(`decisiones-delegadas-2026-10.md:98`), así que necesita el sí de Carlos. Y como no hay plan aprobado, **no se escribe
código** hasta que Manuel lo apruebe por escrito (regla 3.4; encargo de la sesión A del 2026-10-25: "No hay código
hasta que Manuel lo apruebe por escrito").
**Fecha:** 2026-10-25 (UTC).
**Base:** `develop` `406f9b2`. Rama: `fix/cors-put-delete`. El PR va **en borrador** hasta los dos síes.
**Dueño:** Manuel (sesión A).
**Origen:** observación del plan de la Tarea 2 de ADR-051 (`plan-adr051-tarea2-logo.md:267`), M-15 de la auditoría
(`auditoria-pendientes-manuel-2026-10-24.md:50`) y el paso 3 del plan de ejecución
(`plan-ejecucion-cierre-manuel-2026-10-24.md:220`).

## 1. Hechos verificados en `develop` `406f9b2`

- `api/src/main/java/com/traceability/api/web/CorsConfig.java:34`:
  `ALLOWED_METHODS = List.of("GET", "POST", "OPTIONS")`, como fija DD-57.
- Desde el #111 (Tarea 2 de ADR-051) hay dos rutas que un navegador de otro origen **no puede llamar**, porque su
  *preflight* no admite el método:
  - `app/src/main/java/com/traceability/app/web/organization/OrganizationLogoController.java:45`:
    `@PutMapping("/api/v1/organizations/{organizationId}/logo")` (multipart);
  - `app/src/main/java/com/traceability/app/web/organization/OrganizationLogoController.java:56`:
    `@DeleteMapping("/api/v1/organizations/{organizationId}/logo")`.
- No hay más `@PutMapping`, `@DeleteMapping` ni `@PatchMapping` en ningún módulo (`grep` en `*/src/main`).
- `api/src/test/java/com/traceability/api/web/CorsConfigTest.java` **no** prueba la lista exacta de métodos.
  `app/src/test/java/com/traceability/app/web/CorsHttpIntegrationTest.java:81` solo comprueba que `post` está.

## 2. Alcance

Dentro:
- `CorsConfig.ALLOWED_METHODS` pasa a `List.of("GET", "POST", "PUT", "DELETE", "OPTIONS")`.
- Nada más de DD-57 cambia: los mismos orígenes exactos por variable y sin comodines, las mismas cabeceras
  (`Authorization`, `Command-Id`, `Intent-Token`, `Content-Type`), `Location` expuesta, sin credenciales,
  `max-age` 600 s, solo `/api/v1/**` y el filtro antes de la autenticación.

Fuera: `PATCH` (ninguna ruta lo usa) y cualquier cambio de orígenes o cabeceras.

## 3. Cambios (solo con los dos síes)

| Archivo | Cambio |
|---|---|
| `api/.../web/CorsConfig.java` | `ALLOWED_METHODS` con `PUT` y `DELETE`; el Javadoc nombra los métodos y la nota de DD-57 |
| `api/.../web/CorsConfigTest.java` | Test nuevo de la lista exacta (los cinco métodos, en ese orden) |
| `app/.../web/CorsHttpIntegrationTest.java` | *Preflight* `OPTIONS` con `Access-Control-Request-Method: PUT` y otro con `DELETE` sobre `/api/v1/organizations/{id}/logo`, sin JWT: `200` con el origen y el método permitidos. Negativa: con `PATCH`, sin `Access-Control-Allow-Origin` (el navegador lo bloquea) |
| `decisiones-delegadas-2026-10.md` | Nota fechada en DD-57 con la ampliación y la cita del sí de Carlos. **El estado RATIFICADA y el texto de la fila no se reescriben** |
| `referencia-api-v1.md` | Nota fechada en §0 (reglas comunes): CORS admite `PUT` y `DELETE` |
| `estado-fase6.md` | Una sección de la serie de la sesión A (0.39 a 0.49) |

## 4. Tests (rojos primero)

1. `CorsConfigTest`: lista exacta. Rojo: falta `PUT` y `DELETE`.
2. `CorsHttpIntegrationTest`: *preflight* `PUT` y `DELETE` al logo sin JWT → `200`, `Access-Control-Allow-Origin` con
   el origen y `Access-Control-Allow-Methods` con el método. Rojo esperado (sin comprobar todavía, porque no hay código): el `CorsFilter` de Spring rechaza el *preflight* de un método no permitido, sin `Access-Control-Allow-Origin`. El rojo literal irá en el PR de código.
3. Negativa: *preflight* `PATCH` → sin `Access-Control-Allow-Origin`. Ya pasa hoy y debe seguir pasando.
4. Los tests existentes de `CorsConfigTest` y `CorsHttpIntegrationTest`, en verde.

## 5. Mutaciones (`scripts/mutaciones.py`)

| # | Mutación | La mata |
|---|---|---|
| 1 | Sin `DELETE` | 1 y el *preflight* `DELETE` |
| 2 | Sin `PUT` | 1 y el *preflight* `PUT` |
| 3 | Con `PATCH` además | 1 y la negativa |
| 4 | `allowedMethods("*")` | 1 y la negativa |

## 6. Evidencia

Rojo literal; verde por capas (`mvn test -pl api -am` y `mvn test -pl app -am`, cada uno con el módulo completo);
mutaciones en un clon limpio, con las dependencias instaladas con sus tests y la línea base en verde; `free -g` con al
menos 4 GB y `scripts/ci-local.sh` en un clon limpio del commit exacto, con la evidencia en `Documentos/evidencia-ci/`;
autoauditoría contra DD-57 y S-03.

## 7. Puntos para la aprobación

| # | Qué | Quién | Recomendación |
|---|---|---|---|
| **P-CORS-1** | Ampliar DD-57 con `PUT` y `DELETE` | Carlos | Sí: sin ello, la web de otro origen no puede subir ni quitar el logo (#111) |
| **P-CORS-2** | No añadir `PATCH` | Carlos | Sí: ninguna ruta lo usa; lo más restrictivo en lo demás, como dice DD-57 |
| **P-CORS-3** | Este plan | Manuel, por escrito | Aprobar para escribir el código |
| **P-CORS-4** | Nombre del archivo del plan **[Plan]** | Manuel | Aprobar o renombrar |

## 8. Riesgos

- Ninguno nuevo de seguridad: los orígenes siguen siendo exactos y sin credenciales, y `PUT` y `DELETE` siguen
  pidiendo JWT y la autorización de la ruta. El *preflight* no autentica, como hoy con `POST`.
- Mientras no se apruebe, el logo solo se puede gestionar desde el mismo origen o desde clientes que no son navegadores.
