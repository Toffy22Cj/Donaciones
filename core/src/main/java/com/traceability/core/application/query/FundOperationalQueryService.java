package com.traceability.core.application.query;

import com.traceability.contracts.authorization.AuthorizationPrincipal;
import com.traceability.contracts.authorization.AuthorizationRole;
import com.traceability.contracts.authorization.IdentityPrincipalPort;
import com.traceability.core.application.authorization.InsufficientRoleException;
import com.traceability.core.application.authorization.OrganizationBoundaryPolicy;
import com.traceability.core.application.port.out.EventStorePort;
import com.traceability.core.application.port.out.FundClearingEventsPort;
import com.traceability.core.application.port.out.FundClearingEventsPort.ClearingEvent;
import com.traceability.core.application.port.out.FundDirectoryPort;
import com.traceability.core.application.port.out.FundMonthlyClearingReadPort;
import com.traceability.core.application.port.out.FundOperationalReadPort;
import com.traceability.core.domain.event.DomainEvent;
import com.traceability.core.domain.event.HumanActor;
import com.traceability.core.domain.fund.Fund;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Lectura de los fondos de una organización (plan P1.1, DD-31). Del event store, como la lectura operacional de
 * activos (DD-13): la proyección de donaciones no guarda la organización. Autoriza antes de leer nada. También el
 * recaudado por mes (ficha R-6, G-6; plan del Bloque B, P-R6B-4), con la misma política.
 */
@Service
public class FundOperationalQueryService implements FundOperationalReadPort, FundMonthlyClearingReadPort {

    /** Una página, como el historial de la cuenta (DD-21). */
    static final int MAX_FUNDS = 200;

    /** Fondos que lee el recaudado por mes (P-R6B-6): más arriba, {@code truncated}. */
    static final int MAX_MONTHLY_FUNDS = 10_000;

    private static final ZoneId BOGOTA = ZoneId.of(TIMEZONE);

    private final FundDirectoryPort directory;
    private final EventStorePort eventStore;
    private final IdentityPrincipalPort identityPrincipalPort;
    private final OrganizationBoundaryPolicy organizationBoundaryPolicy;
    private final FundClearingEventsPort clearings;
    private final Clock clock;

    public FundOperationalQueryService(FundDirectoryPort directory, EventStorePort eventStore,
                                       IdentityPrincipalPort identityPrincipalPort,
                                       OrganizationBoundaryPolicy organizationBoundaryPolicy,
                                       FundClearingEventsPort clearings, ObjectProvider<Clock> clock) {
        this.directory = directory;
        this.eventStore = eventStore;
        this.identityPrincipalPort = identityPrincipalPort;
        this.organizationBoundaryPolicy = organizationBoundaryPolicy;
        this.clearings = clearings;
        this.clock = clock.getIfAvailable(Clock::systemUTC);
    }

    @Override
    public List<FundView> listForOrganization(String organizationRef, HumanActor actor) {
        authorize(organizationRef, actor);
        return directory.findFundIdsByOrganization(organizationRef, MAX_FUNDS).stream().map(this::view).toList();
    }

    /**
     * Ficha R-6, G-6. La pertenencia es la del directorio, por la génesis: un {@code FUNDS_CLEARED} cuenta para la
     * organización de su fondo, sea 1.0 o 2.0, y los fondos 1.0 sin organización quedan fuera (P-R6B-1). Se filtra
     * por fecha en memoria: {@code occurredAt} es texto ISO con decimales variables y no se compara como texto.
     */
    @Override
    public MonthlyClearing monthlyForOrganization(String organizationRef, int months, HumanActor actor) {
        authorize(organizationRef, actor);
        List<String> fundIds = directory.findFundIdsByOrganization(organizationRef, MAX_MONTHLY_FUNDS + 1);
        boolean truncated = fundIds.size() > MAX_MONTHLY_FUNDS;
        if (truncated) {
            fundIds = fundIds.subList(0, MAX_MONTHLY_FUNDS);
        }
        Map<String, String> fundCurrencies = clearings.findGenesisCurrencies(fundIds);

        YearMonth last = YearMonth.now(clock.withZone(BOGOTA));
        YearMonth first = last.minusMonths(months - 1L);
        Map<String, Map<YearMonth, Long>> byCurrency = new HashMap<>();
        new TreeSet<>(fundCurrencies.values()).forEach(c -> byCurrency.put(c, new HashMap<>()));
        for (ClearingEvent e : clearings.findFundsCleared(fundIds)) {
            YearMonth month = YearMonth.from(e.occurredAt().atZone(BOGOTA));
            String currency = e.currency() != null ? e.currency() : fundCurrencies.get(e.fundId());
            if (month.isBefore(first) || month.isAfter(last) || currency == null) {
                continue;
            }
            byCurrency.computeIfAbsent(currency, c -> new HashMap<>()).merge(month, e.clearedAmount(), Long::sum);
        }

        List<MonthItem> items = new ArrayList<>();
        for (YearMonth month = first; !month.isAfter(last); month = month.plusMonths(1)) {
            for (String currency : new TreeSet<>(byCurrency.keySet())) {
                items.add(new MonthItem(month, currency, byCurrency.get(currency).getOrDefault(month, 0L)));
            }
        }
        return new MonthlyClearing(TIMEZONE, items, truncated);
    }

    /** DD-31: frontera de organización y {@code ADMINISTRATOR} o {@code EMPLOYEE}, antes de leer nada. */
    private void authorize(String organizationRef, HumanActor actor) {
        AuthorizationPrincipal principal = identityPrincipalPort.resolvePrincipal(actor.accountId());
        organizationBoundaryPolicy.assertBelongs(principal.organizationId(), organizationRef);
        if (principal.roles() == null || (!principal.roles().contains(AuthorizationRole.ADMINISTRATOR)
                && !principal.roles().contains(AuthorizationRole.EMPLOYEE))) {
            throw new InsufficientRoleException("Reading funds requires ADMINISTRATOR or EMPLOYEE");
        }
    }

    private FundView view(String fundId) {
        List<DomainEvent> events = eventStore.loadStream(fundId);
        Fund fund = Fund.rehydrate(fundId, events.stream().map(DomainEvent::payload).toList(), events.size());
        return new FundView(fundId, fund.getCampaignRef(), fund.getCurrency(), fund.getClearedAmount(),
                fund.getAvailableAmount(), fund.getAllocations().stream()
                .map(a -> new AllocationItem(a.allocationId(), a.amount(), a.status().name())).toList());
    }
}
