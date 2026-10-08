package com.traceability.app.web.account;

import com.traceability.api.web.CurrentActor;
import com.traceability.app.application.payments.TrackingCodes;
import com.traceability.app.web.donation.DonationDtos.AccountDonation;
import com.traceability.app.web.donation.DonationDtos.AccountDonationsResponse;
import com.traceability.contracts.donor.DonorPseudonymPort;
import com.traceability.convocatoria.application.query.DonationIntentReadPort;
import com.traceability.core.domain.event.HumanActor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Historial de donaciones de la cuenta (criterio 6 del golden path; ADR-048 §3; Q6 de D-API). JWT obligatorio. Lee
 * el seudónimo de la cuenta sin crearlo: una cuenta que nunca donó tiene el historial vacío. Ninguna respuesta lleva
 * el seudónimo ni el {@code donorRef} (ADR-048 §7).
 */
@RestController
public class AccountDonationsController {

    /** Tope de la página única del historial de la demo (DD-21). */
    static final int MAX_ITEMS = 100;

    private final DonorPseudonymPort pseudonyms;
    private final DonationIntentReadPort intents;
    private final TrackingCodes trackingCodes;

    public AccountDonationsController(DonorPseudonymPort pseudonyms, DonationIntentReadPort intents,
                                      TrackingCodes trackingCodes) {
        this.pseudonyms = pseudonyms;
        this.intents = intents;
        this.trackingCodes = trackingCodes;
    }

    @GetMapping("/api/v1/account/donations")
    public AccountDonationsResponse donations(@CurrentActor HumanActor actor) {
        List<AccountDonation> items = pseudonyms.existingPseudonymFor(actor.accountId())
                .map(p -> intents.findByDonorRef("acct:" + p, MAX_ITEMS).stream()
                        .map(d -> new AccountDonation(d.intentId(), d.campaignTitle(), Long.toString(d.amount()),
                                d.currency(), d.status(),
                                trackingCodes.forAppliedFunds(d.status(), d.fundId(), d.fundsAppliedAt()).orElse(null)))
                        .toList())
                .orElse(List.of());
        return new AccountDonationsResponse(items);
    }
}
