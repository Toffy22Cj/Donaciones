package com.traceability.app.web.campaign;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.traceability.api.web.CommandId;
import com.traceability.api.web.CurrentActor;
import com.traceability.api.web.InvalidRequestFieldException;
import com.traceability.app.web.campaign.CampaignDtos.ConfigurationRequest;
import com.traceability.convocatoria.application.command.EditConfigurationCommand;
import com.traceability.convocatoria.application.command.EditConfigurationResult;
import com.traceability.convocatoria.application.service.ConfigurationChangeService;
import com.traceability.convocatoria.application.service.ConvocatoriaLifecycleService;
import com.traceability.convocatoria.domain.model.ConfigurationChangeRequest;
import com.traceability.convocatoria.domain.model.ConvocatoriaConfiguration;
import com.traceability.convocatoria.domain.model.DonationType;
import com.traceability.convocatoria.domain.model.OnTargetReached;
import com.traceability.convocatoria.domain.model.PaymentMethod;
import com.traceability.convocatoria.domain.model.TargetPolicy;
import com.traceability.core.domain.event.HumanActor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * Configuración de una convocatoria (Enmienda 1 de ADR-037, §3.2; Enmienda 4, D1–D2; autorización (3) de Carlos,
 * §3.5). Todas con {@code Command-Id}. Convocatoria inexistente, otra organización o sin el rol → el mismo 403.
 * <ul>
 *   <li>{@code POST /campaigns/{ref}/configuration {expectedConfigurationVersion, configuration}} → edición directa,
 *   solo sin donaciones ({@code ADMINISTRATOR});</li>
 *   <li>{@code POST /campaigns/{ref}/configuration-change-requests} → solicitud ({@code ADMINISTRATOR});</li>
 *   <li>{@code GET /campaigns/{ref}/configuration-change-requests} → listado ({@code ADMINISTRATOR} o
 *   {@code REPRESENTATIVE});</li>
 *   <li>{@code POST …/{requestId}/approve} y {@code …/reject} → otro {@code ADMINISTRATOR} o el {@code REPRESENTATIVE};
 *   aprobar la propia → 403; el solicitante puede retirar la suya.</li>
 * </ul>
 */
@RestController
public class CampaignConfigurationController {

    public record ConfigurationChangeBody(Long expectedConfigurationVersion, ConfigurationRequest configuration) {}

    public record ConfigurationVersionResponse(String campaignRef, long configurationVersion) {}

    public record RequestCreatedResponse(String requestId, String status, long baseConfigurationVersion) {}

    public record DecisionResponse(String requestId, String status, Long configurationVersion) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Configuration(List<String> acceptedDonationTypes, List<String> acceptedPaymentMethods, String currency,
                                String targetAmount, String targetPolicy, String onTargetReached) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RequestItem(String requestId, String status, long baseConfigurationVersion,
                              Configuration proposedConfiguration, String requestedBy, Instant requestedAt,
                              String decidedBy, Instant decidedAt, Long resultingConfigurationVersion) {}

    public record RequestPage(List<RequestItem> items) {}

    private final ConvocatoriaLifecycleService lifecycle;
    private final ConfigurationChangeService changes;

    public CampaignConfigurationController(ConvocatoriaLifecycleService lifecycle, ConfigurationChangeService changes) {
        this.lifecycle = lifecycle;
        this.changes = changes;
    }

    @PostMapping("/api/v1/campaigns/{campaignRef}/configuration")
    public ConfigurationVersionResponse edit(@CurrentActor HumanActor actor, @CommandId String commandId,
                                             @PathVariable("campaignRef") String campaignRef,
                                             @RequestBody(required = false) ConfigurationChangeBody body) {
        EditConfigurationResult result = lifecycle.editConfiguration(new EditConfigurationCommand(commandId,
                actor.accountId(), campaignRef, version(body), configuration(body)));
        return new ConfigurationVersionResponse(result.campaignRef(), result.configurationVersion());
    }

    @PostMapping("/api/v1/campaigns/{campaignRef}/configuration-change-requests")
    @ResponseStatus(HttpStatus.CREATED)
    public RequestCreatedResponse request(@CurrentActor HumanActor actor, @CommandId String commandId,
                                          @PathVariable("campaignRef") String campaignRef,
                                          @RequestBody(required = false) ConfigurationChangeBody body) {
        ConfigurationChangeService.Requested requested = changes.request(commandId, actor.accountId(), campaignRef,
                version(body), configuration(body));
        return new RequestCreatedResponse(requested.requestId(), "PENDING", requested.baseConfigurationVersion());
    }

    @GetMapping("/api/v1/campaigns/{campaignRef}/configuration-change-requests")
    public ResponseEntity<RequestPage> list(@CurrentActor HumanActor actor,
                                            @PathVariable("campaignRef") String campaignRef) {
        List<RequestItem> items = changes.list(actor.accountId(), campaignRef).stream()
                .map(CampaignConfigurationController::item).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new RequestPage(items));
    }

    @PostMapping("/api/v1/campaigns/{campaignRef}/configuration-change-requests/{requestId}/approve")
    public DecisionResponse approve(@CurrentActor HumanActor actor, @CommandId String commandId,
                                    @PathVariable("campaignRef") String campaignRef,
                                    @PathVariable("requestId") String requestId) {
        ConfigurationChangeService.Approved approved = changes.approve(commandId, actor.accountId(), campaignRef, requestId);
        return new DecisionResponse(approved.requestId(), "APPROVED", approved.configurationVersion());
    }

    @PostMapping("/api/v1/campaigns/{campaignRef}/configuration-change-requests/{requestId}/reject")
    public DecisionResponse reject(@CurrentActor HumanActor actor, @CommandId String commandId,
                                   @PathVariable("campaignRef") String campaignRef,
                                   @PathVariable("requestId") String requestId) {
        changes.reject(commandId, actor.accountId(), campaignRef, requestId);
        return new DecisionResponse(requestId, "REJECTED", null);
    }

    private static long version(ConfigurationChangeBody body) {
        if (body == null || body.expectedConfigurationVersion() == null || body.expectedConfigurationVersion() < 1) {
            throw new InvalidRequestFieldException("expectedConfigurationVersion");
        }
        return body.expectedConfigurationVersion();
    }

    /** La misma configuración que CV-01 y las mismas validaciones con nombre (400). */
    private static ConvocatoriaConfiguration configuration(ConfigurationChangeBody body) {
        ConfigurationRequest c = CampaignRequestFields.required(body.configuration(), "configuration");
        return new ConvocatoriaConfiguration(
                CampaignRequestFields.enumSet(DonationType.class, c.acceptedDonationTypes(), "acceptedDonationTypes"),
                CampaignRequestFields.enumSet(PaymentMethod.class, c.acceptedPaymentMethods(), "acceptedPaymentMethods"),
                c.currency(),
                CampaignRequestFields.amount(c.targetAmount(), "targetAmount"),
                CampaignRequestFields.enumValue(TargetPolicy.class, c.targetPolicy(), "targetPolicy"),
                CampaignRequestFields.enumValue(OnTargetReached.class, c.onTargetReached(), "onTargetReached"));
    }

    private static RequestItem item(ConfigurationChangeRequest r) {
        ConvocatoriaConfiguration c = r.proposedConfiguration();
        Configuration proposed = new Configuration(
                c.acceptedDonationTypes().stream().map(Enum::name).sorted().toList(),
                c.acceptedPaymentMethods().isEmpty() ? null : c.acceptedPaymentMethods().stream().map(Enum::name).sorted().toList(),
                c.currency(), c.targetAmount() == null ? null : String.valueOf(c.targetAmount()),
                c.targetPolicy() == null ? null : c.targetPolicy().name(),
                c.onTargetReached() == null ? null : c.onTargetReached().name());
        return new RequestItem(r.requestId(), r.status().name(), r.baseConfigurationVersion(), proposed, r.requestedBy(),
                r.requestedAt(), r.decidedBy(), r.decidedAt(), r.resultingConfigurationVersion());
    }
}
