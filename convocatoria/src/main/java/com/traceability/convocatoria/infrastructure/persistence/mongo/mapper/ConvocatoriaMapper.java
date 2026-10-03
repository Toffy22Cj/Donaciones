package com.traceability.convocatoria.infrastructure.persistence.mongo.mapper;

import com.traceability.convocatoria.domain.model.Convocatoria;
import com.traceability.convocatoria.domain.model.ConvocatoriaConfiguration;
import com.traceability.convocatoria.domain.model.ConvocatoriaStatus;
import com.traceability.convocatoria.domain.model.DonationType;
import com.traceability.convocatoria.domain.model.OnTargetReached;
import com.traceability.convocatoria.domain.model.PaymentMethod;
import com.traceability.convocatoria.domain.model.TargetPolicy;
import com.traceability.convocatoria.domain.model.Visibility;
import com.traceability.convocatoria.infrastructure.persistence.mongo.document.ConvocatoriaDocument;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Mapper manual {@code Convocatoria} ↔ documento (implementation_plan.md §4.3, mismo patrón que {@code identity}).
 */
public final class ConvocatoriaMapper {

    private ConvocatoriaMapper() {
    }

    public static ConvocatoriaDocument toDocument(Convocatoria c) {
        ConvocatoriaDocument d = new ConvocatoriaDocument();
        d.campaignRef = c.getCampaignRef();
        d.organizationRef = c.getOrganizationRef();
        d.publicCode = c.getPublicCode();
        d.title = c.getTitle();
        d.description = c.getDescription();
        d.visibility = c.getVisibility().name();
        d.startDate = c.getStartDate();
        d.endDate = c.getEndDate();
        d.status = c.getStatus().name();
        applyConfiguration(d, c.getConfiguration());
        d.configurationVersion = c.getConfigurationVersion();
        return d;
    }

    public static void applyConfiguration(ConvocatoriaDocument d, ConvocatoriaConfiguration cfg) {
        d.acceptedDonationTypes = cfg.acceptedDonationTypes().stream().map(Enum::name).collect(Collectors.toSet());
        d.acceptedPaymentMethods = cfg.acceptedPaymentMethods().stream().map(Enum::name).collect(Collectors.toSet());
        d.currency = cfg.currency();
        d.targetAmount = cfg.targetAmount();
        d.targetPolicy = cfg.targetPolicy() == null ? null : cfg.targetPolicy().name();
        d.onTargetReached = cfg.onTargetReached() == null ? null : cfg.onTargetReached().name();
    }

    public static Convocatoria toDomain(ConvocatoriaDocument d) {
        ConvocatoriaConfiguration cfg = new ConvocatoriaConfiguration(
                d.acceptedDonationTypes.stream().map(DonationType::valueOf).collect(Collectors.toSet()),
                d.acceptedPaymentMethods == null ? Set.of()
                        : d.acceptedPaymentMethods.stream().map(PaymentMethod::valueOf).collect(Collectors.toSet()),
                d.currency,
                d.targetAmount,
                d.targetPolicy == null ? null : TargetPolicy.valueOf(d.targetPolicy),
                d.onTargetReached == null ? null : OnTargetReached.valueOf(d.onTargetReached));
        return Convocatoria.reconstitute(d.campaignRef, d.organizationRef, d.publicCode, d.title, d.description,
                Visibility.valueOf(d.visibility), d.startDate, d.endDate, ConvocatoriaStatus.valueOf(d.status), cfg,
                d.configurationVersion);
    }
}
