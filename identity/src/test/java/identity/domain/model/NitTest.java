package identity.domain.model;

import identity.domain.exception.InvalidOrganizationProfileFieldException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Ficha R-7 §7 y §9, test 1: el dígito de verificación del NIT con los pesos de la DIAN. Todos los NIT de este test son
 * <b>sintéticos</b> (P-R7-5): números de 9 dígitos con el dígito calculado con el algoritmo, sin relación con ninguna
 * entidad real.
 */
class NitTest {

    @Test
    void syntheticNits_withTheirCheckDigit_areValid() {
        assertThat(Nit.of("900000006", "9").number()).isEqualTo("900000006");
        assertThat(Nit.of("900000014", "8").checkDigit()).isEqualTo("8");
        assertThat(Nit.of("900000003", "7").number()).isEqualTo("900000003");
    }

    @Test
    void remainderZeroAndOne_theDigitIsTheRemainderItself() {
        // r = 0 → dígito 0; r = 1 → dígito 1 (no 11 − r)
        assertThat(Nit.checkDigitOf("900000009")).isEqualTo(0);
        assertThat(Nit.checkDigitOf("900000002")).isEqualTo(1);
        assertThat(Nit.of("900000009", "0").checkDigit()).isEqualTo("0");
        assertThat(Nit.of("900000002", "1").checkDigit()).isEqualTo("1");
        // r = 10 → 11 − 10 = 1
        assertThat(Nit.checkDigitOf("900000005")).isEqualTo(1);
    }

    @Test
    void aWrongCheckDigit_isNitCheckDigit() {
        assertThatThrownBy(() -> Nit.of("900000006", "8"))
                .isInstanceOf(InvalidOrganizationProfileFieldException.class)
                .extracting(e -> ((InvalidOrganizationProfileFieldException) e).getField()).isEqualTo("nitCheckDigit");
        assertThatThrownBy(() -> Nit.of("900000006", "10"))
                .extracting(e -> ((InvalidOrganizationProfileFieldException) e).getField()).isEqualTo("nitCheckDigit");
    }

    @Test
    void aNitThatIsNotNineDigits_isNit() {
        for (String bad : new String[] {"90000000", "9000000060", "90000000a", " 900000006", ""}) {
            assertThatThrownBy(() -> Nit.of(bad, "9")).as(bad)
                    .extracting(e -> ((InvalidOrganizationProfileFieldException) e).getField()).isEqualTo("nit");
        }
    }
}
