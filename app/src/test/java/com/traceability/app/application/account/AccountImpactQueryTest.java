package com.traceability.app.application.account;

import com.traceability.app.application.account.AccountImpactQuery.Impact;
import com.traceability.contracts.donor.DonorPseudonymPort;
import com.traceability.convocatoria.application.query.DonationIntentReadPort;
import com.traceability.convocatoria.application.query.DonationIntentReadPort.DonationView;
import com.traceability.core.application.port.out.DonationReadModel;
import com.traceability.core.application.port.out.DonationReadPort;
import com.traceability.core.application.port.out.LogisticsReadItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * M-21 "Mi impacto" (plan de cierre §M-21, aprobado en #147: P-M21-1 a 5; campaignRef por la decisión (a)). Solo cuentan
 * las donaciones {@code CONFIRMED} (P-M21-3, DD-46); nunca se suman monedas ni unidades distintas; un activo cuenta una
 * vez (P-M21-4: "unidades entregadas en las convocatorias que apoyaste").
 */
class AccountImpactQueryTest {

    private DonorPseudonymPort pseudonyms;
    private DonationIntentReadPort intents;
    private DonationReadPort donations;
    private AccountImpactQuery query;

    @BeforeEach
    void setUp() {
        pseudonyms = mock(DonorPseudonymPort.class);
        intents = mock(DonationIntentReadPort.class);
        donations = mock(DonationReadPort.class);
        when(pseudonyms.existingPseudonymFor("acct-1")).thenReturn(Optional.of("p1"));
        when(donations.findByFundId(anyString())).thenReturn(Optional.empty());
        query = new AccountImpactQuery(pseudonyms, intents, donations);
    }

    private static DonationView view(String id, long amount, String currency, String status, String fundId, String campaignRef) {
        return new DonationView(id, "Campaña", amount, currency, status, fundId, fundId == null ? null : Instant.EPOCH,
                Instant.EPOCH, Instant.EPOCH, "org-1", "GATEWAY", null, campaignRef);
    }

    private static LogisticsReadItem item(String assetId, String status, String unit, String quantity) {
        return new LogisticsReadItem(assetId, status, "FOOD", unit, new BigDecimal(quantity), "loc", "cust", null);
    }

    private void fund(String fundId, LogisticsReadItem... logistics) {
        when(donations.findByFundId(fundId)).thenReturn(Optional.of(new DonationReadModel(fundId, "COP", "camp", 0, 0, 0, 0, 0,
                "CLEARED", List.of(logistics))));
    }

    @Test
    void m21_1_totalsByCurrency_campaignsAndCount() {
        when(intents.findByDonorRef("acct:p1", AccountImpactQuery.MAX_INTENTS + 1)).thenReturn(List.of(
                view("i1", 1000, "COP", "CONFIRMED", null, "camp-A"),
                view("i2", 500, "COP", "CONFIRMED", null, "camp-B"),
                view("i3", 20, "USD", "CONFIRMED", null, "camp-A")));

        Impact impact = query.impactOf("acct-1");

        assertThat(impact.donationsCount()).isEqualTo(3);
        assertThat(impact.totals()).containsExactly(new AccountImpactQuery.Total("COP", 1500), new AccountImpactQuery.Total("USD", 20));
        assertThat(impact.campaignsSupported()).isEqualTo(2);
        assertThat(impact.truncated()).isFalse();
    }

    // P-M21-3: solo CONFIRMED
    @Test
    void m21_2_onlyConfirmedDonationsCount() {
        when(intents.findByDonorRef("acct:p1", AccountImpactQuery.MAX_INTENTS + 1)).thenReturn(List.of(
                view("i1", 1000, "COP", "CONFIRMED", "fund-1", "camp-A"),
                view("i2", 700, "COP", "PENDING", null, "camp-B"),
                view("i3", 300, "COP", "FAILED", null, "camp-C"),
                view("i4", 200, "COP", "FUNDING_REJECTED", "fund-4", "camp-D"),
                view("i5", 100, "COP", "EXPIRED_UNKNOWN", null, "camp-E")));
        fund("fund-4", item("asset-x", "DELIVERED", "KGS", "99"));

        Impact impact = query.impactOf("acct-1");

        assertThat(impact.donationsCount()).isEqualTo(1);
        assertThat(impact.totals()).containsExactly(new AccountImpactQuery.Total("COP", 1000));
        assertThat(impact.campaignsSupported()).isEqualTo(1);
        assertThat(impact.deliveredUnits()).isEmpty();
        verify(donations, never()).findByFundId("fund-4");
    }

    // Solo lo entregado, por unidad de medida; un activo de dos donaciones cuenta una vez (P-M21-4)
    @Test
    void m21_3_onlyDeliveredUnits_byUnitOfMeasure_andEachAssetOnce() {
        when(intents.findByDonorRef("acct:p1", AccountImpactQuery.MAX_INTENTS + 1)).thenReturn(List.of(
                view("i1", 1000, "COP", "CONFIRMED", "fund-1", "camp-A"),
                view("i2", 1000, "COP", "CONFIRMED", "fund-2", "camp-A"),
                view("i3", 1000, "COP", "CONFIRMED", null, "camp-A")));
        fund("fund-1", item("a1", "DELIVERED", "KGS", "10.5"), item("a2", "IN_TRANSIT", "KGS", "7"),
                item("a3", "DELIVERED", "UNITS", "3"));
        fund("fund-2", item("a1", "DELIVERED", "KGS", "10.5"), item("a4", "DELIVERED", "KGS", "2"));

        Impact impact = query.impactOf("acct-1");

        assertThat(impact.deliveredUnits()).containsExactly(
                new AccountImpactQuery.Units("KGS", new BigDecimal("12.5")),
                new AccountImpactQuery.Units("UNITS", new BigDecimal("3")));
        assertThat(impact.campaignsSupported()).isEqualTo(1);
    }

    // Una cuenta que nunca donó: ceros, y el seudónimo no se crea (solo se lee el existente)
    @Test
    void m21_4_anAccountThatNeverDonated_isAllZeros() {
        when(pseudonyms.existingPseudonymFor("acct-2")).thenReturn(Optional.empty());

        Impact impact = query.impactOf("acct-2");

        assertThat(impact.donationsCount()).isZero();
        assertThat(impact.totals()).isEmpty();
        assertThat(impact.campaignsSupported()).isZero();
        assertThat(impact.deliveredUnits()).isEmpty();
        assertThat(impact.truncated()).isFalse();
        verify(intents, never()).findByDonorRef(anyString(), org.mockito.ArgumentMatchers.anyInt());
    }

    // El mismo tope que /account/donations (DD-21): con 101, truncated y solo se usan 100
    @Test
    void m21_7_moreThan100Donations_isTruncated() {
        List<DonationView> many = new ArrayList<>();
        IntStream.range(0, AccountImpactQuery.MAX_INTENTS + 1)
                .forEach(i -> many.add(view("i" + i, 1, "COP", "CONFIRMED", null, "camp-" + i)));
        when(intents.findByDonorRef("acct:p1", AccountImpactQuery.MAX_INTENTS + 1)).thenReturn(many);

        Impact impact = query.impactOf("acct-1");

        assertThat(impact.truncated()).isTrue();
        assertThat(impact.donationsCount()).isEqualTo(AccountImpactQuery.MAX_INTENTS);
        assertThat(impact.totals()).containsExactly(new AccountImpactQuery.Total("COP", AccountImpactQuery.MAX_INTENTS));
    }
}
