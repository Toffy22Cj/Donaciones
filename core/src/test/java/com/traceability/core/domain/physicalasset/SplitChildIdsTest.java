package com.traceability.core.domain.physicalasset;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** D-SPLIT P1: id del hijo determinista con espacio de nombres (UUID v5 de padre + commandId). DoD test 2. */
class SplitChildIdsTest {

    @Test
    void v5_matchesTheRfc9562Vector() {
        // RFC 9562 Apéndice A.4: NameSpace_DNS + "www.example.com"; contrastado con uuid.uuid5 de Python
        UUID dns = UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8");
        assertThat(SplitChildIds.v5(dns, "www.example.com"))
                .isEqualTo(UUID.fromString("2ed6657d-e927-568b-95e1-2665a8aea6a2"));
    }

    @Test
    void theChildIdIsTheV5OfParentAndCommand_inTheSplitNamespace() {
        // uuid.uuid5(UUID('6f1c2a5e-9b47-5d3a-8e21-4c7b9d0f1a36'), 'parent-1:cmd-1') en Python
        assertThat(SplitChildIds.of("parent-1", "cmd-1")).isEqualTo("2cd9cb63-d652-5828-aed7-3c6cecba5d77");
    }

    @Test
    void sameParentAndCommand_giveTheSameChild_otherParentOrCommand_another() {
        assertThat(SplitChildIds.of("parent-1", "cmd-1")).isEqualTo(SplitChildIds.of("parent-1", "cmd-1"));
        assertThat(SplitChildIds.of("parent-2", "cmd-1")).isNotEqualTo(SplitChildIds.of("parent-1", "cmd-1"));
        assertThat(SplitChildIds.of("parent-1", "cmd-2")).isNotEqualTo(SplitChildIds.of("parent-1", "cmd-1"));
    }

    @Test
    void theIdDoesNotRevealTheCommandId() {
        assertThat(SplitChildIds.of("parent-1", "secret-command-id")).doesNotContain("secret");
    }
}
