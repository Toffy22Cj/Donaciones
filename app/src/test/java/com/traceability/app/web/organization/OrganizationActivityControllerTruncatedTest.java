package com.traceability.app.web.organization;

import com.traceability.app.application.activity.OrganizationActivityQuery;
import com.traceability.app.application.activity.OrganizationActivityQuery.ActivityPage;
import com.traceability.app.web.campaign.DiscoveryCursorCodec;
import com.traceability.contracts.authorization.AuthorizationPrincipal;
import com.traceability.contracts.authorization.AuthorizationRole;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Enmienda 2 de la ficha R-6 (plan de cierre, §G-8): la respuesta lleva el {@code truncated} de la consulta. El test
 * HTTP solo ve organizaciones pequeñas ({@code false}); este fija que un {@code true} de la consulta llega a la
 * respuesta (lo encontró la revisión adversaria: sin él, una respuesta con {@code false} fijo sobrevivía).
 */
class OrganizationActivityControllerTruncatedTest {

    @Test
    void e2_6_aTruncatedPage_reachesTheResponse() {
        OrganizationActivityQuery query = mock(OrganizationActivityQuery.class);
        AuthorizationPrincipal principal = new AuthorizationPrincipal("acct-1", "org-A", Set.of(AuthorizationRole.EMPLOYEE), null);
        when(query.page(any(), eq("org-A"), isNull())).thenReturn(new ActivityPage(List.of(), null, true));
        OrganizationActivityController controller = new OrganizationActivityController(query, mock(DiscoveryCursorCodec.class));

        ResponseEntity<OrganizationActivityController.PageResponse> response = controller.activity(principal, "org-A", null);

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().truncated()).isTrue();
        assertThat(response.getBody().nextCursor()).isNull();
    }
}
