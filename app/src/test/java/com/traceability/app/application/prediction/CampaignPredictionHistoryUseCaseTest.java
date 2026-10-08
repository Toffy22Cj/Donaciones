package com.traceability.app.application.prediction;

import com.traceability.app.application.prediction.CampaignPredictionHistoryUseCase.Cut;
import com.traceability.app.application.prediction.CampaignPredictionHistoryUseCase.History;
import com.traceability.app.application.prediction.CampaignPredictionUseCase.Unavailable;
import com.traceability.contracts.authorization.AuthorizationPrincipal;
import com.traceability.contracts.authorization.AuthorizationRole;
import com.traceability.convocatoria.application.query.CampaignPredictionData;
import com.traceability.convocatoria.application.query.CampaignPredictionData.IntentOutcome;
import com.traceability.convocatoria.application.query.CampaignPredictionData.Outcome;
import com.traceability.convocatoria.application.query.CampaignPredictionDataQuery;
import com.traceability.convocatoria.application.query.CampaignTimelineQuery;
import com.traceability.convocatoria.application.query.CampaignTimelineQuery.CampaignTimeline;
import com.traceability.convocatoria.domain.exception.ActorNotInCampaignOrganizationException;
import com.traceability.convocatoria.domain.exception.ActorRoleNotAllowedException;
import com.traceability.convocatoria.domain.exception.CampaignNotFoundException;
import com.traceability.core.application.port.out.CampaignClearedFundsPort;
import com.traceability.core.application.port.out.CampaignClearedFundsPort.ClearedFund;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Encargo 6, P3 (S-10): estimaciones en t = 0,15, 0,25 y 0,50 de los cortes ya pasados, con lo recaudado en ese
 * momento según el event store ({@code FUNDS_CLEARED} anteriores al corte), nunca con los valores actuales.
 */
class CampaignPredictionHistoryUseCaseTest {

    static final Instant START = Instant.parse("2027-01-01T00:00:00Z");
    static final Instant END = START.plus(Duration.ofDays(40));
    static final Instant NOW = START.plus(Duration.ofDays(12)); // t = 0,30: 0,15 y 0,25 pasados, 0,50 futuro
    static final long TARGET = 800_000_000L; // 8 000 000 COP en unidades mínimas

    final CampaignPredictionDataQuery query = mock(CampaignPredictionDataQuery.class);
    final CampaignTimelineQuery timeline = mock(CampaignTimelineQuery.class);
    final CampaignClearedFundsPort clearedFunds = mock(CampaignClearedFundsPort.class);
    final CampaignPredictorModel model = CampaignPredictorModel.load(
            getClass().getResourceAsStream(CampaignPredictionUseCase.MODEL_RESOURCE));

    @SuppressWarnings("unchecked")
    CampaignPredictionHistoryUseCase useCase(Instant now) {
        ObjectProvider<Clock> clock = mock(ObjectProvider.class);
        when(clock.getIfAvailable(any())).thenReturn(Clock.fixed(now, ZoneOffset.UTC));
        return new CampaignPredictionHistoryUseCase(query, timeline, clearedFunds, clock);
    }

    static AuthorizationPrincipal principal(String org, AuthorizationRole... roles) {
        return new AuthorizationPrincipal("acc-1", org, Set.of(roles), null);
    }

    /** Estado ACTUAL de las intenciones: mucho más recaudado que en cualquier corte pasado. */
    static CampaignPredictionData campaign(String policy, Long target, String currency, String status) {
        List<IntentOutcome> current = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            current.add(new IntentOutcome(Outcome.CONFIRMED, 50_000_000L, i, START.plus(Duration.ofDays(1))));
        }
        current.add(new IntentOutcome(Outcome.FAILED, 10_000_000L, 99, null));
        return new CampaignPredictionData("CAMP-1", "ORG-1", status, "PUBLIC", policy, target, currency, START, END, 2, 3,
                List.copyOf(current), false);
    }

    static ClearedFund fund(String id, Duration afterStart, long amount, String donor) {
        return new ClearedFund(id, START.plus(afterStart), amount, donor);
    }

    /** Eventos: dos antes de 0,15 (día 6), uno justo en el corte (no cuenta), uno antes de 0,25 y uno después. */
    static final List<ClearedFund> EVENTS = List.of(
            fund("F-A", Duration.ofDays(2), 40_000_000L, "d-1"),
            fund("F-B", Duration.ofHours(5 * 24 + 12), 30_000_000L, "d-2"),
            fund("F-C", Duration.ofDays(6), 20_000_000L, "d-1"),
            fund("F-D", Duration.ofDays(8), 50_000_000L, "d-3"),
            fund("F-E", Duration.ofDays(11), 60_000_000L, "d-4"));

    void given(CampaignPredictionData data, List<ClearedFund> events, CampaignTimeline line) {
        when(query.dataOf("CAMP-1")).thenReturn(Optional.of(data));
        when(clearedFunds.findClearedFundsByCampaign(eq("CAMP-1"), anyInt())).thenReturn(events);
        when(timeline.timelineOf("CAMP-1")).thenReturn(line);
    }

    static final CampaignTimeline OPEN = new CampaignTimeline(null, List.of());

    /** Lo que Python vería en ese corte: solo las donaciones acreditadas antes, sin fallos (DD-75). */
    CampaignFeatures expectedAt(CampaignPredictionData data, Instant cutAt, ClearedFund... before) {
        List<IntentOutcome> intents = new ArrayList<>();
        List<String> donors = new ArrayList<>();
        for (ClearedFund f : before) {
            if (!donors.contains(f.donorRef())) donors.add(f.donorRef());
            intents.add(new IntentOutcome(Outcome.CONFIRMED, f.clearedAmount(), donors.indexOf(f.donorRef()), f.occurredAt()));
        }
        CampaignPredictionData atCut = new CampaignPredictionData(data.campaignRef(), data.organizationRef(),
                data.status(), data.visibility(), data.targetPolicy(), data.targetAmount(), data.currency(),
                data.startDate(), data.endDate(), data.paymentMethodsEnabled(), data.orgPriorCampaigns(),
                List.copyOf(intents), false);
        return CampaignFeatureBuilder.build(atCut, cutAt);
    }

    static double round4(double v) {
        return Math.round(v * 10_000.0) / 10_000.0;
    }

    @Test
    void pastCuts_useOnlyWhatTheEventStoreHadCleared_beforeEachCut_andAFutureCutHasNoFigure() {
        CampaignPredictionData data = campaign("FLEXIBLE", TARGET, "COP", "OPEN");
        given(data, EVENTS, OPEN);

        History h = useCase(NOW).history(principal("ORG-1", AuthorizationRole.ADMINISTRATOR), "ORG-1", "CAMP-1");

        assertThat(h.unavailable()).isNull();
        assertThat(h.modelVersion()).isEqualTo("baseline-0.2.0");
        assertThat(h.asOf()).isEqualTo(NOW);
        assertThat(h.warnings()).containsExactly(CampaignPredictionUseCase.SYNTHETIC_WARNING,
                CampaignPredictionHistoryUseCase.FAILED_RATE_WARNING);
        assertThat(h.cuts()).extracting(Cut::t).containsExactly(0.15, 0.25, 0.50);
        assertThat(h.cuts()).extracting(Cut::cutAt).containsExactly(START.plus(Duration.ofDays(6)),
                START.plus(Duration.ofDays(10)), START.plus(Duration.ofDays(20)));

        // 0,15: F-A y F-B; F-C ocurre justo en el corte y no cuenta (como "dayFraction < t" del entrenamiento)
        Cut c15 = h.cuts().get(0);
        CampaignFeatures f15 = expectedAt(data, c15.cutAt(), EVENTS.get(0), EVENTS.get(1));
        assertThat(c15.unavailable()).isNull();
        assertThat(c15.pctRaisedAtCut()).isEqualTo(round4(70_000_000.0 / TARGET));
        assertThat(c15.probabilityReachTarget()).isEqualTo(round4(model.probabilityReachTarget(f15.byName())));
        assertThat(c15.estimatedFinalPctOfTarget()).isEqualTo(round4(model.finalPctOfTarget(f15.byName())));
        assertThat(f15.failedRate()).isZero();

        // 0,25: F-A a F-D; F-E es posterior
        Cut c25 = h.cuts().get(1);
        CampaignFeatures f25 = expectedAt(data, c25.cutAt(), EVENTS.get(0), EVENTS.get(1), EVENTS.get(2), EVENTS.get(3));
        assertThat(c25.unavailable()).isNull();
        assertThat(c25.pctRaisedAtCut()).isEqualTo(round4(140_000_000.0 / TARGET));
        assertThat(c25.probabilityReachTarget()).isEqualTo(round4(model.probabilityReachTarget(f25.byName())));
        assertThat(c25.estimatedFinalPctOfTarget()).isEqualTo(round4(model.finalPctOfTarget(f25.byName())));
        assertThat(c25.probabilityReachTarget()).isNotEqualTo(c15.probabilityReachTarget());

        // 0,50: futuro
        Cut c50 = h.cuts().get(2);
        assertThat(c50.unavailable()).isEqualTo(Unavailable.FUTURE_CUT);
        assertThat(c50.probabilityReachTarget()).isNull();
        assertThat(c50.estimatedFinalPctOfTarget()).isNull();
        assertThat(c50.pctRaisedAtCut()).isNull();
    }

    @Test
    void theCurrentStateOfTheIntents_neverEntersAPastCut() {
        CampaignPredictionData data = campaign("FLEXIBLE", TARGET, "COP", "OPEN"); // hoy: 600 000 000 recaudado
        given(data, List.of(), OPEN);

        History h = useCase(NOW).history(principal("ORG-1", AuthorizationRole.REPRESENTATIVE), "ORG-1", "CAMP-1");

        Cut c25 = h.cuts().get(1);
        CampaignFeatures f = expectedAt(data, c25.cutAt());
        assertThat(c25.pctRaisedAtCut()).isZero();
        assertThat(c25.probabilityReachTarget()).isEqualTo(round4(model.probabilityReachTarget(f.byName())));
    }

    @Test
    void aClosedCampaign_keepsItsPastCuts_butNotTheOnesAfterTheClose() {
        CampaignPredictionData data = campaign("CLOSE_ON_TARGET", TARGET, "COP", "CLOSED");
        given(data, EVENTS, new CampaignTimeline(START.plus(Duration.ofDays(15)), List.of()));

        History h = useCase(END.plus(Duration.ofDays(30))).history(principal("ORG-1", AuthorizationRole.ADMINISTRATOR),
                "ORG-1", "CAMP-1");

        assertThat(h.unavailable()).isNull();
        assertThat(h.cuts().get(0).probabilityReachTarget()).isNotNull();
        assertThat(h.cuts().get(1).probabilityReachTarget()).isNotNull();
        assertThat(h.cuts().get(2).unavailable()).isEqualTo(Unavailable.CAMPAIGN_ENDED);
        assertThat(h.cuts().get(2).probabilityReachTarget()).isNull();
    }

    @Test
    void aConfigurationChangedAfterTheCut_isNotReconstructedWithTodaysValues() {
        CampaignPredictionData data = campaign("FLEXIBLE", TARGET, "COP", "OPEN");
        given(data, EVENTS, new CampaignTimeline(null, List.of(START.plus(Duration.ofDays(7)))));

        History h = useCase(NOW).history(principal("ORG-1", AuthorizationRole.ADMINISTRATOR), "ORG-1", "CAMP-1");

        assertThat(h.cuts().get(0).unavailable()).isEqualTo(Unavailable.CONFIGURATION_CHANGED_AFTER_CUT);
        assertThat(h.cuts().get(0).probabilityReachTarget()).isNull();
        assertThat(h.cuts().get(1).unavailable()).isNull();
    }

    @Test
    void aCutWhereTheTargetWasAlreadyReached_givesTheRaisedFactButNoEstimate() {
        CampaignPredictionData data = campaign("FLEXIBLE", 60_000_000L, "COP", "OPEN");
        given(data, EVENTS, OPEN);

        History h = useCase(NOW).history(principal("ORG-1", AuthorizationRole.ADMINISTRATOR), "ORG-1", "CAMP-1");

        Cut c15 = h.cuts().get(0);
        assertThat(c15.unavailable()).isEqualTo(Unavailable.TARGET_ALREADY_REACHED);
        assertThat(c15.pctRaisedAtCut()).isEqualTo(round4(70_000_000.0 / 60_000_000L));
        assertThat(c15.probabilityReachTarget()).isNull();
    }

    @Test
    void strictOtherCurrenciesAndNoMonetaryTarget_haveNoCutsAtAll() {
        for (Object[] c : new Object[][]{{"STRICT", TARGET, "COP", Unavailable.STRICT_POLICY_EXCLUDED},
                {"FLEXIBLE", TARGET, "USD", Unavailable.UNSUPPORTED_CURRENCY},
                {null, null, null, Unavailable.NO_MONETARY_TARGET}}) {
            given(campaign((String) c[0], (Long) c[1], (String) c[2], "OPEN"), EVENTS, OPEN);

            History h = useCase(NOW).history(principal("ORG-1", AuthorizationRole.ADMINISTRATOR), "ORG-1", "CAMP-1");

            assertThat(h.unavailable()).isEqualTo(c[3]);
            assertThat(h.cuts()).isEmpty();
        }
    }

    @Test
    void tooManyClearedFunds_isAnExplicitReason() {
        List<ClearedFund> many = new ArrayList<>();
        for (int i = 0; i <= CampaignPredictionDataQuery.MAX_INTENTS; i++) {
            many.add(fund("F-" + i, Duration.ofDays(1), 1L, "d"));
        }
        given(campaign("FLEXIBLE", TARGET, "COP", "OPEN"), many, OPEN);

        History h = useCase(NOW).history(principal("ORG-1", AuthorizationRole.ADMINISTRATOR), "ORG-1", "CAMP-1");

        assertThat(h.unavailable()).isEqualTo(Unavailable.TOO_MANY_INTENTS);
        assertThat(h.cuts()).isEmpty();
        verify(clearedFunds).findClearedFundsByCampaign("CAMP-1", CampaignPredictionDataQuery.MAX_INTENTS + 1);
    }

    @Test
    void onlyAdministratorOrRepresentativeOfTheOwningOrganization() {
        given(campaign("FLEXIBLE", TARGET, "COP", "OPEN"), EVENTS, OPEN);
        CampaignPredictionHistoryUseCase u = useCase(NOW);

        assertThatThrownBy(() -> u.history(principal("ORG-2", AuthorizationRole.ADMINISTRATOR), "ORG-1", "CAMP-1"))
                .isInstanceOf(ActorNotInCampaignOrganizationException.class);
        assertThatThrownBy(() -> u.history(principal("ORG-1", AuthorizationRole.EMPLOYEE), "ORG-1", "CAMP-1"))
                .isInstanceOf(ActorRoleNotAllowedException.class);
        assertThatThrownBy(() -> u.history(principal("ORG-2", AuthorizationRole.ADMINISTRATOR), "ORG-2", "CAMP-1"))
                .isInstanceOf(CampaignNotFoundException.class);
        verify(clearedFunds, never()).findClearedFundsByCampaign(any(), anyInt());
    }
}
