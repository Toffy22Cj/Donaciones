package com.traceability.core.support;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** La sonda hace fallar el test cuando no hay transacción, en lugar de dejarlo pasar. */
class TransactionProbeTest {

    @Test
    void aWriteOutsideATransaction_failsTheAssertion() {
        TransactionProbe probe = new TransactionProbe();
        probe.record("append"); // aquí no hay transacción activa

        assertThatThrownBy(probe::assertEveryWriteWasTransactional)
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("sin transacción activa");
    }

    @Test
    void noWritesAtAll_failsTheAssertion() {
        assertThatThrownBy(new TransactionProbe()::assertEveryWriteWasTransactional)
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("no vio ninguna escritura");
    }
}
