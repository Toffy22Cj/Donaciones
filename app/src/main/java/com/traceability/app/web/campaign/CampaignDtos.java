package com.traceability.app.web.campaign;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Set;

/**
 * Cuerpos de CV-01, CV-02 y CV-07 (plan B6-a §2). Fechas, importes y enums viajan como texto y los traduce
 * {@link CampaignRequestFields}, para que cualquier forma inválida sea un 400 sin eco del valor.
 */
public final class CampaignDtos {

    private CampaignDtos() {}

    /** CV-01, cuerpo anidado (Q-CV01-12). */
    public record CreateCampaignRequest(String title, String description, String visibility, String startDate,
                                        String endDate, ConfigurationRequest configuration) {}

    public record ConfigurationRequest(List<String> acceptedDonationTypes, List<String> acceptedPaymentMethods,
                                       String currency, String targetAmount, String targetPolicy,
                                       String onTargetReached) {}

    /** CV-01: exactamente estos dos campos (Q-CV01-1). */
    public record CreateCampaignResponse(String campaignRef, String publicCode) {}

    /** CV-02. */
    public record AssignEmployeeRequest(String employeeRef) {}

    /** CV-02 (Q-B6A-3, DD-06): lo que guarda el ejecutor, así que un duplicado devuelve el mismo cuerpo. */
    public record AssignEmployeeResponse(String assignmentId) {}

    /** CV-03 (P2.5): designar a un {@code ADMINISTRATOR} de la organización como responsable. */
    public record DesignateAdministratorRequest(String administratorRef) {}

    /** P2.5: retirar un responsable, con reemplazo opcional ({@code replacementActingRole} obligatorio si lo hay). */
    public record RemoveResponsibleRequest(String replacementRef, String replacementActingRole) {}

    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record RemoveResponsibleResponse(String removedAssignmentId, String replacementAssignmentId) {}

    /** P2.4: cerrar convocatoria. {@code CLOSED} es terminal. */
    public record CloseCampaignResponse(String campaignRef, String status) {}

    /** CV-07 (plan B6-a §2.3; DD-03). Importes como texto (T-34); nulos omitidos (Q-B60-2). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PublicCampaignResponse(String organizationName, String title, String description, String status,
                                         String startDate, String endDate, Set<String> acceptedDonationTypes,
                                         Set<String> acceptedPaymentMethods, String currency, String targetAmount,
                                         String clearedAmount) {}
}
