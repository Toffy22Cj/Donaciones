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
    void theCorsFilter_runsBeforeAnyOther_soAPreflightNeverReachesTheJwtFilter() {
        // el filtro JWT es un @Component sin orden explícito (LOWEST_PRECEDENCE); el de CORS debe ir siempre antes
        assertThat(new CorsConfig().corsFilter("http://localhost:5173").getOrder())
                .isEqualTo(org.springframework.core.Ordered.HIGHEST_PRECEDENCE);
    }

    @Test
    void theAllowedMethods_areExactlyTheFiveOfDd57_withPutAndDelete_andWithoutPatch() {
        // DD-57, ampliada el 2026-10-25 (plan CORS, #138): PUT y DELETE por el logo (#111); nada más
        assertThat(CorsConfig.ALLOWED_METHODS).containsExactly("GET", "POST", "PUT", "DELETE", "OPTIONS");
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
