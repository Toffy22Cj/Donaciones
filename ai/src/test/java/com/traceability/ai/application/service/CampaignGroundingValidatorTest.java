package com.traceability.ai.application.service;

import com.traceability.ai.domain.narrative.CampaignCitedFact;
import com.traceability.ai.domain.narrative.CampaignLlmNarrativeResponse;
import com.traceability.ai.domain.narrative.CampaignNarrativeFacts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.List;

import static com.traceability.ai.domain.narrative.CampaignFactType.CAMPAIGN_STATUS;
import static com.traceability.ai.domain.narrative.CampaignFactType.CLEARED_AMOUNT;
import static com.traceability.ai.domain.narrative.CampaignFactType.DISTINCT_RECIPIENTS;
import static com.traceability.ai.domain.narrative.CampaignFactType.TARGET_AMOUNT;
import static com.traceability.ai.domain.narrative.CampaignFactType.UNITS_DELIVERED;
import static org.assertj.core.api.Assertions.assertThat;

/** Plan B5 §3: solo se publica una narrativa cuyas afirmaciones están en los hechos citados. */
class CampaignGroundingValidatorTest {

    static final CampaignNarrativeFacts FACTS = new CampaignNarrativeFacts("OPEN", "COP", new BigDecimal("100000"),
            "SOFT_TARGET", new BigDecimal("60000"), new BigDecimal("15.0000"), 2);
    static final List<CampaignCitedFact> CITES = List.of(
            new CampaignCitedFact(CLEARED_AMOUNT, "60000"),
            new CampaignCitedFact(UNITS_DELIVERED, "15"),
            new CampaignCitedFact(DISTINCT_RECIPIENTS, "2"));

    private final CampaignGroundingValidator validator = new CampaignGroundingValidator();

    private boolean validate(String text, List<CampaignCitedFact> cites) {
        return validator.validate(new CampaignLlmNarrativeResponse(text, cites), FACTS);
    }

    @Test
    void groundedText_isAccepted() {
        assertThat(validate("Se han recaudado 60.000 COP; se entregaron 15 unidades a 2 receptores distintos.", CITES)).isTrue();
    }

    @Test
    void allCampaignFacts_canBeCited_withTheirExactValue() {
        assertThat(validate("La convocatoria sigue abierta, con una meta de 100000.", List.of(
                new CampaignCitedFact(CAMPAIGN_STATUS, "OPEN"), new CampaignCitedFact(TARGET_AMOUNT, "100000")))).isTrue();
    }

    @Test
    void withoutCitations_isRejected() {
        assertThat(validate("La convocatoria avanza muy bien.", List.of())).isFalse();
        assertThat(validate("La convocatoria avanza muy bien.", null)).isFalse();
    }

    @Test
    void citationWithAValueDifferentFromTheFact_isRejected() {
        assertThat(validate("Se entregaron 16 unidades.", List.of(new CampaignCitedFact(UNITS_DELIVERED, "16")))).isFalse();
    }

    @Test
    void citationOfAFactThatDoesNotExist_isRejected() {
        CampaignNarrativeFacts inKind = new CampaignNarrativeFacts("OPEN", null, null, null, null, BigDecimal.TEN, 1);
        assertThat(validator.validate(new CampaignLlmNarrativeResponse("Se recaudaron 0 pesos.",
                List.of(new CampaignCitedFact(CLEARED_AMOUNT, "0"))), inKind)).isFalse();
    }

    @Test
    void aNumberInTheTextThatIsNotACitedFact_isRejected() {
        assertThat(validate("Se entregaron 15 unidades a 2 receptores distintos y 500 personas más.", CITES)).isFalse();
    }

    @Test
    void aNumberThatIsAFactButIsNotCited_isRejected() {
        assertThat(validate("Se entregaron 15 unidades; la meta es 100000.", CITES)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"familias", "Familia", "hogares", "un hogar", "families", "households"})
    void familiesOrHouseholds_areRejected(String word) {
        assertThat(validate("Se entregaron 15 unidades a 2 " + word + ".", CITES)).isFalse();
    }

    @Test
    void emptyText_isRejected() {
        assertThat(validate(" ", CITES)).isFalse();
        assertThat(validator.validate(null, FACTS)).isFalse();
    }
}
