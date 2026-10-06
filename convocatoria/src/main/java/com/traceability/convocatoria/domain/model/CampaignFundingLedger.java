package com.traceability.convocatoria.domain.model;

import com.traceability.convocatoria.domain.exception.CloseOnTargetCloseNotSupportedException;

import java.util.Objects;

/**
 * Ledger de fondos de la convocatoria (ADR-037 §2.2; Enmienda §3.1 N2, §3.3). Existe solo si la convocatoria
 * acepta {@code MONETARY}. La protección de la meta se ejecuta como escritura condicional atómica en
 * persistencia; {@code status} de la convocatoria no forma parte del filtro (D1).
 */
public record CampaignFundingLedger(
        String campaignRef,
        String currency,
        long targetAmount,
        TargetPolicy targetPolicy,
        OnTargetReached onTargetReached,
        long clearedAmount
) {

    public CampaignFundingLedger {
        Objects.requireNonNull(campaignRef, "campaignRef");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(targetPolicy, "targetPolicy");
    }

    /** Ledger inicial de una configuración que acepta {@code MONETARY} (N2). */
    public static CampaignFundingLedger open(String campaignRef, ConvocatoriaConfiguration configuration) {
        if (!configuration.acceptsMonetary()) {
            throw new IllegalStateException("A ledger only exists for campaigns accepting MONETARY (N2)");
        }
        return new CampaignFundingLedger(campaignRef, configuration.currency(), configuration.targetAmount(),
                configuration.targetPolicy(), configuration.onTargetReached(), 0L);
    }

    /**
     * Si la aplicación de fondos lleva condición de capacidad: {@code STRICT} y {@code CLOSE_ON_TARGET} +
     * {@code REJECT_EXCESS} sí; {@code FLEXIBLE} y {@code CLOSE_ON_TARGET} + {@code ACCEPT_EXCESS} no
     * (ADR-037 §2.2; Enmienda §3.1, §3.3). La rama {@code CLOSE} queda fuera de corte (R4).
     */
    public boolean isCapacityLimited() {
        return switch (targetPolicy) {
            case FLEXIBLE -> false;
            case STRICT -> true;
            case CLOSE_ON_TARGET -> switch (onTargetReached) {
                case REJECT_EXCESS -> true;
                case ACCEPT_EXCESS -> false;
                case CLOSE -> throw new CloseOnTargetCloseNotSupportedException(
                        "CLOSE_ON_TARGET + CLOSE is not implemented in this cut (pending R4)");
            };
        };
    }
}
