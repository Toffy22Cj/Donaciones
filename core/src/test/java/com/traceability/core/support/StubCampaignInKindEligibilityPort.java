package com.traceability.core.support;

import com.traceability.contracts.campaign.CampaignInKindEligibilityPort;
import com.traceability.contracts.campaign.InKindEligibility;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Stub compartido de {@link CampaignInKindEligibilityPort} para los tests de {@code core} (plan D-CAMPAIGN, Q2). El
 * puerto es una dependencia obligatoria de {@code PhysicalAssetCommandService}: los contextos que escanean
 * {@code com.traceability.core} recogen este {@code @Component}, y los tests unitarios lo instancian a mano. Solo
 * existe en las fuentes de test de {@code core}: nunca llega al classpath de producción.
 * <p>
 * Por defecto, una convocatoria desconocida da {@link InKindEligibility#CAMPAIGN_NOT_FOUND}. Registra cada consulta,
 * para comprobar que un actor no autorizado no llega a consultar la convocatoria.
 */
@Component
public class StubCampaignInKindEligibilityPort implements CampaignInKindEligibilityPort {

    private final Map<String, InKindEligibility> answers = new ConcurrentHashMap<>();
    private final List<String> queries = new CopyOnWriteArrayList<>();

    public void answer(String campaignRef, InKindEligibility eligibility) {
        answers.put(campaignRef, eligibility);
    }

    public void reset() {
        answers.clear();
        queries.clear();
    }

    public List<String> queries() {
        return new ArrayList<>(queries);
    }

    @Override
    public InKindEligibility checkInKindEligibility(String campaignRef, String organizationRef) {
        queries.add(campaignRef);
        return answers.getOrDefault(campaignRef, InKindEligibility.CAMPAIGN_NOT_FOUND);
    }
}
