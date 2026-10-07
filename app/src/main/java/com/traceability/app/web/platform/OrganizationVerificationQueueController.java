package com.traceability.app.web.platform;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.traceability.api.web.CurrentActor;
import com.traceability.api.web.InvalidRequestFieldException;
import com.traceability.app.web.campaign.DiscoveryCursorCodec;
import com.traceability.contracts.authorization.AuthorizationPrincipal;
import identity.application.service.OrganizationVerificationQueueQuery;
import identity.domain.model.VerificationStatus;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Cola de verificación (autorización (3) de Carlos, §3.1; Q-v2-2): {@code GET /api/v1/platform/organizations}. Solo
 * el administrador de plataforma; el resto, el mismo 403.
 * <ul>
 *   <li>{@code ?status=}: {@code PENDING_VERIFICATION} o {@code NEEDS_MORE_INFORMATION}; sin él, las dos. Otro valor →
 *   400;</li>
 *   <li>{@code ?cursor=}: opaco (T-35), cifrado con la clave del descubrimiento y con un propósito propio, así que un
 *   cursor del descubrimiento no vale aquí ni al revés. Inválido → 400;</li>
 *   <li>{@code 200 {items: [{organizationId, name?, type, verificationStatus, informationRequest?}], nextCursor?}},
 *   {@value #PAGE_SIZE} por página, por orden de creación; sin miembros ni emails.</li>
 * </ul>
 */
@RestController
public class OrganizationVerificationQueueController {

    static final int PAGE_SIZE = 20;
    /** Propósito dentro del texto cifrado: separa estos cursores de los del descubrimiento. */
    public static final String CURSOR_PURPOSE = "verification-queue:";
    private static final Pattern ULID = Pattern.compile("[0-9A-HJKMNP-TV-Z]{26}");

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Item(String organizationId, String name, String type, String verificationStatus,
                       String informationRequest) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Page(List<Item> items, String nextCursor) {}

    private final OrganizationVerificationQueueQuery queue;
    private final DiscoveryCursorCodec cursors;

    public OrganizationVerificationQueueController(OrganizationVerificationQueueQuery queue, DiscoveryCursorCodec cursors) {
        this.queue = queue;
        this.cursors = cursors;
    }

    @GetMapping("/api/v1/platform/organizations")
    public ResponseEntity<Page> list(@CurrentActor AuthorizationPrincipal principal,
                                     @RequestParam(name = "status", required = false) String status,
                                     @RequestParam(name = "cursor", required = false) String cursor) {
        // autorizar antes de interpretar la entrada: quien no es de la plataforma recibe 403, nunca 400
        queue.authorize(principal);
        Set<VerificationStatus> statuses = statuses(status);
        String after = cursor == null ? null : decode(cursor);
        OrganizationVerificationQueueQuery.QueuePage page = queue.page(principal, statuses, after, PAGE_SIZE);
        List<Item> items = page.items().stream().map(i -> new Item(i.organizationId(), i.name(), i.type(),
                i.verificationStatus(), i.informationRequest())).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new Page(items, page.next() == null ? null : cursors.encode(CURSOR_PURPOSE + page.next())));
    }

    private static Set<VerificationStatus> statuses(String status) {
        if (status == null) {
            return OrganizationVerificationQueueQuery.PENDING;
        }
        try {
            VerificationStatus parsed = VerificationStatus.valueOf(status);
            if (OrganizationVerificationQueueQuery.PENDING.contains(parsed)) {
                return EnumSet.of(parsed);
            }
        } catch (IllegalArgumentException e) {
            // cae al 400
        }
        throw new InvalidRequestFieldException("status");
    }

    private String decode(String cursor) {
        return cursors.decode(cursor)
                .filter(plain -> plain.startsWith(CURSOR_PURPOSE))
                .map(plain -> plain.substring(CURSOR_PURPOSE.length()))
                .filter(id -> ULID.matcher(id).matches())
                .orElseThrow(() -> new InvalidRequestFieldException("cursor"));
    }
}
