package identity.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** ADR-050 D8 (plan R-4 §6): caducidad a los 30 minutos y enfriamiento de 5, con sus bordes. */
class PasswordResetTokenTest {

    private static final Instant T0 = Instant.parse("2026-10-25T10:00:00Z");
    private static final AccountId ACCOUNT = new AccountId("acc-1");

    private static PasswordResetToken pending() {
        return PasswordResetToken.issue("tok-1", ACCOUNT, "hash", T0);
    }

    private static PasswordResetToken with(PasswordResetTokenStatus status) {
        return PasswordResetToken.reconstitute("tok-1", "hash", ACCOUNT, status, T0, T0.plusSeconds(1800), null);
    }

    @Test
    void issue_isPendingAndExpiresThirtyMinutesLater() {
        PasswordResetToken token = pending();

        assertThat(token.getStatus()).isEqualTo(PasswordResetTokenStatus.PENDING);
        assertThat(token.getCreatedAt()).isEqualTo(T0);
        assertThat(token.getExpiresAt()).isEqualTo(T0.plusSeconds(30 * 60));
        assertThat(token.getUsedAt()).isNull();
    }

    @Test
    void isUsableAt_untilJustBeforeExpiry_andNotAtExpiry() {
        PasswordResetToken token = pending();

        assertThat(token.isUsableAt(T0)).isTrue();
        assertThat(token.isUsableAt(T0.plusSeconds(1800).minusMillis(1))).isTrue();
        assertThat(token.isUsableAt(T0.plusSeconds(1800))).isFalse();
    }

    @Test
    void usedOrSuperseded_isNeverUsable() {
        assertThat(with(PasswordResetTokenStatus.USED).isUsableAt(T0)).isFalse();
        assertThat(with(PasswordResetTokenStatus.SUPERSEDED).isUsableAt(T0)).isFalse();
    }

    @Test
    void isInCooldownAt_lessThanFiveMinutes_andNotAtFive() {
        PasswordResetToken token = pending();

        assertThat(token.isInCooldownAt(T0)).isTrue();
        assertThat(token.isInCooldownAt(T0.plusSeconds(300).minusMillis(1))).isTrue();
        assertThat(token.isInCooldownAt(T0.plusSeconds(300))).isFalse();
        assertThat(with(PasswordResetTokenStatus.USED).isInCooldownAt(T0)).isFalse();
    }

    @Test
    void toString_neverShowsTheHash() {
        assertThat(pending().toString()).doesNotContain("hash");
    }
}
