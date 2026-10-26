package com.traceability.app.application.account;

import com.traceability.contracts.donor.DonorPseudonymPort;
import com.traceability.convocatoria.application.query.DonationIntentReadPort;
import com.traceability.convocatoria.application.query.DonationIntentReadPort.DonationView;
import com.traceability.core.application.port.out.DonationReadPort;
import com.traceability.core.application.port.out.LogisticsReadItem;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * M-21 "Mi impacto" (plan de cierre §M-21, aprobado en #147: P-M21-1 a 5; {@code campaignRef} por la decisión (a)).
 * Solo lectura, de la cuenta que pregunta:
 * <ul>
 *   <li>cuentan solo las donaciones {@code CONFIRMED} (P-M21-3, DD-46);</li>
 *   <li>los importes se suman por moneda y las unidades por unidad de medida; nunca se mezclan;</li>
 *   <li>las unidades entregadas son las de los activos {@code DELIVERED} de la {@code logistics} de cada fondo con fondos
 *   aplicados, cada activo una vez (P-M21-4: "unidades entregadas en las convocatorias que apoyaste", no atribuidas al
 *   donante);</li>
 *   <li>el mismo tope que {@code /account/donations} (DD-21), con {@code truncated}.</li>
 * </ul>
 * Lee el seudónimo sin crearlo: una cuenta que nunca donó tiene todo a cero. Nunca devuelve el seudónimo, el
 * {@code donorRef}, el {@code fundId}, el {@code assetId} ni el {@code campaignRef}.
 */
@Service
public class AccountImpactQuery {

    /** El tope de la página única del historial (DD-21). */
    public static final int MAX_INTENTS = 100;

    public record Total(String currency, long amount) {}

    public record Units(String unitOfMeasure, BigDecimal quantity) {}

    public record Impact(int donationsCount, List<Total> totals, int campaignsSupported, List<Units> deliveredUnits,
                         boolean truncated) {}

    private final DonorPseudonymPort pseudonyms;
    private final DonationIntentReadPort intents;
    private final DonationReadPort donations;

    public AccountImpactQuery(DonorPseudonymPort pseudonyms, DonationIntentReadPort intents, DonationReadPort donations) {
        this.pseudonyms = pseudonyms;
        this.intents = intents;
        this.donations = donations;
    }

    public Impact impactOf(String accountId) {
        List<DonationView> found = pseudonyms.existingPseudonymFor(accountId)
                .map(p -> intents.findByDonorRef("acct:" + p, MAX_INTENTS + 1))
                .orElse(List.of());
        boolean truncated = found.size() > MAX_INTENTS;
        List<DonationView> confirmed = found.stream().limit(MAX_INTENTS)
                .filter(d -> "CONFIRMED".equals(d.status())).toList();

        Map<String, Long> totals = new TreeMap<>();
        Set<String> campaigns = new HashSet<>();
        Map<String, BigDecimal> units = new TreeMap<>();
        Set<String> countedAssets = new HashSet<>();
        for (DonationView d : confirmed) {
            totals.merge(d.currency(), d.amount(), Long::sum);
            if (d.campaignRef() != null) {
                campaigns.add(d.campaignRef());
            }
            if (d.fundId() == null) {
                continue;
            }
            donations.findByFundId(d.fundId()).ifPresent(fund -> {
                for (LogisticsReadItem item : fund.logistics()) {
                    if ("DELIVERED".equals(item.lifecycleStatus()) && item.quantity() != null
                            && countedAssets.add(item.assetId())) {
                        units.merge(item.unitOfMeasure(), item.quantity(), BigDecimal::add);
                    }
                }
            });
        }
        return new Impact(confirmed.size(),
                totals.entrySet().stream().map(e -> new Total(e.getKey(), e.getValue())).toList(),
                campaigns.size(),
                units.entrySet().stream().map(e -> new Units(e.getKey(), e.getValue())).toList(),
                truncated);
    }
}
