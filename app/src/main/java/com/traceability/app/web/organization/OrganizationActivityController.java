package com.traceability.app.web.organization;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.traceability.api.web.CurrentActor;
import com.traceability.api.web.InvalidRequestFieldException;
import com.traceability.app.application.activity.OrganizationActivityQuery;
import com.traceability.app.application.activity.OrganizationActivityQuery.ActivityItem;
import com.traceability.app.application.activity.OrganizationActivityQuery.ActivityKey;
import com.traceability.app.application.activity.OrganizationActivityQuery.ActivityPage;
import com.traceability.app.web.campaign.DiscoveryCursorCodec;
import com.traceability.contracts.authorization.AuthorizationPrincipal;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * Actividad reciente de la organización (ficha R-6, §6, G-8):
 * {@code GET /api/v1/organizations/{organizationId}/activity?cursor=}, {@code no-store}.
 * {@code 200 {items: [{type, occurredAt, subject?: {kind, ref}, eventCount?, quantity?, unitOfMeasure?, locationZone?}],
 * nextCursor?, truncated}}, {@value OrganizationActivityQuery#PAGE_SIZE} por página ({@code truncated}: Enmienda 2 de la ficha R-6).
 * <p>
 * El cursor es opaco (DD-58): la clave completa del último ítem, {@code (instante, tipo de fuente, id)}, y la
 * organización, cifrados con la clave del descubrimiento y con un propósito propio, como DD-69. Un cursor alterado,
 * de otro propósito (el del descubrimiento o el de la cola de verificación) o de otra organización → 400. Se autoriza
 * antes de interpretar el cursor: quien no es de la organización recibe 403, nunca 400.
 */
@RestController
public class OrganizationActivityController {

    /** Propósito dentro del texto cifrado: separa estos cursores de los del descubrimiento y de la cola. */
    public static final String CURSOR_PURPOSE = "organization-activity:";

    public record SubjectResponse(String kind, String ref) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ItemResponse(String type, String occurredAt, SubjectResponse subject, Long eventCount,
                               @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal quantity, String unitOfMeasure,
                               String locationZone) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    /** {@code truncated} siempre llega (Enmienda 2 de la ficha R-6, P-E2-1): es un {@code boolean}, que NON_NULL no quita. */
    public record PageResponse(List<ItemResponse> items, String nextCursor, boolean truncated) {}

    private final OrganizationActivityQuery activity;
    private final DiscoveryCursorCodec cursors;

    public OrganizationActivityController(OrganizationActivityQuery activity, DiscoveryCursorCodec cursors) {
        this.activity = activity;
        this.cursors = cursors;
    }

    @GetMapping("/api/v1/organizations/{organizationId}/activity")
    public ResponseEntity<PageResponse> activity(@CurrentActor AuthorizationPrincipal principal,
                                                 @PathVariable("organizationId") String organizationId,
                                                 @RequestParam(name = "cursor", required = false) String cursor) {
        activity.authorize(principal, organizationId);
        ActivityKey after = cursor == null ? null : decode(cursor, organizationId);
        ActivityPage page = activity.page(principal, organizationId, after);
        List<ItemResponse> items = page.items().stream().map(OrganizationActivityController::toResponse).toList();
        String next = page.next() == null ? null : cursors.encode(CURSOR_PURPOSE + organizationId + "|"
                + page.next().instant() + "|" + page.next().source() + "|" + page.next().id());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new PageResponse(items, next, page.truncated()));
    }

    private static ItemResponse toResponse(ActivityItem i) {
        return new ItemResponse(i.type(), i.occurredAt() == null ? null : i.occurredAt().toString(),
                i.subject() == null ? null : new SubjectResponse(i.subject().kind(), i.subject().ref()),
                i.eventCount(), i.quantity(), i.unitOfMeasure(), i.locationZone());
    }

    private ActivityKey decode(String cursor, String organizationId) {
        String plain = cursors.decode(cursor).filter(p -> p.startsWith(CURSOR_PURPOSE))
                .orElseThrow(() -> new InvalidRequestFieldException("cursor"));
        String[] parts = plain.substring(CURSOR_PURPOSE.length()).split("\\|", 4);
        if (parts.length != 4 || !parts[0].equals(organizationId) || parts[3].isEmpty()) {
            throw new InvalidRequestFieldException("cursor");
        }
        try {
            Instant.parse(parts[1]);
            int source = Integer.parseInt(parts[2]);
            if (source < OrganizationActivityQuery.SOURCE_EVENT || source > OrganizationActivityQuery.SOURCE_BATCH) {
                throw new InvalidRequestFieldException("cursor");
            }
            return new ActivityKey(parts[1], source, parts[3]);
        } catch (DateTimeParseException | NumberFormatException e) {
            throw new InvalidRequestFieldException("cursor");
        }
    }
}
