package com.traceability.api.auth;

import com.traceability.contracts.authentication.AcceptanceRequiredException;
import com.traceability.contracts.authentication.AcceptedLegalVersions;
import com.traceability.contracts.authentication.AuthenticateAccountPort;
import com.traceability.contracts.authentication.AuthenticatedAccount;
import com.traceability.contracts.authentication.AuthenticationFailedException;
import com.traceability.contracts.authentication.TokenIssuanceException;
import com.traceability.contracts.authentication.TokenIssuerPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Ficha ID-01: 400 / 401 uniforme / 500 / {@code {token}} (ID01-D1, D2 y D4; ADR-047 P4, puntos 8, 9 y 11). ADR-050
 * Enmienda 1, E1-2 (plan P-R3-3, tests L-1 a L-4): el login emite con {@code iat = max(segundo actual truncado,
 * credentialsNotBefore)}, con el reloj a mitad de un segundo.
 */
@ExtendWith(OutputCaptureExtension.class)
class LoginControllerTest {

    /** A mitad de un segundo, para que se vea el truncado. */
    private static final Instant NOW = Instant.parse("2026-10-25T10:00:00.500Z");
    private static final Instant NOW_SECOND = Instant.parse("2026-10-25T10:00:00Z");

    private AuthenticateAccountPort authenticate;
    private TokenIssuerPort issuer;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        authenticate = mock(AuthenticateAccountPort.class);
        issuer = mock(TokenIssuerPort.class);
        mvc = MockMvcBuilders.standaloneSetup(
                new LoginController(authenticate, issuer, Clock.fixed(NOW, ZoneOffset.UTC))).build();
    }

    private static AuthenticatedAccount account(String id) {
        return new AuthenticatedAccount(id, null);
    }

    private MvcResult login(String json) throws Exception {
        return mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(json)).andReturn();
    }

    @Test
    void success_returnsOnlyTheToken() throws Exception {
        when(authenticate.authenticate("donor@example.org", "pw-123456", AcceptedLegalVersions.NONE)).thenReturn(account("acc-1"));
        when(issuer.issue(eq("acc-1"), any(Instant.class))).thenReturn("header.payload.signature");

        MvcResult r = login("{\"email\":\"donor@example.org\",\"password\":\"pw-123456\"}");

        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(r.getResponse().getContentAsString()).isEqualTo("{\"token\":\"header.payload.signature\"}");
    }

    @Test
    void emptyOrMissingFields_are400_withoutCallingTheAuthentication() throws Exception {
        for (String body : new String[]{
                "{\"email\":\"\",\"password\":\"pw\"}",
                "{\"email\":\"donor@example.org\",\"password\":\"\"}",
                "{\"email\":\"   \",\"password\":\"pw\"}",
                "{\"password\":\"pw\"}",
                "{\"email\":\"donor@example.org\"}",
                "{}"}) {
            assertThat(login(body).getResponse().getStatus()).as(body).isEqualTo(400);
        }
        verifyNoInteractions(authenticate, issuer);
    }

    @Test
    void anAuthenticationFailure_isAUniform401_withoutAnyReason() throws Exception {
        when(authenticate.authenticate("donor@example.org", "wrong", AcceptedLegalVersions.NONE)).thenThrow(new AuthenticationFailedException());
        when(authenticate.authenticate("nobody@example.org", "wrong", AcceptedLegalVersions.NONE)).thenThrow(new AuthenticationFailedException());

        MvcResult a = login("{\"email\":\"donor@example.org\",\"password\":\"wrong\"}");
        MvcResult b = login("{\"email\":\"nobody@example.org\",\"password\":\"wrong\"}");

        assertThat(a.getResponse().getStatus()).isEqualTo(401);
        assertThat(a.getResponse().getContentType()).isEqualTo("application/problem+json");
        assertThat(a.getResponse().getContentAsByteArray()).isEqualTo(b.getResponse().getContentAsByteArray());
        assertThat(a.getResponse().getContentAsString()).doesNotContain("donor@example.org");
        verifyNoInteractions(issuer);
    }

    // 11
    @Test
    void anIssuanceFailure_is500_never401() throws Exception {
        when(authenticate.authenticate("donor@example.org", "pw-123456", AcceptedLegalVersions.NONE)).thenReturn(account("acc-1"));
        when(issuer.issue(eq("acc-1"), any(Instant.class))).thenThrow(new TokenIssuanceException("could not sign", null));

        MvcResult r = login("{\"email\":\"donor@example.org\",\"password\":\"pw-123456\"}");

        assertThat(r.getResponse().getStatus()).isEqualTo(500);
        assertThat(r.getResponse().getContentType()).isEqualTo("application/problem+json");
    }

    // 9 (parte del login)
    @Test
    void neitherTheTokenNorThePassword_appearInTheLogs(CapturedOutput output) throws Exception {
        when(authenticate.authenticate("donor@example.org", "pw-very-secret", AcceptedLegalVersions.NONE)).thenReturn(account("acc-1"));
        when(authenticate.authenticate("donor@example.org", "pw-wrong-secret", AcceptedLegalVersions.NONE)).thenThrow(new AuthenticationFailedException());
        when(issuer.issue(eq("acc-1"), any(Instant.class))).thenReturn("eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJhY2MtMSJ9.c2lnbmF0dXJl");

        login("{\"email\":\"donor@example.org\",\"password\":\"pw-very-secret\"}");
        login("{\"email\":\"donor@example.org\",\"password\":\"pw-wrong-secret\"}");

        assertThat(output.getAll())
                .doesNotContain("pw-very-secret").doesNotContain("pw-wrong-secret")
                .doesNotContain("eyJhbGciOiJIUzI1NiJ9").doesNotContain("eyJzdWIiOiJhY2MtMSJ9").doesNotContain("c2lnbmF0dXJl");
    }

    // ── ADR-050 Enmienda 1, E1-2 (plan P-R3-3) ──

    private void loginOk(AuthenticatedAccount account) throws Exception {
        when(authenticate.authenticate("donor@example.org", "pw-123456", AcceptedLegalVersions.NONE)).thenReturn(account);
        when(issuer.issue(eq(account.accountId()), any(Instant.class))).thenReturn("t");
        assertThat(login("{\"email\":\"donor@example.org\",\"password\":\"pw-123456\"}").getResponse().getStatus())
                .isEqualTo(200);
    }

    // L-1
    @Test
    void credentialsNotBeforeInTheFuture_isTheIat() throws Exception {
        Instant cnb = NOW_SECOND.plusSeconds(2);

        loginOk(new AuthenticatedAccount("acc-1", cnb));

        verify(issuer).issue("acc-1", cnb);
    }

    // L-2
    @Test
    void credentialsNotBeforeInThePast_theIatIsTheCurrentSecond() throws Exception {
        loginOk(new AuthenticatedAccount("acc-1", NOW_SECOND.minusSeconds(30)));

        verify(issuer).issue("acc-1", NOW_SECOND);
    }

    // L-3
    @Test
    void withoutCredentialsNotBefore_theIatIsTheCurrentSecondTruncated() throws Exception {
        loginOk(new AuthenticatedAccount("acc-1", null));

        verify(issuer).issue("acc-1", NOW_SECOND);
    }

    // L-4
    @Test
    void theLogin_neverIssuesWithTheClockAlone() throws Exception {
        loginOk(new AuthenticatedAccount("acc-1", NOW_SECOND.plusSeconds(1)));

        verify(issuer, never()).issue(anyString());
    }

    // ── ADR-050 D3 (Enmienda 1, parte 3, E3-4; P-R5-5) ──

    @Test
    void pendingVersions_are403AcceptanceRequired_withOnlyThePendingOnes_andNoToken() throws Exception {
        when(authenticate.authenticate("donor@example.org", "pw-123456", AcceptedLegalVersions.NONE))
                .thenThrow(new AcceptanceRequiredException("v2-2026-11", null));

        MvcResult r = login("{\"email\":\"donor@example.org\",\"password\":\"pw-123456\"}");

        assertThat(r.getResponse().getStatus()).isEqualTo(403);
        assertThat(r.getResponse().getContentType()).isEqualTo("application/problem+json");
        String body = r.getResponse().getContentAsString();
        assertThat(body).contains("\"title\":\"AcceptanceRequired\"").contains("\"pendingTermsVersion\":\"v2-2026-11\"")
                .doesNotContain("pendingDataPolicyVersion").doesNotContain("token");
        verifyNoInteractions(issuer);
    }

    @Test
    void theAcceptedVersionsOfTheBody_reachThePort() throws Exception {
        AcceptedLegalVersions accepted = new AcceptedLegalVersions("v1-2026-10", "v1-2026-10");
        when(authenticate.authenticate("donor@example.org", "pw-123456", accepted)).thenReturn(account("acc-1"));
        when(issuer.issue(eq("acc-1"), any(Instant.class))).thenReturn("header.payload.signature");

        MvcResult r = login("{\"email\":\"donor@example.org\",\"password\":\"pw-123456\","
                + "\"acceptedTermsVersion\":\"v1-2026-10\",\"acceptedDataPolicyVersion\":\"v1-2026-10\"}");

        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        verify(authenticate).authenticate("donor@example.org", "pw-123456", accepted);
    }
}
