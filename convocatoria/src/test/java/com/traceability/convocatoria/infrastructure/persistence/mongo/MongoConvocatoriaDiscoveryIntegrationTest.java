package com.traceability.convocatoria.infrastructure.persistence.mongo;

import com.traceability.convocatoria.application.port.out.ConvocatoriaRepositoryPort;
import com.traceability.convocatoria.application.query.PublicCampaignDiscoveryQuery;
import com.traceability.convocatoria.application.query.PublicCampaignDiscoveryQuery.Criteria;
import com.traceability.convocatoria.application.query.PublicCampaignDiscoveryQuery.Page;
import com.traceability.convocatoria.application.query.PublicCampaignDiscoveryQuery.Position;
import com.traceability.convocatoria.application.query.PublicCampaignDiscoveryQuery.PublicCampaignSummary;
import com.traceability.convocatoria.application.query.PublicCampaignDiscoveryQuery.Sort;
import com.traceability.convocatoria.domain.model.Convocatoria;
import com.traceability.convocatoria.domain.model.ConvocatoriaConfiguration;
import com.traceability.convocatoria.domain.model.DonationType;
import com.traceability.convocatoria.domain.model.PaymentMethod;
import com.traceability.convocatoria.domain.model.TargetPolicy;
import com.traceability.convocatoria.domain.model.Visibility;
import com.traceability.convocatoria.infrastructure.persistence.mongo.document.ConvocatoriaDocument;
import com.traceability.convocatoria.support.AbstractConvocatoriaMongoIntegrationTest;
import com.traceability.convocatoria.support.ConvocatoriaTestIndexes;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.data.mongodb.core.query.Criteria.where;

/**
 * Ficha R-1 a nivel de repositorio (plan R-1 §3): búsqueda (test 3), tipo (5), orden con desempate (6), paginación
 * completa sin saltos ni repeticiones (7), nunca {@code PRIVATE_LINK} ni {@code CLOSED} (10), sin resultados (11) y el
 * relleno de {@code titleSearch} (13).
 */
class MongoConvocatoriaDiscoveryIntegrationTest extends AbstractConvocatoriaMongoIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-11-01T00:00:00Z");

    @Autowired private MongoTemplate mongoTemplate;
    @Autowired private ConvocatoriaRepositoryPort convocatorias;
    @Autowired private PublicCampaignDiscoveryQuery discovery;
    @Autowired private ConvocatoriaTitleSearchBackfill backfill;

    private int sequence;

    @BeforeEach
    void setUp() {
        ConvocatoriaTestIndexes.resetCollectionsAndIndexes(mongoTemplate);
    }

    private Convocatoria insert(String title, Visibility visibility, int startDay, int endDay, DonationType... types) {
        sequence++;
        String code = String.format("PC%04d", sequence);
        Set<DonationType> accepted = types.length == 0 ? EnumSet.of(DonationType.MONETARY) : EnumSet.of(types[0], types);
        boolean monetary = accepted.contains(DonationType.MONETARY);
        Convocatoria c = Convocatoria.create("camp-" + code, "org-1", code, title, null, visibility,
                T0.plusSeconds(86_400L * startDay), T0.plusSeconds(86_400L * endDay),
                new ConvocatoriaConfiguration(accepted, monetary ? Set.of(PaymentMethod.GATEWAY) : Set.of(),
                        monetary ? "COP" : null, monetary ? 1000L : null, monetary ? TargetPolicy.FLEXIBLE : null, null));
        convocatorias.insert(c);
        return c;
    }

    private Convocatoria insert(String title, int startDay, int endDay, DonationType... types) {
        return insert(title, Visibility.PUBLIC, startDay, endDay, types);
    }

    private static Criteria criteria(String q, DonationType type, Sort sort) {
        return new Criteria(q, type, sort, null);
    }

    private List<String> codes(Criteria criteria) {
        return convocatorias.findPublicOpenPage(criteria, 100).stream().map(Convocatoria::getPublicCode).toList();
    }

    // --- test 3 ---

    @Test
    void theTextQuery_findsBySubstring_ignoringCapitalsAndAccents() {
        Convocatoria a = insert("Educación Rural en Boyacá", 1, 10);
        Convocatoria b = insert("EDUCACION para adultos", 1, 10);
        Convocatoria c = insert("Comedor comunitario", 1, 10);
        Convocatoria d = insert("Reeducación vial", 1, 10);

        assertThat(codes(criteria("educacion", null, Sort.DEFAULT)))
                .containsExactlyInAnyOrder(a.getPublicCode(), b.getPublicCode(), d.getPublicCode())
                .doesNotContain(c.getPublicCode());
        assertThat(codes(criteria("rural en boyaca", null, Sort.DEFAULT))).containsExactly(a.getPublicCode());
    }

    @Test
    void regexCharactersInTheTextQuery_areLiteral() {
        Convocatoria a = insert("Kits (escolares) [2026] .* \\E fin", 1, 10);
        insert("Kits escolares 2026", 1, 10);

        for (String q : List.of("(escolares)", "[2026]", ".*", "\\e fin")) {
            assertThat(codes(criteria(q, null, Sort.DEFAULT))).as(q).containsExactly(a.getPublicCode());
        }
    }

    // --- test 5 ---

    @Test
    void theDonationType_keepsOnlyCampaignsThatAcceptIt() {
        Convocatoria money = insert("Solo dinero", 1, 10, DonationType.MONETARY);
        Convocatoria both = insert("Las dos", 1, 10, DonationType.MONETARY, DonationType.IN_KIND);
        Convocatoria kind = insert("Solo especie", 1, 10, DonationType.IN_KIND);

        assertThat(codes(criteria(null, DonationType.IN_KIND, Sort.DEFAULT)))
                .containsExactlyInAnyOrder(both.getPublicCode(), kind.getPublicCode());
        assertThat(codes(criteria(null, DonationType.MONETARY, Sort.DEFAULT)))
                .containsExactlyInAnyOrder(money.getPublicCode(), both.getPublicCode());
    }

    // --- test 6 ---

    @Test
    void endingSoonAndRecent_haveTheFixedOrder_withThePublicCodeAsTieBreak() {
        Convocatoria p1 = insert("uno", 3, 20);
        Convocatoria p2 = insert("dos", 1, 10);
        Convocatoria p3 = insert("tres", 3, 10);
        Convocatoria p4 = insert("cuatro", 2, 15);

        assertThat(codes(criteria(null, null, Sort.ENDING_SOON))).containsExactly(
                p2.getPublicCode(), p3.getPublicCode(), p4.getPublicCode(), p1.getPublicCode());
        assertThat(codes(criteria(null, null, Sort.RECENT))).containsExactly(
                p1.getPublicCode(), p3.getPublicCode(), p4.getPublicCode(), p2.getPublicCode());
        assertThat(codes(criteria(null, null, Sort.DEFAULT))).containsExactly(
                p1.getPublicCode(), p2.getPublicCode(), p3.getPublicCode(), p4.getPublicCode());
    }

    // --- test 7 ---

    @Test
    void walkingEveryPage_returnsEachMatchingCampaignExactlyOnce_inTheThreeOrders() {
        List<Convocatoria> matching = new ArrayList<>();
        for (int i = 0; i < 47; i++) {
            // solo 3 fechas de inicio y 4 de fin: muchos empates
            matching.add(insert("Campaña " + i, i % 3, 30 + i % 4, DonationType.IN_KIND));
        }
        insert("Campaña sin especie", 1, 31, DonationType.MONETARY);

        for (Sort sort : Sort.values()) {
            List<String> walked = new ArrayList<>();
            Position after = null;
            int pages = 0;
            do {
                Page page = discovery.page(new Criteria("campana", DonationType.IN_KIND, sort, after));
                assertThat(page.items()).hasSizeLessThanOrEqualTo(PublicCampaignDiscoveryQuery.PAGE_SIZE);
                page.items().stream().map(PublicCampaignSummary::publicCode).forEach(walked::add);
                after = page.nextPosition();
                pages++;
            } while (after != null && pages < 10);

            assertThat(pages).as(sort.name()).isEqualTo(3);
            assertThat(walked).as(sort.name()).containsExactlyElementsOf(expectedOrder(matching, sort));
        }
    }

    private static List<String> expectedOrder(List<Convocatoria> all, Sort sort) {
        Comparator<Convocatoria> byCode = Comparator.comparing(Convocatoria::getPublicCode);
        Comparator<Convocatoria> order = switch (sort) {
            case DEFAULT -> byCode;
            case ENDING_SOON -> Comparator.comparing(Convocatoria::getEndDate).thenComparing(byCode);
            case RECENT -> Comparator.comparing(Convocatoria::getStartDate, Comparator.reverseOrder()).thenComparing(byCode);
        };
        return all.stream().sorted(order).map(Convocatoria::getPublicCode).toList();
    }

    // --- test 10 ---

    @Test
    void privateLinkAndClosedCampaigns_neverAppear_withAnyFilterOrOrder() {
        Convocatoria open = insert("Educación abierta", 1, 10, DonationType.MONETARY, DonationType.IN_KIND);
        Convocatoria privateLink = insert("Educación privada", Visibility.PRIVATE_LINK, 1, 10,
                DonationType.MONETARY, DonationType.IN_KIND);
        Convocatoria closed = insert("Educación cerrada", 1, 10, DonationType.MONETARY, DonationType.IN_KIND);
        assertThat(convocatorias.closeIfOpen(closed.getCampaignRef())).isTrue();

        for (Sort sort : Sort.values()) {
            for (String q : new String[] {null, "educacion"}) {
                for (DonationType type : new DonationType[] {null, DonationType.MONETARY, DonationType.IN_KIND}) {
                    assertThat(codes(criteria(q, type, sort))).as(sort + " " + q + " " + type)
                            .containsExactly(open.getPublicCode())
                            .doesNotContain(privateLink.getPublicCode(), closed.getPublicCode());
                }
            }
        }
    }

    // --- test 11 ---

    @Test
    void aSearchWithoutResults_isAnEmptyLastPage() {
        insert("Comedor comunitario", 1, 10);

        Page page = discovery.page(new Criteria("inexistente", null, Sort.ENDING_SOON, null));

        assertThat(page.items()).isEmpty();
        assertThat(page.nextPosition()).isNull();
    }

    // --- test 13 ---

    @Test
    void aNewCampaign_isStoredWithTitleSearch() {
        Convocatoria c = insert("Educación Rural", 1, 10);

        assertThat(document(c.getCampaignRef()).getString("titleSearch")).isEqualTo("educacion rural");
    }

    @Test
    void theBackfill_fillsOnlyTheCampaignsWithoutTitleSearch_andRunningItTwiceChangesNothing() {
        Convocatoria old1 = insert("Niños de Bogotá", 1, 10);
        Convocatoria old2 = insert("ÚTILES escolares", 1, 10);
        Convocatoria current = insert("Comedor", 1, 10);
        // dos "existentes" sin el campo, y una con un valor que el relleno no debe tocar
        for (Convocatoria c : List.of(old1, old2)) {
            mongoTemplate.updateFirst(Query.query(where("_id").is(c.getCampaignRef())), new Update().unset("titleSearch"),
                    ConvocatoriaDocument.class);
        }
        mongoTemplate.updateFirst(Query.query(where("_id").is(current.getCampaignRef())),
                new Update().set("titleSearch", "marca del test"), ConvocatoriaDocument.class);

        assertThat(backfill.backfill()).isEqualTo(2);

        assertThat(document(old1.getCampaignRef()).getString("titleSearch")).isEqualTo("ninos de bogota");
        assertThat(document(old2.getCampaignRef()).getString("titleSearch")).isEqualTo("utiles escolares");
        assertThat(document(current.getCampaignRef()).getString("titleSearch")).isEqualTo("marca del test");
        List<Document> before = all();

        assertThat(backfill.backfill()).isZero();
        assertThat(all()).isEqualTo(before);
        assertThat(codes(criteria("bogota", null, Sort.DEFAULT))).containsExactly(old1.getPublicCode());
    }

    private Document document(String campaignRef) {
        return mongoTemplate.getCollection(ConvocatoriaDocument.COLLECTION).find(new Document("_id", campaignRef)).first();
    }

    private List<Document> all() {
        return mongoTemplate.getCollection(ConvocatoriaDocument.COLLECTION).find().sort(new Document("_id", 1))
                .into(new ArrayList<>());
    }
}
