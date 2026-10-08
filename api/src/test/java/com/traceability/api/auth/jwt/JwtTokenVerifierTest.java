package com.traceability.api.auth.jwt;

import com.traceability.api.auth.jwt.JwtVerification.RejectReason;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static com.traceability.api.auth.jwt.JwtTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Definición de hecho de ADR-047 (P4), puntos 1–6, y orden exacto de validación de D5 (P1):
 * {@code alg} → {@code kid} (solo en el mapa) → firma → {@code exp}.
 */
class JwtTokenVerifierTest {

    private static JwtTokenVerifier verifierAt(JwtSecurityProperties p, Instant now) {
        return new JwtTokenVerifier(p, clockAt(now));
    }

    private static JwtTokenVerifier currentOnlyAt(Instant now) throws Exception {
        return verifierAt(currentOnly(clockAt(NOW)), now);
    }

    @Test
    void aTokenSignedWithTheCurrentKey_isAccepted() throws Exception {
        JwtVerification v = currentOnlyAt(NOW).verify(hs256(CURRENT_KID, CURRENT_SECRET, validClaims()));

        assertThat(v.isAccepted()).isTrue();
        assertThat(v.subject()).isEqualTo("acc-1");
    }

    // 1
    @Test
    void algNone_isRejected() throws Exception {
        assertThat(currentOnlyAt(NOW).verify(algNone(validClaims())).rejectReason()).isEqualTo(RejectReason.ALGORITHM);
    }

    // 2
    @Test
    void hs512AndRs256_areRejected_evenWithAKnownKid() throws Exception {
        JwtTokenVerifier verifier = currentOnlyAt(NOW);

        assertThat(verifier.verify(hs512(CURRENT_KID, validClaims())).rejectReason()).isEqualTo(RejectReason.ALGORITHM);
        assertThat(verifier.verify(rs256(CURRENT_KID, validClaims())).rejectReason()).isEqualTo(RejectReason.ALGORITHM);
    }

    // 3
    @Test
    void aTamperedPayload_isRejectedByTheSignature() throws Exception {
        String token = hs256(CURRENT_KID, CURRENT_SECRET, validClaims());
        String tampered = withTamperedPayload(token, claims("someone-else", NOW, NOW.plus(Duration.ofHours(8))));

        assertThat(currentOnlyAt(NOW).verify(tampered).rejectReason()).isEqualTo(RejectReason.SIGNATURE);
    }

    @Test
    void aKnownKidSignedWithAnotherSecret_isRejectedByTheSignature_withoutTryingOtherKeys() throws Exception {
        JwtSecurityProperties p = withPrevious(NOW, NOW.plus(Duration.ofHours(1)));

        // kid actual, firmado con el secreto anterior: la clave la elige el kid y nunca se prueban las demás
        assertThat(verifierAt(p, NOW).verify(hs256(CURRENT_KID, PREVIOUS_SECRET, validClaims())).rejectReason())
                .isEqualTo(RejectReason.SIGNATURE);
    }

    // 4
    @Test
    void anExpiredToken_isRejected_butWithinTheThirtySecondSkew_isAccepted() throws Exception {
        String token = hs256(CURRENT_KID, CURRENT_SECRET, claims("acc-1", NOW.minus(Duration.ofHours(8)), NOW));

        assertThat(currentOnlyAt(NOW.plusSeconds(10)).verify(token).isAccepted()).isTrue();
        assertThat(currentOnlyAt(NOW.plusSeconds(29)).verify(token).isAccepted()).isTrue();
        assertThat(currentOnlyAt(NOW.plusSeconds(31)).verify(token).rejectReason()).isEqualTo(RejectReason.EXPIRED);
        assertThat(currentOnlyAt(NOW.plus(Duration.ofHours(1))).verify(token).rejectReason()).isEqualTo(RejectReason.EXPIRED);
    }

    // 5
    @Test
    void anUnknownKid_isRejected_includingPathAndQueryShapedKids_andAMissingKid() throws Exception {
        JwtTokenVerifier verifier = currentOnlyAt(NOW);

        for (String kid : new String[]{"k9", "../k2", "../../etc/passwd", "' OR 1=1 --", "{\"$ne\":null}", "k2 ", "K2", ""}) {
            assertThat(verifier.verify(hs256(kid, CURRENT_SECRET, validClaims())).rejectReason())
                    .as("kid %s", kid).isEqualTo(RejectReason.UNKNOWN_KID);
        }
        assertThat(verifier.verify(hs256(null, CURRENT_SECRET, validClaims())).rejectReason())
                .isEqualTo(RejectReason.UNKNOWN_KID);
    }

    @Test
    void order_algorithmIsCheckedBeforeKid_andKidBeforeSignature_andSignatureBeforeExpiry() throws Exception {
        JwtTokenVerifier verifier = currentOnlyAt(NOW.plus(Duration.ofDays(1)));
        var expired = claims("acc-1", NOW, NOW.plusSeconds(1));

        assertThat(verifier.verify(hs512("unknown", expired)).rejectReason()).isEqualTo(RejectReason.ALGORITHM);
        assertThat(verifier.verify(hs256("unknown", "some-other-secret-0123456789abcdefgh", expired)).rejectReason())
                .isEqualTo(RejectReason.UNKNOWN_KID);
        assertThat(verifier.verify(hs256(CURRENT_KID, "some-other-secret-0123456789abcdefgh", expired)).rejectReason())
                .isEqualTo(RejectReason.SIGNATURE);
        assertThat(verifier.verify(hs256(CURRENT_KID, CURRENT_SECRET, expired)).rejectReason())
                .isEqualTo(RejectReason.EXPIRED);
    }

    // 6
    @Test
    void thePreviousKey_isAcceptedUntilItsAcceptUntil_andRejectedAfterwards() throws Exception {
        Instant acceptUntil = NOW.plus(Duration.ofHours(2));
        JwtSecurityProperties p = withPrevious(NOW, acceptUntil);
        String token = hs256(PREVIOUS_KID, PREVIOUS_SECRET, claims("acc-1", NOW, NOW.plus(Duration.ofHours(8))));

        assertThat(verifierAt(p, NOW.plus(Duration.ofHours(1))).verify(token).isAccepted()).isTrue();
        assertThat(verifierAt(p, acceptUntil.plusSeconds(1)).verify(token).rejectReason()).isEqualTo(RejectReason.UNKNOWN_KID);
    }

    @Test
    void thePreviousKey_isRejectedOnceRemovedFromTheConfiguration() throws Exception {
        String token = hs256(PREVIOUS_KID, PREVIOUS_SECRET, validClaims());

        assertThat(currentOnlyAt(NOW).verify(token).rejectReason()).isEqualTo(RejectReason.UNKNOWN_KID);
    }

    @Test
    void missingSubjectOrExpiry_isRejected() throws Exception {
        JwtTokenVerifier verifier = currentOnlyAt(NOW);

        assertThat(verifier.verify(hs256(CURRENT_KID, CURRENT_SECRET, claims(null, NOW, NOW.plusSeconds(60)))).rejectReason())
                .isEqualTo(RejectReason.MISSING_CLAIMS);
        assertThat(verifier.verify(hs256(CURRENT_KID, CURRENT_SECRET, claims("acc-1", NOW, null))).rejectReason())
                .isEqualTo(RejectReason.MISSING_CLAIMS);
    }

    @Test
    void garbageAndTrackingCodes_areMalformed() throws Exception {
        JwtTokenVerifier verifier = currentOnlyAt(NOW);

        assertThat(verifier.verify("not-a-token").rejectReason()).isEqualTo(RejectReason.MALFORMED);
        assertThat(verifier.verify("").rejectReason()).isEqualTo(RejectReason.MALFORMED);
        assertThat(verifier.verify("a.b.c").rejectReason()).isEqualTo(RejectReason.MALFORMED);
    }
}
