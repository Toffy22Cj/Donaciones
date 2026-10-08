package com.traceability.app.application.campaign;

import com.traceability.ai.application.service.CampaignNarrativeGenerator;
import com.traceability.ai.domain.narrative.CampaignNarrative;
import com.traceability.contracts.CampaignAuditFactsDTO;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Narrativa pública de una convocatoria por su {@code publicCode} (plan B5 §4). Devuelve la narrativa validada (o
 * "Narrativa no disponible") y los hechos públicos que la respaldan. Nunca devuelve {@code campaignRef},
 * {@code organizationRef}, ids de activos, beneficiarios ni donantes.
 */
@Service
public class PublicCampaignNarrativeUseCase {

    public record PublicFacts(String status, String currency, String targetAmount, String clearedAmount,
                              String unitsDelivered, long distinctRecipients) {}

    public record PublicNarrative(CampaignNarrative narrative, PublicFacts facts) {}

    private final CampaignAuditFactsProducer producer;
    private final CampaignNarrativeGenerator generator;

    public PublicCampaignNarrativeUseCase(CampaignAuditFactsProducer producer, CampaignNarrativeGenerator generator) {
        this.producer = producer;
        this.generator = generator;
    }

    /** Vacío si el código no existe: el mismo 404 público que CV-07. */
    public Optional<PublicNarrative> narrativeOf(String publicCode) {
        return producer.campaignRefOfPublicCode(publicCode)
                .flatMap(producer::getCampaignAuditFacts)
                .map(facts -> new PublicNarrative(generator.narrativeOf(facts), publicFacts(facts)));
    }

    private static PublicFacts publicFacts(CampaignAuditFactsDTO f) {
        return new PublicFacts(f.status(), f.currency(), plain(f.targetAmount()), plain(f.clearedAmount()),
                plain(f.unitsDelivered()), f.distinctRecipients());
    }

    private static String plain(BigDecimal value) {
        return value == null ? null : value.signum() == 0 ? "0" : value.stripTrailingZeros().toPlainString();
    }
}
