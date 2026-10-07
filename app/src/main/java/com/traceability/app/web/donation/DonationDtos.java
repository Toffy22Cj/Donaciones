package com.traceability.app.web.donation;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** Cuerpos de CV-11, de la consulta de la intención y del historial (plan B6-b). Importes como texto (T-34). */
public final class DonationDtos {

    private DonationDtos() {}

    /** CV-11. Sin {@code donorRef}: si el cliente lo envía, se ignora (ADR-048). */
    public record CreateIntentRequest(String amount, String currency, String paymentMethod) {}

    /** {@code statusToken} solo en la llamada que creó la intención (Enmienda 3 de ADR-037, D6; DD-18). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CreateIntentResponse(String intentId, String statusToken, String paymentRedirectUrl) {}

    /** {@code trackingCode} solo con los fondos aplicados (D6). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record IntentStatusResponse(String status, String trackingCode) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AccountDonation(String intentId, String campaignTitle, String amount, String currency, String status,
                                  String trackingCode) {}

    /** T-35: {@code nextCursor} se omite en la última página; el historial de la demo cabe en una. */
    public record AccountDonationsResponse(List<AccountDonation> items) {}
}
