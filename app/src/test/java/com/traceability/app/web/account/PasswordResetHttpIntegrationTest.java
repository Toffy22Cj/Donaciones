package com.traceability.app.web.account;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.app.TraceabilityApplication;
import com.traceability.app.config.PasswordResetMailExecutorConfig;
import identity.application.service.CreateAccountService;
import identity.application.service.DeactivateAccountService;
import identity.domain.model.AccountId;
import identity.domain.model.AuditActor;
import identity.domain.model.Email;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

/**
 * ADR-050 D8 y Q5 (a) (plan R-4 §6 y §11), contra Tomcat real con {@code identity} real: pedir y confirmar la
 * recuperación de la contraseña. Numeración de la definición de hecho de ADR-050 (11 a 18) y del plan (R4-19 a R4-21).
 * El correo se captura con un {@code JavaMailSender} de test que puede bloquearse o fallar. El ejecutor real del correo
 * se espía ({@code @MockitoSpyBean} por su nombre, sin sustituir definiciones) para hacerlo rechazar (cola llena, R4-20).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = TraceabilityApplication.class)
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
@Import(PasswordResetHttpIntegrationTest.TestBeans.class)
@TestPropertySource(properties = {
    "crypto.anchor.poll.delay=9999999",
    "crypto.anchor.submit.delay=9999999",
    "crypto.anchor.stuck-monitor.delay=9999999",
    "crypto.web3j.private-key=0x1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef",
    "crypto.web3j.node-url=http://dummy-node",
    "spring.ai.openai.api-key=dummy-api-key"
})
class PasswordResetHttpIntegrationTest {

    static final String PASSWORD = "Pass123!Pass123!";
    static final String NEW_PASSWORD = "NewPass456!NewPass456!";
    static final String REQUEST = "/api/v1/auth/password-reset";
    static final String CONFIRM = "/api/v1/auth/password-reset/confirm";
    private static final Pattern LINK = Pattern.compile("(https://\\S+/recuperar#token=([A-Za-z0-9_-]+))");

    /** Reloj que el test puede fijar en un instante o adelantar; si no, la hora real. */
    static final class TestClock extends Clock {
        volatile Instant pinned;
        volatile Duration offset = Duration.ZERO;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { Instant p = pinned; return (p != null ? p : Instant.now()).plus(offset); }
    }

    /** Captura los correos; puede bloquearse con un latch y falla para los destinatarios {@code smtp-fail…}. */
    static final class CapturingMailSender extends JavaMailSenderImpl {
        final List<SimpleMailMessage> sent = new CopyOnWriteArrayList<>();
        volatile CountDownLatch gate;
        volatile CountDownLatch entered;
        @Override public void send(SimpleMailMessage message) { send(new SimpleMailMessage[] {message}); }
        @Override public void send(SimpleMailMessage... messages) {
            CountDownLatch e = entered;
            if (e != null) e.countDown();
            CountDownLatch g = gate;
            if (g != null) {
                try {
                    g.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            for (SimpleMailMessage m : messages) {
                if (m.getTo()[0].startsWith("smtp-fail")) throw new MailSendException("servidor caído");
                sent.add(m);
            }
        }
    }

    @TestConfiguration
    static class TestBeans {
        static final TestClock CLOCK = new TestClock();
        static final CapturingMailSender MAIL = new CapturingMailSender();

        @Bean @Primary Clock testClock() { return CLOCK; }
        @Bean @Primary JavaMailSender capturingMailSender() { return MAIL; }
    }

    @Container
    static MongoDBContainer mongo = new MongoDBContainer(DockerImageName.parse("mongo:6.0")).withCommand("--replSet", "rs0");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongo::getReplicaSetUrl);
    }

    @LocalServerPort private int port;
    @Autowired private CreateAccountService accounts;
    @Autowired private DeactivateAccountService deactivations;
    @Autowired private MongoTemplate mongoTemplate;
    @Autowired private org.springframework.context.ApplicationContext context;
    @MockitoSpyBean(name = PasswordResetMailExecutorConfig.EXECUTOR) private ThreadPoolTaskExecutor mailExecutor;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void reset() {
        TestBeans.CLOCK.pinned = null;
        TestBeans.CLOCK.offset = Duration.ZERO;
        TestBeans.MAIL.sent.clear();
        TestBeans.MAIL.gate = null;
        TestBeans.MAIL.entered = null;
        Mockito.reset(mailExecutor);
    }

    private static String newEmail() {
        return UUID.randomUUID() + "@r4.test";
    }

    private String account(String email) {
        return accounts.createAccount(new Email(email), PASSWORD).getAccountId().value();
    }

    private HttpResponse<String> send(String method, String path, String token, String body) throws Exception {
        HttpRequest.Builder r = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (token != null) r.header("Authorization", "Bearer " + token);
        if (body != null) r.header("Content-Type", "application/json");
        return http.send(r.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> requestReset(String email) throws Exception {
        return send("POST", REQUEST, null, json.createObjectNode().put("email", email).toString());
    }

    private HttpResponse<String> confirm(String token, String newPassword) throws Exception {
        return send("POST", CONFIRM, null, json.createObjectNode().put("token", token).put("newPassword", newPassword)
                .toString());
    }

    private String login(String email, String password) throws Exception {
        HttpResponse<String> r = send("POST", "/api/v1/auth/login", null,
                "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return json.readTree(r.body()).get("token").asText();
    }

    private int loginStatus(String email, String password) throws Exception {
        return send("POST", "/api/v1/auth/login", null,
                "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}").statusCode();
    }

    /** Espera a que lleguen {@code count} correos a {@code to} (el envío es asíncrono). */
    private List<SimpleMailMessage> mailsTo(String to, int count) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            List<SimpleMailMessage> found = TestBeans.MAIL.sent.stream().filter(m -> m.getTo()[0].equals(to)).toList();
            if (found.size() >= count) return found;
            Thread.sleep(25);
        }
        return TestBeans.MAIL.sent.stream().filter(m -> m.getTo()[0].equals(to)).toList();
    }

    /** Lo que no debe llegar: se da un margen al ejecutor antes de afirmar que no llegó nada. */
    private List<SimpleMailMessage> noMailTo(String to) throws Exception {
        Thread.sleep(500);
        return TestBeans.MAIL.sent.stream().filter(m -> m.getTo()[0].equals(to)).toList();
    }

    private static String tokenIn(SimpleMailMessage mail) {
        Matcher m = LINK.matcher(mail.getText());
        assertThat(m.find()).as(mail.getText()).isTrue();
        return m.group(2);
    }

    private String requestAndTakeToken(String email) throws Exception {
        int before = mailsTo(email, 0).size();
        assertThat(requestReset(email).statusCode()).isEqualTo(202);
        List<SimpleMailMessage> mails = mailsTo(email, before + 1);
        assertThat(mails).hasSize(before + 1);
        return tokenIn(mails.get(before));
    }

    private static Map<String, List<String>> comparableHeaders(HttpHeaders headers) {
        Map<String, List<String>> map = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        map.putAll(headers.map());
        map.remove("date");
        return map;
    }

    private List<Document> tokenDocuments(String accountId) {
        return mongoTemplate.find(Query.query(Criteria.where("accountId").is(accountId)), Document.class,
                "password_reset_tokens");
    }

    // --- 11 ---

    @Test
    void request_isTheSame202ByteForByte_forAnExistingMissingInactiveOrMalformedEmail_andOnlyTheExistingOneSendsMail()
            throws Exception {
        String existing = newEmail();
        account(existing);
        String inactive = newEmail();
        String inactiveId = account(inactive);
        deactivations.deactivateAccount(new AuditActor.SystemAuditActor("r4-tests"), new AccountId(inactiveId));
        String missing = newEmail();

        HttpResponse<String> a = requestReset(existing);
        HttpResponse<String> b = requestReset(missing);
        HttpResponse<String> c = requestReset(inactive);
        HttpResponse<String> d = requestReset("not-an-email");

        for (HttpResponse<String> r : List.of(a, b, c, d)) {
            assertThat(r.statusCode()).isEqualTo(202);
            assertThat(r.body()).isEqualTo(a.body()).isEmpty();
            assertThat(comparableHeaders(r.headers())).isEqualTo(comparableHeaders(a.headers()));
        }
        assertThat(a.headers().firstValue("Cache-Control")).hasValue("no-store");
        assertThat(mailsTo(existing, 1)).hasSize(1);
        assertThat(noMailTo(missing)).isEmpty();
        assertThat(noMailTo(inactive)).isEmpty();
        assertThat(tokenDocuments(inactiveId)).isEmpty();
    }

    // --- 12 ---

    @Test
    void the202_arrivesBeforeTheMailIsSent() throws Exception {
        String email = newEmail();
        account(email);
        TestBeans.MAIL.gate = new CountDownLatch(1);
        TestBeans.MAIL.entered = new CountDownLatch(1);

        HttpResponse<String> r = requestReset(email);

        assertThat(r.statusCode()).isEqualTo(202);
        assertThat(TestBeans.MAIL.entered.await(10, TimeUnit.SECONDS)).as("el envío empezó").isTrue();
        assertThat(TestBeans.MAIL.sent).as("y no ha terminado cuando llega el 202").isEmpty();
        TestBeans.MAIL.gate.countDown();
        assertThat(mailsTo(email, 1)).hasSize(1);
    }

    // --- 13 ---

    @Test
    void aSecondRequestBeforeFiveMinutes_sendsNothing_andAfterFiveMinutes_aNewTokenReplacesTheOld() throws Exception {
        String email = newEmail();
        String accountId = account(email);
        String first = requestAndTakeToken(email);

        TestBeans.CLOCK.offset = Duration.ofMinutes(4);
        assertThat(requestReset(email).statusCode()).isEqualTo(202);
        assertThat(noMailTo(email)).hasSize(1);
        assertThat(tokenDocuments(accountId)).hasSize(1);

        TestBeans.CLOCK.offset = Duration.ofMinutes(5);
        String second = requestAndTakeToken(email);

        assertThat(second).isNotEqualTo(first);
        assertThat(tokenDocuments(accountId)).extracting(doc -> doc.getString("status"))
                .containsExactlyInAnyOrder("SUPERSEDED", "PENDING");
        assertThat(confirm(first, NEW_PASSWORD).statusCode()).isEqualTo(400);
    }

    // --- 14 ---

    @Test
    void confirmingAnUnknownExpiredUsedSupersededOrInactiveToken_isTheSame400() throws Exception {
        HttpResponse<String> unknown = confirm("unknown-token", NEW_PASSWORD);
        assertThat(unknown.statusCode()).isEqualTo(400);
        assertThat(json.readTree(unknown.body()).get("title").asText()).isEqualTo("PasswordResetTokenInvalid");

        String usedEmail = newEmail();
        account(usedEmail);
        String used = requestAndTakeToken(usedEmail);
        assertThat(confirm(used, NEW_PASSWORD).statusCode()).isEqualTo(204);
        HttpResponse<String> again = confirm(used, "Another789!Another789!");

        String expiredEmail = newEmail();
        account(expiredEmail);
        String expired = requestAndTakeToken(expiredEmail);
        TestBeans.CLOCK.offset = Duration.ofMinutes(30);
        HttpResponse<String> late = confirm(expired, NEW_PASSWORD);
        TestBeans.CLOCK.offset = Duration.ZERO;

        String inactiveEmail = newEmail();
        String inactiveId = account(inactiveEmail);
        String forInactive = requestAndTakeToken(inactiveEmail);
        deactivations.deactivateAccount(new AuditActor.SystemAuditActor("r4-tests"), new AccountId(inactiveId));
        HttpResponse<String> inactive = confirm(forInactive, NEW_PASSWORD);

        for (HttpResponse<String> r : List.of(again, late, inactive)) {
            assertThat(r.statusCode()).isEqualTo(400);
            assertThat(r.body()).isEqualTo(unknown.body());
        }
        assertThat(loginStatus(expiredEmail, PASSWORD)).as("el caducado no cambió nada").isEqualTo(200);
    }

    // --- 15 ---

    @Test
    void twoSimultaneousConfirmations_oneIs204_andTheOther400() throws Exception {
        String email = newEmail();
        account(email);
        String token = requestAndTakeToken(email);
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Integer> statuses = new CopyOnWriteArrayList<>();
        try {
            List<Future<?>> futures = List.of(
                    pool.submit(() -> { barrier.await(); statuses.add(confirm(token, "First111!First111!").statusCode()); return null; }),
                    pool.submit(() -> { barrier.await(); statuses.add(confirm(token, "Second22!Second22!").statusCode()); return null; }));
            for (Future<?> f : futures) f.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(statuses).containsExactlyInAnyOrder(204, 400);
    }

    // --- 16 ---

    @Test
    void confirming_revokesThePreviousJwt_andTheNewPasswordWorks() throws Exception {
        String email = newEmail();
        account(email);
        String session = login(email, PASSWORD);
        String token = requestAndTakeToken(email);
        TestBeans.CLOCK.offset = Duration.ofSeconds(2);

        HttpResponse<String> r = confirm(token, NEW_PASSWORD);

        assertThat(r.statusCode()).isEqualTo(204);
        assertThat(r.body()).isEmpty();
        assertThat(send("GET", "/api/v1/account", session, null).statusCode()).isEqualTo(401);
        assertThat(loginStatus(email, PASSWORD)).isEqualTo(401);
        login(email, NEW_PASSWORD);
    }

    // --- R4-19 (depende de P-R3-3) ---

    @Test
    void r4_19_confirmAndLogInInTheSameSecond_theTokenWorks() throws Exception {
        String email = newEmail();
        account(email);
        String token = requestAndTakeToken(email);
        TestBeans.CLOCK.pinned = Instant.now().truncatedTo(ChronoUnit.SECONDS).plusMillis(750);

        assertThat(confirm(token, NEW_PASSWORD).statusCode()).isEqualTo(204);
        String session = login(email, NEW_PASSWORD);

        assertThat(send("GET", "/api/v1/account", session, null).statusCode()).isEqualTo(200);
    }

    // --- 17 ---

    @Test
    void theToken_neverAppearsInLogsResponsesOrTheDatabase_andIsNotAcceptedInTheQueryOrThePath(CapturedOutput output)
            throws Exception {
        String email = newEmail();
        String accountId = account(email);
        int from = output.getAll().length();
        String token = requestAndTakeToken(email);

        assertThat(output.getAll().substring(from)).doesNotContain(token).doesNotContain(email);
        Document stored = tokenDocuments(accountId).get(0);
        assertThat(stored.toJson()).doesNotContain(token);
        assertThat(stored.getString("tokenHash")).isEqualTo(identity.domain.model.InvitationToken.hashOf(token));

        HttpResponse<String> inQuery = send("POST", CONFIRM + "?token=" + token + "&newPassword=" + NEW_PASSWORD, null, null);
        HttpResponse<String> inPath = send("POST", CONFIRM + "/" + token, null,
                json.createObjectNode().put("newPassword", NEW_PASSWORD).toString());
        assertThat(inQuery.statusCode()).isEqualTo(400);
        assertThat(inPath.statusCode()).isIn(400, 401);
        assertThat(inQuery.body()).doesNotContain(token);
        assertThat(inPath.body()).doesNotContain(token);
        assertThat(loginStatus(email, PASSWORD)).as("nada cambió").isEqualTo(200);
        assertThat(output.getAll().substring(from)).doesNotContain(token).doesNotContain(email);
    }

    // --- 18 ---

    @Test
    void theMailLink_carriesTheTokenAfterTheHash_onTheRecoverPage() throws Exception {
        String email = newEmail();
        account(email);
        assertThat(requestReset(email).statusCode()).isEqualTo(202);

        SimpleMailMessage mail = mailsTo(email, 1).get(0);
        Matcher m = LINK.matcher(mail.getText());
        assertThat(m.find()).isTrue();
        assertThat(m.group(1)).startsWith("https://web.tests.paxfide.local/recuperar#token=").doesNotContain("?");
        assertThat(mail.getFrom()).isEqualTo("no-reply@tests.paxfide.local");
        assertThat(mail.getText()).contains("Si no lo pediste, ignora este correo");
    }

    // --- campos vacíos ---

    @Test
    void emptyFields_are400_withoutCallingTheDomain() throws Exception {
        assertThat(send("POST", REQUEST, null, "{}").statusCode()).isEqualTo(400);
        assertThat(send("POST", REQUEST, null, "{\"email\":\"  \"}").statusCode()).isEqualTo(400);
        assertThat(send("POST", CONFIRM, null, "{\"newPassword\":\"" + NEW_PASSWORD + "\"}").statusCode()).isEqualTo(400);
        assertThat(send("POST", CONFIRM, null, "{\"token\":\"abc\"}").statusCode()).isEqualTo(400);
        assertThat(send("POST", CONFIRM, null, "{\"token\":\"abc\",\"newPassword\":\"short\"}").statusCode()).isEqualTo(400);
    }

    // --- SMTP caído ---

    @Test
    void aFailingSmtp_stillAnswers202_andLogsNeitherTheTokenNorTheEmail(CapturedOutput output) throws Exception {
        String email = "smtp-fail-" + UUID.randomUUID() + "@r4.test";
        String accountId = account(email);
        int from = output.getAll().length();

        HttpResponse<String> r = requestReset(email);

        assertThat(r.statusCode()).isEqualTo(202);
        String tokenId = tokenDocuments(accountId).get(0).getString("_id");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!output.getAll().substring(from).contains("password-reset.mail.failed") && System.nanoTime() < deadline) {
            Thread.sleep(25);
        }
        String logs = output.getAll().substring(from);
        assertThat(logs).contains("password-reset.mail.failed tokenId=" + tokenId);
        assertThat(logs).doesNotContain(email);
    }

    // --- R4-20: cola llena ---

    @Test
    void theOwnMailExecutor_doesNotReplaceSpringsApplicationTaskExecutor() {
        // plan R-4 §4 (app): el ejecutor del correo es propio y con nombre, y no sustituye al de Spring
        assertThat(context.containsBean("applicationTaskExecutor")).isTrue();
        assertThat(context.getBean("applicationTaskExecutor")).isNotSameAs(mailExecutor)
                .isInstanceOf(ThreadPoolTaskExecutor.class);
    }

    @Test
    void r4_20_aFullQueue_is202IdenticalToTheOthers_withALogOfTheTokenIdOnly_andNoMail(CapturedOutput output)
            throws Exception {
        String reference = newEmail();
        HttpResponse<String> missing = requestReset(reference);
        String email = newEmail();
        String accountId = account(email);
        doThrow(new TaskRejectedException("cola llena (test)")).when(mailExecutor).execute(any(Runnable.class));
        int from = output.getAll().length();

        HttpResponse<String> r = requestReset(email);

        assertThat(r.statusCode()).isEqualTo(202);
        assertThat(r.body()).isEqualTo(missing.body());
        assertThat(comparableHeaders(r.headers())).isEqualTo(comparableHeaders(missing.headers()));
        String tokenId = tokenDocuments(accountId).get(0).getString("_id");
        String logs = output.getAll().substring(from);
        assertThat(logs).contains("password-reset.mail.rejected tokenId=" + tokenId + " cause=TaskRejectedException");
        assertThat(logs).doesNotContain(email);
        Mockito.reset(mailExecutor);
        assertThat(noMailTo(email)).isEmpty();
    }

    // --- R4-21: dos peticiones simultáneas contra el índice único parcial ---

    @Test
    void r4_21_twoSimultaneousRequests_areBoth202WithTheSameBody_aSinglePending_andASingleMail() throws Exception {
        String email = newEmail();
        String accountId = account(email);
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<HttpResponse<String>> responses = new CopyOnWriteArrayList<>();
        try {
            List<Future<?>> futures = List.of(
                    pool.submit(() -> { barrier.await(); responses.add(requestReset(email)); return null; }),
                    pool.submit(() -> { barrier.await(); responses.add(requestReset(email)); return null; }));
            for (Future<?> f : futures) f.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(responses).extracting(HttpResponse::statusCode).containsExactly(202, 202);
        assertThat(responses.get(0).body()).isEqualTo(responses.get(1).body());
        assertThat(tokenDocuments(accountId)).filteredOn(doc -> "PENDING".equals(doc.getString("status"))).hasSize(1);
        assertThat(mailsTo(email, 1)).hasSize(1);
        assertThat(noMailTo(email)).hasSize(1);
    }
}
