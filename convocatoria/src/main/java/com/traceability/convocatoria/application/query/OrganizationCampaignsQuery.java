package com.traceability.convocatoria.application.query;

import com.traceability.convocatoria.application.authorization.ConvocatoriaAuthorizationPolicy;
import com.traceability.convocatoria.application.port.out.CampaignAssignmentRepositoryPort;
import com.traceability.convocatoria.application.port.out.CampaignFundingLedgerRepositoryPort;
import com.traceability.convocatoria.application.port.out.ConvocatoriaRepositoryPort;
import com.traceability.convocatoria.domain.model.ActingRole;
import com.traceability.convocatoria.domain.model.AssignmentStatus;
import com.traceability.convocatoria.domain.model.CampaignAssignment;
import com.traceability.convocatoria.domain.model.CampaignFundingLedger;
import com.traceability.convocatoria.domain.model.Convocatoria;
import com.traceability.convocatoria.domain.model.ConvocatoriaConfiguration;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/**
 * Listado administrativo de las convocatorias de una organización (matriz §2b, {@code ConvocatoriaAdminReadModel};
 * P2.3). Solo {@code ADMINISTRATOR} de la organización ({@link ConvocatoriaAuthorizationPolicy}). Una página con tope
 * de {@value #MAX_ITEMS}, como DD-21.
 */
@Component
public class OrganizationCampaignsQuery {

    public static final int MAX_ITEMS = 100;

    public record Responsible(String accountId, String actingRole) {}

    /**
     * Sin {@code fullName} de los responsables: identity no guarda nombres de cuenta (hallazgo H-P2-2).
     * {@code currency}, {@code targetAmount}, {@code targetPolicy} y {@code clearedAmount} solo con {@code MONETARY}.
     */
    public record AdminCampaignView(String campaignRef, String publicCode, String title, String status,
                                    String visibility, String currency, Long targetAmount, String targetPolicy,
                                    Long clearedAmount, List<Responsible> responsibles, long assignedEmployeeCount) {}

    private final ConvocatoriaAuthorizationPolicy authorization;
    private final ConvocatoriaRepositoryPort convocatorias;
    private final CampaignFundingLedgerRepositoryPort ledgers;
    private final CampaignAssignmentRepositoryPort assignments;

    public OrganizationCampaignsQuery(ConvocatoriaAuthorizationPolicy authorization, ConvocatoriaRepositoryPort convocatorias,
                                      CampaignFundingLedgerRepositoryPort ledgers,
                                      CampaignAssignmentRepositoryPort assignments) {
        this.authorization = authorization;
        this.convocatorias = convocatorias;
        this.ledgers = ledgers;
        this.assignments = assignments;
    }

    public List<AdminCampaignView> list(String actorAccountId, String organizationRef) {
        authorization.requireAdministratorOf(actorAccountId, organizationRef);
        return convocatorias.findByOrganizationRef(organizationRef, MAX_ITEMS).stream().map(this::view).toList();
    }

    private AdminCampaignView view(Convocatoria c) {
        ConvocatoriaConfiguration config = c.getConfiguration();
        boolean monetary = config.acceptsMonetary();
        List<CampaignAssignment> active = assignments.findByCampaignRef(c.getCampaignRef()).stream()
                .filter(a -> a.getStatus() == AssignmentStatus.ACTIVE)
                .sorted(Comparator.comparing(CampaignAssignment::getAssignedAt))
                .toList();
        return new AdminCampaignView(c.getCampaignRef(), c.getPublicCode(), c.getTitle(), c.getStatus().name(),
                c.getVisibility().name(),
                monetary ? config.currency() : null,
                monetary ? config.targetAmount() : null,
                monetary ? config.targetPolicy().name() : null,
                monetary ? ledgers.findByCampaignRef(c.getCampaignRef()).map(CampaignFundingLedger::clearedAmount).orElse(0L) : null,
                active.stream().map(a -> new Responsible(a.getEmployeeRef(), a.getActingRole().name())).toList(),
                active.stream().filter(a -> a.getActingRole() == ActingRole.EMPLOYEE).count());
    }
}
