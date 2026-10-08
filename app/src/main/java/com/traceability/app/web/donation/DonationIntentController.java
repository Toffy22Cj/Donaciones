package com.traceability.app.web.donation;

import com.traceability.api.web.CommandId;
import com.traceability.api.web.CurrentActor;
import com.traceability.api.web.InvalidRequestFieldException;
import com.traceability.app.application.payments.TrackingCodes;
import com.traceability.app.web.campaign.PublicCampaignNotFoundException;
import com.traceability.app.web.donation.DonationDtos.CreateIntentRequest;
import com.traceability.app.web.donation.DonationDtos.CreateIntentResponse;
import com.traceability.app.web.donation.DonationDtos.IntentStatusResponse;
import com.traceability.contracts.donor.DonorPseudonymPort;
import com.traceability.convocatoria.application.command.CreateDonationIntentCommand;
import com.traceability.convocatoria.application.command.CreateDonationIntentWithAccessResult;
import com.traceability.convocatoria.application.query.ConvocatoriaReadPort;
import com.traceability.convocatoria.application.query.DonationIntentReadPort;
import com.traceability.convocatoria.application.service.DonationIntentService;
import com.traceability.convocatoria.domain.model.PaymentMethod;
import com.traceability.core.domain.event.HumanActor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * CV-11 (crear la intención) y la consulta de su estado con entrega del {@code trackingCode} (Enmienda 3 de ADR-037,
 * D3 y D6; ADR-048; plan B6-b). El {@code donorRef} nunca viene del cliente: con JWT es {@code acct:} + el seudónimo
 * de la cuenta; sin JWT, {@code anon:} + un UUID nuevo. Ni el {@code donorRef} ni el {@code statusToken} se registran
 * en ningún log.
 */
@RestController
public class DonationIntentController {

    public static final String INTENT_TOKEN_HEADER = "Intent-Token";
    private static final Pattern DIGITS = Pattern.compile("^[0-9]{1,18}$");

    private final ConvocatoriaReadPort campaigns;
    private final DonationIntentService intents;
    private final DonationIntentReadPort intentReads;
    private final DonorPseudonymPort pseudonyms;
    private final TrackingCodes trackingCodes;

    public DonationIntentController(ConvocatoriaReadPort campaigns, DonationIntentService intents,
                                    DonationIntentReadPort intentReads, DonorPseudonymPort pseudonyms,
                                    TrackingCodes trackingCodes) {
        this.campaigns = campaigns;
        this.intents = intents;
        this.intentReads = intentReads;
        this.pseudonyms = pseudonyms;
        this.trackingCodes = trackingCodes;
    }

    @PostMapping("/api/v1/public/campaigns/{publicCode}/donation-intents")
    @ResponseStatus(HttpStatus.CREATED)
    public CreateIntentResponse create(@CurrentActor Optional<HumanActor> actor, @CommandId String commandId,
                                       @PathVariable("publicCode") String publicCode,
                                       @RequestBody CreateIntentRequest body) {
        if (body == null) {
            throw new InvalidRequestFieldException("body");
        }
        // 404 público para un código inexistente: CampaignNotFoundException responde 403 en administración (B6-a)
        if (campaigns.findPublicByCode(publicCode).isEmpty()) {
            throw new PublicCampaignNotFoundException();
        }
        String donorRef = actor.map(a -> "acct:" + pseudonyms.pseudonymFor(a.accountId()))
                .orElseGet(() -> "anon:" + UUID.randomUUID());
        CreateDonationIntentWithAccessResult result = intents.createDonationIntentWithAccess(
                new CreateDonationIntentCommand(commandId, publicCode, donorRef, amount(body.amount()),
                        currency(body.currency()), paymentMethod(body.paymentMethod())));
        return new CreateIntentResponse(result.intentId(), result.statusToken(), result.paymentRedirectUrl());
    }

    /** El token solo se acepta en la cabecera {@code Intent-Token}, nunca en la ruta ni en la consulta (Q4 de D-API). */
    @GetMapping("/api/v1/public/donation-intents/{intentId}")
    public IntentStatusResponse status(@PathVariable("intentId") String intentId,
                                       @RequestHeader(name = INTENT_TOKEN_HEADER, required = false) String token) {
        DonationIntentReadPort.IntentStatusView v = intentReads.findForStatusToken(intentId, token)
                .orElseThrow(IntentNotFoundException::new);
        return new IntentStatusResponse(v.status(),
                trackingCodes.forAppliedFunds(v.status(), v.fundId(), v.fundsAppliedAt()).orElse(null));
    }

    private static long amount(String value) {
        if (value == null || !DIGITS.matcher(value).matches()) {
            throw new InvalidRequestFieldException("amount");
        }
        return Long.parseLong(value);
    }

    private static String currency(String value) {
        if (value == null || value.isBlank() || value.length() > 3) {
            throw new InvalidRequestFieldException("currency");
        }
        return value;
    }

    private static PaymentMethod paymentMethod(String value) {
        try {
            return PaymentMethod.valueOf(value);
        } catch (RuntimeException e) {
            throw new InvalidRequestFieldException("paymentMethod");
        }
    }
}
