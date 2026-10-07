package com.traceability.core.application.saga;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.core.application.command.PhysicalAssetCommandService;
import com.traceability.core.application.port.out.ProcessedCommandRepositoryPort;
import com.traceability.core.domain.physicalasset.exceptions.AssetTerminalStateException;
import com.traceability.core.domain.shared.exceptions.DomainInvariantViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Saga de la división (D-SPLIT; plan B1-bis; ADR-007/008 Enmienda 1). Mensaje {@code {"parentAssetId","childAssetId"}}.
 *
 * <ul>
 *   <li>{@code execute}: crea el hijo bajo la barrera {@code SPLIT_RESOLUTION:{childAssetId}}. Si la división ya se
 *       resolvió de otra forma, termina sin efecto.</li>
 *   <li>{@code compensate} (resolución, Enmienda 1 D4): compensa; si el padre está {@code DELIVERED} y compensar es
 *       imposible, <strong>crea el hijo igualmente</strong> (la cantidad ya se extrajo). Solo si eso también es
 *       imposible lanza {@link PermanentSagaFailureException}: la división queda {@code UNRESOLVED}.</li>
 *   <li>{@code onManualResolution}: toma el reclamo con {@code RESOLVED_MANUALLY}, en la transacción de la
 *       resolución manual; si la división ya tenía reclamo, falla sin cambiar nada.</li>
 * </ul>
 * Un invariante de dominio o un dato inexistente es un fallo permanente; cualquier otra excepción es transitoria.
 */
@Component
public class SplitPhysicalAssetSagaPolicy implements SagaPolicy {

    public static final String SAGA_TYPE = "ASSET_SPLIT_SAGA";

    private static final Logger log = LoggerFactory.getLogger(SplitPhysicalAssetSagaPolicy.class);

    private final PhysicalAssetCommandService assets;
    private final ProcessedCommandRepositoryPort processedCommands;
    private final ObjectMapper objectMapper;

    public SplitPhysicalAssetSagaPolicy(PhysicalAssetCommandService assets, ProcessedCommandRepositoryPort processedCommands,
                                        ObjectMapper objectMapper) {
        this.assets = assets;
        this.processedCommands = processedCommands;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getSagaType() {
        return SAGA_TYPE;
    }

    @Override
    public void execute(OutboxMessage message) {
        Split split = parse(message);
        SplitResolution outcome = classified(() -> assets.createSplitChild(split.parent(), split.child()));
        if (outcome != SplitResolution.CHILD_CREATED) {
            log.warn("Split {} of {} was already resolved as {}: child not created", split.child(), split.parent(), outcome);
        }
    }

    @Override
    public void compensate(OutboxMessage message) {
        Split split = parse(message);
        SplitResolution outcome;
        try {
            outcome = classified(() -> assets.compensateSplitChild(split.parent(), split.child()));
        } catch (PermanentSagaFailureException e) {
            if (!(e.getCause() instanceof AssetTerminalStateException)) {
                throw e;
            }
            log.warn("Split {} of {}: parent is terminal, compensation impossible; creating the child instead",
                    split.child(), split.parent());
            outcome = classified(() -> assets.createSplitChild(split.parent(), split.child()));
        }
        if (outcome == SplitResolution.CHILD_CREATED) {
            log.warn("Split {} of {} resolved by creating the child", split.child(), split.parent());
        }
    }

    @Override
    public void onManualResolution(OutboxMessage message, String operator, String note) {
        Split split = parse(message);
        String key = SplitResolution.claimKey(split.child());
        if (!processedCommands.tryClaim(key, SplitResolution.RESOLVED_MANUALLY.name())) {
            throw new IllegalStateException("Split " + split.child() + " is already resolved as "
                    + processedCommands.findOutcome(key).orElse("unknown"));
        }
    }

    private static SplitResolution classified(java.util.function.Supplier<SplitResolution> action) {
        try {
            return action.get();
        } catch (DomainInvariantViolationException | IllegalArgumentException e) {
            throw new PermanentSagaFailureException(e.getClass().getSimpleName() + ": " + e.getMessage(), e);
        }
    }

    private Split parse(OutboxMessage message) {
        try {
            JsonNode payload = objectMapper.readTree(message.payload());
            return new Split(payload.path("parentAssetId").asText(), payload.path("childAssetId").asText());
        } catch (Exception e) {
            throw new PermanentSagaFailureException("Unreadable ASSET_SPLIT_SAGA payload", e);
        }
    }

    private record Split(String parent, String child) {}
}
