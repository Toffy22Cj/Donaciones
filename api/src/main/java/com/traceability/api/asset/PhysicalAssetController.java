package com.traceability.api.asset;

import com.traceability.api.asset.PhysicalAssetDtos.DeliverRequest;
import com.traceability.api.asset.PhysicalAssetDtos.DispatchRequest;
import com.traceability.api.asset.PhysicalAssetDtos.OperationalResponse;
import com.traceability.api.asset.PhysicalAssetDtos.ReceiveRequest;
import com.traceability.api.asset.PhysicalAssetDtos.RegisterFromDonationRequest;
import com.traceability.api.asset.PhysicalAssetDtos.RegisterRequest;
import com.traceability.api.asset.PhysicalAssetDtos.RegisteredResponse;
import com.traceability.api.asset.PhysicalAssetDtos.SplitAcceptedResponse;
import com.traceability.api.asset.PhysicalAssetDtos.SplitRequest;
import com.traceability.api.asset.PhysicalAssetDtos.SplitStatusResponse;
import com.traceability.api.asset.PhysicalAssetDtos.TransitionResponse;
import com.traceability.api.web.CommandId;
import com.traceability.api.web.CurrentActor;
import com.traceability.api.web.InvalidRequestFieldException;
import com.traceability.contracts.authorization.AuthorizationPrincipal;
import com.traceability.core.application.command.PhysicalAssetCommandService;
import com.traceability.core.application.command.RegisteredAsset;
import com.traceability.core.application.port.out.PhysicalAssetOperationalReadPort;
import com.traceability.core.application.port.out.PhysicalAssetOperationalView;
import com.traceability.core.domain.event.HumanActor;
import com.traceability.core.domain.physicalasset.AssetLifecycleStatus;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Clock;
import java.util.UUID;

/**
 * Activos y división por HTTP (plan B6-c §2.2; matriz §4 y §4b). Solo usa {@code core}, así que vive en {@code api}
 * (Q1 de D-API). Todas las rutas exigen JWT; los comandos, {@code Command-Id}. La autorización (rol y frontera de
 * organización) la hace {@code core}; aquí solo se valida la forma de la petición.
 */
@RestController
@RequestMapping(PhysicalAssetController.BASE)
public class PhysicalAssetController {

    public static final String BASE = "/api/v1/physical-assets";

    private final PhysicalAssetCommandService assets;
    private final PhysicalAssetOperationalReadPort reads;
    private final Clock clock;

    public PhysicalAssetController(PhysicalAssetCommandService assets, PhysicalAssetOperationalReadPort reads,
                                   org.springframework.beans.factory.ObjectProvider<Clock> clock) {
        this.assets = assets;
        this.reads = reads;
        this.clock = clock.getIfAvailable(Clock::systemUTC);
    }

    /** Camino A: compra con el {@code Fund}. La organización es la del JWT (DD-10). */
    @PostMapping("/register")
    public ResponseEntity<RegisteredResponse> register(@CurrentActor AuthorizationPrincipal principal,
                                                       @CommandId String commandId,
                                                       @RequestBody RegisterRequest body) {
        RegisterRequest b = requireBody(body);
        RegisteredAsset registered = assets.registerPhysicalAsset(commandId,
                RequestFields.required(b.fundId(), "fundId"),
                organizationOf(principal),
                RequestFields.required(b.assetType(), "assetType"),
                RequestFields.quantity(b.quantity(), "quantity"),
                RequestFields.required(b.unitOfMeasure(), "unitOfMeasure"),
                RequestFields.required(b.custodianRef(), "custodianRef"),
                RequestFields.required(b.currentLocation(), "currentLocation"),
                RequestFields.required(b.allocationId(), "allocationId"),
                RequestFields.optional(b.sourceAllocationId(), "sourceAllocationId"),
                new HumanActor(principal.accountId()));
        return ResponseEntity.status(HttpStatus.CREATED).body(registered(registered));
    }

    /**
     * Camino B: donación en especie. El {@code donorRef} lo genera el servidor, opaco y sin datos personales (DD-09,
     * `[DECISIÓN DELEGADA — pendiente de ratificar por Carlos]`); el cliente no lo envía.
     */
    @PostMapping("/from-donation")
    public ResponseEntity<RegisteredResponse> registerFromDonation(@CurrentActor AuthorizationPrincipal principal,
                                                                   @CommandId String commandId,
                                                                   @RequestBody RegisterFromDonationRequest body) {
        RegisterFromDonationRequest b = requireBody(body);
        RegisteredAsset registered = assets.registerPhysicalAssetFromDonation(commandId,
                organizationOf(principal),
                "anon:" + UUID.randomUUID(),
                RequestFields.required(b.assetType(), "assetType"),
                RequestFields.quantity(b.quantity(), "quantity"),
                RequestFields.required(b.unitOfMeasure(), "unitOfMeasure"),
                RequestFields.required(b.custodianRef(), "custodianRef"),
                RequestFields.required(b.currentLocation(), "currentLocation"),
                RequestFields.optional(b.campaignRef(), "campaignRef"),
                new HumanActor(principal.accountId()));
        return ResponseEntity.status(HttpStatus.CREATED).body(registered(registered));
    }

    /** El hijo nace de forma asíncrona (saga): {@code 202} y el recurso de estado en {@code Location}. */
    @PostMapping("/{assetRef}/split")
    public ResponseEntity<SplitAcceptedResponse> split(@CurrentActor HumanActor actor, @CommandId String commandId,
                                                       @PathVariable("assetRef") String assetRef,
                                                       @RequestBody SplitRequest body) {
        String childAssetRef = assets.splitPhysicalAsset(commandId, assetRef,
                RequestFields.quantity(requireBody(body).quantity(), "quantity"), actor);
        return ResponseEntity.accepted()
                .location(URI.create(BASE + "/" + assetRef + "/splits/" + childAssetRef))
                .body(new SplitAcceptedResponse(assetRef, childAssetRef, "PENDING"));
    }

    @GetMapping("/{assetRef}/splits/{childAssetRef}")
    public SplitStatusResponse splitStatus(@CurrentActor HumanActor actor,
                                           @PathVariable("assetRef") String assetRef,
                                           @PathVariable("childAssetRef") String childAssetRef) {
        return reads.findSplitStatus(assetRef, childAssetRef, actor)
                .map(status -> new SplitStatusResponse(status.name()))
                .orElseThrow(() -> new SplitNotFoundException(childAssetRef));
    }

    @PostMapping("/{assetRef}/dispatch")
    public TransitionResponse dispatch(@CurrentActor HumanActor actor, @CommandId String commandId,
                                       @PathVariable("assetRef") String assetRef,
                                       @RequestBody DispatchRequest body) {
        assets.dispatchAsset(commandId, assetRef, RequestFields.required(requireBody(body).carrierRef(), "carrierRef"),
                actor);
        return new TransitionResponse(assetRef, AssetLifecycleStatus.DISPATCHED.name());
    }

    @PostMapping("/{assetRef}/receive")
    public TransitionResponse receive(@CurrentActor HumanActor actor, @CommandId String commandId,
                                      @PathVariable("assetRef") String assetRef,
                                      @RequestBody ReceiveRequest body) {
        ReceiveRequest b = requireBody(body);
        assets.receiveAsset(commandId, assetRef, RequestFields.required(b.facilityLocation(), "facilityLocation"),
                RequestFields.required(b.receiverRef(), "receiverRef"), actor);
        return new TransitionResponse(assetRef, AssetLifecycleStatus.RECEIVED.name());
    }

    /** {@code deliveredAt} es la hora del servidor (DD-15). */
    @PostMapping("/{assetRef}/deliver")
    public TransitionResponse deliver(@CurrentActor HumanActor actor, @CommandId String commandId,
                                      @PathVariable("assetRef") String assetRef,
                                      @RequestBody DeliverRequest body) {
        DeliverRequest b = requireBody(body);
        assets.deliverAsset(commandId, assetRef,
                RequestFields.required(b.finalCustodianRef(), "finalCustodianRef"),
                RequestFields.required(b.beneficiaryRef(), "beneficiaryRef"),
                RequestFields.required(b.locationRef(), "locationRef"),
                RequestFields.required(b.evidenceRef(), "evidenceRef"),
                clock.instant(), actor);
        return new TransitionResponse(assetRef, AssetLifecycleStatus.DELIVERED.name());
    }

    @GetMapping("/{assetRef}")
    public OperationalResponse operational(@CurrentActor HumanActor actor, @PathVariable("assetRef") String assetRef) {
        PhysicalAssetOperationalView v = reads.findOperationalView(assetRef, actor);
        return new OperationalResponse(v.assetRef(), v.lifecycleStatus(), v.currentCustodianRef(), v.currentLocation(),
                v.quantity().toPlainString(), v.unitOfMeasure(), v.campaignRef());
    }

    private static RegisteredResponse registered(RegisteredAsset r) {
        return new RegisteredResponse(r.assetId(), AssetLifecycleStatus.REGISTERED.name(), r.donationRef(),
                r.campaignRef());
    }

    /** Sin organización no hay a qué asociar el activo; la frontera de {@code core} lo rechazaría igual (403). */
    private static String organizationOf(AuthorizationPrincipal principal) {
        if (principal.organizationId() == null) {
            throw new com.traceability.core.application.authorization.CrossOrganizationAccessException(
                    "principal without organization");
        }
        return principal.organizationId();
    }

    private static <T> T requireBody(T body) {
        if (body == null) {
            throw new InvalidRequestFieldException("body");
        }
        return body;
    }
}
