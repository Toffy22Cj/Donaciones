package com.traceability.app.application.prediction;

import com.traceability.app.application.prediction.CampaignPredictionUseCase.Prediction;
import com.traceability.app.application.prediction.CampaignPredictionUseCase.Unavailable;
import com.traceability.contracts.authorization.AuthorizationPrincipal;
import com.traceability.contracts.authorization.AuthorizationRole;
import com.traceability.convocatoria.application.query.CampaignPredictionData;
import com.traceability.convocatoria.application.query.CampaignPredictionData.IntentOutcome;
import com.traceability.convocatoria.application.query.CampaignPredictionData.Outcome;
import com.traceability.convocatoria.application.query.CampaignPredictionDataQuery;
import com.traceability.convocatoria.domain.exception.ActorNotInCampaignOrganizationException;
import com.traceability.convocatoria.domain.exception.ActorRoleNotAllowedException;
import com.traceability.convocatoria.domain.exception.CampaignNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** P3: la estimación con datos de una convocatoria, los motivos sin estimación y la autorización en el servidor. */
class CampaignPredictionUseCaseTest {

    static final Instant START = Instant.parse("2027-01-01T00:00:00Z");
    static final Instant END = START.plus(Duration.ofDays(40));
    static final Instant NOW = START.plus(Duration.ofDays(10)); // t = 0.25

    final CampaignPredictionDataQuery query = mock(CampaignPredictionDataQuery.class);

    @SuppressWarnings("unchecked")
    CampaignPredictionUseCase useCase(Instant now) {
        ObjectProvider<Clock> clock = mock(ObjectProvider.class);
        when(clock.getIfAvailable(any())).thenReturn(Clock.fixed(now, ZoneOffset.UTC));
        return new CampaignPredictionUseCase(query, clock);
    }

    static AuthorizationPrincipal principal(String org, AuthorizationRole... roles) {
        return new AuthorizationPrincipal("acc-1", org, Set.of(roles), null);
    }

    static CampaignPredictionData campaign(String policy, Long target, String status, List<IntentOutcome> intents) {
        return new CampaignPredictionData("CAMP-1", "ORG-1", status, "PUBLIC", policy, target, START, END, 2, 3,
                intents, false);
    }

    static List<IntentOutcome> donations(long... amounts) {
        List<IntentOutcome> list = new java.util.ArrayList<>();
        for (int i = 0; i < amounts.length; i++) {
            list.add(new IntentOutcome(Outcome.CONFIRMED, amounts[i], i, START.plus(Duration.ofDays(1 + i % 9))));
        }
        list.add(new IntentOutcome(Outcome.FAILED, 50_000, 99, null));
        list.add(new IntentOutcome(Outcome.IN_PROGRESS, 70_000, 98, null));
        return list;
    }

    @Test
    void aFlexibleCampaignInProgress_getsAnEstimateFromTheModel() {
        CampaignPredictionData data = campaign("FLEXIBLE", 8_000_000L, "OPEN",
                donations(400_000, 300_000, 500_000, 250_000, 600_000));
        when(query.dataOf("CAMP-1")).thenReturn(Optional.of(data));

        Prediction p = useCase(NOW).predict(principal("ORG-1", AuthorizationRole.ADMINISTRATOR), "ORG-1", "CAMP-1");

        CampaignFeatures f = CampaignFeatureBuilder.build(data, NOW);
        CampaignPredictorModel model = CampaignPredictorModel.load(
                getClass().getResourceAsStream(CampaignPredictionUseCase.MODEL_RESOURCE));
        assertThat(p.unavailable()).isNull();
        assertThat(p.modelVersion()).isEqualTo("baseline-0.2.0");
        assertThat(p.probabilityReachTarget()).isEqualTo(Math.round(model.probabilityReachTarget(f.byName()) * 10_000.0) / 10_000.0)
                .isBetween(0.0, 1.0);
        assertThat(p.estimatedFinalPctOfTarget()).isEqualTo(Math.round(model.finalPctOfTarget(f.byName()) * 10_000.0) / 10_000.0);
        assertThat(p.pctTimeElapsed()).isEqualTo(0.25);
        assertThat(p.warnings()).containsExactly("modelo entrenado con datos sintéticos");
        assertThat(f.nDonations()).isEqualTo(5);
        assertThat(f.failedRate()).as("el intento en curso no cuenta").isEqualTo(1.0 / 6);
    }

    @Test
    void theRepresentative_canAlsoSeeIt_andOutsideTheTrainedRangeThereIsAWarning() {
        when(query.dataOf("CAMP-1")).thenReturn(Optional.of(campaign("CLOSE_ON_TARGET", 8_000_000L, "OPEN",
                donations(400_000))));

        Prediction p = useCase(START.plus(Duration.ofDays(30)))
                .predict(principal("ORG-1", AuthorizationRole.REPRESENTATIVE), "ORG-1", "CAMP-1");

        assertThat(p.unavailable()).isNull();
        assertThat(p.warnings()).hasSize(2).anyMatch(w -> w.startsWith("fuera del rango de entrenamiento"));
    }

    @Test
    void strict_neverGetsAPrediction_withAnExplicitReason() {
        when(query.dataOf("CAMP-1")).thenReturn(Optional.of(campaign("STRICT", 8_000_000L, "OPEN", donations(1))));

        Prediction p = useCase(NOW).predict(principal("ORG-1", AuthorizationRole.ADMINISTRATOR), "ORG-1", "CAMP-1");

        assertThat(p.unavailable()).isEqualTo(Unavailable.STRICT_POLICY_EXCLUDED);
        assertThat(p.probabilityReachTarget()).isNull();
        assertThat(p.estimatedFinalPctOfTarget()).isNull();
    }

    @Test
    void reasonsWithoutEstimate() {
        CampaignPredictionUseCase atNow = useCase(NOW);
        AuthorizationPrincipal admin = principal("ORG-1", AuthorizationRole.ADMINISTRATOR);

        when(query.dataOf("CAMP-1")).thenReturn(Optional.of(campaign(null, null, "OPEN", List.of())));
        assertThat(atNow.predict(admin, "ORG-1", "CAMP-1").unavailable()).isEqualTo(Unavailable.NO_MONETARY_TARGET);

        when(query.dataOf("CAMP-1")).thenReturn(Optional.of(campaign("FLEXIBLE", 8_000_000L, "CLOSED", List.of())));
        assertThat(atNow.predict(admin, "ORG-1", "CAMP-1").unavailable()).isEqualTo(Unavailable.CAMPAIGN_ENDED);

        when(query.dataOf("CAMP-1")).thenReturn(Optional.of(campaign("FLEXIBLE", 8_000_000L, "OPEN", donations(9_000_000))));
        assertThat(atNow.predict(admin, "ORG-1", "CAMP-1").unavailable()).isEqualTo(Unavailable.TARGET_ALREADY_REACHED);

        when(query.dataOf("CAMP-1")).thenReturn(Optional.of(campaign("FLEXIBLE", 8_000_000L, "OPEN", List.of())));
        assertThat(useCase(START.minusSeconds(1)).predict(admin, "ORG-1", "CAMP-1").unavailable())
                .isEqualTo(Unavailable.NOT_STARTED);
        assertThat(useCase(END).predict(admin, "ORG-1", "CAMP-1").unavailable()).isEqualTo(Unavailable.CAMPAIGN_ENDED);
    }

    @Test
    void authorization_isCheckedOnTheServer() {
        when(query.dataOf("CAMP-1")).thenReturn(Optional.of(campaign("FLEXIBLE", 8_000_000L, "OPEN", List.of())));
        when(query.dataOf("CAMP-X")).thenReturn(Optional.empty());
        CampaignPredictionUseCase atNow = useCase(NOW);

        assertThatThrownBy(() -> atNow.predict(principal("ORG-1", AuthorizationRole.EMPLOYEE), "ORG-1", "CAMP-1"))
                .isInstanceOf(ActorRoleNotAllowedException.class);
        assertThatThrownBy(() -> atNow.predict(principal("ORG-2", AuthorizationRole.ADMINISTRATOR), "ORG-1", "CAMP-1"))
                .isInstanceOf(ActorNotInCampaignOrganizationException.class);
        assertThatThrownBy(() -> atNow.predict(principal("ORG-2", AuthorizationRole.ADMINISTRATOR), "ORG-2", "CAMP-1"))
                .as("convocatoria de otra organización").isInstanceOf(CampaignNotFoundException.class);
        assertThatThrownBy(() -> atNow.predict(principal("ORG-1", AuthorizationRole.ADMINISTRATOR), "ORG-1", "CAMP-X"))
                .isInstanceOf(CampaignNotFoundException.class);
        assertThatThrownBy(() -> atNow.predict(principal(null), "ORG-1", "CAMP-1"))
                .isInstanceOf(ActorNotInCampaignOrganizationException.class);
    }
}
