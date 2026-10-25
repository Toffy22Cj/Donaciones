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
                        "POST /api/v1/auth/password-reset PUBLIC",
                        "POST /api/v1/auth/password-reset/confirm PUBLIC",
                        "GET /api/v1/public/campaigns/{publicCode} PUBLIC",
                        "GET /api/v1/public/campaigns PUBLIC",
                        "POST /api/v1/public/campaigns/{publicCode}/donation-intents OPTIONAL_JWT",
                        "GET /api/v1/public/campaigns/{publicCode}/narrative PUBLIC",
                        // ADR-051 F6, Enmienda 1: el logo público, por el código de la convocatoria (Tarea 2)
                        "GET /api/v1/public/campaigns/{publicCode}/organization-logo PUBLIC",
                        // Enmienda 3 de ADR-037, D6 (APROBADA): consulta de la intención con Intent-Token
                        "GET /api/v1/public/donation-intents/{intentId} PUBLIC",
                        "POST /api/v1/webhooks/payments PUBLIC");
    }

    @Test
    void anythingElse_isProtected() {
        assertThat(PublicRoutes.accessFor("GET", "/api/v1/accounts/me")).isEmpty();
        assertThat(PublicRoutes.accessFor("DELETE", "/api/v1/public/campaigns/CV-1")).isEmpty();
        assertThat(PublicRoutes.accessFor("POST", "/api/v1/physical-assets/a-1/split")).isEmpty();
    }

    /** ADR-051 F6 y Enmienda 1 (Tarea 2, T2-14): solo el logo por código es público; el resto del logo exige JWT. */
    @Test
    void theOrganizationLogo_isPublicOnlyByCampaignCode() {
        assertThat(PublicRoutes.accessFor("GET", "/api/v1/public/campaigns/CV-1/organization-logo"))
                .contains(PublicRoutes.Access.PUBLIC);
        assertThat(PublicRoutes.accessFor("GET", "/api/v1/organizations/org-1/logo")).isEmpty();
        assertThat(PublicRoutes.accessFor("PUT", "/api/v1/organizations/org-1/logo")).isEmpty();
        assertThat(PublicRoutes.accessFor("DELETE", "/api/v1/organizations/org-1/logo")).isEmpty();
        // la ruta antigua de F6 no se registra (Enmienda 1, "Consecuencias")
        assertThat(PublicRoutes.accessFor("GET", "/api/v1/public/organizations/org-1/logo")).isEmpty();
    }
}
