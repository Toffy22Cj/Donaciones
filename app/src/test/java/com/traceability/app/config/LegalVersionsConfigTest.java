package com.traceability.app.config;

import identity.domain.model.LegalVersions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/** ADR-050 test 21 (plan R-5): sin las versiones vigentes en la configuración, la aplicación no arranca. */
class LegalVersionsConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(LegalVersionsConfig.class);

    @Test
    void withTheThreeVersions_theBeanHasThem() {
        runner.withPropertyValues("traceability.legal.terms-version=v1-2026-10",
                        "traceability.legal.data-policy-version=v2-2026-11",
                        "traceability.legal.organization-terms-version= v3-2026-12 ")
                .run(context -> assertThat(context.getBean(LegalVersions.class))
                        .isEqualTo(new LegalVersions("v1-2026-10", "v2-2026-11", "v3-2026-12")));
    }

    @Test
    void withoutAnyOfThem_orBlank_theContextDoesNotStart_andTheMessageNamesTheVariable() {
        runner.withPropertyValues("traceability.legal.terms-version=v1-2026-10",
                        "traceability.legal.organization-terms-version=v1-2026-10")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause()
                            .hasMessageContaining("TRACEABILITY_LEGAL_DATA_POLICY_VERSION");
                });
        runner.withPropertyValues("traceability.legal.terms-version=  ",
                        "traceability.legal.data-policy-version=v1-2026-10",
                        "traceability.legal.organization-terms-version=v1-2026-10")
                .run(context -> assertThat(context).hasFailed());
    }
}
