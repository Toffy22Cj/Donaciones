package com.traceability.convocatoria.application.service;

import com.traceability.contracts.campaign.InKindEligibility;
import com.traceability.convocatoria.application.command.CloseConvocatoriaCommand;
import com.traceability.convocatoria.domain.model.ConvocatoriaConfiguration;
import com.traceability.convocatoria.domain.model.DonationType;
import com.traceability.convocatoria.domain.model.PaymentMethod;
import com.traceability.convocatoria.domain.model.TargetPolicy;
import com.traceability.convocatoria.support.AbstractConvocatoriaServiceIntegrationTest;
import com.traceability.convocatoria.support.ConvocatoriaScenarios;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ADR-029 Enmienda 1, D3: los cinco resultados del puerto que consume {@code core} para el Camino B. */
class CampaignInKindEligibilityServiceIntegrationTest extends AbstractConvocatoriaServiceIntegrationTest {

    @Autowired private ConvocatoriaLifecycleService lifecycle;
    @Autowired private CampaignInKindEligibilityService eligibility;

    private String campaign(ConvocatoriaConfiguration cfg) {
        return ConvocatoriaScenarios.createConvocatoria(lifecycle, ADMIN, ORG, cfg);
    }

    @Test
    void openCampaignAcceptingInKind_ofTheSameOrganization_isEligible() {
        assertEquals(InKindEligibility.ELIGIBLE, eligibility.checkInKindEligibility(campaign(inKindOnly()), ORG));
    }

    @Test
    void mixedCampaign_acceptingMonetaryAndInKind_isEligible() {
        ConvocatoriaConfiguration mixed = new ConvocatoriaConfiguration(
                EnumSet.of(DonationType.MONETARY, DonationType.IN_KIND), Set.of(PaymentMethod.BANK_TRANSFER), "COP",
                1000L, TargetPolicy.FLEXIBLE, null);

        assertEquals(InKindEligibility.ELIGIBLE, eligibility.checkInKindEligibility(campaign(mixed), ORG));
    }

    @Test
    void unknownCampaign_isNotFound() {
        assertEquals(InKindEligibility.CAMPAIGN_NOT_FOUND, eligibility.checkInKindEligibility("no-existe", ORG));
    }

    @Test
    void campaignOfAnotherOrganization_isOtherOrganization() {
        assertEquals(InKindEligibility.OTHER_ORGANIZATION,
                eligibility.checkInKindEligibility(campaign(inKindOnly()), OTHER_ORG));
    }

    @Test
    void closedCampaign_isClosed() {
        String campaignRef = campaign(inKindOnly());
        lifecycle.closeConvocatoria(new CloseConvocatoriaCommand(newCommandId(), ADMIN, campaignRef));

        assertEquals(InKindEligibility.CAMPAIGN_CLOSED, eligibility.checkInKindEligibility(campaignRef, ORG));
    }

    @Test
    void monetaryOnlyCampaign_doesNotAcceptInKind() {
        assertEquals(InKindEligibility.IN_KIND_NOT_ACCEPTED,
                eligibility.checkInKindEligibility(campaign(flexible()), ORG));
    }

    @Test
    void acceptsInKind_reflectsTheConfiguredDonationTypes() {
        assertTrue(inKindOnly().acceptsInKind());
        assertFalse(flexible().acceptsInKind());
    }
}
