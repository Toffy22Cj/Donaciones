package com.traceability.app.web.campaign;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.api.web.InvalidRequestFieldException;
import com.traceability.app.application.media.CampaignCoverService;
import com.traceability.app.application.media.OrganizationLogoService;
import com.traceability.convocatoria.application.query.PublicCampaignDiscoveryQuery;
import com.traceability.convocatoria.application.query.PublicCampaignDiscoveryQuery.Criteria;
import com.traceability.convocatoria.application.query.PublicCampaignDiscoveryQuery.Page;
import com.traceability.convocatoria.application.query.PublicCampaignDiscoveryQuery.Position;
import com.traceability.convocatoria.application.query.PublicCampaignDiscoveryQuery.PublicCampaignSummary;
import com.traceability.convocatoria.application.query.PublicCampaignDiscoveryQuery.Sort;
import com.traceability.convocatoria.application.query.SearchTextNormalizer;
import com.traceability.convocatoria.application.service.ConvocatoriaLifecycleService;
import com.traceability.convocatoria.domain.model.DonationType;
import identity.application.service.OrganizationLogoReferenceQuery;
import identity.application.service.OrganizationLogoReferenceQuery.PublicOrganization;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Descubrimiento público, {@code GET /api/v1/public/campaigns?q=&donationType=&sort=&cursor=} (P2.6; T-35:
 * {@code {items, nextCursor}}, sin {@code nextCursor} en la última página, cursor inválido → 400). Nunca lista
 * {@code PRIVATE_LINK}. Sin {@code campaignRef} ni {@code organizationRef}: solo el nombre público de la organización.
 * El cursor es opaco ({@link DiscoveryCursorCodec}, T-35; DD-53 rehecha).
 * {@code organizationVerified} se calcula con el estado actual de la organización (ficha L-FRONTS, A1; M-07).
 * {@code organizationLogoUrl} solo si la organización tiene logo (ADR-051 F6, Enmienda 1). Nombre, verificación y logo
 * salen de una sola lectura de {@code identity} por ítem (plan de la Tarea 2, P-T2-7).
 * <p>
 * Ficha R-1 (sustituye a Q-v2-3 en filtros, orden y cursor; la forma de la respuesta no cambia). Los parámetros son
 * opcionales y se validan en este orden, cada error con un solo campo, el primero que falla ({@code
 * InvalidRequestFieldException}):
 * <ol>
 *   <li>{@code q}: se recorta y debe tener de 2 a 100 puntos de código; se busca normalizado ({@link
 *       SearchTextNormalizer});</li>
 *   <li>{@code donationType}: {@code MONETARY} o {@code IN_KIND};</li>
 *   <li>{@code sort}: {@code ending_soon} o {@code recent}; sin él, {@code publicCode} ascendente, como antes;</li>
 *   <li>{@code cursor}: un v2 ({@link DiscoveryCursorPayload}) solo vale con los mismos filtros y orden que lleva
 *       dentro (comparando {@code q} normalizado); un v1 (el {@code publicCode}), solo sin filtros y sin orden.</li>
 * </ol>
 * El {@code nextCursor} que se emite es siempre v2.
 */
@RestController
public class PublicCampaignDiscoveryController {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Item(String publicCode, String title, String organizationName, boolean organizationVerified,
                       String status, String startDate, String endDate, Set<String> acceptedDonationTypes,
                       String currency, String targetAmount, String clearedAmount, String organizationLogoUrl,
                       String coverImageUrl) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Response(List<Item> items, String nextCursor) {}

    /** Ficha R-1 §3: los valores de {@code sort} en la API. */
    private static final Map<String, Sort> SORTS = Map.of("ending_soon", Sort.ENDING_SOON, "recent", Sort.RECENT);
    private static final int Q_MIN_CODE_POINTS = 2;
    private static final int Q_MAX_CODE_POINTS = 100;

    private final PublicCampaignDiscoveryQuery discovery;
    private final OrganizationLogoReferenceQuery organizations;

    private final DiscoveryCursorCodec cursors;
    private final ObjectMapper json;

    public PublicCampaignDiscoveryController(PublicCampaignDiscoveryQuery discovery,
                                             OrganizationLogoReferenceQuery organizations,
                                             DiscoveryCursorCodec cursors,
                                             ObjectMapper json) {
        this.discovery = discovery;
        this.organizations = organizations;
        this.cursors = cursors;
        this.json = json;
    }

    @GetMapping("/api/v1/public/campaigns")
    public Response discover(@RequestParam(name = "q", required = false) String q,
                             @RequestParam(name = "donationType", required = false) String donationType,
                             @RequestParam(name = "sort", required = false) String sort,
                             @RequestParam(name = "cursor", required = false) String cursor) {
        String textQuery = q == null ? null : textQuery(q);
        DonationType type = donationType == null ? null : donationType(donationType);
        Sort order = sort == null ? Sort.DEFAULT : sort(sort);
        Position after = cursor == null ? null : position(cursor, textQuery, type, order);
        Criteria criteria = new Criteria(textQuery, type, order, after);
        Page page = discovery.page(criteria);
        return new Response(page.items().stream().map(this::item).toList(),
                page.nextPosition() == null ? null : encode(criteria, page.nextPosition()));
    }

    private static String textQuery(String q) {
        String trimmed = q.strip();
        int codePoints = trimmed.codePointCount(0, trimmed.length());
        if (codePoints < Q_MIN_CODE_POINTS || codePoints > Q_MAX_CODE_POINTS) {
            throw new InvalidRequestFieldException("q");
        }
        return SearchTextNormalizer.normalize(trimmed);
    }

    private static DonationType donationType(String value) {
        if (DonationType.MONETARY.name().equals(value)) {
            return DonationType.MONETARY;
        }
        if (DonationType.IN_KIND.name().equals(value)) {
            return DonationType.IN_KIND;
        }
        throw new InvalidRequestFieldException("donationType");
    }

    private static Sort sort(String value) {
        Sort sort = SORTS.get(value);
        if (sort == null) {
            throw new InvalidRequestFieldException("sort");
        }
        return sort;
    }

    private static String sortValue(Sort sort) {
        return SORTS.entrySet().stream().filter(e -> e.getValue() == sort).map(Map.Entry::getKey).findFirst().orElse(null);
    }

    private Item item(PublicCampaignSummary s) {
        PublicOrganization o = organizations.publicOrganization(s.organizationRef());
        return new Item(s.publicCode(), s.title(), o.name(), o.verified(), s.status(), s.startDate().toString(),
                s.endDate().toString(), s.acceptedDonationTypes(), s.currency(),
                s.targetAmount() == null ? null : s.targetAmount().toString(),
                s.clearedAmount() == null ? null : s.clearedAmount().toString(),
                o.hasLogo() ? OrganizationLogoService.publicLogoUrl(s.publicCode()) : null,
                s.coverObjectKey() == null ? null : CampaignCoverService.coverUrl(s.publicCode()));
    }

    /** Siempre v2 (ficha R-1 §5), con {@code q} ya normalizado. */
    private String encode(Criteria criteria, Position next) {
        return cursors.encode(new DiscoveryCursorPayload(DiscoveryCursorPayload.VERSION, criteria.textQuery(),
                criteria.donationType() == null ? null : criteria.donationType().name(), sortValue(criteria.sort()),
                next.lastSortValue() == null ? null : next.lastSortValue().toString(), next.lastPublicCode()).toJson(json));
    }

    /**
     * Ficha R-1 §5. Un v1 (el {@code publicCode}) solo sin {@code q}, sin {@code donationType} y sin {@code sort}. Un v2
     * solo con los mismos filtros y orden que lleva, y con un {@code lastSortValue} que corresponda a ese orden.
     * Cualquier otro caso es 400 ({@code cursor}).
     */
    private Position position(String cursor, String textQuery, DonationType type, Sort order) {
        String text = cursors.decode(cursor).orElseThrow(() -> new InvalidRequestFieldException("cursor"));
        if (isPublicCode(text)) {
            if (textQuery != null || type != null || order != Sort.DEFAULT) {
                throw new InvalidRequestFieldException("cursor");
            }
            return new Position(null, text);
        }
        DiscoveryCursorPayload payload = DiscoveryCursorPayload.fromJson(text, json)
                .filter(p -> Objects.equals(p.q(), textQuery)
                        && Objects.equals(p.donationType(), type == null ? null : type.name())
                        && Objects.equals(p.sort(), sortValue(order))
                        && isPublicCode(p.lastPublicCode())
                        && (order == Sort.DEFAULT) == (p.lastSortValue() == null))
                .orElseThrow(() -> new InvalidRequestFieldException("cursor"));
        return new Position(order == Sort.DEFAULT ? null : instant(payload.lastSortValue()), payload.lastPublicCode());
    }

    private static boolean isPublicCode(String text) {
        return text != null && ConvocatoriaLifecycleService.PUBLIC_CODE_FORMAT.matcher(text).matches();
    }

    private static Instant instant(String value) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw new InvalidRequestFieldException("cursor");
        }
    }
}
