package com.traceability.app.web.organization;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.app.TraceabilityApplication;
import com.traceability.contracts.authentication.TokenIssuerPort;
import identity.application.service.AddEmployeeService;
import identity.application.service.AssignAdministratorService;
import identity.application.service.BootstrapPlatformAuthorityService;
import identity.application.service.CreateAccountService;
import identity.application.service.CreateOrganizationService;
import identity.domain.model.AccountId;
import identity.domain.model.AuditActor;
import identity.domain.model.Email;
import identity.domain.model.InvitationToken;
import identity.domain.model.OrganizationId;
import identity.domain.model.OrganizationType;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Autorización (3) de Carlos, §3.3, y definición de hecho de ADR-049, contra Tomcat real con {@code identity} y
 * {@code convocatoria} reales: invitar, listar, revocar y aceptar invitaciones; cambiar el rol y quitar miembros. El
 * correo se captura con un {@link JavaMailSender} de test (el SMTP real con Mailpit está en
 * {@code InvitationMailpitIntegrationTest}); el reloj de los servicios es controlable para la caducidad.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = TraceabilityApplication.class)
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
@Import(OrganizationMembersAndInvitationsHttpIntegrationTest.TestBeans.class)
@TestPropertySource(properties = {
    "crypto.anchor.poll.delay=9999999",
    "crypto.anchor.submit.delay=9999999",
    "crypto.anchor.stuck-monitor.delay=9999999",
    "crypto.web3j.private-key=0x1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef",
    "crypto.web3j.node-url=http://dummy-node",
    "spring.ai.openai.api-key=dummy-api-key"
})
class OrganizationMembersAndInvitationsHttpIntegrationTest {

    static final String PASSWORD = "Pass123!Pass123!";
    static final String BASE = "https://web.tests.paxfide.local";
    static final Pattern LINK = Pattern.compile(Pattern.quote(BASE) + "/invitaciones#token=([A-Za-z0-9_-]+)");

    /** Reloj que el test mueve (caducidad); los servicios lo leen por {@code ObjectProvider<Clock>}. */
    static final class MovableClock extends Clock {
        volatile Duration offset = Duration.ZERO;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.now().plus(offset); }
    }

    /** Captura los correos; falla para los destinatarios que empiezan por {@code smtp-fail}. */
    static final class CapturingMailSender extends JavaMailSenderImpl {
        final List<SimpleMailMessage> sent = new CopyOnWriteArrayList<>();
        @Override public void send(SimpleMailMessage message) { send(new SimpleMailMessage[] {message}); }
        @Override public void send(SimpleMailMessage... messages) {
            for (SimpleMailMessage m : messages) {
                if (m.getTo()[0].startsWith("smtp-fail")) throw new MailSendException("servidor caído");
                sent.add(m);
            }
        }
    }

    @TestConfiguration
    static class TestBeans {
        static final MovableClock CLOCK = new MovableClock();
        static final CapturingMailSender MAIL = new CapturingMailSender();

        @Bean @Primary Clock movableClock() { return CLOCK; }
        @Bean @Primary JavaMailSender capturingMailSender() { return MAIL; }
    }

    @Container
    static MongoDBContainer mongo = new MongoDBContainer(DockerImageName.parse("mongo:6.0")).withCommand("--replSet", "rs0");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongo::getReplicaSetUrl);
    }

    @LocalServerPort private int port;
    @Autowired private TokenIssuerPort tokens;
    @Autowired private CreateAccountService accounts;
    @Autowired private CreateOrganizationService organizations;
    @Autowired private AddEmployeeService employees;
    @Autowired private AssignAdministratorService administrators;
    @Autowired private BootstrapPlatformAuthorityService bootstrap;
    @Autowired private MongoTemplate mongoTemplate;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();
    private static final AuditActor SETUP = new AuditActor.SystemAuditActor("members-tests");
    private static final String START = Instant.now().plus(Duration.ofDays(1)).toString();
    private static final String END = Instant.now().plus(Duration.ofDays(60)).toString();

    private static String platformAdmin, org, representative, admin, employee, otherOrg, otherAdmin;

    private String account(String email) {
        return accounts.createAccount(new Email(email), PASSWORD).getAccountId().value();
    }

    private static String email() {
        return "u" + UUID.randomUUID().toString().substring(0, 12) + "@members.test";
    }

    @BeforeEach
    void world() throws Exception {
        TestBeans.CLOCK.offset = Duration.ZERO;
        if (org != null) return;
        String platformEmail = email();
        account(platformEmail);
        platformAdmin = bootstrap.bootstrap(platformEmail).value();
        representative = account(email());
        OrganizationId o = organizations.createOrganization(SETUP, OrganizationType.FOUNDATION, new AccountId(representative),
                "Fundación Miembros").getOrganizationId();
        org = o.value();
        admin = account(email());
        employees.addEmployee(SETUP, o, new AccountId(admin));
        administrators.assignAdministrator(SETUP, o, new AccountId(admin));
        employee = account(email());
        employees.addEmployee(SETUP, o, new AccountId(employee));
        String otherRep = account(email());
        OrganizationId other = organizations.createOrganization(SETUP, OrganizationType.COMPANY, new AccountId(otherRep), "Otra")
                .getOrganizationId();
        otherOrg = other.value();
        otherAdmin = account(email());
        employees.addEmployee(SETUP, other, new AccountId(otherAdmin));
        administrators.assignAdministrator(SETUP, other, new AccountId(otherAdmin));
        ok(send("POST", "/api/v1/platform/organizations/" + org + "/verify", platformAdmin, null), 200);
    }

    private HttpResponse<String> send(String method, String path, String account, String body) throws Exception {
        HttpRequest.Builder r = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (account != null) r.header("Authorization", "Bearer " + tokens.issue(account));
        if (method.equals("POST")) r.header("Command-Id", UUID.randomUUID().toString());
        if (body != null) r.header("Content-Type", "application/json");
        return http.send(r.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode ok(HttpResponse<String> r, int status) throws Exception {
        assertThat(r.statusCode()).as(r.body()).isEqualTo(status);
        return r.body().isEmpty() ? null : json.readTree(r.body());
    }

    private String title(HttpResponse<String> r) throws Exception {
        return json.readTree(r.body()).get("title").asText();
    }

    private HttpResponse<String> invite(String by, String organization, String email, String role) throws Exception {
        return send("POST", "/api/v1/organizations/" + organization + "/invitations", by,
                "{\"email\":\"" + email + "\",\"role\":\"" + role + "\"}");
    }

    /** Invita y devuelve el token del correo capturado. */
    private String invitedToken(String email, String role) throws Exception {
        int before = TestBeans.MAIL.sent.size();
        ok(invite(admin, org, email, role), 202);
        assertThat(TestBeans.MAIL.sent).hasSize(before + 1);
        SimpleMailMessage mail = TestBeans.MAIL.sent.get(before);
        Matcher m = LINK.matcher(mail.getText());
        assertThat(m.find()).as(mail.getText()).isTrue();
        return m.group(1);
    }

    private HttpResponse<String> accept(String account, String token) throws Exception {
        return send("POST", "/api/v1/invitations/accept", account, "{\"token\":\"" + token + "\"}");
    }

    private JsonNode me(String account) throws Exception {
        return ok(send("GET", "/api/v1/me", account, null), 200);
    }

    private List<JsonNode> pending(String by) throws Exception {
        JsonNode page = ok(send("GET", "/api/v1/organizations/" + org + "/invitations", by, null), 200);
        return StreamSupport.stream(page.get("items").spliterator(), false).toList();
    }

    // --- invitar ---

    @Test
    void theInvitation_storesOnlyTheHashOfA256BitToken_andTheMailCarriesItInTheFragment() throws Exception {
        String email = email();
        String token = invitedToken(email, "EMPLOYEE");
        assertThat(Base64.getUrlDecoder().decode(token)).hasSize(32);

        SimpleMailMessage mail = TestBeans.MAIL.sent.get(TestBeans.MAIL.sent.size() - 1);
        assertThat(mail.getTo()).containsExactly(email);
        assertThat(mail.getSubject()).isEqualTo("Invitación a Fundación Miembros en PaxFide");
        // el token solo después de '#': nunca en la ruta ni en la query
        assertThat(mail.getText()).contains(BASE + "/invitaciones#token=" + token).doesNotContain("?token")
                .doesNotContain("/invitaciones/" + token).contains("empleado").contains("caduca el");
        // ningún dato de quien invita ni de terceros
        assertThat(mail.getText()).doesNotContain(admin).doesNotContain(representative).doesNotContain(org);

        List<Document> stored = mongoTemplate.getCollection("organization_invitations").find(new Document("email", email))
                .into(new ArrayList<>());
        assertThat(stored).hasSize(1);
        assertThat(stored.get(0).toJson()).doesNotContain(token);
        assertThat(stored.get(0).getString("tokenHash")).isEqualTo(InvitationToken.hashOf(token));
        assertThat(stored.get(0).getString("delivery")).isEqualTo("SENT");
    }

    @Test
    void invitingAnEmailWithAndWithoutAnAccount_givesExactlyTheSameAnswer() throws Exception {
        String withAccount = email();
        account(withAccount);
        HttpResponse<String> a = invite(admin, org, withAccount, "EMPLOYEE");
        HttpResponse<String> b = invite(admin, org, email(), "EMPLOYEE");
        String inOtherOrg = email();
        String member = account(inOtherOrg);
        employees.addEmployee(SETUP, new OrganizationId(otherOrg), new AccountId(member));
        HttpResponse<String> c = invite(admin, org, inOtherOrg, "EMPLOYEE");
        for (HttpResponse<String> r : List.of(a, b, c)) {
            assertThat(r.statusCode()).isEqualTo(202);
            assertThat(json.readTree(r.body()).fieldNames()).toIterable()
                    .containsExactly("invitationId", "role", "expiresAt");
            assertThat(r.headers().map().keySet()).isEqualTo(a.headers().map().keySet());
        }
    }

    @Test
    void onlyAdministratorOrRepresentative_invite_andTheRolesAreAdministratorOrEmployee() throws Exception {
        ok(invite(representative, org, email(), "ADMINISTRATOR"), 202);
        for (String role : List.of("REPRESENTATIVE", "OWNER", "employee", "")) {
            HttpResponse<String> r = invite(admin, org, email(), role);
            assertThat(r.statusCode()).as(role).isEqualTo(400);
            assertThat(title(r)).isEqualTo("InvalidMemberRole");
        }
        assertThat(invite(admin, org, "not-an-email", "EMPLOYEE").statusCode()).isEqualTo(400);
        String forbidden = title(invite(employee, org, email(), "EMPLOYEE"));
        for (HttpResponse<String> r : List.of(invite(employee, org, email(), "EMPLOYEE"),
                invite(otherAdmin, org, email(), "EMPLOYEE"), invite(admin, otherOrg, email(), "EMPLOYEE"),
                invite(admin, UUID.randomUUID().toString(), email(), "EMPLOYEE"),
                // autoriza antes de validar: quien no gestiona recibe 403 aunque el cuerpo sea inválido
                invite(employee, org, "not-an-email", "REPRESENTATIVE"),
                send("GET", "/api/v1/organizations/" + org + "/invitations", employee, null))) {
            assertThat(r.statusCode()).isEqualTo(403);
            assertThat(title(r)).isEqualTo(forbidden);
        }
    }

    @Test
    void anSmtpFailure_stillAnswers202_andIsVisibleAsFailedDelivery_withoutLeakingTheEmail(CapturedOutput output) throws Exception {
        String email = "smtp-fail-" + UUID.randomUUID().toString().substring(0, 8) + "@members.test";
        JsonNode issued = ok(invite(admin, org, email, "EMPLOYEE"), 202);
        JsonNode row = pending(admin).stream()
                .filter(i -> i.get("invitationId").asText().equals(issued.get("invitationId").asText())).findFirst().orElseThrow();
        assertThat(row.get("delivery").asText()).isEqualTo("FAILED");
        assertThat(output.getAll()).contains(issued.get("invitationId").asText()).doesNotContain(email);
    }

    // --- listar y revocar ---

    @Test
    void theList_masksTheEmail_andNeverShowsTheTokenOrItsHash() throws Exception {
        String email = email();
        String token = invitedToken(email, "ADMINISTRATOR");
        HttpResponse<String> r = send("GET", "/api/v1/organizations/" + org + "/invitations", representative, null);
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.headers().firstValue("Cache-Control")).hasValueSatisfying(v -> assertThat(v).contains("no-store"));
        assertThat(r.body()).doesNotContain(email).doesNotContain(token).doesNotContain(InvitationToken.hashOf(token))
                .contains(email.charAt(0) + "***@members.test");
        for (JsonNode item : json.readTree(r.body()).get("items")) {
            assertThat(item.fieldNames()).toIterable().containsExactlyInAnyOrder("invitationId", "emailMasked", "role",
                    "createdAt", "expiresAt", "delivery");
        }
    }

    @Test
    void reinvitingTheSameEmail_revokesThePreviousLink() throws Exception {
        String email = email();
        String invitee = account(email);
        String first = invitedToken(email, "EMPLOYEE");
        String second = invitedToken(email.toUpperCase(), "ADMINISTRATOR");
        assertThat(pending(admin).stream().filter(i -> i.get("emailMasked").asText().startsWith(email.substring(0, 1)))
                .filter(i -> i.get("emailMasked").asText().endsWith("@members.test")).count()).isGreaterThanOrEqualTo(1);
        assertThat(accept(invitee, first).statusCode()).isEqualTo(403);
        JsonNode accepted = ok(accept(invitee, second), 200);
        assertThat(accepted.get("roles")).extracting(JsonNode::asText).containsExactly("ADMINISTRATOR", "EMPLOYEE");
    }

    @Test
    void revoking_aPendingInvitation_invalidatesItsLink_andRevokingAgainIs409() throws Exception {
        String email = email();
        String invitee = account(email);
        String token = invitedToken(email, "EMPLOYEE");
        String id = pending(admin).stream().map(i -> i.get("invitationId").asText()).reduce((x, y) -> y).orElseThrow();
        String path = "/api/v1/organizations/" + org + "/invitations/" + id + "/revoke";
        assertThat(send("POST", "/api/v1/organizations/" + otherOrg + "/invitations/" + id + "/revoke", otherAdmin, null)
                .statusCode()).isEqualTo(403);
        assertThat(send("POST", path, employee, null).statusCode()).isEqualTo(403);
        JsonNode revoked = ok(send("POST", path, admin, null), 200);
        assertThat(revoked.get("status").asText()).isEqualTo("REVOKED");
        HttpResponse<String> again = send("POST", path, admin, null);
        assertThat(again.statusCode()).isEqualTo(409);
        assertThat(title(again)).isEqualTo("InvitationNotPending");
        assertThat(accept(invitee, token).statusCode()).isEqualTo(403);
        assertThat(send("POST", "/api/v1/organizations/" + org + "/invitations/" + UUID.randomUUID() + "/revoke", admin, null)
                .statusCode()).isEqualTo(403);
    }

    // --- aceptar ---

    @Test
    void accepting_withTheInvitedAccount_joinsWithTheInvitedRole_effectiveOnTheNextRequest() throws Exception {
        String email = email();
        String invitee = account(email);
        String token = invitedToken(email.toUpperCase(), "EMPLOYEE");
        assertThat(me(invitee).has("organizationId")).isFalse();

        JsonNode accepted = ok(accept(invitee, token), 200);
        assertThat(accepted.get("organizationId").asText()).isEqualTo(org);
        assertThat(accepted.get("roles")).extracting(JsonNode::asText).containsExactly("EMPLOYEE");
        JsonNode me = me(invitee);
        assertThat(me.get("organizationId").asText()).isEqualTo(org);
        assertThat(me.get("roles")).extracting(JsonNode::asText).containsExactly("EMPLOYEE");
        // usado: no vale otra vez
        assertThat(accept(invitee, token).statusCode()).isEqualTo(403);
    }

    @Test
    void everyRejection_isTheSame403_andAnotherAccountLeavesTheInvitationPending() throws Exception {
        String email = email();
        String invitee = account(email);
        String token = invitedToken(email, "EMPLOYEE");
        String stranger = account(email());

        HttpResponse<String> otherAccount = accept(stranger, token);
        HttpResponse<String> unknown = accept(invitee, "A".repeat(43));
        HttpResponse<String> empty = send("POST", "/api/v1/invitations/accept", invitee, "{}");
        HttpResponse<String> inQuery = send("POST", "/api/v1/invitations/accept?token=" + token, invitee,
                "{\"token\":\"" + token + "\"}");
        for (HttpResponse<String> r : List.of(otherAccount, unknown, empty, inQuery)) {
            assertThat(r.statusCode()).isEqualTo(403);
            assertThat(json.readTree(r.body()).path("detail")).isEqualTo(json.readTree(otherAccount.body()).path("detail"));
            assertThat(title(r)).isEqualTo("InvitationNotAcceptable");
            assertThat(r.body()).doesNotContain(token);
        }
        assertThat(send("POST", "/api/v1/invitations/accept", null, "{\"token\":\"" + token + "\"}").statusCode())
                .isEqualTo(401);
        // la invitación sigue pendiente y la cuenta correcta todavía puede aceptarla
        ok(accept(invitee, token), 200);
    }

    @Test
    void anExpiredInvitation_isRejected_andDisappearsFromTheList() throws Exception {
        String email = email();
        String invitee = account(email);
        String token = invitedToken(email, "EMPLOYEE");
        try {
            TestBeans.CLOCK.offset = Duration.ofDays(7).plusSeconds(1);
            assertThat(accept(invitee, token).statusCode()).isEqualTo(403);
            assertThat(pending(admin).toString()).doesNotContain(email.substring(1, 6));
        } finally {
            TestBeans.CLOCK.offset = Duration.ZERO;
        }
        TestBeans.CLOCK.offset = Duration.ofDays(6).plusHours(23);
        try {
            ok(accept(invitee, token), 200);
        } finally {
            TestBeans.CLOCK.offset = Duration.ZERO;
        }
    }

    @Test
    void anAccountThatAlreadyBelongsToAnOrganization_gets409_onlyAfterTheTokenAndEmailAreValid() throws Exception {
        String email = email();
        String member = account(email);
        employees.addEmployee(SETUP, new OrganizationId(otherOrg), new AccountId(member));
        String token = invitedToken(email, "EMPLOYEE");
        HttpResponse<String> r = accept(member, token);
        assertThat(r.statusCode()).isEqualTo(409);
        assertThat(title(r)).isEqualTo("AccountAlreadyBelongsToOrganization");
    }

    @Test
    void twoSimultaneousAcceptancesOfTheSameToken_onlyOneWins() throws Exception {
        String email = email();
        String invitee = account(email);
        String token = invitedToken(email, "EMPLOYEE");
        CountDownLatch go = new CountDownLatch(1);
        List<CompletableFuture<HttpResponse<String>>> calls = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            calls.add(CompletableFuture.supplyAsync(() -> {
                try {
                    go.await();
                    return accept(invitee, token);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }));
        }
        go.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (CompletableFuture<HttpResponse<String>> c : calls) statuses.add(c.get().statusCode());
        assertThat(statuses).containsOnlyOnce(200);
        assertThat(statuses).allMatch(s -> s == 200 || s == 403 || s == 409);
        long memberships = StreamSupport.stream(ok(send("GET", "/api/v1/organizations/" + org + "/members", admin, null), 200)
                .get("items").spliterator(), false).filter(m -> m.get("accountId").asText().equals(invitee)).count();
        assertThat(memberships).isEqualTo(1);
    }

    @Test
    void theTokenNeverAppearsInTheLogs(CapturedOutput output) throws Exception {
        String email = email();
        String invitee = account(email);
        String token = invitedToken(email, "ADMINISTRATOR");
        accept(account(email()), token);
        accept(invitee, token + "x");
        send("POST", "/api/v1/invitations/accept?token=" + token, invitee, "{}");
        ok(accept(invitee, token), 200);
        assertThat(output.getAll()).doesNotContain(token).doesNotContain(InvitationToken.hashOf(token));
    }

    // --- cambiar rol y quitar ---

    private String newMember(String role) throws Exception {
        String email = email();
        String id = account(email);
        ok(accept(id, invitedToken(email, role)), 200);
        return id;
    }

    private HttpResponse<String> changeRole(String by, String member, String role) throws Exception {
        return send("POST", "/api/v1/organizations/" + org + "/members/" + member + "/role", by,
                "{\"role\":\"" + role + "\"}");
    }

    private HttpResponse<String> remove(String by, String member) throws Exception {
        return send("POST", "/api/v1/organizations/" + org + "/members/" + member + "/remove", by, null);
    }

    private String campaign() throws Exception {
        String body = """
                {"title":"Responsables","visibility":"PUBLIC","startDate":"%s","endDate":"%s",
                 "configuration":{"acceptedDonationTypes":["IN_KIND"]}}""".formatted(START, END);
        return ok(send("POST", "/api/v1/organizations/" + org + "/campaigns", admin, body), 201).get("campaignRef").asText();
    }

    @Test
    void changingTheRole_isRestrictive_whenTheMemberIsAlreadyInThatState() throws Exception {
        String member = newMember("EMPLOYEE");
        HttpResponse<String> same = changeRole(admin, member, "EMPLOYEE");
        assertThat(same.statusCode()).isEqualTo(409);
        assertThat(title(same)).isEqualTo("MemberAlreadyHasRole");
        assertThat(ok(changeRole(admin, member, "ADMINISTRATOR"), 200).get("roles")).extracting(JsonNode::asText)
                .containsExactly("ADMINISTRATOR", "EMPLOYEE");
        assertThat(changeRole(representative, member, "ADMINISTRATOR").statusCode()).isEqualTo(409);
        assertThat(ok(changeRole(representative, member, "EMPLOYEE"), 200).get("roles")).extracting(JsonNode::asText)
                .containsExactly("EMPLOYEE");
        assertThat(me(member).get("roles")).extracting(JsonNode::asText).containsExactly("EMPLOYEE");
        assertThat(changeRole(admin, member, "REPRESENTATIVE").statusCode()).isEqualTo(400);
        // quien no gestiona, otra organización o un no miembro: el mismo 403, antes de validar el rol
        String forbidden = title(changeRole(employee, member, "ADMINISTRATOR"));
        for (HttpResponse<String> r : List.of(changeRole(employee, member, "REPRESENTATIVE"),
                changeRole(otherAdmin, member, "ADMINISTRATOR"), changeRole(admin, otherAdmin, "EMPLOYEE"),
                changeRole(admin, UUID.randomUUID().toString(), "EMPLOYEE"))) {
            assertThat(r.statusCode()).isEqualTo(403);
            assertThat(title(r)).isEqualTo(forbidden);
        }
    }

    @Test
    void theRepresentativesRoles_areOnlyChangedByTheRepresentative() throws Exception {
        HttpResponse<String> byAdmin = changeRole(admin, representative, "ADMINISTRATOR");
        assertThat(byAdmin.statusCode()).isEqualTo(403);
        JsonNode self = ok(changeRole(representative, representative, "ADMINISTRATOR"), 200);
        assertThat(self.get("roles")).extracting(JsonNode::asText).containsExactly("ADMINISTRATOR", "REPRESENTATIVE");
        JsonNode back = ok(changeRole(representative, representative, "EMPLOYEE"), 200);
        assertThat(back.get("roles")).extracting(JsonNode::asText).containsExactly("EMPLOYEE", "REPRESENTATIVE");
    }

    @Test
    void theRepresentative_isNeverRemoved_soTheOrganizationAlwaysHasOne() throws Exception {
        HttpResponse<String> r = remove(admin, representative);
        assertThat(r.statusCode()).isEqualTo(409);
        assertThat(title(r)).isEqualTo("RepresentativeTransferRequired");
        assertThat(remove(representative, representative).statusCode()).isEqualTo(409);
        assertThat(me(representative).get("roles")).extracting(JsonNode::asText).contains("REPRESENTATIVE");
    }

    @Test
    void anActiveCampaignResponsible_isNeitherDemotedNorRemoved_untilReplacedOrTheCampaignCloses() throws Exception {
        String responsibleAdmin = newMember("ADMINISTRATOR");
        String responsibleEmployee = newMember("EMPLOYEE");
        String campaignRef = campaign();
        String base = "/api/v1/campaigns/" + campaignRef;
        ok(send("POST", base + "/administrators", admin, "{\"administratorRef\":\"" + responsibleAdmin + "\"}"), 201);
        ok(send("POST", base + "/employees", admin, "{\"employeeRef\":\"" + responsibleEmployee + "\"}"), 201);

        HttpResponse<String> demote = changeRole(admin, responsibleAdmin, "EMPLOYEE");
        assertThat(demote.statusCode()).isEqualTo(409);
        assertThat(title(demote)).isEqualTo("ActiveCampaignResponsible");
        assertThat(remove(admin, responsibleAdmin).statusCode()).isEqualTo(409);
        HttpResponse<String> removeEmployee = remove(admin, responsibleEmployee);
        assertThat(removeEmployee.statusCode()).isEqualTo(409);
        assertThat(title(removeEmployee)).isEqualTo("ActiveCampaignResponsible");
        // ascender a un responsable EMPLOYEE no rompe nada
        ok(changeRole(admin, responsibleEmployee, "ADMINISTRATOR"), 200);

        // reemplazado (DD-50), ya se puede quitar; y el principal deja de tener organización
        ok(send("POST", base + "/responsibles/" + responsibleEmployee + "/remove", admin, null), 200);
        JsonNode removed = ok(remove(admin, responsibleEmployee), 200);
        assertThat(removed.get("removed").asBoolean()).isTrue();
        JsonNode me = me(responsibleEmployee);
        assertThat(me.has("organizationId")).isFalse();
        assertThat(send("GET", "/api/v1/organizations/" + org + "/members", responsibleEmployee, null).statusCode())
                .isEqualTo(403);

        // con la convocatoria cerrada, el administrador deja de contar como responsable activo
        ok(send("POST", base + "/close", admin, null), 200);
        assertThat(ok(changeRole(admin, responsibleAdmin, "EMPLOYEE"), 200).get("roles")).extracting(JsonNode::asText)
                .containsExactly("EMPLOYEE");
        ok(remove(admin, responsibleAdmin), 200);
    }

    @Test
    void removing_isOnlyForManagers_andOnlyForMembers() throws Exception {
        String member = newMember("EMPLOYEE");
        String forbidden = title(remove(employee, member));
        for (HttpResponse<String> r : List.of(remove(employee, member), remove(otherAdmin, member),
                remove(admin, otherAdmin), remove(admin, UUID.randomUUID().toString()))) {
            assertThat(r.statusCode()).isEqualTo(403);
            assertThat(title(r)).isEqualTo(forbidden);
        }
        ok(remove(representative, member), 200);
        assertThat(remove(representative, member).statusCode()).isEqualTo(403);
    }
}
