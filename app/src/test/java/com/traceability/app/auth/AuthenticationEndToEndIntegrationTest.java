package com.traceability.app.auth;

import com.traceability.api.auth.jwt.JwtAuthFilter;
import com.traceability.app.TraceabilityApplication;
import com.traceability.contracts.authorization.AuthorizationPrincipal;
import com.traceability.core.application.security.TrackingCodeService;
import identity.application.service.CreateAccountService;
import identity.application.service.DeactivateAccountService;
import identity.domain.model.Account;
import identity.domain.model.AccountId;
import identity.domain.model.AuditActor;
import identity.domain.model.Email;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * B3 de punta a punta con el contexto real (identity + api + Mongo). Definición de hecho de ADR-047 (P4),
 * puntos 8, 9, 10 y 12.
 */
@SpringBootTest(classes = {TraceabilityApplication.class, AuthenticationEndToEndIntegrationTest.WhoAmIController.class})
@AutoConfigureMockMvc
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = {
    "crypto.anchor.poll.delay=9999999",
    "crypto.anchor.submit.delay=9999999",
    "crypto.anchor.stuck-monitor.delay=9999999",
    "crypto.web3j.private-key=0x1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef",
    "crypto.web3j.node-url=http://dummy-node",
    "spring.ai.openai.api-key=dummy-api-key",
    // DEBUG para que el motivo del rechazo se registre y el test de logs (punto 9) tenga algo que revisar
    "logging.level.com.traceability.api.auth=DEBUG"
})
class AuthenticationEndToEndIntegrationTest {

    static final String JWT_SECRET = "test-only-jwt-signing-secret-not-for-production";

    /** Ruta protegida solo de test: ninguna ruta protegida existe todavía en {@code develop}. */
    @RestController
    static class WhoAmIController {
        @GetMapping("/api/v1/b3-test/whoami")
        String whoami(@RequestAttribute(JwtAuthFilter.PRINCIPAL_ATTRIBUTE) AuthorizationPrincipal principal) {
            return principal.accountId();
        }
    }

    @Container
    static MongoDBContainer mongo = new MongoDBContainer(DockerImageName.parse("mongo:6.0")).withCommand("--replSet", "rs0");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongo::getReplicaSetUrl);
    }

    @Autowired private MockMvc mvc;
    @Autowired private CreateAccountService createAccountService;
    @Autowired private DeactivateAccountService deactivateAccountService;
    @Autowired private TrackingCodeService trackingCodeService;

    private static String uniqueEmail() {
        return "u" + UUID.randomUUID().toString().substring(0, 8) + "@example.org";
    }

    private Account account(String email, String password) {
        return createAccountService.createAccount(new Email(email), password);
    }

    private MvcResult login(String email, String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}")).andReturn();
    }

    private static String token(MvcResult login) throws Exception {
        String body = login.getResponse().getContentAsString();
        return body.substring("{\"token\":\"".length(), body.length() - 2);
    }

    private MvcResult whoami(String token) throws Exception {
        return mvc.perform(get("/api/v1/b3-test/whoami").header("Authorization", "Bearer " + token)).andReturn();
    }

    private void deactivate(Account account) {
        deactivateAccountService.deactivateAccount(new AuditActor.AccountAuditActor(account.getAccountId()), account.getAccountId());
    }

    @Test
    void login_thenAProtectedRoute_resolvesThePrincipal() throws Exception {
        String email = uniqueEmail();
        Account account = account(email, "pw-correct-123");

        MvcResult login = login(email, "pw-correct-123");

        assertThat(login.getResponse().getStatus()).isEqualTo(200);
        assertThat(whoami(token(login)).getResponse().getContentAsString()).isEqualTo(account.getAccountId().value());
    }

    // 8
    @Test
    void theThreeLoginFailures_areIdenticalByteForByte() throws Exception {
        String active = uniqueEmail();
        String inactive = uniqueEmail();
        account(active, "pw-correct-123");
        deactivate(account(inactive, "pw-correct-123"));

        MvcResult unknownEmail = login(uniqueEmail(), "pw-correct-123");
        MvcResult wrongPassword = login(active, "pw-wrong-123");
        MvcResult inactiveAccount = login(inactive, "pw-correct-123");

        for (MvcResult r : new MvcResult[]{unknownEmail, wrongPassword, inactiveAccount}) {
            assertThat(r.getResponse().getStatus()).isEqualTo(401);
            assertThat(r.getResponse().getContentType()).isEqualTo(unknownEmail.getResponse().getContentType());
            assertThat(r.getResponse().getContentAsByteArray()).isEqualTo(unknownEmail.getResponse().getContentAsByteArray());
        }
    }

    // 10
    @Test
    void anAccountDeactivatedAfterTheTokenWasIssued_gets401OnTheNextRequest() throws Exception {
        String email = uniqueEmail();
        Account account = account(email, "pw-correct-123");
        String token = token(login(email, "pw-correct-123"));
        assertThat(whoami(token).getResponse().getStatus()).isEqualTo(200);

        deactivate(account);

        assertThat(whoami(token).getResponse().getStatus()).isEqualTo(401);
    }

    // 12
    @Test
    void trackingCodesAndJwts_areNotInterchangeable() throws Exception {
        String trackingCode = trackingCodeService.generate("fund-" + UUID.randomUUID(), Instant.now().plus(1, ChronoUnit.DAYS));
        String email = uniqueEmail();
        account(email, "pw-correct-123");
        String jwt = token(login(email, "pw-correct-123"));

        assertThat(whoami(trackingCode).getResponse().getStatus()).isEqualTo(401);
        assertThat(mvc.perform(get("/api/v1/donations/tracking").header("Authorization", "Bearer " + jwt))
                .andReturn().getResponse().getStatus()).isEqualTo(401);
    }

    // 9
    @Test
    void neitherTokenNorSecretNorPassword_appearInTheLogs(CapturedOutput output) throws Exception {
        String email = uniqueEmail();
        account(email, "pw-correct-123");
        String token = token(login(email, "pw-correct-123"));
        login(email, "pw-wrong-123");
        whoami(token);
        whoami(token.substring(0, token.length() - 4) + "AAAA");

        String logs = output.getAll();
        assertThat(logs).contains("JWT authentication rejected: SIGNATURE").contains("Login rejected");
        for (String part : token.split("\\.")) {
            assertThat(logs).doesNotContain(part);
        }
        assertThat(logs).doesNotContain(JWT_SECRET).doesNotContain("pw-correct-123").doesNotContain("pw-wrong-123");
    }
}
