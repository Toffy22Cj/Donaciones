package com.traceability.app.web.campaign;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.f4b6a3.ulid.UlidCreator;
import com.traceability.api.web.InvalidRequestFieldException;
import com.traceability.app.TraceabilityApplication;
import com.traceability.convocatoria.application.port.out.ConvocatoriaRepositoryPort;
import com.traceability.convocatoria.domain.model.Convocatoria;
import com.traceability.convocatoria.domain.model.ConvocatoriaConfiguration;
import com.traceability.convocatoria.domain.model.DonationType;
import com.traceability.convocatoria.domain.model.PaymentMethod;
import com.traceability.convocatoria.domain.model.TargetPolicy;
import com.traceability.convocatoria.domain.model.Visibility;
import com.traceability.convocatoria.infrastructure.persistence.mongo.document.ConvocatoriaDocument;
import identity.application.service.CreateAccountService;
import identity.application.service.CreateOrganizationService;
import identity.domain.model.Account;
import identity.domain.model.AuditActor;
import identity.domain.model.Email;
import identity.domain.model.OrganizationType;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Ficha R-1 por HTTP (plan R-1 §3; ficha §8, tests 3 a 12), contra Tomcat real. Las convocatorias se insertan con el
 * puerto del repositorio (con su mapeador, que escribe {@code titleSearch}) para fijar títulos, fechas, visibilidad y
 * tipos; la colección se vacía antes de cada test.
 * <p>
 * El cuerpo del 400 es fijo ({@code InvalidRequestFieldException}: el campo no sale en la respuesta), así que el campo
 * que nombra cada error se comprueba llamando al controlador del contexto, y el estado, por HTTP.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = TraceabilityApplication.class)
@Testcontainers
@TestPropertySource(properties = {
    "crypto.anchor.poll.delay=9999999",
    "crypto.anchor.submit.delay=9999999",
    "crypto.anchor.stuck-monitor.delay=9999999",
    "crypto.web3j.private-key=0x1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef",
    "crypto.web3j.node-url=http://dummy-node",
    "spring.ai.openai.api-key=dummy-api-key"
})
class PublicCampaignDiscoveryHttpIntegrationTest {

    @Container
    static MongoDBContainer mongo = new MongoDBContainer(DockerImageName.parse("mongo:6.0")).withCommand("--replSet", "rs0");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongo::getReplicaSetUrl);
    }

    private static final Instant T0 = Instant.parse("2027-01-01T00:00:00Z");
    private static final List<String> ITEM_FIELDS = List.of("publicCode", "title", "organizationName",
            "organizationVerified", "status", "startDate", "endDate", "acceptedDonationTypes", "currency",
            "targetAmount", "clearedAmount");

    @LocalServerPort private int port;
    @Autowired private MongoTemplate mongoTemplate;
    @Autowired private ConvocatoriaRepositoryPort convocatorias;
    @Autowired private DiscoveryCursorCodec cursors;
    @Autowired private PublicCampaignDiscoveryController controller;
    @Autowired private CreateAccountService accounts;
    @Autowired private CreateOrganizationService organizations;

    /** Una organización real, para que cada ítem lleve su nombre público como antes del cambio. */
    private static String organizationRef;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void emptyCampaigns() {
        if (organizationRef == null) {
            Account representative = accounts.createAccount(new Email(UUID.randomUUID() + "@r1.test"), "Pass123!Pass123!");
            organizationRef = organizations.createOrganization(new AuditActor.SystemAuditActor("r1-tests"),
                    OrganizationType.FOUNDATION, representative.getAccountId(), "Fundación R-1")
                    .getOrganizationId().value();
        }
        mongoTemplate.getCollection(ConvocatoriaDocument.COLLECTION).deleteMany(new Document());
    }

    private Convocatoria insert(String title, Visibility visibility, int startDay, int endDay, DonationType... types) {
        String code = UlidCreator.getMonotonicUlid().toString();
        Set<DonationType> accepted = types.length == 0 ? EnumSet.of(DonationType.MONETARY) : EnumSet.of(types[0], types);
        boolean monetary = accepted.contains(DonationType.MONETARY);
        Convocatoria c = Convocatoria.create("camp-" + code, organizationRef, code, title, null, visibility,
                T0.plusSeconds(86_400L * startDay), T0.plusSeconds(86_400L * endDay),
                new ConvocatoriaConfiguration(accepted, monetary ? Set.of(PaymentMethod.GATEWAY) : Set.of(),
                        monetary ? "COP" : null, monetary ? 1000L : null, monetary ? TargetPolicy.FLEXIBLE : null, null));
        convocatorias.insert(c);
        return c;
    }

    private Convocatoria insert(String title, int startDay, int endDay, DonationType... types) {
        return insert(title, Visibility.PUBLIC, startDay, endDay, types);
    }

    private HttpResponse<String> get(Map<String, String> params) throws Exception {
        String query = params.entrySet().stream()
                .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        URI uri = URI.create("http://localhost:" + port + "/api/v1/public/campaigns" + (query.isEmpty() ? "" : "?" + query));
        return http.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode ok(Map<String, String> params) throws Exception {
        HttpResponse<String> r = get(params);
        assertThat(r.statusCode()).as(params + " → " + r.body()).isEqualTo(200);
        return json.readTree(r.body());
    }

    private static Map<String, String> params(String... keyValues) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            m.put(keyValues[i], keyValues[i + 1]);
        }
        return m;
    }

    private static List<String> codes(JsonNode page) {
        List<String> out = new ArrayList<>();
        page.get("items").forEach(i -> out.add(i.get("publicCode").asText()));
        return out;
    }

    /** Recorre todas las páginas con los mismos parámetros y devuelve los códigos en orden. */
    private List<String> walk(Map<String, String> filters) throws Exception {
        List<String> all = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            Map<String, String> p = new LinkedHashMap<>(filters);
            if (cursor != null) p.put("cursor", cursor);
            JsonNode page = ok(p);
            assertThat(page.get("items").size()).isLessThanOrEqualTo(20);
            all.addAll(codes(page));
            cursor = page.has("nextCursor") ? page.get("nextCursor").asText() : null;
            pages++;
        } while (cursor != null && pages < 20);
        return all;
    }

    private void rejects(String field, String q, String donationType, String sort, String cursor) throws Exception {
        Map<String, String> p = new LinkedHashMap<>();
        if (q != null) p.put("q", q);
        if (donationType != null) p.put("donationType", donationType);
        if (sort != null) p.put("sort", sort);
        if (cursor != null) p.put("cursor", cursor);
        HttpResponse<String> r = get(p);
        assertThat(r.statusCode()).as(p.toString()).isEqualTo(400);
        assertThat(json.readTree(r.body()).get("title").asText()).isEqualTo("BadRequest");
        assertThatThrownBy(() -> controller.discover(q, donationType, sort, cursor)).as(p.toString())
                .isInstanceOf(InvalidRequestFieldException.class).hasMessage("Invalid request field: " + field);
    }

    // --- test 3 ---

    @Test
    void q_findsBySubstring_ignoringCapitalsAndAccents_andLeavesOutTheRest() throws Exception {
        Convocatoria a = insert("Educación Rural en Boyacá", 1, 10);
        Convocatoria b = insert("EDUCACION para adultos", 1, 10);
        Convocatoria c = insert("Comedor comunitario", 1, 10);

        for (String q : List.of("educacion", "EDUCACIÓN", "Educación", "ducaci")) {
            assertThat(codes(ok(params("q", q)))).as(q).containsExactlyInAnyOrder(a.getPublicCode(), b.getPublicCode())
                    .doesNotContain(c.getPublicCode());
        }
        assertThat(codes(ok(params("q", "boyaca")))).containsExactly(a.getPublicCode());
        assertThat(codes(ok(params("q", "(adultos")))).as("un paréntesis no es un operador").isEmpty();
    }

    // --- test 4 ---

    @Test
    void q_outsideTwoToOneHundredCodePoints_orBlank_is400Q_andSurroundingSpacesAreTrimmed() throws Exception {
        Convocatoria a = insert("Educación Rural", 1, 10);

        rejects("q", "e", null, null, null);
        rejects("q", "a".repeat(101), null, null, null);
        rejects("q", "𝔸".repeat(101), null, null, null);
        rejects("q", "", null, null, null);
        rejects("q", "    ", null, null, null);
        rejects("q", "  e  ", null, null, null);

        assertThat(codes(ok(params("q", "  educación  ")))).containsExactly(a.getPublicCode());
        assertThat(codes(ok(params("q", "ed")))).containsExactly(a.getPublicCode());
        // 100 puntos de código fuera del plano básico son 200 caracteres Java: se cuentan puntos de código
        assertThat(ok(params("q", "𝔸".repeat(100))).get("items")).isEmpty();
        assertThat(ok(params("q", "a".repeat(100))).get("items")).isEmpty();
    }

    // --- test 5 ---

    @Test
    void donationTypeInKind_returnsOnlyCampaignsThatAcceptIt_andAnotherValueIs400DonationType() throws Exception {
        Convocatoria money = insert("Solo dinero", 1, 10, DonationType.MONETARY);
        Convocatoria both = insert("Las dos", 1, 10, DonationType.MONETARY, DonationType.IN_KIND);
        Convocatoria kind = insert("Solo especie", 1, 10, DonationType.IN_KIND);

        JsonNode inKind = ok(params("donationType", "IN_KIND"));
        assertThat(codes(inKind)).containsExactlyInAnyOrder(both.getPublicCode(), kind.getPublicCode());
        inKind.get("items").forEach(i -> assertThat(i.get("acceptedDonationTypes").toString()).contains("IN_KIND"));
        assertThat(codes(ok(params("donationType", "MONETARY"))))
                .containsExactlyInAnyOrder(money.getPublicCode(), both.getPublicCode());

        rejects("donationType", null, "OTRO", null, null);
        rejects("donationType", null, "in_kind", null, null);
        rejects("donationType", null, "", null, null);
    }

    // --- test 6 ---

    @Test
    void sortEndingSoonAndRecent_haveTheFixedOrderWithTheTieBreak_andAnotherValueIs400Sort() throws Exception {
        Convocatoria p1 = insert("uno", 3, 20);
        Convocatoria p2 = insert("dos", 1, 10);
        Convocatoria p3 = insert("tres", 3, 10);
        Convocatoria p4 = insert("cuatro", 2, 15);

        assertThat(codes(ok(params("sort", "ending_soon")))).containsExactly(
                p2.getPublicCode(), p3.getPublicCode(), p4.getPublicCode(), p1.getPublicCode());
        assertThat(codes(ok(params("sort", "recent")))).containsExactly(
                p1.getPublicCode(), p3.getPublicCode(), p4.getPublicCode(), p2.getPublicCode());

        rejects("sort", null, null, "OTRO", null);
        rejects("sort", null, null, "ENDING_SOON", null);
        rejects("sort", null, null, "", null);
    }

    @Test
    void theValidationOrder_isQ_thenDonationType_thenSort_thenCursor() throws Exception {
        rejects("q", "x", "OTRO", "OTRO", "!!");
        rejects("donationType", "educacion", "OTRO", "OTRO", "!!");
        rejects("sort", "educacion", "IN_KIND", "OTRO", "!!");
        rejects("cursor", "educacion", "IN_KIND", "recent", "!!");
    }

    // --- test 7 ---

    @Test
    void walkingEveryPageWithFiltersAndOrder_returnsEachMatchingCampaignExactlyOnce() throws Exception {
        List<Convocatoria> matching = new ArrayList<>();
        for (int i = 0; i < 45; i++) {
            matching.add(insert("Campaña de útiles " + i, i % 3, 30 + i % 4, DonationType.IN_KIND));
        }
        insert("Campaña de útiles sin especie", 1, 31, DonationType.MONETARY);
        insert("Otra cosa", 1, 31, DonationType.IN_KIND);

        Comparator<Convocatoria> byCode = Comparator.comparing(Convocatoria::getPublicCode);
        Map<String, Comparator<Convocatoria>> orders = new LinkedHashMap<>();
        orders.put(null, byCode);
        orders.put("ending_soon", Comparator.comparing(Convocatoria::getEndDate).thenComparing(byCode));
        orders.put("recent", Comparator.comparing(Convocatoria::getStartDate, Comparator.reverseOrder()).thenComparing(byCode));

        for (Map.Entry<String, Comparator<Convocatoria>> order : orders.entrySet()) {
            Map<String, String> filters = params("q", "útiles", "donationType", "IN_KIND");
            if (order.getKey() != null) filters.put("sort", order.getKey());
            List<String> walked = walk(filters);
            assertThat(walked).as(String.valueOf(order.getKey())).doesNotHaveDuplicates()
                    .containsExactlyElementsOf(matching.stream().sorted(order.getValue()).map(Convocatoria::getPublicCode).toList());
        }
    }

    // --- test 8 ---

    @Test
    void aCursorUsedWithOtherFiltersOrAnotherOrder_is400Cursor() throws Exception {
        for (int i = 0; i < 25; i++) {
            insert("Educación " + i, 1, 10 + i, DonationType.MONETARY, DonationType.IN_KIND);
        }
        String cursor = ok(params("q", "educacion", "donationType", "IN_KIND", "sort", "ending_soon"))
                .get("nextCursor").asText();

        // los mismos filtros, con q escrito de otra forma pero igual tras normalizar: sigue
        assertThat(codes(ok(params("q", "  EDUCACIÓN ", "donationType", "IN_KIND", "sort", "ending_soon", "cursor", cursor))))
                .hasSize(5);

        rejects("cursor", "educacio", "IN_KIND", "ending_soon", cursor);
        rejects("cursor", "educacion", "MONETARY", "ending_soon", cursor);
        rejects("cursor", "educacion", null, "ending_soon", cursor);
        rejects("cursor", "educacion", "IN_KIND", "recent", cursor);
        rejects("cursor", "educacion", "IN_KIND", null, cursor);
        rejects("cursor", null, "IN_KIND", "ending_soon", cursor);
        rejects("cursor", null, null, null, cursor);

        String unfiltered = ok(params()).get("nextCursor").asText();
        rejects("cursor", "educacion", null, null, unfiltered);
        rejects("cursor", null, null, "recent", unfiltered);
        rejects("cursor", null, "IN_KIND", null, unfiltered);
    }

    // --- test 9 ---

    @Test
    void aV1CursorWithoutFilters_continues_andWithQDonationTypeOrSort_is400Cursor() throws Exception {
        List<Convocatoria> all = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            all.add(insert("Educación " + i, 1, 10, DonationType.MONETARY, DonationType.IN_KIND));
        }
        List<String> sorted = all.stream().map(Convocatoria::getPublicCode).sorted().toList();
        // formato anterior: el último publicCode, cifrado tal cual
        String v1 = cursors.encode(sorted.get(1));

        assertThat(codes(ok(params("cursor", v1)))).containsExactlyElementsOf(sorted.subList(2, 5));

        rejects("cursor", "educacion", null, null, v1);
        rejects("cursor", null, "IN_KIND", null, v1);
        rejects("cursor", null, null, "ending_soon", v1);
        rejects("cursor", null, null, "recent", v1);
        // un texto cifrado que no es ni un publicCode ni un cursor v2
        rejects("cursor", null, null, null, cursors.encode("no-es-un-codigo"));
        rejects("cursor", null, null, null, cursors.encode("{\"v\":3,\"lastPublicCode\":\"" + sorted.get(0) + "\"}"));
    }

    // --- test 10 ---

    @Test
    void privateLinkAndClosed_neverAppear_withAnyFilterOrOrder() throws Exception {
        Convocatoria open = insert("Educación abierta", 1, 10, DonationType.MONETARY, DonationType.IN_KIND);
        Convocatoria privateLink = insert("Educación privada", Visibility.PRIVATE_LINK, 1, 10,
                DonationType.MONETARY, DonationType.IN_KIND);
        Convocatoria closed = insert("Educación cerrada", 1, 10, DonationType.MONETARY, DonationType.IN_KIND);
        assertThat(convocatorias.closeIfOpen(closed.getCampaignRef())).isTrue();

        for (String sort : new String[] {null, "ending_soon", "recent"}) {
            for (String q : new String[] {null, "educacion", "privada", "cerrada"}) {
                for (String type : new String[] {null, "MONETARY", "IN_KIND"}) {
                    Map<String, String> p = new LinkedHashMap<>();
                    if (q != null) p.put("q", q);
                    if (type != null) p.put("donationType", type);
                    if (sort != null) p.put("sort", sort);
                    HttpResponse<String> r = get(p);
                    assertThat(r.statusCode()).isEqualTo(200);
                    assertThat(r.body()).as(p.toString()).doesNotContain(privateLink.getPublicCode())
                            .doesNotContain(closed.getPublicCode()).doesNotContain("privada").doesNotContain("cerrada");
                    assertThat(codes(json.readTree(r.body()))).isSubsetOf(open.getPublicCode());
                }
            }
        }
    }

    // --- test 11 ---

    @Test
    void aSearchWithoutResults_is200WithEmptyItemsAndNoNextCursor() throws Exception {
        insert("Comedor comunitario", 1, 10);

        HttpResponse<String> r = get(params("q", "inexistente", "sort", "recent"));

        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(json.readTree(r.body())).isEqualTo(json.readTree("{\"items\":[]}"));
    }

    // --- test 12 ---

    @Test
    void withoutParameters_theResponseIsAsBefore_orderedByPublicCode_withTheSameItemShape() throws Exception {
        List<Convocatoria> all = new ArrayList<>();
        for (int i = 0; i < 23; i++) {
            all.add(insert("Convocatoria " + i, 22 - i, 40 - i));
        }

        JsonNode first = ok(params());
        assertThat(first.fieldNames()).toIterable().containsExactly("items", "nextCursor");
        first.get("items").forEach(i -> assertThat(i.fieldNames()).toIterable().containsExactlyInAnyOrderElementsOf(ITEM_FIELDS));
        assertThat(walk(params())).containsExactlyElementsOf(all.stream().map(Convocatoria::getPublicCode).sorted().toList());
    }
}
