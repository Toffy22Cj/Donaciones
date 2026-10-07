package identity.domain.model;

import identity.domain.exception.PasswordTooShortException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** H-P2-1 (Carlos, 2026-10-07): una contraseña tiene al menos 12 caracteres. */
class PlainPasswordTest {

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "           ", "Pass123!", "elevenchars"})
    void lessThanTwelveCharacters_isRejected_withoutEchoingIt(String value) {
        assertThatThrownBy(() -> new PlainPassword(value)).isInstanceOf(PasswordTooShortException.class)
                .hasMessageNotContaining(value == null || value.isBlank() ? "§" : value);
    }

    @Test
    void twelveCharacters_isAccepted_countingCodePoints() {
        assertThat(new PlainPassword("twelve-chars").value()).isEqualTo("twelve-chars");
        assertThat(new PlainPassword("ñandú-🐦🐦🐦🐦🐦🐦🐦").value()).hasSizeGreaterThan(12);
        assertThatThrownBy(() -> new PlainPassword("🐦🐦🐦🐦🐦🐦🐦🐦🐦🐦🐦")).as("11 code points, 22 chars UTF-16")
                .isInstanceOf(PasswordTooShortException.class);
    }

    @Test
    void theValue_isNotPrinted() {
        assertThat(new PlainPassword("super-secret-value").toString()).doesNotContain("super-secret-value");
    }
}
