package identity.application.service;

import com.traceability.contracts.campaign.CampaignResponsibilityPort;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * [Plan] Doble de {@link CampaignResponsibilityPort} para los tests de identity (plan D6 §5): en identity no hay
 * convocatorias, así que el test fija qué cuentas son responsables activas. Por defecto, ninguna.
 */
public class StubCampaignResponsibilityPort implements CampaignResponsibilityPort {

    private final Map<String, Set<String>> activeRoles = new HashMap<>();

    public void markActiveResponsible(String organizationId, String accountId, String role) {
        activeRoles.computeIfAbsent(organizationId + "/" + accountId, k -> new HashSet<>()).add(role);
    }

    public void reset() {
        activeRoles.clear();
    }

    @Override
    public Set<String> activeActingRoles(String organizationId, String accountId) {
        return Set.copyOf(activeRoles.getOrDefault(organizationId + "/" + accountId, Set.of()));
    }
}
