package com.traceability.app.application.activity;

import com.traceability.contracts.authorization.AuthorizationPrincipal;
import com.traceability.contracts.authorization.AuthorizationRole;
import com.traceability.convocatoria.application.query.OrganizationCampaignClosuresQuery;
import com.traceability.core.application.port.out.ActivityEventsPort;
import com.traceability.core.application.port.out.AssetDirectoryPort;
import com.traceability.core.application.port.out.EventBatchCountPort;
import com.traceability.core.application.port.out.FundDirectoryPort;
import com.traceability.core.application.service.LocationReferenceService;
import com.traceability.core.application.authorization.OrganizationBoundaryPolicy;
import com.traceability.crypto.application.port.out.MerkleBatchRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Ficha R-6, Enmienda 2 (plan de cierre, §G-8; P-E2-1): {@code truncated} en G-8 con el criterio de G-7
 * ({@code OrganizationIntegrityQuery}): se leen {@code DIRECTORY_LIMIT + 1} de cada directorio, {@code truncated} si
 * alguno pasa del límite, y solo se usan los primeros {@code DIRECTORY_LIMIT}. Con dobles de los directorios, como el
 * 10d de G-7, para no sembrar 10 001 documentos.
 */
class OrganizationActivityTruncatedTest {

    private static final String ORG = "org-A";
    private static final int LIMIT = OrganizationActivityQuery.DIRECTORY_LIMIT;

    private FundDirectoryPort funds;
    private AssetDirectoryPort assets;
    private ActivityEventsPort events;
    private OrganizationActivityQuery query;

    @BeforeEach
    void setUp() {
        funds = mock(FundDirectoryPort.class);
        assets = mock(AssetDirectoryPort.class);
        events = mock(ActivityEventsPort.class);
        EventBatchCountPort counts = mock(EventBatchCountPort.class);
        OrganizationCampaignClosuresQuery closures = mock(OrganizationCampaignClosuresQuery.class);
        when(events.findPage(any(), any(), any(), any(), anyInt())).thenReturn(List.of());
        when(counts.countByBatch(any())).thenReturn(List.of());
        when(closures.closedCampaigns(anyString())).thenReturn(List.of());
        query = new OrganizationActivityQuery(funds, assets, events, counts, mock(MerkleBatchRepositoryPort.class),
                closures, mock(LocationReferenceService.class), new OrganizationBoundaryPolicy());
    }

    private static List<String> ids(String prefix, int n) {
        return IntStream.range(0, n).mapToObj(i -> prefix + i).toList();
    }

    /**
     * Los directorios devuelven como mucho lo que se les pide, como los adaptadores Mongo. Desde la Enmienda 3 (P-E2-2),
     * G-8 lee los de lo más reciente: los dobles son esos métodos; el criterio de {@code truncated} no cambia.
     */
    private void directories(int fundCount, int assetCount) {
        when(funds.findLatestFundIdsByOrganization(eq(ORG), anyInt()))
                .thenAnswer(i -> ids("fund-", Math.min(fundCount, i.<Integer>getArgument(1))));
        when(assets.findLatestAssetIdsByOrganization(eq(ORG), anyInt()))
                .thenAnswer(i -> ids("asset-", Math.min(assetCount, i.<Integer>getArgument(1))));
    }

    private static AuthorizationPrincipal employee() {
        return new AuthorizationPrincipal("acct-1", ORG, Set.of(AuthorizationRole.EMPLOYEE), null);
    }

    @SuppressWarnings("unchecked")
    private Collection<String> streamsRead() {
        ArgumentCaptor<Collection<String>> streams = ArgumentCaptor.forClass(Collection.class);
        verify(events).findPage(streams.capture(), any(), any(), any(), anyInt());
        return streams.getValue();
    }

    @Test
    void e2_1_moreAssetsThanTheLimit_isTruncated_andTheExtraOneIsNotRead() {
        directories(3, LIMIT + 1);

        OrganizationActivityQuery.ActivityPage page = query.page(employee(), ORG, null);

        assertThat(page.truncated()).isTrue();
        verify(assets).findLatestAssetIdsByOrganization(ORG, LIMIT + 1);
        assertThat(streamsRead()).hasSize(3 + LIMIT).doesNotContain("asset-" + LIMIT).contains("asset-" + (LIMIT - 1));
    }

    @Test
    void e2_2_moreFundsThanTheLimit_isTruncated() {
        directories(LIMIT + 1, 2);

        OrganizationActivityQuery.ActivityPage page = query.page(employee(), ORG, null);

        assertThat(page.truncated()).isTrue();
        verify(funds).findLatestFundIdsByOrganization(ORG, LIMIT + 1);
        assertThat(streamsRead()).hasSize(LIMIT + 2).doesNotContain("fund-" + LIMIT);
    }

    @Test
    void e2_3_exactlyTheLimitOfEach_isNotTruncated() {
        directories(LIMIT, LIMIT);

        OrganizationActivityQuery.ActivityPage page = query.page(employee(), ORG, null);

        assertThat(page.truncated()).isFalse();
        assertThat(streamsRead()).hasSize(2 * LIMIT);
    }

    @Test
    void e2_4_aSmallOrganization_isNotTruncated() {
        directories(2, 3);

        OrganizationActivityQuery.ActivityPage page = query.page(employee(), ORG, null);

        assertThat(page.truncated()).isFalse();
        assertThat(page.items()).isEmpty();
        assertThat(page.next()).isNull();
        assertThat(streamsRead()).hasSize(5);
    }
}
