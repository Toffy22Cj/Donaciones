package com.traceability.app.web.asset;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.api.web.CurrentActor;
import com.traceability.api.web.InvalidRequestFieldException;
import com.traceability.app.application.media.DeliveryEvidenceUnavailableException;
import com.traceability.app.application.media.EvidenceRemovedException;
import com.traceability.app.application.media.MediaObject;
import com.traceability.app.application.media.MediaObjectStore;
import com.traceability.app.application.media.MediaPurpose;
import com.traceability.app.application.media.Publication;
import com.traceability.app.application.media.PublicationDecision;
import com.traceability.app.application.media.RedactedEvidenceService;
import com.traceability.contracts.authorization.AuthorizationPrincipal;
import com.traceability.contracts.authorization.AuthorizationRole;
import com.traceability.contracts.authorization.PlatformAuthority;
import com.traceability.core.application.authorization.InsufficientRoleException;
import com.traceability.core.application.authorization.OrganizationBoundaryPolicy;
import com.traceability.core.application.exception.PhysicalAssetNotFoundException;
import com.traceability.core.application.port.out.EventStorePort;
import com.traceability.core.domain.event.DomainEvent;
import com.traceability.core.domain.physicalasset.PhysicalAsset;
import identity.application.authorization.PlatformAuthorizationPolicy;
import identity.application.authorization.PlatformCommandType;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.regex.Pattern;

/**
 * La copia censurada de la foto de entrega y su publicación (ADR-051 Enmienda 2, E2-4 a E2-6; plan de la Tarea 3, §2.3):
 * <ul>
 *   <li>{@code GET /api/v1/physical-assets/{assetRef}/evidence/redacted}: la copia (PNG), generada bajo demanda. La ven los
 *   miembros de la organización con cualquier rol y el administrador de plataforma; {@code private, no-store}; sin foto de
 *   entrega, 404; retirada por F7, 410.</li>
 *   <li>{@code PUT …/evidence/redacted/publication} con {@code {confirmNoIdentifiablePerson: true}} (204; sin la confirmación,
 *   400) y {@code DELETE …/publication} (204, también si no estaba publicada): <b>solo</b> el {@code ADMINISTRATOR} de la
 *   organización del activo. El resto —empleado, representante, otra organización y el administrador de plataforma—, el
 *   mismo 403 (DD-01), antes de mirar el cuerpo.</li>
 *   <li>{@code GET …/publication} (Q-T3-8): {@code {published, decisions[]}} sin bytes, solo para el {@code ADMINISTRATOR} de
 *   la organización.</li>
 * </ul>
 * La copia no sale por ninguna ruta pública (la del seguimiento es la Tarea 4, bloqueada). Solo traduce (ADR-041 §2.1).
 */
@RestController
public class RedactedEvidenceController {

    private static final Pattern FOOTPRINT = Pattern.compile("^sha256:[0-9a-f]{64}$");
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Una decisión del historial; todos los campos siempre presentes, la confirmación {@code null} en {@code UNPUBLISH}. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record DecisionView(String action, String at, String by, Boolean confirmNoIdentifiablePerson) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record PublicationView(boolean published, List<DecisionView> decisions) {}

    private final EventStorePort eventStore;
    private final OrganizationBoundaryPolicy boundary;
    private final PlatformAuthorizationPolicy platform;
    private final MediaObjectStore metadata;
    private final RedactedEvidenceService redacted;

    public RedactedEvidenceController(EventStorePort eventStore, OrganizationBoundaryPolicy boundary,
                                      PlatformAuthorizationPolicy platform, MediaObjectStore metadata,
                                      RedactedEvidenceService redacted) {
        this.eventStore = eventStore;
        this.boundary = boundary;
        this.platform = platform;
        this.metadata = metadata;
        this.redacted = redacted;
    }

    /** La foto de entrega del activo, tras autorizar; {@code administrator} exige el rol de la organización. */
    private MediaObject delivery(AuthorizationPrincipal principal, String assetRef, boolean administrator) {
        boolean asPlatform = principal.platformAuthority() == PlatformAuthority.ADMINISTRATOR;
        if (asPlatform && !administrator) {
            platform.authorize(principal, PlatformCommandType.READ_DELIVERY_EVIDENCE);
        }
        List<DomainEvent> events = eventStore.loadStream(assetRef);
        if (events.isEmpty()) {
            if (asPlatform && !administrator) {
                throw new DeliveryEvidenceUnavailableException();
            }
            throw new PhysicalAssetNotFoundException(assetRef);
        }
        PhysicalAsset asset = PhysicalAsset.rehydrate(assetRef, events.stream().map(DomainEvent::payload).toList(), events.size());
        if (administrator || !asPlatform) {
            // el administrador de plataforma no publica: sin organización, el mismo 403 que otra organización
            boundary.assertBelongs(principal.organizationId(), asset.getOrganizationRef());
        }
        if (administrator && (principal.roles() == null || !principal.roles().contains(AuthorizationRole.ADMINISTRATOR))) {
            throw new InsufficientRoleException("Only an ADMINISTRATOR of the organization manages the publication");
        }
        String evidenceRef = asset.getFinalEvidenceRef();
        if (evidenceRef == null || !FOOTPRINT.matcher(evidenceRef).matches()) {
            throw new DeliveryEvidenceUnavailableException();
        }
        return metadata.findByOrganizationIdAndPurposeAndSha256(asset.getOrganizationRef(), MediaPurpose.DELIVERY_EVIDENCE,
                evidenceRef.substring("sha256:".length())).orElseThrow(DeliveryEvidenceUnavailableException::new);
    }

    @GetMapping("/api/v1/physical-assets/{assetRef}/evidence/redacted")
    public ResponseEntity<byte[]> read(@CurrentActor AuthorizationPrincipal principal, @PathVariable("assetRef") String assetRef) {
        MediaObject original = delivery(principal, assetRef, false);
        if (original.deletedAt() != null) {
            throw new EvidenceRemovedException();
        }
        RedactedEvidenceService.Copy copy = redacted.getOrGenerate(original, principal.accountId());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate())
                .contentType(MediaType.parseMediaType(copy.object().contentType())).body(copy.bytes());
    }

    @PutMapping("/api/v1/physical-assets/{assetRef}/evidence/redacted/publication")
    public ResponseEntity<Void> publish(@CurrentActor AuthorizationPrincipal principal, @PathVariable("assetRef") String assetRef,
                                        @RequestBody(required = false) String body) {
        MediaObject original = delivery(principal, assetRef, true);
        requireConfirmation(body);
        if (original.deletedAt() != null) {
            throw new EvidenceRemovedException();
        }
        redacted.publish(original, principal.accountId());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/api/v1/physical-assets/{assetRef}/evidence/redacted/publication")
    public ResponseEntity<Void> unpublish(@CurrentActor AuthorizationPrincipal principal, @PathVariable("assetRef") String assetRef) {
        MediaObject original = delivery(principal, assetRef, true);
        redacted.unpublish(original, principal.accountId());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/v1/physical-assets/{assetRef}/evidence/redacted/publication")
    public ResponseEntity<PublicationView> publication(@CurrentActor AuthorizationPrincipal principal,
                                                       @PathVariable("assetRef") String assetRef) {
        MediaObject original = delivery(principal, assetRef, true);
        Publication publication = redacted.publication(original);
        List<DecisionView> decisions = publication.decisions().stream().map(RedactedEvidenceController::view).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate())
                .body(new PublicationView(publication.published(), decisions));
    }

    private static DecisionView view(PublicationDecision d) {
        return new DecisionView(d.action().name(), d.at() == null ? null : d.at().toString(), d.by(), d.confirmNoIdentifiablePerson());
    }

    /** La confirmación explícita («Revisé la copia y no se reconoce a ninguna persona»): solo el booleano {@code true}. */
    private static void requireConfirmation(String body) {
        try {
            JsonNode node = body == null || body.isBlank() ? null : JSON.readTree(body);
            if (node != null && node.path("confirmNoIdentifiablePerson").isBoolean() && node.get("confirmNoIdentifiablePerson").asBoolean()) {
                return;
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException ignored) {
            // un cuerpo que no es JSON es lo mismo que no confirmar
        }
        throw new InvalidRequestFieldException("confirmNoIdentifiablePerson");
    }
}
