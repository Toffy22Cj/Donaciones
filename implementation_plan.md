# Tarea 13 — Diseño corregido de Scheduler + Web3j + Poller

## Estado

Este documento corrige y consolida el diseño del flujo de anclaje para que sea coherente con la máquina de estados ya aprobada en ADR-019 y con la restricción de nonce en Ethereum/EVM.

Se trata del documento de referencia para la implementación de:

- `BlockchainAnchorScheduler`
- `Web3jBlockchainAnchorAdapter`
- `AnchorConfirmationPoller`
- `AnchorStartupReconciler`

---

## 1) Regla crítica: el nonce no se libera ni se reassigna cuando se aborta por gas cap

### Decisión final

Si la estimación de gas supera el techo configurable (`crypto.anchor.gas.max-fee-per-gas-cap`), la transacción NO se emite y el batch permanece en `SUBMITTING` con el mismo `nonceUsed`.

No se devuelve a `PENDING`.
No se asigna un nuevo nonce.
No se libera el nonce.

### Justificación

La reserva del nonce se produce en `claimNextPendingBatchAndAssignNonceWithRetry()` y se persiste atómicamente junto con la reclamación del batch. Ese nonce ya ha sido consumido por la cuenta local en el contexto de coordinación del sistema. Si la transacción real no llega a la red, el nonce queda "vacío" para la secuencia de la cuenta y cualquier futura transacción con el siguiente nonce quedará bloqueada esperando a que este nonce aparezca, lo que es un bloqueo permanente de la cuenta.

Esto es especialmente crítico porque la estrategia de coordinación actual ya exige:

- Claim atómico de batch + nonce
- `SUBMITTING` con `nonceUsed` persistido
- `txHash` sólo cuando hay transacción emitida

Por tanto, el caso de "gas cap superado" debe tratarse como una condición de reintento del mismo lote, no como una liberación de recursos.

### Mecanismo unificado

El flujo de reintento del mismo batch debe reutilizar el mismo mecanismo de recuperación ya diseñado para el arranque:

- `AnchorStartupReconciler` corrige batches en `SUBMITTING` sin `txHash` tras reinicio o interrupción.
- El caso de "gas cap superado" es la misma clase de estado: `SUBMITTING`, nonce reservado, nada emitido a la red.

Es decir, ambos caminos comparten una misma regla:

- `SUBMITTING` + `nonceUsed != null` + `txHash == null` = batch legítimamente pendiente de reintento del mismo nonce.
- El scheduler no debe reclamarlo de nuevo ni reasignarle nonce.
- El scheduler debe volver a intentar el envío en el siguiente ciclo, evaluando de nuevo el gas cap y la red.

### Regla operativa

Pseudo-flujo:

```java
if (batch.status() == SUBMITTING && batch.nonceUsed() != null && batch.transactionHash() == null) {
    // Reintento del mismo batch con el mismo nonce
    // no hacer claimNextPendingBatchAndAssignNonce
    // no liberar nonce
    submitAgainWithSameNonce(batch);
}
```

Y el caso de `PENDING` sigue siendo el único caso en el que se puede reclamar un lote nuevo.

### Impacto en los estados

- `PENDING` -> `SUBMITTING` (clamp incluso con nonce reservado)
- `SUBMITTING` + gas cap abort -> permanece `SUBMITTING`, mismo `nonceUsed`, sin `txHash`
- `SUBMITTING` + envío exitoso -> `SUBMITTED`
- `SUBMITTING` + startup recovery -> mismo patrón de recuperación sin liberar nonce

Esto no duplica coordinación ni introduce una segunda forma de manejar lo mismo.

---

## 2) Política de gas: se intenta enviar sólo si cabe dentro del techo

### Decisión final

El adaptador Web3j debe calcular gas dinámicamente usando `ethEstimateGas` y `ethGasPrice` (o el equivalente del cliente), pero con un parámetro de control explícito:

```properties
crypto.anchor.gas.max-fee-per-gas-cap=50000000000
```

Si la red reporta un valor superior al cap, se aborta el intento sin emitir la transacción.

### Comportamiento esperado

- La estimación se hace antes del envío.
- Si el precio supera eltope, se lanza una excepción de dominio o de infraestructura (sin hacer `sendTransaction`).
- El batch no cambia de estado ni se desasigna del nonce.
- El siguiente ciclo del scheduler vuelve a evaluar el mismo lote.

### Regla de tolerancia

El proyecto debe evitar el patrón ambiguo de "a veces vuelve a `PENDING` y a veces entra a `FAILED` temporal". Eso es exactamente lo que ha generado el problema aquí. En esta implementación se adopta una regla única:

- Sobre techo de gas: el batch se mantiene en `SUBMITTING` y se reintenta más tarde.

Esto es coherente con el requisito de no perder el nonce ni bloquear la cuenta.

---

## 3) Máquina de estados del poller: coherente con ADR-019

### Regla aprobada y congelada

La transición del poller debe respetar la separación exacta ya aprobada:

```text
receipt.status == 0 (revertida)  ->  FAILED
sin receipt tras timeout         ->  STUCK
```

### Prohibido

No se debe convertir un `Reverted` en `STUCK`.
No se debe usar `STUCK` como contenedor para todos los errores "no confirmados".

### Regla de negocio

- `status == 1` + evento emitido + `merkleRoot` coincide exactamente -> `ANCHORED`
- `status == 1` + root emitido no coincide -> `ANCHOR_MISMATCH`
- `status == 0` -> `FAILED`
- Sin receipt tras timeout configurable -> `STUCK`

Esto conserva la semántica de ADR-019 según la cual:

- `FAILED` = fallo definitivo confirmado por la red
- `STUCK` = falta de información o incertidumbre operativa

---

## 4) Base temporal del timeout de confirmaciones insuficientes

### Decisión final

El timeout para declarar `STUCK` cuando la transacción ya está minada pero aún no tiene suficientes confirmaciones se mide desde `submittedAt`.

### Justificación

`submittedAt` representa el momento en que el lote entró en el ciclo de envío y el momento desde el cual el sistema tiene la expectativa de que la transacción termine, confirme y cierre el anclaje. Esto hace que el SLA de la transacción sea consistente y evita que la transacción se "renueve" en términos de tiempo según si el receipt aparece tardíamente.

Es decir:

- Si la transacción nunca fue minada: se mira `submittedAt` para detectar timeout de mempool / no receipt.
- Si la transacción fue minada pero aún no alcanza N confirmaciones: también se mide desde `submittedAt` para detectar "transacción viva pero bloqueada / lenta / no cerrada a tiempo".
- El momento en que aparece el receipt por primera vez puede registrarse para observación, pero no sustituye la base temporal del timeout global.

### Regla operacional

```text
if (submittedAt + maxSubmissionWindow < now) {
    if (receipt == null || confirmations < requiredConfirmations) {
        mark STUCK
    }
}
```

La base temporal es, por tanto, un único reloj del ciclo de vida del anclaje.

---

## 5) Arquitectura del flujo resultante

### 5.1 `BlockchainAnchorScheduler`

Responsabilidad:

- Reclamar lote `PENDING` si existe.
- Reintentar reenvío del mismo batch en `SUBMITTING` sin `txHash`.
- Gestionar timeout RPC y abortar por gas cap sin liberar nonce.
- Enviar el lote a `Web3jBlockchainAnchorAdapter`.
- Actualizar estado y `txHash` en repo solo si el envío real tuvo éxito.

#### Guardia crítica obligatoria al inicio del ciclo

Antes de reclamar cualquier lotes `PENDING`, el scheduler debe verificar explícitamente si existe un batch en `SUBMITTING` con `nonceUsed != null` y `transactionHash == null`.

Si existe, el ciclo debe resolver ese batch primero y no puede reclamar un `PENDING` nuevo.

Esto es una guardia dura del invariante de EVM:

```java
public void submitPendingAnchors() {
    Optional<MerkleBatch> retryCandidate = repository.findSubmittingWithoutTxHashAndNonce();

    if (retryCandidate.isPresent()) {
        retrySubmittingBatch(retryCandidate.get());
        return;
    }

    Optional<MerkleBatch> claimed = repository.claimNextPendingBatchAndAssignNonceWithRetry(...);
    if (claimed.isEmpty()) {
        return;
    }

    submitNewPendingBatch(claimed.get());
}
```

Esto evita la regresión catastrófica:

```text
Ciclo n: batch A queda en SUBMITTING, nonceUsed=7, txHash=null, gas cap excedido
Ciclo n+1: NO se debe reclamar B PENDING ni avanzar el nonce
Ciclo n+1: se reintenta A con nonce 7, sin liberar ni reasignar el nonce
```

La operación `findSubmittingWithoutTxHashAndNonce()` debe ser atómica y consistente con el mismo patrón de coordinación de Mongo que `claimNextPendingBatchAndAssignNonceWithRetry()`, para evitar una condición de carrera entre réplicas del monolito.

#### Reintento específico de cada caso

El documento distingue dos caminos explícitos:

```java
private void retrySubmittingBatch(MerkleBatch batch) {
    try {
        var sendResult = adapter.submitRoot(batch); // mismo nonce, mismo batch
        repository.updateSubmitted(batch.batchId(), sendResult.txHash(), Instant.now());
    } catch (GasCapExceededException e) {
        // Determinista: nunca se emitió la tx. No liberar nonce. Queda en SUBMITTING.
        repository.keepSubmittingWithSameNonce(batch.batchId());
    } catch (BlockchainAnchorTimeoutException e) {
        // Ambiguo: la tx pudo haber sido aceptada por el nodo tras el timeout del cliente.
        // No se reenvía ciegamente. Se activa reconciliación contra el nodo con nonceUsed.
        repository.reconcileSubmittingTimeout(batch.batchId(), batch.nonceUsed());
    }
}
```

La diferencia es intencional:

- `GasCapExceededException` = costo determinista antes del envío -> reintento directo del mismo batch, sin liberar nonce.
- `BlockchainAnchorTimeoutException` = timeout ambiguo en la llamada real -> reconciliación contra el nodo antes de reenviar para evitar dobles transacciones con el mismo nonce o pérdida de rastro del `txHash` real.

`markSubmittingAsTimedOut()` ya no debe ser el mecanismo único para ambos casos; debe quedar reservado para el flujo de reconciliación con el nodo, no para el caso de gas cap.

#### Reintento de lote nuevo

La rama de lote nuevo sigue siendo:

```java
private void submitNewPendingBatch(MerkleBatch batch) {
    try {
        var sendResult = adapter.submitRoot(batch);
        repository.updateSubmitted(batch.batchId(), sendResult.txHash(), Instant.now());
    } catch (GasCapExceededException e) {
        repository.keepSubmittingWithSameNonce(batch.batchId());
    } catch (BlockchainAnchorTimeoutException e) {
        repository.reconcileSubmittingTimeout(batch.batchId(), batch.nonceUsed());
    }
}
```

Observación importante: la lógica de retry de `SUBMITTING` no debe invocar `claimNextPendingBatchAndAssignNonce*` otra vez. Debe trabajar sobre el batch ya existente y solo puede ser ejecutada si no existe un `PENDING` más prioritario; el invariante es: cuando haya un `SUBMITTING` sin resolver, ese tiene prioridad absoluta.

### 5.2 `Web3jBlockchainAnchorAdapter`

Responsabilidad:

- Cargar `Credentials` desde la private key.
- Construir el wrapper generado por `web3j-maven-plugin`.
- Estimar gas y verificar tope.
- Enviar `storeRoot(bytes32)` con el nonce persistido.
- Ejecutar `sendRawTransaction` o equivalente con `CompletableFuture` con timeout de 10s.
- Exponer `getAnchorReceipt(String txHash)` para el poller.

Reglas:

- El envío usa el nonce ya reservado en `MerkleBatch.nonceUsed`.
- Si el gas supera el cap, aborta el envío sin liberar/ordenar nonce.
- Si el nodo tarda demasiado, lanza `BlockchainAnchorTimeoutException`.

### 5.3 `AnchorConfirmationPoller`

Responsabilidad:

- Consultar batches `SUBMITTED`.
- Verificar receipt y confirmaciones.
- Evaluar igualdad exacta de `merkleRoot` con el evento.
- Aplicar estados finales:
  - `ANCHORED`
  - `ANCHOR_MISMATCH`
  - `FAILED`
  - `STUCK`

Pseudo-lógica:

```java
for (MerkleBatch batch : repository.findSubmittedOlderFirst()) {
    TransactionReceipt receipt = adapter.getAnchorReceipt(batch.transactionHash());

    if (receipt == null) {
        if (clock.now().isAfter(batch.submittedAt().plus(timeout))) {
            repository.markStuck(batch.batchId());
        }
        continue;
    }

    if (receipt.isStatusOK()) {
        if (eventRootMatches(batch.merkleRoot(), receipt)) {
            repository.markAnchored(batch.batchId(), receipt.getBlockNumber());
        } else {
            repository.markAnchorMismatch(batch.batchId());
        }
    } else {
        repository.markFailed(batch.batchId());
    }
}
```

Con la condición adicional de `N` confirmaciones:

- Si el receipt existe pero aún no alcanza el umbral configurado, se ignora hasta el próximo ciclo.
- Si supera el timeout global desde `submittedAt`, se marca `STUCK`.

---

## 6) Implementación necesaria para la implementación programada

### 6.1 Maven / Web3j wrapper

Se debe integrar el plugin `web3j-maven-plugin` en el `pom.xml` del módulo `crypto` para compilar el ABI del contrato `AnchorRegistry.sol` y generar el wrapper Java tipado.

El flujo esperado:

1. Obtener `AnchorRegistry.sol` en `src/main/solidity` o equivalente del paquete de contratos.
2. Configurar el plugin con la ruta del Solidity y el paquete destino.
3. Generar wrapper con `storeRoot(bytes32)` y los tipos de evento.
4. Verificar que el wrapper expone el evento con root legible.

### 6.2 Repositorio / estado

Necesidad real de mantener:

- `submittedAt`
- `nonceUsed`
- `transactionHash`
- `confirmedBlockNumber`
- `resolution`

Sin perder nunca el nonce si se aborta por gas cap.

### 6.3 Test plan ampliado

Se añaden estas pruebas específicas:

1. `scheduler_keeps_same_nonce_when_gas_cap_exceeded`  
   Verifica que el batch sigue en `SUBMITTING`, con el mismo `nonceUsed`, sin liberar ni reclamar de nuevo.

2. `scheduler_retries_same_batch_on_next_cycle_after_gas_cap_abort`  
   Verifica que el siguiente ciclo reintenta el envío del mismo batch usando el mismo nonce.

3. `poller_marks_failed_when_receipt_status_zero`  
   Verifica `FAILED` cuando la transacción se revirtió.

4. `poller_marks_stuck_when_no_receipt_after_timeout`  
   Verifica `STUCK` solo cuando la transacción nunca aparece o no confirma a tiempo.

5. `poller_marks_anchor_mismatch_when_root_differs`  
   Verifica la transición a `ANCHOR_MISMATCH` si el evento emite un root distinto.

6. `poller_marks_anchored_after_required_confirmations`  
   Verifica `ANCHORED` con confirmaciones suficientes y root correcto.

---

## 7) Aprobación para implementación

El diseño queda corregido en estos tres puntos:

1. `gas cap exceeded` no libera ni reasigna nonce; el batch se mantiene en `SUBMITTING` con el mismo `nonceUsed` y reintenta en ciclos siguientes.
2. `revert` va a `FAILED`, nunca a `STUCK`; `STUCK` sólo se usa para ausencia de receipt o confirmaciones insuficientes tras timeout.
3. El timeout de confirmaciones insuficientes se miden desde `submittedAt`.

Adicionalmente, el documento incorpora la resolución del cuarto vacío crítico:

4. Un batch que permanece en `SUBMITTING` sin `txHash` y sin salida durante un tiempo configurable debe escalar también a `STUCK`, con el mismo flujo operativo de resolución manual que ya existe para batches en `STUCK`.

### Timeout específico de `SUBMITTING`

La arquitectura requiere un timeout independiente para batches que nunca llegan a `SUBMITTED`:

```text
if (status == SUBMITTING && txHash == null && submittingSince + maxSubmittingWindow < now) {
    mark STUCK
}
```

La semántica del campo temporal debe ser explícita:

- `submittedAt` sigue siendo el tiempo de publicación para lotes ya `SUBMITTED` y confirmación.
- `submittingSince` (o `submittedAt` reutilizado con un segundo significado antes de tener `txHash`) representa el momento en que el lote entró en `SUBMITTING` y comenzó el reintento del mismo nonce.

La transición no depende del `AnchorConfirmationPoller` sobre `SUBMITTED`; puede implementarse como una precondición al inicio del `BlockchainAnchorScheduler` o como un chequeo adicional en el poller para incluir también `SUBMITTING`.

La regla de negocio es:

```java
public void checkStuckSubmittingBatches() {
    for (MerkleBatch batch : repository.findSubmittingWithoutTxHashOlderThan(maxSubmittingWindow)) {
        repository.markStuck(batch.batchId());
        alertingService.emitOperationalAlert(batch.batchId(), "SUBMITTING timeout without txHash");
    }
}
```

Esto garantiza que no haya un estado `SUBMITTING` sin salida posible que monopolice el scheduler indefinidamente y que el sistema dispare la alerta operativa y la resolución manual exigidas por ADR-019.

### Nota de robustez para implementación

- `retrySubmittingBatch` y `submitNewPendingBatch` deben cerrar un `catch (Exception e)` final que no rompa la ejecución del scheduler y deje el batch en su estado actual con logging de severidad alta, evitando que excepciones inesperadas de Web3j desactiven el ciclo de programación sin dejar trazabilidad.
- La guardia `findSubmittingWithoutTxHashAndNonce()` debe contemplar el ámbito actual de una sola red; si el sistema evoluciona a múltiples redes, la consulta debe hacerse por `network + smartContractAddress` para no bloquear una red sana por un lote atascado en otra.

Con estas dos adiciones, el diseño queda cerrado con la misma disciplina de validación que exigimos para las transiciones anteriores: un estado sin salida real no puede quedar oculto ni bloquear el sistema indefinidamente.

Con esto, el siguiente paso es la implementación real del plugin Web3j, el wrapper generado y la escritura de los tests del ciclo de confirmación, reintento del scheduler y escalado a `STUCK`.
