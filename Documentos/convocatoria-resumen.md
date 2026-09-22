# Convocatoria — Resumen de diseño conceptual (no congelado)

**Estado:** Diseño pre-ADR, sujeto a extensión. Esta capa se puede seguir ampliando — este documento no es una decisión final, es un punto de continuidad para retomar el trabajo sin perder lo ya acordado.
**Para qué sirve:** que cualquiera (tú, el equipo, otra sesión) pueda ver de un vistazo qué quedó decidido sobre Convocatoria y qué sigue pendiente, antes de pasar a diseñar la siguiente capa de Fase 6.

---

## 1. Qué es Convocatoria

Una convocatoria es una campaña de recaudación que una `Organization` crea y administra. Las personas se "vinculan" a ella donando dinero — con o sin cuenta. Las donaciones en especie siguen siendo físicas (ya resuelto desde antes, sin cambios).

## 2. Lo que quedó decidido

**Identidad y acceso**
- `publicCode` (acceso a la convocatoria, antes de donar) ≠ `trackingCode` (seguimiento de una donación, después de donar).
- Donante con o sin cuenta — ambos caminos válidos.
- **Descubrimiento público confirmado**: existe un panel donde se listan todas las convocatorias públicas y abiertas, accesible a cualquier usuario. Dos casos de uso distintos:
  - **Descubrimiento** → panel público; solo muestra convocatorias con visibilidad pública **y** estado abierto.
  - **Acceso directo** → `publicCode`/link/QR/otro medio; funciona igual para públicas y privadas-por-enlace (una privada-por-enlace nunca aparece en el panel, pero su link/código sigue resolviéndola).
- El panel no necesita una proyección nueva: al ser `Convocatoria` CRUD, es una consulta filtrada sobre su propio estado (`visibility`, `status`, fechas) — mismo mecanismo que el resto de la vista pública.

**Responsables**
- Nunca puede haber 0 responsables activos en una convocatoria — retirar al último sin reemplazo simultáneo se rechaza (mismo mecanismo que ya protege al `REPRESENTATIVE` único de una `Organization`).
- Responsable puede ser `EMPLOYEE`, o `REPRESENTATIVE` como respaldo cuando no hay `EMPLOYEE` asignado — sin que esto lo convierta en `EMPLOYEE` ni le dé todos sus permisos.
- Respaldo del `REPRESENTATIVE` limitado exactamente a: `REGISTER_PHYSICAL_ASSET` y `SPLIT_PHYSICAL_ASSET`. Los comandos de `Fund` quedan siempre fuera, y `REGISTER_PHYSICAL_ASSET_FROM_DONATION` también (semántica financiera distinta, y de hecho sigue bloqueado para todos los roles hasta que exista `HumanAccount`).

**Asignación empleado↔convocatoria**
- La asigna `ADMINISTRATOR`. No hay autoasignación. Es retirable. Tiene estado y fechas.
- Un empleado solo puede estar asignado a **una** convocatoria activa a la vez (se descartó la participación simultánea en varias — poca necesidad real, complejidad de horarios innecesaria para el MVP).

**Dinero y meta**
- `Fund` es **por donante**, no por convocatoria — una convocatoria tiene muchos `Fund`, uno por cada persona que dona. Esto fue una corrección importante a mitad del diseño.
- `targetAmount` + `targetPolicy`, con tres opciones (no dos): `FLEXIBLE` (se puede superar), `STRICT` (no debe superarse), `CLOSE_ON_TARGET` (al llegar a la meta, la organización elige cerrar, rechazar el excedente, o aceptarlo).
- Meta y política quedan fijas una vez que la convocatoria empieza a recibir dinero — cambiarlas después requiere una operación explícita, no una edición silenciosa.
- **`STRICT` se resuelve mediante una transacción MongoDB compartida real** (decisión revisada — ver nota de corrección al final del documento): `CampaignFundingLedger`, el `FUNDS_CLEARED` de `Fund` y el mensaje de Outbox correspondiente participan en la misma unidad transaccional MongoDB, reutilizando el `MongoTransactionManager` canónico ya provisto por `app` y el patrón `TransactionalEventPublisher`/`@Transactional` ya probado para Event Store + Outbox (Testcontainers real). La coordinación ocurre en un Application Service orquestador en `app`; `convocatoria` mantiene `contracts` como única dependencia directa, sin importar `core`. Los mecanismos de retry permanecen separados y reutilizados: `CommandRetryTemplate` para conflictos de concurrencia de dominio, retry acotado de `TransientTransactionError` para fallos transitorios de Mongo — ningún mecanismo nuevo. El alcance de la transacción se limita a escrituras MongoDB (nunca HTTP, blockchain o IA dentro de ella).

**Visibilidad**
- Pública, o privada-por-enlace (no aparece en listado/búsqueda, pero el link/QR sigue funcionando igual). Control de acceso real con invitación queda fuera del MVP.
- Disponible 24/7 dentro del rango de fechas de la convocatoria.
- **`visibility` (pública/privada), `status` (abierta/cerrada) y ventana temporal (fechas) son tres cosas distintas, no una sola.** "Dentro del rango de fechas" no equivale automáticamente a "abierta": si la política es `CLOSE_ON_TARGET`, alcanzar la meta puede cerrar la convocatoria antes de su fecha de fin. Solo entra al panel público lo que cumple **las tres** condiciones a la vez. La definición exacta de `status` (campo propio vs. derivado de fechas+meta) queda para el review formal, no se fija aquí.

**Cómo se construye técnicamente**
- `Convocatoria` y `CampaignFundingLedger` son CRUD + audit log (como `identity`), no event-sourced — sus reglas son todas de estado actual, no necesitan reconstrucción histórica.
- Viven en un módulo nuevo (`convocatoria`) que depende únicamente de `contracts` — reutiliza lo que ya existe para roles/organización (`IdentityPrincipalPort`), sin importar tipos de `identity` ni de `core`.
- La vista pública (meta, recaudado, estado) se lee directamente de estos dos componentes — no hace falta una proyección nueva; los cuatro read models existentes (`DonationProjection`, etc.) resuelven un problema distinto (por donación individual, no por convocatoria).
- Coordinación con `Fund` vía orquestación externa (mismo patrón ya usado para heredar `organizationRef` de `Fund` a `PhysicalAsset`), no dependencia directa entre módulos.

## 3. Lo que falta / sigue abierto

Con el descubrimiento público confirmado, esto es lo que queda genuinamente pendiente:

- **Definición exacta de `status` (abierta/cerrada)**: si es un campo propio o derivado de fechas + `targetPolicy`, y cómo interactúa `CLOSE_ON_TARGET` con la salida del panel público. Material para el review, no decidido aquí a propósito.
- **Riesgo a llevar al review**: el panel público (`GET /campaigns` o equivalente) y cualquier endpoint de listado necesitan paginación/límite obligatorio — un listado público sin tope es superficie de abuso (mismo riesgo que ya se identificó para otros endpoints públicos del sistema).
- **Redacción formal de la enmienda a ADR-032** (contenido ya decidido — respaldo de `REPRESENTATIVE` — falta el documento normativo).
- **Review formal de Modo de Arquitectura (12 puntos)** de `Convocatoria` + `CampaignFundingLedger` — todo lo anterior es consenso conceptual, no ha pasado por responsabilidad, contratos, errores, concurrencia, persistencia, testing y riesgos.
- **Golden Path de la demo** — transversal a toda Fase 6, no específico de esta capa.

## 4. Cierre de esta ronda

**Convocatoria queda cerrada como diseño conceptual — no como diseño arquitectónico ejecutable.** Distinción explícita, para que nadie lea "cerrado" como "listo para implementar":

```text
Diseño conceptual             CERRADO
Perímetro funcional           CERRADO
Casos de uso públicos         CERRADO
Frontera modular              CERRADA
Naturaleza CRUD               CERRADA
Autorización de respaldo      CERRADA
API concreta                  PENDIENTE (review formal)
Persistencia detallada        PENDIENTE (review formal)
Concurrencia                  PENDIENTE (review formal)
Testing                       PENDIENTE (review formal)
Enmienda ADR-032              PENDIENTE de redacción
Review arquitectónico 12 pts  PENDIENTE
```

**Restricción de arquitectura a llevar al review** (no se diseña ahora, pero debe quedar como requisito desde el origen del endpoint público de listado): límite máximo de resultados, paginación obligatoria, ordenamiento determinista, filtros permitidos explícitos, tamaño de página no controlable arbitrariamente por el cliente, y respuesta que nunca exponga datos privados.

Con esto, Fase 6 puede avanzar a la siguiente capa conceptual (Identidad/`HumanAccount`, Blockchain, o IA) sin esperar a que el Golden Path de la demo esté definido — el Golden Path condiciona el plan de ejecución final, no el diseño conceptual de cada capa.

Este resumen existe para no tener que reconstruir el hilo completo de la conversación cada vez que se retome.

## 5. Nota de corrección posterior al cierre

Después de cerrar esta ronda, se detectó que la justificación original de "STRICT con tolerancia documentada" se apoyaba en una premisa incompleta: se verificó `MongoConfig.java` de `core` (sin `MongoTransactionManager`) pero no la configuración de `app`, el módulo de ensamblaje. `documento-maestro-proyecto.md`/`technical_documentation.md` confirman que `app` ya provee un `MongoTransactionManager` canónico, probado con un smoke test transaccional real que cubre Event Store + Outbox. Eso invalidó la premisa y llevó a reabrir y resolver `STRICT` como transacción compartida real (§2, "Dinero y meta"). El resto de las decisiones de esta capa no se vieron afectadas. Se deja registrado explícitamente, en vez de editar la historia en silencio — coherente con el principio de trazabilidad que rige todo el proyecto.
