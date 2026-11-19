package com.traceability.app.application.media;

import com.traceability.contracts.media.EvidenceAlreadyBoundException;
import com.traceability.contracts.media.EvidenceNotFoundException;
import com.traceability.contracts.media.EvidenceStep;
import org.junit.jupiter.api.Test;

import static com.traceability.app.application.media.MediaObjectStore.BindResult.BOUND;
import static com.traceability.app.application.media.MediaObjectStore.BindResult.BOUND_TO_OTHER;
import static com.traceability.app.application.media.MediaObjectStore.BindResult.NOT_FOUND;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * ADR-051 Enmienda 2, E2-1 (plan de la Tarea 1, §2.3): cada paso se vincula con el propósito de su foto, y la entrega,
 * que usa el método de tres argumentos, sigue vinculando {@code DELIVERY_EVIDENCE}.
 */
class DeliveryEvidenceBindingStepsTest {

    private static final String SHA = "ab".repeat(32);
    private static final String REF = "sha256:" + SHA;

    private final MediaObjectStore store = mock(MediaObjectStore.class);
    private final DeliveryEvidenceBinding binding = new DeliveryEvidenceBinding(store);

    @Test
    void eachStep_bindsThePhotoOfItsOwnPurpose() {
        when(store.bindEvidence(anyString(), anyString(), anyString(), any(MediaPurpose.class))).thenReturn(BOUND);

        binding.bindToAsset("org-1", REF, "asset-1", EvidenceStep.REGISTERED);
        binding.bindToAsset("org-1", REF, "asset-1", EvidenceStep.DISPATCHED);
        binding.bindToAsset("org-1", REF, "asset-1", EvidenceStep.RECEIVED);

        verify(store).bindEvidence("org-1", SHA, "asset-1", MediaPurpose.REGISTRATION_EVIDENCE);
        verify(store).bindEvidence("org-1", SHA, "asset-1", MediaPurpose.DISPATCH_EVIDENCE);
        verify(store).bindEvidence("org-1", SHA, "asset-1", MediaPurpose.RECEIPT_EVIDENCE);
    }

    @Test
    void theResultsAreTranslatedToThePortExceptions() {
        when(store.bindEvidence("org-1", SHA, "a-other", MediaPurpose.DISPATCH_EVIDENCE)).thenReturn(BOUND_TO_OTHER);
        when(store.bindEvidence("org-1", SHA, "a-none", MediaPurpose.DISPATCH_EVIDENCE)).thenReturn(NOT_FOUND);

        assertThatThrownBy(() -> binding.bindToAsset("org-1", REF, "a-other", EvidenceStep.DISPATCHED))
                .isInstanceOf(EvidenceAlreadyBoundException.class);
        assertThatThrownBy(() -> binding.bindToAsset("org-1", REF, "a-none", EvidenceStep.DISPATCHED))
                .isInstanceOf(EvidenceNotFoundException.class);
    }

    @Test
    void aMalformedReferenceOrNoOrganization_isNotFound_withoutTouchingTheStore() {
        assertThatThrownBy(() -> binding.bindToAsset(null, REF, "a", EvidenceStep.RECEIVED))
                .isInstanceOf(EvidenceNotFoundException.class);
        assertThatThrownBy(() -> binding.bindToAsset("org-1", "abc", "a", EvidenceStep.RECEIVED))
                .isInstanceOf(EvidenceNotFoundException.class);
        verifyNoInteractions(store);
    }

    // la entrega no cambia
    @Test
    void theDelivery_stillBindsDeliveryEvidence_withTheThreeArgumentMethod() {
        when(store.bindEvidence("org-1", SHA, "asset-1")).thenReturn(BOUND);

        binding.bindToAsset("org-1", REF, "asset-1");

        verify(store).bindEvidence("org-1", SHA, "asset-1");
    }
}
