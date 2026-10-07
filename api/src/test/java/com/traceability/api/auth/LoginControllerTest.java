package com.traceability.api.auth;

import com.traceability.contracts.authentication.AuthenticateAccountPort;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Ficha ID-01: 400 / 401 uniforme / 500 / {@code {token}} (ID01-D1, D2 y D4; ADR-047 P4, puntos 8, 9 y 11). */
@ExtendWith(OutputCaptureExtension.class)
class LoginControllerTest {

    private AuthenticateAccountPort authenticate;
    private TokenIssuerPort issuer;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        authenticate = mock(AuthenticateAccountPort.class);
        issuer = mock(TokenIssuerPort.class);
        mvc = MockMvcBuilders.standaloneSetup(new LoginController(authenticate, issuer)).build();
    }

    private MvcResult login(String json) throws Exception {
        return mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(json)).andReturn();
    }

    @Test
    void success_returnsOnlyTheToken() throws Exception {
        when(authenticate.authenticate("donor@example.org", "pw-123456")).thenReturn("acc-1");
        when(issuer.issue("acc-1")).thenReturn("header.payload.signature");

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
        when(authenticate.authenticate("donor@example.org", "wrong")).thenThrow(new AuthenticationFailedException());
        when(authenticate.authenticate("nobody@example.org", "wrong")).thenThrow(new AuthenticationFailedException());

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
        when(authenticate.authenticate("donor@example.org", "pw-123456")).thenReturn("acc-1");
        when(issuer.issue("acc-1")).thenThrow(new TokenIssuanceException("could not sign", null));

        MvcResult r = login("{\"email\":\"donor@example.org\",\"password\":\"pw-123456\"}");

        assertThat(r.getResponse().getStatus()).isEqualTo(500);
        assertThat(r.getResponse().getContentType()).isEqualTo("application/problem+json");
    }

    // 9 (parte del login)
    @Test
    void neitherTheTokenNorThePassword_appearInTheLogs(CapturedOutput output) throws Exception {
        when(authenticate.authenticate("donor@example.org", "pw-very-secret")).thenReturn("acc-1");
        when(authenticate.authenticate("donor@example.org", "pw-wrong-secret")).thenThrow(new AuthenticationFailedException());
        when(issuer.issue("acc-1")).thenReturn("eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJhY2MtMSJ9.c2lnbmF0dXJl");

        login("{\"email\":\"donor@example.org\",\"password\":\"pw-very-secret\"}");
        login("{\"email\":\"donor@example.org\",\"password\":\"pw-wrong-secret\"}");

        assertThat(output.getAll())
                .doesNotContain("pw-very-secret").doesNotContain("pw-wrong-secret")
                .doesNotContain("eyJhbGciOiJIUzI1NiJ9").doesNotContain("eyJzdWIiOiJhY2MtMSJ9").doesNotContain("c2lnbmF0dXJl");
    }
}
