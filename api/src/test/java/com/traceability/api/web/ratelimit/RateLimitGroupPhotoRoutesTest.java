package com.traceability.api.web.ratelimit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan 3 de {@code plan-tanda-2026-11-19.md} (ADR-053, firmado por Carlos el 2026-11-19): lo que el límite de intentos
 * cubre de las fotos por paso. La subida por paso ({@code asset-evidence}) entra en {@code media-upload}; la copia
 * censurada del seguimiento (ADR-021-D Enm. 2) cuelga de {@code /donations/tracking/**} y ya está en {@code tracking}.
 * Las lecturas autenticadas de los miembros y la publicación NO tienen límite: no son superficies de adivinación.
 */
class RateLimitGroupPhotoRoutesTest {

    @Test
    void theStepPhotoUploadIsInMediaUpload_onlyAsAPost() {
        assertThat(RateLimitGroup.of("POST", "/api/v1/organizations/org-1/asset-evidence")).contains(RateLimitGroup.MEDIA_UPLOAD);
        assertThat(RateLimitGroup.of("POST", "/api/v1/organizations/o%201/asset-evidence")).contains(RateLimitGroup.MEDIA_UPLOAD);
        assertThat(RateLimitGroup.of("GET", "/api/v1/organizations/org-1/asset-evidence")).isEmpty();
        assertThat(RateLimitGroup.of("PUT", "/api/v1/organizations/org-1/asset-evidence")).isEmpty();
        assertThat(RateLimitGroup.of("POST", "/api/v1/organizations/org-1/other/asset-evidence")).isEmpty();
    }

    @Test
    void theTrackingCopyRouteIsInTheTrackingGroup() {
        assertThat(RateLimitGroup.of("GET", "/api/v1/donations/tracking/assets/asset-ref/evidence-preview")).contains(RateLimitGroup.TRACKING);
        assertThat(RateLimitGroup.of("GET", "/api/v1/donations/tracking")).contains(RateLimitGroup.TRACKING);
        assertThat(RateLimitGroup.of("GET", "/api/v1/donations/tracking/assets/asset-ref/history")).contains(RateLimitGroup.TRACKING);
    }

    @Test
    void theAuthenticatedReadsAndThePublicationHaveNoLimit() {
        for (String path : new String[] {
                "/api/v1/physical-assets/a1/evidence/steps", "/api/v1/physical-assets/a1/evidence/received",
                "/api/v1/physical-assets/a1/evidence/redacted", "/api/v1/physical-assets/a1/evidence/redacted/publication"}) {
            for (String method : new String[] {"GET", "PUT", "DELETE", "POST"}) {
                assertThat(RateLimitGroup.of(method, path)).as(method + " " + path).isEmpty();
            }
        }
    }

    @Test
    void theStepUploadDoesNotConsumeAnotherGroupsQuota() {
        assertThat(RateLimitGroup.of("POST", "/api/v1/organizations/org-1/asset-evidence").orElseThrow())
                .isNotIn(RateLimitGroup.LOGIN, RateLimitGroup.TRACKING, RateLimitGroup.REGISTRATION);
    }
}
