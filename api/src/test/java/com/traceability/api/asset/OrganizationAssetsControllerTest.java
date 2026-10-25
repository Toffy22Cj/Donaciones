package com.traceability.api.asset;

import com.traceability.api.auth.jwt.JwtAuthFilter;
import com.traceability.api.web.CurrentActorArgumentResolver;
import com.traceability.contracts.authorization.AuthorizationPrincipal;
import com.traceability.contracts.authorization.AuthorizationRole;
import com.traceability.core.application.port.out.PhysicalAssetOperationalReadPort;
import com.traceability.core.application.port.out.PhysicalAssetOperationalView;
import com.traceability.core.domain.event.HumanActor;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Ficha R-6, Enmienda 1, E2 (G-9; adenda G-9 del plan B, test G9-3): {@code lastEventAt?} en ISO-8601 UTC y omitido
 * del JSON cuando no hay fecha (ni {@code null} ni un texto inventado).
 */
class OrganizationAssetsControllerTest {

    private static final AuthorizationPrincipal EMPLOYEE =
            new AuthorizationPrincipal("acc-1", "org-1", Set.of(AuthorizationRole.EMPLOYEE), null);

    private final PhysicalAssetOperationalReadPort reads = mock(PhysicalAssetOperationalReadPort.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new OrganizationAssetsController(reads))
            .setCustomArgumentResolvers(new CurrentActorArgumentResolver())
            .build();

    private static PhysicalAssetOperationalView view(String ref, Instant lastEventAt) {
        return new PhysicalAssetOperationalView(ref, "REGISTERED", "CUST-1", "WH-1", new BigDecimal("10.0000"), "KGS",
                null, "FOOD", lastEventAt);
    }

    private String list() throws Exception {
        return mvc.perform(get("/api/v1/organizations/org-1/physical-assets")
                        .requestAttr(JwtAuthFilter.PRINCIPAL_ATTRIBUTE, EMPLOYEE))
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void g9_lastEventAt_isIso8601Utc() throws Exception {
        when(reads.listForOrganization(eq("org-1"), any(HumanActor.class)))
                .thenReturn(List.of(view("a-1", Instant.parse("2026-10-05T10:00:00Z"))));

        assertThat(list()).contains("\"lastEventAt\":\"2026-10-05T10:00:00Z\"");
    }

    @Test
    void g9_withoutADate_theFieldIsOmitted() throws Exception {
        when(reads.listForOrganization(eq("org-1"), any(HumanActor.class))).thenReturn(List.of(view("a-1", null)));

        String body = list();

        assertThat(body).contains("\"assetRef\":\"a-1\"");
        assertThat(body).doesNotContain("lastEventAt");
    }
}
