package com.traceability.core.application.command;

import com.traceability.contracts.authorization.AuthorizationPrincipal;
import com.traceability.contracts.authorization.AuthorizationRole;
import com.traceability.contracts.authorization.IdentityPrincipalPort;
import com.traceability.contracts.media.DeliveryEvidencePort;
import com.traceability.contracts.media.EvidenceAlreadyBoundException;
import com.traceability.core.application.authorization.CrossOrganizationAccessException;
import com.traceability.core.application.authorization.InsufficientRoleException;
import com.traceability.core.application.authorization.OrganizationBoundaryPolicy;
import com.traceability.core.application.authorization.RoleAuthorizationPolicy;
import com.traceability.core.application.exception.InvalidEvidenceRefException;
import com.traceability.core.application.port.out.EventStorePort;
import com.traceability.core.application.port.out.ProcessedCommandRepositoryPort;
import com.traceability.core.application.service.TransactionalEventPublisher;
import com.traceability.core.domain.event.HumanActor;
import com.traceability.core.domain.physicalasset.PhysicalAsset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.mockito.stubbing.Answer;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

/**
 * ADR-051, Tarea 4 (F3; plan T4 §3 y §5): la validación del formato de {@code evidenceRef} en
 * {@code PhysicalAssetCommandService.deliverAsset}, antes del reclamo del comando (P-T4-1), y el gancho de
 * {@code transition()} que vincula la foto solo si el agregado generó un evento, después de autorizar y antes de
 * {@code appendAndOutbox} (P-T4-2). Tests T4-6, T4-9, T4-9b y T4-12 en {@code core}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DeliveryEvidenceCommandTest {

    private static final String ORG = "ORG-1";
    private static final String EVIDENCE = "sha256:" + "a1".repeat(32);

    @Mock private CommandRetryTemplate retryTemplate;
    @Mock private ProcessedCommandRepositoryPort processedCommandRepository;
    @Mock private EventStorePort eventStore;
    @Mock private TransactionalEventPublisher eventPublisher;
    @Mock private IdentityPrincipalPort identityPrincipalPort;
    @Mock private DeliveryEvidencePort deliveryEvidence;

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
                new com.traceability.core.support.StubCampaignInKindEligibilityPort(), deliveryEvidence);
    }

    private void principal(String org, AuthorizationRole role) {
        when(identityPrincipalPort.resolvePrincipal("user1"))
                .thenReturn(new AuthorizationPrincipal("user1", org, Set.of(role), null));
    }

    /** Un activo recibido (lo único que admite entregar), o en el estado que se pida. */
    private PhysicalAsset asset(String assetId, boolean received) {
        PhysicalAsset asset = PhysicalAsset.create(assetId, "MEDICINE", BigDecimal.valueOf(100), "BOX", "WH-1", "CUST-1",
                null, assetId, "ALLOC-1", "SRC-1", ORG, "DON", "DON-REF");
        if (received) {
            asset.dispatch("carrier");
            asset.receive("fac", "rec");
        }
        when(eventStore.loadStream(assetId)).thenReturn(asset.getUncommittedEvents());
        return asset;
    }

    // T4-6: un evidenceRef sin el formato sha256: → 400, sin reclamar el comando ni leer el activo (P-T4-1)
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"acta-1", "ev-1", "sha256:", "sha256:" + "A1A1A1A1A1A1A1A1A1A1A1A1A1A1A1A1A1A1A1A1A1A1A1A1A1A1A1A1A1A1A1A1",
            "sha256:abc", "SHA256:a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1",
            "sha256:a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a", " sha256:a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1"})
    void t4_6_anEvidenceRefWithoutTheSha256Format_isRejected_beforeTheClaimAndWithoutReadingTheAsset(String evidenceRef) {
        assertThatThrownBy(() -> service.deliverAsset("cmd-1", "asset-1", "cust-2", "ben-1", "loc-2", evidenceRef,
                Instant.now(), actor)).isInstanceOf(InvalidEvidenceRefException.class);

        verifyNoInteractions(processedCommandRepository, eventStore, eventPublisher, deliveryEvidence, identityPrincipalPort);
    }

    // F3, pasos 1 a 5: cargar, autorizar, deliver en memoria, vincular y después appendAndOutbox
    @Test
    void aValidDelivery_bindsThePhoto_afterAuthorizing_andBeforeAppending() {
        String assetId = UUID.randomUUID().toString();
        asset(assetId, true);
        principal(ORG, AuthorizationRole.EMPLOYEE);

        service.deliverAsset("cmd-1", assetId, "cust-2", "ben-1", "loc-2", EVIDENCE, Instant.now(), actor);

        InOrder order = inOrder(eventStore, identityPrincipalPort, deliveryEvidence, eventPublisher);
        order.verify(eventStore).loadStream(assetId);
        order.verify(identityPrincipalPort).resolvePrincipal("user1");
        order.verify(deliveryEvidence).bindToAsset(ORG, EVIDENCE, assetId);
        order.verify(eventPublisher).appendAndOutbox(any(), any(), any(Long.class), any(), any(), any(), any(), any());
    }

    // El 409 del vínculo deja el activo sin cambios: no se escribe ningún evento
    @Test
    void whenThePhotoIsBoundToAnotherAsset_nothingIsAppended() {
        String assetId = UUID.randomUUID().toString();
        asset(assetId, true);
        principal(ORG, AuthorizationRole.EMPLOYEE);
        doThrow(new EvidenceAlreadyBoundException("bound")).when(deliveryEvidence).bindToAsset(ORG, EVIDENCE, assetId);

        assertThatThrownBy(() -> service.deliverAsset("cmd-1", assetId, "cust-2", "ben-1", "loc-2", EVIDENCE,
                Instant.now(), actor)).isInstanceOf(EvidenceAlreadyBoundException.class);

        verify(eventPublisher, never()).appendAndOutbox(any(), any(), any(Long.class), any(), any(), any(), any(), any());
    }

    // T4-9b: rechazada por permisos (otra organización, rol sin permiso) → bindToAsset nunca se llama
    @Test
    void t4_9b_aDeliveryRejectedForAnotherOrganization_neverBinds() {
        String assetId = UUID.randomUUID().toString();
        asset(assetId, true);
        principal("OTHER-ORG", AuthorizationRole.EMPLOYEE);

        assertThatThrownBy(() -> service.deliverAsset("cmd-1", assetId, "cust-2", "ben-1", "loc-2", EVIDENCE,
                Instant.now(), actor)).isInstanceOf(CrossOrganizationAccessException.class);
        verify(deliveryEvidence, never()).bindToAsset(anyString(), anyString(), anyString());
    }

    @Test
    void t4_9b_aDeliveryRejectedForTheRole_neverBinds() {
        String assetId = UUID.randomUUID().toString();
        asset(assetId, true);
        principal(ORG, AuthorizationRole.REPRESENTATIVE);

        assertThatThrownBy(() -> service.deliverAsset("cmd-1", assetId, "cust-2", "ben-1", "loc-2", EVIDENCE,
                Instant.now(), actor)).isInstanceOf(InsufficientRoleException.class);
        verify(deliveryEvidence, never()).bindToAsset(anyString(), anyString(), anyString());
    }

    // T4-9b: rechazada por el estado del activo (registrado, sin despachar ni recibir) → nunca vincula
    @Test
    void t4_9b_aDeliveryRejectedByTheAssetState_neverBinds() {
        String assetId = UUID.randomUUID().toString();
        asset(assetId, false);
        principal(ORG, AuthorizationRole.EMPLOYEE);

        assertThatThrownBy(() -> service.deliverAsset("cmd-1", assetId, "cust-2", "ben-1", "loc-2", EVIDENCE,
                Instant.now(), actor)).isInstanceOf(RuntimeException.class);
        verify(deliveryEvidence, never()).bindToAsset(anyString(), anyString(), anyString());
        verify(eventPublisher, never()).appendAndOutbox(any(), any(), any(Long.class), any(), any(), any(), any(), any());
    }

    // T4-9b: ya entregado con otros datos → error del dominio y nunca vincula
    @Test
    void t4_9b_anAssetAlreadyDeliveredWithOtherData_neverBinds() {
        String assetId = UUID.randomUUID().toString();
        PhysicalAsset asset = asset(assetId, true);
        asset.deliver("cust-2", "ben-1", "loc-2", "sha256:" + "b2".repeat(32), Instant.parse("2026-10-20T10:00:00Z"));
        when(eventStore.loadStream(assetId)).thenReturn(asset.getUncommittedEvents());
        principal(ORG, AuthorizationRole.EMPLOYEE);

        assertThatThrownBy(() -> service.deliverAsset("cmd-1", assetId, "cust-2", "ben-1", "loc-2", EVIDENCE,
                Instant.now(), actor)).isInstanceOf(RuntimeException.class);
        verify(deliveryEvidence, never()).bindToAsset(anyString(), anyString(), anyString());
    }

    // Redundancia exacta (mismos datos, otro Command-Id): no genera evento, así que no se vuelve a vincular (F3, paso 4)
    @Test
    void anExactRedundancy_generatesNoEvent_andDoesNotBindAgain() {
        String assetId = UUID.randomUUID().toString();
        Instant deliveredAt = Instant.parse("2026-10-20T10:00:00Z");
        PhysicalAsset asset = asset(assetId, true);
        asset.deliver("cust-2", "ben-1", "loc-2", EVIDENCE, deliveredAt);
        when(eventStore.loadStream(assetId)).thenReturn(asset.getUncommittedEvents());
        principal(ORG, AuthorizationRole.EMPLOYEE);

        service.deliverAsset("cmd-2", assetId, "cust-2", "ben-1", "loc-2", EVIDENCE, deliveredAt, actor);

        verify(deliveryEvidence, never()).bindToAsset(anyString(), anyString(), anyString());
    }

    // T4-9: el mismo Command-Id reenviado es un no-op en el reclamo: el vínculo no se repite
    @Test
    void t4_9_theSameCommandIdResent_isANoOp_withoutBindingAgain() {
        when(processedCommandRepository.exists("cmd-1")).thenReturn(true);
        when(processedCommandRepository.findOutcome("cmd-1")).thenReturn(Optional.of("DELIVER_ASSET:asset-1"));

        service.deliverAsset("cmd-1", "asset-1", "cust-2", "ben-1", "loc-2", EVIDENCE, Instant.now(), actor);

        verifyNoInteractions(deliveryEvidence, eventStore, eventPublisher);
    }

    // T4-12: despachar y recibir no llaman al gancho
    @Test
    void t4_12_dispatchAndReceive_neverCallTheHook() {
        String assetId = UUID.randomUUID().toString();
        PhysicalAsset registered = PhysicalAsset.create(assetId, "MEDICINE", BigDecimal.valueOf(100), "BOX", "WH-1",
                "CUST-1", null, assetId, "ALLOC-1", "SRC-1", ORG, "DON", "DON-REF");
        when(eventStore.loadStream(assetId)).thenReturn(registered.getUncommittedEvents());
        principal(ORG, AuthorizationRole.EMPLOYEE);
        service.dispatchAsset("cmd-d", assetId, "carrier", actor);

        registered.dispatch("carrier");
        when(eventStore.loadStream(assetId)).thenReturn(registered.getUncommittedEvents());
        service.receiveAsset("cmd-r", assetId, "fac", "rec", actor);

        verifyNoInteractions(deliveryEvidence);
        verify(eventPublisher, org.mockito.Mockito.times(2))
                .appendAndOutbox(any(), any(), any(Long.class), any(), any(), any(), any(), any());
    }
}
