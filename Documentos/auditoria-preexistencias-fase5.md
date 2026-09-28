# Auditoría de preexistencias de comandos y sagas - Fase 5

Este documento presenta una auditoría detallada basada estrictamente en la lectura del código fuente y comandos ejecutados sobre el repositorio real, para validar las afirmaciones hechas en el `documento-maestro-proyecto.md` y los requerimientos de Fase 5.

## 1. Inventario de `core.application.command`

**Comando ejecutado sobre `FundCommandService.java`:**
```bash
$ grep -n "public " core/src/main/java/com/traceability/core/application/command/FundCommandService.java
15:public class FundCommandService {
22:    public FundCommandService(CommandRetryTemplate retryTemplate,
32:    public void confirmAllocation(String commandId, String fundId, String allocationId, com.traceability.core.domain.event.ActorRef actorRef) {
53:    public void reverseAllocation(String commandId, String fundId, String allocationId, String reason, com.traceability.core.domain.event.ActorRef actorRef) {
```

**Comando ejecutado sobre `PhysicalAssetCommandService.java`:**
```bash
$ grep -n "public " core/src/main/java/com/traceability/core/application/command/PhysicalAssetCommandService.java
16:public class PhysicalAssetCommandService {
23:    public PhysicalAssetCommandService(CommandRetryTemplate retryTemplate,
33:    public void deliverAsset(String commandId, String assetId, String finalCustodianRef, String beneficiaryRef, String locationRef, String evidenceRef, Instant deliveredAt, com.traceability.core.domain.event.ActorRef actorRef) {
```

**Estado de los métodos asumidos por la documentación de Fase 5:**
- `registerFund`: **NO EXISTE** (Evidencia: ausencia en la salida de `FundCommandService.java`).
- `clearFunds`: **NO EXISTE** (Evidencia: ausencia en la salida de `FundCommandService.java`).
- `confirmAllocation`: **EXISTE** (Evidencia: línea 32 de `FundCommandService.java`).
- `reverseAllocation`: **EXISTE** (Evidencia: línea 53 de `FundCommandService.java`).
- `registerPhysicalAsset`: **NO EXISTE** (Evidencia: ausencia en la salida de `PhysicalAssetCommandService.java`).
- `splitPhysicalAsset`: **NO EXISTE** (Evidencia: ausencia en la salida de `PhysicalAssetCommandService.java`).
- `deliverAsset`: **EXISTE** (Evidencia: línea 33 de `PhysicalAssetCommandService.java`).

---

## 2. Inventario de `core.application.saga`

**Comando ejecutado:**
```bash
$ find . -path "*core/application/saga*" -name "*.java"
./core/src/test/java/com/traceability/core/application/saga/OutboxSagaCoordinatorTest.java
./core/src/main/java/com/traceability/core/application/saga/OutboxStatus.java
./core/src/main/java/com/traceability/core/application/saga/OutboxMessage.java
./core/src/main/java/com/traceability/core/application/saga/SagaPolicy.java
./core/src/main/java/com/traceability/core/application/saga/OutboxSagaCoordinator.java
./core/src/main/java/com/traceability/core/application/saga/AssetRegisteredSagaPolicy.java
```

**Análisis de clases encontradas:**
- `OutboxStatus.java`, `OutboxMessage.java`, `SagaPolicy.java`, `OutboxSagaCoordinator.java` (y su respectivo Test): Son piezas de infraestructura del coordinador genérico del patrón Transactional Outbox. No reaccionan a eventos de negocio per se, solo orquestan a las políticas concretas.
- `AssetRegisteredSagaPolicy.java`: Reacciona a `ASSET_REGISTERED` leyendo el payload de origen, y en éxito despacha `fundCommandService.confirmAllocation`, y en compensación despacha `fundCommandService.reverseAllocation`.

**Confirmación explícita:**
¿Existe alguna saga que reaccione a `ALLOCATION_REQUESTED` o `ALLOCATION_CONFIRMED` de `Fund`?
**NO EXISTE.** La clase esperada `FundAllocationSagaPolicy` no existe bajo ningún nombre. No hay ninguna saga codificada que consuma estos eventos.

---

## 3. Estado del flujo de dos fases de ADR-012

**Comando ejecutado:**
```bash
$ find . -name "Allocation*Payload.java"
./core/src/main/java/com/traceability/core/domain/fund/payloads/AllocationRequestedPayload.java
./core/src/main/java/com/traceability/core/domain/fund/payloads/AllocationConfirmedPayload.java
./core/src/main/java/com/traceability/core/domain/fund/payloads/AllocationReversedPayload.java
```

**Análisis de productores y consumidores (búsqueda en todo el código):**
- **Productores Reales:** Existen. En `core/src/main/java/com/traceability/core/domain/fund/Fund.java`, los métodos de dominio `requestAllocation`, `confirmAllocation` y `reverseAllocation` producen estos eventos exitosamente (líneas 138, 149 y 160 respectivamente).
- **Consumidores Reales (Sagas):** NO existen para iniciar el activo. Como se demostró en el punto 2, ninguna saga reacciona a `ALLOCATION_REQUESTED`. (La política `AssetRegisteredSagaPolicy` despacha comandos que resultan en `ALLOCATION_CONFIRMED`/`ALLOCATION_REVERSED`, pero el enlace crítico que *genera* la creación del activo reaccionando a la reserva inicial no existe).
- **Consumidores Reales (Proyecciones):** Sí existen. `DonationProjectionHandler.java` reacciona a estos eventos para actualizar la proyección de vista.

**Conclusión Explícita:**
El flujo de asignación Fund → PhysicalAsset está **(b) parcialmente implementado**.
Exactamente falta:
1. La Saga que reaccione a `ALLOCATION_REQUESTED` (`FundAllocationSagaPolicy`) encargada de inyectar el `organizationRef` (leído o provisto por el origen) para despachar el comando hacia PhysicalAsset.
2. La implementación del Application Service Command `registerPhysicalAsset` dentro de `PhysicalAssetCommandService.java` que reciba dicha orden y ejecute el método de dominio.

---

## 4. Tabla de Discrepancias

| Origen Documental | Cita Exacta del Documento | Evidencia de Código que la contradice |
| :--- | :--- | :--- |
| `documento-maestro-proyecto.md` (Línea 246) | "- **Servicios de Dominio (`FundCommandService`, etc.)**: Coordinan la ejecución invocando el agregado... formalizando el registro de las `SagaPolicy` concretas (`AssetRegisteredSagaPolicy`, `SplitPhysicalAssetSagaPolicy`, `FundAllocationSagaPolicy`)." | Salida de comando `find . -iname "*FundAllocation*"`: 0 resultados. Salida de comando `find . -path "*core/application/saga*" -name "*.java"` solo expone `AssetRegisteredSagaPolicy.java`. Ni la clase, ni su contraparte `SplitPhysicalAssetSagaPolicy` existen. |
| `plan-ejecucion-agentes-fase5.md` (Línea 152) | "RegisterPhysicalAsset (comando existente, Camino A) NO recibe organizationRef como parámetro..." | La salida de `grep -n "public " core/src/main/java/com/traceability/core/application/command/PhysicalAssetCommandService.java` expone únicamente el comando `deliverAsset`. El comando `registerPhysicalAsset` a nivel de aplicación NO existe. |
