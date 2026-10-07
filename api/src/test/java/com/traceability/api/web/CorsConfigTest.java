package com.traceability.api.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** S-03: los orígenes CORS solo por variable de entorno, exactos y nunca con comodín. */
class CorsConfigTest {

    @Test
    void exactOrigins_areAccepted() {
        assertThat(CorsConfig.parseOrigins(" http://localhost:5173 , https://demo.paxfide.example ,"))
                .containsExactly("http://localhost:5173", "https://demo.paxfide.example");
    }

    @Test
    void withoutTheVariable_noCrossOriginIsAllowed() {
        assertThat(CorsConfig.parseOrigins("")).isEmpty();
        assertThat(CorsConfig.parseOrigins(null)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"*", "https://*.paxfide.example", "http://localhost:5173,*", "https://demo.example/",
            "https://demo.example/app", "demo.example", "null", "file://x"})
    void wildcardsAndMalformedOrigins_preventStartup(String value) {
        assertThatThrownBy(() -> CorsConfig.parseOrigins(value)).isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining(value.length() > 3 ? value : "§");
    }
}
