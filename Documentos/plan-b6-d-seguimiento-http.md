# Plan B6-d — Seguimiento de punta a punta con el `trackingCode` real

**Estado:** **HECHO** (2026-10-07, `feat/b6-d-seguimiento-http`; evidencia `evidencia-fase6/b6-d-seguimiento-http-ba6d1f1-2026-10-07.txt`, `estado-fase6.md` §0.15). Ejecutado bajo la autorización de trabajo autónomo de Carlos (2026-10-07), que exceptúa temporalmente las reglas 1 y 3.4. El plan es una `[DECISIÓN DELEGADA — pendiente de ratificar por Carlos]` (DD-28); las decisiones concretas son DD-26 y DD-27 de `decisiones-delegadas-2026-10.md`.
**Origen:** `propuesta-d-api.md` §3 (B6-d: "seguimiento de Fase 3 integrado con el `trackingCode` real de B6-b, de punta a punta; narrativa de convocatoria cuando exista B5"); `plan-b6-0-base-http.md` Q-B60-1 (Carlos: "el 404 del seguimiento (Fase 3) se corrige en B6-d"); `propuesta-apis-fase6.md` TR-D1.
**Fuera de alcance:** la narrativa de convocatoria. **B5 no está listo** (ADR-040 C2–C5 y C8 siguen abiertos), y la instrucción de Carlos es incluirla "solo si B5 está listo".

---

## 1. Cambios

1. **TR-D1:** con un `trackingCode` válido y sin proyección, TR-01, TR-02 y TR-03 respondían 404 **sin cuerpo**. La ficha congelada pide `ProblemDetail`. Ahora lanzan `TrackedResourceNotFoundException` y el manejador único de B6-0 responde 404 con el cuerpo fijo (DD-27). El 401 de los filtros de seguimiento no cambia.
2. **H-B6D-2, hallazgo:** `AssetHistoryMapper` pasaba la **referencia del custodio** a `mapCustodian`, que espera un **estado del ciclo de vida**. Con datos reales, el historial público de un activo (TR-03) **nunca funcionó**: daba 500, y desde B6-0, 400 (el `IllegalArgumentException` se traduce a 400). El test unitario no lo detectaba porque su *fixture* tenía los campos intercambiados respecto a lo que escribe la proyección. Ahora la categoría sale del estado de la transición. Las transiciones que no son un estado del ciclo de vida (`SPLIT`, `SPLIT_COMPENSATED`, `CUSTODY_TRANSFERRED`) llevan `UNCATEGORIZED` (DD-26). La referencia del custodio sigue sin publicarse.
3. **Recorrido de la demo por HTTP** (`GoldenPathHttpIntegrationTest`), contra Tomcat real con todos los módulos reales:
   - verificar la organización;
   - CV-01 y CV-02;
   - CV-07, y CV-11 con y sin cuenta;
   - webhook simulado, `trackingCode` y `/account/donations`;
   - registro del Camino A, división y espera del hijo;
   - dispatch, receive y deliver del padre y del hijo;
   - seguimiento público con el `trackingCode` real (logística `DELIVERED` de los dos) y el historial público del activo.

   Cubre los criterios 1–9 y 15–17 de `golden-path.md` §8.

## 2. Hallazgos para Carlos

- **H-B6D-1:** el empleado necesita el `fundId` para registrar un activo del Camino A, y **ninguna ruta lo da**: CV-11 y `/account/donations` lo ocultan a propósito, porque es un id interno. El test lo lee de la base de datos. Hay que decidir cómo se elige el `Fund` en la web: un listado administrativo de fondos o de intenciones de la convocatoria, o registrar por intención.
- **H-B6C-1 (de B6-c):** la asignación previa del Camino A (`requestAllocation`) tampoco tiene ruta. El test la crea por servicio.
- **H-B6D-2:** corregido aquí (§1.2). Es el mismo patrón que H-PROJ: una pieza de Fase 3 que solo se había probado con datos construidos a mano.
