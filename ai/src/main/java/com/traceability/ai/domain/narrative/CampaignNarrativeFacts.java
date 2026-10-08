package com.traceability.ai.domain.narrative;

import com.traceability.contracts.CampaignAuditFactsDTO;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.Map;

/**
 * Lo único que recibe el LLM para narrar una convocatoria (plan B5 §2, condición 1 de Carlos): números y estados
 * deterministas. Sin título, descripción, {@code campaignRef}, {@code organizationRef}, ids de activos,
 * {@code beneficiaryRef} ni {@code donorRef}: no hay campo donde ponerlos.
 *
 * @param currency     {@code null} si la convocatoria no acepta {@code MONETARY}
 * @param targetAmount {@code null} si la convocatoria no acepta {@code MONETARY}
 * @param targetPolicy {@code null} si la convocatoria no acepta {@code MONETARY}
 * @param clearedAmount {@code null} si la convocatoria no acepta {@code MONETARY}
 */
public record CampaignNarrativeFacts(String status, String currency, BigDecimal targetAmount, String targetPolicy,
                                     BigDecimal clearedAmount, BigDecimal unitsDelivered, long distinctRecipients) {

    public static CampaignNarrativeFacts of(CampaignAuditFactsDTO dto) {
        return new CampaignNarrativeFacts(dto.status(), dto.currency(), dto.targetAmount(), dto.targetPolicy(),
                dto.clearedAmount(), dto.unitsDelivered() == null ? BigDecimal.ZERO : dto.unitsDelivered(),
                dto.distinctRecipients());
    }

    /** Valor canónico de cada hecho citable; los ausentes no están y no se pueden citar. */
    public Map<CampaignFactType, String> citable() {
        Map<CampaignFactType, String> values = new EnumMap<>(CampaignFactType.class);
        values.put(CampaignFactType.CAMPAIGN_STATUS, status);
        if (targetAmount != null) {
            values.put(CampaignFactType.TARGET_AMOUNT, plain(targetAmount));
        }
        if (clearedAmount != null) {
            values.put(CampaignFactType.CLEARED_AMOUNT, plain(clearedAmount));
        }
        values.put(CampaignFactType.UNITS_DELIVERED, plain(unitsDelivered));
        values.put(CampaignFactType.DISTINCT_RECIPIENTS, Long.toString(distinctRecipients));
        return values;
    }

    /** Forma determinista de los hechos: la base del hash de la caché (DD-34). Sin instantes de lectura. */
    public String canonical() {
        return "status=" + status + "|currency=" + currency + "|targetAmount=" + (targetAmount == null ? null : plain(targetAmount))
                + "|targetPolicy=" + targetPolicy + "|clearedAmount=" + (clearedAmount == null ? null : plain(clearedAmount))
                + "|unitsDelivered=" + plain(unitsDelivered) + "|distinctRecipients=" + distinctRecipients;
    }

    public static String plain(BigDecimal value) {
        return value.signum() == 0 ? "0" : value.stripTrailingZeros().toPlainString();
    }
}
