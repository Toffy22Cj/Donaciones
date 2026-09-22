# ADR-037 (número tentativo — confirmar contra el catálogo real antes de commitear) — APIs de producto y Frontend

**Estado:** Aprobado — arquitectura de la capa cerrada. No bloquea trabajo paralelo, pero varios endpoints concretos no son asignables hasta que sus contratos de dominio heredados (ADR-033/034/036) se resuelvan.
**Fecha:** Sesión de Fase 6, review formal de 12 puntos (Modo de Arquitectura), posterior a ADR-033/034/035/036.
**Complementa:** `api-contract-matrix.md`, `golden-path.md`. No reabre ningún ADR de dominio — su función es traducir, mapear y transportar, nunca decidir.

---

## 1. Contexto

Con las cuatro capas de dominio de Fase 6 revisadas (Convocatoria, Identidad, Blockchain, IA), esta capa agrupa los contratos HTTP y el Frontend que los consume. A diferencia de las capas anteriores, `api` no introduce invariantes de negocio propias — su responsabilidad es traducir HTTP↔dominio sin fabricar decisiones que el dominio no ha tomado.

## 2. Decisión

### 2.1 Responsabilidad y límite fundamental de la capa

`api` es dueño de HTTP; controllers sin lógica de negocio; escrituras delegan en Application/Command Services existentes; lecturas usan `ReadPort`/`ReadModel`, nunca documentos Mongo crudos (`api-contract-matrix.md`, reglas 1-3, ya cerradas, no reabiertas).

**Precisión central de este ADR**: "DISEÑO CERRADO" en `api-contract-matrix.md` significa forma HTTP fijada (ruta, auth, forma de respuesta) — **no** significa que el Application Service subyacente esté verificado. Esta capa traduce "contrato HTTP cerrado" en "qué huecos de las capas de dominio afectan a ese endpoint", sin volver a decidir el dominio.

### 2.2 Invariantes de frontera (ReadModel, autenticación)

- **El `ReadModel` es una frontera, no un espejo de Mongo**: exclusión explícita > información disponible en la fuente. `PhysicalAssetOperationalReadModel` excluye `donorRef`/datos financieros/`parentAssetRef`/`rootAssetRef` aunque la proyección subyacente los conozca, y **la autenticación no amplía ese perímetro** — un usuario autenticado y autorizado sigue sin recibir esos campos.
- **Frontend no reconstruye datos excluidos** desde otra respuesta ni los trata como "ocultos en esta pantalla" — el `ReadModel` los excluye estructuralmente.
- **Público vs. administrativo mediante `ReadModel` separados** (`ConvocatoriaReadModel` vs. `ConvocatoriaAdminReadModel`) — no convergen en un DTO único.
- **Tres mecanismos de autenticación, no intercambiables**: JWT (operaciones autenticadas de plataforma/organización), tracking credential HMAC (seguimiento individual de donación), firma de webhook (servidor-servidor). Un endpoint no elige el mecanismo por conveniencia — usa exactamente el definido en su contrato. Añadir JWT a un endpoint de tracking no es "más seguro", es cambiar la semántica del contrato.

### 2.3 Mapeo endpoint → hueco de dominio heredado (primera consolidación explícita)

| Endpoint | Estado en matriz | Hueco heredado |
|---|---|---|
| `POST /organizations/{id}/campaigns` | DISEÑO CERRADO | Firma de `CreateConvocatoria` no verificada (ADR-033 §7) |
| `POST /campaigns/{campaignRef}/employees` | DISEÑO CERRADO | Firma de `AssignEmployee` no verificada; D2 (autoasignación) sin resolver |
| `POST /webhooks/payments` | CONTRATO CONCEPTUAL | **Idempotencia de `clearFundsGenesis` no verificada (ADR-033 §7) — mayor severidad de todo el mapeo** |
| `POST /platform/organizations/{id}/verify` | DISEÑO CERRADO | `VERIFY` sobre `Organization` ya `VERIFIED` sin definir (ADR-034 §7, ítem 4) |
| `POST /platform/administrators` / `DELETE .../{accountId}` | DISEÑO CERRADO | `GRANT`/`REVOKE` repetidos sin resolver; mecanismo de concurrencia de `PlatformAuthorityState` no verificado (ADR-034 §7) |
| `POST /auth/login` | CONTRATO CERRADO | Fallo de `TokenIssuerPort.issue()` no documentado (ADR-034 §7) |
| `GET /public/campaigns/{publicCode}/narrative` | CONTRATO DEFINIDO | **Contradicción `AuditFactsPort`/`CampaignAuditFactsPort` sin resolver (ADR-036, C1)** |
| Los cinco endpoints de `PhysicalAsset` | CONTRATO DEFINIDO / bloqueado | `HumanAccount` + integración P7 (`golden-path.md` §5, no reabierto aquí) |

Ningún endpoint de esta tabla requiere una decisión nueva de esta capa — todos heredan bloqueos ya identificados en ADRs anteriores.

### 2.4 Estados — tres capas separadas, ninguna sustituye a otra

Dominio (`DonationIntent.status`, `Convocatoria.status`, `PhysicalAsset.lifecycleStatus`) → API/`ReadModel` → Frontend (estado visual derivado + estado transitorio de interacción). El frontend puede tener máquina de estados local de UX (`idle→loading→displaying/error`), pero nunca persiste como dominio, nunca autoriza comandos, nunca sustituye un estado recibido del backend. En particular: `EXPIRED-UNKNOWN` no debe colapsarse en `FAILED` en la UI — el nombre mismo distingue "sabemos que falló" de "no sabemos qué pasó", y un timeout de red del lado del cliente no constituye una transición de dominio.

### 2.5 Errores — traducción, no invención

`api` traduce excepciones ya nombradas por el dominio a HTTP; no decide la excepción ni completa un contrato de error para una excepción que el dominio no ha nombrado todavía. Propuesta de mapeo para lo ya nombrado en Convocatoria (404 para "no encontrado", 409 para conflictos de invariante de estado — `CampaignClosedException`, `CampaignFundingLimitExceededException`, `LastResponsibleRemovalWithoutReplacementException`, `EmployeeAlreadyAssignedException`) — **propuesta de este review, no confirmada por ninguna fuente**. `authenticate()`: los tres casos indistinguibles (email inexistente, password incorrecto, cuenta `INACTIVE`) deben producir **código y cuerpo de respuesta HTTP idénticos** — invariante de dominio proyectada directamente a esta capa, no negociable.

### 2.6 Concurrencia — sin autoridad propia

`api` no implementa locks de dominio ni hace "check-then-act" para proteger invariantes — la protección vive donde vive el estado (Application Service + persistencia). `409` describe el resultado, no prescribe una política de retry del frontend — cada conflicto puede significar algo distinto (recurso ya asignado, campaña cerrada, límite alcanzado) y reintentar ciegamente puede repetir el mismo rechazo o, si el comando no es idempotente, ejecutar otra operación. No se introduce deduplicación HTTP transversal — crearía una segunda semántica de idempotencia compitiendo con la del comando. Doble clic en frontend: protección de UX (deshabilitar botón), **no sustituye idempotencia backend**.

### 2.7 Seguridad — `publicCode`/`trackingCode` como "bearer-like secrets"

Ambos conceden acceso a quien los conoce, sin ser criptográficamente equivalentes a un JWT o firma HMAC. El frontend debe tratarlos como datos sensibles de acceso: no en logs, no en analítica innecesaria, no en URLs externas, no en mensajes de error. Autenticación y perímetro de datos (§2.2) son **controles independientes** — pasar la autenticación no amplía lo que el `ReadModel` expone.

### 2.8 Dependencias — hallazgo nuevo específico de esta capa

`api → contracts` + Application Services de cada módulo, nunca Mongo ni implementaciones internas — coherente con las cuatro capas de dominio, sin contradicciones. **Riesgo nuevo, no reducible a los de capas anteriores**: `api` podría absorber orquestación cross-módulo que pertenece a `app` si un controller decide coordinar directamente la secuencia entre módulos distintos (ej. "primero llamo a Identity, luego si tiene éxito llamo a Convocatoria") en vez de delegar en el orquestador ya diseñado. Cualquier caso de uso que invoque más de un módulo de dominio debe pasar por `app`, nunca coordinarse en el controller.

### 2.9 Observabilidad — ocho identificadores, ninguno sustituye a otro

`correlationId`, `commandId`, `intentId`, `providerEventId`, `merkleBatchId`, `transactionHash`, `eventId`, `streamId+sequence` — cada uno responde una pregunta distinta; la trazabilidad los **relaciona**, no los fusiona. `api` es el punto natural de entrada HTTP para `correlationId`, pero no todos los procesos nacen en HTTP (schedulers, webhooks) y no debe asumirse que heredan automáticamente ese contexto.

## 3. Consecuencias

- Positivas: primera consolidación explícita de qué endpoints heredan qué huecos de dominio — antes disperso en cuatro ADRs distintos, ahora mapeado en un solo lugar (§2.3); identifica un riesgo de frontera específico de esta capa (orquestación filtrándose al controller) que ninguna capa de dominio podía detectar por sí sola.
- Negativas / deuda aceptada: varios endpoints no son asignables a un agente hasta que se resuelvan sus huecos heredados — esta capa no puede acelerar esa resolución, solo señalarla.

## 4. Alternativas descartadas

- **Frontend recalculando estados de dominio o decidiendo autorización a partir del `ReadModel`**: descartada — el backend sigue siendo la única autoridad; el frontend solo representa.
- **Timeout de cliente convertido en `FAILED` de `DonationIntent`**: descartada — colapsaría `EXPIRED-UNKNOWN` (el sistema no sabe qué pasó) en `FAILED` (el sistema sabe que falló), destruyendo información real del dominio.
- **Retry automático ante cualquier 409**: descartada — un conflicto de invariante no es un error transitorio de red.
- **Deduplicación HTTP transversal en `api`**: descartada — introduciría una segunda semántica de idempotencia sin contrato que la respalde, compitiendo con la del comando específico.
- **JWT sustituyendo o complementando tracking credential**: descartada — cambia la semántica de quién puede consultar y qué reglas de autorización aplican, aunque "funcione" técnicamente.
- **Asumir códigos HTTP para excepciones de dominio no nombradas todavía** (`GRANT`/`REVOKE` repetidos, comandos de verificación sobre estados terminales): descartada — presupondría la decisión de dominio que sigue abierta.

## 5. Autorización

Tres mecanismos, sin mezcla: JWT, tracking credential HMAC, firma de webhook — cada endpoint usa exactamente el de su contrato. Webhook nunca es mecanismo de frontend — la firma del proveedor es autenticación servidor-servidor exclusiva de la integración de pago.

## 6. Testing

Pruebas críticas de seguridad: exclusión de campos del `ReadModel` verificada aunque el solicitante esté autorizado; indistinguibilidad byte-a-byte de `authenticate()`; separación real de mecanismos de autenticación (JWT no debe colar como sustituto de tracking credential). Pruebas de descubrimiento de contrato (no normativas) para los huecos heredados de §2.3. Cuatro tests de `correlationId` de extremo a extremo, incluyendo el caso de salto a proceso asíncrono (webhook) sin asumir que la correlación sobrevive. ArchUnit: `api ↛ MongoTemplate/MongoRepository`, `api ↛ internals de dominio`, `api ↛ crypto`, y verificación de que ningún controller orquesta directamente entre módulos (§2.8).

## 7. Explícitamente NO resuelto por este ADR

### 7-A. Decisiones propias de esta capa (no heredadas)

| # | Pendiente | Bloquea |
|---|---|---|
| 1 | Formato y mecanismo de `correlationId` (header, generación cuando falta, propagación, política para procesos no-HTTP) | Trazabilidad completa de extremo a extremo |
| 2 | Política de almacenamiento/exposición de `publicCode`/`trackingCode` en el cliente (storage local, logs, analítica, referrers) | Hardening de seguridad del Frontend |
| 3 | Persistencia local del Frontend para sobrevivir un refresco durante el flujo de `DonationIntent` — posible reutilización del patrón offline Flutter (`Outbox`+`flutter_secure_storage`) ya usado para JWT, sin confirmar aplicable aquí | UX de resiliencia del flujo de pago |
| 4 | Contrato de idempotencia del endpoint de creación de `DonationIntent` ante doble request (más allá de la protección de UX de doble clic) | Garantía real ante reintento de red o doble pestaña |

### 7-B. Huecos heredados que esta capa solo hace visibles (no resuelve)

Idempotencia de `clearFundsGenesis` (ADR-033) · `VERIFY` sobre `VERIFIED` y `GRANT`/`REVOKE` repetidos (ADR-034) · fallo de `TokenIssuerPort.issue()` (ADR-034) · contradicción `AuditFactsPort`/`CampaignAuditFactsPort` (ADR-036) · `HumanAccount`+P7 para `PhysicalAsset` (`golden-path.md`, no de esta sesión).

Ningún punto de §7 bloquea trabajo paralelo en esta capa (contratos ya cerrados pueden implementarse); los de 7-B bloquean específicamente los endpoints listados en §2.3 hasta que sus ADRs de origen se resuelvan.

## 8. Trazabilidad de verificación

No se inspeccionó código nuevo en esta sesión para esta capa — el análisis se apoya en `api-contract-matrix.md`, `golden-path.md`, y las cuatro capas de dominio ya verificadas en ADR-033/034/035/036 dentro de esta misma sesión.
