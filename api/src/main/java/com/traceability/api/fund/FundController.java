package com.traceability.api.fund;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.traceability.api.web.CommandId;
import com.traceability.api.web.CurrentActor;
import com.traceability.api.web.InvalidRequestFieldException;
import com.traceability.core.application.command.FundCommandService;
import com.traceability.core.application.port.out.FundMonthlyClearingReadPort;
import com.traceability.core.application.port.out.FundMonthlyClearingReadPort.MonthlyClearing;
import com.traceability.core.application.port.out.FundOperationalReadPort;
import com.traceability.core.domain.event.HumanActor;
import com.traceability.core.domain.fund.AllocationIds;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
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
    /** Ficha R-6, G-6: {@code months} de 1 a 12. */
    private static final Pattern MONTHS = Pattern.compile("^([1-9]|1[0-2])$");
    private static final int DEFAULT_MONTHS = 6;

    public record AllocationRequest(String amount) {}

    public record AllocationResponse(String allocationId, String status) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FundItem(String fundId, String campaignRef, String currency, String clearedAmount,
                           String availableAmount, List<AllocationItem> allocations) {}

    public record AllocationItem(String allocationId, String amount, String status) {}

    public record FundsResponse(List<FundItem> items) {}

    /** Ficha R-6, G-6: {@code month} como {@code AAAA-MM}; importe como texto (T-34). */
    public record MonthlyItem(String month, String currency, String clearedAmount) {}

    /** {@code truncated} solo aparece cuando es {@code true} (P-R6B-6). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MonthlyResponse(String timezone, List<MonthlyItem> items, Boolean truncated) {}

    private final FundCommandService funds;
    private final FundOperationalReadPort reads;
    private final FundMonthlyClearingReadPort monthly;

    public FundController(FundCommandService funds, FundOperationalReadPort reads, FundMonthlyClearingReadPort monthly) {
        this.funds = funds;
        this.reads = reads;
        this.monthly = monthly;
    }

    @GetMapping("/api/v1/organizations/{organizationId}/funds")
    public FundsResponse list(@CurrentActor HumanActor actor, @PathVariable("organizationId") String organizationId) {
        return new FundsResponse(reads.listForOrganization(organizationId, actor).stream()
                .map(f -> new FundItem(f.fundId(), f.campaignRef(), f.currency(), Long.toString(f.clearedAmount()),
                        Long.toString(f.availableAmount()), f.allocations().stream()
                        .map(a -> new AllocationItem(a.allocationId(), Long.toString(a.amount()), a.status())).toList()))
                .toList());
    }

    /**
     * Ficha R-6, G-6: recaudado por mes de la organización, {@code months} de 1 a 12 (por defecto 6), la misma política
     * que {@code /funds} (DD-31) y {@code no-store}. La autorización la hace {@code core}.
     */
    @GetMapping("/api/v1/organizations/{organizationId}/funds/monthly")
    public ResponseEntity<MonthlyResponse> monthly(@CurrentActor HumanActor actor,
                                                   @PathVariable("organizationId") String organizationId,
                                                   @RequestParam(name = "months", required = false) String months) {
        if (months != null && !MONTHS.matcher(months).matches()) {
            throw new InvalidRequestFieldException("months");
        }
        MonthlyClearing m = monthly.monthlyForOrganization(organizationId,
                months == null ? DEFAULT_MONTHS : Integer.parseInt(months), actor);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new MonthlyResponse(m.timezone(),
                m.items().stream().map(i -> new MonthlyItem(i.month().toString(), i.currency(),
                        Long.toString(i.clearedAmount()))).toList(),
                m.truncated() ? Boolean.TRUE : null));
    }

    /** El {@code allocationId} es determinista (DD-29): un reenvío del mismo {@code Command-Id} devuelve el mismo. */
    @PostMapping("/api/v1/funds/{fundId}/allocations")
    @ResponseStatus(HttpStatus.CREATED)
    public AllocationResponse request(@CurrentActor HumanActor actor, @CommandId String commandId,
                                      @PathVariable("fundId") String fundId, @RequestBody AllocationRequest body) {
        if (body == null || body.amount() == null || !AMOUNT.matcher(body.amount()).matches()) {
            throw new InvalidRequestFieldException("amount");
        }
        String allocationId = AllocationIds.of(fundId, commandId);
        funds.requestAllocation(commandId, fundId, allocationId, Long.parseLong(body.amount()), actor);
        return new AllocationResponse(allocationId, "REQUESTED");
    }

    /** Confirmación manual (DD-32). En el recorrido normal la hace la saga al registrar el activo. */
    @PostMapping("/api/v1/funds/{fundId}/allocations/{allocationId}/confirm")
    public AllocationResponse confirm(@CurrentActor HumanActor actor, @CommandId String commandId,
                                      @PathVariable("fundId") String fundId,
                                      @PathVariable("allocationId") String allocationId) {
        funds.confirmAllocationByCommand(commandId, fundId, allocationId, actor);
        return new AllocationResponse(allocationId, "CONFIRMED");
    }
}
