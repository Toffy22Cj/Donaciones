package com.traceability.app.web.campaign;

import com.traceability.api.web.CommandId;
import com.traceability.api.web.CurrentActor;
import com.traceability.app.web.campaign.CampaignDtos.AssignEmployeeRequest;
import com.traceability.app.web.campaign.CampaignDtos.AssignEmployeeResponse;
import com.traceability.app.web.campaign.CampaignDtos.ConfigurationRequest;
import com.traceability.app.web.campaign.CampaignDtos.CreateCampaignRequest;
import com.traceability.app.web.campaign.CampaignDtos.CreateCampaignResponse;
import com.traceability.app.web.campaign.CampaignDtos.CloseCampaignResponse;
import com.traceability.app.web.campaign.CampaignDtos.DesignateAdministratorRequest;
import com.traceability.app.web.campaign.CampaignDtos.RemoveResponsibleRequest;
import com.traceability.app.web.campaign.CampaignDtos.RemoveResponsibleResponse;
import com.traceability.convocatoria.application.command.AssignEmployeeToCampaignCommand;
import com.traceability.convocatoria.application.command.CloseConvocatoriaCommand;
import com.traceability.convocatoria.application.command.DesignateAdministratorAsCampaignResponsibleCommand;
import com.traceability.convocatoria.application.command.RemoveResponsibleCommand;
import com.traceability.convocatoria.application.command.RemoveResponsibleResult;
import com.traceability.convocatoria.domain.model.ActingRole;
import com.traceability.convocatoria.application.command.CreateConvocatoriaCommand;
import com.traceability.convocatoria.application.command.CreateConvocatoriaResult;
import com.traceability.convocatoria.application.service.ConvocatoriaLifecycleService;
import com.traceability.convocatoria.application.service.ResponsibleAssignmentService;
import com.traceability.convocatoria.domain.model.ConvocatoriaConfiguration;
import com.traceability.convocatoria.domain.model.DonationType;
import com.traceability.convocatoria.domain.model.OnTargetReached;
import com.traceability.convocatoria.domain.model.PaymentMethod;
import com.traceability.convocatoria.domain.model.TargetPolicy;
import com.traceability.convocatoria.domain.model.Visibility;
import com.traceability.core.domain.event.HumanActor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * CV-01 (crear convocatoria, ficha CONGELADA) y CV-02 (asignar responsable), plan B6-a §2.1–§2.2; cerrar (P2.4),
 * CV-03 (designar administrador) y retirar responsable (P2.5), segunda autorización. Cruzan módulos
 * ({@code convocatoria} y el actor del JWT), así que viven en {@code app.web} (B6-0 §2.4). La autorización y las reglas
 * las aplica {@code convocatoria}; aquí solo se traduce la forma de la petición.
 */
@RestController
public class CampaignAdministrationController {

    private final ConvocatoriaLifecycleService lifecycle;
    private final ResponsibleAssignmentService responsibles;

    public CampaignAdministrationController(ConvocatoriaLifecycleService lifecycle, ResponsibleAssignmentService responsibles) {
        this.lifecycle = lifecycle;
        this.responsibles = responsibles;
    }

    @PostMapping("/api/v1/organizations/{organizationId}/campaigns")
    @ResponseStatus(HttpStatus.CREATED)
    public CreateCampaignResponse create(@CurrentActor HumanActor actor, @CommandId String commandId,
                                         @PathVariable("organizationId") String organizationId,
                                         @RequestBody CreateCampaignRequest body) {
        CampaignRequestFields.required(body, "body");
        ConfigurationRequest c = CampaignRequestFields.required(body.configuration(), "configuration");
        ConvocatoriaConfiguration configuration = new ConvocatoriaConfiguration(
                CampaignRequestFields.enumSet(DonationType.class, c.acceptedDonationTypes(), "acceptedDonationTypes"),
                CampaignRequestFields.enumSet(PaymentMethod.class, c.acceptedPaymentMethods(), "acceptedPaymentMethods"),
                c.currency(),
                CampaignRequestFields.amount(c.targetAmount(), "targetAmount"),
                CampaignRequestFields.enumValue(TargetPolicy.class, c.targetPolicy(), "targetPolicy"),
                CampaignRequestFields.enumValue(OnTargetReached.class, c.onTargetReached(), "onTargetReached"));
        CreateConvocatoriaResult result = lifecycle.createConvocatoria(new CreateConvocatoriaCommand(commandId,
                actor.accountId(), organizationId, body.title(), body.description(),
                CampaignRequestFields.enumValue(Visibility.class, body.visibility(), "visibility"),
                CampaignRequestFields.instant(body.startDate(), "startDate"),
                CampaignRequestFields.instant(body.endDate(), "endDate"),
                configuration));
        return new CreateCampaignResponse(result.campaignRef(), result.publicCode());
    }

    @PostMapping("/api/v1/campaigns/{campaignRef}/employees")
    @ResponseStatus(HttpStatus.CREATED)
    public AssignEmployeeResponse assignEmployee(@CurrentActor HumanActor actor, @CommandId String commandId,
                                                 @PathVariable("campaignRef") String campaignRef,
                                                 @RequestBody AssignEmployeeRequest body) {
        String employeeRef = CampaignRequestFields.required(CampaignRequestFields.required(body, "body").employeeRef(),
                "employeeRef");
        return new AssignEmployeeResponse(responsibles.assignEmployee(
                new AssignEmployeeToCampaignCommand(commandId, actor.accountId(), campaignRef, employeeRef))
                .assignmentId());
    }

    /** CV-03 (P2.5). {@code 201 {assignmentId}}; mismas reglas y errores que CV-02. */
    @PostMapping("/api/v1/campaigns/{campaignRef}/administrators")
    @ResponseStatus(HttpStatus.CREATED)
    public AssignEmployeeResponse designateAdministrator(@CurrentActor HumanActor actor, @CommandId String commandId,
                                                        @PathVariable("campaignRef") String campaignRef,
                                                        @RequestBody DesignateAdministratorRequest body) {
        String administratorRef = CampaignRequestFields.required(
                CampaignRequestFields.required(body, "body").administratorRef(), "administratorRef");
        return new AssignEmployeeResponse(responsibles.designateAdministrator(
                new DesignateAdministratorAsCampaignResponsibleCommand(commandId, actor.accountId(), campaignRef,
                        administratorRef)).assignmentId());
    }

    /**
     * Retirar responsable (P2.5; ADR-037 §2.5, Enmienda §4.2). Sin cuerpo, retira sin reemplazo (409 si es el último);
     * con {@code replacementRef}, en la misma operación y con {@code replacementActingRole} obligatorio.
     */
    @PostMapping("/api/v1/campaigns/{campaignRef}/responsibles/{responsibleRef}/remove")
    public RemoveResponsibleResponse removeResponsible(@CurrentActor HumanActor actor, @CommandId String commandId,
                                                       @PathVariable("campaignRef") String campaignRef,
                                                       @PathVariable("responsibleRef") String responsibleRef,
                                                       @RequestBody(required = false) RemoveResponsibleRequest body) {
        String replacementRef = body == null ? null : body.replacementRef();
        ActingRole replacementRole = body == null ? null
                : CampaignRequestFields.enumValue(ActingRole.class, body.replacementActingRole(), "replacementActingRole");
        RemoveResponsibleResult result = responsibles.removeResponsible(new RemoveResponsibleCommand(commandId,
                actor.accountId(), campaignRef, responsibleRef, replacementRef, replacementRole));
        return new RemoveResponsibleResponse(result.removedAssignmentId(), result.replacementAssignmentId());
    }

    /** Cerrar convocatoria (P2.4; Enmienda §3.4). {@code CLOSED} es terminal: cerrar otra vez → 409. */
    @PostMapping("/api/v1/campaigns/{campaignRef}/close")
    public CloseCampaignResponse close(@CurrentActor HumanActor actor, @CommandId String commandId,
                                       @PathVariable("campaignRef") String campaignRef) {
        return new CloseCampaignResponse(lifecycle.closeConvocatoria(
                new CloseConvocatoriaCommand(commandId, actor.accountId(), campaignRef)).campaignRef(), "CLOSED");
    }
}
