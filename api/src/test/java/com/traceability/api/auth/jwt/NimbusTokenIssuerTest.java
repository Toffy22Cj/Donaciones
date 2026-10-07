package com.traceability.api.auth.jwt;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.SignedJWT;
import com.traceability.contracts.authentication.TokenIssuanceException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static com.traceability.api.auth.jwt.JwtTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ADR-047 D2, D4 y D5: HS256, cabecera {@code kid}, claims exactamente {@code sub}, {@code iat} y {@code exp}. */
class NimbusTokenIssuerTest {

    @Test
    void issuesAnHs256TokenWithTheCurrentKid_andExactlySubIatExp() throws Exception {
        String token = new NimbusTokenIssuer(currentOnly(clockAt(NOW)), clockAt(NOW)).issue("acc-1");
        SignedJWT jwt = SignedJWT.parse(token);

        assertThat(jwt.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.HS256);
        assertThat(jwt.getHeader().getKeyID()).isEqualTo(CURRENT_KID);
        assertThat(jwt.getJWTClaimsSet().getClaims().keySet()).containsExactlyInAnyOrder("sub", "iat", "exp");
        assertThat(jwt.getJWTClaimsSet().getSubject()).isEqualTo("acc-1");
        assertThat(jwt.getJWTClaimsSet().getIssueTime().toInstant()).isEqualTo(NOW);
        assertThat(jwt.getJWTClaimsSet().getExpirationTime().toInstant()).isEqualTo(NOW.plus(Duration.ofHours(8)));
        assertThat(jwt.verify(new MACVerifier(CURRENT_SECRET.getBytes(StandardCharsets.UTF_8)))).isTrue();
    }

    @Test
    void alwaysSignsWithTheCurrentKey_evenWhileThePreviousIsAccepted() throws Exception {
        JwtSecurityProperties p = withPrevious(NOW, NOW.plus(Duration.ofHours(1)));
        SignedJWT jwt = SignedJWT.parse(new NimbusTokenIssuer(p, clockAt(NOW)).issue("acc-1"));

        assertThat(jwt.getHeader().getKeyID()).isEqualTo(CURRENT_KID);
        assertThat(jwt.verify(new MACVerifier(CURRENT_SECRET.getBytes(StandardCharsets.UTF_8)))).isTrue();
    }

    @Test
    void theIssuedTokenIsAcceptedByTheVerifier() throws Exception {
        JwtSecurityProperties p = currentOnly(clockAt(NOW));
        String token = new NimbusTokenIssuer(p, clockAt(NOW)).issue("acc-1");

        assertThat(new JwtTokenVerifier(p, clockAt(NOW.plus(Duration.ofHours(7)))).verify(token).subject()).isEqualTo("acc-1");
    }

    @Test
    void anyFailure_isATokenIssuanceException_withoutKeyMaterialInTheMessage() throws Exception {
        NimbusTokenIssuer issuer = new NimbusTokenIssuer(currentOnly(clockAt(NOW)), clockAt(NOW));

        assertThatThrownBy(() -> issuer.issue(null))
                .isInstanceOf(TokenIssuanceException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(CURRENT_SECRET));
        assertThatThrownBy(() -> issuer.issue(" "))
                .isInstanceOf(TokenIssuanceException.class);
    }
}
