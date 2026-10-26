package com.traceability.app.web.account;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.traceability.api.web.CurrentActor;
import com.traceability.app.application.account.AccountImpactQuery;
import com.traceability.app.application.account.AccountImpactQuery.Impact;
import com.traceability.core.domain.event.HumanActor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

/**
 * M-21 "Mi impacto" (plan de cierre §M-21; P-M21-1 y P-M21-2): {@code GET /api/v1/account/impact}, JWT de cualquier
 * cuenta, {@code private, no-store}. {@code 200 {donationsCount, totals: [{currency, amount}], campaignsSupported,
 * deliveredUnits: [{unitOfMeasure, quantity}], truncated}}; importes y cantidades como texto, como el historial (T-34).
 */
@RestController
public class AccountImpactController {

    public record TotalResponse(String currency, String amount) {}

    public record UnitsResponse(String unitOfMeasure, @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal quantity) {}

    public record ImpactResponse(int donationsCount, List<TotalResponse> totals, int campaignsSupported,
                                 List<UnitsResponse> deliveredUnits, boolean truncated) {}

    private final AccountImpactQuery impact;

    public AccountImpactController(AccountImpactQuery impact) {
        this.impact = impact;
    }

    @GetMapping("/api/v1/account/impact")
    public ResponseEntity<ImpactResponse> impact(@CurrentActor HumanActor actor) {
        Impact i = impact.impactOf(actor.accountId());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate()).body(new ImpactResponse(
                i.donationsCount(),
                i.totals().stream().map(t -> new TotalResponse(t.currency(), Long.toString(t.amount()))).toList(),
                i.campaignsSupported(),
                i.deliveredUnits().stream().map(u -> new UnitsResponse(u.unitOfMeasure(), u.quantity())).toList(),
                i.truncated()));
    }
}
