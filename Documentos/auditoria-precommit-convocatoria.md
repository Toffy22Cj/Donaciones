# Auditoría pre-commit — Convocatoria (Fase 6)

**Fecha:** 2026-10-03. **Naturaleza:** informe de preparación Git del bloque Convocatoria. Es evidencia; no es documentación normativa.

**Destino:** rama `feat/fase6-convocatoria`, para un Pull Request hacia `develop`. **No se hizo merge.**

## 1. Estado inicial de Git

- **Rama:** `develop`, HEAD `673eda9` (merge del PR #28). Sin commits locales.
- **Árbol de trabajo sucio:** 8 archivos modificados y 25 sin seguimiento, mezclando Convocatoria y otros bloques. La clasificación está en §4–§6.
- **`git fetch origin`:** OK. Ramas remotas nuevas de compañeros: `feat/identity-adr-038`, `feat/contracts-campaign-auditfacts-port`.

## 2. Estado de `develop` antes de crear la feature

- `develop...origin/develop` → `0 0`: ni divergente ni atrasado.
- `git pull --ff-only origin develop` → "Ya está actualizado".
- **El árbol no estaba limpio.** Se detuvo el proceso y se pidió decisión humana. Respuesta: crear la feature desde el `develop` actual, llevando el árbol sin tocarlo, y añadir solo archivos de Convocatoria, uno a uno.

## 3. Feature

- **Comando:** `git switch -c feat/fase6-convocatoria`, desde `673eda92fcb18626788804a3a48b41730e6784fb` (igual a `origin/develop`).
- No se usó `git add .` ni `git add -A`.

## 4. Archivos incluidos

| Commit | Archivos |
|---|---|
| `81cf87c` feat | `pom.xml` (raíz) y `convocatoria/`: su `pom.xml`, 114 archivos de `src/main` y 32 de `src/test` (148 archivos) |
| `894b81e` docs | `ADR-037-convocatoria-ledger-assignment-donationintent.md`, `ADR-037-enmienda-1-convocatoria.md`, `ADR-037-enmienda-2-convocatoria.md`, `convocatoria-resumen.md`, `implementation_plan.md`, `handoff-convocatoria.md`, `estado-fase6.md` y `fase-6-estructura-y-perimetro-convocatoria.md` |
| `1f3ed5d` docs | Las 14 auditorías del bloque. Del cierre de fondos: `auditoria-c01-idempotencia-fondos.md`, `auditoria-c01-postcorreccion.md`, `auditoria-cierre-f1-f2.md`, `auditoria-flujo-confirmed-fondos-v3.md`, `debate-cierre-confirmed-fondos.md`, `auditoria-preimplementacion-cierre-convocatoria.md`, `auditoria-delimitacion-cierre-convocatoria.md`, `auditoria-cierre-final-convocatoria.md`. Del bloque: `auditoria-herencia-fase5-convocatoria.md`, `auditoria-documental-convocatoria.md`, `auditoria-porcentaje-convocatoria-fase6.md`. De la consolidación: `auditoria-inventario-documental-convocatoria.md`, `auditoria-plan-consolidacion-documental-convocatoria.md`, `auditoria-ejecucion-consolidacion-convocatoria.md` |
| (este commit) docs | `auditoria-precommit-convocatoria.md` |

**Diffs de los dos documentos compartidos:**
- `estado-fase6.md` solo toca Convocatoria: la nota de §1, la fila de Convocatoria de §2 con su nota, §3bis y la viñeta de §5.
- `fase-6-estructura-y-perimetro-convocatoria.md` solo añade notas en §3.

## 5. Archivos excluidos (siguen sin commit en el árbol de trabajo, sin modificar)

| Archivo | Motivo |
|---|---|
| `ADR-043-recuperacion-aplicacion-fondos-convocatoria.md` | Otro bloque (`convocatoria-resumen.md` §6.21) |
| `plan-api-fase6.md`, `revision-ready-api-fase6.md`, `auditoria-api-convocatoria.md`, `-v2.md`, `-v3.md` | Capa API (Capa 5) |
| `golden-path.md`, `api-contract-matrix.md` | Capas 6 y 5. Llevan cambios previos con el flujo de la Enmienda 2: excluidos por decisión humana, para revisión de sus dueños (DH-C-7) |
| `Promt-maestro.md` | No es de Convocatoria (solo cambia el salto de línea final) |

## 6. Archivos de otros bloques detectados

Los de §5. Ninguno se modificó, se añadió al commit ni se borró.

## 7. Tratamiento de `pom.xml`

- **Cambio:** solo `+ <module>convocatoria</module>`.
- **Por qué:** registra el módulo en el reactor Maven. Sin esa línea, `convocatoria` no forma parte del build ni de `mvn -pl convocatoria` desde la raíz.
- **Decisión humana:** incluirlo. Va en el commit `81cf87c`, junto al módulo.

## 8. Estado de la documentación

**LISTO CON OBSERVACIONES** (verificación final del 2026-10-03):
- ADR-037 es la normativa consolidada, con la Enmienda 1 integrada. La Enmienda 2 (BORRADOR) no aparece como norma.
- ADR-043 aparece solo como dependencia externa.
- Las enmiendas y las auditorías están intactas.
- No se ha borrado, movido ni renombrado ningún documento.
- Las contradicciones abiertas están marcadas `DECISIÓN HUMANA PENDIENTE` (§15).

## 9. Estado del código

Verificación de solo lectura de las decisiones cerradas:
- **Estados:** `PENDING`, `CONFIRMED`, `FAILED`, `EXPIRED_UNKNOWN`, `FUNDING_REJECTED`. No hay `APPLIED`.
- **Aplicación de fondos:**
  - solo desde `CONFIRMED` (`DonationIntentNotConfirmedException` en otro caso);
  - barrera: reclamo del comando de sistema `APPLY_FUNDS` antes del ledger;
  - la intención aplicada sigue `CONFIRMED`.
- **`FUNDING_REJECTED`:** solo con capacidad insuficiente verificada como permanente. `CLOSE_ON_TARGET + CLOSE` no es permanente: R4 sigue pendiente.
- **Rutas de ejecución:**
  - con transacción externa activa: sin reintento interno;
  - sin ella: `ConvocatoriaTransactionRetryHelper`, con 6 intentos, espera de 10–50 ms ×2^(n-1) y tope de 500 ms; reintenta `TransientTransactionError` y `CommandClaimCollisionException`.
- **Claves de sistema:** `_id = {commandType, commandId}`, separadas de las claves string de cliente.

No se modificó código ni tests.

## 10. Resultado de los tests

`mvn -o -q test -pl convocatoria`, ejecutado en `feat/fase6-convocatoria` antes de los commits:
- código de salida 0 (**BUILD SUCCESS**);
- suma de los 20 informes de Surefire de esa ejecución: `Tests run: 194, Failures: 0, Errors: 0, Skipped: 0`.

Los avisos `WriteConflict` del log son los reintentos esperados de los tests de concurrencia.

## 11. Commits realizados

```
81cf87c feat(convocatoria): add convocatoria module (phase 6 first cut)
894b81e docs(convocatoria): consolidate phase 6 documentation
1f3ed5d docs(convocatoria): add audit evidence for phase 6
```

Más el commit `docs(convocatoria)` de este informe.

## 12. Rama publicada

`feat/fase6-convocatoria`, con seguimiento de `origin/feat/fase6-convocatoria`.

## 13. Destino exacto del push

- **Comando:** `git push -u origin feat/fase6-convocatoria`. Remoto: `https://github.com/Toffy22Cj/Donaciones.git`.
- **Resultado:** `[new branch] feat/fase6-convocatoria -> feat/fase6-convocatoria`.
- **No se hizo:** push a `master` ni a `develop`, `--force`, rebase ni reset.

## 14. Datos del PR

- **base:** `develop`
- **compare:** `feat/fase6-convocatoria`
- **URL para crearlo:** `https://github.com/Toffy22Cj/Donaciones/pull/new/feat/fase6-convocatoria`
- **Título propuesto:** `Fase 6 — Convocatoria: módulo, documentación consolidada y evidencia`
- **Aviso para el revisor:**
  - El código implementa el flujo de aplicación de fondos (`APPLY_FUNDS`, `FUNDING_REJECTED`) de las decisiones humanas del 2026-10-02 (`convocatoria-resumen.md` §6.20).
  - Su texto normativo, la Enmienda 2 de ADR-037, sigue en **BORRADOR**, y la regla 3.5 de `reglas-equipo-y-agentes.md` exige un ADR aprobado antes de fusionar un mecanismo nuevo de reintento o recuperación.
  - Recomendación: aprobar la Enmienda 2 (DH-C-1) antes del merge.
  - `app` aún no depende de `convocatoria` (B-1): el módulo no cambia el arranque de la aplicación.

## 15. Pendientes por decisión humana

Ver `convocatoria-resumen.md` §0.4–§0.5.
1. **DH-C-1:** aprobar la Enmienda 2. Su §9 incluye la aprobación de ADR-043, que es de otro bloque. Resolvería CD-03, la barrera contra la doble aplicación y la semántica de `CONFIRMED`.
2. **DH-C-3:** numeración de los ADR (CD-02).
3. **DH-C-4:** G1–G3 (CD-10).
4. **DH-C-5:** aprobación de la revisión 3 del plan (CD-07).
5. **DH-C-7:** cambios sin commit en `golden-path.md` y `api-contract-matrix.md`, que deben revisar sus dueños.
6. **DH-C-8:** grafía `EXPIRED_UNKNOWN` (CD-13).
7. **Dueño de ADR-043:** qué bloque es y quién lo aprueba.
8. **Contrato HTTP de Convocatoria:** no está cerrado (`handoff-convocatoria.md` §8–§9). Pertenece a la capa API.

## 16. Confirmación

**No se hizo merge** ni se abrió el PR automáticamente. El merge lo hará el responsable humano.
