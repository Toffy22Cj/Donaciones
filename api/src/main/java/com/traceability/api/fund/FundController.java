package com.traceability.api.fund;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.traceability.api.web.CommandId;
import com.traceability.api.web.CurrentActor;
import com.traceability.api.web.InvalidRequestFieldException;
import com.traceability.core.application.command.FundCommandService;
import com.traceability.core.application.port.out.FundOperationalReadPort;
import com.traceability.core.domain.event.HumanActor;
import com.traceability.core.domain.fund.AllocationIds;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Camino A por HTTP (plan P1.1; cierra H-B6C-1 y H-B6D-1): los fondos de la organización y sus asignaciones. Solo usa
 * {@code core}, así que vive en {@code api}. La autorización (P7 y frontera) la hace {@code core}. Importes como texto
 * (T-34). Ninguna respuesta lleva datos del donante.
 */
@RestController
public class FundController {

    private static final Pattern AMOUNT = Pattern.compile("^[1-9][0-9]{0,17}$");

    public record AllocationRequest(String amount) {}

    public record AllocationResponse(String allocationId, String status) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FundItem(String fundId, String campaignRef, String currency, String clearedAmount,
                           String availableAmount, List<AllocationItem> allocations) {}

    public record AllocationItem(String allocationId, String amount, String status) {}

    public record FundsResponse(List<FundItem> items) {}

    private final FundCommandService funds;
    private final FundOperationalReadPort reads;

    public FundController(FundCommandService funds, FundOperationalReadPort reads) {
        this.funds = funds;
        this.reads = reads;
    }

    @GetMapping("/api/v1/organizations/{organizationId}/funds")
    public FundsResponse list(@CurrentActor HumanActor actor, @PathVariable("organizationId") String organizationId) {
        throw new UnsupportedOperationException("P1.1");
    }

    /** El {@code allocationId} es determinista (DD-29): un reenvío del mismo {@code Command-Id} devuelve el mismo. */
    @PostMapping("/api/v1/funds/{fundId}/allocations")
    @ResponseStatus(HttpStatus.CREATED)
    public AllocationResponse request(@CurrentActor HumanActor actor, @CommandId String commandId,
                                      @PathVariable("fundId") String fundId, @RequestBody AllocationRequest body) {
        throw new UnsupportedOperationException("P1.1");
    }

    /** Confirmación manual (DD-32). En el recorrido normal la hace la saga al registrar el activo. */
    @PostMapping("/api/v1/funds/{fundId}/allocations/{allocationId}/confirm")
    public AllocationResponse confirm(@CurrentActor HumanActor actor, @CommandId String commandId,
                                      @PathVariable("fundId") String fundId,
                                      @PathVariable("allocationId") String allocationId) {
        throw new UnsupportedOperationException("P1.1");
    }
}
