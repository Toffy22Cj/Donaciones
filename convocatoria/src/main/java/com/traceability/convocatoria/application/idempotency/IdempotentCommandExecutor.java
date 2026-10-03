package com.traceability.convocatoria.application.idempotency;

import com.traceability.convocatoria.application.port.out.ProcessedCommandPort;
import com.traceability.convocatoria.application.service.ConvocatoriaTransactionRetryHelper;
import com.traceability.convocatoria.domain.exception.CommandIdReusedForDifferentCommandException;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Ejecución idempotente de un comando de escritura del módulo (Enmienda §3.5, N12; ID; implementation_plan.md §7.1, §7.3).
 * Dentro de una única transacción (con reintento ante {@code TransientTransactionError}): reclamar {@code commandId}
 * → si ya existía, devolver el resultado guardado sin ejecutar (o rechazar si es de otro tipo, I1) → si no, ejecutar
 * y guardar el resultado en el mismo documento antes del commit. Si la transacción se revierte, el reclamo también.
 * No compara el contenido del comando duplicado (Enmienda §3.5 [ESTADO]).
 */
@Component
public class IdempotentCommandExecutor {

    private final ConvocatoriaTransactionRetryHelper transactionRetryHelper;
    private final ProcessedCommandPort processedCommandPort;

    public IdempotentCommandExecutor(ConvocatoriaTransactionRetryHelper transactionRetryHelper,
                                     ProcessedCommandPort processedCommandPort) {
        this.transactionRetryHelper = transactionRetryHelper;
        this.processedCommandPort = processedCommandPort;
    }

    public Map<String, String> execute(String commandId, CommandType commandType,
                                       Supplier<Map<String, String>> action) {
        Objects.requireNonNull(commandId, "commandId");
        Objects.requireNonNull(commandType, "commandType");
        if (commandType.isSystem()) {
            throw new IllegalArgumentException(commandType + " is a system command and never takes a client commandId");
        }
        return transactionRetryHelper.executeWithRetry(() -> {
            Optional<ProcessedCommand> previous = processedCommandPort.claim(commandId, commandType);
            if (previous.isPresent()) {
                if (previous.get().commandType() != commandType) {
                    throw new CommandIdReusedForDifferentCommandException("commandId " + commandId
                            + " was already used by " + previous.get().commandType());
                }
                return previous.get().result();
            }
            Map<String, String> result = Map.copyOf(action.get());
            processedCommandPort.saveResult(commandId, result);
            return result;
        });
    }
}
