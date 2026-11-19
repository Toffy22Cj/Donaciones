package com.traceability.core.application.command;

import com.traceability.contracts.authorization.AuthorizationPrincipal;
import com.traceability.contracts.authorization.AuthorizationRole;
import com.traceability.contracts.authorization.IdentityPrincipalPort;
import com.traceability.contracts.media.DeliveryEvidencePort;
import com.traceability.contracts.media.EvidenceAlreadyBoundException;
import com.traceability.contracts.media.EvidenceNotFoundException;
import com.traceability.contracts.media.EvidenceStep;
import com.traceability.core.application.authorization.CrossOrganizationAccessException;
import com.traceability.core.application.authorization.InsufficientRoleException;
import com.traceability.core.application.authorization.OrganizationBoundaryPolicy;
import com.traceability.core.application.authorization.RoleAuthorizationPolicy;
import com.traceability.core.application.exception.InvalidEvidenceRefException;
import com.traceability.core.application.port.out.EventStorePort;
import com.traceability.core.application.port.out.ProcessedCommandRepositoryPort;
import com.traceability.core.application.service.TransactionalEventPublisher;
import com.traceability.core.domain.event.DomainEvent;
import com.traceability.core.domain.event.HumanActor;
import com.traceability.core.domain.fund.Fund;
import com.traceability.core.domain.fund.OrganizationRef;
import com.traceability.core.domain.physicalasset.PhysicalAsset;
import com.traceability.core.domain.physicalasset.payloads.AssetDispatchedV3Payload;
import com.traceability.core.domain.physicalasset.payloads.AssetReceivedV3Payload;
import com.traceability.core.domain.physicalasset.payloads.AssetRegisteredV5Payload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.mockito.stubbing.Answer;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * ADR-051 Enmienda 2, E2-1 y E2-3 (plan de la Tarea 1, grupo B, tests 5 a 11): {@code evidenceRef?} opcional en
 * registrar (los dos caminos), despachar y recibir, con el orden de F3 (formato antes del reclamo; cargar, autorizar,
 * validar en el agregado, vincular solo si hay foto y el agregado generó evento, y después {@code appendAndOutbox}) y la
 * aserción negativa de que sin foto, o con un rechazo por permisos o por estado, no se vincula nada.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StepEvidenceCommandTest {

    private static final String ORG = "ORG-1";
    private static final String PHOTO = "sha256:" + "a1".repeat(32);
    private static final String FUND = "fund-1";

    @Mock private CommandRetryTemplate retryTemplate;
    @Mock private ProcessedCommandRepositoryPort processedCommandRepository;
    @Mock private EventStorePort eventStore;
    @Mock private TransactionalEventPublisher eventPublisher;
    @Mock private IdentityPrincipalPort identityPrincipalPort;
    @Mock private DeliveryEvidencePort evidencePort;

    private PhysicalAssetCommandService service;
    private final HumanActor actor = new HumanActor("user1");

    @BeforeEach
    void setUp() {
        when(retryTemplate.execute(any())).thenAnswer((Answer<Object>) invocation -> {
            Supplier<Object> supplier = invocation.getArgument(0);
            return supplier.get();
        });
        when(eventPublisher.appendAndOutbox(any(), any(), any(Long.class), any(), any(), any(), any(), any())).thenReturn(true);
        service = new PhysicalAssetCommandService(retryTemplate, processedCommandRepository, eventStore, eventPublisher,
                new RoleAuthorizationPolicy(), new OrganizationBoundaryPolicy(), identityPrincipalPort,
                new com.traceability.core.support.StubCampaignInKindEligibilityPort(), evidencePort,
                new com.traceability.core.support.StubMunicipalityCatalogPort());
        principal(ORG, AuthorizationRole.EMPLOYEE);
        Fund fund = Fund.registerFund(FUND, new OrganizationRef(ORG), 5000L, "COP", "campaign-1", "donor-1");
        when(eventStore.loadStream(FUND)).thenReturn(fund.getUncommittedEvents());
    }

    private void principal(String org, AuthorizationRole role) {
        when(identityPrincipalPort.resolvePrincipal("user1"))
                .thenReturn(new AuthorizationPrincipal("user1", org, Set.of(role), null));
    }

    /** Un activo registrado ({@code dispatched = false}) o ya despachado, cargable del event store. */
    private String asset(String assetId, boolean dispatched) {
        PhysicalAsset asset = PhysicalAsset.create(assetId, "MEDICINE", BigDecimal.valueOf(100), "BOX", "WH-1", "CUST-1",
                null, assetId, "ALLOC-1", "SRC-1", ORG, "DON", "DON-REF");
        if (dispatched) {
            asset.dispatch("carrier");
        }
        when(eventStore.loadStream(assetId)).thenReturn(asset.getUncommittedEvents());
        return assetId;
    }

    @SuppressWarnings("unchecked")
    private List<DomainEvent> appended() {
        ArgumentCaptor<List<DomainEvent>> events = ArgumentCaptor.forClass(List.class);
        verify(eventPublisher).appendAndOutbox(any(), any(), any(Long.class), events.capture(), any(), any(), any(), any());
        return events.getValue();
    }

    private void neverAppended() {
        verify(eventPublisher, never()).appendAndOutbox(any(), any(), any(Long.class), any(), any(), any(), any(), any());
    }

    private void neverBound() {
        verify(evidencePort, never()).bindToAsset(anyString(), anyString(), anyString(), any(EvidenceStep.class));
    }

    // ---- dispatch ----

    // test 6: con foto válida se vincula al activo (paso DISPATCHED) después de autorizar y antes de escribir
    @Test
    void test6_dispatchWithAPhoto_bindsItAfterAuthorizing_andBeforeAppending_andTheEventCarriesIt() {
        String assetId = asset("asset-d1", false);

        service.dispatchAsset("cmd-1", assetId, "carrier-1", PHOTO, actor);

        InOrder order = inOrder(eventStore, identityPrincipalPort, evidencePort, eventPublisher);
        order.verify(eventStore).loadStream(assetId);
        order.verify(identityPrincipalPort).resolvePrincipal("user1");
        order.verify(evidencePort).bindToAsset(ORG, PHOTO, assetId, EvidenceStep.DISPATCHED);
        order.verify(eventPublisher).appendAndOutbox(any(), any(), any(Long.class), any(), any(), any(), any(), any());
        assertThat(((AssetDispatchedV3Payload) appended().get(0).payload()).evidenceRef()).isEqualTo(PHOTO);
    }

    // test 4 (negativa): sin foto no se llama al vínculo, y se escribe la versión nueva con null
    @Test
    void test4_dispatchWithoutAPhoto_neverBinds_andWritesNull() {
        String assetId = asset("asset-d2", false);

        service.dispatchAsset("cmd-1", assetId, "carrier-1", actor);

        neverBound();
        assertThat(((AssetDispatchedV3Payload) appended().get(0).payload()).evidenceRef()).isNull();
    }

    // test 7: formato inválido → 400 sin reclamar el comando ni leer el activo
    static java.util.stream.Stream<String> invalidEvidenceRefs() {
        String hex = "a1".repeat(32);
        return java.util.stream.Stream.of("acta-1", "sha256:", "sha256:abc", "SHA256:" + hex, "sha256:" + hex.toUpperCase(),
                "sha256:" + hex + "0", " sha256:" + hex, "");
    }

    @ParameterizedTest
    @MethodSource("invalidEvidenceRefs")
    void test7_anInvalidEvidenceRef_isRejectedBeforeTheClaim_inEveryCommand(String evidenceRef) {
        assertThatThrownBy(() -> service.dispatchAsset("cmd-1", "asset-x", "carrier-1", evidenceRef, actor))
                .isInstanceOf(InvalidEvidenceRefException.class);
        assertThatThrownBy(() -> service.receiveAsset("cmd-1", "asset-x", "fac", "05001", "rec", evidenceRef, actor))
                .isInstanceOf(InvalidEvidenceRefException.class);
        assertThatThrownBy(() -> service.registerPhysicalAssetFromDonation("cmd-1", ORG, "donor-1", "FOOD",
                BigDecimal.TEN, "KG", "c-1", "Bodega", "05001", null, evidenceRef, actor))
                .isInstanceOf(InvalidEvidenceRefException.class);
        assertThatThrownBy(() -> service.registerPhysicalAsset("cmd-1", FUND, ORG, "FOOD", BigDecimal.TEN, "KG", "c-1",
                "Bodega", "05001", "alloc-1", null, evidenceRef, actor))
                .isInstanceOf(InvalidEvidenceRefException.class);

        verifyNoInteractions(processedCommandRepository, eventPublisher, evidencePort, identityPrincipalPort);
        verify(eventStore, never()).loadStream("asset-x");
    }

    // test 8: la foto no existe / es de otra organización / otro propósito / borrada → 400 y ningún evento
    @Test
    void test8_aPhotoThatDoesNotExistForThatStep_writesNothing() {
        String assetId = asset("asset-d3", false);
        doThrow(new EvidenceNotFoundException("not found")).when(evidencePort)
                .bindToAsset(ORG, PHOTO, assetId, EvidenceStep.DISPATCHED);

        assertThatThrownBy(() -> service.dispatchAsset("cmd-1", assetId, "carrier-1", PHOTO, actor))
                .isInstanceOf(EvidenceNotFoundException.class);
        neverAppended();
    }

    // test 9: la misma foto en otro activo → 409 y ningún evento
    @Test
    void test9_aPhotoBoundToAnotherAsset_writesNothing() {
        String assetId = asset("asset-d4", false);
        doThrow(new EvidenceAlreadyBoundException("bound")).when(evidencePort)
                .bindToAsset(ORG, PHOTO, assetId, EvidenceStep.DISPATCHED);

        assertThatThrownBy(() -> service.dispatchAsset("cmd-1", assetId, "carrier-1", PHOTO, actor))
                .isInstanceOf(EvidenceAlreadyBoundException.class);
        neverAppended();
    }

    // test 10 (negativas): un rechazo por permisos o por estado no vincula la foto
    @Test
    void test10_aDispatchRejectedForAnotherOrganization_neverBinds() {
        String assetId = asset("asset-d5", false);
        principal("OTHER-ORG", AuthorizationRole.EMPLOYEE);

        assertThatThrownBy(() -> service.dispatchAsset("cmd-1", assetId, "carrier-1", PHOTO, actor))
                .isInstanceOf(CrossOrganizationAccessException.class);
        neverBound();
        neverAppended();
    }

    @Test
    void test10_aDispatchRejectedForTheRole_neverBinds() {
        String assetId = asset("asset-d6", false);
        principal(ORG, AuthorizationRole.REPRESENTATIVE);

        assertThatThrownBy(() -> service.dispatchAsset("cmd-1", assetId, "carrier-1", PHOTO, actor))
                .isInstanceOf(InsufficientRoleException.class);
        neverBound();
        neverAppended();
    }

    @Test
    void test10_aDispatchRejectedByTheAssetState_neverBinds() {
        String assetId = asset("asset-d7", true); // ya despachado
        assertThatThrownBy(() -> service.dispatchAsset("cmd-1", assetId, "carrier-1", PHOTO, actor))
                .isInstanceOf(RuntimeException.class);
        neverBound();
        neverAppended();
    }

    // test 11: el mismo Command-Id reenviado es un no-op en el reclamo: no se vuelve a vincular
    @Test
    void test11_theSameCommandIdResent_isANoOp_withoutBindingAgain() {
        when(processedCommandRepository.exists("cmd-1")).thenReturn(true);
        when(processedCommandRepository.findOutcome("cmd-1")).thenReturn(java.util.Optional.of("DISPATCH_PHYSICAL_ASSET:asset-d8"));

        service.dispatchAsset("cmd-1", "asset-d8", "carrier-1", PHOTO, actor);

        neverBound();
        neverAppended();
    }

    // ---- receive ----

    @Test
    void test6_receiveWithAPhoto_bindsTheReceiptStep_andTheEventCarriesIt() {
        String assetId = asset("asset-r1", true);

        service.receiveAsset("cmd-1", assetId, "Centro", "05001", "receiver-1", PHOTO, actor);

        verify(evidencePort).bindToAsset(ORG, PHOTO, assetId, EvidenceStep.RECEIVED);
        assertThat(((AssetReceivedV3Payload) appended().get(0).payload()).evidenceRef()).isEqualTo(PHOTO);
    }

    @Test
    void test4_receiveWithoutAPhoto_neverBinds_andWritesNull() {
        String assetId = asset("asset-r2", true);

        service.receiveAsset("cmd-1", assetId, "Centro", "05001", "receiver-1", actor);

        neverBound();
        assertThat(((AssetReceivedV3Payload) appended().get(0).payload()).evidenceRef()).isNull();
    }

    @Test
    void test10_aReceiveRejectedByTheAssetState_neverBinds() {
        String assetId = asset("asset-r3", false); // sin despachar: no se puede recibir
        assertThatThrownBy(() -> service.receiveAsset("cmd-1", assetId, "Centro", "05001", "receiver-1", PHOTO, actor))
                .isInstanceOf(RuntimeException.class);
        neverBound();
        neverAppended();
    }

    @Test
    void test10_aReceiveRejectedForAnUnknownMunicipality_neverBinds() {
        String assetId = asset("asset-r4", true);
        assertThatThrownBy(() -> service.receiveAsset("cmd-1", assetId, "Centro", "99999", "receiver-1", PHOTO, actor))
                .isInstanceOf(RuntimeException.class);
        neverBound();
        neverAppended();
    }

    // ---- register: Camino B (donación en especie) y Camino A (compra) ----

    @Test
    void test6_registerFromDonationWithAPhoto_bindsTheRegistrationStep_toTheDeterministicAssetId() {
        service.registerPhysicalAssetFromDonation("cmd-b1", ORG, "donor-1", "FOOD", BigDecimal.TEN, "KG", "c-1", "Bodega",
                "05001", null, PHOTO, actor);

        String assetId = com.traceability.core.domain.physicalasset.AssetIds.of(ORG, "cmd-b1");
        InOrder order = inOrder(identityPrincipalPort, evidencePort, eventPublisher);
        order.verify(identityPrincipalPort).resolvePrincipal("user1");
        order.verify(evidencePort).bindToAsset(ORG, PHOTO, assetId, EvidenceStep.REGISTERED);
        order.verify(eventPublisher).appendAndOutbox(any(), any(), any(Long.class), any(), any(), anyList(), any(), any());
        assertThat(((AssetRegisteredV5Payload) appended().get(0).payload()).evidenceRef()).isEqualTo(PHOTO);
    }

    @Test
    void test4_registerFromDonationWithoutAPhoto_neverBinds_andWritesNull() {
        service.registerPhysicalAssetFromDonation("cmd-b2", ORG, "donor-1", "FOOD", BigDecimal.TEN, "KG", "c-1", "Bodega",
                "05001", actor);

        neverBound();
        assertThat(((AssetRegisteredV5Payload) appended().get(0).payload()).evidenceRef()).isNull();
    }

    @Test
    void test6_registerFromPurchaseWithAPhoto_bindsTheRegistrationStep() {
        service.registerPhysicalAsset("cmd-a1", FUND, ORG, "FOOD", BigDecimal.TEN, "KG", "c-1", "Bodega", "05001",
                "alloc-1", null, PHOTO, actor);

        String assetId = com.traceability.core.domain.physicalasset.AssetIds.of(ORG, "cmd-a1");
        verify(evidencePort).bindToAsset(eq(ORG), eq(PHOTO), eq(assetId), eq(EvidenceStep.REGISTERED));
        assertThat(((AssetRegisteredV5Payload) appended().get(0).payload()).evidenceRef()).isEqualTo(PHOTO);
    }

    @Test
    void test10_aRegistrationRejectedForAnotherOrganization_neverBinds_inBothPaths() {
        principal("OTHER-ORG", AuthorizationRole.EMPLOYEE);

        assertThatThrownBy(() -> service.registerPhysicalAssetFromDonation("cmd-b3", ORG, "donor-1", "FOOD", BigDecimal.TEN,
                "KG", "c-1", "Bodega", "05001", null, PHOTO, actor)).isInstanceOf(CrossOrganizationAccessException.class);
        assertThatThrownBy(() -> service.registerPhysicalAsset("cmd-a2", FUND, ORG, "FOOD", BigDecimal.TEN, "KG", "c-1",
                "Bodega", "05001", "alloc-1", null, PHOTO, actor)).isInstanceOf(CrossOrganizationAccessException.class);
        neverBound();
        neverAppended();
    }

    @Test
    void test10_aRegistrationRejectedByTheAggregate_neverBinds() {
        assertThatThrownBy(() -> service.registerPhysicalAssetFromDonation("cmd-b4", ORG, "donor-1", "FOOD",
                BigDecimal.valueOf(-1), "KG", "c-1", "Bodega", "05001", null, PHOTO, actor))
                .isInstanceOf(IllegalArgumentException.class);
        neverBound();
        neverAppended();
    }
}
