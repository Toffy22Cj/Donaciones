package com.traceability.convocatoria.application.query;

import com.traceability.convocatoria.application.port.out.CampaignFundingLedgerRepositoryPort;
import com.traceability.convocatoria.application.port.out.ConvocatoriaRepositoryPort;
import com.traceability.convocatoria.domain.model.CampaignFundingLedger;
import com.traceability.convocatoria.domain.model.Convocatoria;
import com.traceability.convocatoria.domain.model.ConvocatoriaConfiguration;
import com.traceability.convocatoria.domain.model.DonationType;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Descubrimiento público (P2.6): convocatorias {@code PUBLIC} y {@code OPEN}. <b>Nunca</b> {@code PRIVATE_LINK}
 * (§3.3): el filtro está en la consulta. {@code organizationRef} es interno: lo usa {@code app} para el nombre público
 * y no sale.
 * <p>
 * Ficha R-1 (sustituye a Q-v2-3 en filtros, orden y cursor): búsqueda por subcadena en el título ({@code textQuery},
 * ya normalizado con {@link SearchTextNormalizer}), tipo de donación aceptado y orden ({@link Sort}). Cada página
 * continúa estrictamente después de la última devuelta ({@link Position}, §5).
 */
@Component
public class PublicCampaignDiscoveryQuery {

    public static final int PAGE_SIZE = 20;

    public record PublicCampaignSummary(String organizationRef, String publicCode, String title, String status,
                                        Instant startDate, Instant endDate, Set<String> acceptedDonationTypes,
                                        String currency, Long targetAmount, Long clearedAmount,
                                        /** Interna (ADR-051, Tarea 3; P-T3-2): {@code app} da {@code coverImageUrl} si no es nula. */
                                        String coverObjectKey) {}

    /**
     * Ficha R-1 §3: sin orden, {@code publicCode} ascendente (como antes); {@code ENDING_SOON}, {@code endDate}
     * ascendente; {@code RECENT}, {@code startDate} descendente. El desempate es siempre {@code publicCode} ascendente.
     */
    public enum Sort { DEFAULT, ENDING_SOON, RECENT }

    /**
     * La última convocatoria devuelta (ficha R-1 §5): {@code lastSortValue} es su {@code endDate} o su {@code startDate}
     * según el orden, y nulo con el orden por defecto.
     */
    public record Position(Instant lastSortValue, String lastPublicCode) {}

    /**
     * {@code textQuery} y {@code donationType} son nulos si no se filtra por ellos; {@code after}, nulo en la primera
     * página. Un {@code sort} nulo es {@link Sort#DEFAULT}.
     */
    public record Criteria(String textQuery, DonationType donationType, Sort sort, Position after) {
        public Criteria {
            sort = sort == null ? Sort.DEFAULT : sort;
        }
    }

    /** {@code nextPosition} es {@code null} si no hay más páginas. */
    public record Page(List<PublicCampaignSummary> items, Position nextPosition) {}

    private final ConvocatoriaRepositoryPort convocatorias;
    private final CampaignFundingLedgerRepositoryPort ledgers;

    public PublicCampaignDiscoveryQuery(ConvocatoriaRepositoryPort convocatorias, CampaignFundingLedgerRepositoryPort ledgers) {
        this.convocatorias = convocatorias;
        this.ledgers = ledgers;
    }

    public Page page(Criteria criteria) {
        List<Convocatoria> found = convocatorias.findPublicOpenPage(criteria, PAGE_SIZE + 1);
        boolean more = found.size() > PAGE_SIZE;
        List<PublicCampaignSummary> items = found.stream().limit(PAGE_SIZE).map(this::summary).toList();
        return new Page(items, more ? positionOf(items.get(items.size() - 1), criteria.sort()) : null);
    }

    private static Position positionOf(PublicCampaignSummary last, Sort sort) {
        Instant sortValue = switch (sort) {
            case DEFAULT -> null;
            case ENDING_SOON -> last.endDate();
            case RECENT -> last.startDate();
        };
        return new Position(sortValue, last.publicCode());
    }

    private PublicCampaignSummary summary(Convocatoria c) {
        ConvocatoriaConfiguration config = c.getConfiguration();
        boolean monetary = config.acceptsMonetary();
        return new PublicCampaignSummary(c.getOrganizationRef(), c.getPublicCode(), c.getTitle(), c.getStatus().name(),
                c.getStartDate(), c.getEndDate(),
                config.acceptedDonationTypes().stream().map(Enum::name).collect(Collectors.toCollection(TreeSet::new)),
                monetary ? config.currency() : null, monetary ? config.targetAmount() : null,
                monetary ? ledgers.findByCampaignRef(c.getCampaignRef()).map(CampaignFundingLedger::clearedAmount).orElse(0L) : null,
                c.getCoverObjectKey());
    }
}
