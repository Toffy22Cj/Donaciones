package com.traceability.app.application.integrity;

import com.traceability.contracts.authorization.AuthorizationPrincipal;
import com.traceability.contracts.authorization.AuthorizationRole;
import com.traceability.core.application.authorization.CrossOrganizationAccessException;
import com.traceability.core.application.authorization.InsufficientRoleException;
import com.traceability.core.application.authorization.OrganizationBoundaryPolicy;
import com.traceability.core.application.port.out.AssetDirectoryPort;
import com.traceability.core.application.port.out.EventBatchCountPort;
import com.traceability.core.application.port.out.FundDirectoryPort;
import com.traceability.crypto.application.port.out.MerkleBatchRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Ficha R-6, G-7: autorización antes de leer nada (10e) y {@code truncated} al llegar al límite (10d, C-5). */
class OrganizationIntegrityQueryTest {

    private FundDirectoryPort funds;
    private AssetDirectoryPort assets;
    private EventBatchCountPort counts;
    private MerkleBatchRepositoryPort batches;
    private OrganizationIntegrityQuery query;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        funds = mock(FundDirectoryPort.class);
        assets = mock(AssetDirectoryPort.class);
        counts = mock(EventBatchCountPort.class);
        batches = mock(MerkleBatchRepositoryPort.class);
        ObjectProvider<Clock> clock = mock(ObjectProvider.class);
        when(clock.getIfAvailable(any())).thenReturn(Clock.systemUTC());
        query = new OrganizationIntegrityQuery(funds, assets, counts, batches, new OrganizationBoundaryPolicy(), clock);
    }

    private static AuthorizationPrincipal principal(String org, AuthorizationRole... roles) {
        return new AuthorizationPrincipal("acct-1", org, Set.of(roles), null);
    }

    private static List<String> ids(String prefix, int n) {
        return IntStream.range(0, n).mapToObj(i -> prefix + i).toList();
    }

    // P-E2-2 (Enmienda 3 de la ficha R-6): G-7 no cambia; sigue con los directorios de siempre, nunca con los nuevos
    @Test
    void pe2_g7_keepsReadingTheDirectoriesOfAlways_neverTheLatest() {
        int limit = OrganizationIntegrityQuery.DIRECTORY_LIMIT;
        when(funds.findFundIdsByOrganization("org-1", limit + 1)).thenReturn(ids("f", 2));
        when(assets.findAssetIdsByOrganization("org-1", limit + 1)).thenReturn(ids("a", 3));
        when(counts.countByBatch(any())).thenReturn(List.of());

        query.integrityOf(principal("org-1", AuthorizationRole.ADMINISTRATOR), "org-1");

        verify(funds).findFundIdsByOrganization("org-1", limit + 1);
        verify(assets).findAssetIdsByOrganization("org-1", limit + 1);
        verify(funds, org.mockito.Mockito.never()).findLatestFundIdsByOrganization(anyString(), anyInt());
        verify(assets, org.mockito.Mockito.never()).findLatestAssetIdsByOrganization(anyString(), anyInt());
    }

    // 10d: con más fondos que el límite, truncated; solo se cuentan los del límite
    @Test
    void t10d_moreFundsThanTheLimit_isTruncated_andCountsOnlyTheLimit() {
        int limit = OrganizationIntegrityQuery.DIRECTORY_LIMIT;
        when(funds.findFundIdsByOrganization("org-1", limit + 1)).thenReturn(ids("f", limit + 1));
        when(assets.findAssetIdsByOrganization("org-1", limit + 1)).thenReturn(ids("a", 3));
        when(counts.countByBatch(any())).thenReturn(List.of());

        assertThat(query.integrityOf(principal("org-1", AuthorizationRole.ADMINISTRATOR), "org-1").truncated()).isTrue();
        verify(counts).countByBatch(argThat((Collection<String> streams) -> streams.size() == limit + 3
                && !streams.contains("f" + limit)));
    }

    @Test
    void t10d_moreAssetsThanTheLimit_isTruncated() {
        int limit = OrganizationIntegrityQuery.DIRECTORY_LIMIT;
        when(funds.findFundIdsByOrganization("org-1", limit + 1)).thenReturn(ids("f", 2));
        when(assets.findAssetIdsByOrganization("org-1", limit + 1)).thenReturn(ids("a", limit + 1));
        when(counts.countByBatch(any())).thenReturn(List.of());

        assertThat(query.integrityOf(principal("org-1", AuthorizationRole.REPRESENTATIVE), "org-1").truncated()).isTrue();
    }

    @Test
    void t10d_exactlyTheLimit_isNotTruncated() {
        int limit = OrganizationIntegrityQuery.DIRECTORY_LIMIT;
        when(funds.findFundIdsByOrganization("org-1", limit + 1)).thenReturn(ids("f", limit));
        when(assets.findAssetIdsByOrganization("org-1", limit + 1)).thenReturn(ids("a", limit));
        when(counts.countByBatch(any())).thenReturn(List.of());

        assertThat(query.integrityOf(principal("org-1", AuthorizationRole.ADMINISTRATOR), "org-1").truncated()).isFalse();
    }

    // 10e: un EMPLOYEE, otra organización o sin organización → el 403 de siempre, sin leer nada
    @Test
    void t10e_anEmployee_isForbidden_beforeReadingAnything() {
        assertThatThrownBy(() -> query.integrityOf(principal("org-1", AuthorizationRole.EMPLOYEE), "org-1"))
                .isInstanceOf(InsufficientRoleException.class);
        verifyNoInteractions(funds, assets, counts, batches);
    }

    @Test
    void t10e_anotherOrganization_isForbidden_beforeReadingAnything() {
        assertThatThrownBy(() -> query.integrityOf(principal("org-2", AuthorizationRole.ADMINISTRATOR), "org-1"))
                .isInstanceOf(CrossOrganizationAccessException.class);
        assertThatThrownBy(() -> query.integrityOf(principal(null, AuthorizationRole.ADMINISTRATOR), "org-1"))
                .isInstanceOf(CrossOrganizationAccessException.class);
        verifyNoInteractions(funds, assets, counts, batches);
        verify(funds, org.mockito.Mockito.never()).findFundIdsByOrganization(anyString(), anyInt());
    }
}
