package com.traceability.app.web.asset;

import com.traceability.app.application.media.MediaPurpose;
import com.traceability.app.application.media.MediaUploadService;
import com.traceability.app.application.media.StoredMedia;
import com.traceability.contracts.authorization.AuthorizationPrincipal;
import com.traceability.contracts.authorization.AuthorizationRole;
import com.traceability.core.application.authorization.CrossOrganizationAccessException;
import com.traceability.core.application.authorization.InsufficientRoleException;
import com.traceability.core.application.authorization.OrganizationBoundaryPolicy;
import com.traceability.core.application.authorization.RoleAuthorizationPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * ADR-051, Tarea 4 (F8; plan T4 §3 y P-T4-4): la subida de evidencia autoriza con las políticas de {@code core}
 * ({@code OrganizationBoundaryPolicy} y {@code RoleAuthorizationPolicy} con {@code DELIVER_ASSET}) <b>antes de leer los
 * bytes</b>, y devuelve {@code "sha256:" + huella}. El {@link MultipartFile} es un doble que cuenta las lecturas
 * (mutación 8).
 */
class DeliveryEvidenceControllerTest {

    private MediaUploadService uploads;
    private DeliveryEvidenceController controller;
    private MultipartFile file;

    @BeforeEach
    void setUp() throws Exception {
        uploads = mock(MediaUploadService.class);
        controller = new DeliveryEvidenceController(uploads, new OrganizationBoundaryPolicy(), new RoleAuthorizationPolicy());
        file = mock(MultipartFile.class);
        when(file.getSize()).thenReturn(10L);
        when(file.getBytes()).thenReturn(new byte[]{1, 2, 3});
    }

    private static AuthorizationPrincipal principal(String org, AuthorizationRole role) {
        return new AuthorizationPrincipal("acct-1", org, Set.of(role), null);
    }

    @Test
    void anEmployeeOfTheOrganization_uploads_andGets201WithThePrefixedFootprint() throws Exception {
        when(uploads.store(MediaPurpose.DELIVERY_EVIDENCE, "org-1", null, "acct-1", new byte[]{1, 2, 3}))
                .thenReturn(new StoredMedia("key", "ab".repeat(32), "image/png", 3, 1, 1));

        ResponseEntity<DeliveryEvidenceController.EvidenceResponse> r =
                controller.upload(principal("org-1", AuthorizationRole.EMPLOYEE), "org-1", file);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(r.getBody().evidenceRef()).isEqualTo("sha256:" + "ab".repeat(32));
    }

    @Test
    void anotherOrganization_isForbidden_withoutReadingTheBytes() throws Exception {
        assertThatThrownBy(() -> controller.upload(principal("org-2", AuthorizationRole.EMPLOYEE), "org-1", file))
                .isInstanceOf(CrossOrganizationAccessException.class);
        verify(file, never()).getBytes();
        verify(file, never()).getInputStream();
        verifyNoInteractions(uploads);
    }

    @Test
    void aRoleThatCannotDeliver_isForbidden_withoutReadingTheBytes() throws Exception {
        assertThatThrownBy(() -> controller.upload(principal("org-1", AuthorizationRole.REPRESENTATIVE), "org-1", file))
                .isInstanceOf(InsufficientRoleException.class);
        verify(file, never()).getBytes();
        verify(file, never()).getInputStream();
        verifyNoInteractions(uploads);
    }

    @Test
    void aFileAboveTheEvidenceLimit_is413_withoutCallingTheUpload() throws Exception {
        when(file.getSize()).thenReturn(MediaPurpose.DELIVERY_EVIDENCE.maxBytes() + 1L);

        assertThatThrownBy(() -> controller.upload(principal("org-1", AuthorizationRole.EMPLOYEE), "org-1", file))
                .isInstanceOf(com.traceability.app.application.media.ImageTooLargeException.class);
        verify(uploads, never()).store(any(), any(), any(), any(), any());
    }
}
