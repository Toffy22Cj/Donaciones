# Fase 6 — Estructura de Capas y Perímetro de Convocatoria

**Estado:** Documento de congelación previo a `plan-ejecucion-agentes-fase6.md`.
**Propósito:** Este documento fija (a) la estructura de capas de Fase 6 y (b) el perímetro conceptual de `Convocatoria` como precondición para abrir el Modo de Arquitectura formal (review de 12 puntos). No es un ADR — las decisiones normativas seguirán viviendo en sus ADRs correspondientes (ADR-032 y su enmienda, y los ADRs nuevos que salgan del review). Este documento es el snapshot de lo que está autorizado a diseñarse sin reabrir el alcance.

No sustituye a `documento-maestro-proyecto.md`, `reglas-equipo-y-agentes.md` ni al catálogo de ADRs — los complementa con el estado de esta fase.

---

## 1. Por qué Fase 6 se divide en capas, no en una cola lineal

Fase 6 tiene dos objetivos simultáneos que no comparten la misma secuencia de dependencias:

1. Construir producto real (no una maqueta desconectada).
2. Llegar a una muestra demostrable, con una historia completa de extremo a extremo.

Por eso no se estructura como `Convocatoria → Blockchain → IA → APIs → Frontend → Datos` (secuencia artificial que bloquea trabajo que podría avanzar en paralelo), ni como "todo en paralelo sin orden" (el equipo ya identificó en la reunión del 18 sept 2026 que esto genera bloqueos cruzados y desperdicio de tiempo/recursos limitados).

## 2. Las seis capas de Fase 6

```text
FASE 6 — Producto demostrable

Capa 1 — Convocatoria
    ├── diseño formal (review de 12 puntos)
    ├── implementación
    └── integración financiera con Fund

Capa 2 — Identidad y APIs de acceso
    ├── HumanAccount
    ├── registro / login
    ├── sesión / AuthorizationPrincipal
    └── APIs autenticadas de producto

Capa 3 — Blockchain
    ├── infraestructura real (cuenta, testnet)
    ├── smart contract
    ├── conexión Web3j real (salir de la maqueta)
    └── verificación pública

Capa 4 — IA
    ├── selección de proveedor (no decidido)
    ├── integración Spring AI
    └── generación de resumen end-to-end

Capa 5 — APIs de producto + Frontend
    ├── contratos de API (pueden adelantarse a la implementación)
    ├── flujos públicos
    ├── flujos autenticados
    └── interfaz (Flutter / web)

Capa 6 — Dataset + narrativa de demo
    ├── narrativa (Golden Path) — se define ANTES que los datos
    ├── datos coherentes de principio a fin
    └── escenario(s) demostrables
```

**Importante:** el orden 1→6 es el orden de definición de fronteras, **no** la secuencia de ejecución. Ver mapa de dependencias (§4).

## 3. Capa 1 — Perímetro congelado de Convocatoria

### 3.1 Alcance funcional confirmado (API pública, MVP)

| Caso de uso | Estado |
|---|---|
| Resolver convocatoria por `publicCode` | Confirmado MVP |
| Consultar información pública de convocatoria | Confirmado MVP |
| Donación monetaria, con o sin cuenta | Confirmado MVP |
| Donación en especie | Ya diseñada (ADR-029); integración pendiente, no es alcance nuevo |
| `trackingCode` de una donación | Ya diseñado (ADR-021); integración pendiente |
| Trazabilidad física pública (QR/enlace) | Ya diseñada; integración pendiente |
| Listado/búsqueda pública de convocatorias | **No confirmado** — sin evidencia de requisito; acceso es dirigido (QR/link/publicCode), no por exploración |
| Panel de "eventos y donaciones públicas abiertas" | **No confirmado** — mencionado en reunión, naturaleza exacta sin definir; no asumir que equivale al listado anterior |

### 3.2 Invariantes de dominio

- **Responsables de la convocatoria**: cardinalidad mínima 1 en todo momento. Retirar al último responsable sin reemplazo en la misma operación se rechaza. Mismo mecanismo que `CannotRemoveLastRoleException` (`REPRESENTATIVE`/`Organization`, Fase 4).
- **Responsable puede ser** `EMPLOYEE` o, en su ausencia, `REPRESENTATIVE` como capacidad de respaldo contextual (ver §3.5 — no implica herencia de rol).
- **Asignación empleado↔convocatoria**: la asigna `ADMINISTRATOR`; sin autoasignación; retirable; con estado (`ASSIGNED`/`REMOVED`/...) y fechas. **Un empleado solo puede estar asignado a una convocatoria activa a la vez** (participación simultánea descartada explícitamente por el equipo el 18 sept 2026, por baja necesidad real y complejidad de colisión de horarios).
- **`targetAmount` + `targetPolicy`** (enum de tres valores, no booleano):
  - `FLEXIBLE` — se puede superar la meta.
  - `STRICT` — no debe superarse (ver §3.4 sobre su mecanismo).
  - `CLOSE_ON_TARGET` — al alcanzar la meta, la organización puede cerrar la convocatoria, rechazar la última transacción, o aceptar el excedente (decisión de la reunión del 18 sept).
  - `targetAmount` + `targetPolicy` quedan **inmutables** una vez que la convocatoria empieza a recibir dinero — cambiarlos después implica una operación explícita y auditable, no una edición silenciosa.
- **Visibilidad**: `listedPublicly: boolean` — Nivel 2 confirmado (pública, o privada-por-enlace: no aparece en listado/búsqueda, pero `publicCode`/QR/link siguen funcionando igual). Control de acceso real (Nivel 3, invitación) queda **fuera del MVP**.
- **Disponibilidad**: 24/7 dentro del rango fecha inicio/fin de la convocatoria, sin restricción horaria.

### 3.3 Hallazgo crítico — `Fund` es per-donante, no per-convocatoria

Verificado contra `documento-maestro-proyecto.md` §6.2 (Fase 1-2, cerrado e implementado): el estado de `Fund` incluye `donorRef` — cada `Fund` representa el aporte de **un donante específico**, con su propio lifecycle `PLEDGED → CLEARED`. Por tanto, **una convocatoria tiene muchos `Fund`, uno por donante** — no un `Fund` único acumulador. Esta corrección invalidó una hipótesis de diseño intermedia ("un Fund por convocatoria") y determina el mecanismo de `STRICT` (§3.4).

### 3.4 Mecanismo de `STRICT` — decisión final

- **Verificado con evidencia de código** (`MongoEventStoreAdapter.java`, `MongoConfig.java`): `core` no tiene `MongoTransactionManager` — su concurrencia se resuelve enteramente vía índice único `(streamId, sequence)` capturado como `DuplicateKeyException`. Introducir una transacción MongoDB compartida entre `core` y `Convocatoria` significaría construir infraestructura transaccional nueva en el componente más sensible del sistema (el Event Store), por primera vez, solo para esta regla.
- **Decisión adoptada**: **STRICT con tolerancia documentada**. `CampaignFundingLedger` protege `clearedAmount <= targetAmount` con una escritura condicional atómica de un solo documento (sin transacción cruzada). Existe una ventana breve entre la confirmación de capacidad en el ledger y el registro del evento en `Fund`; un fallo posterior en `Fund` dejaría una inconsistencia contable **reconciliable**, no una violación arbitraria de la meta.
- Esta decisión reemplaza explícitamente la alternativa de saga+compensación (descartada por costo/beneficio) y la de transacción compartida (descartada por el hallazgo de infraestructura anterior).

### 3.5 Enmienda a ADR-032 — capacidad de respaldo de `REPRESENTATIVE`

```
REPRESENTATIVE_BACKUP_COMMANDS = { REGISTER_PHYSICAL_ASSET, SPLIT_PHYSICAL_ASSET }
```

- `REPRESENTATIVE` **no** adquiere el rol `EMPLOYEE` ni permisos financieros.
- La capacidad se ejerce únicamente cuando, para un `campaignRef` determinado, no existe un `EMPLOYEE` responsable activo.
- **Excluidos explícitamente**: todos los comandos de `Fund` (siguen siendo `{ADMINISTRATOR}` exclusivamente), y `REGISTER_PHYSICAL_ASSET_FROM_DONATION` (semántica financiera distinta, y además bloqueado de implementación en su totalidad hasta que exista `HumanAccount`, ADR-031 — no ejecutable por ningún rol todavía).
- Se implementa como una **tercera política**, separada de `RoleAuthorizationPolicy` (que debe seguir siendo un `switch` exhaustivo sin excepciones internas) y de `OrganizationBoundaryPolicy`.
- La condición se evalúa al inicio de la operación; **no hay garantía de revocación retroactiva** si un `EMPLOYEE` se asigna mientras una operación de respaldo está en curso.

### 3.6 Vista pública — sin nuevo read model

Los cuatro read models existentes (`DonationProjection`, `AssetHistoryProjection`, `asset_index`, `DonationAuditFacts` — ADR-015) están diseñados **por donación/activo individual**, no agregan por `campaignRef`. No sirven para la vista pública de convocatoria, y **no se justifica un quinto componente**: los dos datos necesarios ya tienen dueño natural sin pasar por proyección de eventos:

- Metadata de la convocatoria (título, `targetAmount`, `targetPolicy`, visibilidad, estado) → se lee directamente de `Convocatoria`.
- Monto recaudado → se lee directamente de `CampaignFundingLedger.clearedAmount`.

Esto solo es válido bajo la decisión de §3.7 (CRUD, no event-sourced).

### 3.7 Naturaleza de los componentes

```text
Convocatoria             → CRUD transaccional + audit log append-only
CampaignFundingLedger    → CRUD transaccional + audit log append-only
Fund                     → Event Sourcing (sin cambios, Fase 1-2)
```

Justificación: todos los invariantes de `Convocatoria` y `CampaignFundingLedger` son de **estado actual**, no requieren reconstrucción histórica por replay. Mismo patrón que `identity` (Fase 4), justificado por tipo de invariante — no por analogía superficial. Precedente directo: `CannotRemoveLastRoleException` ya resuelve "cardinalidad mínima protegida" sin Event Sourcing.

### 3.8 Módulo y dependencias

```text
convocatoria → contracts   (única dependencia directa)
```

- **`organizationRef`**: referencia opaca (Value Object), sin importar tipos de `identity`.
- **Roles/autorización**: reutiliza `IdentityPrincipalPort` + `AuthorizationPrincipal` (ya existentes en `contracts`, verificado en `estado-fase5.md` §9.1) — cero contrato nuevo necesario.
- **Referencia a `Fund`**: vía `campaignRef`/correlación, sin importar el Aggregate.
- **Coordinación `CampaignFundingLedger ↔ Fund`**: orquestación externa (mismo patrón que `AssetRegisteredSagaPolicy`, ADR-029 Camino A), sin dependencia directa `convocatoria → core`.

### 3.9 Fuera de alcance (Fase 6, Capa 1)

- Comandos de `Fund` para `REPRESENTATIVE`.
- `REGISTER_PHYSICAL_ASSET_FROM_DONATION` para `REPRESENTATIVE`.
- Listado/búsqueda pública de convocatorias (pendiente de confirmación).
- Panel de eventos/donaciones públicas abiertas (pendiente de definición).
- Visibilidad Nivel 3 (control de acceso con invitación).
- Participación simultánea de un empleado en varias convocatorias.
- Quinto read model de convocatoria.
- Transacción MongoDB compartida `core`↔`convocatoria`.

### 3.10 Dependencias con decisiones previas (para trazabilidad del review)

- `IdentityPrincipalPort` / `AuthorizationPrincipal` (`contracts`, Fase 5).
- `OrganizationBoundaryPolicy`, `RoleAuthorizationPolicy` (`core.application.authorization`, ADR-032).
- `AssetRegisteredSagaPolicy` (precedente de orquestación intermodular, ADR-029).
- `CannotRemoveLastRoleException` (precedente de cardinalidad mínima, ADR-026).
- ADR-016 (enmendado), ADR-021, ADR-029, ADR-032 (enmienda pendiente de redacción formal).

---

## 4. Mapa de dependencias entre capas

| Capa | Bloquea | Puede avanzar en paralelo con |
|---|---|---|
| 1 — Convocatoria | Contratos de producto para Capa 5 | Blockchain (3), IA (4) |
| 2 — Identity/APIs (`HumanAccount`) | Flujos autenticados (donante con cuenta, gestión de organización/empleados) | Blockchain (3), IA (4), frontend público (5) |
| 3 — Blockchain | Parte de anclaje/verificación de la demo | Convocatoria (1), Auth (2), IA (4) |
| 4 — IA | Parte de resumen narrativo de la demo | Convocatoria (1), Auth (2), Blockchain (3) |
| 5 — APIs de producto + Frontend | Integración visual de la demo | Todo lo que ya tenga contrato fijado (no requiere backend terminado) |
| 6 — Dataset + narrativa | Demo final | Requiere 1 funcionando + narrativa definida; va al final |

**Cuello de botella real**: `HumanAccount` (Capa 2) es la pieza que más otras piezas bloquea — flujo de donante con cuenta, gestión de organización/empleados en frontend, y `REGISTER_PHYSICAL_ASSET_FROM_DONATION`.

## 5. Pendiente antes de `plan-ejecucion-agentes-fase6.md`

1. **Golden Path de la demo** — la historia completa de principio a fin que la muestra debe poder ejecutar. Sin definir todavía; condiciona qué es indispensable en cada capa y qué es secundario. Ejemplo ilustrativo (no congelado):

   ```text
   1. Organización autenticada crea convocatoria
   2. Convocatoria queda publicada (pública o privada-por-enlace)
   3. Persona entra vía QR/link, consulta información pública
   4. Dona dinero (con o sin cuenta)
   5. Ledger actualiza recaudado; Fund registra el evento
   6. Se consulta trackingCode
   7. Se registra/consulta activo físico, trazabilidad
   8. Evento/hash queda anclado en blockchain
   9. IA genera resumen de auditoría
   ```

2. **Review formal de 12 puntos** de `Convocatoria` + `CampaignFundingLedger` (Modo de Arquitectura), sobre el perímetro de §3.
3. **Regla de adelanto de fases**: una funcionalidad de fase futura puede adelantarse a Fase 6 si es necesaria para el producto demostrable, pero el adelanto debe registrarse explícitamente y conservar sus límites originales (mismo tratamiento ya dado a la API pública, Fase 7 → MVP).

---

*Documento generado a partir de la sesión de diseño conceptual de Convocatoria y estructura de Fase 6. Toda cita a código o ADR fue verificada contra los archivos del proyecto disponibles en el momento de su redacción.*
