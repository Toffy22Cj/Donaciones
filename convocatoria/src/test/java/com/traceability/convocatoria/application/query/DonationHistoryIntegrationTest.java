package com.traceability.convocatoria.application.query;

import com.traceability.convocatoria.application.command.CreateConvocatoriaCommand;
import com.traceability.convocatoria.application.command.CreateDonationIntentCommand;
import com.traceability.convocatoria.application.query.DonationIntentReadPort.DonationView;
import com.traceability.convocatoria.application.service.ConvocatoriaLifecycleService;
import com.traceability.convocatoria.application.service.DonationIntentService;
import com.traceability.convocatoria.domain.model.Convocatoria;
import com.traceability.convocatoria.domain.model.ConfirmationSource;
import com.traceability.convocatoria.domain.model.DonationIntent;
import com.traceability.convocatoria.domain.model.DonationIntentStatus;
import com.traceability.convocatoria.domain.model.PaymentMethod;
import com.traceability.convocatoria.domain.model.Visibility;
import com.traceability.convocatoria.infrastructure.persistence.mongo.document.DonationIntentDocument;
import com.traceability.convocatoria.support.AbstractConvocatoriaServiceIntegrationTest;
import com.traceability.convocatoria.support.ConvocatoriaScenarios;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Enmienda 5 de ADR-037 (E5-1 y E5-2), criterios 1 a 5, en Mongo real: {@code createdAt} fijado al crear e inmutable,
 * las intenciones antiguas sin él, {@code confirmedAt} por estado, el {@code publicCode} solo de las convocatorias
 * {@code PUBLIC} (filtrado dentro de {@code convocatoria}) y el orden del historial.
 */
class DonationHistoryIntegrationTest extends AbstractConvocatoriaServiceIntegrationTest {

    private static final String DONOR = "acct:donor-history";
    private static final Instant T0 = Instant.parse("2026-10-05T12:00:00Z");

    @Autowired private ConvocatoriaLifecycleService lifecycle;
    @Autowired private DonationIntentService service;
    @Autowired private DonationIntentReadPort reads;

    private Convocatoria campaign(Visibility visibility) {
        String campaignRef = lifecycle.createConvocatoria(new CreateConvocatoriaCommand(newCommandId(), ADMIN, ORG,
                "Campaña " + visibility, null, visibility, ConvocatoriaScenarios.START, ConvocatoriaScenarios.END,
                flexible())).campaignRef();
        return convocatorias.findByCampaignRef(campaignRef).orElseThrow();
    }

    private String donate(Convocatoria c, PaymentMethod method) {
        return service.createDonationIntent(new CreateDonationIntentCommand(newCommandId(), c.getPublicCode(), DONOR,
                250, "COP", method)).intentId();
    }

    private Map<String, DonationView> history() {
        return reads.findByDonorRef(DONOR, 100).stream().collect(Collectors.toMap(DonationView::intentId,
                Function.identity()));
    }

    private Document raw(String intentId) {
        return mongoTemplate.getCollection(DonationIntentDocument.COLLECTION).find(new Document("_id", intentId)).first();
    }

    // ── Criterio 1: createdAt con el reloj del servidor; ninguna transición lo cambia ──

    @Test
    void creating_fixesCreatedAtWithTheServerClock_andNoTransitionChangesIt() {
        Convocatoria c = campaign(Visibility.PUBLIC);
        clock.set(T0);
        String confirmed = donate(c, PaymentMethod.BANK_TRANSFER);
        String failed = donate(c, PaymentMethod.GATEWAY);
        String rejected = donate(c, PaymentMethod.BANK_TRANSFER);
        assertThat(donationIntents.findById(confirmed).orElseThrow().getCreatedAt()).isEqualTo(T0);
        assertThat(raw(confirmed).getDate("createdAt").toInstant()).isEqualTo(T0);

        clock.set(T0.plusSeconds(3600));
        Instant later = clock.instant();
        donationIntents.confirmIfPending(confirmed, DonationIntent.Confirmation.of(ADMIN, later, PaymentMethod.BANK_TRANSFER, "REF-1"));
        donationIntents.failIfPending(failed, "evt-failed", later);
        donationIntents.confirmIfPending(rejected, DonationIntent.Confirmation.of(ADMIN, later, PaymentMethod.BANK_TRANSFER, "REF-2"));
        donationIntents.markFundingRejectedIfConfirmed(rejected, new DonationIntent.FundingRejection(later, "test"));
        donationIntents.markFundsApplied(confirmed, later);

        for (String id : List.of(confirmed, failed, rejected)) {
            assertThat(donationIntents.findById(id).orElseThrow().getCreatedAt()).as(id).isEqualTo(T0);
        }
        assertThat(donationIntents.findById(failed).orElseThrow().getStatus()).isEqualTo(DonationIntentStatus.FAILED);
        assertThat(donationIntents.findById(rejected).orElseThrow().getStatus())
                .isEqualTo(DonationIntentStatus.FUNDING_REJECTED);
    }

    // ── Criterio 2: intenciones anteriores a la enmienda ──

    @Test
    void anIntentStoredWithoutCreatedAt_isReadWithCreatedAtNull() {
        Convocatoria c = campaign(Visibility.PUBLIC);
        String legacy = donate(c, PaymentMethod.GATEWAY);
        mongoTemplate.getCollection(DonationIntentDocument.COLLECTION).updateOne(new Document("_id", legacy),
                new Document("$unset", new Document("createdAt", "")));

        assertThat(donationIntents.findById(legacy).orElseThrow().getCreatedAt()).isNull();
        assertThat(history().get(legacy).createdAt()).isNull();
    }

    // ── Criterio 3: confirmedAt por estado ──

    @Test
    void confirmedAt_onlyInConfirmedAndFundingRejected() {
        Convocatoria c = campaign(Visibility.PUBLIC);
        clock.set(T0);
        String pending = donate(c, PaymentMethod.BANK_TRANSFER);
        String confirmed = donate(c, PaymentMethod.BANK_TRANSFER);
        String failed = donate(c, PaymentMethod.GATEWAY);
        String rejected = donate(c, PaymentMethod.BANK_TRANSFER);
        Instant at = T0.plusSeconds(60);
        donationIntents.confirmIfPending(confirmed, DonationIntent.Confirmation.of(ADMIN, at, PaymentMethod.BANK_TRANSFER, "R1"));
        donationIntents.failIfPending(failed, "evt-1", at);
        donationIntents.confirmIfPending(rejected, DonationIntent.Confirmation.of(ADMIN, at, PaymentMethod.BANK_TRANSFER, "R2"));
        donationIntents.markFundingRejectedIfConfirmed(rejected, new DonationIntent.FundingRejection(at.plusSeconds(5), "x"));
        // EXPIRED_UNKNOWN no tiene transición en el código de hoy: se guarda el estado directamente
        String expired = UUID.randomUUID().toString();
        donationIntents.insert(DonationIntent.reconstitute(expired, UUID.randomUUID().toString(), ORG, c.getCampaignRef(),
                DONOR, 250, "COP", PaymentMethod.GATEWAY, ConfirmationSource.PAYMENT_PROVIDER, 1, null, null, null,
                DonationIntentStatus.EXPIRED_UNKNOWN, null, DonationIntent.ApplicationTracking.NONE, null,
                DonationIntent.Access.NONE, T0));

        Map<String, DonationView> h = history();
        assertThat(h.get(confirmed).confirmedAt()).isEqualTo(at);
        assertThat(h.get(rejected).confirmedAt()).isEqualTo(at);
        assertThat(h.get(pending).confirmedAt()).isNull();
        assertThat(h.get(failed).confirmedAt()).isNull();
        assertThat(h.get(expired).confirmedAt()).isNull();
    }

    // ── Criterio 4: publicCode solo de las PUBLIC, según la visibilidad actual al leer ──

    @Test
    void publicCode_onlyForPublicCampaigns_byTheirVisibilityAtReadTime() {
        Convocatoria open = campaign(Visibility.PUBLIC);
        Convocatoria privateLink = campaign(Visibility.PRIVATE_LINK);
        Convocatoria becomesPrivate = campaign(Visibility.PUBLIC);
        Convocatoria becomesPublic = campaign(Visibility.PRIVATE_LINK);
        String toOpen = donate(open, PaymentMethod.GATEWAY);
        String toPrivate = donate(privateLink, PaymentMethod.GATEWAY);
        String toBecomesPrivate = donate(becomesPrivate, PaymentMethod.GATEWAY);
        String toBecomesPublic = donate(becomesPublic, PaymentMethod.GATEWAY);
        // P-A2-3: la visibilidad es inmutable en el dominio; se simula el cambio en el documento
        setVisibility(becomesPrivate, Visibility.PRIVATE_LINK);
        setVisibility(becomesPublic, Visibility.PUBLIC);

        Map<String, DonationView> h = history();
        assertThat(h.get(toOpen).publicCode()).isEqualTo(open.getPublicCode());
        assertThat(h.get(toPrivate).publicCode()).isNull();
        assertThat(h.get(toBecomesPrivate).publicCode()).isNull();
        assertThat(h.get(toBecomesPublic).publicCode()).isEqualTo(becomesPublic.getPublicCode());
        assertThat(h.get(toPrivate).campaignTitle()).isEqualTo("Campaña PRIVATE_LINK");
        assertThat(h.get(toOpen).organizationRef()).isEqualTo(ORG);
        assertThat(h.get(toOpen).paymentMethod()).isEqualTo("GATEWAY");
    }

    // M-21 (plan de cierre; decisión (a) de Manuel, autorización 3.5 en #147): campaignRef interno en cada vista, también
    // de las PRIVATE_LINK, que no dan publicCode; app lo usa para contar las convocatorias apoyadas y nunca lo devuelve
    @Test
    void m21_campaignRef_isInEveryView_alsoForPrivateLinkCampaigns() {
        Convocatoria open = campaign(Visibility.PUBLIC);
        Convocatoria privateLink = campaign(Visibility.PRIVATE_LINK);
        String toOpen = donate(open, PaymentMethod.GATEWAY);
        String toPrivate = donate(privateLink, PaymentMethod.GATEWAY);
        String again = donate(open, PaymentMethod.GATEWAY);

        Map<String, DonationView> h = history();
        assertThat(h.get(toOpen).campaignRef()).isEqualTo(open.getCampaignRef());
        assertThat(h.get(again).campaignRef()).isEqualTo(open.getCampaignRef());
        assertThat(h.get(toPrivate).campaignRef()).isEqualTo(privateLink.getCampaignRef());
        assertThat(h.get(toPrivate).publicCode()).isNull();
    }

    private void setVisibility(Convocatoria c, Visibility visibility) {
        mongoTemplate.getCollection("convocatorias").updateOne(new Document("_id", c.getCampaignRef()),
                new Document("$set", new Document("visibility", visibility.name())));
        assertThat(convocatorias.findByCampaignRef(c.getCampaignRef()).orElseThrow().getVisibility()).isEqualTo(visibility);
    }

    // ── Criterio 5: orden por createdAt descendente, antiguas al final, las 100 más recientes ──

    @Test
    void history_isNewestFirst_withLegacyIntentsLast() {
        Convocatoria c = campaign(Visibility.PUBLIC);
        clock.set(T0.plusSeconds(20));
        String middle = donate(c, PaymentMethod.GATEWAY);
        clock.set(T0.plusSeconds(30));
        String newest = donate(c, PaymentMethod.GATEWAY);
        clock.set(T0.plusSeconds(10));
        String oldest = donate(c, PaymentMethod.GATEWAY);
        String legacyA = donate(c, PaymentMethod.GATEWAY);
        String legacyB = donate(c, PaymentMethod.GATEWAY);
        for (String legacy : List.of(legacyA, legacyB)) {
            mongoTemplate.getCollection(DonationIntentDocument.COLLECTION).updateOne(new Document("_id", legacy),
                    new Document("$unset", new Document("createdAt", "")));
        }
        List<String> legacyInIdOrder = List.of(legacyA, legacyB).stream().sorted().toList();

        List<String> order = reads.findByDonorRef(DONOR, 100).stream().map(DonationView::intentId).toList();

        assertThat(order).containsExactly(newest, middle, oldest, legacyInIdOrder.get(0), legacyInIdOrder.get(1));
    }

    @Test
    void moreThan100_thePageHasThe100MostRecent() {
        Convocatoria c = campaign(Visibility.PUBLIC);
        clock.set(T0);
        String first = donate(c, PaymentMethod.GATEWAY);
        for (int i = 1; i <= 100; i++) {
            clock.set(T0.plusSeconds(i));
            donate(c, PaymentMethod.GATEWAY);
        }

        List<DonationView> page = reads.findByDonorRef(DONOR, 100);

        assertThat(page).hasSize(100);
        assertThat(page).extracting(DonationView::intentId).doesNotContain(first);
        assertThat(page.get(0).createdAt()).isEqualTo(T0.plusSeconds(100));
    }
}
