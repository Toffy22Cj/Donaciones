package com.traceability.api.auth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Lista explícita y completa de rutas públicas (plan B3 §2.3.1, condición de Q1). */
class PublicRoutesTest {

    @Test
    void theListIsExactlyTheOneApprovedInThePlan() {
        assertThat(PublicRoutes.ROUTES).extracting(r -> r.method() + " " + r.pattern() + " " + r.access())
                .containsExactlyInAnyOrder(
                        "* /api/v1/donations/tracking/** PUBLIC",
                        "POST /api/v1/auth/login PUBLIC",
                        "POST /api/v1/auth/register PUBLIC",
                        "GET /api/v1/public/campaigns/{publicCode} PUBLIC",
                        "GET /api/v1/public/campaigns PUBLIC",
                        "POST /api/v1/public/campaigns/{publicCode}/donation-intents OPTIONAL_JWT",
                        "GET /api/v1/public/campaigns/{publicCode}/narrative PUBLIC",
                        "POST /api/v1/webhooks/payments PUBLIC");
    }

    @Test
    void anythingElse_isProtected() {
        assertThat(PublicRoutes.accessFor("GET", "/api/v1/accounts/me")).isEmpty();
        assertThat(PublicRoutes.accessFor("DELETE", "/api/v1/public/campaigns/CV-1")).isEmpty();
        assertThat(PublicRoutes.accessFor("POST", "/api/v1/physical-assets/a-1/split")).isEmpty();
    }
}
