# ADR-055 — Escaneo de dependencias y SBOM (T-07)

## Status

**PROPUESTO — redactado según el plan aprobado; falta la firma del texto.** El usuario aprobó el plan `plan-evolucion-producto.md` el 2026-11-14. Su §2.6 fija:

- OWASP Dependency-Check (o equivalente) y CycloneDX en un perfil `-Psecurity` que llama `ci-local.sh`;
- `pnpm audit` en la web y `flutter pub outdated` en el móvil;
- fallo con CVSS ≥ 7 salvo excepción justificada y con caducidad en `Documentos/seguridad/excepciones-dependencias.md`.

El plan pide «registrar la decisión de herramienta»; es este ADR.

Por la regla 3.5 cubre dos herramientas nuevas de construcción, no de ejecución: `dependency-check-maven` 13.0.0 (Maven Central, 2026-08-03) y `cyclonedx-maven-plugin` 2.9.3 (2026-07-30). Las dos tienen más de dos semanas, y solo existen dentro del perfil `security`.

## Decision

- **D1 — Backend.** El perfil `security` del `pom.xml` padre:
  - genera el **SBOM** CycloneDX 1.6 agregado del reactor (`target/bom.json`, sin dependencias de test);
  - ejecuta **OWASP Dependency-Check** (`aggregate`) con `failBuildOnCVSS=7`, la clave `NVD_API_KEY` por entorno y las supresiones de `Documentos/seguridad/dependency-check-supresiones.xml`. Los analizadores de JavaScript, .NET y OSS Index (que exige credenciales propias) están apagados.
  - **Uso:** `NVD_API_KEY=… mvn -B -Psecurity -DskipTests verify`.
- **D2 — Excepciones con caducidad.**
  - Cada `<suppress>` lleva `until` (Dependency-Check deja de aplicarla al vencer) y cita una entrada `EXC-NNN` de `excepciones-dependencias.md` con su dueño, su motivo y lo que la cierra.
  - `scripts/seguridad/comprobar-supresiones.py` rechaza una supresión sin `until`, caducada, sin `EXC-NNN` o con una `EXC-NNN` que no está registrada.
- **D3 — `ci-local.sh`.**
  - Ejecuta siempre la comprobación de supresiones.
  - Ejecuta el perfil `security` cuando hay `NVD_API_KEY`. Sin ella, la evidencia dice **«OMITIDO: falta NVD_API_KEY»**, nunca verde.
  - Un fallo de cualquiera de las dos cosas da un código de salida distinto de 0.
- **D4 — Web.** `pnpm audit --audit-level high` es un paso nuevo, «Dependency audit», en `.github/workflows/ci.yml` y en `scripts/ci-local.sh` de `PaxFide`, en el mismo orden en los dos (regla del propio script).
- **D5 — Móvil.** `flutter pub outdated` en el `ci-local` del móvil. **Pendiente:** este entorno no tiene Flutter. Además, `pub outdated` informa de versiones nuevas, no de vulnerabilidades. El equivalente con umbral es `osv-scanner` sobre `pubspec.lock`, que se decide al implementarlo.

## Alternatives

| Opción | Veredicto |
|---|---|
| OSV-Scanner sobre el SBOM | Sin clave de NVD y con datos de GitHub. Es un binario externo, no un plugin de Maven. Queda como alternativa si NVD falla a menudo |
| Sonatype OSS Index | Exige cuenta y token desde 2025 |
| Dependabot o Renovate | Necesitan GitHub Actions o la app, que no están disponibles (cuenta bloqueada) |

## Consequences

- **Primer informe (2026-11-14):**
  - **Web:** el primer `pnpm audit` dio **2 críticas y 2 altas en producción**: GHSA-2464-fhr4-w9xw y GHSA-673q-mjm6-r5xr (ejecución remota de código sin autenticar en `next` ≤ 16.4.0), GHSA-p3m5-59mp-jv5r (DoS, `next`) y GHSA-68fv-2mgg-jv7q (`source-map-js` < 1.2.2). Se corrigió en `PaxFide` (Next.js 16.3.8 → 16.4.3 y *overrides*). Después: 0 en producción y, en todo el árbol, 2 moderadas.
  - **Backend:** el SBOM tiene 167 componentes. **El escaneo de NVD no se pudo ejecutar en el entorno de esta sesión:** su red bloquea `services.nvd.nist.gov`, `cisa.gov`, `api.osv.dev` y `ossindex.sonatype.org`. Dependency-Check falla cerrado («No documents exist»). Hay que ejecutarlo en el servidor o en un equipo con salida a NVD y con `NVD_API_KEY`.
- **Prueba de que falla con una dependencia vulnerable:** en la web, sobre el commit anterior a la corrección, `pnpm audit --audit-level high` sale con 1, y con 0 después. En el backend queda pendiente hasta tener acceso a NVD: rama de prueba con una dependencia vulnerable a propósito, comprobar que el escaneo falla y descartar la rama.
- Evidencia literal: `Documentos/evidencia-ci/t07-escaneo-2026-11-14.txt`.
